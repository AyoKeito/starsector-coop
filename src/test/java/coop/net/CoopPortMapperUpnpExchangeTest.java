package coop.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the whole UPnP half of {@link CoopPortMapper} against a stub IGD on loopback: descriptor
 * fetch, {@code GetExternalIPAddress}, both {@code AddPortMapping} calls, the 718/725 recovery paths,
 * and the {@code DeletePortMapping} release on shutdown.
 *
 * <p>This exists because the LAN the spike ran on has no UPnP gateway at all (see the spike results
 * in {@code docs/CONNECTIVITY.md}), so nothing else proves the SOAP conversation actually works
 * end to end. Unit tests of the envelope bytes cannot catch a state machine that never advances.
 */
class CoopPortMapperUpnpExchangeTest {
    private static final int MAPPED_PORT = 27015;

    private StubIgd igd;

    @BeforeEach
    void startStub() throws Exception {
        igd = new StubIgd();
    }

    @AfterEach
    void stopStub() {
        igd.close();
    }

    private CoopPortMapper runToCompletion(CoopPortMapper mapper) throws Exception {
        long start = System.currentTimeMillis();
        while (!mapper.result().finished() && System.currentTimeMillis() - start < 15_000L) {
            mapper.tick(System.currentTimeMillis());
            Thread.sleep(5L);
        }
        return mapper;
    }

