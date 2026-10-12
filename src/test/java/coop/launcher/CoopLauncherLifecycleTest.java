package coop.launcher;

import coop.net.CoopPortMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the Swing transitions against a real probe and a loopback-only UPnP gateway. */
class CoopLauncherLifecycleTest {

    @Test
    void aFinishedCheckKeepsAndRenewsTheMappingUntilTheConnectionChanges() throws Exception {
        try (Gateway gateway = new Gateway(); Harness harness = new Harness()) {
            CoopPortMapper mapper = prepare(harness.app, gateway);
            assertEquals(Set.of("TCP", "UDP"), gateway.mapped);
            assertEquals(0, gateway.deletes.get());

            CoopLauncherProbe.Result probe = CoopLauncherProbe.GuestProber.probe(
                    "127.0.0.1", mapper.port());
            assertTrue(probe.launcherAnswered(), probe.message());
            assertTrue(probe.udpEchoed(), probe.message());

            // A due lease must still be driven by the launcher's timer after the check finishes.
            onEdt(() -> set(mapper, "nextRenewMillis", 0L));
            await(() -> gateway.adds.get() >= 4);
            assertEquals(0, gateway.deletes.get());
            assertEquals(Set.of("TCP", "UDP"), gateway.mapped);

            onEdt(() -> invoke(harness.app, "connectionInputsChanged"));
            await(() -> !onEdtBoolean(harness.app, "mappingCleanupPending"));
            assertEquals(2, gateway.deletes.get());
            assertTrue(gateway.mapped.isEmpty());
            onEdt(() -> assertNull(get(harness.app, "listener")));

            // The same still-open window can run a fresh check after the old mapping is gone.
            prepare(harness.app, gateway);
            assertEquals(Set.of("TCP", "UDP"), gateway.mapped);
        }
    }

