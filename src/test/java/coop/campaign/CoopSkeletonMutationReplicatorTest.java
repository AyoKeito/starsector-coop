package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyDecivListener;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.GateEntityPlugin;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Engine-glue coverage for the Phase 13 skeleton mutations: the host's poll and deciv listener, and
 * the guest's three appliers. The engine is stood up as interface proxies (no mocking framework in
 * this build), which is enough because every real decision lives in
 * {@link CoopSkeletonMutationWatcher} and is tested there.
 */
class CoopSkeletonMutationReplicatorTest {

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
    }

    // ---- Host capture --------------------------------------------------------------------------

    @Test
    void hostPollSeedsSilentlyThenReportsEveryObjectiveFlipIncludingFlipsBack() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas();
        assertTrue(service.sent.isEmpty(), "the seeding poll reports nothing");

        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        relay.factionId = "hegemony";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(List.of("pirates", "hegemony", "pirates"), ownershipPayloads(service.sent));
    }

    @Test
    void hostPollIsThrottled() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas();
        relay.factionId = "pirates";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS - 1);
        replicator.tickWorldDeltas();

        assertTrue(service.sent.isEmpty());
    }

    @Test
    void hostReportsAGateThatIsAlreadyScannedWhenTheSessionStarts() {
        FakeSector sector = new FakeSector();
        FakeEntity gate = sector.addGate("gate-galatia");
        gate.memory.set(GateEntityPlugin.GATE_SCANNED, true);
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), new MutableClock(1_000_000L));

        replicator.tickWorldDeltas();

        assertEquals(1, service.sent.size());
        assertEquals("GATE_ACTIVATED", CoopMessages.requiredPayloadString(service.sent.get(0), "kind"));
        assertEquals("gate-galatia",
                CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        assertEquals(CoopSkeletonMutationWatcher.encodeGateState(true, false, false),
                CoopMessages.requiredPayloadString(service.sent.get(0), "newStateJson"));
    }

    /**
     * Phase 12c gap 3b: {@code Objectives.control} runs in the guest's own dialog, so the guest polls
     * objectives too and reports the capture upward on the same channel.
     */
    @Test
    void guestReportsItsOwnObjectiveCapture() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), clock);

        replicator.tickWorldDeltas();
        assertTrue(service.sent.isEmpty(), "the seeding poll reports nothing");

        relay.factionId = "player";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(List.of("player"), ownershipPayloads(service.sent));
        assertEquals("guest-player",
                CoopMessages.requiredPayloadString(service.sent.get(0), "actingPlayerId"));
    }

    /** The host's verbatim rebroadcast of what the guest just reported must die in the ledger. */
    @Test
    void guestSwallowsTheHostsEchoOfItsOwnCapture() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), clock);

        replicator.tickWorldDeltas();
        relay.factionId = "player";
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();
        assertEquals(1, service.sent.size());
        int setFactionCalls = relay.setFactionCalls;

        replicator.handle(ownershipMessage("relay-1", "player"));

        assertEquals(1, service.sent.size(), "the echo must not be re-reported");
        assertEquals(setFactionCalls, relay.setFactionCalls, "the echo must not touch the engine");

        // And the next poll finds nothing new either.
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();
        assertEquals(1, service.sent.size());
    }

    /** Gates and deciv stay host-only: the guest's producers for both are suppressed. */
    @Test
    void guestNeverCapturesGates() {
        FakeSector sector = new FakeSector();
        FakeEntity gate = sector.addGate("gate-galatia");
        gate.memory.set(GateEntityPlugin.GATE_SCANNED, true);
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), clock);

        replicator.tickWorldDeltas();
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertTrue(service.sent.isEmpty(), "the host owns gate activation");
    }

    /**
     * Host integration of a guest capture: apply it to the authoritative world, rebroadcast once, and
     * let the ledger stop the host's own next poll from reporting the same value straight back.
     */
    @Test
    void hostIntegratesAGuestObjectiveCaptureWithoutEchoingItTwice() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas(); // seed the host baseline at "hegemony"
        assertTrue(service.sent.isEmpty());

        replicator.handle(CoopMessages.worldDelta("session-a", 1L, 0L, "relay-1",
                "OBJECTIVE_OWNERSHIP", false, "player", "guest-player"));

        assertEquals("player", relay.factionId);
        assertEquals(List.of("player"), ownershipPayloads(service.sent), "rebroadcast exactly once");

        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(1, service.sent.size(), "the host's poll must not re-report what it just applied");
    }

    // ---- Survey levels + ruins (Phase 12c build task D) ----------------------------------------

    @Test
    void pollSeedsSurveyLevelsSilentlyThenReportsEveryStep() {
        FakeSector sector = new FakeSector();
        sector.addStar("star-1");
        FakePlanet planet = sector.addPlanet("planet-1");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas();
        assertTrue(service.sent.isEmpty(), "hundreds of identical planets must not seed the wire");

        planet.level = MarketAPI.SurveyLevel.SEEN;
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        planet.level = MarketAPI.SurveyLevel.PRELIMINARY;
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        planet.level = MarketAPI.SurveyLevel.FULL;
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(List.of("SEEN", "PRELIMINARY", "FULL"), payloadsOfKind(service.sent, "SURVEY"));
        assertEquals("planet-1", CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        // SEEN is replicated too: the system map colours a system by its minimum survey level, so
        // filtering it out would leave the two maps visibly different.
        assertEquals(3, service.sent.size(), "the star has no survey state of its own");
    }

    /** Both players survey, so the guest polls and reports its own surveys upward. */
    @Test
    void guestReportsItsOwnSurveyAndSwallowsTheHostsEcho() {
        FakeSector sector = new FakeSector();
        FakePlanet planet = sector.addPlanet("planet-1");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), clock);

        replicator.tickWorldDeltas();
        planet.level = MarketAPI.SurveyLevel.FULL;
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(List.of("FULL"), payloadsOfKind(service.sent, "SURVEY"));
        assertEquals("guest-player",
                CoopMessages.requiredPayloadString(service.sent.get(0), "actingPlayerId"));

        int setLevelCalls = planet.setSurveyLevelCalls;
        replicator.handle(surveyMessage("planet-1", "FULL"));
        assertEquals(1, service.sent.size(), "the host's echo must not be re-reported");
        assertEquals(setLevelCalls, planet.setSurveyLevelCalls, "the echo must not touch the engine");

        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();
        assertEquals(1, service.sent.size());
    }

    /** The other direction: the host applies a guest survey, rebroadcasts once, and stops there. */
    @Test
    void hostIntegratesAGuestSurveyWithoutEchoingItTwice() {
        FakeSector sector = new FakeSector();
        FakePlanet planet = sector.addPlanet("planet-1");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), clock);

        replicator.tickWorldDeltas();
        replicator.handle(surveyMessage("planet-1", "PRELIMINARY"));

        assertEquals(MarketAPI.SurveyLevel.PRELIMINARY, planet.level);
        assertEquals(List.of("PRELIMINARY"), payloadsOfKind(service.sent, "SURVEY"));

        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();
        assertEquals(1, service.sent.size(), "the host's poll must not re-report what it just applied");
    }

    @Test
    void surveyLevelOnlyEverRises() {
        FakeSector sector = new FakeSector();
        FakePlanet planet = sector.addPlanet("planet-1");
        planet.level = MarketAPI.SurveyLevel.PRELIMINARY;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        // Lower and equal levels are dropped: two independently polling clients can deliver a stale
        // SEEN after a FULL, and the field is monotonic in vanilla.
        replicator.handle(surveyMessage("planet-1", "SEEN"));
        assertEquals(MarketAPI.SurveyLevel.PRELIMINARY, planet.level);
        assertEquals(0, planet.setSurveyLevelCalls);

        replicator.worldLedger().clear();
        replicator.handle(surveyMessage("planet-1", "PRELIMINARY"));
        assertEquals(0, planet.setSurveyLevelCalls);

        replicator.worldLedger().clear();
        replicator.handle(surveyMessage("planet-1", "FULL"));
        assertEquals(MarketAPI.SurveyLevel.FULL, planet.level);
        assertEquals(1, planet.setSurveyLevelCalls);

        // Idempotent with the ledger out of the way, which is what makes reordering safe.
        replicator.worldLedger().clear();
        replicator.handle(surveyMessage("planet-1", "FULL"));
        assertEquals(1, planet.setSurveyLevelCalls);
    }

    @Test
    void fullSurveyGoesThroughMiscSoTheConditionsAreRevealedToo() {
        FakeSector sector = new FakeSector();
        FakePlanet full = sector.addPlanet("planet-full");
        FakePlanet partial = sector.addPlanet("planet-partial");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        replicator.handle(surveyMessage("planet-full", "FULL"));
        replicator.handle(surveyMessage("planet-partial", "PRELIMINARY"));

        assertEquals(MarketAPI.SurveyLevel.FULL, full.level);
        assertTrue(full.conditionSurveyed,
                "the enum setter alone leaves a FULL planet's conditions hidden");
        assertEquals(MarketAPI.SurveyLevel.PRELIMINARY, partial.level);
        assertFalse(partial.conditionSurveyed, "below FULL the conditions stay hidden, as in vanilla");
    }

    @Test
    void surveyForAnUnknownOrLevellessEntityIsTolerated() {
        FakeSector sector = new FakeSector();
        sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(surveyMessage("no-such-planet", "FULL")));
        // An entity with no market at all (an objective) and a payload that is not a level.
        assertDoesNotThrow(() -> replicator.handle(surveyMessage("relay-1", "FULL")));
        assertDoesNotThrow(() -> replicator.handle(surveyMessage("relay-1", "TOTALLY_SURVEYED")));
        assertTrue(service.sent.isEmpty());
    }

    @Test
    void ruinsExplorationIsPolledOnBothRolesAndAppliedOnce() {
        FakeSector sector = new FakeSector();
        FakePlanet ruins = sector.addPlanet("planet-ruins");
        ruins.hasRuins = true;
        FakePlanet plain = sector.addPlanet("planet-plain");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        MutableClock clock = new MutableClock(1_000_000L);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), clock);

        replicator.tickWorldDeltas();
        assertTrue(service.sent.isEmpty());

        ruins.marketMemory.set(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG, true);
        clock.advance(CoopCampaignReplicator.SKELETON_POLL_INTERVAL_MILLIS);
        replicator.tickWorldDeltas();

        assertEquals(List.of("true"), payloadsOfKind(service.sent, "RUINS_EXPLORED"));
        assertEquals("planet-ruins",
                CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        assertNull(plain.marketMemory.values.get(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG),
                "a planet with no ruins is not polled at all");

        // The echo dies in the ledger, and the applier is idempotent without it.
        replicator.handle(ruinsMessage("planet-ruins"));
        assertEquals(1, service.sent.size());
        int writes = ruins.marketMemory.writes;
        replicator.worldLedger().clear();
        replicator.handle(ruinsMessage("planet-ruins"));
        assertEquals(writes, ruins.marketMemory.writes);
    }

    @Test
    void ruinsExplorationApplyIsOneWay() {
        FakeSector sector = new FakeSector();
        FakePlanet ruins = sector.addPlanet("planet-ruins");
        ruins.hasRuins = true;
        ruins.marketMemory.set(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG, true);
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        replicator.handle(CoopMessages.worldDelta("session-a", 1L, 0L, "planet-ruins",
                "RUINS_EXPLORED", false, "false", "host"));

        assertTrue(ruins.marketMemory.getBoolean(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG),
                "the flag is never cleared by a remote report");
    }

    @Test
    void hostDecivListenerReportsTheMarketOnce() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), new MutableClock(1_000_000L));
        replicator.registerOn(sector.proxy());

        ColonyDecivListener capture = sector.listenerOfType(ColonyDecivListener.class);
        capture.reportColonyDecivilized(market("market_yama", false, true), false);
        // Vanilla can fire more than once across a session; the ledger keeps the wire clean. The
        // payload's occurrence stamp is the campaign timestamp precisely so that a repeat report of
        // the SAME event reads identically and is still deduped.
        capture.reportColonyDecivilized(market("market_yama", false, true), false);

        assertEquals(1, service.sent.size());
        assertEquals("DECIV", CoopMessages.requiredPayloadString(service.sent.get(0), "kind"));
        assertEquals("market_yama", CoopMessages.requiredPayloadString(service.sent.get(0), "entityId"));
        assertEquals("false#0", CoopMessages.requiredPayloadString(service.sent.get(0), "newStateJson"));
        assertFalse(CoopSkeletonMutationWatcher.decodeDecivFullDestroy(
                CoopMessages.requiredPayloadString(service.sent.get(0), "newStateJson")));
    }

    /**
     * Vanilla re-uses the planet's gen-time market object when a colony is founded, so a colony that
     * decivilizes, is re-founded on the same planet and decivilizes again produces two events under
     * one market id. The set-based ledger key latched the first one for the whole session: the host
     * sent nothing the second time and the guest kept a live colony the host no longer had.
     */
    @Test
    void aSecondDecivOfTheSameMarketMonthsLaterIsReplicated() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), new MutableClock(1_000_000L));
        replicator.registerOn(sector.proxy());

        ColonyDecivListener capture = sector.listenerOfType(ColonyDecivListener.class);
        capture.reportColonyDecivilized(market("market_yama", false, true), false);
        sector.campaignTimestamp = 90L * 24L * 3600L;
        capture.reportColonyDecivilized(market("market_yama", false, true), true);

        assertEquals(2, service.sent.size());
        assertEquals("false#0", CoopMessages.requiredPayloadString(service.sent.get(0), "newStateJson"));
        assertEquals("true#7776000",
                CoopMessages.requiredPayloadString(service.sent.get(1), "newStateJson"));
        assertTrue(CoopSkeletonMutationWatcher.decodeDecivFullDestroy(
                CoopMessages.requiredPayloadString(service.sent.get(1), "newStateJson")));
    }

    // ---- Guest apply ---------------------------------------------------------------------------

    @Test
    void guestAppliesObjectiveOwnershipAndIsIdempotent() {
        FakeSector sector = new FakeSector();
        FakeEntity relay = sector.addObjective("relay-1", "hegemony");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        replicator.handle(ownershipMessage("relay-1", "pirates"));
        assertEquals("pirates", relay.factionId);
        assertEquals(1, relay.setFactionCalls);

        // Ledger-blocked re-apply.
        replicator.handle(ownershipMessage("relay-1", "pirates"));
        assertEquals(1, relay.setFactionCalls);

        // And the applier itself is idempotent even with the ledger out of the way.
        replicator.worldLedger().clear();
        replicator.handle(ownershipMessage("relay-1", "pirates"));
        assertEquals(1, relay.setFactionCalls);

        // A genuine flip back still lands.
        replicator.handle(ownershipMessage("relay-1", "hegemony"));
        assertEquals("hegemony", relay.factionId);
        assertEquals(2, relay.setFactionCalls);
    }

    @Test
    void guestToleratesObjectiveOwnershipForAnUnknownEntity() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(ownershipMessage("no-such-relay", "pirates")));
        assertTrue(service.sent.isEmpty());
    }

    @Test
    void guestAppliesGateStateAndIsIdempotent() {
        FakeSector sector = new FakeSector();
        FakeEntity gate = sector.addGate("gate-1");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        replicator.handle(gateMessage("gate-1", true, true, true));

        assertTrue(gate.memory.getBoolean(GateEntityPlugin.GATE_SCANNED));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.GATES_ACTIVE));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES));

        int writes = sector.memory.writes + gate.memory.writes;
        replicator.worldLedger().clear();
        replicator.handle(gateMessage("gate-1", true, true, true));
        assertEquals(writes, sector.memory.writes + gate.memory.writes,
                "re-applying an already-applied gate state must write nothing");
    }

    @Test
    void guestToleratesGateActivationForAnUnknownGateAndStillTakesTheGlobalFlags() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(gateMessage("no-such-gate", true, true, true)));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.GATES_ACTIVE));
        assertTrue(sector.memory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES));
    }

    @Test
    void guestToleratesDecivForAnUnknownMarket() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), new MutableClock(1_000_000L));

        assertDoesNotThrow(() -> replicator.handle(CoopMessages.worldDelta("session-a", 1L, 0L,
                "no-such-market", "DECIV", false, "false", "host")));
        // Recorded either way, so a later echo cannot re-trigger it.
        assertEquals("false",
                replicator.worldLedger().latestState(CoopWorldDelta.Kind.DECIV, "no-such-market"));
        assertTrue(service.sent.isEmpty());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static List<String> ownershipPayloads(List<CoopMessages.Message> sent) {
        return payloadsOfKind(sent, "OBJECTIVE_OWNERSHIP");
    }

    private static List<String> payloadsOfKind(List<CoopMessages.Message> sent, String kind) {
        List<String> payloads = new ArrayList<>();
        for (CoopMessages.Message message : sent) {
            if (kind.equals(CoopMessages.requiredPayloadString(message, "kind"))) {
                payloads.add(CoopMessages.requiredPayloadString(message, "newStateJson"));
            }
        }
        return payloads;
    }

    private static CoopMessages.Message surveyMessage(String entityId, String level) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, entityId, "SURVEY", false, level, "host");
    }

    private static CoopMessages.Message ruinsMessage(String entityId) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, entityId, "RUINS_EXPLORED", false,
                "true", "host");
    }

    private static CoopMessages.Message ownershipMessage(String entityId, String factionId) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, entityId, "OBJECTIVE_OWNERSHIP",
                false, factionId, "host");
    }

    private static CoopMessages.Message gateMessage(String entityId, boolean scanned,
                                                    boolean gatesActive, boolean canUseGates) {
        return CoopMessages.worldDelta("session-a", 1L, 0L, entityId, "GATE_ACTIVATED", false,
                CoopSkeletonMutationWatcher.encodeGateState(scanned, gatesActive, canUseGates), "host");
    }

    private static CoopSessionState activeHostSession() {
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123L, "seed-a", "fingerprint-a");
        return session;
    }

    private static CoopSessionState activeGuestSession() {
        CoopSessionState session = new CoopSessionState(new SequencedIds("guest-player"));
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123L, "seed-a", "fingerprint-a");
        return session;
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    /** Minimal in-memory {@code MemoryAPI} that also counts writes, for the idempotency assertions. */
    private static final class FakeMemory {
        private final Map<String, Object> values = new HashMap<>();
        private int writes;

        void set(String key, Object value) {
            values.put(key, value);
            writes++;
        }

        boolean getBoolean(String key) {
            return Boolean.TRUE.equals(values.get(key));
        }

        MemoryAPI proxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            set((String) args[0], args[1]);
                            yield null;
                        }
                        case "unset" -> {
                            values.remove((String) args[0]);
                            yield null;
                        }
                        case "get" -> values.get((String) args[0]);
                        case "contains" -> values.containsKey((String) args[0]);
                        case "getBoolean" -> getBoolean((String) args[0]);
                        case "toString" -> "Memory" + values;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /**
     * A planet with a market, which is all the survey path touches: the level enum, the conditions
     * {@code Misc.setFullySurveyed} flips, and the market memory that carries {@code $ruinsExplored}.
     */
    private static final class FakePlanet {
        private final String id;
        private final boolean star;
        private final FakeMemory marketMemory = new FakeMemory();
        private MarketAPI.SurveyLevel level = MarketAPI.SurveyLevel.NONE;
        private boolean hasRuins;
        private int setSurveyLevelCalls;
        private boolean conditionSurveyed;

        private FakePlanet(String id, boolean star) {
            this.id = id;
            this.star = star;
        }

        private MarketConditionAPI condition() {
            return (MarketConditionAPI) Proxy.newProxyInstance(
                    MarketConditionAPI.class.getClassLoader(),
                    new Class<?>[]{MarketConditionAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setSurveyed" -> {
                            conditionSurveyed = (Boolean) args[0];
                            yield null;
                        }
                        case "isSurveyed" -> conditionSurveyed;
                        case "toString" -> "Condition";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        MarketAPI market() {
            MemoryAPI memoryProxy = marketMemory.proxy();
            return (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id + "_market";
                        case "getSurveyLevel" -> level;
                        case "setSurveyLevel" -> {
                            level = (MarketAPI.SurveyLevel) args[0];
                            setSurveyLevelCalls++;
                            yield null;
                        }
                        // Misc.hasRuins asks for the four ruins conditions; nothing else here does.
                        case "hasCondition" -> hasRuins;
                        case "getConditions" -> List.of(condition());
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
                        case "toString" -> "Market[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        PlanetAPI proxy() {
            MarketAPI marketProxy = market();
            return (PlanetAPI) Proxy.newProxyInstance(
                    PlanetAPI.class.getClassLoader(),
                    new Class<?>[]{PlanetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "isStar" -> star;
                        case "getMarket" -> marketProxy;
                        case "toString" -> "Planet[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class FakeEntity {
        private final String id;
        private final FakeMemory memory = new FakeMemory();
        private String factionId;
        private int setFactionCalls;

        private FakeEntity(String id, String factionId) {
            this.id = id;
            this.factionId = factionId;
        }

        SectorEntityToken proxy() {
            MemoryAPI memoryProxy = memory.proxy();
            return (SectorEntityToken) Proxy.newProxyInstance(
                    SectorEntityToken.class.getClassLoader(),
                    new Class<?>[]{SectorEntityToken.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getFaction" -> factionId == null ? null : faction(factionId);
                        case "setFaction" -> {
                            factionId = (String) args[0];
                            setFactionCalls++;
                            yield null;
                        }
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
                        case "toString" -> "Entity[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /** One location holding tagged objectives and gates, plus sector memory and a listener manager. */
    private static final class FakeSector {
        private final Map<String, FakeEntity> objectives = new LinkedHashMap<>();
        private final Map<String, FakeEntity> gates = new LinkedHashMap<>();
        private final Map<String, FakePlanet> planets = new LinkedHashMap<>();
        private final FakeMemory memory = new FakeMemory();
        private final List<Object> listeners = new ArrayList<>();
        private long campaignTimestamp;
        private SectorAPI cached;

        FakeEntity addObjective(String id, String factionId) {
            FakeEntity entity = new FakeEntity(id, factionId);
            objectives.put(id, entity);
            return entity;
        }

        FakeEntity addGate(String id) {
            FakeEntity entity = new FakeEntity(id, null);
            gates.put(id, entity);
            return entity;
        }

        FakePlanet addPlanet(String id) {
            FakePlanet planet = new FakePlanet(id, false);
            planets.put(id, planet);
            return planet;
        }

        FakePlanet addStar(String id) {
            FakePlanet star = new FakePlanet(id, true);
            planets.put(id, star);
            return star;
        }

        private List<PlanetAPI> planetProxies() {
            List<PlanetAPI> tokens = new ArrayList<>();
            for (FakePlanet planet : planets.values()) {
                tokens.add(planet.proxy());
            }
            return tokens;
        }

        @SuppressWarnings("unchecked")
        <T> T listenerOfType(Class<T> type) {
            for (Object listener : listeners) {
                if (type.isInstance(listener)) {
                    return (T) listener;
                }
            }
            return null;
        }

        private List<SectorEntityToken> tagged(String tag) {
            Map<String, FakeEntity> source = switch (tag) {
                case "objective" -> objectives;
                case "gate" -> gates;
                default -> Map.of();
            };
            List<SectorEntityToken> tokens = new ArrayList<>();
            for (FakeEntity entity : source.values()) {
                tokens.add(entity.proxy());
            }
            return tokens;
        }

        private SectorEntityToken byId(String id) {
            FakePlanet planet = planets.get(id);
            if (planet != null) {
                return planet.proxy();
            }
            FakeEntity entity = objectives.get(id);
            if (entity == null) {
                entity = gates.get(id);
            }
            return entity == null ? null : entity.proxy();
        }

        SectorAPI proxy() {
            if (cached != null) {
                return cached;
            }
            LocationAPI location = (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(),
                    new Class<?>[]{LocationAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> "loc-1";
                        case "getEntitiesWithTag" -> tagged((String) args[0]);
                        case "getPlanets" -> planetProxies();
                        case "getAllEntities" -> List.<SectorEntityToken>of();
                        case "toString" -> "Location";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            ListenerManagerAPI listenerManager = (ListenerManagerAPI) Proxy.newProxyInstance(
                    ListenerManagerAPI.class.getClassLoader(),
                    new Class<?>[]{ListenerManagerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addListener" -> {
                            listeners.add(args[0]);
                            yield null;
                        }
                        case "removeListener" -> {
                            listeners.remove(args[0]);
                            yield null;
                        }
                        case "toString" -> "ListenerManager";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> null;
                        case "toString" -> "Economy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            CampaignClockAPI clock = (CampaignClockAPI) Proxy.newProxyInstance(
                    CampaignClockAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignClockAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getTimestamp" -> campaignTimestamp;
                        case "toString" -> "Clock";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            MemoryAPI memoryProxy = memory.proxy();
            cached = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAllLocations" -> List.of(location);
                        case "getHyperspace" -> location;
                        case "getClock" -> clock;
                        case "getEntityById" -> byId((String) args[0]);
                        case "getMemoryWithoutUpdate" -> memoryProxy;
                        case "getListenerManager" -> listenerManager;
                        case "getEconomy" -> economy;
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    private static FactionAPI faction(String id) {
        return (FactionAPI) Proxy.newProxyInstance(
                FactionAPI.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "toString" -> "Faction[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static com.fs.starfarer.api.campaign.econ.MarketAPI market(
            String id, boolean decivilized, boolean hasPrimary) {
        return (com.fs.starfarer.api.campaign.econ.MarketAPI) Proxy.newProxyInstance(
                com.fs.starfarer.api.campaign.econ.MarketAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.campaign.econ.MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "hasCondition" -> decivilized;
                    case "getPrimaryEntity" -> hasPrimary ? new FakeEntity(id + "_p", null).proxy() : null;
                    case "toString" -> "Market[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
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
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class MutableClock implements java.util.function.LongSupplier {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public long getAsLong() {
            return millis;
        }
    }

    private static final class RecordingNetService extends CoopNetService {
        private final CoopConnectionRole role;
        private final List<CoopMessages.Message> sent = new ArrayList<>();

        private RecordingNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(CoopMessages.Message message) {
            sent.add(message);
        }
    }

    private static final class SequencedIds implements java.util.function.Supplier<String> {
        private final List<String> ids;
        private int index;

        private SequencedIds(String... ids) {
            this.ids = List.of(ids);
        }

        @Override
        public String get() {
            return ids.get(index++);
        }
    }
}
