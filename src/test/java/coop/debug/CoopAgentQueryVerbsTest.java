package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.campaign.CoopCreditTransfer;
import coop.fleet.CoopLocations;
import coop.fleet.CoopMirrorTags;
import coop.net.CoopConnectionRole;
import coop.testing.FakeCreditEngine;
import coop.time.CoopSharedPauseCoordinator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String PLAYER_MIRROR_TAG = CoopMirrorTags.PLAYER_MIRROR_TAG;
    private static final String NPC_MIRROR_TAG = CoopMirrorTags.NPC_MIRROR_TAG;

    @BeforeEach
    @AfterEach
    void resetLocationCache() {
        // CoopLocations caches per sector identity and the fakes are throwaway sectors.
        CoopLocations.invalidate();
    }

    @BeforeEach
    void installSettings() {
        // Misc.getDistanceLY divides by settings.getFloat("unitsPerLightYear"); colonizable is the
        // only verb here that measures anything, and 2000 is the stock value.
        Global.setSettings(proxy(SettingsAPI.class, answers("getFloat", args -> 2000f)));
    }

    @AfterEach
    void clearInstalledCreditTransfer() {
        // The transfer's handle and pending amount are statics; leaving one installed would put the
        // previous test's ledgers into the next one's status dump.
        CoopCreditTransfer.uninstall();
    }

    @AfterEach
    void clearSector() {
        // hostileActivityBlock reads Global.getSector(); a fake left installed would follow the next
        // test class into its own sector-memory assertions.
        Global.setSector(null);
    }

    @AfterEach
    void clearSettings() {
        Global.setSettings(null);
        // addship is the only verb that reaches for the factory; leaving a fake installed would let
        // an unrelated test create ships out of one.
        Global.setFactory(null);
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

    // ---- status: the credits block ---------------------------------------------------------------

    /**
     * A money smoke has to be checkable from outside the game. A wallet total moves for a dozen
     * reasons; "the ledger id the sender minted is in the receiver's applied set" is the transfer
     * itself, and no log line proves it on the receiving side after a restart.
     */
    @Test
    void theCreditsBlockReportsBothLedgersAndAnswersAboutOneGrant() throws JSONException {
        FakeCreditEngine engine = new FakeCreditEngine(100_000L);
        CoopCreditTransfer transfer = new CoopCreditTransfer(engine, alwaysSendableLink());
        CoopCreditTransfer.install(transfer);
        transfer.send(25_000);
        transfer.receive("host-player-4", 5_000, "gift");

        JSONObject counts = CoopAgentCommands.creditsBlock("");
        assertTrue(counts.getBoolean("installed"));
        assertEquals(1, counts.getInt("appliedCount"));
        assertEquals(1, counts.getInt("sentCount"));
        assertTrue(counts.getBoolean("canSend"));
        assertFalse(counts.has("applied"), "no ledgerId asked about, no answer invented");

        JSONObject applied = CoopAgentCommands.creditsBlock("host-player-4");
        assertEquals("host-player-4", applied.getString("ledgerId"));
        assertTrue(applied.getBoolean("applied"));
        assertFalse(applied.getBoolean("inFlight"), "this one arrived, it was never sent from here");

        JSONObject unknown = CoopAgentCommands.creditsBlock("no-such-ledger");
        assertFalse(unknown.getBoolean("applied"));
        assertFalse(unknown.getBoolean("inFlight"));
    }

    @Test
    void theCreditsBlockSaysSoWhenThereIsNoTransferInstalledAtAll() throws JSONException {
        CoopCreditTransfer.uninstall();

        JSONObject block = CoopAgentCommands.creditsBlock("some-ledger");

        assertFalse(block.getBoolean("installed"));
        assertFalse(block.has("appliedCount"), "counts a torn-down transfer cannot answer for");
        assertFalse(block.has("applied"));
    }

    @Test
    void statusCarriesTheCreditsBlock() throws JSONException {
        SectorAPI sector = proxy(SectorAPI.class, answers("isPaused", args -> Boolean.FALSE));
        CoopCreditTransfer.uninstall();

        JSONObject status = CoopAgentCommands.status(new JSONObject(), contextFor(sector));

        assertFalse(status.getJSONObject("credits").getBoolean("installed"));
    }

    private static CoopCreditTransfer.Link alwaysSendableLink() {
        return new CoopCreditTransfer.Link() {
            private int minted;

            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public String mintLedgerId() {
                return "local-ledger-" + (++minted);
            }

            @Override
            public void sendGrant(String ledgerId, int amount, String reason) {
            }
        };
    }

    // ---- pause: the verb itself ------------------------------------------------------------------

    /**
     * The guest has no authority over the clock, so its {@code pause} is a screen-level intent — and
     * it has to be raised where the pump's own per-frame recompute picks it up. Written into the
     * coordinator's latch instead, it reported {@code changed:true} and was overwritten from the real
     * UI on the next frame, so the host never heard about it at all.
     */
    @Test
    void theGuestPauseVerbPullsALeverThePumpActuallyShips() throws JSONException {
        coop.net.CoopNetPump pump = sessionPump(CoopConnectionRole.GUEST);
        JSONObject on = new JSONObject();
        on.put("on", true);

        JSONObject response = CoopAgentCommands.pause(on, contextFor(null, pump));

        assertEquals("GUEST", response.getString("role"));
        assertTrue(response.getBoolean("changed"));
        assertTrue(pump.bridgePauseIntent(),
                "the lever the pump ORs into the guest's screen intent is what has to move");

        assertFalse(CoopAgentCommands.pause(on, contextFor(null, pump)).getBoolean("changed"),
                "a repeat of the level already held sends nothing");
    }

    @Test
    void pauseIsRefusedWhenThereIsNoSessionToHold() {
        coop.net.CoopNetPump pump = new coop.net.CoopNetPump(new coop.net.CoopNetService());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            JSONObject on = new JSONObject();
            on.put("on", true);
            CoopAgentCommands.pause(on, contextFor(null, pump));
        });

        assertTrue(failure.getMessage().contains("no coop session"), failure.getMessage());
        assertFalse(pump.pauseCoordinatorForBridge().hostPauseIntent(),
                "and nothing was written into a coordinator no session is applying");
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

    // ---- expedition: who gets forced, and why nobody could ----------------------------------------

    @Test
    void theFreePortCandidateWinsSoARepeatedSmokeRunPicksTheSameFaction() {
        CoopAgentCommands.ExpeditionCandidate competition =
                candidate("tritachyon", 4, false, false);
        CoopAgentCommands.ExpeditionCandidate freePort =
                candidate("hegemony", 1, true, false);

        assertSame(freePort, CoopAgentCommands.chooseExpeditionFaction(
                List.of(competition, freePort)),
                "ANTI_FREE_PORT is the only reason the caller can conjure on demand; preferring it"
                        + " keeps the pick deterministic instead of following today's economy");
    }

    @Test
    void aFactionWithNoLiveReasonOrOneAlreadyRunningIsNotACandidate() {
        CoopAgentCommands.ExpeditionCandidate none = candidate("luddic_church", 0, false, false);
        // Reasons and a free port, but its one intel handle is taken: forcing a second would orphan it.
        CoopAgentCommands.ExpeditionCandidate busy = candidate("hegemony", 3, true, true);
        CoopAgentCommands.ExpeditionCandidate usable = candidate("sindrian_diktat", 2, false, false);

        assertSame(usable, CoopAgentCommands.chooseExpeditionFaction(List.of(none, busy, usable)));
        assertNull(CoopAgentCommands.chooseExpeditionFaction(List.of(none, busy)));
    }

    @Test
    void theRefusalNamesTheFreePortPreconditionWhenNothingHasAReason() {
        String one = CoopAgentCommands.noExpeditionCandidateMessage(
                List.of(candidate("hegemony", 0, false, false)));
        assertTrue(one.contains("hegemony") && one.contains("no live punitive expedition reason"), one);
        assertTrue(one.contains(CoopAgentCommands.FREE_PORT_HINT),
                "a caller who cannot see the cause must be told the one they can create: " + one);

        String many = CoopAgentCommands.noExpeditionCandidateMessage(List.of(
                candidate("hegemony", 0, false, false),
                candidate("luddic_church", 0, false, false),
                candidate("sindrian_diktat", 2, true, true)));
        assertTrue(many.contains("none of the 3") && many.contains("(1 already running one)"), many);
        assertTrue(many.contains(CoopAgentCommands.FREE_PORT_HINT), many);
    }

    @Test
    void theRefusalDropsTheFreePortHintWhenTheOnlyProblemIsAnExpeditionAlreadyRunning() {
        String one = CoopAgentCommands.noExpeditionCandidateMessage(
                List.of(candidate("hegemony", 3, true, true)));
        assertTrue(one.contains("already running") && one.contains("orphan"), one);
        assertFalse(one.contains(CoopAgentCommands.FREE_PORT_HINT),
                "free port is already on; telling the caller to toggle it would be a wrong lead: " + one);

        String all = CoopAgentCommands.noExpeditionCandidateMessage(List.of(
                candidate("hegemony", 3, true, true),
                candidate("luddic_church", 1, true, true)));
        assertTrue(all.contains("all 2 factions"), all);
        assertFalse(all.contains(CoopAgentCommands.FREE_PORT_HINT), all);
    }

    @Test
    void expeditionIsRefusedOnTheGuestAndAllowedWithNoSessionAtAll() {
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> CoopAgentCommands.requireExpeditionAuthority(CoopConnectionRole.GUEST));
        assertTrue(refused.getMessage().contains("host-only")
                        && refused.getMessage().contains("suppressed"),
                "the guest's manager is suppressed, so the refusal has to say so rather than read as"
                        + " a missing feature: " + refused.getMessage());

        CoopAgentCommands.requireExpeditionAuthority(CoopConnectionRole.HOST);
        CoopAgentCommands.requireExpeditionAuthority(CoopConnectionRole.NONE);
    }

    private static CoopAgentCommands.ExpeditionCandidate candidate(String factionId, int reasonCount,
                                                                   boolean freePort, boolean ongoing) {
        return new CoopAgentCommands.ExpeditionCandidate(factionId, reasonCount, freePort, ongoing);
    }

    // ---- rep: player-faction standing with an NPC faction -------------------------------------------

    @Test
    void repSetsTheValueDirectlyAndReportsBeforeAndAfter() throws JSONException {
        float[] relationship = {0.1f};
        CoopAgentCommands.Context context = repContext("hegemony", relationship);

        JSONObject out = CoopAgentCommands.rep(args("factionId", "hegemony", "value", 0.4), context);

        assertEquals("hegemony", out.getString("factionId"));
        assertEquals(0.1d, out.getDouble("before"), 1e-6);
        assertEquals(0.4d, out.getDouble("after"), 1e-6);
        assertEquals(0.4f, relationship[0], 1e-6f, "the engine call must actually have landed");
    }

    @Test
    void repAcceptsPointsAsTheUiScaleInsteadOfTheRawApiValue() throws JSONException {
        float[] relationship = {0f};
        CoopAgentCommands.Context context = repContext("hegemony", relationship);

        JSONObject out = CoopAgentCommands.rep(args("factionId", "hegemony", "points", -50), context);

        assertEquals(0d, out.getDouble("before"), 1e-6);
        assertEquals(-0.5d, out.getDouble("after"), 1e-6);
        assertEquals(-0.5f, relationship[0], 1e-6f);
    }

    @Test
    void repRefusesWhenNeitherOrBothOfValueAndPointsAreGiven() throws JSONException {
        CoopAgentCommands.Context context = repContext("hegemony", new float[]{0f});

        String neither = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.rep(args("factionId", "hegemony"), context)).getMessage();
        assertTrue(neither.contains("\"value\": -1..1") && neither.contains("\"points\": -100..100"),
                "the refusal must name both accepted forms: " + neither);

        JSONObject both = args("factionId", "hegemony", "value", 0.2);
        both.put("points", 20);
        String bothMessage = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.rep(both, context)).getMessage();
        assertTrue(bothMessage.contains("\"value\": -1..1") && bothMessage.contains("\"points\": -100..100"),
                bothMessage);
    }

    @Test
    void repRejectsAnUnknownFaction() {
        CoopAgentCommands.Context context = repContext("hegemony", new float[]{0f});

        String message = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.rep(args("factionId", "no_such_faction", "points", 10), context))
                .getMessage();

        assertTrue(message.contains("no_such_faction"), message);
    }

    @Test
    void repRejectsAValueOrPointsOutOfRange() {
        CoopAgentCommands.Context context = repContext("hegemony", new float[]{0f});

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.rep(args("factionId", "hegemony", "value", 1.5), context))
                .getMessage().contains("between -1 and 1"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.rep(args("factionId", "hegemony", "points", 150), context))
                .getMessage().contains("between -100 and 100"));
    }

    @Test
    void repIsRefusedOnTheGuestAndAllowedWithNoSessionOrOnTheHost() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.requireRepAuthority(CoopConnectionRole.GUEST));
        assertTrue(refused.getMessage().contains("host-only")
                        && refused.getMessage().contains("PLAYER_REP_SNAPSHOT"),
                "standings are host-authoritative; the refusal has to say what would overwrite a guest"
                        + " edit: " + refused.getMessage());

        CoopAgentCommands.requireRepAuthority(CoopConnectionRole.HOST);
        CoopAgentCommands.requireRepAuthority(CoopConnectionRole.NONE);
    }

    @Test
    void repViaDispatchIsRefusedOnAGuestSessionRatherThanReachingTheEngine() throws JSONException {
        float[] relationship = {0f};
        SectorAPI sector = repSector("hegemony", relationship);
        coop.net.CoopNetPump pump = sessionPump(CoopConnectionRole.GUEST);
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put("rep", CoopAgentCommands::rep);
        CoopAgentCommands commands = new CoopAgentCommands(handlers);

        JSONObject response = new JSONObject(commands.dispatch(
                "{\"id\":20,\"cmd\":\"rep\",\"args\":{\"factionId\":\"hegemony\",\"points\":50}}",
                contextFor(sector, pump)));

        assertFalse(response.getBoolean("ok"));
        assertTrue(response.getString("error").contains("host-only"), response.getString("error"));
        assertEquals(0f, relationship[0], "a refused call must never have touched the engine");
    }

    private static CoopAgentCommands.Context repContext(String factionId, float[] relationship) {
        return contextFor(repSector(factionId, relationship));
    }

    private static SectorAPI repSector(String factionId, float[] relationship) {
        FactionAPI faction = proxy(FactionAPI.class, answers());
        FactionAPI player = proxy(FactionAPI.class, answers(
                "getRelationship", args -> relationship[0],
                "setRelationship", args -> {
                    relationship[0] = ((Number) args[1]).floatValue();
                    return null;
                }));
        return proxy(SectorAPI.class, answers(
                "getFaction", args -> factionId.equals(args[0]) ? faction : null,
                "getPlayerFaction", args -> player));
    }

    private static JSONObject args(String key, Object value) throws JSONException {
        JSONObject out = new JSONObject();
        out.put(key, value);
        return out;
    }

    private static JSONObject args(String k1, Object v1, String k2, Object v2) throws JSONException {
        JSONObject out = args(k1, v1);
        out.put(k2, v2);
        return out;
    }

    // ---- colonizable: which planets count, and in what order ---------------------------------------

    @Test
    void onlyUncolonizedNonStarPlanetsCount_andAGasGiantIsOneOfThem() throws JSONException {
        JSONObject out = CoopAgentCommands.colonizable(new JSONObject(), colonizableSector());

        assertEquals(3, out.getInt("candidateCount"));
        assertEquals(List.of("ancyra", "aleph_gas", "hidden_planet"), planetIds(out),
                "out: the star, the colonized world, the market-less moon, and the cut-off, abyssal"
                        + " and temporary systems. In: the gas giant, and the theme_hidden system"
                        + " that carries no gate vanilla actually reads");
        assertEquals("corvus", out.getString("fromLocationId"));
    }

    @Test
    void theRowsCarryWhatChoosingATargetTurnsOn() throws JSONException {
        JSONObject out = CoopAgentCommands.colonizable(new JSONObject(), colonizableSector());

        JSONObject ancyra = out.getJSONArray("planets").getJSONObject(0);
        assertEquals("Ancyra", ancyra.getString("name"));
        assertEquals("terran", ancyra.getString("type"));
        assertFalse(ancyra.getBoolean("gasGiant"));
        assertEquals("corvus", ancyra.getString("systemId"));
        assertEquals("Corvus Star System", ancyra.getString("systemName"));
        assertEquals(0d, ancyra.getDouble("distanceLy"), 0d, "the fleet's own system is at zero LY");
        assertEquals(1000d, ancyra.getDouble("distanceSu"), 0d);
        assertEquals(1.25d, ancyra.getDouble("hazard"), 1e-6);
        assertEquals(List.of("farmland_poor", "habitable", "ore_moderate"),
                stringList(ancyra.getJSONArray("conditions")),
                "sorted, so the same planet on two clients compares equal instead of as a reorder");

        JSONObject faraway = out.getJSONArray("planets").getJSONObject(2);
        assertEquals(5d, faraway.getDouble("distanceLy"), 1e-6, "10000 su / 2000 su per LY");
        assertEquals(0d, faraway.getDouble("distanceSu"), 0d,
                "in-system distance is for this system only");
    }

    @Test
    void aRowCarriesThePlanetsOwnCoordinatesSoTeleportNeedsNoOrbitArithmetic() throws JSONException {
        JSONObject out = CoopAgentCommands.colonizable(new JSONObject(), colonizableSector());

        JSONObject ancyra = out.getJSONArray("planets").getJSONObject(0);
        assertEquals(1000d, ancyra.getDouble("x"), 1e-6,
                "location-local coordinates, the pair teleport takes alongside systemId");
        assertEquals(0d, ancyra.getDouble("y"), 1e-6);

        JSONObject gasGiant = out.getJSONArray("planets").getJSONObject(1);
        assertEquals(2000d, gasGiant.getDouble("x"), 1e-6);
    }

    @Test
    void marketsInSystemCountsTheEconomyAndIsZeroWhereNoFactionHoldsAnything() throws JSONException {
        JSONObject out = CoopAgentCommands.colonizable(new JSONObject(), colonizableSector());

        assertEquals(1, out.getJSONArray("planets").getJSONObject(0).getInt("marketsInSystem"),
                "Corvus holds Jangala, so it is not a system nobody is in");
        assertEquals(1, out.getJSONArray("planets").getJSONObject(1).getInt("marketsInSystem"),
                "the count is the system's, so both Corvus planets read it");
        assertEquals(0, out.getJSONArray("planets").getJSONObject(2).getInt("marketsInSystem"),
                "the hidden system has no economy market; the candidate's own condition market must"
                        + " not count itself, or nothing would ever read as neutral");
        assertFalse(out.getBoolean("neutralOnly"), "the answer echoes the filter it was asked for");
    }

    @Test
    void neutralOnlyLeavesOnlyTheSystemsNobodyHolds() throws JSONException {
        JSONObject args = new JSONObject();
        args.put("neutralOnly", true);

        JSONObject out = CoopAgentCommands.colonizable(args, colonizableSector());

        assertEquals(List.of("hidden_planet"), planetIds(out));
        assertTrue(out.getBoolean("neutralOnly"));
        assertEquals(3, out.getInt("candidateCount"),
                "candidateCount stays the whole sector's, so \"none neutral nearby\" and \"nothing to"
                        + " colonize at all\" still read differently");
        assertEquals(1, out.getInt("count"));
    }

    @Test
    void theSurveyGateAndTheRuinsGateAreReportedRatherThanFiltered() throws JSONException {
        JSONObject out = CoopAgentCommands.colonizable(new JSONObject(), colonizableSector());

        JSONObject ancyra = out.getJSONArray("planets").getJSONObject(0);
        assertEquals("FULL", ancyra.getString("surveyLevel"));
        assertFalse(ancyra.getBoolean("unexploredRuins"));

        JSONObject gasGiant = out.getJSONArray("planets").getJSONObject(1);
        assertEquals("NONE", gasGiant.getString("surveyLevel"),
                "an unsurveyed planet still lists: surveyset is exactly how the run fixes that");
        assertTrue(gasGiant.getBoolean("unexploredRuins"),
                "ruins block vanilla's colonize option but salvaging them unblocks it, so the planet"
                        + " stays on the list with the gate named");
    }

    @Test
    void aSectorWithNothingLeftToColonizeAnswersAnEmptyListNotAnError() throws JSONException {
        MarketAPI colonized = conditionMarket(false, MarketAPI.SurveyLevel.FULL, 1f, List.of());
        PlanetAPI onlyColony = planet("jangala", "Jangala", "terran", false, false, colonized, 0f, 0f, 0f, 0f);
        LocationAPI corvus = system("corvus", "Corvus Star System", List.of(onlyColony));

        JSONObject out = CoopAgentCommands.colonizable(new JSONObject(),
                contextFor(colonizableSectorOf(corvus, fleetAt(corvus, 0f, 0f, 0f, 0f))));

        assertEquals(0, out.getInt("candidateCount"));
        assertEquals(0, out.getInt("count"));
        assertEquals(0, out.getJSONArray("planets").length());
    }

    @Test
    void everyLocationVanillaRefusesToColonizeIsFilteredAndNoOtherIs() {
        assertFalse(CoopAgentCommands.colonizableSystem(null));
        assertFalse(CoopAgentCommands.colonizableSystem(hyperspace()));
        assertFalse(CoopAgentCommands.colonizableSystem(deepSpace()),
                "PlanetSurveyPanel: \"This planet is in deep space and can not be colonized.\"");
        assertFalse(CoopAgentCommands.colonizableSystem(
                system("cut_off", "Cut Off", List.of(), "system_cut_off_from_hyper")),
                "rules.csv surveySystemIsCutOffCanNotColonize disables the colonize option outright");
        assertFalse(CoopAgentCommands.colonizableSystem(
                system("abyss", "Abyss", List.of(), "system_abyssal")),
                "PlanetSurveyPanel: \"deep in abyssal hyperspace and can not be colonized\" - a gate"
                        + " that appears in no rules.csv row and in no API source");
        assertFalse(CoopAgentCommands.colonizableSystem(
                system("abyss_tmp", "Nowhere", List.of(), "temporary_location")),
                "the deliberate tightening: the encounter generators mint and discard these systems");
        assertTrue(CoopAgentCommands.colonizableSystem(
                system("hidden", "Unknown Location", List.of(), "theme_hidden", "theme_special")),
                "theme_hidden only means off-map until found; the vanilla systems carrying it are"
                        + " blocked by abyssal or deep space, and filtering the theme tag instead"
                        + " would be filtering the wrong thing");
    }

    @Test
    void candidatesComeBackNearestFirstWithTheFleetsOwnSystemAhead() {
        CoopAgentCommands.ColonizableCandidate far = colonizable("far", 12f, 0f);
        CoopAgentCommands.ColonizableCandidate hereFar = colonizable("here_far", 0f, 9000f);
        CoopAgentCommands.ColonizableCandidate hereNear = colonizable("here_near", 0f, 250f);
        CoopAgentCommands.ColonizableCandidate near = colonizable("near", 3.5f, 0f);

        assertEquals(List.of("here_near", "here_far", "near", "far"),
                ids(CoopAgentCommands.selectColonizable(
                        List.of(far, hereFar, hereNear, near), 10, 0d, false)));
    }

    @Test
    void limitCapsTheListAndMaxLyFiltersItBeforeTheCap() {
        List<CoopAgentCommands.ColonizableCandidate> all = List.of(
                colonizable("a", 1f, 0f), colonizable("b", 4f, 0f), colonizable("c", 9f, 0f));

        assertEquals(List.of("a", "b"), ids(CoopAgentCommands.selectColonizable(all, 2, 0d, false)),
                "limit takes the nearest, not the first two the walk happened to find");
        assertEquals(List.of("a", "b"), ids(CoopAgentCommands.selectColonizable(all, 10, 5d, false)));
        assertEquals(List.of("a"), ids(CoopAgentCommands.selectColonizable(all, 1, 5d, false)),
                "maxLy runs first so the cap applies to what is actually in range");
        assertEquals(List.of(), ids(CoopAgentCommands.selectColonizable(all, 10, 0.5d, false)));
        assertEquals(List.of("a", "b", "c"), ids(CoopAgentCommands.selectColonizable(all, 10, 0d, false)),
                "maxLy 0 is no filter, not a filter that excludes everything");
    }

    @Test
    void neutralOnlyDropsSystemsWithAnyFactionPresenceAndDoesItBeforeTheCap() {
        List<CoopAgentCommands.ColonizableCandidate> all = List.of(
                colonizable("occupied_near", 1f, 0f, 2),
                colonizable("empty_mid", 4f, 0f, 0),
                colonizable("empty_far", 9f, 0f, 0));

        assertEquals(List.of("empty_mid", "empty_far"),
                ids(CoopAgentCommands.selectColonizable(all, 10, 0d, true)));
        assertEquals(List.of("empty_mid"), ids(CoopAgentCommands.selectColonizable(all, 1, 0d, true)),
                "filter first, then cap: limit 1 has to mean one neutral planet, not \"the nearest"
                        + " planet, dropped for not being neutral\"");
        assertEquals(List.of("occupied_near", "empty_mid", "empty_far"),
                ids(CoopAgentCommands.selectColonizable(all, 10, 0d, false)),
                "the default keeps everything, so the field is context rather than a hidden filter");
    }

    @Test
    void anUnusableLimitIsRefusedRatherThanQuietlyClamped() {
        CoopAgentCommands.Context context = colonizableSector();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.colonizable(args("limit", "0"), context))
                .getMessage().contains("limit must be between 1 and 200"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.colonizable(args("limit", "500"), context))
                .getMessage().contains("limit must be between 1 and 200"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.colonizable(args("maxLy", "nearby"), context))
                .getMessage().contains("maxLy must be numeric"));
    }

    // ---- landmarks: the unique objects, found by vanilla's own tags -------------------------------

    @Test
    void eachLandmarkKindIsFoundByItsVanillaTagAndNothingElseIs() throws JSONException {
        JSONObject out = CoopAgentCommands.landmarks(new JSONObject(), landmarkSector());

        assertEquals(List.of("hypershunt", "cryosleeper", "gate", "stable_location", "gate_hauler"),
                stringList(out.getJSONArray("kinds")));
        assertEquals(6, out.getInt("candidateCount"));
        assertEquals(
                List.of("corvus_stable", "corvus_gate", "sleeper", "shunt", "hauler", "hyper_stable"),
                landmarkIds(out),
                "the comm relay carries no landmark tag and must not appear. Corvus first (the fleet"
                        + " is there, so in-system distance orders it), then Aleph's two at the same"
                        + " 3 LY broken by kind - cryosleeper before hypershunt");
    }

    @Test
    void aLandmarkRowNamesItsKindItsSpecAndWhereItIs() throws JSONException {
        JSONObject out = CoopAgentCommands.landmarks(new JSONObject(), landmarkSector());
        Map<String, JSONObject> rows = landmarkRows(out);

        JSONObject shunt = rows.get("shunt");
        assertEquals("hypershunt", shunt.getString("kind"));
        assertEquals("Coronal Hypershunt", shunt.getString("name"));
        assertEquals("coronal_tap", shunt.getString("type"), "the custom entity spec id");
        assertEquals("aleph", shunt.getString("systemId"));
        assertEquals(3d, shunt.getDouble("distanceLy"), 1e-6, "6000 su / 2000 su per LY");
        assertEquals(0d, shunt.getDouble("distanceSu"), 0d);
        assertFalse(shunt.getBoolean("hyperspace"));

        JSONObject inHyperspace = rows.get("hyper_stable");
        assertTrue(inHyperspace.getBoolean("hyperspace"),
                "hyperspace is walked like any other location; a landmark out there is a real one");
        assertEquals("hyperspace", inHyperspace.getString("systemId"));
    }

    @Test
    void aLandmarkRowCarriesItsOwnCoordinatesTheSameWayAColonizableRowDoes() throws JSONException {
        Map<String, JSONObject> rows =
                landmarkRows(CoopAgentCommands.landmarks(new JSONObject(), landmarkSector()));

        JSONObject gate = rows.get("corvus_gate");
        assertEquals(400d, gate.getDouble("x"), 1e-6, "with systemId, a teleport argument");
        assertEquals(0d, gate.getDouble("y"), 1e-6);
        assertEquals(20_000d, rows.get("hyper_stable").getDouble("x"), 1e-6,
                "a hyperspace landmark's coordinates are hyperspace coordinates, which is what"
                        + " teleporting to it wants");
    }

    @Test
    void aGateCarriesTheReadsThatDecideWhetherItCanBeUsed() throws JSONException {
        JSONObject gate = landmarkRows(CoopAgentCommands.landmarks(new JSONObject(), landmarkSector()))
                .get("corvus_gate");

        assertTrue(gate.getBoolean("scanned"));
        assertFalse(gate.getBoolean("active"));
        assertTrue(gate.getBoolean("gatesActive"));
        assertFalse(gate.getBoolean("playerCanUseGates"));
        assertFalse(landmarkRows(CoopAgentCommands.landmarks(new JSONObject(), landmarkSector()))
                .get("shunt").has("scanned"), "the gate tail is the gate's, not every row's");
    }

    @Test
    void theHypershuntAndCryosleeperCarryTheirUsableFlagAndTheEnginesOwnRange() throws JSONException {
        Map<String, JSONObject> rows =
                landmarkRows(CoopAgentCommands.landmarks(new JSONObject(), landmarkSector()));

        JSONObject shunt = rows.get("shunt");
        assertTrue(shunt.getBoolean("usable"),
                "$usable is a contains() check in vanilla, not getBoolean - until it is set neither"
                        + " landmark counts for any colony at any distance");
        assertEquals(10d, shunt.getDouble("benefitRangeLy"), 0d,
                "read live off ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS, not copied into this repo");
        assertFalse(shunt.has("minBenefitMult"), "the hypershunt effect is binary, not graded");

        JSONObject sleeper = rows.get("sleeper");
        assertFalse(sleeper.getBoolean("usable"), "its guardian is still alive");
        assertEquals(10d, sleeper.getDouble("benefitRangeLy"), 0d,
                "read live off Cryorevival.MAX_BONUS_DIST_LY");
        assertEquals(0.1d, sleeper.getDouble("minBenefitMult"), 1e-6,
                "Cryorevival.MIN_BONUS_MULT - the cryosleeper bonus is graded down to this at the edge");

        assertFalse(rows.get("corvus_stable").has("benefitRangeLy"),
                "only the two landmarks with a real colony radius get one");
    }

    @Test
    void theGateHaulerIsFoundBySpecIdBecauseItHasNoTagThatIdentifiesIt() throws JSONException {
        JSONObject out = CoopAgentCommands.landmarks(args("kinds", "gate_hauler"), landmarkSector());

        assertEquals(List.of("hauler"), landmarkIds(out),
                "its four tags are all shared with cryosleepers and ordinary salvage, so the walk has"
                        + " to match the custom entity spec id and skip everything else in the system");
        assertEquals("derelict_gatehauler",
                out.getJSONArray("landmarks").getJSONObject(0).getString("type"));
    }

    @Test
    void theSectorWideGateFlagsIgnoreWhatTheLocalPlayerHappensToBeCarrying() {
        // GateEntityPlugin.areGatesActive()/canUseGates() OR in "holding a Janus Device", which is one
        // client's cargo. Reading the memory flags instead is what keeps this verb diffable.
        assertEquals(new CoopAgentCommands.GateState(false, false),
                CoopAgentCommands.readGateState(proxy(SectorAPI.class, answers())),
                "a sector with no memory at all is not a crash and not gates-open");
        assertEquals(new CoopAgentCommands.GateState(true, false),
                CoopAgentCommands.readGateState(sectorWithGateFlags(true, false)));
    }

    @Test
    void theKindsArgumentFiltersAndKeepsTheRegistrysOrderHoweverItIsSpelled() throws JSONException {
        JSONObject byString = CoopAgentCommands.landmarks(args("kinds", "gate, hypershunt"),
                landmarkSector());
        assertEquals(List.of("hypershunt", "gate"), stringList(byString.getJSONArray("kinds")),
                "the answer's kind list is the registry's order, not the caller's");
        assertEquals(List.of("corvus_gate", "shunt"), landmarkIds(byString));
        assertEquals(2, byString.getInt("candidateCount"),
                "candidateCount counts what the requested kinds found, not the whole sector");

        JSONObject args = new JSONObject();
        args.put("kinds", new JSONArray(List.of("CRYOSLEEPER")));
        assertEquals(List.of("sleeper"), landmarkIds(CoopAgentCommands.landmarks(args, landmarkSector())));
    }

    @Test
    void anUnknownKindIsRefusedRatherThanAnsweredWithAnEmptyList() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.landmarks(args("kinds", "gate,hypergate"), landmarkSector()))
                .getMessage();

        assertTrue(message.contains("unknown landmark kind hypergate"), message);
        assertTrue(message.contains("hypershunt, cryosleeper, gate, stable_location, gate_hauler"),
                "\"none of that kind\" and \"you misspelled the kind\" have to read differently: "
                        + message);
    }

    @Test
    void aSectorWithNoLandmarksAnswersAnEmptyListNotAnError() throws JSONException {
        LocationAPI empty = system("corvus", "Corvus Star System", List.of());
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getPlayerFleet", args -> fleetAt(empty, 0f, 0f, 0f, 0f),
                "getAllLocations", args -> List.of(empty)));

        JSONObject out = CoopAgentCommands.landmarks(new JSONObject(), contextFor(sector));

        assertEquals(0, out.getInt("candidateCount"));
        assertEquals(0, out.getInt("count"));
        assertEquals(0, out.getJSONArray("landmarks").length());
    }

    @Test
    void anEntityCarryingTwoLandmarkTagsIsEmittedOnceUnderTheFirstKindThatClaimsIt()
            throws JSONException {
        SectorEntityToken doubleTagged = landmarkEntity("odd", "Odd Thing", "coronal_tap", 0f, 0f, 0f, 0f);
        LocationAPI corvus = taggedSystem("corvus", "Corvus Star System", Map.of(
                "coronal_tap", List.of(doubleTagged),
                "cryosleeper", List.of(doubleTagged)));
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getPlayerFleet", args -> fleetAt(corvus, 0f, 0f, 0f, 0f),
                "getAllLocations", args -> List.of(corvus)));

        JSONObject out = CoopAgentCommands.landmarks(new JSONObject(), contextFor(sector));

        assertEquals(List.of("odd"), landmarkIds(out), "two rows for one object would break the keyed diff");
        assertEquals("hypershunt", out.getJSONArray("landmarks").getJSONObject(0).getString("kind"));
    }

    @Test
    void landmarksSortNearestFirstAndAreTrimmedByLimitAndMaxLy() {
        CoopAgentCommands.Landmark far = landmark("far", "gate", 12f, 0f);
        CoopAgentCommands.Landmark hereFar = landmark("here_far", "gate", 0f, 9000f);
        CoopAgentCommands.Landmark hereNear = landmark("here_near", "stable_location", 0f, 250f);
        CoopAgentCommands.Landmark near = landmark("near", "cryosleeper", 3.5f, 0f);
        List<CoopAgentCommands.Landmark> all = List.of(far, hereFar, hereNear, near);

        assertEquals(List.of("here_near", "here_far", "near", "far"),
                landmarkIds(CoopAgentCommands.selectLandmarks(all, 25, 0d)));
        assertEquals(List.of("here_near", "here_far"),
                landmarkIds(CoopAgentCommands.selectLandmarks(all, 2, 0d)));
        assertEquals(List.of("here_near", "here_far", "near"),
                landmarkIds(CoopAgentCommands.selectLandmarks(all, 25, 5d)),
                "maxLy runs before the cap");
        assertEquals(List.of("here_near", "here_far"),
                landmarkIds(CoopAgentCommands.selectLandmarks(all, 25, 0.5d)),
                "a landmark in the fleet's own system is at zero LY, so no range filter can exclude"
                        + " it however tight it is - which is the behaviour you want");
    }

    @Test
    void twoLandmarksAtTheSameDistanceStillHaveOneAgreedOrder() {
        CoopAgentCommands.Landmark gate = landmark("zzz", "gate", 4f, 0f);
        CoopAgentCommands.Landmark sleeper = landmark("aaa", "cryosleeper", 4f, 0f);

        assertEquals(List.of("aaa", "zzz"),
                landmarkIds(CoopAgentCommands.selectLandmarks(List.of(gate, sleeper), 25, 0d)),
                "kind then id breaks the tie, so ss_diff never sees a phantom reorder");
    }

    // ---- landmarks fakes ---------------------------------------------------------------------------

    /**
     * Corvus (the fleet's own system) holds a scanned gate, a stable location, a comm relay and a
     * plain station; Aleph 3 LY out holds the hypershunt and the cryosleeper; hyperspace holds a
     * stable location. Gates are active sector-wide but the player cannot use them yet.
     */
    private static CoopAgentCommands.Context landmarkSector() {
        SectorEntityToken gate = landmarkEntity("corvus_gate", "Gate", "inactive_gate", 400f, 0f, 0f, 0f);
        SectorEntityToken stable = landmarkEntity("corvus_stable", "Stable Location",
                "stable_location", 100f, 0f, 0f, 0f);
        SectorEntityToken relay = landmarkEntity("corvus_relay", "Comm Relay", "comm_relay", 50f, 0f, 0f, 0f);
        LocationAPI corvus = taggedSystem("corvus", "Corvus Star System", Map.of(
                "gate", List.of(gate),
                "stable_location", List.of(stable),
                "objective", List.of(relay)));

        // The shunt has been repaired, the cryosleeper's guardian is still alive.
        SectorEntityToken shunt = landmarkEntity("shunt", "Coronal Hypershunt", "coronal_tap",
                0f, 0f, 6000f, 0f, true);
        SectorEntityToken sleeper = landmarkEntity("sleeper", "Derelict Cryosleeper",
                "derelict_cryosleeper", 0f, 0f, 6000f, 0f, false);
        LocationAPI aleph = taggedSystem("aleph", "Aleph Star System", Map.of(
                "coronal_tap", List.of(shunt),
                "cryosleeper", List.of(sleeper)));

        // The gate hauler sits in its own hidden deep-space system and carries no identifying tag, so
        // it is found by spec id through getAllEntities.
        SectorEntityToken hauler = landmarkEntity("hauler", "Domain-era Gate Hauler",
                "derelict_gatehauler", 0f, 0f, 14_000f, 0f);
        SectorEntityToken haulerJunk = landmarkEntity("hauler_rock", "Ice Giant", "planet",
                0f, 0f, 14_000f, 0f);
        Map<String, Answer> haulerSystem = answers();
        haulerSystem.put("getId", args -> "gatehauler_loc");
        haulerSystem.put("getName", args -> "Deep Space");
        haulerSystem.put("getEntitiesWithTag", args -> List.of());
        haulerSystem.put("getAllEntities", args -> List.of(haulerJunk, hauler));
        LocationAPI haulerLocation = proxy(LocationAPI.class, haulerSystem);

        SectorEntityToken deepStable = landmarkEntity("hyper_stable", "Stable Location",
                "stable_location", 20_000f, 0f, 20_000f, 0f);
        LocationAPI hyper = proxy(LocationAPI.class, answers(
                "getId", args -> "hyperspace",
                "getName", args -> "Hyperspace",
                "isHyperspace", args -> true,
                "getEntitiesWithTag",
                args -> "stable_location".equals(args[0]) ? List.of(deepStable) : List.of()));

        CampaignFleetAPI player = fleetAt(corvus, 0f, 0f, 0f, 0f);
        Map<String, Answer> sector = answers();
        sector.put("getPlayerFleet", args -> player);
        sector.put("getAllLocations", args -> List.of(corvus, aleph, haulerLocation, hyper));
        sector.put("getMemoryWithoutUpdate", args -> gateMemory(true, false));
        return contextFor(proxy(SectorAPI.class, sector));
    }

    private static SectorAPI sectorWithGateFlags(boolean gatesActive, boolean canUse) {
        return proxy(SectorAPI.class,
                answers("getMemoryWithoutUpdate", args -> gateMemory(gatesActive, canUse)));
    }

    private static MemoryAPI gateMemory(boolean gatesActive, boolean canUse) {
        return proxy(MemoryAPI.class, answers("getBoolean", args -> switch (String.valueOf(args[0])) {
            case "$gatesActive" -> gatesActive;
            case "$playerCanUseGates" -> canUse;
            default -> false;
        }));
    }

    private static SectorEntityToken landmarkEntity(String id, String name, String specId,
                                                    float x, float y, float hyperX, float hyperY) {
        return landmarkEntity(id, name, specId, x, y, hyperX, hyperY, false);
    }

    private static SectorEntityToken landmarkEntity(String id, String name, String specId,
                                                    float x, float y, float hyperX, float hyperY,
                                                    boolean usable) {
        Map<String, Answer> answers = answers();
        answers.put("getId", args -> id);
        answers.put("getName", args -> name);
        answers.put("getCustomEntityType", args -> specId);
        answers.put("getLocation", args -> new Vector2f(x, y));
        answers.put("getLocationInHyperspace", args -> new Vector2f(hyperX, hyperY));
        // Everything is a scanned gate as far as getBoolean is concerned; only the gate is asked.
        // $usable is a contains() check, which is how vanilla itself tests it.
        Map<String, Answer> memory = answers();
        memory.put("getBoolean", args -> "$gateScanned".equals(args[0]));
        memory.put("contains", args -> usable && "$usable".equals(args[0]));
        answers.put("getMemoryWithoutUpdate", args -> proxy(MemoryAPI.class, memory));
        return proxy(SectorEntityToken.class, answers);
    }

    private static LocationAPI taggedSystem(String id, String name,
                                            Map<String, List<SectorEntityToken>> byTag) {
        Map<String, Answer> answers = answers();
        answers.put("getId", args -> id);
        answers.put("getName", args -> name);
        answers.put("getEntitiesWithTag",
                args -> byTag.getOrDefault(String.valueOf(args[0]), List.of()));
        return proxy(LocationAPI.class, answers);
    }

    private static CoopAgentCommands.Landmark landmark(String entityId, String kind, float ly, float su) {
        return new CoopAgentCommands.Landmark(kind, entityId, entityId, "spec", "system", "System",
                false, 0f, 0f, ly, su, new LinkedHashMap<>());
    }

    private static List<String> landmarkIds(List<CoopAgentCommands.Landmark> landmarks) {
        List<String> out = new ArrayList<>();
        for (CoopAgentCommands.Landmark landmark : landmarks) {
            out.add(landmark.entityId());
        }
        return out;
    }

    private static List<String> landmarkIds(JSONObject out) throws JSONException {
        List<String> ids = new ArrayList<>();
        JSONArray rows = out.getJSONArray("landmarks");
        for (int i = 0; i < rows.length(); i++) {
            ids.add(rows.getJSONObject(i).getString("entityId"));
        }
        return ids;
    }

    private static Map<String, JSONObject> landmarkRows(JSONObject out) throws JSONException {
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        JSONArray array = out.getJSONArray("landmarks");
        for (int i = 0; i < array.length(); i++) {
            rows.put(array.getJSONObject(i).getString("entityId"), array.getJSONObject(i));
        }
        return rows;
    }

    // ---- colonizable fakes ------------------------------------------------------------------------

    /**
     * The sector every end-to-end colonizable test runs against: the fleet's own system holding one
     * candidate, one gas giant, a star, a colonized world and a moon with no market at all; a hidden
     * -themed system 5 LY out that must still count; and the two systems that must not.
     */
    private static CoopAgentCommands.Context colonizableSector() {
        PlanetAPI star = planet("corvus_star", "Corvus", "star_yellow", true, false,
                conditionMarket(true, MarketAPI.SurveyLevel.FULL, 0f, List.of()), 0f, 0f, 0f, 0f);
        PlanetAPI ancyra = planet("ancyra", "Ancyra", "terran", false, false,
                conditionMarket(true, MarketAPI.SurveyLevel.FULL, 1.25f,
                        List.of("habitable", "ore_moderate", "farmland_poor")),
                1000f, 0f, 0f, 0f);
        PlanetAPI colony = planet("jangala", "Jangala", "terran", false, false,
                conditionMarket(false, MarketAPI.SurveyLevel.FULL, 1f, List.of()), 500f, 0f, 0f, 0f);
        PlanetAPI moon = planet("corvus_moon", "Corvus I", "barren", false, false,
                null, 100f, 0f, 0f, 0f);
        PlanetAPI gasGiant = planet("aleph_gas", "Aleph", "gas_giant", false, true,
                conditionMarket(true, MarketAPI.SurveyLevel.NONE, 1.5f, List.of("ruins_scattered")),
                2000f, 0f, 0f, 0f);
        LocationAPI corvus = system("corvus", "Corvus Star System",
                List.of(star, ancyra, colony, moon, gasGiant));

        PlanetAPI hidden = planet("hidden_planet", "Wayfarer", "tundra", false, false,
                conditionMarket(true, MarketAPI.SurveyLevel.SEEN, 2f, List.of()),
                0f, 0f, 10_000f, 0f);
        LocationAPI hiddenSystem = system("hidden", "Unknown Location", List.of(hidden), "theme_hidden");

        PlanetAPI stranded = planet("cutoff_planet", "Stranded", "terran", false, false,
                conditionMarket(true, MarketAPI.SurveyLevel.FULL, 1f, List.of()),
                0f, 0f, 4000f, 0f);
        LocationAPI cutOff = system("cut_off", "Cut Off", List.of(stranded), "system_cut_off_from_hyper");

        PlanetAPI sunken = planet("abyss_planet", "Drowned", "barren", false, false,
                conditionMarket(true, MarketAPI.SurveyLevel.FULL, 1f, List.of()),
                0f, 0f, 3000f, 0f);
        LocationAPI abyssal = system("abyss", "Abyss", List.of(sunken), "system_abyssal");

        PlanetAPI ephemeral = planet("tmp_planet", "Nowhere", "barren", false, false,
                conditionMarket(true, MarketAPI.SurveyLevel.FULL, 1f, List.of()),
                0f, 0f, 2000f, 0f);
        LocationAPI temporary = system("abyss_tmp", "Nowhere", List.of(ephemeral), "temporary_location");

        CampaignFleetAPI player = fleetAt(corvus, 0f, 0f, 0f, 0f);
        // The economy holds Jangala only. Corvus therefore reads one market and the hidden system
        // reads none, which is the whole point of the field: no market, no faction presence. The
        // uncolonized planets' own condition markets are deliberately not in here, because they are
        // not in the engine's economy either.
        EconomyAPI economy = proxy(EconomyAPI.class,
                answers("getMarketsCopy", args -> List.of(economyMarket("jangala", "corvus"))));
        return contextFor(proxy(SectorAPI.class, answers(
                "getPlayerFleet", args -> player,
                "getEconomy", args -> economy,
                "getAllLocations",
                args -> List.of(corvus, hiddenSystem, cutOff, abyssal, temporary, hyperspace()))));
    }

    /** A market that answers only what {@code marketsInSystem} counts it by. */
    private static MarketAPI economyMarket(String marketId, String locationId) {
        return proxy(MarketAPI.class, answers(
                "getId", args -> marketId,
                "getContainingLocation", args -> location(locationId, List.of())));
    }

    /** A deep-space pocket: colonization is refused by the core UI, not by any rule or tag. */
    private static LocationAPI deepSpace() {
        return proxy(LocationAPI.class, answers(
                "getId", args -> "deep_space",
                "getName", args -> "Deep Space",
                "isDeepSpace", args -> true,
                "getPlanets", args -> new ArrayList<PlanetAPI>(),
                "getFleets", args -> new ArrayList<CampaignFleetAPI>()));
    }

    private static SectorAPI colonizableSectorOf(LocationAPI location, CampaignFleetAPI player) {
        return proxy(SectorAPI.class, answers(
                "getPlayerFleet", args -> player,
                "getAllLocations", args -> List.of(location)));
    }

    private static PlanetAPI planet(String id, String name, String typeId, boolean star,
                                    boolean gasGiant, MarketAPI market, float x, float y,
                                    float hyperX, float hyperY) {
        Map<String, Answer> answers = answers();
        answers.put("getId", args -> id);
        answers.put("getName", args -> name);
        answers.put("getTypeId", args -> typeId);
        answers.put("isStar", args -> star);
        answers.put("isGasGiant", args -> gasGiant);
        answers.put("getMarket", args -> market);
        answers.put("getLocation", args -> new Vector2f(x, y));
        answers.put("getLocationInHyperspace", args -> new Vector2f(hyperX, hyperY));
        return proxy(PlanetAPI.class, answers);
    }

    /** Conditions double as the {@code hasCondition} answers so {@code Misc.hasRuins} sees them. */
    private static MarketAPI conditionMarket(boolean planetConditionOnly, MarketAPI.SurveyLevel level,
                                             float hazard, List<String> conditionIds) {
        List<MarketConditionAPI> conditions = new ArrayList<>();
        for (String conditionId : conditionIds) {
            conditions.add(proxy(MarketConditionAPI.class, answers("getId", args -> conditionId)));
        }
        Map<String, Answer> answers = answers();
        answers.put("isPlanetConditionMarketOnly", args -> planetConditionOnly);
        answers.put("getSurveyLevel", args -> level);
        answers.put("getHazardValue", args -> hazard);
        answers.put("getConditions", args -> conditions);
        answers.put("hasCondition", args -> conditionIds.contains(String.valueOf(args[0])));
        answers.put("getMemoryWithoutUpdate", args -> proxy(MemoryAPI.class,
                answers("getBoolean", memoryArgs -> false)));
        return proxy(MarketAPI.class, answers);
    }

    private static LocationAPI system(String id, String name, List<PlanetAPI> planets, String... tags) {
        List<String> tagList = List.of(tags);
        Map<String, Answer> answers = answers();
        answers.put("getId", args -> id);
        answers.put("getName", args -> name);
        answers.put("getPlanets", args -> new ArrayList<>(planets));
        answers.put("hasTag", args -> tagList.contains(String.valueOf(args[0])));
        answers.put("getFleets", args -> new ArrayList<CampaignFleetAPI>());
        return proxy(LocationAPI.class, answers);
    }

    private static LocationAPI hyperspace() {
        return proxy(LocationAPI.class, answers(
                "getId", args -> "hyperspace",
                "isHyperspace", args -> true,
                "getPlanets", args -> new ArrayList<PlanetAPI>(),
                "getFleets", args -> new ArrayList<CampaignFleetAPI>()));
    }

    private static CampaignFleetAPI fleetAt(LocationAPI location, float x, float y,
                                            float hyperX, float hyperY) {
        return proxy(CampaignFleetAPI.class, answers(
                "getContainingLocation", args -> location,
                "getLocation", args -> new Vector2f(x, y),
                "getLocationInHyperspace", args -> new Vector2f(hyperX, hyperY)));
    }

    private static CoopAgentCommands.ColonizableCandidate colonizable(String planetId, float ly, float su) {
        return colonizable(planetId, ly, su, 0);
    }

    private static CoopAgentCommands.ColonizableCandidate colonizable(String planetId, float ly,
                                                                      float su, int marketsInSystem) {
        return new CoopAgentCommands.ColonizableCandidate(planetId, planetId, "terran", false,
                "system", "System", 0f, 0f, marketsInSystem, ly, su, 1f, "FULL", false, List.of());
    }

    private static List<String> ids(List<CoopAgentCommands.ColonizableCandidate> candidates) {
        List<String> out = new ArrayList<>();
        for (CoopAgentCommands.ColonizableCandidate candidate : candidates) {
            out.add(candidate.planetId());
        }
        return out;
    }

    private static List<String> planetIds(JSONObject out) throws JSONException {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < out.getJSONArray("planets").length(); i++) {
            ids.add(out.getJSONArray("planets").getJSONObject(i).getString("planetId"));
        }
        return ids;
    }

    private static List<String> stringList(JSONArray array) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }

    // ---- teleport: naming a target, and crossing locations the way the engine does -----------------

    @Test
    void teleportingToAnEntityLandsClearOfItInItsOwnSystem() throws JSONException {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.system("penelope");
        world.entity("penelope", "aztlan", "Aztlan", 3000f, -1500f, 120f);
        world.playerIn("corvus");

        JSONObject out = CoopAgentCommands.teleport(args("entityId", "aztlan"), world.context());

        assertEquals("aztlan", out.getString("entityId"));
        assertEquals("Aztlan", out.getString("entityName"));
        assertEquals("penelope", out.getString("locationId"), "the entity's location, not the fleet's");
        assertEquals("corvus", out.getString("movedFrom"));
        assertEquals(3320d, out.getDouble("x"), 1e-6, "radius 120 + 200 clearance, straight out on +x");
        assertEquals(-1500d, out.getDouble("y"), 1e-6);
    }

    @Test
    void anEntityTheSectorIndexMissesIsStillFoundByWalkingTheLocations() throws JSONException {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.system("penelope");
        world.entity("penelope", "aztlan", "Aztlan", 3000f, -1500f, 120f);
        world.playerIn("corvus");
        world.sectorIndexAnswers = false;

        JSONObject out = CoopAgentCommands.teleport(args("entityId", "aztlan"), world.context());

        assertEquals("penelope", out.getString("locationId"),
                "the engine rebuilds its id map lazily; the walk behind it is what makes the verb"
                        + " answer for an entity in a system this client has never had current");
    }

    @Test
    void anUnresolvableEntityIdIsRefusedByName() {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.playerIn("corvus");
        CoopAgentCommands.Context context = world.context();

        String message = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.teleport(args("entityId", "not_a_thing"), context))
                .getMessage();

        assertTrue(message.contains("not_a_thing"),
                "a typo has to name what was typed, not read as an empty sector: " + message);
    }

    @Test
    void entityIdAndCoordinatesTogetherAreRefusedRatherThanOneQuietlyWinning() throws JSONException {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.entity("corvus", "ancyra", "Ancyra", 1000f, 0f, 100f);
        world.playerIn("corvus");
        CoopAgentCommands.Context context = world.context();

        JSONObject both = args("entityId", "ancyra");
        both.put("x", 50);
        both.put("y", 50);

        String message = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.teleport(both, context)).getMessage();

        assertTrue(message.contains("entityId") && message.contains("x"),
                "guessing which one the caller meant is how a fleet ends up somewhere nobody asked"
                        + " for: " + message);
    }

    @Test
    void aCrossLocationTeleportGoesThroughTheEngineJumpTransition() throws JSONException {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.system("penelope");
        world.entity("penelope", "aztlan", "Aztlan", 3000f, -1500f, 120f);
        world.playerIn("corvus");

        JSONObject out = CoopAgentCommands.teleport(args("entityId", "aztlan"), world.context());

        assertEquals("jump", out.getString("transition"));
        assertTrue(out.getBoolean("pending"), "the transition is frame-driven; x/y is where it is"
                + " going, not where the fleet is at reply time");
        assertEquals("penelope", world.jumpLocationId);
        assertEquals(3320f, world.jumpPoint.x, 1e-3, "the destination token carries the same point"
                + " the answer reports, so the fleet lands where the caller was told");
        assertEquals(-1500f, world.jumpPoint.y, 1e-3);
        assertTrue(world.localPlacements.isEmpty(),
                "the raw re-parent is the bug: it skips setCurrentLocation, and a fleet outside the"
                        + " engine's current location gets no player input and cannot fly");
    }

    @Test
    void aCrossLocationTeleportByCoordinatesJumpsToo() throws JSONException {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.system("penelope");
        world.playerIn("corvus");

        JSONObject args = new JSONObject();
        args.put("locationId", "penelope");
        args.put("x", 750);
        args.put("y", -250);

        JSONObject out = CoopAgentCommands.teleport(args, world.context());

        assertEquals("jump", out.getString("transition"),
                "the path is chosen by the destination, not by which argument mode was used");
        assertEquals("penelope", world.jumpLocationId);
        assertEquals(750f, world.jumpPoint.x, 1e-3);
        assertEquals("", out.getString("entityId"), "no entity was named, so the field is empty");
    }

    @Test
    void aSameLocationTeleportKeepsTheDirectPlacement() throws JSONException {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.playerIn("corvus");

        JSONObject args = new JSONObject();
        args.put("locationId", "corvus");
        args.put("x", 500);
        args.put("y", 250);

        JSONObject out = CoopAgentCommands.teleport(args, world.context());

        assertEquals("local", out.getString("transition"));
        assertFalse(out.getBoolean("pending"), "a local placement is done by the time this answers");
        assertNull(world.jumpLocationId, "no location change, nothing for a jump transition to fix");
        assertEquals(List.of("500.0,250.0"), world.localPlacements);
    }

    @Test
    void aTeleportIssuedMidJumpIsRefusedRatherThanSwallowedByTheEngine() {
        TeleportWorld world = new TeleportWorld();
        world.system("corvus");
        world.system("penelope");
        world.entity("penelope", "aztlan", "Aztlan", 3000f, -1500f, 120f);
        world.playerIn("corvus");
        world.inTransition = true;
        CoopAgentCommands.Context context = world.context();

        String message = assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.teleport(args("entityId", "aztlan"), context)).getMessage();

        assertTrue(message.contains("jump transition"),
                "doHyperspaceTransition returns silently on a fleet already in one, so the second"
                        + " request would otherwise look like it worked: " + message);
    }

    /** Locations, entities and a player fleet, recording which of the two move paths a teleport took. */
    private static final class TeleportWorld {
        private final Map<String, LocationAPI> locations = new LinkedHashMap<>();
        private final Map<String, SectorEntityToken> entitiesById = new LinkedHashMap<>();
        private final Map<String, String> locationOfEntity = new LinkedHashMap<>();
        private final List<String> localPlacements = new ArrayList<>();

        /** False stands in for the engine's lazily-rebuilt id map coming up empty. */
        private boolean sectorIndexAnswers = true;
        private boolean inTransition;
        private CampaignFleetAPI player;
        private LocationAPI playerLocation;
        private String jumpLocationId;
        private Vector2f jumpPoint;

        private void system(String id) {
            Map<String, Answer> answers = answers();
            answers.put("getId", args -> id);
            answers.put("getName", args -> id);
            answers.put("getEntityById", args -> entityIn(id, String.valueOf(args[0])));
            answers.put("createToken",
                    args -> token(id, ((Number) args[0]).floatValue(), ((Number) args[1]).floatValue()));
            locations.put(id, proxy(LocationAPI.class, answers));
        }

        private void entity(String locationId, String id, String name, float x, float y, float radius) {
            Map<String, Answer> answers = answers();
            answers.put("getId", args -> id);
            answers.put("getName", args -> name);
            answers.put("getLocation", args -> new Vector2f(x, y));
            answers.put("getRadius", args -> radius);
            answers.put("getContainingLocation", args -> locations.get(locationId));
            entitiesById.put(id, proxy(SectorEntityToken.class, answers));
            locationOfEntity.put(id, locationId);
        }

        private void playerIn(String locationId) {
            playerLocation = locations.get(locationId);
            Map<String, Answer> answers = answers();
            answers.put("getContainingLocation", args -> playerLocation);
            answers.put("getLocation", args -> new Vector2f(0f, 0f));
            answers.put("isInHyperspaceTransition", args -> inTransition);
            answers.put("setLocation", args -> {
                localPlacements.add(args[0] + "," + args[1]);
                return null;
            });
            player = proxy(CampaignFleetAPI.class, answers);
        }

        private CoopAgentCommands.Context context() {
            Map<String, Answer> answers = answers();
            answers.put("getPlayerFleet", args -> player);
            answers.put("getAllLocations", args -> new ArrayList<>(locations.values()));
            answers.put("getEntityById",
                    args -> sectorIndexAnswers ? entitiesById.get(String.valueOf(args[0])) : null);
            answers.put("doHyperspaceTransition", args -> {
                SectorEntityToken destination =
                        ((JumpPointAPI.JumpDestination) args[2]).getDestination();
                jumpLocationId = destination.getContainingLocation().getId();
                jumpPoint = destination.getLocation();
                return null;
            });
            return contextFor(proxy(SectorAPI.class, answers));
        }

        private SectorEntityToken entityIn(String locationId, String entityId) {
            return locationId.equals(locationOfEntity.get(entityId))
                    ? entitiesById.get(entityId) : null;
        }

        private SectorEntityToken token(String locationId, float x, float y) {
            Map<String, Answer> answers = answers();
            answers.put("getLocation", args -> new Vector2f(x, y));
            answers.put("getContainingLocation", args -> locations.get(locationId));
            return proxy(SectorEntityToken.class, answers);
        }
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

    private static CoopAgentCommands.Context contextFor(SectorAPI sector, coop.net.CoopNetPump pump) {
        return new CoopAgentCommands.Context() {
            @Override
            public SectorAPI sector() {
                return sector;
            }

            @Override
            public coop.net.CoopNetPump pump() {
                return pump;
            }
        };
    }

    /** A pump whose session is live enough for the pause verb: handshake validated plus a seed. */
    private static coop.net.CoopNetPump sessionPump(CoopConnectionRole role) {
        coop.session.CoopSessionState session = new coop.session.CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new coop.session.CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        session.releaseLobby();
        return new coop.net.CoopNetPump(new RoleNetService(role), session, () -> 1000L);
    }

    /** Transport stand-in: nothing is sent in these tests, only the role is read. */
    private static final class RoleNetService extends coop.net.CoopNetService {
        private final CoopConnectionRole role;

        private RoleNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }
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

    // ---- cargo: the logistics read the QA matrix had to work blind without ----------------------

    @Test
    void cargoReportsEveryLoadAndLimitTheEngineTracks() throws JSONException {
        CampaignFleetAPI player = fleetWithCargo(cargoOf(120f, 90f, 40, 12, 250_000f,
                200f, 150f, 300f, 200f, 52));
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> player));

        JSONObject data = CoopAgentCommands.cargo(new JSONObject(), contextFor(sector, null));

        assertEquals("player-fleet", data.getString("engineId"),
                "named engineId so the structural diff drops it: the two players own different fleets");
        assertEquals(120d, data.getDouble("supplies"));
        assertEquals(90d, data.getDouble("fuel"));
        assertEquals(40, data.getInt("crew"));
        assertEquals(12, data.getInt("marines"));
        assertEquals(250_000d, data.getDouble("credits"));

        assertEquals(200d, data.getJSONObject("cargoSpace").getDouble("capacity"));
        assertEquals(150d, data.getJSONObject("cargoSpace").getDouble("used"));
        assertEquals(50d, data.getJSONObject("cargoSpace").getDouble("free"),
                "free is vanilla's own $cargoRoom subtraction, not a second opinion");
        assertEquals(300d, data.getJSONObject("fuelSpace").getDouble("capacity"));
        assertEquals(90d, data.getJSONObject("fuelSpace").getDouble("used"));
        assertEquals(200d, data.getJSONObject("personnel").getDouble("capacity"));
        assertEquals(52d, data.getJSONObject("personnel").getDouble("used"),
                "personnel is crew plus marines plus anything else aboard, so it is read whole");

        assertFalse(data.getBoolean("overloaded"));
        assertEquals(0, data.getJSONArray("over").length());
    }

    @Test
    void cargoNamesEveryDimensionThatIsOverItsLimit() throws JSONException {
        // Cargo and personnel over, fuel comfortably inside: the flag must not hide which is which.
        CampaignFleetAPI player = fleetWithCargo(cargoOf(400f, 20f, 200, 60, 0f,
                200f, 260f, 300f, 200f, 260));
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> player));

        JSONObject data = CoopAgentCommands.cargo(new JSONObject(), contextFor(sector, null));

        assertTrue(data.getBoolean("overloaded"));
        assertEquals(List.of("cargoSpace", "personnel"), stringsOf(data.getJSONArray("over")));
        assertEquals(-60d, data.getJSONObject("cargoSpace").getDouble("free"),
                "over capacity reads as negative room, the way the engine's own subtraction does");
        assertEquals(280d, data.getJSONObject("fuelSpace").getDouble("free"));
    }

    @Test
    void cargoRefusesWhenThereIsNoPlayerFleetRatherThanReportingZeroes() {
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> null));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.cargo(new JSONObject(), contextFor(sector, null)));
        assertEquals("no player fleet", failure.getMessage());
    }

    // ---- addship: the setup verb `give` could not cover -----------------------------------------

    @Test
    void addshipCreatesReadyMembersAndReportsTheFleetHashChange() throws JSONException {
        List<FleetMemberAPI> roster = new ArrayList<>();
        List<Boolean> repaired = new ArrayList<>();
        CampaignFleetAPI player = fleetWithRoster(roster);
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> player));
        installFactory(roster, repaired);
        installSettingsKnowing("shuttle_Hauler");

        JSONObject args = new JSONObject();
        args.put("variantId", "shuttle_Hauler");
        args.put("count", 2);
        JSONObject data = CoopAgentCommands.addship(args, contextFor(sector, null));

        assertEquals(2, data.getInt("added"));
        assertEquals(2, data.getInt("requested"));
        assertEquals(2, data.getInt("fleetSize"));
        assertEquals(2, roster.size(), "the members must land in the fleet's own data, not a copy");
        assertEquals(List.of(true, true), repaired, "an added ship must arrive combat-ready");
        assertEquals(0.7d, data.getJSONArray("members").getJSONObject(0).getDouble("cr"),
                "CR is set to the member's max, not left at the factory default");
        assertEquals("shuttle_Hauler",
                data.getJSONArray("members").getJSONObject(1).getString("variantId"));

        assertTrue(data.getBoolean("fleetHashChanged"));
        assertFalse(data.getString("fleetHashBefore").isEmpty());
        assertFalse(data.getString("fleetHashAfter").equals(data.getString("fleetHashBefore")),
                "the hash is what makes the replicator resend the roster; an unchanged one is the bug");
    }

    @Test
    void addshipDefaultsToOneShip() throws JSONException {
        List<FleetMemberAPI> roster = new ArrayList<>();
        CampaignFleetAPI player = fleetWithRoster(roster);
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> player));
        installFactory(roster, new ArrayList<>());
        installSettingsKnowing("wolf_Assault");

        JSONObject args = new JSONObject();
        args.put("variantId", "wolf_Assault");
        JSONObject data = CoopAgentCommands.addship(args, contextFor(sector, null));

        assertEquals(1, data.getInt("added"));
        assertEquals(1, roster.size());
    }

    @Test
    void addshipRefusesAnUnknownVariantByNameAndAddsNothing() {
        List<FleetMemberAPI> roster = new ArrayList<>();
        CampaignFleetAPI player = fleetWithRoster(roster);
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> player));
        installFactory(roster, new ArrayList<>());
        installSettingsKnowing("wolf_Assault");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            JSONObject args = new JSONObject();
            args.put("variantId", "wolf_Asault");
            CoopAgentCommands.addship(args, contextFor(sector, null));
        });

        // The factory substitutes a placeholder hull for an unknown id instead of refusing it, so
        // without this check a typo would quietly add the wrong ship.
        assertTrue(failure.getMessage().startsWith("unknown variant wolf_Asault"), failure.getMessage());
        assertEquals(0, roster.size());
    }

    @Test
    void addshipRefusesACountOutsideTheAllowedRange() {
        List<FleetMemberAPI> roster = new ArrayList<>();
        CampaignFleetAPI player = fleetWithRoster(roster);
        SectorAPI sector = proxy(SectorAPI.class, answers("getPlayerFleet", args -> player));
        installFactory(roster, new ArrayList<>());
        installSettingsKnowing("wolf_Assault");

        for (int count : new int[]{0, -3, CoopAgentCommands.MAX_ADDED_SHIPS + 1}) {
            assertThrows(IllegalArgumentException.class, () -> {
                JSONObject args = new JSONObject();
                args.put("variantId", "wolf_Assault");
                args.put("count", count);
                CoopAgentCommands.addship(args, contextFor(sector, null));
            }, "count " + count + " must be refused");
        }
        assertEquals(0, roster.size());
    }

    // ---- cargo / addship fakes ------------------------------------------------------------------

    private static List<String> stringsOf(JSONArray array) throws JSONException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private static CampaignFleetAPI fleetWithCargo(com.fs.starfarer.api.campaign.CargoAPI cargo) {
        return proxy(CampaignFleetAPI.class, answers(
                "getId", args -> "player-fleet",
                "getCargo", args -> cargo));
    }

    private static com.fs.starfarer.api.campaign.CargoAPI cargoOf(
            float supplies, float fuel, int crew, int marines, float credits,
            float maxCapacity, float spaceUsed, float maxFuel, float maxPersonnel, int totalPersonnel) {
        Map<String, Answer> map = answers();
        map.put("getSupplies", args -> supplies);
        map.put("getFuel", args -> fuel);
        map.put("getCrew", args -> crew);
        map.put("getMarines", args -> marines);
        map.put("getCredits", args -> new com.fs.starfarer.api.util.MutableValue(credits));
        map.put("getMaxCapacity", args -> maxCapacity);
        map.put("getSpaceUsed", args -> spaceUsed);
        map.put("getMaxFuel", args -> maxFuel);
        map.put("getMaxPersonnel", args -> maxPersonnel);
        map.put("getTotalPersonnel", args -> totalPersonnel);
        return proxy(com.fs.starfarer.api.campaign.CargoAPI.class, map);
    }

    private static CampaignFleetAPI fleetWithRoster(List<FleetMemberAPI> roster) {
        com.fs.starfarer.api.campaign.FleetDataAPI data =
                proxy(com.fs.starfarer.api.campaign.FleetDataAPI.class, answers(
                        "getMembersListCopy", args -> new ArrayList<>(roster),
                        "addFleetMember", args -> {
                            roster.add((FleetMemberAPI) args[0]);
                            return null;
                        }));
        return proxy(CampaignFleetAPI.class, answers(
                "getId", args -> "player-fleet",
                "getName", args -> "Player Fleet",
                "getFleetData", args -> data));
    }

    /** A factory that hands back a distinct, readable member each call, ids numbered from the roster. */
    private void installFactory(List<FleetMemberAPI> roster,
                                List<Boolean> repaired) {
        Global.setFactory(proxy(com.fs.starfarer.api.FactoryAPI.class, answers(
                "createFleetMember", args -> fakeMember(
                        "member-" + (roster.size() + 1), String.valueOf(args[1]), repaired))));
    }

    private static FleetMemberAPI fakeMember(
            String memberId, String variantId, List<Boolean> repaired) {
        float[] cr = {0f};
        com.fs.starfarer.api.fleet.RepairTrackerAPI repair =
                proxy(com.fs.starfarer.api.fleet.RepairTrackerAPI.class, answers(
                        "getMaxCR", args -> 0.7f,
                        "getCR", args -> cr[0],
                        "setCR", args -> {
                            cr[0] = (Float) args[0];
                            return null;
                        }));
        com.fs.starfarer.api.fleet.FleetMemberStatusAPI status =
                proxy(com.fs.starfarer.api.fleet.FleetMemberStatusAPI.class, answers(
                        "getHullFraction", args -> 1f,
                        "repairFully", args -> {
                            repaired.add(true);
                            return null;
                        }));
        return proxy(FleetMemberAPI.class, answers(
                "getId", args -> memberId,
                "getSpecId", args -> variantId,
                "getHullId", args -> "shuttle",
                "getRepairTracker", args -> repair,
                "getStatus", args -> status));
    }

    /** Settings that know exactly one variant id, which is what makes the refusal test meaningful. */
    private void installSettingsKnowing(String variantId) {
        Global.setSettings(proxy(SettingsAPI.class, answers(
                "getFloat", args -> 2000f,
                "doesVariantExist", args -> variantId.equals(args[0]))));
    }

    // ---- entities: the verb that makes a hidden base addressable ----------------------------------

    /**
     * The classification, and the point of the whole verb: the hidden base is a {@code base} even
     * though it also carries the station tag, and the id it emits is one {@code teleport} takes.
     */
    @Test
    void entitiesClassifiesEveryKindAndTeleportAcceptsTheHiddenBaseItNames() throws JSONException {
        EntityWorld world = new EntityWorld();

        JSONObject listed = CoopAgentCommands.entities(new JSONObject(), world.context());

        assertEquals("corvus", listed.getString("locationId"));
        assertFalse(listed.getBoolean("truncated"));
        Map<String, String> kinds = new LinkedHashMap<>();
        JSONArray rows = listed.getJSONArray("entities");
        for (int i = 0; i < rows.length(); i++) {
            kinds.put(rows.getJSONObject(i).getString("id"), rows.getJSONObject(i).getString("kind"));
        }
        assertEquals(Map.of(
                        "corvus_i", "planet",
                        "corvus_station", "station",
                        "corvus_jump", "jumpPoint",
                        "corvus_relay", "relay",
                        "pirate_base", "base",
                        "fleet_1", "fleet",
                        "corvus_rock", "other"),
                kinds);

        JSONObject base = rowById(rows, "pirate_base");
        assertTrue(base.getBoolean("hidden"), "a hidden market is what makes it a base");
        assertEquals("market_pirate", base.getString("marketId"),
                "the key to look up in status.baseMarketIds to see whether the guest paired it");
        assertTrue(base.getBoolean("discoverable"), "vanilla's not-found-yet flag");
        assertEquals(500d, base.getDouble("x"));

        // The whole reason the verb exists: an id from this list has to be a teleport argument.
        JSONObject teleported =
                CoopAgentCommands.teleport(args("entityId", "pirate_base"), world.context());
        assertEquals("corvus", teleported.getString("locationId"));
        assertEquals("local", teleported.getString("transition"));
        assertEquals("pirate_base", teleported.getString("entityId"));
    }

    @Test
    void entitiesFiltersByKindAndRefusesAKindItDoesNotKnow() throws JSONException {
        EntityWorld world = new EntityWorld();

        JSONObject basesOnly = CoopAgentCommands.entities(
                args("kinds", new JSONArray(List.of("base", "relay"))), world.context());

        assertEquals(2, basesOnly.getInt("count"));
        assertEquals(List.of("relay", "base"), kindsOf(basesOnly),
                "output order is the registry's, whatever order the request used");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.entities(args("kinds", "bases"), world.context()));
        assertTrue(refused.getMessage().startsWith("unknown entity kind bases; known kinds:"),
                refused.getMessage());
    }

    /**
     * A star system can be named by its generated id or by its name, and hyperspace by the keyword —
     * the same widening {@code teleport}'s coordinate mode now shares, so an argument that works in
     * one works in the other.
     */
    @Test
    void entitiesResolvesASystemByIdByNameAndByTheHyperspaceKeyword() throws JSONException {
        EntityWorld world = new EntityWorld();

        assertEquals("corvus",
                CoopAgentCommands.entities(args("system", "corvus"), world.context())
                        .getString("locationId"));
        assertEquals("corvus",
                CoopAgentCommands.entities(args("system", "Corvus Star System"), world.context())
                        .getString("locationId"));
        assertEquals("hyperspace",
                CoopAgentCommands.entities(args("system", "hyperspace"), world.context())
                        .getString("locationId"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.entities(args("system", "nowhere"), world.context()));
        assertTrue(refused.getMessage().contains("no location with id or name nowhere"),
                refused.getMessage());
    }

    /** The {system, x, y} form, which is the escape hatch when an entity id is not what you have. */
    @Test
    void teleportTakesASystemNameWithCoordinates() throws JSONException {
        EntityWorld world = new EntityWorld();

        JSONObject out = CoopAgentCommands.teleport(
                teleportArgs("Corvus Star System", 1_200d, -300d), world.context());

        assertEquals("corvus", out.getString("locationId"));
        assertEquals(1_200d, out.getDouble("x"));
        assertEquals(-300d, out.getDouble("y"));
        assertEquals("local", out.getString("transition"));
    }

    @Test
    void teleportStillRefusesBothArgumentModesAtOnce() {
        EntityWorld world = new EntityWorld();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.teleport(
                        args("entityId", "pirate_base", "system", "corvus"), world.context()));
        assertTrue(refused.getMessage().contains("not both; got entityId and system"),
                refused.getMessage());
    }

    // ---- intel ------------------------------------------------------------------------------------

    /**
     * The shape, over an entry that answers {@link coop.util.CoopIntelFacts}.
     *
     * <p><b>No Hostile Activity fixture.</b> {@code HostileActivityEventIntel} cannot be built
     * outside a running engine — its constructor registers listeners and walks the economy, and its
     * static initialiser reads a settings key — so the row shape is exercised on the coop-facts
     * branch instead, and the {@code hostileActivity} block is tested for the two answers it can give
     * without one: absent, and unreadable.
     */
    @Test
    void intelReportsFlagsTitleTagsAndACoopEntrysOwnFacts() throws JSONException {
        Object entry = intelEntry("Coop Stats", Set.of("coop", "info"),
                Map.of("fleetsDestroyedTeam", 12L, "daysElapsed", 3.5f));
        CoopAgentCommands.Context context = intelContext(List.of(entry));

        JSONObject out = CoopAgentCommands.intel(new JSONObject(), context);

        assertEquals(1, out.getInt("total"));
        assertEquals(1, out.getInt("count"));
        JSONObject row = out.getJSONArray("intel").getJSONObject(0);
        assertEquals("Coop Stats", row.getString("title"));
        assertEquals(List.of("coop", "info"), stringsOf(row.getJSONArray("tags")),
                "tags are sorted so two clients emit the same row");
        assertTrue(row.getBoolean("isNew"));
        assertFalse(row.getBoolean("isEnded"));
        assertEquals("independent", row.getString("factionId"));
        assertEquals(12L, row.getJSONObject("extra").getLong("fleetsDestroyedTeam"));
        assertEquals(3.5d, row.getJSONObject("extra").getDouble("daysElapsed"), 0.0001d);
    }

    @Test
    void intelFiltersOnClassOrTitleAndValidatesItsLimit() throws JSONException {
        CoopAgentCommands.Context context = intelContext(List.of(
                intelEntry("Coop Stats", Set.of(), Map.of()),
                intelEntry("Hostile Activity", Set.of(), Map.of())));

        assertEquals(1, CoopAgentCommands.intel(args("filter", "hostile"), context).getInt("count"));
        assertEquals(2, CoopAgentCommands.intel(args("filter", ""), context).getInt("count"));
        assertEquals(0, CoopAgentCommands.intel(args("filter", "nothing"), context).getInt("count"));
        assertEquals(1, CoopAgentCommands.intel(args("limit", 1), context).getInt("count"),
                "the cap trims after the match count is recorded");
        assertEquals(2, CoopAgentCommands.intel(args("limit", 1), context).getInt("matched"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.intel(args("limit", 0), context));
        assertTrue(refused.getMessage().startsWith("limit must be between 1 and 300"),
                refused.getMessage());
    }

    /**
     * The block step 30 of the smoke reads. With no Hostile Activity in sector memory the answer is
     * a positive "not present", not a missing row — and with something unreadable under the key it
     * says so rather than throwing, because the class is touched through a cast.
     */
    @Test
    void theHostileActivityBlockAnswersAbsentAndUnreadableWithoutTakingTheVerbDown()
            throws JSONException {
        Map<String, Object> memory = new LinkedHashMap<>();
        Global.setSector(coop.testing.ApiProxies.sectorWithMemory(
                coop.testing.ApiProxies.memory(memory)));

        JSONObject absent = CoopAgentCommands.hostileActivityBlock();
        assertFalse(absent.getBoolean("present"));
        assertFalse(absent.has("unreadable"));

        memory.put("$hae_ref", "not an intel entry");
        JSONObject unreadable = CoopAgentCommands.hostileActivityBlock();
        assertFalse(unreadable.getBoolean("present"));
        assertEquals("ClassCastException", unreadable.getString("unreadable"));
    }

    // ---- feed -------------------------------------------------------------------------------------

    /**
     * The verb reads the static handle when there is no pump, which is the case that matters: the
     * pump's feed is gone by the time a tester asks what the screen said.
     */
    @Test
    void feedReturnsTheRingNewestLastAndSaysTheSessionEnded() throws JSONException {
        coop.ui.CoopSessionIntelFeed feed = new coop.ui.CoopSessionIntelFeed(() -> 5_000L);
        feed.publishSession(CoopConnectionRole.GUEST, "connected", "Ayo");
        feed.noteEvent("fallback", "Co-op: fell back to TCP.", new java.awt.Color(1, 2, 3));
        feed.noteEvent("partner-left", "Co-op: Ayo left the game.", null);
        feed.endSession();
        coop.ui.CoopSessionIntelFeed.install(feed);
        try {
            JSONObject out = CoopAgentCommands.feed(new JSONObject(), contextFor(null));

            assertTrue(out.getBoolean("installed"));
            assertTrue(out.getBoolean("sessionEnded"));
            assertEquals("NONE", out.getString("role"), "endSession drops the role, on purpose");
            assertEquals(2, out.getInt("count"));
            JSONArray lines = out.getJSONArray("lines");
            assertEquals("Co-op: fell back to TCP.", lines.getJSONObject(0).getString("text"));
            assertEquals("fallback", lines.getJSONObject(0).getString("kind"));
            assertEquals("#010203", lines.getJSONObject(0).getString("color"));
            assertEquals(5_000L, lines.getJSONObject(0).getLong("atMillis"));
            assertEquals("Co-op: Ayo left the game.", lines.getJSONObject(1).getString("text"));

            assertEquals(1, CoopAgentCommands.feed(args("limit", 1), contextFor(null)).getInt("count"));
        } finally {
            coop.ui.CoopSessionIntelFeed.uninstall();
        }
    }

    @Test
    void feedValidatesItsLimitAgainstTheRingDepth() {
        IllegalArgumentException tooSmall = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.feed(args("limit", 0), contextFor(null)));
        assertEquals("limit must be between 1 and 200, got 0", tooSmall.getMessage());

        IllegalArgumentException tooBig = assertThrows(IllegalArgumentException.class,
                () -> CoopAgentCommands.feed(args("limit", 201), contextFor(null)));
        assertEquals("limit must be between 1 and 200, got 201", tooBig.getMessage());
    }

    // ---- screen -----------------------------------------------------------------------------------

    @Test
    void screenReportsTheDialogTheTabAndTheInteractionTarget() throws JSONException {
        SectorEntityToken target = proxy(SectorEntityToken.class, answers(
                "getId", args -> "jangala",
                "getName", args -> "Jangala"));
        InteractionDialogPluginStub plugin = new InteractionDialogPluginStub();
        InteractionDialogAPI dialog = proxy(InteractionDialogAPI.class, answers(
                "getPlugin", args -> plugin,
                "getInteractionTarget", args -> target));
        CampaignUIAPI ui = proxy(CampaignUIAPI.class, answers(
                "isShowingDialog", args -> true,
                "isShowingMenu", args -> false,
                "getCurrentCoreTab", args -> CoreUITabId.CARGO,
                "getCurrentInteractionDialog", args -> dialog));
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getCampaignUI", args -> ui,
                "isPaused", args -> true));

        JSONObject out = CoopAgentCommands.screen(new JSONObject(), contextFor(sector));

        assertTrue(out.getBoolean("paused"));
        assertTrue(out.getBoolean("uiAvailable"));
        assertTrue(out.getBoolean("dialogOpen"));
        assertFalse(out.getBoolean("menuOpen"));
        assertEquals("InteractionDialogPluginStub", out.getString("interactionDialog"),
                "the plugin names the dialog; the engine's own dialog class says nothing");
        assertEquals("jangala", out.getString("interactionTargetId"));
        assertEquals("Jangala", out.getString("interactionTargetName"));
        assertEquals("CARGO", out.getString("coreTab"));
        assertTrue(out.isNull("coopDialog"), "no pump means no coop dialog can be requested");
        assertTrue(out.getJSONObject("pause").getBoolean("blockingScreenOpen"),
                "the same predicate the guest ships as PAUSE_INTENT(SCREEN); an open dialog is one");
    }

    /** A campaign with no UI yet is a state, not a failure: every other field still answers. */
    @Test
    void screenDegradesFieldByFieldWhenTheUiIsNotThere() throws JSONException {
        SectorAPI sector = proxy(SectorAPI.class, answers(
                "getCampaignUI", args -> null,
                "isPaused", args -> false));

        JSONObject out = CoopAgentCommands.screen(new JSONObject(), contextFor(sector));

        assertFalse(out.getBoolean("uiAvailable"));
        assertFalse(out.getBoolean("dialogOpen"));
        assertTrue(out.isNull("interactionDialog"));
        assertTrue(out.isNull("coreTab"));
        assertEquals("", out.getString("interactionTargetId"));
    }

    // ---- fixtures for the 0.1.1 smoke verbs -------------------------------------------------------

    private static JSONObject teleportArgs(String system, double x, double y) throws JSONException {
        JSONObject out = args("system", system, "x", x);
        out.put("y", y);
        return out;
    }

    private static JSONObject rowById(JSONArray rows, String id) throws JSONException {
        for (int i = 0; i < rows.length(); i++) {
            if (id.equals(rows.getJSONObject(i).optString("id", ""))) {
                return rows.getJSONObject(i);
            }
        }
        throw new AssertionError("no row with id " + id + " in " + rows);
    }

    private static List<String> kindsOf(JSONObject listed) throws JSONException {
        List<String> kinds = new ArrayList<>();
        JSONArray rows = listed.getJSONArray("entities");
        for (int i = 0; i < rows.length(); i++) {
            kinds.add(rows.getJSONObject(i).getString("kind"));
        }
        return kinds;
    }

    /**
     * One star system carrying one of every entity kind, plus hyperspace, plus a player fleet in it.
     * The location is handed to the entities through a holder because they refer to each other.
     */
    private static final class EntityWorld {
        private final LocationAPI[] here = new LocationAPI[1];
        private final SectorAPI sector;

        private EntityWorld() {
            SectorEntityToken planet = proxy(PlanetAPI.class, entityAnswers(here, "corvus_i", "Jangala",
                    "planet", 0f, 0f, Set.of(), null));
            SectorEntityToken station = proxy(SectorEntityToken.class, entityAnswers(here, "corvus_station",
                    "Jangala Station", "station", 100f, 0f, Set.of("station"), null));
            SectorEntityToken jump = proxy(JumpPointAPI.class, entityAnswers(here, "corvus_jump",
                    "Jump Point", "", 200f, 0f, Set.of(), null));
            SectorEntityToken relay = proxy(SectorEntityToken.class, entityAnswers(here, "corvus_relay",
                    "Comm Relay", "comm_relay", 300f, 0f, Set.of("objective"), null));
            SectorEntityToken rock = proxy(SectorEntityToken.class, entityAnswers(here, "corvus_rock",
                    "Asteroid", "", 400f, 0f, Set.of(), null));
            // A hidden base is a station with a hidden market; the market is what decides.
            MarketAPI hidden = proxy(MarketAPI.class, answers(
                    "getId", args -> "market_pirate",
                    "isHidden", args -> true));
            SectorEntityToken base = proxy(SectorEntityToken.class, entityAnswers(here, "pirate_base",
                    "Pirate Base", "station", 500f, 0f, Set.of("station"), hidden));
            CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, entityAnswers(here, "fleet_1",
                    "Pirate Armada", "", 600f, 0f, Set.of(), null));

            Map<String, Answer> corvus = answers();
            corvus.put("getId", args -> "corvus");
            corvus.put("getName", args -> "Corvus Star System");
            corvus.put("getAllEntities", args -> List.of(planet, station, jump, relay, rock, base));
            corvus.put("getFleets", args -> List.of(fleet));
            corvus.put("getEntityById", args -> "pirate_base".equals(args[0]) ? base : null);
            here[0] = proxy(StarSystemAPI.class, corvus);

            LocationAPI hyper = proxy(LocationAPI.class, answers(
                    "getId", args -> "hyperspace",
                    "getName", args -> "Hyperspace",
                    "isHyperspace", args -> true,
                    "getAllEntities", args -> List.of(),
                    "getFleets", args -> List.of()));

            CampaignFleetAPI player = proxy(CampaignFleetAPI.class, answers(
                    "getContainingLocation", args -> here[0],
                    "getLocation", args -> new Vector2f(0f, 0f)));

            Map<String, Answer> answers = answers();
            answers.put("getPlayerFleet", args -> player);
            answers.put("getAllLocations", args -> List.of(here[0], hyper));
            answers.put("getStarSystems", args -> List.of(here[0]));
            answers.put("getStarSystem",
                    args -> "Corvus Star System".equals(args[0]) ? here[0] : null);
            answers.put("getHyperspace", args -> hyper);
            answers.put("getEntityById", args -> "pirate_base".equals(args[0]) ? base : null);
            sector = proxy(SectorAPI.class, answers);
        }

        private CoopAgentCommands.Context context() {
            return contextFor(sector);
        }
    }

    private static Map<String, Answer> entityAnswers(LocationAPI[] here, String id, String name,
                                                     String type, float x, float y, Set<String> tags,
                                                     MarketAPI market) {
        Map<String, Answer> answers = answers();
        answers.put("getContainingLocation", args -> here[0]);
        answers.put("getId", args -> id);
        answers.put("getName", args -> name);
        answers.put("getCustomEntityType", args -> type);
        answers.put("getLocation", args -> new Vector2f(x, y));
        answers.put("getTags", args -> tags);
        answers.put("hasTag", args -> tags.contains(args[0]));
        answers.put("getMarket", args -> market);
        answers.put("isDiscoverable", args -> true);
        answers.put("getRadius", args -> 0f);
        return answers;
    }

    /** An intel entry that is also one of ours, so the {@code extra} branch has something to read. */
    private static Object intelEntry(String title, Set<String> tags, Map<String, Object> facts) {
        FactionAPI faction = proxy(FactionAPI.class, answers("getId", args -> "independent"));
        return java.lang.reflect.Proxy.newProxyInstance(
                CoopAgentQueryVerbsTest.class.getClassLoader(),
                new Class<?>[]{IntelInfoPlugin.class, coop.util.CoopIntelFacts.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getSmallDescriptionTitle" -> title;
                    case "getIntelTags" -> tags;
                    case "isNew" -> true;
                    case "getFactionForUIColors" -> faction;
                    case "intelFacts" -> facts;
                    case "toString" -> "Intel[" + title + "]";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> zeroFor(method.getReturnType());
                });
    }

    private static CoopAgentCommands.Context intelContext(List<Object> entries) {
        IntelManagerAPI manager = proxy(IntelManagerAPI.class, answers(
                "getIntel", args -> args.length == 0 ? entries : List.of()));
        return contextFor(proxy(SectorAPI.class, answers("getIntelManager", args -> manager)));
    }

    /** Named rather than a lambda: {@code screen} reports the plugin by its class. */
    private static final class InteractionDialogPluginStub implements InteractionDialogPlugin {
        @Override
        public void init(com.fs.starfarer.api.campaign.InteractionDialogAPI dialog) {
        }

        @Override
        public void optionSelected(String optionText, Object optionData) {
        }

        @Override
        public void optionMousedOver(String optionText, Object optionData) {
        }

        @Override
        public void advance(float amount) {
        }

        @Override
        public void backFromEngagement(com.fs.starfarer.api.combat.EngagementResultAPI battleResult) {
        }

        @Override
        public Object getContext() {
            return null;
        }

        @Override
        public Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> getMemoryMap() {
            return Map.of();
        }
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