    @Test
    void gameStartupWaitsForCleanupAndConsumesTheCheckboxOnce(@TempDir Path temp) throws Exception {
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(temp.toFile());
        try (Gateway gateway = new Gateway(); Harness harness = new Harness()) {
            prepare(harness.app, gateway);
            gateway.holdDeletes = true;
            AtomicInteger launches = new AtomicInteger();
            FakeProcess first = new FakeProcess();
            CoopLauncherConfig.read(layout.coopOptions()).write(layout.coopOptions(), false, Map.of(
                    CoopLauncherConfig.CONNECT_HOST, "127.0.0.1",
                    CoopLauncherConfig.CONNECT_PORT, "7777",
                    CoopLauncherConfig.ADOPT_CAMPAIGN_ID, "true"));

            onEdt(() -> harness.app.handoffToGame(layout, ignored -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertTrue(gateway.mapped.isEmpty(), "the game's mapping could be deleted next");
                assertEquals(2, gateway.deletes.get());
                launches.incrementAndGet();
                return first;
            }));
            assertTrue(gateway.deleteStarted.await(2, TimeUnit.SECONDS));
            assertEquals(0, launches.get(), "startup raced with DeletePortMapping");
            onEdt(() -> assertTrue((boolean) get(harness.app, "mappingCleanupPending")));
            gateway.allowDeletes.countDown();
            await(() -> launches.get() == 1 && onEdtBoolean(harness.app, "gameRunning"));
            onEdt(() -> assertFalse(((JCheckBox) get(harness.app, "adoptCampaignBox")).isSelected()));

            first.finish();
            await(() -> !onEdtBoolean(harness.app, "gameRunning"));
            assertFalse(CoopLauncherConfig.read(layout.coopOptions()).keys()
                    .contains(CoopLauncherConfig.ADOPT_CAMPAIGN_ID));

            FakeProcess second = new FakeProcess();
            onEdt(() -> harness.app.handoffToGame(layout, ignored -> {
                assertFalse(((JCheckBox) get(harness.app, "adoptCampaignBox")).isSelected());
                launches.incrementAndGet();
                return second;
            }));
            assertEquals(2, launches.get());
            second.finish();
            await(() -> !onEdtBoolean(harness.app, "gameRunning"));
        }
    }

    @Test
    void closingDuringCleanupCancelsDeferredStartupAndClearsConsent(@TempDir Path temp)
            throws Exception {
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(temp.toFile());
        try (Gateway gateway = new Gateway(); Harness harness = new Harness()) {
            prepare(harness.app, gateway);
            CoopLauncherConfig.read(layout.coopOptions()).write(layout.coopOptions(), true, Map.of(
                    CoopLauncherConfig.HOST_PORT, "7777",
                    CoopLauncherConfig.ADOPT_CAMPAIGN_ID, "true"));
            gateway.holdDeletes = true;
            AtomicInteger launches = new AtomicInteger();
            onEdt(() -> harness.app.handoffToGame(layout, ignored -> {
                launches.incrementAndGet();
                return new FakeProcess();
            }));
            assertTrue(gateway.deleteStarted.await(2, TimeUnit.SECONDS));
            onEdt(() -> set(harness.app, "closing", true));
            gateway.allowDeletes.countDown();
            await(() -> !onEdtBoolean(harness.app, "mappingCleanupPending"));
            assertEquals(0, launches.get());
            assertEquals(2, gateway.deletes.get());
            assertFalse(CoopLauncherConfig.read(layout.coopOptions()).keys()
                    .contains(CoopLauncherConfig.ADOPT_CAMPAIGN_ID));
        }
    }

    private static CoopPortMapper prepare(CoopLauncherApp app, Gateway gateway) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = socket.getLocalPort();
        }
        Method start = CoopPortMapper.class.getDeclaredMethod("startFromDescriptor",
                int.class, java.util.function.LongSupplier.class, String.class);
        start.setAccessible(true);
        CoopPortMapper mapper = (CoopPortMapper) start.invoke(null, port,
                (java.util.function.LongSupplier) System::currentTimeMillis, gateway.descriptorUrl());
        onEdt(() -> app.beginHostConnectionCheck(mapper.port(), (int) get(app, "checkGeneration"), mapper));
        await(() -> onEdtValue(app, "listener") != null && !onEdtBoolean(app, "checkingConnection"));
        assertTrue(mapper.result().mapped(), mapper.result().failureText());
        return mapper;
    }

    private static final class Harness implements AutoCloseable {
        final CoopLauncherApp app = new CoopLauncherApp();

        Harness() throws Exception {
            onEdt(() -> {
                set(app, "hostSegment", new JToggleButton("Host", true));
                set(app, "hostPortField", new JTextField("7777"));
                set(app, "hostSeedField", new JTextField(CoopSeeds.generate()));
                set(app, "guestHostField", new JTextField());
                set(app, "guestPortField", new JTextField("7777"));
                set(app, "hostPasswordField", new javax.swing.JPasswordField("secret"));
                set(app, "publicAddressField", new JTextField("203.0.113.7"));
                set(app, "connectionButton", new JButton());
                set(app, "connectionDetailsButton", new JButton());
                set(app, "connectionTitle", new JLabel());
                set(app, "connectionSummary", new JTextArea());
                set(app, "chipRow", new JPanel());
                JTextArea note = new JTextArea();
                new JPanel().add(note);
                set(app, "connectionNote", note);
                set(app, "launchButton", new JButton());
                set(app, "footerHint", new JTextArea());
                set(app, "adoptCampaignBox", new JCheckBox("Adopt once", true));
            });
        }

        @Override
        public void close() throws Exception {
            onEdt(() -> {
                set(app, "closing", true);
                invoke(app, "cancelConnectionCheck", String.class, "test teardown");
                invoke(app, "closeListener", String.class, "test teardown");
                CoopLogTail tail = (CoopLogTail) get(app, "logTail");
                if (tail != null) {
                    tail.close();
                }
                ((ExecutorService) get(app, "background")).shutdownNow();
            });
            await(() -> !onEdtBoolean(app, "mappingCleanupPending"));
        }
    }

    private static final class FakeProcess extends Process {
        private final CompletableFuture<Process> exited = new CompletableFuture<>();

        void finish() { exited.complete(this); }
        @Override public CompletableFuture<Process> onExit() { return exited; }
        @Override public long pid() { return 1234L; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public int waitFor() throws InterruptedException {
            try { exited.get(); } catch (java.util.concurrent.ExecutionException ex) {
                throw new AssertionError(ex);
            }
            return 0;
        }
        @Override public int exitValue() {
            if (!exited.isDone()) { throw new IllegalThreadStateException("still running"); }
            return 0;
        }
        @Override public void destroy() { finish(); }
    }

    /** A minimal router with observable mappings and a controllable cleanup response. */
    private static final class Gateway implements AutoCloseable {
        final Set<String> mapped = Collections.synchronizedSet(new HashSet<>());
        final AtomicInteger adds = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final CountDownLatch deleteStarted = new CountDownLatch(1);
        final CountDownLatch allowDeletes = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ServerSocket server = new ServerSocket();
        volatile boolean holdDeletes;
        volatile boolean closed;

        Gateway() throws Exception {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread worker = new Thread(() -> {
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        serve(socket);
                    } catch (Exception ex) {
                        if (!closed) { failure.set(ex); }
                    }
                }
            }, "launcher-test-router");
            worker.setDaemon(true);
            worker.start();
        }

        String descriptorUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/root.xml"; }

        private void serve(Socket socket) throws Exception {
            InputStream input = socket.getInputStream();
            StringBuilder header = new StringBuilder();
            for (int value; !header.toString().endsWith("\r\n\r\n") && (value = input.read()) >= 0;) {
                header.append((char) value);
            }
            int length = 0;
            for (String line : header.toString().split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            String request = header + new String(input.readNBytes(length), StandardCharsets.UTF_8);
            String response;
            if (request.startsWith("GET ")) {
                response = "<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1"
                        + "</deviceType><serviceList><service><serviceType>urn:schemas-upnp-org:service:"
                        + "WANIPConnection:1</serviceType><controlURL>/control</controlURL>"
                        + "</service></serviceList></device></root>";
            } else if (request.contains("#GetExternalIPAddress")) {
                response = "<GetExternalIPAddressResponse><NewExternalIPAddress>203.0.113.7"
                        + "</NewExternalIPAddress></GetExternalIPAddressResponse>";
            } else {
                String protocol = request.contains("<NewProtocol>UDP</NewProtocol>") ? "UDP" : "TCP";
                if (request.contains("#DeletePortMapping")) {
                    deleteStarted.countDown();
                    if (holdDeletes) { allowDeletes.await(2, TimeUnit.SECONDS); }
                    mapped.remove(protocol);
                    deletes.incrementAndGet();
                } else if (request.contains("#AddPortMapping")) {
                    mapped.add(protocol);
                    adds.incrementAndGet();
                } else {
                    throw new AssertionError("unexpected SOAP request: " + header);
                }
                response = "<ok/>";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }

        @Override
        public void close() throws Exception {
            closed = true;
            allowDeletes.countDown();
            server.close();
            assertNull(failure.get(), "mock router failed");
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10L); }
        assertTrue(condition.getAsBoolean(), "launcher transition did not finish");
    }

    private static Object onEdtValue(Object object, String name) {
        AtomicReference<Object> value = new AtomicReference<>();
        try { onEdt(() -> value.set(get(object, name))); } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        return value.get();
    }

    private static boolean onEdtBoolean(Object object, String name) {
        return (boolean) onEdtValue(object, name);
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    private static Object get(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
            field.set(object, value);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static void invoke(Object object, String name) { invoke(object, name, null, null); }

    private static void invoke(Object object, String name, Class<?> type, Object argument) {
        try { Method method = type == null ? object.getClass().getDeclaredMethod(name)
                    : object.getClass().getDeclaredMethod(name, type);
            method.setAccessible(true);
            if (type == null) { method.invoke(object); } else { method.invoke(object, argument); }
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
}
