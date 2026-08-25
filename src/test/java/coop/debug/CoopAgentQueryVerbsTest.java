package coop.debug;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import coop.fleet.CoopLocations;
import coop.net.CoopConnectionRole;
import coop.time.CoopSharedPauseCoordinator;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query and setup verbs themselves, driven against engine interfaces faked with dynamic proxies.
 *
 * <p>These cover the four defects the first live bridged run turned up — a survey lookup that refused
 * ids its own enumerator emits, an {@code ability} verb that could only ever re-arm a toggle, player
 * fleets keyed per-instance so one logical fleet diffed as four, and a visibility dump that was text
 * rather than something two clients could compare — plus the {@code markets} index and the
 * {@code status} pause block those runs needed and did not have.
 *
 * <p>Proxies rather than hand-written stubs because the engine interfaces are wide and only a handful
 * of methods matter per test; anything unanswered returns the zero value for its return type, which is
 * what the probe's best-effort accessors are written to survive anyway.
 */
class CoopAgentQueryVerbsTest {

    private static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    private static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    @BeforeEach
    @AfterEach
    void resetLocationCache() {
        // CoopLocations caches per sector identity and the fakes are throwaway sectors.
        CoopLocations.invalidate();
    }

    // ---- survey: an id the "all" dump emits must resolve --------------------------------------

