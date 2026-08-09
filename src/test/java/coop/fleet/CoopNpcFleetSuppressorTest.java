package coop.fleet;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNpcFleetSuppressorTest {

    @Test
    void plainNpcFleetIsSwept() {
        assertTrue(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, false, false));
    }

    @Test
    void localPlayerFleetIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(true, false, false, false));
    }

    @Test
    void stationIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, true, false, false));
    }

    @Test
    void remotePlayerMirrorIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, true, false));
    }

    @Test
    void npcMirrorIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, false, true));
    }

    @Test
    void suppressorRunsOnGuestOnly() {
        assertTrue(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.GUEST));
        assertFalse(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.HOST));
        assertFalse(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.NONE));
    }

    @Test
    void recognizesKnownAndSuffixedSpawnerScripts() {
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("RouteManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("MercFleetManagerV2"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("RemnantSeededFleetManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("PersonBountyManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("HegemonyPatrolFleetManager"));
    }

    @Test
    void doesNotMatchUnrelatedScripts() {
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName("CoopNetPump"));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName("CoopCampaignReplicator"));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(""));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(null));
    }

    // ---- Phase 12b: spawner suppression retries after a failure --------------------------------

    @Test
    void spawnerSuppressionRetriesAfterAThrow() {
        CoopNpcFleetSuppressor suppressor = new CoopNpcFleetSuppressor();
        FailingSector sector = new FailingSector(1);

        // First tick: getScripts() throws, so suppression fails. Pre-12b the "done" flag was set
        // outside the try, permanently demoting the per-frame sweep to sole mechanism.
        suppressor.tick(sector.proxy());
        assertEquals(1, sector.scriptAccessAttempts);

        // Second tick retries and succeeds.
        suppressor.tick(sector.proxy());
        assertEquals(2, sector.scriptAccessAttempts);

        // Third tick: already suppressed, so no further attempts.
        suppressor.tick(sector.proxy());
        assertEquals(2, sector.scriptAccessAttempts,
                "suppression should run once successfully, then stop retrying");
    }

    /** SectorAPI whose getScripts() throws for the first {@code failures} calls, then works. */
    private static final class FailingSector {
        private final int failures;
        private int scriptAccessAttempts;

        private FailingSector(int failures) {
            this.failures = failures;
        }

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "toString" -> {
                                return proxy.getClass().getName();
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            case "getScripts" -> {
                                scriptAccessAttempts++;
                                if (scriptAccessAttempts <= failures) {
                                    throw new IllegalStateException("simulated spawner scan failure");
                                }
                                return new ArrayList<EveryFrameScript>();
                            }
                            case "getTransientScripts" -> {
                                return new ArrayList<EveryFrameScript>();
                            }
                            case "getAllLocations" -> {
                                return new ArrayList<LocationAPI>();
                            }
                            case "getHyperspace", "getPlayerFleet" -> {
                                return null;
                            }
                            default -> {
                                return null;
                            }
                        }
                    });
        }
    }
}
