package coop.campaign;

import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import coop.fleet.CoopEntityIds;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterGhostOrbitSyncTest {
    private static final String FOCUS = "coopenc_alpha_system3";
    private static final String GHOST = "coopenc_alpha_system4";

    @Test void pausedGhostSyncRetainsHostPoseAtFocusDespiteNativeAngleSetterRecomputingPosition() {
        Body focus = new Body("native-planet", FOCUS, "planet"); focus.position.set(492, -757);
        Body host = ghost("native-host-ghost", focus), guest = ghost("native-guest-ghost", focus);
        host.position.set(focus.position); host.velocity.set(7, -3);
        guest.position.set(focus.position); guest.velocity.set(1, 2);
        CoopOrbitSync.OrbitEntry entry = snapshot(host);

        CoopCampaignReplicator.applyOrbitTo(guest.api, entry, Map.of(FOCUS, focus.api), null);

        assertEquals(1, guest.angleSets, "orbit sync must keep updating the ghost's native orbit");
        assertPose(host, guest);
        assertXY(focus.position, guest.position);
        assertNotEquals(guest.computedOrbitPoint().y, guest.position.y, "native creation's off-circle placement is authoritative");
    }

    @Test void progressedHostSnapshotCorrectsFreshReplayCreationPoseAndContinuesAdvancing() {
        Body focus = new Body("native-planet", FOCUS, "planet"); focus.position.set(700, -1200);
        Body host = ghost("native-host-ghost", focus), freshGuest = ghost("native-guest-ghost", focus);
        freshGuest.position.set(492, -757); // immutable creation recipe, before current Host orbit snapshot
        host.angle = 145; host.position.set(host.computedOrbitPoint()); host.velocity.set(-8, 11);

        CoopCampaignReplicator.applyOrbitTo(freshGuest.api, snapshot(host), Map.of(FOCUS, focus.api), null);
        assertEquals(145, freshGuest.angle); assertPose(host, freshGuest);
        host.angle = 120; host.position.set(host.computedOrbitPoint()); host.velocity.set(-5, 13);
        CoopCampaignReplicator.applyOrbitTo(freshGuest.api, snapshot(host), Map.of(FOCUS, focus.api), null);
        assertEquals(120, freshGuest.angle); assertPose(host, freshGuest);
        assertEquals(2, freshGuest.angleSets);
    }

    @Test void geometryResetAlsoRestoresCurrentHostPoseAfterInstallingTheOrbit() {
        Body focus = new Body("native-planet", FOCUS, "planet"); focus.position.set(492, -757);
        Body host = ghost("native-host-ghost", focus), guest = ghost("native-guest-ghost", focus);
        host.position.set(focus.position); host.velocity.set(7, -3);
        guest.radius = 999; guest.period = 99;
        CoopCampaignReplicator.applyOrbitTo(guest.api, snapshot(host), Map.of(FOCUS, focus.api), null);
        assertEquals(1, guest.orbitSets); assertEquals(0, guest.angleSets);
        assertEquals(host.radius, guest.radius); assertEquals(host.period, guest.period);
        assertSame(focus.api, guest.focus); assertPose(host, guest);
    }

    @Test void guestOnlyNativeSpinIsCorrectedByCurrentHostFacingOnEverySnapshot() {
        Body focus = new Body("native-planet", FOCUS, "planet");
        Body host = ghost("native-host-ghost", focus), guest = ghost("native-guest-ghost", focus);
        host.facing = 150; guest.facing = 240; // only Guest's location ran native derelict spin
        CoopCampaignReplicator.applyOrbitTo(guest.api, snapshot(host), Map.of(FOCUS, focus.api), null);
        assertEquals(150, guest.facing);
        guest.facing += 17;
        CoopCampaignReplicator.applyOrbitTo(guest.api, snapshot(host), Map.of(FOCUS, focus.api), null);
        assertEquals(150, guest.facing);
        host.facing = 160;
        CoopCampaignReplicator.applyOrbitTo(guest.api, snapshot(host), Map.of(FOCUS, focus.api), null);
        assertEquals(160, guest.facing); assertEquals(3, guest.angleSets);
    }

    @Test void poseCaptureRequiresOwnedCanonicalNativeGhostAndLeavesOrdinaryOrbitApplyUnchanged() {
        Body focus = new Body("native-planet", FOCUS, "planet"); focus.position.set(492, -757);
        Body owned = ghost("native-ghost", focus);
        Body ordinary = new Body("ordinary", "ordinary", "station"); ordinary.focus = focus.api;
        Body nativeUnmanaged = ghost("native-unmanaged", focus); nativeUnmanaged.memory.clear();
        Body withoutGhostFlag = ghost("native-no-flag", focus); withoutGhostFlag.memory.remove("$gsType");
        Body wrongOwner = ghost("native-wrong-owner", focus); wrongOwner.memory.put(CoopEncounterWorld.ENCOUNTER_ID_KEY, "beta");
        Body subclass = ghost("custom-subclass", focus); subclass.plugin = new DerelictShipEntityPlugin() { };
        List<CoopOrbitSync.OrbitEntry> entries = CoopCampaignReplicator.orbitEntries(
                List.of(owned.api, ordinary.api, nativeUnmanaged.api, withoutGhostFlag.api, wrongOwner.api, subclass.api));
        assertNotNull(entries.get(0).pose());
        assertTrue(entries.subList(1, entries.size()).stream().allMatch(e -> e.pose() == null));
        assertEquals(5, CoopDelimited.split(CoopOrbitSync.encode(List.of(entries.get(1)))).size());

        CoopCampaignReplicator.applyOrbitTo(ordinary.api,
                new CoopOrbitSync.OrbitEntry("ordinary", FOCUS, ordinary.radius, ordinary.period, 90), Map.of(FOCUS, focus.api), null);
        assertEquals(1, ordinary.angleSets); assertEquals(0, ordinary.locationSets);
        assertXY(ordinary.computedOrbitPoint(), ordinary.position);
        assertThrows(IllegalArgumentException.class, () -> CoopCampaignReplicator.applyOrbitTo(ordinary.api,
                entries.get(0), Map.of(FOCUS, focus.api), null));
        assertEquals(1, ordinary.angleSets, "invalid exact-pose targeting must fail before changing the orbit");
    }

    private static CoopOrbitSync.OrbitEntry snapshot(Body host) {
        return CoopOrbitSync.decode(CoopOrbitSync.encode(CoopCampaignReplicator.orbitEntries(List.of(host.api)))).get(0);
    }
    private static Body ghost(String id, Body focus) {
        Body ghost = new Body(id, GHOST, "wreck"); ghost.focus = focus.api;
        ghost.memory.put("$gsType", "GS_ACADEMY"); return ghost;
    }
    private static void assertPose(Body expected, Body actual) {
        assertXY(expected.position, actual.position); assertXY(expected.velocity, actual.velocity);
        assertEquals(expected.facing, actual.facing);
    }
    private static void assertXY(Vector2f expected, Vector2f actual) {
        assertEquals(expected.x, actual.x); assertEquals(expected.y, actual.y);
    }

    private static final class Body {
        final CustomCampaignEntityAPI api;
        final Map<String, Object> memory = new HashMap<>();
        final Vector2f position = new Vector2f(), velocity = new Vector2f();
        DerelictShipEntityPlugin plugin = new DerelictShipEntityPlugin();
        SectorEntityToken focus;
        float radius = 352, period = 29.6f, angle = 289.36f;
        float facing;
        int angleSets, orbitSets, locationSets;
        Body(String id, String key, String type) {
            memory.put(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, key);
            memory.put(CoopEncounterWorld.ENCOUNTER_ID_KEY, "alpha");
            MemoryAPI nativeMemory = (MemoryAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{MemoryAPI.class}, (p, m, a) -> switch (m.getName()) {
                        case "get" -> memory.get(a[0]);
                        case "contains" -> memory.containsKey(a[0]);
                        default -> ProxyDefaults.defaultValue(m.getReturnType());
                    });
            api = (CustomCampaignEntityAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{CustomCampaignEntityAPI.class}, (p, m, a) -> switch (m.getName()) {
                        case "getId" -> id;
                        case "getMemoryWithoutUpdate" -> nativeMemory;
                        case "getCustomEntityType" -> type;
                        case "getCustomPlugin" -> plugin;
                        case "getLocation" -> position;
                        case "getVelocity" -> velocity;
                        case "getFacing" -> facing;
                        case "setFacing" -> { facing = (Float) a[0]; yield null; }
                        case "getOrbitFocus" -> focus;
                        case "getCircularOrbitRadius" -> radius;
                        case "getCircularOrbitPeriod" -> period;
                        case "getCircularOrbitAngle" -> angle;
                        case "setCircularOrbitAngle" -> { angleSets++; angle = (Float) a[0]; position.set(computedOrbitPoint()); yield null; }
                        case "setCircularOrbit" -> {
                            orbitSets++; focus = (SectorEntityToken) a[0]; angle = (Float) a[1]; radius = (Float) a[2]; period = (Float) a[3];
                            position.set(computedOrbitPoint()); yield null;
                        }
                        case "setLocation" -> { locationSets++; position.set((Float) a[0], (Float) a[1]); yield null; }
                        default -> ProxyDefaults.defaultValue(m.getReturnType());
                    });
        }
        Vector2f computedOrbitPoint() {
            return new Vector2f(focus.getLocation().x + (float) Math.cos(Math.toRadians(angle)) * radius,
                    focus.getLocation().y + (float) Math.sin(Math.toRadians(angle)) * radius);
        }
    }
}
