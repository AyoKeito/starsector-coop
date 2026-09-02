package coop.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20.3 spike: drive {@link CoopPortMapper} against whatever router is actually on this LAN and
 * print what came back, then delete the mapping again.
 *
 * <p>Opt in with {@code -Dcoop.spike.upnp=true} (build.gradle forwards the property into the test
 * JVM). It is off by default because it talks to real network hardware and briefly opens a port on
 * it; the recorded outcome lives in {@code docs/CONNECTIVITY.md} under "Spike results".
 *
 * <p>The test asserts only that the state machine reaches a terminal state. A router refusing to map
 * is a legitimate, interesting result — the point of the spike is to find out and write it down, not
 * to turn a router's policy into a red build.
 */
@EnabledIfSystemProperty(named = "coop.spike.upnp", matches = "true")
class CoopPortMapperLiveSpikeTest {
    private static final int SPIKE_PORT = 27015;
    private static final long BUDGET_MILLIS = 30_000L;

    @Test
    void mapsThenReleasesTheSpikePortAgainstTheRealRouter() throws Exception {
        CoopPortMapper mapper = CoopPortMapper.start(SPIKE_PORT, true, System::currentTimeMillis);

        long start = System.currentTimeMillis();
        try {
            while (!mapper.result().finished() && System.currentTimeMillis() - start < BUDGET_MILLIS) {
                mapper.tick(System.currentTimeMillis());
                Thread.sleep(20L);
            }
            CoopPortMapper.Result result = mapper.result();
            long elapsed = System.currentTimeMillis() - start;

            System.out.println("=== Phase 20.3 UPnP live spike ===");
            System.out.println("elapsed           " + elapsed + " ms");
            System.out.println("tier              " + result.tier());
            System.out.println("gateway address   " + result.gatewayAddress());
            System.out.println("gateway name      " + result.gatewayName());
            System.out.println("  friendlyName    " + mapper.gatewayFriendlyName());
            System.out.println("  modelName       " + mapper.gatewayModelName());
            System.out.println("external address  " + result.externalAddress());
            System.out.println("external port     " + result.externalPort());
            System.out.println("cgnat             " + result.cgnat());
            System.out.println("mapped            " + result.mapped());
            System.out.println("failureText       " + result.failureText());
            System.out.println();
            System.out.println(CoopConnectionDoctor.hostReport(SPIKE_PORT, result));
            System.out.println("=== end spike ===");

            assertTrue(result.finished(), "port mapper did not settle within " + BUDGET_MILLIS + " ms");
        } finally {
            // Always give the port back, whatever happened above.
            mapper.shutdown();
            System.out.println("spike mapping released");
        }
    }
}
