package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.MarketConditionSpecAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 milestone 2: the colony lifecycle codec, its latest-wins ledger, the deferred capture, and
 * the market-build recipe. The engine is stood up as interface proxies (no mocking framework in this
 * build), which is enough because the recipe is a sequence of public setters.
 */
class CoopColonySyncTest {

    /**
     * Proxying {@code Industry} makes the JDK initialize the proxy class, which resolves every type in
     * its signatures — including {@code MarketCMD.RaidDangerLevel}, whose static init asks the
     * settings for highlight colors ({@code MarketCMD.java:102-107}). Without a stub the proxy class
     * itself fails to initialize, so this is a prerequisite, not decoration.
     */
    @BeforeEach
    void stubSettings() {
        Global.setSettings(fakeSettings());
    }

    @AfterEach
    void clearGlobals() {
        Global.setSector(null);
        Global.setSettings(null);
    }

    private static SettingsAPI fakeSettings() {
        return (SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColor" -> Color.WHITE;
                    case "toString" -> "Settings";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    // ---- Codec ---------------------------------------------------------------------------------

    @Test
    void aFoundedColonyRoundTrips() {
        CoopColonySync.Event event = foundedEvent("host-player:1");

        assertEquals(event, CoopColonySync.decode(event.encode()));
    }

    /**
     * The defect this test exists for: vanilla colonization auto-queues a spaceport, so the founding
     * payload has to carry a queue. Order is the build order and has to survive.
     */
    @Test
    void theFoundingTimeConstructionQueueRoundTripsInOrder() {
        CoopColonySync.Event event = withQueue(foundedEvent("host-player:1"),
                List.of(new CoopColonyManagement.QueueItem("spaceport", 50_000),
                        new CoopColonyManagement.QueueItem("mining", 60_000),
                        new CoopColonyManagement.QueueItem("farming", 0)));

        CoopColonySync.Event decoded = CoopColonySync.decode(event.encode());

        assertEquals(event, decoded);
        assertEquals(List.of("spaceport", "mining", "farming"),
                decoded.queue().stream().map(CoopColonyManagement.QueueItem::industryId).toList());
        assertEquals(60_000, decoded.queue().get(1).cost());
    }

    /** Zero {@code Q} lines is a legal payload, not a malformed one. */
    @Test
    void aFoundingWithAnEmptyQueueRoundTrips() {
        CoopColonySync.Event event = withQueue(foundedEvent("host-player:2"), List.of());

        CoopColonySync.Event decoded = CoopColonySync.decode(event.encode());

        assertEquals(event, decoded);
        assertTrue(decoded.queue().isEmpty());
    }

    @Test
    void queueIndustryIdsCarryingDelimiterCharactersRoundTripExactly() {
        CoopColonySync.Event event = withQueue(foundedEvent("host-player:3"),
                List.of(new CoopColonyManagement.QueueItem("space|port\nII", 1),
                        new CoopColonyManagement.QueueItem("min\\ing", -1)));

        CoopColonySync.Event decoded = CoopColonySync.decode(event.encode());

        assertEquals(event, decoded);
        assertEquals("space|port\nII", decoded.queue().get(0).industryId());
        assertEquals("min\\ing", decoded.queue().get(1).industryId());
    }

    @Test
    void anAbandonedColonyRoundTrips() {
        CoopColonySync.Event event = CoopColonySync.Event.abandoned(
                "guest-player:3", "guest-player", "planet_eos", "market_planet_eos");

        CoopColonySync.Event decoded = CoopColonySync.decode(event.encode());

        assertEquals(event, decoded);
        assertEquals(CoopColonySync.Kind.ABANDONED, decoded.kind());
        assertTrue(decoded.conditions().isEmpty());
        assertTrue(decoded.industries().isEmpty());
        assertTrue(decoded.submarkets().isEmpty());
    }

    @Test
    void idsAndNamesCarryingDelimiterCharactersRoundTripExactly() {
        CoopColonySync.Event event = new CoopColonySync.Event(
                "pipe|player\\:1", CoopColonySync.Kind.FOUNDED, "planet|eos\nII",
                "market\\_planet|eos", "acting\\player", "New | Hope\nStation", "player", 3,
                true, "FULL", true,
                List.of(new CoopColonySync.ConditionState("cond|ition", true),
                        new CoopColonySync.ConditionState("hot\\", false)),
                List.of("ind|ustry", "pop\nulation"),
                List.of("sub|market"),
                List.of(new CoopColonyManagement.QueueItem("queued|industry", 7)));

        CoopColonySync.Event decoded = CoopColonySync.decode(event.encode());

        assertEquals(event, decoded);
        assertEquals("New | Hope\nStation", decoded.name());
        assertEquals("planet|eos\nII", decoded.planetId());
        assertEquals(List.of("ind|ustry", "pop\nulation"), decoded.industries());
    }

    /** The colony name is player-typed free text; it has to survive the wire byte for byte. */
    @Test
    void aUnicodeColonyNameSurvivesTheWire() {
        String name = "Новая Надежда – 星海";
        CoopColonySync.Event event = new CoopColonySync.Event("host-player:9",
                CoopColonySync.Kind.FOUNDED, "planet_eos", "market_planet_eos", "host-player",
                name, "player", 4, false, "FULL", true, List.of(), List.of(), List.of(), List.of());

        assertEquals(name, CoopColonySync.decode(event.encode()).name());
    }

    @Test
    void decodeRejectsMalformedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> CoopColonySync.decode(""));
        assertThrows(IllegalArgumentException.class, () -> CoopColonySync.decode("H|a|b"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopColonySync.decode(foundedEvent("a:1").encode() + "\nX|nope"));
        assertThrows(IllegalArgumentException.class, () -> CoopColonySync.decode(
                foundedEvent("a:1").encode().replace("FOUNDED", "RENAMED")));
        assertThrows(IllegalArgumentException.class, () -> CoopColonySync.decode(
                foundedEvent("a:1").encode() + "\nI|too|many|fields"));
    }

    // ---- Ledger --------------------------------------------------------------------------------

    /**
     * The milestone's whole reason for not reusing the raid ledger's once-only semantics: a colony is
     * a value that oscillates, and every genuine transition has to land.
     */
    @Test
    void foundAbandonAndRefoundAllApplyWhileEveryEchoDies() {
        CoopColonySync.Ledger ledger = new CoopColonySync.Ledger();
        CoopColonySync.Event founded = foundedEvent("guest-player:1");
        CoopColonySync.Event abandoned = CoopColonySync.Event.abandoned(
                "guest-player:2", "guest-player", "planet_eos", "market_planet_eos");
        CoopColonySync.Event refounded = foundedEvent("guest-player:3");

        assertTrue(ledger.apply(founded), "first founding applies");
        assertFalse(ledger.apply(founded), "the host's verbatim echo must not re-apply");
        assertTrue(ledger.apply(abandoned), "abandonment is a genuine transition");
        assertFalse(ledger.apply(abandoned));
        assertTrue(ledger.apply(refounded), "re-founding the same planet applies again");
        assertFalse(ledger.apply(refounded));

        assertEquals(3, ledger.size());
        assertEquals(CoopColonySync.Kind.FOUNDED, ledger.latestKind("planet_eos"));
        assertTrue(ledger.isApplied("guest-player:2"));
    }

    /** A fresh event id that reports a state the planet is already in is not a transition. */
    @Test
    void aRedundantTransitionIsANoOpEvenWithANewEventId() {
        CoopColonySync.Ledger ledger = new CoopColonySync.Ledger();

        assertTrue(ledger.apply(foundedEvent("host-player:1")));
        assertFalse(ledger.apply(foundedEvent("host-player:2")),
                "a second FOUNDED for an already-founded planet must not rebuild it");
        assertEquals(2, ledger.size(), "but it is still remembered, so its own echo dies too");
    }

    @Test
    void differentPlanetsAreTrackedIndependentlyAndClearWipesEverything() {
        CoopColonySync.Ledger ledger = new CoopColonySync.Ledger();
        ledger.apply(foundedEvent("host-player:1"));
        ledger.apply(new CoopColonySync.Event("host-player:2", CoopColonySync.Kind.FOUNDED,
                "planet_yama", "market_planet_yama", "host-player", "Yama", "player", 3, false,
                "FULL", true, List.of(), List.of(), List.of(), List.of()));

        assertEquals(CoopColonySync.Kind.FOUNDED, ledger.latestKind("planet_eos"));
        assertEquals(CoopColonySync.Kind.FOUNDED, ledger.latestKind("planet_yama"));

        ledger.clear();

        assertEquals(0, ledger.size());
        assertNull(ledger.latestKind("planet_eos"));
    }

    // ---- Capture -------------------------------------------------------------------------------

    @Test
    void aFoundedColonyIsReadOffTheLiveMarket() {
        FakeMarket market = colonizedMarket();

        CoopColonySync.Event event = CoopColonySync.captureFounded(
                "host-player:1", "host-player", "planet_eos", market.proxy());

        assertEquals(CoopColonySync.Kind.FOUNDED, event.kind());
        assertEquals("planet_eos", event.planetId());
        assertEquals("market_planet_eos", event.marketId());
        assertEquals("New Hope", event.name());
        assertEquals("player", event.factionId());
        assertEquals(3, event.size());
        assertEquals("FULL", event.surveyLevel());
        assertTrue(event.freePort());
        assertTrue(event.storageUnlocked(), "a storage submarket means founding paid to unlock it");
        assertEquals(List.of("population"), event.industries());
        assertEquals(List.of(new CoopColonyManagement.QueueItem("spaceport", 50_000)), event.queue(),
                "vanilla's auto-queued spaceport is read in the same pass as the industries");
        assertEquals(List.of("local_resources", "storage"), event.submarkets());
        assertEquals(List.of("habitable", "population_3", "decivilized_subpop"),
                event.conditions().stream().map(CoopColonySync.ConditionState::conditionId).toList());
        assertTrue(event.conditions().get(0).surveyed());
    }

    /**
     * Vanilla reports colonization from closed code with only a {@code PlanetAPI} in hand, so the
     * capture waits a frame and reads the market once it is definitely a player colony.
     */
    @Test
    void colonizationIsCapturedOnTheDrainNotTheReport() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());
        RecordingSink sink = new RecordingSink();
        CoopColonySync.ColonizationCapture capture = new CoopColonySync.ColonizationCapture(sink);

        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));

        assertTrue(sink.captured.isEmpty(), "nothing is read while the engine may still be building");
        assertEquals(1, capture.pendingCount());

        // Still a bare planet-condition market: the drain waits rather than shipping an empty colony.
        capture.drainPending();
        assertTrue(sink.captured.isEmpty());
        assertEquals(1, capture.pendingCount());

        market.becomeColony();
        capture.drainPending();

        assertEquals(1, sink.captured.size());
        assertEquals(0, capture.pendingCount());
        CoopColonySync.Event event = sink.captured.get(0);
        assertEquals(CoopColonySync.Kind.FOUNDED, event.kind());
        assertEquals("planet_eos", event.planetId());
        assertEquals("host-player:1", event.eventId());
    }

    @Test
    void aColonizationThatNeverCompletesIsGivenUpOnRatherThanRetriedForever() {
        FakeSector sector = new FakeSector();
        sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());
        RecordingSink sink = new RecordingSink();
        CoopColonySync.ColonizationCapture capture = new CoopColonySync.ColonizationCapture(sink);

        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));
        for (int i = 0; i < CoopColonySync.MAX_DRAIN_ATTEMPTS; i++) {
            capture.drainPending();
        }

        assertTrue(sink.captured.isEmpty());
        assertEquals(0, capture.pendingCount());
    }

    /**
     * The drain removes the pending entry <em>before</em> it reads the market and speaks, so a blow-up
     * inside the emit must not remove it a second time: that throws {@code IllegalStateException} off
     * the iterator, which escapes the drain, buries the real cause and strands every planet behind it
     * for a frame.
     */
    @Test
    void aColonizationThatBlowsUpWhileBeingEmittedDoesNotStrandTheRest() {
        FakeSector sector = new FakeSector();
        FakeMarket first = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        FakeMarket second = sector.addPlanetWithMarket("planet_ithaca", "market_planet_ithaca");
        Global.setSector(sector.proxy());
        RecordingSink sink = new RecordingSink();
        sink.throwOnPlanet = "planet_eos";
        CoopColonySync.ColonizationCapture capture = new CoopColonySync.ColonizationCapture(sink);

        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));
        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_ithaca"));
        first.becomeColony();
        second.becomeColony();

        assertDoesNotThrow(capture::drainPending);

        assertEquals(0, capture.pendingCount(), "both planets are done with in one pass");
        assertEquals(List.of("planet_ithaca"),
                sink.captured.stream().map(CoopColonySync.Event::planetId).toList(),
                "the planet behind the thrower is still reported");
    }

    @Test
    void abandonmentIsCapturedInlineAndCancelsAPendingColonization() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());
        RecordingSink sink = new RecordingSink();
        CoopColonySync.ColonizationCapture capture = new CoopColonySync.ColonizationCapture(sink);
        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));

        capture.reportPlayerAbandonedColony(market.proxy());

        assertEquals(0, capture.pendingCount(), "the pending colonization is moot and must not fire");
        assertEquals(1, sink.captured.size());
        assertEquals(CoopColonySync.Kind.ABANDONED, sink.captured.get(0).kind());
        assertEquals("planet_eos", sink.captured.get(0).planetId());
        assertEquals("market_planet_eos", sink.captured.get(0).marketId());
    }

    @Test
    void captureIsSkippedEntirelyWhileTheSinkSaysNo() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.becomeColony();
        Global.setSector(sector.proxy());
        RecordingSink sink = new RecordingSink();
        sink.capturing = false;
        CoopColonySync.ColonizationCapture capture = new CoopColonySync.ColonizationCapture(sink);

        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));
        capture.reportPlayerAbandonedColony(market.proxy());
        capture.drainPending();

        assertTrue(sink.captured.isEmpty());
        assertEquals(0, capture.pendingCount());
    }

    @Test
    void resetDropsPendingColonizationsAndRestartsTheIdCounter() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());
        RecordingSink sink = new RecordingSink();
        CoopColonySync.ColonizationCapture capture = new CoopColonySync.ColonizationCapture(sink);
        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));

        capture.reset();
        market.becomeColony();
        capture.drainPending();

        assertTrue(sink.captured.isEmpty());

        capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));
        capture.drainPending();
        assertEquals("host-player:1", sink.captured.get(0).eventId());
    }

    // ---- Apply ---------------------------------------------------------------------------------

    @Test
    void applyingAFoundedColonyBuildsTheSameMarketOnAPlainPlanet() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertFalse(market.planetConditionMarketOnly, "the flag has to clear before setSize works");
        assertTrue(market.playerOwned);
        assertTrue(market.freePort);
        assertEquals("New Hope", market.name);
        assertEquals("player", market.factionId);
        assertEquals(3, market.size);
        assertEquals(MarketAPI.SurveyLevel.FULL, market.surveyLevel);
        assertEquals(List.of("population"), List.copyOf(market.industries));
        assertEquals(List.of("spaceport"), queueIds(market),
                "the mirror has to start the auto-queued spaceport itself");
        assertEquals(50_000, market.queue.getItems().get(0).cost);
        assertEquals(List.of("local_resources", "storage"), List.copyOf(market.submarkets.keySet()));
        assertEquals(List.of("habitable", "population_3", "decivilized_subpop"),
                market.conditions.stream().map(FakeCondition::id).toList());
        assertTrue(market.inEconomy, "a colony has to be registered with the economy to trade");
        assertEquals("player", sector.entityFactions.get("planet_eos"));
        assertTrue(market.reappliedIndustries);
    }

    /**
     * The condition set is applied absolutely, which is the only shape that can express colonization's
     * {@code DECIVILIZED} &rarr; {@code DECIVILIZED_SUBPOP} swap.
     */
    @Test
    void applyingAFoundedColonySwapsConditionsAndAlignsSurveyedFlags() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.conditions.add(new FakeCondition("habitable", false));
        market.conditions.add(new FakeCondition("decivilized", false));
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertEquals(List.of("habitable", "population_3", "decivilized_subpop"),
                market.conditions.stream().map(FakeCondition::id).toList(),
                "decivilized is dropped, decivilized_subpop is added in its place");
        assertTrue(market.conditions.stream().allMatch(FakeCondition::surveyed),
                "colonization marks every condition surveyed");
        assertTrue(market.reappliedConditions);
    }

    /** Re-applying the same founded event must not double up anything. */
    @Test
    void applyingAFoundedColonyTwiceIsIdempotent() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));
        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertEquals(List.of("population"), List.copyOf(market.industries));
        assertEquals(2, market.submarkets.size());
        assertEquals(3, market.conditions.size());
        assertEquals(List.of("spaceport"), queueIds(market), "the queue must not be appended to twice");
        assertEquals(1, market.addMarketCalls, "already in the economy: do not add it twice");
    }

    /**
     * The queue reconcile is latest-wins, not additive: a mirror that already holds a stale queue —
     * a re-founding on the same planet, a management report that raced ahead — ends up with exactly
     * the reported list, in the reported order.
     */
    @Test
    void applyingAFoundedColonyReconcilesAQueueThatIsAlreadyPopulated() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.queue.addToEnd("spaceport", 50_000);
        market.queue.addToEnd("mining", 60_000);
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertEquals(List.of("spaceport"), queueIds(market),
                "the reported queue replaces what was there rather than adding to it");
    }

    /**
     * Defect 2 as reported live: the guest was seen "constructing" a population industry the host had
     * finished at founding. An I-line is a finished industry — the queue is what carries anything still
     * being built — and a freshly added one already is finished ({@code Market.addIndustry} appends the
     * plugin and calls {@code apply()}), so nothing on this path may start a build.
     */
    @Test
    void aFoundingLeavesItsIndustriesFinishedAndOnlyItsQueueQueued() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertFalse(market.industryState("population").building,
                "an I-line industry is finished, never under construction");
        assertEquals(List.of("spaceport"), queueIds(market),
                "and only the Q-lines stay queued for the mirror's own engine to build");
    }

    /** The backstop: an industry this engine already held mid-build is finished, not left hanging. */
    @Test
    void aFoundingFinishesAnIndustryTheMirrorWasAlreadyBuilding() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.industries.add("population");
        market.industryState("population").building = true;
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertFalse(market.industryState("population").building);
        assertEquals(1, market.industryState("population").finishCalls);
    }

    /**
     * And the vanilla quirk that made the live report look like a rebuild in the first place: a colony
     * below its maximum size reports itself upgrading forever. Finishing that non-build would fire
     * vanilla's "construction completed" message and pop the construction queue early.
     */
    @Test
    void aFoundingDoesNotEndVanillasColonyGrowth() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.industries.add("population");
        market.industryState("population").growing = true;
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(foundedEvent("guest-player:1"));

        assertEquals(0, market.industryState("population").finishCalls);
    }

    /** A colony really founded with an empty queue must mirror as empty, not as "unset". */
    @Test
    void applyingAFoundedColonyWithNoQueueClearsTheMirrorsQueue() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.queue.addToEnd("spaceport", 50_000);
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(withQueue(foundedEvent("guest-player:1"), List.of()));

        assertTrue(market.queue.getItems().isEmpty());
    }

    /**
     * The inverse recipe is vanilla's own {@code DecivTracker.removeColony(market, false)} — the exact
     * call {@code AbandonMarketPluginImpl.abandonConfirmed} makes — so this asserts vanilla's end
     * state rather than a hand-written guess at it.
     */
    @Test
    void applyingAnAbandonmentRunsVanillaTeardownOnTheColony() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.becomeColony();
        market.conditions.add(new FakeCondition("habitable", true, false));
        market.conditions.add(new FakeCondition("population_3", true, true));
        market.industries.add("population");
        market.industries.add("spaceport");
        market.submarkets.put("storage", submarketProxy("storage"));
        market.submarkets.put("local_resources", submarketProxy("local_resources"));
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(CoopColonySync.Event.abandoned(
                "guest-player:2", "guest-player", "planet_eos", "market_planet_eos"));

        assertTrue(market.adminCleared);
        assertTrue(market.planetConditionMarketOnly);
        assertFalse(market.playerOwned);
        assertEquals("neutral", market.factionId);
        assertEquals("neutral", sector.entityFactions.get("planet_eos"));
        assertTrue(market.industries.isEmpty(), "every industry is shut down");
        assertTrue(market.submarkets.isEmpty(), "submarkets go, storage contents with them");
        assertEquals(List.of("habitable"), market.conditions.stream().map(FakeCondition::id).toList(),
                "only isDecivRemove conditions are stripped; planet conditions stay");
        // Vanilla writes setSize(1) after it has already flipped the planet-condition flag back on,
        // at which point the engine's market reports 1 regardless -- so read it the way the game does.
        assertEquals(1, market.proxy().getSize());
        assertFalse(market.inEconomy, "an abandoned colony leaves the economy");
    }

    /** An abandonment for a market that is already torn down must not strip its planet conditions. */
    @Test
    void applyingAnAbandonmentToAPlanetConditionMarketDoesNothing() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        market.conditions.add(new FakeCondition("habitable", true));
        Global.setSector(sector.proxy());

        CoopColonySync.applyToEngine(CoopColonySync.Event.abandoned(
                "guest-player:2", "guest-player", "planet_eos", "market_planet_eos"));

        assertFalse(market.adminCleared);
        assertEquals(1, market.conditions.size());
    }

    @Test
    void anEventForAPlanetThisEngineDoesNotHaveIsDroppedNotThrown() {
        FakeSector sector = new FakeSector();
        Global.setSector(sector.proxy());

        assertDoesNotThrow(() -> CoopColonySync.applyToEngine(foundedEvent("guest-player:1")));
        assertDoesNotThrow(() -> CoopColonySync.applyToEngine(CoopColonySync.Event.abandoned(
                "guest-player:2", "guest-player", "planet_missing", "market_missing")));
    }

    @Test
    void anEventWithNoSectorAtAllIsDroppedNotThrown() {
        assertDoesNotThrow(() -> CoopColonySync.applyToEngine(foundedEvent("guest-player:1")));
    }

    /**
     * The market is outside the economy on both sides of the lifecycle, so the planet is the primary
     * key; the market id is only a fallback for entities that do not carry their market.
     */
    @Test
    void theMarketResolvesThroughThePlanetFirstAndTheEconomySecond() {
        FakeSector sector = new FakeSector();
        FakeMarket viaPlanet = sector.addPlanetWithMarket("planet_eos", "market_planet_eos");
        FakeMarket viaEconomy = sector.addEconomyOnlyMarket("station_market");
        Global.setSector(sector.proxy());

        assertEquals(viaPlanet.proxy(),
                CoopColonySync.resolveMarket("planet_eos", "station_market"));
        assertEquals(viaEconomy.proxy(),
                CoopColonySync.resolveMarket("planet_missing", "station_market"));
        assertNull(CoopColonySync.resolveMarket("planet_missing", "market_missing"));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static CoopColonySync.Event foundedEvent(String eventId) {
        return new CoopColonySync.Event(eventId, CoopColonySync.Kind.FOUNDED, "planet_eos",
                "market_planet_eos", "guest-player", "New Hope", "player", 3, true, "FULL", true,
                List.of(new CoopColonySync.ConditionState("habitable", true),
                        new CoopColonySync.ConditionState("population_3", true),
                        new CoopColonySync.ConditionState("decivilized_subpop", true)),
                List.of("population"),
                List.of("local_resources", "storage"),
                List.of(new CoopColonyManagement.QueueItem("spaceport", 50_000)));
    }

    private static CoopColonySync.Event withQueue(CoopColonySync.Event event,
                                                  List<CoopColonyManagement.QueueItem> queue) {
        return new CoopColonySync.Event(event.eventId(), event.kind(), event.planetId(),
                event.marketId(), event.actingPlayerId(), event.name(), event.factionId(),
                event.size(), event.freePort(), event.surveyLevel(), event.storageUnlocked(),
                event.conditions(), event.industries(), event.submarkets(), queue);
    }

    private static List<String> queueIds(FakeMarket market) {
        List<String> ids = new ArrayList<>();
        for (ConstructionQueue.ConstructionQueueItem item : market.queue.getItems()) {
            ids.add(item.id);
        }
        return ids;
    }

    private static FakeMarket colonizedMarket() {
        FakeMarket market = new FakeMarket("market_planet_eos");
        market.conditions.add(new FakeCondition("habitable", true));
        market.conditions.add(new FakeCondition("population_3", true));
        market.conditions.add(new FakeCondition("decivilized_subpop", true));
        market.industries.add("population");
        market.submarkets.put("local_resources", submarketProxy("local_resources"));
        market.submarkets.put("storage", submarketProxy("storage"));
        // Vanilla colonization queues this one itself; the player never orders it.
        market.queue.addToEnd("spaceport", 50_000);
        market.becomeColony();
        market.name = "New Hope";
        market.freePort = true;
        market.surveyLevel = MarketAPI.SurveyLevel.FULL;
        return market;
    }

    private static final class RecordingSink implements CoopColonySync.Sink {
        private final List<CoopColonySync.Event> captured = new ArrayList<>();
        private boolean capturing = true;
        /** Test seam: stands in for any engine getter that blows up mid-emit. */
        private String throwOnPlanet;

        @Override
        public boolean shouldCaptureColonyLifecycle() {
            return capturing;
        }

        @Override
        public String colonyActingPlayerId() {
            return "host-player";
        }

        @Override
        public void onColonyLifecycleCaptured(CoopColonySync.Event event) {
            if (event.planetId().equals(throwOnPlanet)) {
                throw new IllegalArgumentException("engine blew up reading " + throwOnPlanet);
            }
            captured.add(event);
        }
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    private static final class FakeCondition {
        private final String id;
        private final boolean decivRemove;
        private boolean surveyed;
        private MarketConditionAPI cached;

        private FakeCondition(String id, boolean surveyed) {
            this(id, surveyed, true);
        }

        private FakeCondition(String id, boolean surveyed, boolean decivRemove) {
            this.id = id;
            this.surveyed = surveyed;
            this.decivRemove = decivRemove;
        }

        String id() {
            return id;
        }

        boolean surveyed() {
            return surveyed;
        }

        MarketConditionAPI proxy() {
            if (cached != null) {
                return cached;
            }
            MarketConditionSpecAPI spec = (MarketConditionSpecAPI) Proxy.newProxyInstance(
                    MarketConditionSpecAPI.class.getClassLoader(),
                    new Class<?>[]{MarketConditionSpecAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "isDecivRemove" -> decivRemove;
                        case "toString" -> "ConditionSpec[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            cached = (MarketConditionAPI) Proxy.newProxyInstance(
                    MarketConditionAPI.class.getClassLoader(),
                    new Class<?>[]{MarketConditionAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId", "getIdForPluginModifications" -> id;
                        case "getSpec" -> spec;
                        case "isSurveyed" -> surveyed;
                        case "setSurveyed" -> {
                            surveyed = (Boolean) args[0];
                            yield null;
                        }
                        case "toString" -> "Condition[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    /**
     * A market that behaves like the engine's on the one point that matters to the recipe: while
     * {@code planetConditionMarketOnly} is set, {@code getSize} reports 1 and {@code setSize} is
     * ignored ({@code PlanetConditionMarket} does exactly that).
     */
    private static final class FakeMarket {
        private final String id;
        private String name;
        private String factionId = "neutral";
        private int size = 1;
        private boolean planetConditionMarketOnly = true;
        private boolean playerOwned;
        private boolean freePort;
        private boolean inEconomy;
        private boolean adminCleared;
        private boolean reappliedIndustries;
        private boolean reappliedConditions;
        private int addMarketCalls;
        private final List<FakeCondition> conditions = new ArrayList<>();
        private final List<String> industries = new ArrayList<>();
        private final Map<String, FakeIndustry> industryStates = new LinkedHashMap<>();
        private final Map<String, SubmarketAPI> submarkets = new LinkedHashMap<>();
        private final ConstructionQueue queue = new ConstructionQueue();
        private SectorEntityToken primary;
        private MarketAPI cached;
        private final PopulationComposition population = new PopulationComposition();
        private final CommDirectoryAPI commDirectory = (CommDirectoryAPI) Proxy.newProxyInstance(
                CommDirectoryAPI.class.getClassLoader(),
                new Class<?>[]{CommDirectoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "CommDirectory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        private final MemoryAPI memory = (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "Memory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });

        private FakeMarket(String id) {
            this.id = id;
            this.name = id;
        }

        FakeIndustry industryState(String industryId) {
            return industryStates.computeIfAbsent(industryId, FakeIndustry::new);
        }

        void becomeColony() {
            planetConditionMarketOnly = false;
            playerOwned = true;
            inEconomy = true;
            factionId = "player";
            size = 3;
        }

        MarketAPI proxy() {
            if (cached != null) {
                return cached;
            }
            cached = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getName" -> name;
                        case "setName" -> {
                            name = (String) args[0];
                            yield null;
                        }
                        case "getFactionId" -> factionId;
                        case "setFactionId" -> {
                            factionId = (String) args[0];
                            yield null;
                        }
                        case "getSize" -> planetConditionMarketOnly ? 1 : size;
                        case "setSize" -> {
                            if (!planetConditionMarketOnly) {
                                size = (Integer) args[0];
                            }
                            yield null;
                        }
                        case "isPlanetConditionMarketOnly" -> planetConditionMarketOnly;
                        case "setPlanetConditionMarketOnly" -> {
                            planetConditionMarketOnly = (Boolean) args[0];
                            yield null;
                        }
                        case "isPlayerOwned" -> playerOwned;
                        case "setPlayerOwned" -> {
                            playerOwned = (Boolean) args[0];
                            yield null;
                        }
                        case "isFreePort" -> freePort;
                        case "setFreePort" -> {
                            freePort = (Boolean) args[0];
                            yield null;
                        }
                        case "isInEconomy" -> inEconomy;
                        case "getSurveyLevel" -> surveyLevel;
                        case "setSurveyLevel" -> {
                            surveyLevel = (MarketAPI.SurveyLevel) args[0];
                            yield null;
                        }
                        case "setAdmin" -> {
                            adminCleared = args[0] == null;
                            yield null;
                        }
                        // Surface DecivTracker.removeColony walks on its way through the teardown.
                        case "getConnectedEntities" -> {
                            Set<SectorEntityToken> connected = new LinkedHashSet<>();
                            if (primary != null) {
                                connected.add(primary);
                            }
                            yield connected;
                        }
                        case "getCommDirectory" -> commDirectory;
                        case "getPeopleCopy" -> List.of();
                        case "getPopulation" -> population;
                        case "getMemoryWithoutUpdate", "getMemory" -> memory;
                        case "removeSpecificCondition" -> {
                            conditions.removeIf(condition -> condition.id().equals(args[0]));
                            yield null;
                        }
                        case "removeIndustry" -> {
                            industries.remove((String) args[0]);
                            yield null;
                        }
                        case "removeSubmarket" -> {
                            submarkets.remove((String) args[0]);
                            yield null;
                        }
                        case "getConditions" -> {
                            List<MarketConditionAPI> all = new ArrayList<>();
                            for (FakeCondition condition : conditions) {
                                all.add(condition.proxy());
                            }
                            yield all;
                        }
                        case "hasCondition" -> conditions.stream()
                                .anyMatch(condition -> condition.id().equals(args[0]));
                        case "addCondition" -> {
                            conditions.add(new FakeCondition((String) args[0], false));
                            yield (String) args[0];
                        }
                        case "removeCondition" -> {
                            conditions.removeIf(condition -> condition.id().equals(args[0]));
                            yield null;
                        }
                        case "reapplyConditions" -> {
                            reappliedConditions = true;
                            yield null;
                        }
                        case "getIndustries" -> {
                            List<Industry> all = new ArrayList<>();
                            for (String industryId : industries) {
                                all.add(industryState(industryId).proxy());
                            }
                            yield all;
                        }
                        case "hasIndustry" -> industries.contains((String) args[0]);
                        case "getIndustry" -> industries.contains((String) args[0])
                                ? industryState((String) args[0]).proxy() : null;
                        case "addIndustry" -> {
                            industries.add((String) args[0]);
                            industryState((String) args[0]);
                            yield null;
                        }
                        case "reapplyIndustries" -> {
                            reappliedIndustries = true;
                            yield null;
                        }
                        case "getConstructionQueue" -> queue;
                        case "getSubmarketsCopy" -> new ArrayList<>(submarkets.values());
                        case "hasSubmarket" -> submarkets.containsKey((String) args[0]);
                        case "addSubmarket" -> {
                            submarkets.put((String) args[0], submarketProxy((String) args[0]));
                            yield null;
                        }
                        case "getSubmarket" -> submarkets.get((String) args[0]);
                        case "getPrimaryEntity" -> primary;
                        case "toString" -> "Market[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }

        private MarketAPI.SurveyLevel surveyLevel = MarketAPI.SurveyLevel.NONE;
    }

    /**
     * Enough industry to tell "finished" from "under construction" apart, plus the one vanilla quirk
     * that matters here: {@code PopulationAndInfrastructure} reports a colony below its maximum size as
     * upgrading with no upgrade in its spec ({@code PopulationAndInfrastructure.java:606-617}).
     */
    private static final class FakeIndustry {
        private final String id;
        private boolean building;
        private boolean growing;
        private int finishCalls;
        private Industry cached;

        private FakeIndustry(String id) {
            this.id = id;
        }

        Industry proxy() {
            if (cached != null) {
                return cached;
            }
            Object spec = Proxy.newProxyInstance(
                    com.fs.starfarer.api.loading.IndustrySpecAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.loading.IndustrySpecAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getUpgrade" -> null;
                        case "toString" -> "Spec[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            cached = (Industry) Proxy.newProxyInstance(
                    Industry.class.getClassLoader(),
                    new Class<?>[]{Industry.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getSpec" -> spec;
                        case "isBuilding" -> building;
                        case "isUpgrading" -> growing;
                        case "finishBuildingOrUpgrading" -> {
                            building = false;
                            finishCalls++;
                            yield null;
                        }
                        case "toString" -> "Industry[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    private static SubmarketAPI submarketProxy(String specId) {
        return (SubmarketAPI) Proxy.newProxyInstance(
                SubmarketAPI.class.getClassLoader(),
                new Class<?>[]{SubmarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSpecId" -> specId;
                    case "toString" -> "Submarket[" + specId + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class FakeSector {
        private final Map<String, FakeMarket> byPlanetId = new LinkedHashMap<>();
        private final Map<String, FakeMarket> byMarketId = new LinkedHashMap<>();
        private final Map<String, SectorEntityToken> planets = new LinkedHashMap<>();
        private final Map<String, String> entityFactions = new LinkedHashMap<>();
        private SectorAPI cached;

        FakeMarket addPlanetWithMarket(String planetId, String marketId) {
            FakeMarket market = new FakeMarket(marketId);
            byPlanetId.put(planetId, market);
            byMarketId.put(marketId, market);
            entityFactions.put(planetId, "neutral");
            SectorEntityToken planet = (SectorEntityToken) Proxy.newProxyInstance(
                    PlanetAPI.class.getClassLoader(),
                    new Class<?>[]{PlanetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> planetId;
                        case "getMarket" -> market.proxy();
                        case "setFaction" -> {
                            entityFactions.put(planetId, (String) args[0]);
                            yield null;
                        }
                        case "toString" -> "Planet[" + planetId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            planets.put(planetId, planet);
            market.primary = planet;
            return market;
        }

        /** A market registered with the economy but not reachable through any planet. */
        FakeMarket addEconomyOnlyMarket(String marketId) {
            FakeMarket market = new FakeMarket(marketId);
            byMarketId.put(marketId, market);
            return market;
        }

        PlanetAPI planetProxy(String planetId) {
            return (PlanetAPI) planets.get(planetId);
        }

        SectorAPI proxy() {
            if (cached != null) {
                return cached;
            }
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> {
                            FakeMarket market = byMarketId.get((String) args[0]);
                            yield market == null ? null : market.proxy();
                        }
                        case "getMarketsCopy" -> List.<MarketAPI>of();
                        case "addMarket" -> {
                            for (FakeMarket market : byMarketId.values()) {
                                if (market.proxy() == args[0]) {
                                    market.inEconomy = true;
                                    market.addMarketCalls++;
                                }
                            }
                            yield null;
                        }
                        case "removeMarket" -> {
                            for (FakeMarket market : byMarketId.values()) {
                                if (market.proxy() == args[0]) {
                                    market.inEconomy = false;
                                }
                            }
                            yield null;
                        }
                        case "toString" -> "Economy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            cached = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEconomy" -> economy;
                        case "getEntityById" -> planets.get((String) args[0]);
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
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
}