    @Test
    void aSystemIdIsResolvedByIdBecauseTheEngineLookupMatchesOnName() {
        // The live failure: {"systemId":"system_16cf"} came back "no star system with id system_16cf"
        // while every other verb emitted that same id as a locationId. getStarSystem() matches names.
        StarSystemAPI system = proxy(StarSystemAPI.class, answers(
                "getId", args -> "system_16cf",
                "getName", args -> "Nowhere"));
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getStarSystems", args -> List.of(system),
                "getStarSystem", args -> null,
                "getAllLocations", args -> List.of()));

        assertSame(system, CoopAgentCommands.resolveSurveyScope(sector, "system_16cf"));
    }

    @Test
    void theOldNameLookupStaysAsTheFallback() {
        StarSystemAPI system = proxy(StarSystemAPI.class, answers("getId", args -> "system_04ab"));
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getStarSystems", args -> List.of(),
                "getStarSystem", args -> "Corvus".equals(args[0]) ? system : null,
                "getAllLocations", args -> List.of()));

        assertSame(system, CoopAgentCommands.resolveSurveyScope(sector, "Corvus"));
    }

    @Test
    void anIdThatIsNeitherIsStillNullSoTheVerbCanRefuseIt() {
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getStarSystems", args -> List.of(),
                "getStarSystem", args -> null,
                "getAllLocations", args -> List.of()));

        assertNull(CoopAgentCommands.resolveSurveyScope(sector, "not-a-place"));
    }

    // ---- ability: on/off is a level, no argument is a press -------------------------------------

    @Test
    void abilityWithNoArgumentIsStillThePlainToolbarPress() throws JSONException {
        FakeAbility ability = new FakeAbility(true);

        CoopAgentCommands.ability(args("abilityId", "transponder"), abilityContext(ability));

        assertEquals(1, ability.activateCalls, "no \"on\" argument means press the button, as before");
        assertEquals(0, ability.deactivateCalls);
    }

    @Test
    void abilityOnIsIdempotent() throws JSONException {
        FakeAbility off = new FakeAbility(false);
        JSONObject turnOn = args("abilityId", "transponder");
        turnOn.put("on", true);

        CoopAgentCommands.ability(turnOn, abilityContext(off));
        assertEquals(1, off.activateCalls, "an ability that was off must be activated");

        off.active = true;
        CoopAgentCommands.ability(turnOn, abilityContext(off));
        assertEquals(1, off.activateCalls, "on again must not re-arm an already-active ability");
        assertEquals(0, off.deactivateCalls);
    }

    @Test
    void abilityOffIsIdempotent() throws JSONException {
        FakeAbility on = new FakeAbility(true);
        JSONObject turnOff = args("abilityId", "transponder");
        turnOff.put("on", false);

        JSONObject response = CoopAgentCommands.ability(turnOff, abilityContext(on));
        assertEquals(1, on.deactivateCalls, "an active ability must be deactivated");
        assertEquals(0, on.activateCalls, "off must never call activate(); that was the live bug");
        assertFalse(response.getBoolean("active"));

        on.active = false;
        CoopAgentCommands.ability(turnOff, abilityContext(on));
        assertEquals(1, on.deactivateCalls, "off again on an inactive ability is a no-op");
    }

    @Test
    void abilityAcceptsTheWordFormsThePauseVerbAccepts() throws JSONException {
        FakeAbility ability = new FakeAbility(true);
        JSONObject offByWord = args("abilityId", "transponder");
        offByWord.put("on", "off");

        CoopAgentCommands.ability(offByWord, abilityContext(ability));

        assertEquals(1, ability.deactivateCalls);
    }

    // ---- status: the pause block ----------------------------------------------------------------

    @Test
    void theHostPauseBlockNamesWhichIntentHoldsTheClock() throws JSONException {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.applyGuestScreenPauseIntent(true, 1L);

        JSONObject pause = CoopAgentCommands.pauseBlock(CoopConnectionRole.HOST, coordinator, false);

        assertFalse(pause.getBoolean("blockingScreenOpen"), "the host itself has no screen open");
        assertFalse(pause.getBoolean("hostIntent"));
        assertTrue(pause.getBoolean("guestIntent"), "the guest's screen is what is holding the clock");
        assertFalse(pause.getBoolean("guestKeyIntent"));
        assertTrue(pause.getBoolean("guestScreenIntent"));
        assertFalse(pause.getBoolean("eitherInCombat"));
        assertTrue(pause.getBoolean("effective"));
    }

    @Test
    void theGuestPauseBlockCarriesItsOwnScreenStateAndNoAuthorityFields() throws JSONException {
        JSONObject pause = CoopAgentCommands.pauseBlock(
                CoopConnectionRole.GUEST, new CoopSharedPauseCoordinator(), true);

        assertTrue(pause.getBoolean("blockingScreenOpen"));
        assertFalse(pause.has("hostIntent"),
                "a guest's coordinator holds its outgoing intents, not the authority's; reporting them"
                        + " as the breakdown would be a lie the diff would chase");
        assertFalse(pause.has("effective"));
    }

    @Test
    void statusCarriesThePauseBlockEvenWithNoSessionAtAll() throws JSONException {
        SectorAPI sector = proxy(SectorAPI.class, answers("isPaused", args -> Boolean.TRUE));

        JSONObject status = CoopAgentCommands.status(new JSONObject(), contextFor(sector));

        assertTrue(status.getBoolean("paused"));
        JSONObject pause = status.getJSONObject("pause");
        assertFalse(pause.getBoolean("blockingScreenOpen"), "no campaign UI, no blocking screen");
        assertFalse(pause.has("hostIntent"), "role NONE has no intent breakdown");
    }

    // ---- markets: enumeration only ---------------------------------------------------------------

    @Test
    void marketsEnumeratesTheEconomySortedAndWithoutStockingAnything() throws JSONException {
        FakeMarket jangala = new FakeMarket("jangala", "Jangala", "hegemony", 6, "corvus");
        FakeMarket asharu = new FakeMarket("asharu", "Asharu", "independent", 4, "askonia");
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getEconomy", args -> proxy(EconomyAPI.class, answers(
                        "getMarketsCopy", ignored -> List.of(jangala.proxy(), asharu.proxy())))));

        JSONObject out = CoopAgentCommands.markets(new JSONObject(), contextFor(sector));

        assertEquals(2, out.getInt("count"));
        JSONObject first = out.getJSONArray("markets").getJSONObject(0);
        assertEquals("asharu", first.getString("marketId"), "sorted by marketId so two dumps line up");
        assertEquals("Asharu", first.getString("name"));
        assertEquals("independent", first.getString("factionId"));
        assertEquals(4, first.getInt("size"));
        assertEquals("askonia", first.getString("locationId"));
        assertEquals("jangala", out.getJSONArray("markets").getJSONObject(1).getString("marketId"));

        for (String called : jangala.calls) {
            assertFalse(called.toLowerCase(java.util.Locale.ROOT).contains("submarket")
                            || called.contains("updateCargoPrePlayerInteraction"),
                    "markets is an index, not a dock visit; it must not touch stock: " + called);
        }
    }

    @Test
    void marketsIsEmptyRatherThanAnErrorWithNoEconomy() throws JSONException {
        JSONObject out = CoopAgentCommands.markets(new JSONObject(),
                contextFor(proxy(SectorAPI.class, answers())));

        assertEquals(0, out.getInt("count"));
    }

    // ---- fleets: one logical player fleet, one key ------------------------------------------------

    @Test
    void aPlayerFleetAndItsRemoteMirrorCarryTheSameKeyOnBothInstances() {
        // Host instance: its own fleet plus the guest's mirror. Guest instance: the reverse. Before the
        // fix each side keyed both fleets by its own engine ids, so a diff showed four one-sided rows.
        CampaignFleetAPI hostOwn = fleet("engine_host_own", memory(false, null));
        CampaignFleetAPI guestMirrorOnHost = fleet("engine_mirror_of_guest", memory(true, null));
        CampaignFleetAPI guestOwn = fleet("engine_guest_own", memory(false, null));
        CampaignFleetAPI hostMirrorOnGuest = fleet("engine_mirror_of_host", memory(true, null));

        String hostSideHost = CoopAgentCommands.coopFleetKey(
                hostOwn, hostOwn, guestMirrorOnHost, "player-H", "player-G");
        String hostSideGuest = CoopAgentCommands.coopFleetKey(
                guestMirrorOnHost, hostOwn, guestMirrorOnHost, "player-H", "player-G");
        String guestSideGuest = CoopAgentCommands.coopFleetKey(
                guestOwn, guestOwn, hostMirrorOnGuest, "player-G", "player-H");
        String guestSideHost = CoopAgentCommands.coopFleetKey(
                hostMirrorOnGuest, guestOwn, hostMirrorOnGuest, "player-G", "player-H");

        assertEquals("player:player-H", hostSideHost);
        assertEquals("player:player-H", guestSideHost);
        assertEquals("player:player-G", hostSideGuest);
        assertEquals("player:player-G", guestSideGuest);
    }

    @Test
    void anNpcFleetKeepsTheHostFleetIdAndAnUnsessionedFleetKeepsItsEngineId() {
        CampaignFleetAPI npcOnHost = fleet("fleet_A", memory(false, null));
        CampaignFleetAPI npcOnGuest = fleet("engine_mirror_A", memory(false, "fleet_A"));
        CampaignFleetAPI ownWithNoSession = fleet("engine_own", memory(false, null));

        assertEquals("fleet_A", CoopAgentCommands.coopFleetKey(npcOnHost, null, null, "H", "G"));
        assertEquals("fleet_A", CoopAgentCommands.coopFleetKey(npcOnGuest, null, null, "G", "H"));
        assertEquals("engine_own", CoopAgentCommands.coopFleetKey(
                ownWithNoSession, ownWithNoSession, null, null, null),
                "with no handshake there is no player id to key on; the engine id is the fallback");
    }

    // ---- visibility: the two sides' views are comparable -------------------------------------------

    @Test
    void theHostEstimateAndTheGuestActualViewAreTheSameObjectWhenTheSensorModelAgrees()
            throws JSONException {
        SectorEntityToken.VisibilityLevel agreed = SectorEntityToken.VisibilityLevel.SENSOR_CONTACT;
        CampaignFleetAPI hostPlayer = fleet("engine_host_own", memory(false, null));
        CampaignFleetAPI guestMirror = fleet("engine_mirror_of_guest", memory(true, null));
        CampaignFleetAPI npc = visibleFleet("fleet_A", memory(false, null), guestMirror, agreed);
        SectorAPI hostSector = sectorWithFleets(hostPlayer, List.of(hostPlayer, guestMirror, npc));

        CampaignFleetAPI guestPlayer = fleet("engine_guest_own", memory(false, null));
        CampaignFleetAPI npcMirror = visibleFleet(
                "engine_mirror_A", memory(false, "fleet_A"), null, agreed);
        SectorAPI guestSector = sectorWithFleets(guestPlayer, List.of(guestPlayer, npcMirror));

        JSONObject hostView = CoopAgentCommands
                .visibilityFor(hostSector, CoopConnectionRole.HOST, "").getJSONObject("view");
        CoopLocations.invalidate();
        JSONObject guestOut = CoopAgentCommands.visibilityFor(guestSector, CoopConnectionRole.GUEST, "");
        JSONObject guestView = guestOut.getJSONObject("view");

        assertEquals("{\"fleet_A\":\"SENSOR_CONTACT\"}", hostView.toString());
        assertEquals(hostView.toString(), guestView.toString(),
                "the host's guestView estimate and the guest's actual view are the same claim");
        assertEquals(1, guestOut.getInt("viewCount"));
        assertTrue(guestOut.getJSONArray("lines").length() > 0, "the raw dump stays available");
    }

    @Test
    void theFleetIdFilterNarrowsTheViewAsWellAsTheLines() throws JSONException {
        SectorEntityToken.VisibilityLevel agreed = SectorEntityToken.VisibilityLevel.NONE;
        CampaignFleetAPI guestPlayer = fleet("engine_guest_own", memory(false, null));
        CampaignFleetAPI wanted = visibleFleet("engine_m1", memory(false, "fleet_A"), null, agreed);
        CampaignFleetAPI other = visibleFleet("engine_m2", memory(false, "fleet_B"), null, agreed);
        SectorAPI sector = sectorWithFleets(guestPlayer, List.of(guestPlayer, wanted, other));

        JSONObject out = CoopAgentCommands.visibilityFor(sector, CoopConnectionRole.GUEST, "fleet_A");

        assertEquals(1, out.getInt("viewCount"));
        assertEquals("NONE", out.getJSONObject("view").getString("fleet_A"));
    }

    // ---- Fakes --------------------------------------------------------------------------------------

    /** A recording {@link AbilityPlugin}; the counters are the whole point of the on/off tests. */
    private static final class FakeAbility {
        private boolean active;
        private int activateCalls;
        private int deactivateCalls;

        private FakeAbility(boolean active) {
            this.active = active;
        }

        private AbilityPlugin proxy() {
            return CoopAgentQueryVerbsTest.proxy(AbilityPlugin.class, answers(
                    "activate", args -> {
                        activateCalls++;
                        active = true;
                        return null;
                    },
                    "deactivate", args -> {
                        deactivateCalls++;
                        active = false;
                        return null;
                    },
                    "isActive", args -> active,
                    "isActiveOrInProgress", args -> active));
        }
    }

    /** A market that remembers every method the verb called on it. */
    private static final class FakeMarket {
        private final String id;
        private final String name;
        private final String factionId;
        private final int size;
        private final String locationId;
        private final List<String> calls = new ArrayList<>();

        private FakeMarket(String id, String name, String factionId, int size, String locationId) {
            this.id = id;
            this.name = name;
            this.factionId = factionId;
            this.size = size;
            this.locationId = locationId;
        }

        private MarketAPI proxy() {
            Map<String, Answer> answers = answers(
                    "getId", args -> id,
                    "getName", args -> name,
                    "getFactionId", args -> factionId,
                    "getSize", args -> size,
                    "getContainingLocation", args -> location(locationId, List.of()));
            return (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (p, method, args) -> {
                        calls.add(method.getName());
                        return invoke(answers, p, method, args);
                    });
        }
    }

    private static CoopAgentCommands.Context abilityContext(FakeAbility ability) {
        AbilityPlugin plugin = ability.proxy();
        CampaignFleetAPI player = proxy(CampaignFleetAPI.class, answers(
                "getAbility", args -> "transponder".equals(args[0]) ? plugin : null));
        return contextFor(proxy(SectorAPI.class, answers("getPlayerFleet", args -> player)));
    }

    private static CoopAgentCommands.Context contextFor(SectorAPI sector) {
        return new CoopAgentCommands.Context() {
            @Override
            public SectorAPI sector() {
                return sector;
            }

            @Override
            public coop.net.CoopNetPump pump() {
                return null;
            }
        };
    }

    private static SectorAPI sectorWithFleets(CampaignFleetAPI player, List<CampaignFleetAPI> fleets) {
        LocationAPI location = location("corvus", fleets);
        return proxy(SectorAPI.class, answers(
                "getPlayerFleet", args -> player,
                "getAllLocations", args -> List.of(location)));
    }

    private static LocationAPI location(String id, List<CampaignFleetAPI> fleets) {
        return proxy(LocationAPI.class, answers(
                "getId", args -> id,
                "getFleets", args -> new ArrayList<>(fleets)));
    }

    private static CampaignFleetAPI fleet(String engineId, MemoryAPI memory) {
        return proxy(CampaignFleetAPI.class, answers(
                "getId", args -> engineId,
                "getName", args -> engineId,
                "getMemoryWithoutUpdate", args -> memory));
    }

    /** A fleet that answers the two visibility questions the probe asks of each side. */
    private static CampaignFleetAPI visibleFleet(String engineId, MemoryAPI memory,
                                                 CampaignFleetAPI expectedObserver,
                                                 SectorEntityToken.VisibilityLevel level) {
        return proxy(CampaignFleetAPI.class, answers(
                "getId", args -> engineId,
                "getName", args -> engineId,
                "getMemoryWithoutUpdate", args -> memory,
                "getVisibilityLevelToPlayerFleet", args -> level,
                "getVisibilityLevelTo", args ->
                        expectedObserver == null || args[0] == expectedObserver
                                ? level : SectorEntityToken.VisibilityLevel.NONE));
    }

    private static MemoryAPI memory(boolean playerMirror, String npcMirrorId) {
        return proxy(MemoryAPI.class, answers(
                "getBoolean", args -> PLAYER_MIRROR_TAG.equals(args[0]) && playerMirror,
                "contains", args -> NPC_MIRROR_TAG.equals(args[0]) && npcMirrorId != null,
                "getString", args -> NPC_MIRROR_TAG.equals(args[0]) ? npcMirrorId : null,
                "get", args -> NPC_MIRROR_TAG.equals(args[0]) ? npcMirrorId : null));
    }

    private static JSONObject args(String key, String value) throws JSONException {
        JSONObject args = new JSONObject();
        args.put(key, value);
        return args;
    }

    // ---- Proxy plumbing -------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Answer {
        Object answer(Object[] args);
    }

    // Fixed arities rather than varargs: a lambda cannot be inferred against an Object... parameter.

    private static Map<String, Answer> answers() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Answer> answers(String n1, Answer a1) {
        Map<String, Answer> map = answers();
        map.put(n1, a1);
        return map;
    }

    private static Map<String, Answer> answers(String n1, Answer a1, String n2, Answer a2) {
        Map<String, Answer> map = answers(n1, a1);
        map.put(n2, a2);
        return map;
    }

    private static Map<String, Answer> answers(String n1, Answer a1, String n2, Answer a2,
                                               String n3, Answer a3) {
        Map<String, Answer> map = answers(n1, a1, n2, a2);
        map.put(n3, a3);
        return map;
    }

    private static Map<String, Answer> answers(String n1, Answer a1, String n2, Answer a2,
                                               String n3, Answer a3, String n4, Answer a4) {
        Map<String, Answer> map = answers(n1, a1, n2, a2, n3, a3);
        map.put(n4, a4);
        return map;
    }

    private static Map<String, Answer> answers(String n1, Answer a1, String n2, Answer a2,
                                               String n3, Answer a3, String n4, Answer a4,
                                               String n5, Answer a5) {
        Map<String, Answer> map = answers(n1, a1, n2, a2, n3, a3, n4, a4);
        map.put(n5, a5);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Answer> answers) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (p, method, args) -> invoke(answers, p, method, args));
    }

    /** Anything unanswered is the zero value for its return type — the engine's own best-effort shape. */
    private static Object invoke(Map<String, Answer> answers, Object self, java.lang.reflect.Method method,
                                 Object[] args) {
        Object[] safeArgs = args == null ? new Object[0] : args;
        switch (method.getName()) {
            case "toString":
                return "Fake" + method.getDeclaringClass().getSimpleName() + "@"
                        + System.identityHashCode(self);
            case "hashCode":
                return System.identityHashCode(self);
            case "equals":
                return self == safeArgs[0];
            default:
                break;
        }
        Answer answer = answers.get(method.getName());
        if (answer != null) {
            return answer.answer(safeArgs);
        }
        return zeroFor(method.getReturnType());
    }

    private static Object zeroFor(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
