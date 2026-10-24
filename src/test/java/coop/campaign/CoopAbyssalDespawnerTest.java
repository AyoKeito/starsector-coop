package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.NascentGravityWellAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import coop.fleet.CoopLocations;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoopAbyssalDespawnerTest {
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;
    private float nowDays;
    private float hostVisitDays = 20f;
    private Vector2f hostPosition = new Vector2f(10000f, 0f);
    private Vector2f guestPosition = new Vector2f(10000f, 0f);
    private LocationAPI hostLocation;
    private LocationAPI guestLocation;
    private final List<SectorEntityToken> removedLinks = new ArrayList<>();
    private final List<StarSystemAPI> queuedSystems = new ArrayList<>();
    private final List<SectorEntityToken> jumpPoints = new ArrayList<>();
    private final List<NascentGravityWellAPI> wells = new ArrayList<>();
    private final List<LocationAPI> locations = new ArrayList<>();
    private LocationAPI hyper;
    private StarSystemAPI system;
    private SectorAPI sector;
    private SectorEntityToken guest;
    private SectorEntityToken anchor;

    @BeforeEach
    void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        CoopLocations.invalidate();
        Global.setSettings((SettingsAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SettingsAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getColor" -> Color.WHITE;
                    case "getFloat" -> "unitsPerLightYear".equals(a[0]) ? 1000f : 0f;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                }));
        hyper = (LocationAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LocationAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getId" -> "hyperspace";
                    case "getJumpPoints" -> jumpPoints;
                    case "getGravityWells" -> wells;
                    case "removeEntity" -> { removedLinks.add((SectorEntityToken) a[0]); yield null; }
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        anchor = proxy(SectorEntityToken.class, Map.of("getId", "anchor"));
        system = (StarSystemAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{StarSystemAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getId" -> "deep space";
                    case "getOptionalUniqueId" -> "temp-a";
                    case "getLocation" -> new Vector2f();
                    case "getHyperspaceAnchor" -> anchor;
                    case "getDaysSinceLastPlayerVisit" -> hostVisitDays;
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        hostLocation = hyper;
        guestLocation = hyper;
        CampaignFleetAPI host = (CampaignFleetAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getLocationInHyperspace" -> hostPosition;
                    case "getContainingLocation" -> hostLocation;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        guest = (SectorEntityToken) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SectorEntityToken.class}, (p, m, a) -> switch (m.getName()) {
                    case "getLocationInHyperspace" -> guestPosition;
                    case "getContainingLocation" -> guestLocation;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        CampaignClockAPI clock = (CampaignClockAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CampaignClockAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getTimestamp" -> Math.round((double) nowDays * MILLIS_PER_DAY);
                    case "getElapsedDaysSince" -> (float) ((Math.round((double) nowDays * MILLIS_PER_DAY)
                            - (Long) a[0]) / (double) MILLIS_PER_DAY);
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        locations.addAll(List.of(system, hyper));
        sector = (SectorAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SectorAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getClock" -> clock;
                    case "getPlayerFleet" -> host;
                    case "getHyperspace" -> hyper;
                    case "getAllLocations" -> locations;
                    case "removeStarSystemNextFrame" -> {
                        queuedSystems.add((StarSystemAPI) a[0]);
                        yield null;
                    }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        Global.setSector(sector);
    }

    @AfterEach
    void cleanup() {
        CoopLocations.invalidate();
        Global.setSettings(previousSettings);
        Global.setSector(previousSector);
    }

    @Test
    void firstRuntimeUpdateIsRequiredButNeverSeenGuestDoesNotPinSoloSystems() {
        CoopAbyssalDespawner script = new CoopAbyssalDespawner(system);
        assertFalse(script.canDespawnNow(sector));
        script.advance(10f);
        assertTrue(queuedSystems.isEmpty());
        script.updateGuestPresence(null, false);
        assertTrue(script.canDespawnNow(sector));
    }

    @Test
    void hostMustMeetBothNativeThresholdsIncludingTheirExactBoundaries() {
        CoopAbyssalDespawner script = solo();
        hostVisitDays = 9.999f;
        assertFalse(script.canDespawnNow(sector));
        hostVisitDays = 10f;
        hostPosition.x = 4999.9f;
        assertFalse(script.canDespawnNow(sector));
        hostPosition.x = 5000f;
        assertTrue(script.canDespawnNow(sector));
        hostLocation = system;
        assertFalse(script.canDespawnNow(sector), "current occupancy overrides any stale native visit counter");
    }

    @Test
    void firstGuestObservationStartsTenDaysEvenWithoutAVisit() {
        CoopAbyssalDespawner script = new CoopAbyssalDespawner(system);
        script.updateGuestPresence(guest, false);
        nowDays = 9.999f;
        assertFalse(script.canDespawnNow(sector));
        nowDays = 10f;
        assertTrue(script.canDespawnNow(sector));
    }

    @Test
    void guestOccupancyAndEveryVisitRefreshTheGuestTimer() {
        CoopAbyssalDespawner script = new CoopAbyssalDespawner(system);
        guestLocation = system;
        script.updateGuestPresence(guest, false);
        nowDays = 20f;
        script.updateGuestPresence(guest, false);
        assertFalse(script.canDespawnNow(sector));
        guestLocation = hyper;
        script.updateGuestPresence(guest, false);
        nowDays = 29.999f;
        assertFalse(script.canDespawnNow(sector));
        nowDays = 30f;
        assertTrue(script.canDespawnNow(sector));
    }

    @Test
    void guestDistanceHasItsOwnFiveLyBoundaryAndDoesNotRefreshOutsideVisitTime() {
        CoopAbyssalDespawner script = new CoopAbyssalDespawner(system);
        script.updateGuestPresence(guest, false);
        nowDays = 20f;
        guestPosition.x = 4999.9f;
        script.updateGuestPresence(guest, false);
        assertFalse(script.canDespawnNow(sector));
        guestPosition.x = 5000f;
        script.updateGuestPresence(guest, false);
        assertTrue(script.canDespawnNow(sector));
    }

    @Test
    void reconnectGraceHoldsEvenWhenBothNativeCriteriaPass() {
        CoopAbyssalDespawner script = solo();
        script.updateGuestPresence(null, true);
        script.advance(100f);
        assertFalse(script.canDespawnNow(sector));
        assertTrue(queuedSystems.isEmpty());
        script.updateGuestPresence(null, false);
        assertTrue(script.canDespawnNow(sector));
    }

    @Test
    void disconnectedGuestInsideSurvivesSaveLoadAndManySoloHostDays() throws Exception {
        CoopAbyssalDespawner original = new CoopAbyssalDespawner(system);
        guestLocation = system;
        original.updateGuestPresence(guest, false);
        CoopAbyssalDespawner restored = savedCopy(original);
        nowDays = 100f;
        assertFalse(restored.canDespawnNow(sector), "runtime readiness must not survive a load");
        restored.updateGuestPresence(null, false);
        assertFalse(restored.canDespawnNow(sector), "session end cannot release the saved inside hold");
        restored.advance(100f);
        assertTrue(queuedSystems.isEmpty());

        guestLocation = hyper;
        restored.updateGuestPresence(guest, false);
        assertFalse(restored.canDespawnNow(sector), "confirmed departure starts a new visit-age window");
        nowDays = 110f;
        assertTrue(restored.canDespawnNow(sector));
    }

    @Test
    void disconnectedOutsideGuestRetainsItsLastDistanceAndVisitAge() throws Exception {
        CoopAbyssalDespawner script = new CoopAbyssalDespawner(system);
        guestPosition.x = 4000f;
        script.updateGuestPresence(guest, false);
        script = savedCopy(script);
        nowDays = 30f;
        script.updateGuestPresence(null, false);
        assertFalse(script.canDespawnNow(sector));
        guestPosition.x = 6000f;
        script.updateGuestPresence(guest, false);
        assertTrue(script.canDespawnNow(sector));
        script.updateGuestPresence(null, false);
        assertTrue(script.canDespawnNow(sector));
    }

    @Test
    void scriptPollsOnNativeScaleAndRemovesOnlyOnce() {
        CoopAbyssalDespawner script = solo();
        script.advance(1f);
        assertTrue(queuedSystems.isEmpty());
        script.advance(0.5f);
        assertEquals(List.of(system), queuedSystems);
        assertEquals(List.of(anchor), removedLinks);
        assertTrue(script.isDone());
        assertNull(script.getSystem());
        script.advance(10f);
        assertEquals(1, queuedSystems.size());
        assertFalse(script.runWhilePaused());
    }

    @Test
    void removalMatchesNativeHyperLinkOwnershipAndLeavesOtherSystemsIntact() {
        StarSystemAPI other = proxy(StarSystemAPI.class, Map.of("getId", "deep space", "getOptionalUniqueId", "temp-b"));
        SectorEntityToken target = proxy(SectorEntityToken.class, Map.of("getStarSystem", system));
        SectorEntityToken unrelated = proxy(SectorEntityToken.class, Map.of("getStarSystem", other));
        JumpPointAPI linked = jump(target);
        JumpPointAPI unlinked = jump(unrelated);
        JumpPointAPI empty = jump();
        JumpPointAPI firstOnly = jump(unrelated, target);
        jumpPoints.addAll(List.of(linked, unlinked, empty, firstOnly));
        NascentGravityWellAPI linkedWell = proxy(NascentGravityWellAPI.class, Map.of("getTarget", target));
        NascentGravityWellAPI otherWell = proxy(NascentGravityWellAPI.class, Map.of("getTarget", unrelated));
        NascentGravityWellAPI noTarget = proxy(NascentGravityWellAPI.class, Map.of());
        wells.addAll(List.of(linkedWell, otherWell, noTarget));

        CoopAbyssalDespawner.removeSystemAndLinks(sector, system);

        assertEquals(List.of(system), queuedSystems);
        assertEquals(List.of(linked, linkedWell, anchor), removedLinks);
        assertFalse(removedLinks.contains(unlinked));
        assertFalse(removedLinks.contains(firstOnly), "native removal checks only the first destination");
    }

    @Test
    void removalInvalidatesTheSharedLocationCacheImmediately() {
        assertSame(system, CoopLocations.byId(sector, "temp-a"));
        CoopAbyssalDespawner.removeSystemAndLinks(sector, system);
        locations.remove(system); // Apply the engine's queued next-frame system removal.
        assertNull(CoopLocations.byId(sector, "temp-a"));
    }

    @Test
    void savedStateContainsOnlyPrimitivesAndTheEngineSystemReference() throws Exception {
        for (Field field : CoopAbyssalDespawner.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            assertTrue(field.getType().isPrimitive() || field.getType() == StarSystemAPI.class, field.getName());
        }
        CoopAbyssalDespawner restored = savedCopy(solo());
        assertFalse(restored.canDespawnNow(sector));
        restored.updateGuestPresence(null, false);
        assertTrue(restored.canDespawnNow(sector));
    }

    @Test
    void invalidPresenceCannotEraseAnInsideHold() {
        CoopAbyssalDespawner script = new CoopAbyssalDespawner(system);
        guestLocation = system;
        script.updateGuestPresence(guest, false);
        guestPosition = new Vector2f(Float.NaN, 0f);
        guestLocation = hyper;
        assertThrows(IllegalStateException.class, () -> script.updateGuestPresence(guest, false));
        nowDays = 100f;
        script.updateGuestPresence(null, false);
        assertFalse(script.canDespawnNow(sector));
    }

    private CoopAbyssalDespawner solo() {
        CoopAbyssalDespawner result = new CoopAbyssalDespawner(system);
        result.updateGuestPresence(null, false);
        return result;
    }

    private CoopAbyssalDespawner savedCopy(CoopAbyssalDespawner original) throws Exception {
        CoopAbyssalDespawner result = new CoopAbyssalDespawner(system);
        for (Field field : CoopAbyssalDespawner.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            field.setAccessible(true);
            field.set(result, field.get(original));
        }
        return result;
    }

    private static JumpPointAPI jump(SectorEntityToken... targets) {
        List<JumpPointAPI.JumpDestination> destinations = new ArrayList<>();
        for (SectorEntityToken target : targets) destinations.add(new JumpPointAPI.JumpDestination(target, ""));
        return proxy(JumpPointAPI.class, Map.of("getDestinations", destinations));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> switch (m.getName()) {
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            case "toString" -> type.getSimpleName();
            default -> answers.containsKey(m.getName()) ? answers.get(m.getName())
                    : ProxyDefaults.defaultValue(m.getReturnType());
        });
    }
}
