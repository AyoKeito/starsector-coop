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

        CoopPortMapper mapper = runToCompletion(CoopPortMapper.startFromDescriptor(
                MAPPED_PORT, System::currentTimeMillis, igd.descriptorUrl()));

        assertTrue(mapper.result().mapped(), mapper.result().failureText());
        assertEquals(List.of("GetExternalIPAddress", "AddPortMapping", "DeletePortMapping",
                "AddPortMapping", "AddPortMapping"), igd.actions());
        mapper.shutdown();
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
        private volatile boolean withoutWanService;
        private volatile boolean closed;

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
                            + "<NewExternalIPAddress>203.0.113.7</NewExternalIPAddress>"
                            + "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>");
                }
                case "AddPortMapping" -> {
                    if (alwaysAddErrorCode != 0) {
                        return fault(alwaysAddErrorCode, alwaysAddErrorText);
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
                    + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>"
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
