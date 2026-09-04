package coop.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20 M6: the {@code ENGAGE_GUEST} handoff threshold re-derived in time-to-contact terms.
 *
 * <p>The property under test is the audit's rule: a measured link may only ever <em>widen</em> the
 * handoff band, never move or narrow it, and an unmeasured link must reproduce Phase 14's loopback
 * geometry exactly.
 */
class CoopNpcThreatHandoffMarginTest {

    /** The flat Phase 14 margin, which is also the floor of the derived one. */
    private static final float FLAT = CoopNpcThreatWatcher.CONTACT_MARGIN_SU;

    /** su/s x ms budget / 1000, expressed the way the derivation reads. */
    private static float expected(float closingSuPerSec, int p95Millis) {
        long budget = 2L * p95Millis + CoopNpcThreatWatcher.SCAN_INTERVAL_MILLIS
                + CoopNpcThreatWatcher.PROCESSING_SLACK_MILLIS;
        return closingSuPerSec * budget / 1000f;
    }

    @Test
    void theMarginTableMatchesTheDerivation() {
        // (closing speed su/s, p95 RTT ms) -> margin su. Third column is what the formula gives; the
        // effective answer is max(flat, that), which the assertions below apply.
        float[][] table = {
                //  closing, p95,  derived
                {340f, 200f, expected(340f, 200)},   // the spike chaser on a 200 ms WAN link: 255 su
                {340f, 100f, expected(340f, 100)},   // half the latency, still over the flat floor
                {340f, 20f, expected(340f, 20)},     // a LAN-ish link: 132 su, only just over
                {120f, 200f, expected(120f, 200)},   // a slow chaser at the same latency: 90 su
                {0f, 200f, 0f},                      // not closing at all: the floor is the answer
                {600f, 250f, expected(600f, 250)},   // a fast pair on a bad link: 510 su
        };
        for (float[] row : table) {
            float closing = row[0];
            int p95 = (int) row[1];
            float derived = row[2];
            assertEquals(Math.max(FLAT, derived),
                    CoopNpcThreatWatcher.handoffMargin(closing, p95), 0.001f,
                    "closing=" + closing + " p95=" + p95);
        }
    }

    @Test
    void aMeasuredLinkOnlyEverWidensTheBand() {
        for (int p95 : new int[] {1, 25, 80, 200, 500}) {
            for (float closing : new float[] {0f, 50f, 120f, 340f, 900f}) {
                assertTrue(CoopNpcThreatWatcher.handoffMargin(closing, p95) >= FLAT,
                        "closing=" + closing + " p95=" + p95);
            }
        }
    }

    @Test
    void anUnmeasuredLinkReproducesThePhase14GeometryExactly() {
        // The loopback contract: p95 = 0 (or "no PONG yet", which the pump maps to 0) must produce
        // byte-identical numbers to the flat Phase 14 constant, whatever the fleets are doing. A
        // re-derivation at zero latency would give 119 su for the spike chaser and quietly move the
        // handoff point on localhost, where nothing is wrong with it.
        for (float closing : new float[] {0f, 120f, 340f, 900f, Float.NaN}) {
            assertEquals(FLAT, CoopNpcThreatWatcher.handoffMargin(closing, 0), 0f);
            assertEquals(FLAT, CoopNpcThreatWatcher.handoffMargin(closing, -1), 0f);
        }
        assertEquals(400f, CoopNpcThreatWatcher.contactDistance(150f, 150f), 0f);
        assertEquals(CoopNpcThreatWatcher.contactDistance(150f, 150f),
                CoopNpcThreatWatcher.contactDistance(150f, 150f, 340f, 0), 0f);
    }

    @Test
    void anUnreadableClosingSpeedFallsBackToTheSpikeFigure() {
        // NaN is "the engine would not answer" (no location, no velocity, coincident fleets), and the
        // fallback has to be the fastest thing the spike ever saw so the failure is an early handoff
        // rather than a missed one.
        assertEquals(CoopNpcThreatWatcher.handoffMargin(
                        CoopNpcThreatWatcher.SPIKE_CLOSING_SPEED_SU_PER_SEC, 200),
                CoopNpcThreatWatcher.handoffMargin(Float.NaN, 200), 0.001f);
        assertTrue(CoopNpcThreatWatcher.handoffMargin(Float.NaN, 200)
                > CoopNpcThreatWatcher.handoffMargin(120f, 200),
                "the fallback must not be gentler than a real, slower chaser");
    }

    @Test
    void fastForwardWidensTheBandByTheCampaignSpeedMultiplier() {
        // Every term of the budget is wall-clock, the closing speed is per campaign second, and under
        // shared fast-forward CampaignState.advance runs campaignSpeedupMult engine advances per wall
        // frame. The chaser therefore covers twice the distance inside the same 650 ms round trip, so
        // an unscaled margin fires the handoff after contact - the failure M6 exists to prevent.
        float oneX = CoopNpcThreatWatcher.handoffMargin(340f, 200);
        float twoX = CoopNpcThreatWatcher.handoffMargin(340f, 200, 2f);

        assertEquals(expected(340f, 200) * 2f, twoX, 0.001f);
        assertEquals(2f * oneX, twoX, 0.001f);
        assertEquals(oneX + 100f, CoopNpcThreatWatcher.contactDistance(100f, 0f, 340f, 200),
                0.001f, "the 1x overload is unchanged");
        assertEquals(twoX + 200f, CoopNpcThreatWatcher.contactDistance(100f, 100f, 340f, 200, 2f),
                0.001f);
    }