    @Test
    void mapsTcpAndUdpThroughAStubGatewayAndReleasesBothOnShutdown() throws Exception {
        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        CoopPortMapper.Result result = mapper.result();
        assertTrue(result.mapped(), result.failureText());
        assertEquals(CoopPortMapper.Tier.UPNP, result.tier());
        assertEquals("203.0.113.7", result.externalAddress());
        assertEquals(MAPPED_PORT, result.externalPort());
        assertEquals("203.0.113.7:" + MAPPED_PORT, result.externalEndpoint());
        assertFalse(result.cgnat());
        assertEquals("Stub Router (SR-1)", result.gatewayName());
        assertEquals("Stub Router", mapper.gatewayFriendlyName());
        assertEquals("SR-1", mapper.gatewayModelName());
        assertEquals("", result.failureText());

        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "AddPortMapping"), igd.actions());
        assertTrue(igd.bodies().get(1).contains("<NewProtocol>TCP</NewProtocol>"));
        assertTrue(igd.bodies().get(2).contains("<NewProtocol>UDP</NewProtocol>"));
        assertTrue(igd.bodies().get(1).contains("<NewLeaseDuration>3600</NewLeaseDuration>"));
        // NewInternalClient must be the address this machine used to reach the gateway.
        assertTrue(igd.bodies().get(1).contains("<NewInternalClient>127.0.0.1</NewInternalClient>"),
                igd.bodies().get(1));

        mapper.shutdown();
        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "AddPortMapping",
                "DeletePortMapping", "DeletePortMapping"), igd.actions());
        assertTrue(igd.bodies().get(3).contains("<NewExternalPort>" + MAPPED_PORT + "</NewExternalPort>"));
    }

    @Test
    void retriesWithAPermanentLeaseWhenTheRouterAnswers725() throws Exception {
        igd.failFirstAddWith(CoopUpnpSoap.ERROR_ONLY_PERMANENT_LEASES, "OnlyPermanentLeasesSupported");

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        assertTrue(mapper.result().mapped(), mapper.result().failureText());
        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "AddPortMapping", "AddPortMapping"),
                igd.actions());
        assertTrue(igd.bodies().get(1).contains("<NewLeaseDuration>3600</NewLeaseDuration>"));
        assertTrue(igd.bodies().get(2).contains("<NewLeaseDuration>0</NewLeaseDuration>"), igd.bodies().get(2));
        mapper.shutdown();
    }

    @Test
    void deletesTheConflictingEntryAndRetriesWhenTheRouterAnswers718() throws Exception {
        igd.failFirstAddWith(CoopUpnpSoap.ERROR_CONFLICT_IN_MAPPING_ENTRY, "ConflictInMappingEntry");
        igd.reportConflictOwner("127.0.0.1"); // The router says the stale entry is this machine's.

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        assertTrue(mapper.result().mapped(), mapper.result().failureText());
        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "GetSpecificPortMappingEntry",
                "DeletePortMapping", "AddPortMapping", "AddPortMapping"), igd.actions());
        mapper.shutdown();
    }

    /**
     * net-18: DeletePortMapping is keyed on the external port alone, so answering a 718 with a blind
     * delete evicts whichever machine on the LAN owns that port - and with two coop hosts on one LAN
     * they then steal it from each other on every 30-minute renewal. The router is asked first now.
     */
    @Test
    void net18_aConflictOwnedByAnotherMachineIsNotDeleted() throws Exception {
        igd.failFirstAddWith(CoopUpnpSoap.ERROR_CONFLICT_IN_MAPPING_ENTRY, "ConflictInMappingEntry");
        igd.reportConflictOwner("192.168.1.50");

        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, clock::get, igd.descriptorUrl());
        driveUntilFinished(mapper, clock);

        CoopPortMapper.Result result = mapper.result();
        assertFalse(result.mapped());
        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "GetSpecificPortMappingEntry"),
                igd.actions(), "another machine's mapping must survive our conflict");
        assertTrue(result.failureText().contains("already mapped to 192.168.1.50"), result.failureText());
        assertTrue(result.failureText().contains("coop.hostPort"), result.failureText());
        mapper.shutdown();
    }

    /**
     * net-11: 0.0.0.0 is what a router with a down or bridged WAN link reports. It used to be stored
     * verbatim, shared as the guest's endpoint and described as "a public address".
     */
    @Test
    void net11_anExternalAddressOfZeroIsNotPublishedAsAnEndpoint() throws Exception {
        igd.reportExternalAddress("0.0.0.0");

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        CoopPortMapper.Result result = mapper.result();
        assertEquals(CoopPortMapper.Tier.UPNP, result.tier());
        assertEquals("", result.externalAddress());
        assertEquals("", result.externalEndpoint());
        assertFalse(result.cgnat());
        mapper.shutdown();
    }

    /**
     * net-24: a URLBase with a name host makes the control-URL resolve throw, which used to escape
     * into tick()'s catch-all and end the mapper outright - skipping the NAT-PMP fallback that every
     * other UPnP dead end gets.
     */
    @Test
    void net24_aDescriptorWithANameHostFallsThroughToNatPmpInsteadOfAborting() throws Exception {
        igd.serveDescriptorWithUrlBase("http://router.local:5000/");

        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, clock::get, igd.descriptorUrl());
        driveUntilFinished(mapper, clock);

        CoopPortMapper.Result result = mapper.result();
        assertTrue(result.finished());
        assertFalse(result.mapped());
        assertTrue(result.failureText().contains("descriptor unusable"), result.failureText());
        assertFalse(result.failureText().contains("aborted"), result.failureText());
        assertTrue(result.failureText().contains("NAT-PMP"),
                "the NAT-PMP fallback must have had its turn: " + result.failureText());
        mapper.shutdown();
    }

    /**
     * net-50: the descriptor comes from whatever answered an SSDP search. An endless body used to be
     * accumulated at line rate for the whole 6 s timeout, re-scanned from byte 0 after every read,
     * on the campaign thread.
     */
    @Test
    void net50_anEndlessResponseBodyIsCutOffInsteadOfFillingTheHeap() throws Exception {
        igd.serveEndlessDescriptorBody();

        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, clock::get, igd.descriptorUrl());
        driveUntilFinished(mapper, clock);

        CoopPortMapper.Result result = mapper.result();
        assertFalse(result.mapped());
        assertTrue(result.failureText().contains("larger than"), result.failureText());
        mapper.shutdown();
    }

    /**
     * net-25: the release used to be gated on the tier, which is only set once both protocols went
     * in. A TCP mapping that succeeded before the UDP one was refused was therefore left in the
     * router - permanently, when the 725 path had made it a lease-0 mapping.
     */
    @Test
    void net25_aHalfCompletedMappingIsStillReleasedOnShutdown() throws Exception {
        igd.refuseUdpAddWith(714, "NoSuchEntryInArray");

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        assertFalse(mapper.result().mapped());
        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "AddPortMapping"), igd.actions());

        mapper.shutdown();

        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "AddPortMapping",
                "DeletePortMapping"), igd.actions(), "the TCP entry that did go in must be deleted");
        assertTrue(igd.bodies().get(3).contains("<NewProtocol>TCP</NewProtocol>"), igd.bodies().get(3));
    }

    /**
     * net-27: shutdown left {@code renewing} set, so a release that could not even be sent went
     * through failMapping, which parked the machine back in ACTIVE - read by the shutdown loop as
     * "still working", spun to its 1.2 s deadline, and never sent the remaining release.
     */
    @Test
    void net27_aShutdownDuringARenewalEndsInsteadOfSpinningOutTheBudget() throws Exception {
        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));
        assertTrue(mapper.result().mapped(), mapper.result().failureText());

        // The lease renewal is in flight when the game exits, and the router has become unreachable.
        mapper.tick(System.currentTimeMillis() + CoopPortMapper.RENEW_INTERVAL_MILLIS + 1L);
        mapper.breakExchanges();

        mapper.shutdown();

        assertTrue(mapper.shutdownTicks() <= 5,
                "the release must end when it cannot be sent, not spin the budget out: "
                        + mapper.shutdownTicks());
        CoopPortMapper.Result after = mapper.result();
        assertTrue(after.mapped(), "a failed release is not a failed mapping: " + after.failureText());
        assertEquals("", after.failureText());
    }

    @Test
    void aRouterThatRefusesOutrightEndsInAReadableFailureRatherThanAnException() throws Exception {
        igd.refuseEveryAddWith(714, "NoSuchEntryInArray");

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        CoopPortMapper.Result result = mapper.result();
        assertTrue(result.finished());
        assertFalse(result.mapped());
        assertTrue(result.failureText().contains("UPnPError 714"), result.failureText());
        assertTrue(result.failureText().contains("NoSuchEntryInArray"), result.failureText());
        mapper.shutdown();
    }

    @Test
    void aGatewayWithNoWanServiceFallsThroughInsteadOfMapping() throws Exception {
        igd.serveDescriptorWithoutWanService();

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        CoopPortMapper.Result result = mapper.result();
        assertTrue(result.finished());
        assertFalse(result.mapped());
        assertTrue(result.failureText().contains("no WAN connection service"), result.failureText());
        mapper.shutdown();
    }

    // ---- red-team B5/B8 --------------------------------------------------------------------------

    /**
     * B5: a renewal that the router refuses must not retract a mapping the router is still holding,
     * and must not be the last renewal ever attempted. Routing a renewal failure through
     * {@code fail()} did both — it wrote a failureText over a live mapping and parked the state
     * machine in FAILED, where {@code tickActive} never runs again.
     */
    @Test
    void b5_aRefusedRenewalKeepsTheMappingAndKeepsRenewing() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, clock::get, igd.descriptorUrl());
        driveUntilFinished(mapper, clock);
        assertTrue(mapper.result().mapped(), mapper.result().failureText());
        long version = mapper.resultVersion();
        int addsBeforeRenewal = countAdds();

        igd.refuseEveryAddWith(714, "NoSuchEntryInArray");
        clock.addAndGet(CoopPortMapper.RENEW_INTERVAL_MILLIS + 1L);
        driveTicks(mapper, clock, 400);

        assertTrue(countAdds() > addsBeforeRenewal, "the renewal must actually have been attempted");
        CoopPortMapper.Result after = mapper.result();
        assertTrue(after.mapped(), "the mapping in the router outlives one refused renewal");
        assertEquals("", after.failureText());
        assertEquals(CoopPortMapper.Tier.UPNP, after.tier());
        assertEquals(version, mapper.resultVersion(), "nothing a publisher cares about changed");

        int addsAfterFirstRenewal = countAdds();
        clock.addAndGet(CoopPortMapper.RENEW_INTERVAL_MILLIS + 1L);
        driveTicks(mapper, clock, 400);
        assertTrue(countAdds() > addsAfterFirstRenewal,
                "the next renewal must still be attempted; one failure is not the end of renewals");
    }

    /**
     * B8: the shutdown release is a busy wait — there is no thread to hand off to and sleeping is not
     * allowed on the campaign thread — but the state machine it drives is made of wall-clock
     * comparisons, so running it twice inside one millisecond cannot change anything. It used to run
     * up to 200,000 full passes; now it runs one per millisecond, and a clock that does not move at
     * all buys exactly one.
     */
    @Test
    void b8_theShutdownReleaseLoopTicksOnlyWhenTheClockMoves() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, clock::get, igd.descriptorUrl());
        driveUntilFinished(mapper, clock);
        assertTrue(mapper.result().mapped(), mapper.result().failureText());

        // driveUntilFinished leaves the clock where it stopped, so shutdown sees a frozen one.
        mapper.shutdown();

        assertEquals(1, mapper.shutdownTicks(),
                "a frozen clock must buy one pass of the state machine, not two hundred thousand");
    }

    private int countAdds() {
        int adds = 0;
        for (String action : igd.actions()) {
            if (action.equals("AddPortMapping")) {
                adds++;
            }
        }
        return adds;
    }

    /** Drives the mapper on an injected clock; real sockets need real time, hence the short sleep. */
    private void driveTicks(CoopPortMapper mapper, AtomicLong clock, int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            mapper.tick(clock.addAndGet(5L));
            mapper.result();
            Thread.sleep(1L);
        }
    }

    private void driveUntilFinished(CoopPortMapper mapper, AtomicLong clock) throws Exception {
        long start = System.currentTimeMillis();
        while (!mapper.result().finished() && System.currentTimeMillis() - start < 15_000L) {
            mapper.tick(clock.addAndGet(5L));
            Thread.sleep(1L);
        }
    }

    /**
     * A deliberately dumb HTTP/1.1 server that answers the four requests an IGD conversation makes.
     * Chunks the descriptor on purpose — plenty of real firmware does, and it exercises the chunked
     * reader against a real socket rather than a byte array.
     */
    private static final class StubIgd implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final List<String> actions = Collections.synchronizedList(new ArrayList<>());
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger addCalls = new AtomicInteger();

        private volatile int firstAddErrorCode;
        private volatile String firstAddErrorText = "";
        private volatile int alwaysAddErrorCode;
        private volatile String alwaysAddErrorText = "";
        private volatile int udpAddErrorCode;
        private volatile String udpAddErrorText = "";
        private volatile boolean withoutWanService;
        private volatile boolean closed;
        private volatile String externalIpAddress = "203.0.113.7";
        private volatile String conflictOwner = "127.0.0.1";
        private volatile String urlBase = "";
        private volatile boolean endlessBody;

        StubIgd() throws Exception {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            thread = new Thread(this::serve, "stub-igd");
            thread.setDaemon(true);
            thread.start();
        }

        String descriptorUrl() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/upnp/rootDesc.xml";
        }

        List<String> actions() {
            return new ArrayList<>(actions);
        }

        List<String> bodies() {
            return new ArrayList<>(bodies);
        }

        void failFirstAddWith(int code, String text) {
            firstAddErrorCode = code;
            firstAddErrorText = text;
        }

        void refuseEveryAddWith(int code, String text) {
            alwaysAddErrorCode = code;
            alwaysAddErrorText = text;
        }

        void serveDescriptorWithoutWanService() {
            withoutWanService = true;
        }

        void refuseUdpAddWith(int code, String text) {
            udpAddErrorCode = code;
            udpAddErrorText = text;
        }

        void reportExternalAddress(String address) {
            externalIpAddress = address;
        }

        /** Who the router says owns the conflicting external port when asked about a 718. */
        void reportConflictOwner(String internalClient) {
            conflictOwner = internalClient;
        }

        void serveDescriptorWithUrlBase(String base) {
            urlBase = base;
        }

        /** A device that answers the descriptor GET with a body that never ends. */
        void serveEndlessDescriptorBody() {
            endlessBody = true;
        }

        private void serve() {
            while (!closed) {
                try (Socket socket = serverSocket.accept()) {
                    handle(socket);
                } catch (Exception ex) {
                    if (!closed) {
                        // A client that hangs up mid-request is not interesting to the test.
                        continue;
                    }
                    return;
                }
            }
        }

        private void handle(Socket socket) throws Exception {
            InputStream in = socket.getInputStream();
            StringBuilder head = new StringBuilder();
            int value;
            while (!head.toString().endsWith("\r\n\r\n") && (value = in.read()) >= 0) {
                head.append((char) value);
            }
            String header = head.toString();
            int contentLength = 0;
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < contentLength; i++) {
                int read = in.read();
                if (read < 0) {
                    break;
                }
                body.append((char) read);
            }

            if (header.startsWith("GET ") && endlessBody) {
                streamEndlessBody(socket);
                return;
            }
            String response = header.startsWith("GET ")
                    ? descriptorResponse()
                    : soapResponse(header, body.toString());

            OutputStream out = socket.getOutputStream();
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.shutdownOutput();
        }

        private String soapResponse(String header, String body) {
            String action = "";
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase().startsWith("soapaction:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim().replace("\"", "");
                    action = value.substring(value.indexOf('#') + 1);
                }
            }
            actions.add(action);
            bodies.add(body);

            switch (action) {
                case "GetExternalIPAddress" -> {
                    return ok("<?xml version=\"1.0\"?><s:Envelope"
                            + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                            + "<u:GetExternalIPAddressResponse"
                            + " xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">"
                            + "<NewExternalIPAddress>" + externalIpAddress + "</NewExternalIPAddress>"
                            + "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>");
                }
                case "GetSpecificPortMappingEntry" -> {
                    return ok("<?xml version=\"1.0\"?><s:Envelope"
                            + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                            + "<u:GetSpecificPortMappingEntryResponse"
                            + " xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">"
                            + "<NewInternalPort>" + MAPPED_PORT + "</NewInternalPort>"
                            + "<NewInternalClient>" + conflictOwner + "</NewInternalClient>"
                            + "<NewEnabled>1</NewEnabled>"
                            + "<NewPortMappingDescription>Starsector coop</NewPortMappingDescription>"
                            + "<NewLeaseDuration>0</NewLeaseDuration>"
                            + "</u:GetSpecificPortMappingEntryResponse></s:Body></s:Envelope>");
                }
                case "AddPortMapping" -> {
                    if (alwaysAddErrorCode != 0) {
                        return fault(alwaysAddErrorCode, alwaysAddErrorText);
                    }
                    if (udpAddErrorCode != 0 && body.contains("<NewProtocol>UDP</NewProtocol>")) {
                        return fault(udpAddErrorCode, udpAddErrorText);
                    }
                    if (firstAddErrorCode != 0 && addCalls.getAndIncrement() == 0) {
                        return fault(firstAddErrorCode, firstAddErrorText);
                    }
                    return ok("<?xml version=\"1.0\"?><s:Envelope"
                            + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                            + "<u:AddPortMappingResponse"
                            + " xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\"/>"
                            + "</s:Body></s:Envelope>");
                }
                default -> {
                    return ok("<?xml version=\"1.0\"?><s:Envelope"
                            + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                            + "<u:" + action + "Response"
                            + " xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\"/>"
                            + "</s:Body></s:Envelope>");
                }
            }
        }

        private String descriptorResponse() {
            String xml = "<?xml version=\"1.0\"?>"
                    + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                    + (urlBase.isEmpty() ? "" : "<URLBase>" + urlBase + "</URLBase>")
                    + "<device>"
                    + "<deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>"
                    + "<friendlyName>Stub Router</friendlyName><modelName>SR-1</modelName>"
                    + "<serviceList>"
                    + (withoutWanService ? "" : "<service>"
                    + "<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
                    + "<controlURL>ctl/IPConn</controlURL></service>")
                    + "<service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>"
                    + "<controlURL>/ctl/L3F</controlURL></service>"
                    + "</serviceList></device></root>";
            // Chunked on purpose: real IGD firmware commonly does this.
            return "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nTransfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n"
                    + Integer.toHexString(xml.length()) + "\r\n" + xml + "\r\n0\r\n\r\n";
        }

        /**
         * Headers that promise nothing about framing, then bytes until the reader gives up. Bounded
         * at 4 MB so a regression cannot wedge the suite; the mapper's own cap is far below that.
         */
        private void streamEndlessBody(Socket socket) throws Exception {
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            byte[] filler = new byte[8192];
            java.util.Arrays.fill(filler, (byte) 'x');
            for (int written = 0; written < 4 * 1024 * 1024 && !closed; written += filler.length) {
                out.write(filler);
                out.flush();
            }
        }

        private static String ok(String body) {
            return "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
        }

        private static String fault(int code, String description) {
            String body = "<?xml version=\"1.0\"?><s:Envelope"
                    + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>"
                    + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                    + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                    + "<errorCode>" + code + "</errorCode>"
                    + "<errorDescription>" + description + "</errorDescription>"
                    + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
            return "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/xml\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body;
        }

        @Override
        public void close() {
            closed = true;
            try {
                serverSocket.close();
            } catch (Exception ignored) {
                // Test teardown.
            }
        }
    }
}
