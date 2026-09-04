package coop.net;

import coop.util.CoopLog;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Asks the household router to forward the coop host port from the Internet, so a host behind an
 * ordinary consumer NAT is reachable without anyone opening a router admin page.
 *
 * <p><strong>Why a state machine and not a few blocking calls.</strong> Everything here would be
 * twenty lines with a socket timeout and a thread. Neither is available: mod-created threads have
 * been observed dying mid-session in this codebase, and the script sandbox refuses {@code java.io},
 * which takes {@code HttpURLConnection} and every stream-based HTTP client with it. So the whole
 * conversation — SSDP discovery, an HTTP GET of the device descriptor, three SOAP calls, or the
 * NAT-PMP datagram exchange — runs as non-blocking NIO driven one slice per campaign frame from
 * {@link #tick(long)}. Timeouts are wall-clock comparisons against the frame's timestamp.
 *
 * <p><strong>Order.</strong> UPnP IGD first (it is what consumer routers overwhelmingly speak),
 * NAT-PMP second when SSDP finds nothing or UPnP refuses. PCP is deliberately not implemented — see
 * {@link CoopNatPmpMessages} for why the extra code buys nothing.
 *
 * <p><strong>Failure is never fatal.</strong> {@link #tick(long)} does not throw; every dead end
 * lands in {@link Result#failureText()} with {@link Result#finished()} true, and the host simply
 * falls back to the manual tiers documented in {@code docs/CONNECTIVITY.md}. Hosting is never
 * blocked on this class succeeding.
 *
 * <p><strong>CGNAT.</strong> If the address the router calls "external" is itself private or in
 * {@code 100.64.0.0/10}, the mapping is real but useless: the ISP is doing a second layer of NAT
 * that no port-mapping protocol can reach through. That verdict is the single most valuable thing
 * this class produces, because it turns an unexplainable "we can't connect" into "use IPv6 or a VPN".
 */
public final class CoopPortMapper {
    /** Which protocol produced the mapping, if any. */
    public enum Tier {
        NONE,
        UPNP,
        NAT_PMP,
        /** Reserved; PCP is documented-not-implemented (see {@link CoopNatPmpMessages}). */
        PCP
    }

    /**
     * Immutable snapshot of what the mapper has established so far.
     *
     * @param gatewayAddress router IP the mapper talked to, {@code ""} if none was found
     * @param gatewayName    friendlyName/modelName from the UPnP descriptor, {@code ""} if unknown
     * @param externalAddress the router's WAN address, {@code ""} if unknown
     * @param externalPort   external port actually mapped, {@code 0} if none
     * @param cgnat          the external address is not globally routable
     * @param failureText    empty on success, else one sentence naming what went wrong
     * @param finished       the mapper has stopped trying (success or failure); renewals continue
     */
    public record Result(Tier tier,
                         String gatewayAddress,
                         String gatewayName,
                         String externalAddress,
                         int externalPort,
                         boolean cgnat,
                         String failureText,
                         boolean finished) {
        public Result {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(gatewayAddress, "gatewayAddress");
            Objects.requireNonNull(gatewayName, "gatewayName");
            Objects.requireNonNull(externalAddress, "externalAddress");
            Objects.requireNonNull(failureText, "failureText");
        }

        /** True when a mapping is in place and the external endpoint is worth sharing. */
        public boolean mapped() {
            return tier != Tier.NONE && externalPort > 0 && failureText.isEmpty();
        }

        /** {@code address:port} the guest should be given, or {@code ""} when there is nothing to share. */
        public String externalEndpoint() {
            if (externalAddress.isEmpty() || externalPort <= 0) {
                return "";
            }
            return externalAddress + ":" + externalPort;
        }
    }

    static final String MAPPING_DESCRIPTION = "Starsector coop";
    static final int LEASE_SECONDS = 3600;
    static final long RENEW_INTERVAL_MILLIS = 30L * 60L * 1000L;

    private static final long SSDP_WINDOW_MILLIS = 3000L;
    private static final long SSDP_SECOND_SEND_MILLIS = 1000L;
    private static final long HTTP_TIMEOUT_MILLIS = 6000L;
    private static final long NATPMP_ATTEMPT_INTERVAL_MILLIS = 750L;
    private static final int NATPMP_ATTEMPTS = 3;
    private static final long SHUTDOWN_BUDGET_MILLIS = 1200L;
    /** Real time the shutdown loop spins on a clock that never moves before it gives up. */
    private static final long SHUTDOWN_FROZEN_CLOCK_MILLIS = 50L;
    /**
     * Ceiling on one HTTP response. A device descriptor is a few KB and a SOAP reply is smaller; the
     * responder is an unauthenticated LAN device, so an unbounded read is an unbounded allocation on
     * the campaign thread (red-team net-50).
     */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    /**
     * Destination the LAN-address probe pretends to talk to. Nothing is sent — connecting a UDP
     * socket only makes the OS pick the source address it would route from, which is the one on the
     * interface that reaches the default gateway.
     */
    private static final String ROUTE_PROBE_TARGET = "1.1.1.1";

    private enum Stage {
        DISABLED,
        SSDP,
        DESCRIPTOR,
        SOAP_EXTERNAL_IP,
        SOAP_ADD,
        SOAP_QUERY_CONFLICT,
        SOAP_DELETE_CONFLICT,
        NATPMP_EXTERNAL,
        NATPMP_MAP,
        ACTIVE,
        FAILED,
        RELEASE_UPNP,
        RELEASE_NATPMP,
        CLOSED
    }

    private final int port;
    private final LongSupplier clock;

    private Stage stage;
    private boolean started;
    private long stageStartMillis;

    // Result fields.
    private Tier tier = Tier.NONE;
    private String gatewayAddress = "";
    private String gatewayName = "";
    private String gatewayFriendlyName = "";
    private String gatewayModelName = "";
    private String externalAddress = "";
    private int externalPort;
    private String failureText = "";
    private boolean finished;
    private boolean cgnatWarned;

    // UPnP state.
    private DatagramChannel ssdpChannel;
    private boolean ssdpStarted;
    private boolean ssdpSecondSent;
    private String descriptorUrl = "";
    private String serviceType = "";
    private String controlUrl = "";
    private String internalClient = "";
    private HttpExchange exchange;
    private String addProtocol = "TCP";
    private int addLeaseSeconds = LEASE_SECONDS;
    private boolean conflictRetried;
    private boolean upnpTcpMapped;
    private boolean upnpUdpMapped;

    // NAT-PMP state.
    private DatagramChannel natPmpChannel;
    private String natPmpGateway = "";
    private int natPmpAttempts;
    private long natPmpLastSendMillis;
    private boolean natPmpMappingUdp = true;
    private boolean natPmpUdpMapped;
    private boolean natPmpTcpMapped;

    // Lifecycle.
    private long nextRenewMillis = Long.MAX_VALUE;
    private boolean renewing;
    /** Full passes of the state machine the last {@link #shutdown()} spent; the B8 evidence. */
    private int shutdownTicks;
    private final Deque<String> releaseQueue = new ArrayDeque<>();
    /** Protocol whose UPnP release is in flight, so a failure clears the right flag. */
    private String releasingProtocol = "";

    /**
     * Test seam: run the state machine without opening any socket. Discovery then always times out,
     * which is exactly the path a unit test needs to drive (SSDP silence -> NAT-PMP -> give up) and
     * the one path that is impossible to reproduce against a real LAN.
     */
    private boolean offline;

    /**
     * Test seam: make every {@link #beginExchange} fail the way an unroutable control URL does. The
     * only way to reproduce a router that stops being reachable mid-session — a laptop changing
     * networks, a VPN coming up — which is the state in which a shutdown used to park the machine.
     */
    private boolean brokenExchanges;

    private CoopPortMapper(int port, LongSupplier clock, boolean enabled) {
        this.port = port;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!enabled) {
            this.stage = Stage.DISABLED;
            this.finished = true;
            this.failureText = "automatic port mapping disabled (" + CoopNetStartupConfig.PORT_MAPPING_PROPERTY + "=off)";
        } else {
            this.stage = Stage.SSDP;
        }
    }

    /**
     * Creates a mapper. Nothing touches the network until the first {@link #tick(long)}, so this is
     * safe to call from anywhere including a constructor.
     *
     * @param enabled {@code false} produces an inert, already-finished mapper (the {@code off} setting)
     * @param clock   wall-clock source, injected so the shutdown release loop and tests can drive time
     */
    public static CoopPortMapper start(int port, boolean enabled, LongSupplier clock) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 1..65535");
        }
        return new CoopPortMapper(port, clock, enabled);
    }

    /** See {@link #offline}. Package-private on purpose: this is a test seam, not an API. */
    static CoopPortMapper startOffline(int port, LongSupplier clock) {
        CoopPortMapper mapper = start(port, true, clock);
        mapper.offline = true;
        return mapper;
    }

    /**
     * Test seam: skip SSDP and start straight from a known device-descriptor URL. Lets the SOAP half
     * of the conversation be driven end to end against a stub IGD on loopback, which is the only way
     * to test it on a LAN whose router does not speak UPnP at all. Also sets {@link #offline}, so a
     * UPnP failure in a test falls through to NAT-PMP without ever putting a packet on the real LAN.
     */
    static CoopPortMapper startFromDescriptor(int port, LongSupplier clock, String descriptorUrl) {
        CoopPortMapper mapper = start(port, true, clock);
        mapper.offline = true;
        mapper.descriptorUrl = descriptorUrl;
        mapper.gatewayAddress = CoopHttpMessages.parseUrl(descriptorUrl).host();
        mapper.beginExchange(Stage.DESCRIPTOR, descriptorUrl, CoopUpnpSoap.httpGet(descriptorUrl),
                clock.getAsLong());
        return mapper;
    }

    /** See {@link #brokenExchanges}. Package-private on purpose: this is a test seam, not an API. */
    void breakExchanges() {
        brokenExchanges = true;
    }

    /** UPnP {@code friendlyName} of the gateway, {@code ""} when unknown. */
    public String gatewayFriendlyName() {
        return gatewayFriendlyName;
    }

    /** UPnP {@code modelName} of the gateway, {@code ""} when unknown. */
    public String gatewayModelName() {
        return gatewayModelName;
    }

    /** Immutable snapshot of the current state; safe to call every frame. */
    public Result result() {
        return new Result(tier, gatewayAddress, gatewayName, externalAddress, externalPort,
                isUnroutableExternalAddress(externalAddress), failureText, finished);
    }

    /**
     * How many times the last {@link #shutdown()} actually ran the state machine (red-team B8).
     * Package-private evidence: the loop is a busy wait with no thread to hand off to, so the only
     * thing that can be asserted about it is that it does not run the machine thousands of times
     * against a clock that has not moved.
     */
    int shutdownTicks() {
        return shutdownTicks;
    }

    /** The internal port the mapper was asked to expose. */
    public int port() {
        return port;
    }

    /**
     * One slice of work. Call every campaign frame. Never throws: any failure is folded into
     * {@link Result#failureText()}.
     */
    public void tick(long nowMillis) {
        try {
            if (!started) {
                started = true;
                stageStartMillis = nowMillis;
            }
            switch (stage) {
                case SSDP -> tickSsdp(nowMillis);
                case DESCRIPTOR -> tickDescriptor(nowMillis);
                case SOAP_EXTERNAL_IP -> tickExternalIp(nowMillis);
                case SOAP_ADD -> tickAddPortMapping(nowMillis);
                case SOAP_QUERY_CONFLICT -> tickQueryConflict(nowMillis);
                case SOAP_DELETE_CONFLICT -> tickDeleteConflict(nowMillis);
                case NATPMP_EXTERNAL -> tickNatPmpExternal(nowMillis);
                case NATPMP_MAP -> tickNatPmpMap(nowMillis);
                case ACTIVE -> tickActive(nowMillis);
                case RELEASE_UPNP -> tickReleaseUpnp(nowMillis);
                case RELEASE_NATPMP -> tickReleaseNatPmp(nowMillis);
                default -> {
                    // DISABLED, FAILED and CLOSED have no work left.
                }
            }
        } catch (Throwable throwable) {
            // A port mapper must never be able to take the campaign frame down with it. Routed
            // through failMapping rather than fail (red-team B5): thrown during a renewal, fail()
            // marked a mapping that is still live in the router as failed and stopped every future
            // renewal, so one transient socket error 30 minutes in ended port mapping for the session.
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper aborted: " + throwable, throwable);
            failMapping(nowMillis, "port mapping aborted: " + describe(throwable));
        }
    }

    /**
     * Releases the mapping and closes every socket. Queues the release calls and drives them with a
     * bounded busy loop against the injected clock — bounded because there is no thread to wait on
     * and the game is shutting down, and busy because sleeping is not allowed here either.
     */
    public void shutdown() {
        try {
            if (stage == Stage.CLOSED) {
                return;
            }
            closeExchange();
            // A renewal in flight is over: without this, a release that throws lands in failMapping,
            // which sees renewing and parks the machine back in ACTIVE — where the loop below reads
            // "still working" and spins to its deadline without ever sending the rest of the
            // releases (red-team net-27).
            renewing = false;
            beginNextRelease();

            // Red-team B8: the state machine is driven by wall-clock comparisons, so ticking it twice
            // inside one millisecond re-runs every timeout check against a clock that has not moved
            // and cannot change the outcome. The old loop did exactly that: it burned 200,000 full
            // passes of the machine and then gave up long before the 1.2 s budget it was written to
            // spend. Now at most one tick per millisecond runs (~1200 in the budget).
            //
            // The "clock is frozen, stop spinning" guard is measured with nanoTime rather than in
            // spins: a spin count is a measure of CPU speed, not of time, and on a Windows clock with
            // 15.6 ms granularity 200,000 clock reads fit inside a single tick of it — so the guard
            // fired between two legitimate ticks and abandoned the release half-sent. nanoTime is
            // independent of the injected clock, which is exactly what is needed to tell "this clock
            // does not move" (a test's fixed clock) from "this clock is coarse" (Windows).
            long deadline = clock.getAsLong() + SHUTDOWN_BUDGET_MILLIS;
            long lastTickMillis = Long.MIN_VALUE;
            long lastAdvanceNanos = System.nanoTime();
            shutdownTicks = 0;
            while (stage != Stage.CLOSED && stage != Stage.FAILED) {
                long now = clock.getAsLong();
                if (now > deadline) {
                    break;
                }
                if (now == lastTickMillis) {
                    if (System.nanoTime() - lastAdvanceNanos
                            > SHUTDOWN_FROZEN_CLOCK_MILLIS * 1_000_000L) {
                        break;
                    }
                    continue;
                }
                lastAdvanceNanos = System.nanoTime();
                lastTickMillis = now;
                shutdownTicks++;
                tick(now);
            }
            if (stage != Stage.CLOSED && stage != Stage.FAILED) {
                CoopLog.warn(CoopPortMapper.class,
                        "Coop port mapper shutdown release did not complete in "
                                + SHUTDOWN_BUDGET_MILLIS + " ms; the router lease (" + LEASE_SECONDS
                                + " s) will expire on its own");
            }
        } catch (Throwable throwable) {
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper shutdown failed: " + throwable, throwable);
        } finally {
            closeQuietly(ssdpChannel);
            ssdpChannel = null;
            closeQuietly(natPmpChannel);
            natPmpChannel = null;
            closeExchange();
            stage = Stage.CLOSED;
        }
    }

    // ---------------------------------------------------------------- UPnP: SSDP discovery

    private void tickSsdp(long now) throws Exception {
        if (!ssdpStarted) {
            ssdpStarted = true;
            stageStartMillis = now;
            if (!offline) {
                ssdpChannel = DatagramChannel.open(StandardProtocolFamily.INET);
                ssdpChannel.configureBlocking(false);
                ssdpChannel.bind(new InetSocketAddress(0));
                selectMulticastInterface(ssdpChannel);
                sendSsdp(CoopSsdpMessages.ST_IGD_V1);
            }
        }
        if (!ssdpSecondSent && now - stageStartMillis >= SSDP_SECOND_SEND_MILLIS) {
            ssdpSecondSent = true;
            if (!offline) {
                sendSsdp(CoopSsdpMessages.ST_IGD_V2);
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(4096);
        while (ssdpChannel != null) {
            buffer.clear();
            SocketAddress from = ssdpChannel.receive(buffer);
            if (from == null) {
                break;
            }
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            Map<String, String> headers = CoopSsdpMessages.parseHeaders(text);
            if (!CoopSsdpMessages.isInternetGatewayDevice(headers)) {
                continue;
            }
            String location = CoopSsdpMessages.location(headers);
            if (location == null) {
                continue;
            }
            try {
                descriptorUrl = location;
                gatewayAddress = CoopHttpMessages.parseUrl(location).host();
            } catch (IllegalArgumentException ex) {
                continue; // Unusable LOCATION; keep listening for another device.
            }
            closeQuietly(ssdpChannel);
            ssdpChannel = null;
            CoopLog.info(CoopPortMapper.class, "Coop port mapper: UPnP gateway announced at " + descriptorUrl);
            beginExchange(Stage.DESCRIPTOR, descriptorUrl, CoopUpnpSoap.httpGet(descriptorUrl), now);
            return;
        }

        if (now - stageStartMillis >= SSDP_WINDOW_MILLIS) {
            closeQuietly(ssdpChannel);
            ssdpChannel = null;
            CoopLog.info(CoopPortMapper.class,
                    "Coop port mapper: no UPnP gateway answered in " + SSDP_WINDOW_MILLIS + " ms; trying NAT-PMP");
            beginNatPmp(now, "");
        }
    }

    private void sendSsdp(String searchTarget) throws Exception {
        ByteBuffer request = ByteBuffer.wrap(CoopSsdpMessages.searchRequest(searchTarget));
        ssdpChannel.send(request, new InetSocketAddress(
                InetAddress.getByName(CoopSsdpMessages.MULTICAST_ADDRESS), CoopSsdpMessages.MULTICAST_PORT));
    }

    /**
     * Pins the multicast send to the interface that carries the machine's LAN address. On a host with
     * a VPN adapter or Hyper-V switch the OS default can be an interface with no router on it.
     */
    private void selectMulticastInterface(DatagramChannel channel) {
        try {
            String local = localLanIpv4();
            if (local.isEmpty()) {
                return;
            }
            NetworkInterface nic = NetworkInterface.getByInetAddress(InetAddress.getByName(local));
            if (nic != null) {
                channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, nic);
            }
        } catch (Exception ignored) {
            // Best effort: the OS default route is usually right anyway.
        }
    }

    // ---------------------------------------------------------------- UPnP: descriptor + SOAP

    private void tickDescriptor(long now) {
        exchange.tick(now);
        if (!exchange.isSettled()) {
            return;
        }
        if (exchange.isFailed()) {
            beginNatPmp(now, "UPnP descriptor fetch failed: " + exchange.failure());
            return;
        }
        CoopHttpMessages.Response response = exchange.response();
        if (response.statusCode() != 200) {
            beginNatPmp(now, "UPnP descriptor fetch returned HTTP " + response.statusCode());
            return;
        }
        internalClient = exchange.localAddress();
        CoopUpnpDescriptor.Descriptor descriptor;
        try {
            descriptor = CoopUpnpDescriptor.parse(response.body(), descriptorUrl);
        } catch (IllegalArgumentException ex) {
            // A URLBase or controlURL that is not an http:// IP literal (a name host, an https URL).
            // That is a dead end for UPnP like any other, and NAT-PMP deserves its turn: letting the
            // exception escape into tick()'s catch skipped the fallback entirely (red-team net-24).
            closeExchange();
            beginNatPmp(now, "UPnP descriptor unusable: " + describe(ex));
            return;
        }
        gatewayFriendlyName = descriptor.friendlyName();
        gatewayModelName = descriptor.modelName();
        gatewayName = descriptor.displayName();
        closeExchange();

        if (descriptor.service() == null) {
            beginNatPmp(now, "UPnP gateway " + quoted(gatewayName) + " exposes no WAN connection service");
            return;
        }
        serviceType = descriptor.service().serviceType();
        controlUrl = descriptor.service().controlUrl();
        CoopLog.info(CoopPortMapper.class, "Coop port mapper: gateway " + quoted(gatewayName)
                + " service " + serviceType + " control " + controlUrl);

        byte[] request;
        try {
            request = CoopUpnpSoap.httpRequest(controlUrl, serviceType, "GetExternalIPAddress",
                    CoopUpnpSoap.getExternalIpAddressBody(serviceType));
        } catch (IllegalArgumentException ex) {
            beginNatPmp(now, "UPnP control URL unusable: " + describe(ex));
            return;
        }
        beginExchange(Stage.SOAP_EXTERNAL_IP, controlUrl, request, now);
    }

    private void tickExternalIp(long now) {
        exchange.tick(now);
        if (!exchange.isSettled()) {
            return;
        }
        if (exchange.isFailed()) {
            CoopLog.warn(CoopPortMapper.class,
                    "Coop port mapper: GetExternalIPAddress failed (" + exchange.failure() + "); mapping anyway");
        } else {
            CoopHttpMessages.Response response = exchange.response();
            String address = CoopUpnpSoap.externalIpAddress(response.body());
            if (response.statusCode() == 200 && address != null && !address.isEmpty()) {
                if (isUnknownExternalAddress(address)) {
                    // What miniupnpd answers while the WAN link is down or the box is bridged. It is
                    // not an address; sharing it hands the guest an endpoint that cannot work, and
                    // "CGNAT: no, 0.0.0.0 is a public address" is a lie on top (red-team net-11).
                    CoopLog.warn(CoopPortMapper.class, "Coop port mapper: the router reports external"
                            + " address " + address + " -- its WAN link is probably down; mapping"
                            + " anyway, but there is no endpoint to share");
                } else {
                    externalAddress = address;
                }
            } else {
                CoopLog.warn(CoopPortMapper.class, "Coop port mapper: GetExternalIPAddress returned HTTP "
                        + response.statusCode() + " " + soapErrorText(response.body()));
            }
        }
        closeExchange();
        addProtocol = "TCP";
        addLeaseSeconds = LEASE_SECONDS;
        conflictRetried = false;
        beginAddPortMapping(now);
    }

    private void beginAddPortMapping(long now) {
        beginExchange(Stage.SOAP_ADD, controlUrl,
                CoopUpnpSoap.httpRequest(controlUrl, serviceType, "AddPortMapping",
                        CoopUpnpSoap.addPortMappingBody(serviceType, addProtocol, port, port,
                                internalClient, MAPPING_DESCRIPTION, addLeaseSeconds)),
                now);
    }

    private void tickAddPortMapping(long now) {
        exchange.tick(now);
        if (!exchange.isSettled()) {
            return;
        }
        if (exchange.isFailed()) {
            String reason = "UPnP AddPortMapping " + addProtocol + " failed: " + exchange.failure();
            closeExchange();
            finishMappingFailure(now, reason);
            return;
        }
        CoopHttpMessages.Response response = exchange.response();
        closeExchange();

        if (response.statusCode() == 200) {
            markUpnpMapped(addProtocol);
            if (addProtocol.equals("TCP")) {
                addProtocol = "UDP";
                conflictRetried = false;
                beginAddPortMapping(now);
            } else {
                succeedUpnp(now);
            }
            return;
        }

        int errorCode = CoopUpnpSoap.errorCode(response.body());
        if (errorCode == CoopUpnpSoap.ERROR_ONLY_PERMANENT_LEASES && addLeaseSeconds != 0) {
            // 725: the router refuses timed leases outright. Retry permanent; shutdown still deletes it.
            CoopLog.info(CoopPortMapper.class,
                    "Coop port mapper: router only supports permanent leases; retrying " + addProtocol + " with lease 0");
            addLeaseSeconds = 0;
            beginAddPortMapping(now);
            return;
        }
        if (errorCode == CoopUpnpSoap.ERROR_CONFLICT_IN_MAPPING_ENTRY && !conflictRetried) {
            // 718: something already owns that external port — most often our own mapping from a
            // crashed session, whose lease has not expired. Ask the router whose it is first: the
            // delete is keyed on the external port alone, so doing it blind evicts whichever machine
            // on the LAN holds the port, and two coop hosts then steal it from each other on every
            // renewal (red-team net-18).
            conflictRetried = true;
            CoopLog.info(CoopPortMapper.class, "Coop port mapper: external port " + port + "/" + addProtocol
                    + " already mapped; asking the router who owns it");
            beginExchange(Stage.SOAP_QUERY_CONFLICT, controlUrl,
                    CoopUpnpSoap.httpRequest(controlUrl, serviceType, "GetSpecificPortMappingEntry",
                            CoopUpnpSoap.getSpecificPortMappingEntryBody(serviceType, addProtocol, port)),
                    now);
            return;
        }

        String reason = "UPnP AddPortMapping " + addProtocol + " refused: HTTP " + response.statusCode()
                + " " + soapErrorText(response.body());
        finishMappingFailure(now, reason);
    }

    /**
     * Reads the {@code GetSpecificPortMappingEntry} answer and decides whether the entry blocking us
     * is ours to delete. Ownership is the internal client the router reports, not the description:
     * a second coop host on the same LAN writes the same description, and that is precisely the case
     * that must not end in a delete.
     */
    private void tickQueryConflict(long now) {
        exchange.tick(now);
        if (!exchange.isSettled()) {
            return;
        }
        String owner = "";
        String description = "";
        boolean answered = false;
        if (!exchange.isFailed()) {
            CoopHttpMessages.Response response = exchange.response();
            if (response.statusCode() == 200) {
                answered = true;
                owner = orEmpty(CoopUpnpSoap.internalClient(response.body()));
                description = orEmpty(CoopUpnpSoap.portMappingDescription(response.body()));
            }
        }
        closeExchange();

        if (answered && !owner.isEmpty() && owner.equals(internalClient)) {
            CoopLog.info(CoopPortMapper.class, "Coop port mapper: external port " + port + "/" + addProtocol
                    + " is our own stale entry; deleting it and retrying");
            beginExchange(Stage.SOAP_DELETE_CONFLICT, controlUrl,
                    CoopUpnpSoap.httpRequest(controlUrl, serviceType, "DeletePortMapping",
                            CoopUpnpSoap.deletePortMappingBody(serviceType, addProtocol, port)),
                    now);
            return;
        }
        String held = owner.isEmpty()
                ? (answered ? "another device" : "another device (the router would not say which)")
                : owner + (description.isEmpty() ? "" : " " + quoted(description));
        finishMappingFailure(now, "UPnP external port " + port + "/" + addProtocol
                + " is already mapped to " + held + "; set " + CoopNetStartupConfig.HOST_PORT_PROPERTY
                + " to a free port");
    }

    private void tickDeleteConflict(long now) {
        exchange.tick(now);
        if (!exchange.isSettled()) {
            return;
        }
        // Whatever the delete answered, the only useful next move is to try the add once more.
        closeExchange();
        beginAddPortMapping(now);
    }

    private void succeedUpnp(long now) {
        tier = Tier.UPNP;
        externalPort = port;
        failureText = "";
        finished = true;
        renewing = false;
        stage = Stage.ACTIVE;
        nextRenewMillis = now + RENEW_INTERVAL_MILLIS;
        logSuccess();
    }

    private void markUpnpMapped(String protocol) {
        if (protocol.equals("TCP")) {
            upnpTcpMapped = true;
        } else {
            upnpUdpMapped = true;
        }
    }

    /**
     * A UPnP mapping attempt died. During a renewal the existing mapping is still good, so log and
     * reschedule; on the first attempt, fall through to NAT-PMP before giving up.
     */
    private void finishMappingFailure(long now, String reason) {
        if (renewing) {
            renewing = false;
            stage = Stage.ACTIVE;
            nextRenewMillis = now + RENEW_INTERVAL_MILLIS;
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper: lease renewal failed (" + reason
                    + "); the existing mapping stands until it expires");
            return;
        }
        beginNatPmp(now, reason);
    }

    // ---------------------------------------------------------------- NAT-PMP

    /**
     * @param upnpFailure why UPnP did not work, carried into the final failure text so the log names
     *                    the first cause rather than only the last
     */
    private void beginNatPmp(long now, String upnpFailure) {
        closeExchange();
        if (!upnpFailure.isEmpty()) {
            failureText = upnpFailure;
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper: " + upnpFailure);
        }
        natPmpGateway = gatewayAddress.isEmpty() ? guessDefaultGateway(localLanIpv4()) : gatewayAddress;
        if (natPmpGateway.isEmpty()) {
            fail(combineFailure(failureText, "no gateway address to try NAT-PMP against"));
            return;
        }
        if (gatewayAddress.isEmpty()) {
            gatewayAddress = natPmpGateway;
        }
        natPmpAttempts = 0;
        natPmpLastSendMillis = 0L;
        stage = Stage.NATPMP_EXTERNAL;
        stageStartMillis = now;
    }

    private void tickNatPmpExternal(long now) throws Exception {
        openNatPmpChannel();
        if (!sendNatPmpIfDue(now, CoopNatPmpMessages.externalAddressRequest())) {
            fail(combineFailure(failureText, "NAT-PMP gateway " + natPmpGateway + " did not answer"));
            return;
        }
        byte[] datagram = receiveNatPmp();
        if (datagram == null) {
            return;
        }
        CoopNatPmpMessages.ExternalAddressResponse response;
        try {
            response = CoopNatPmpMessages.parseExternalAddress(datagram, datagram.length);
        } catch (IllegalArgumentException ex) {
            return; // Not the response we are waiting for; keep listening until the attempts run out.
        }
        if (!response.success()) {
            fail(combineFailure(failureText, "NAT-PMP refused: "
                    + CoopNatPmpMessages.describeResult(response.resultCode())));
            return;
        }
        if (isUnknownExternalAddress(response.address())) {
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper: NAT-PMP reports external address "
                    + response.address() + " -- the gateway has no WAN address; mapping anyway, but"
                    + " there is no endpoint to share");
        } else {
            externalAddress = response.address();
        }
        natPmpAttempts = 0;
        natPmpLastSendMillis = 0L;
        natPmpMappingUdp = true;
        stage = Stage.NATPMP_MAP;
    }

    private void tickNatPmpMap(long now) throws Exception {
        openNatPmpChannel();
        byte[] request = CoopNatPmpMessages.mapRequest(natPmpMappingUdp, port, port, LEASE_SECONDS);
        if (!sendNatPmpIfDue(now, request)) {
            failMapping(now, combineFailure(failureText, "NAT-PMP mapping request timed out"));
            return;
        }
        byte[] datagram = receiveNatPmp();
        if (datagram == null) {
            return;
        }
        CoopNatPmpMessages.MapResponse response;
        try {
            response = CoopNatPmpMessages.parseMap(datagram, datagram.length);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (response.udp() != natPmpMappingUdp) {
            return; // Response for the other protocol; ignore and wait for ours.
        }
        if (!response.success()) {
            failMapping(now, combineFailure(failureText, "NAT-PMP mapping refused: "
                    + CoopNatPmpMessages.describeResult(response.resultCode())));
            return;
        }
        if (natPmpMappingUdp) {
            natPmpUdpMapped = true;
            externalPort = response.externalPort();
            natPmpMappingUdp = false;
            natPmpAttempts = 0;
            natPmpLastSendMillis = 0L;
            return;
        }
        natPmpTcpMapped = true;
        if (response.externalPort() != externalPort) {
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper: NAT-PMP assigned different external ports"
                    + " (UDP " + externalPort + ", TCP " + response.externalPort() + "); sharing the TCP one");
            externalPort = response.externalPort();
        }
        tier = Tier.NAT_PMP;
        failureText = "";
        finished = true;
        renewing = false;
        stage = Stage.ACTIVE;
        nextRenewMillis = now + RENEW_INTERVAL_MILLIS;
        logSuccess();
    }

    private void openNatPmpChannel() throws Exception {
        if (natPmpChannel == null && !offline) {
            natPmpChannel = DatagramChannel.open(StandardProtocolFamily.INET);
            natPmpChannel.configureBlocking(false);
            natPmpChannel.bind(new InetSocketAddress(0));
        }
    }

    /** @return {@code false} once the retry budget is exhausted */
    private boolean sendNatPmpIfDue(long now, byte[] request) throws Exception {
        if (natPmpLastSendMillis != 0L && now - natPmpLastSendMillis < NATPMP_ATTEMPT_INTERVAL_MILLIS) {
            return true;
        }
        if (natPmpAttempts >= NATPMP_ATTEMPTS) {
            return false;
        }
        natPmpAttempts++;
        natPmpLastSendMillis = now;
        if (natPmpChannel != null) {
            natPmpChannel.send(ByteBuffer.wrap(request),
                    new InetSocketAddress(InetAddress.getByName(natPmpGateway), CoopNatPmpMessages.PORT));
        }
        return true;
    }

    private byte[] receiveNatPmp() throws Exception {
        if (natPmpChannel == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(64);
        SocketAddress from = natPmpChannel.receive(buffer);
        if (from == null) {
            return null;
        }
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    // ---------------------------------------------------------------- Steady state + release

    private void tickActive(long now) {
        if (now < nextRenewMillis) {
            return;
        }
        renewing = true;
        nextRenewMillis = now + RENEW_INTERVAL_MILLIS;
        if (tier == Tier.UPNP && !controlUrl.isEmpty()) {
            addProtocol = "TCP";
            conflictRetried = false;
            beginAddPortMapping(now);
        } else if (tier == Tier.NAT_PMP) {
            natPmpAttempts = 0;
            natPmpLastSendMillis = 0L;
            natPmpMappingUdp = true;
            stage = Stage.NATPMP_MAP;
        } else {
            renewing = false;
        }
    }

    /**
     * Picks the next release stage from the per-protocol mapped flags. Keyed on those and not on
     * {@link #tier}, which is only set once <em>both</em> protocols went in: a TCP mapping that
     * succeeded before the UDP one was refused used to be left in the router forever, and forever is
     * literal when the 725 path made it a permanent lease (red-team net-25). Chained, because a UPnP
     * attempt that half-succeeded and then fell through to NAT-PMP leaves entries in both protocols.
     */
    private void beginNextRelease() {
        releaseQueue.clear();
        releasingProtocol = "";
        if ((upnpTcpMapped || upnpUdpMapped) && !controlUrl.isEmpty() && !serviceType.isEmpty()) {
            if (upnpTcpMapped) {
                releaseQueue.add("TCP");
            }
            if (upnpUdpMapped) {
                releaseQueue.add("UDP");
            }
            stage = Stage.RELEASE_UPNP;
            return;
        }
        if ((natPmpUdpMapped || natPmpTcpMapped) && !natPmpGateway.isEmpty()) {
            if (natPmpUdpMapped) {
                releaseQueue.add("UDP");
            }
            if (natPmpTcpMapped) {
                releaseQueue.add("TCP");
            }
            natPmpAttempts = 0;
            natPmpLastSendMillis = 0L;
            stage = Stage.RELEASE_NATPMP;
            return;
        }
        stage = Stage.CLOSED;
    }

    /** Marks a protocol as no longer ours to release, whether the release worked or not. */
    private void markReleased(boolean upnp, String protocol) {
        if (upnp) {
            if (protocol.equals("TCP")) {
                upnpTcpMapped = false;
            } else {
                upnpUdpMapped = false;
            }
        } else if (protocol.equals("TCP")) {
            natPmpTcpMapped = false;
        } else {
            natPmpUdpMapped = false;
        }
    }

    private void tickReleaseUpnp(long now) {
        if (exchange == null) {
            String protocol = releaseQueue.poll();
            if (protocol == null) {
                beginNextRelease();
                return;
            }
            // Cleared before the call, not after: a release is best effort, and a second attempt at
            // one the router already refused would only spend the shutdown budget.
            markReleased(true, protocol);
            releasingProtocol = protocol;
            beginExchange(Stage.RELEASE_UPNP, controlUrl,
                    CoopUpnpSoap.httpRequest(controlUrl, serviceType, "DeletePortMapping",
                            CoopUpnpSoap.deletePortMappingBody(serviceType, protocol, port)),
                    now);
            return;
        }
        exchange.tick(now);
        if (exchange.isSettled()) {
            releasingProtocol = "";
            closeExchange();
            if (releaseQueue.isEmpty()) {
                beginNextRelease();
            }
        }
    }

    private void tickReleaseNatPmp(long now) throws Exception {
        String protocol = releaseQueue.peek();
        if (protocol == null) {
            beginNextRelease();
            return;
        }
        openNatPmpChannel();
        byte[] request = CoopNatPmpMessages.releaseRequest(protocol.equals("UDP"), port);
        if (!sendNatPmpIfDue(now, request)) {
            // Best effort only: an unreleased NAT-PMP mapping expires with its lifetime.
            finishNatPmpRelease(protocol);
            return;
        }
        byte[] datagram = receiveNatPmp();
        if (datagram == null) {
            return;
        }
        finishNatPmpRelease(protocol);
    }

    private void finishNatPmpRelease(String protocol) {
        releaseQueue.poll();
        markReleased(false, protocol);
        natPmpAttempts = 0;
        natPmpLastSendMillis = 0L;
        if (releaseQueue.isEmpty()) {
            beginNextRelease();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void beginExchange(Stage nextStage, String url, byte[] request, long now) {
        try {
            closeExchange();
            if (brokenExchanges) {
                throw new java.net.ConnectException("test seam: the control URL is unreachable");
            }
            exchange = new HttpExchange(url, request, now, HTTP_TIMEOUT_MILLIS);
            stage = nextStage;
            stageStartMillis = now;
        } catch (Exception ex) {
            // Renewal-aware for the reason on tick()'s catch (red-team B5): a router that refuses one
            // connection during a renewal has not invalidated the mapping it already holds.
            failMapping(now, "cannot reach " + url + ": " + describe(ex));
        }
    }

    private void closeExchange() {
        if (exchange != null) {
            exchange.close();
            exchange = null;
        }
    }

    /** Mapping attempt failed: during a renewal the old mapping stands, otherwise this is the end. */
    private void failMapping(long now, String reason) {
        if (stage == Stage.RELEASE_UPNP || stage == Stage.RELEASE_NATPMP) {
            // Shutting down. A release that cannot even be sent is not a mapping failure and must
            // not re-enter ACTIVE or FAILED: both leave the remaining releases unsent, and ACTIVE
            // also spins the shutdown loop to its deadline (red-team net-27).
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper: release of " + port + "/"
                    + (releasingProtocol.isEmpty() ? "?" : releasingProtocol) + " failed (" + reason
                    + "); the router lease expires on its own");
            closeExchange();
            if (stage == Stage.RELEASE_NATPMP) {
                String protocol = releaseQueue.poll();
                if (protocol != null) {
                    markReleased(false, protocol);
                }
            }
            releasingProtocol = "";
            beginNextRelease();
            return;
        }
        if (renewing) {
            renewing = false;
            stage = Stage.ACTIVE;
            nextRenewMillis = now + RENEW_INTERVAL_MILLIS;
            CoopLog.warn(CoopPortMapper.class, "Coop port mapper: lease renewal failed (" + reason
                    + "); the existing mapping stands until it expires");
            return;
        }
        fail(reason);
    }

    private void fail(String reason) {
        closeExchange();
        closeQuietly(ssdpChannel);
        ssdpChannel = null;
        closeQuietly(natPmpChannel);
        natPmpChannel = null;
        failureText = reason;
        finished = true;
        if (stage != Stage.CLOSED) {
            stage = Stage.FAILED;
        }
        CoopLog.warn(CoopPortMapper.class, "Coop port mapper gave up: " + reason
                + " -- host the session on a manual port forward, IPv6, or a VPN (see docs/CONNECTIVITY.md)");
    }

    private void logSuccess() {
        CoopLog.info(CoopPortMapper.class, "Coop port mapper: mapped " + externalAddress + ":" + externalPort
                + " (TCP+UDP) via " + tier + " on gateway " + gatewayAddress
                + (gatewayName.isEmpty() ? "" : " " + quoted(gatewayName))
                + ", lease " + LEASE_SECONDS + " s");
        if (isUnroutableExternalAddress(externalAddress) && !cgnatWarned) {
            cgnatWarned = true;
            CoopLog.warn(CoopPortMapper.class, "CGNAT/double NAT: direct IPv4 impossible; use IPv6 or a VPN"
                    + " (see docs/CONNECTIVITY.md). The router mapped " + externalAddress
                    + ", which is not a public address -- something upstream is doing a second layer of NAT.");
        }
    }

    private static String combineFailure(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        return first + "; " + second;
    }

    private static String soapErrorText(String body) {
        int code = CoopUpnpSoap.errorCode(body);
        if (code < 0) {
            return "(no UPnPError in body)";
        }
        String description = CoopUpnpSoap.errorDescription(body);
        return "UPnPError " + code + (description.isEmpty() ? "" : " " + description);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * True for the "I do not have one" answers a gateway gives for its external address: the
     * unspecified address, or anything else in {@code 0.0.0.0/8}. Storing one of these publishes an
     * endpoint that cannot work and reports it as a public address (red-team net-11).
     */
    static boolean isUnknownExternalAddress(String address) {
        if (address == null) {
            return true;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        int firstDot = trimmed.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        try {
            return Integer.parseInt(trimmed.substring(0, firstDot)) == 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /**
     * Starts a non-blocking connect, closing the channel if that throws instead of leaking the
     * descriptor. Package-private so the failure branch can be tested with an unresolved address,
     * which every platform rejects the same way.
     */
    static void connectNonBlocking(SocketChannel channel, InetSocketAddress target) throws Exception {
        try {
            channel.configureBlocking(false);
            channel.connect(target);
        } catch (Exception ex) {
            closeQuietly(channel);
            throw ex;
        }
    }

    private static void closeQuietly(java.nio.channels.Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ignored) {
            // Closing is best effort.
        }
    }

    /**
     * True when an address cannot be reached from the Internet: RFC 1918 private space, the
     * {@code 100.64.0.0/10} carrier-grade NAT block, link-local, or loopback. An "external" address
     * in any of these means a second NAT sits upstream of the router.
     */
    public static boolean isUnroutableExternalAddress(String address) {
        if (address == null) {
            return false;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        if (octets[0] == 0 || octets[0] == 10 || octets[0] == 127) {
            // 0.0.0.0/8 is "this network": a router that answers with one has no WAN address at all.
            return true;
        }
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return true;
        }
        if (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127) {
            return true;
        }
        return octets[0] == 169 && octets[1] == 254;
    }

    /**
     * The default-gateway guess NAT-PMP needs when SSDP produced no {@code LOCATION}: the LAN
     * address with its last octet replaced by 1.
     *
     * <p>This is a guess, not a lookup. The JDK exposes no routing table, and reading one would
     * need process execution or file access — both blocked in the sandbox. {@code x.y.z.1} is the
     * near-universal consumer-router convention; when it is wrong the NAT-PMP probe simply times
     * out after ~2.3 s and the mapper reports failure, which costs nothing but the wait.
     */
    public static String guessDefaultGateway(String localIpv4) {
        if (localIpv4 == null) {
            return "";
        }
        String[] parts = localIpv4.trim().split("\\.");
        if (parts.length != 4) {
            return "";
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".1";
    }

    /**
     * The machine's LAN IPv4 address: the source address the OS would use to reach the Internet.
     *
     * <p>{@link #firstSiteLocalIpv4()} takes whatever interface enumerates first, which on a Windows
     * box with VirtualBox, Hyper-V/WSL or a VPN adapter is routinely a virtual switch with no router
     * behind it (red-team net-14). SSDP then goes into that dead switch and the NAT-PMP fallback
     * targets {@code x.y.z.1} of the virtual subnet — the machine itself, on a host-only network.
     * Asking the routing table directly is not possible here (no process execution, no file access),
     * but connecting a UDP socket asks the same table without sending a byte.
     *
     * <p>Enumeration order stays as the fallback for a machine with no route to the Internet at all.
     */
    static String localLanIpv4() {
        String routed = routedLocalIpv4(ROUTE_PROBE_TARGET);
        if (!routed.isEmpty() && !routed.startsWith("127.")) {
            return routed;
        }
        return firstSiteLocalIpv4();
    }

    /**
     * Source IPv4 address the OS would use to reach {@code target}, or {@code ""} when there is no
     * route or anything else goes wrong. No packet is sent: a UDP {@code connect} is a routing-table
     * lookup and a local bind, nothing more.
     *
     * @param target IP literal — never a name, because a lookup here would be DNS on the campaign thread
     */
    static String routedLocalIpv4(String target) {
        try (DatagramChannel probe = DatagramChannel.open(StandardProtocolFamily.INET)) {
            probe.connect(new InetSocketAddress(InetAddress.getByName(target), CoopNatPmpMessages.PORT));
            SocketAddress local = probe.getLocalAddress();
            if (local instanceof InetSocketAddress inet
                    && inet.getAddress() instanceof Inet4Address address
                    && !address.isAnyLocalAddress()) {
                return address.getHostAddress();
            }
        } catch (Exception ignored) {
            // No route, no permission, no IPv4: the caller falls back to interface enumeration.
        }
        return "";
    }

    /** First non-loopback IPv4 address on an up interface, or {@code ""}. */
    static String firstSiteLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // No interface enumeration: caller falls back to "no gateway".
        }
        return "";
    }

    /**
     * One non-blocking HTTP request/response over a raw {@link SocketChannel}.
     *
     * <p>Reads to whichever comes first: the framing headers saying the body is complete, or
     * end-of-stream (which is why every request asks for {@code Connection: close}).
     */
    private static final class HttpExchange {
        private final long timeoutMillis;
        private final long deadline;
        private final SocketChannel channel;
        private final ByteBuffer out;
        private final ByteBuffer in = ByteBuffer.allocate(8192);

        private byte[] accumulated = new byte[8192];
        private int accumulatedLength;
        private String localAddress = "";
        private boolean settled;
        private boolean failed;
        private String failure = "";
        private CoopHttpMessages.Response response;

        HttpExchange(String url, byte[] request, long nowMillis, long timeoutMillis) throws Exception {
            CoopHttpMessages.Url parsed = CoopHttpMessages.parseUrl(url);
            this.timeoutMillis = timeoutMillis;
            this.deadline = nowMillis + timeoutMillis;
            this.out = ByteBuffer.wrap(request);
            SocketChannel opened = SocketChannel.open();
            // Connect through the helper and assign only afterwards: assigning the field first meant
            // a connect() that throws (no route, wrong address family) discarded a half-built
            // exchange with the channel still open, and NIO channels have no cleaner (net-30).
            connectNonBlocking(opened,
                    new InetSocketAddress(InetAddress.getByName(parsed.host()), parsed.port()));
            this.channel = opened;
        }

        void tick(long now) {
            if (settled) {
                return;
            }
            try {
                if (now > deadline) {
                    settle(true, "no response within " + timeoutMillis + " ms");
                    return;
                }
                if (!channel.isConnected()) {
                    if (!channel.finishConnect()) {
                        return;
                    }
                    SocketAddress local = channel.getLocalAddress();
                    if (local instanceof InetSocketAddress inet && inet.getAddress() != null) {
                        localAddress = inet.getAddress().getHostAddress();
                    }
                }
                if (out.hasRemaining()) {
                    channel.write(out);
                    if (out.hasRemaining()) {
                        return;
                    }
                }
                while (true) {
                    in.clear();
                    int read = channel.read(in);
                    if (read < 0) {
                        complete();
                        return;
                    }
                    if (read == 0) {
                        return;
                    }
                    in.flip();
                    if (accumulatedLength + in.remaining() > MAX_RESPONSE_BYTES) {
                        // A device that answers the SSDP search with an endless body would otherwise
                        // grow this buffer at line rate for the whole 6 s timeout, re-scanning it from
                        // byte 0 on every read, on the campaign thread (red-team net-50).
                        settle(true, "response larger than " + MAX_RESPONSE_BYTES + " bytes");
                        return;
                    }
                    append(in);
                    if (CoopHttpMessages.isComplete(accumulated, accumulatedLength)) {
                        complete();
                        return;
                    }
                }
            } catch (Exception ex) {
                settle(true, describe(ex));
            }
        }

        private void complete() {
            try {
                response = CoopHttpMessages.parse(accumulated, accumulatedLength);
                settle(false, "");
            } catch (RuntimeException ex) {
                settle(true, describe(ex));
            }
        }

        private void settle(boolean isFailure, String reason) {
            settled = true;
            failed = isFailure;
            failure = reason;
            close();
        }

        private void append(ByteBuffer buffer) {
            int needed = accumulatedLength + buffer.remaining();
            if (needed > accumulated.length) {
                byte[] grown = new byte[Math.max(accumulated.length * 2, needed)];
                System.arraycopy(accumulated, 0, grown, 0, accumulatedLength);
                accumulated = grown;
            }
            int count = buffer.remaining();
            buffer.get(accumulated, accumulatedLength, count);
            accumulatedLength += count;
        }

        boolean isSettled() {
            return settled;
        }

        boolean isFailed() {
            return failed;
        }

        String failure() {
            return failure;
        }

        String localAddress() {
            return localAddress;
        }

        CoopHttpMessages.Response response() {
            return response;
        }

        void close() {
            closeQuietly(channel);
        }
    }

    // ---- Phase 20 red-team seam ------------------------------------------------------------------
    // Appended rather than filed beside result(): this lands alongside a transport rewrite of the
    // same file, and an appended block that leaves result() untouched cannot collide with it.

    private Result versionedResult;
    private long resultVersion;

    /**
     * A counter that changes whenever {@link #result()} would return something different (red-team
     * B5). The host's connection-doctor block and the intel page's reachability line are published
     * exactly once today, on the first finished result — so a renewal that later downgrades or
     * repairs the mapping is never reported, and both surfaces keep showing a verdict that stopped
     * being true. Callers compare this against the last value they published.
     *
     * <p>Computed here rather than inside {@code result()} so the accessor stays a pure snapshot.
     */
    public long resultVersion() {
        Result current = result();
        if (!current.equals(versionedResult)) {
            versionedResult = current;
            resultVersion++;
        }
        return resultVersion;
    }

    /** Rendering helper shared with the connection doctor. */
    static String describeTier(Tier tier) {
        return switch (tier) {
            case UPNP -> "UPnP IGD";
            case NAT_PMP -> "NAT-PMP";
            case PCP -> "PCP";
            case NONE -> "none";
        };
    }
}