    @Test
    void aSpeedMultiplierCanOnlyEverWidenTheBand() {
        for (float mult : new float[] {0f, 0.5f, 1f, Float.NaN, -3f}) {
            assertEquals(CoopNpcThreatWatcher.handoffMargin(340f, 200),
                    CoopNpcThreatWatcher.handoffMargin(340f, 200, mult), 0.001f, "mult=" + mult);
        }
        // And an unmeasured link keeps Phase 14's flat geometry whatever the campaign speed is.
        assertEquals(FLAT, CoopNpcThreatWatcher.handoffMargin(340f, 0, 2f), 0f);
    }

    @Test
    void onlyAnAffirmativeFastForwardReadWidensTheBand() {
        assertEquals(coop.time.CoopFastForwardLock.SESSION_MULT,
                CoopNpcThreatWatcher.campaignSpeedMult(sectorFastForwarding(true)), 0f);
        assertEquals(1f, CoopNpcThreatWatcher.campaignSpeedMult(sectorFastForwarding(false)), 0f);
        assertEquals(1f, CoopNpcThreatWatcher.campaignSpeedMult(null), 0f);
        assertEquals(1f, CoopNpcThreatWatcher.campaignSpeedMult(sectorWith(null)), 0f);
    }

    private static com.fs.starfarer.api.campaign.SectorAPI sectorFastForwarding(boolean fastForward) {
        Object ui = java.lang.reflect.Proxy.newProxyInstance(
                CoopNpcThreatHandoffMarginTest.class.getClassLoader(),
                new Class<?>[] {com.fs.starfarer.api.campaign.CampaignUIAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isFastForward" -> fastForward;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "uiStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return sectorWith(ui);
    }

    /** A null ui is "no campaign UI at all" (loading, teardown): unknown speed must read as 1x. */
    private static com.fs.starfarer.api.campaign.SectorAPI sectorWith(Object ui) {
        return (com.fs.starfarer.api.campaign.SectorAPI) java.lang.reflect.Proxy.newProxyInstance(
                CoopNpcThreatHandoffMarginTest.class.getClassLoader(),
                new Class<?>[] {com.fs.starfarer.api.campaign.SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCampaignUI" -> ui;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "sectorStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aRecedingPairClampsToTheFloor() {
        // A negative projection means the mirror is pulling away. There is no time-to-contact to
        // budget for, so the band stays at the floor rather than going negative.
        assertEquals(FLAT, CoopNpcThreatWatcher.handoffMargin(-500f, 200), 0f);
    }

    @Test
    void closingSpeedIsTheRelativeVelocityProjectedOnTheSeparation() {
        // Chaser at the origin, mirror 1000 su to the +x, so the separation direction is +x.
        // Chaser doing 300 su/s straight at it, mirror running at 100 su/s the same way: 200 su/s.
        assertEquals(200f, CoopNpcThreatWatcher.closingSpeed(
                fleet(0f, 0f, 300f, 0f), fleet(1000f, 0f, 100f, 0f)), 0.001f);
        // Pure crossing motion closes nothing.
        assertEquals(0f, CoopNpcThreatWatcher.closingSpeed(
                fleet(0f, 0f, 0f, 250f), fleet(1000f, 0f, 0f, 250f)), 0.001f);
        // Outrunning the chaser clamps at zero rather than going negative.
        assertEquals(0f, CoopNpcThreatWatcher.closingSpeed(
                fleet(0f, 0f, 100f, 0f), fleet(1000f, 0f, 400f, 0f)), 0.001f);
    }

    @Test
    void anUnusableEngineReadReportsUnknownRatherThanStationary() {
        // Coincident fleets have no separation direction; a null velocity is an engine that would not
        // answer. Both must come back NaN so the margin falls back instead of reading "not closing".
        assertTrue(Float.isNaN(CoopNpcThreatWatcher.closingSpeed(
                fleet(500f, 500f, 10f, 0f), fleet(500f, 500f, 0f, 0f))));
        assertTrue(Float.isNaN(CoopNpcThreatWatcher.closingSpeed(
                fleet(0f, 0f, 300f, 0f), nullVelocityFleet(1000f, 0f))));
    }

    private static com.fs.starfarer.api.campaign.CampaignFleetAPI fleet(
            float x, float y, float vx, float vy) {
        return stub(new org.lwjgl.util.vector.Vector2f(x, y),
                new org.lwjgl.util.vector.Vector2f(vx, vy));
    }

    private static com.fs.starfarer.api.campaign.CampaignFleetAPI nullVelocityFleet(float x, float y) {
        return stub(new org.lwjgl.util.vector.Vector2f(x, y), null);
    }

    /** Only two methods matter here; anything else the code touches should fail loudly, not silently. */
    private static com.fs.starfarer.api.campaign.CampaignFleetAPI stub(
            org.lwjgl.util.vector.Vector2f location, org.lwjgl.util.vector.Vector2f velocity) {
        return (com.fs.starfarer.api.campaign.CampaignFleetAPI) java.lang.reflect.Proxy.newProxyInstance(
                CoopNpcThreatHandoffMarginTest.class.getClassLoader(),
                new Class<?>[] {com.fs.starfarer.api.campaign.CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> location;
                    case "getVelocity" -> velocity;
                    case "toString" -> "fleet-stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void contactDistanceIsEdgeToEdgePlusTheDerivedMargin() {
        float margin = CoopNpcThreatWatcher.handoffMargin(340f, 200);
        assertEquals(150f + 220f + margin,
                CoopNpcThreatWatcher.contactDistance(150f, 220f, 340f, 200), 0.001f);
        // Negative radii are engine junk, not a way to shrink the band.
        assertEquals(margin, CoopNpcThreatWatcher.contactDistance(-5f, -5f, 340f, 200), 0.001f);
    }
}
