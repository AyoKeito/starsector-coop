package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CharacterDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The host's commission poll and the guest's one-key apply.
 *
 * <p>The last two tests run against {@link CoopCommissionSync#liveEngine()} on a proxied sector,
 * which is what pins the two engine facts the mirror rests on: the key is
 * {@code MemFlags.FCM_FACTION} ({@code "$fcm_faction"}) and the object is
 * {@code getCharacterData().getMemoryWithoutUpdate()} — the same pair
 * {@code Misc.getCommissionFactionId} reads ({@code Misc.java:2419-2421}) and
 * {@code FactionCommissionIntel} writes ({@code FactionCommissionIntel.java:107}, {@code :120}).
 */
class CoopCommissionSyncTest {

    private static final long T0 = 1_000_000L;

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
    }

    @Test
    void theFirstPollAlwaysReportsWhateverTheHostHolds() {
        FakeEngine engine = new FakeEngine();
        engine.factionId = "hegemony";
        CoopCommissionSync sync = new CoopCommissionSync(engine);

        assertEquals("hegemony", sync.poll(T0), "the once-per-session send");
        assertNull(sync.poll(T0 + CoopCommissionSync.POLL_INTERVAL_MILLIS), "unchanged");
    }

    @Test
    void aSessionThatStartsWithNoCommissionSaysNothing() {
        // Every poller in this family is silent when it has nothing to report, and "no commission"
        // is what a guest assumes anyway. See CoopCommissionSync#poll for the trade and its residue.
        FakeEngine engine = new FakeEngine();
        CoopCommissionSync sync = new CoopCommissionSync(engine);

        assertNull(sync.poll(T0));
        assertNull(sync.poll(T0 + CoopCommissionSync.POLL_INTERVAL_MILLIS));
    }

    @Test
    void everyChangeIsReportedIncludingSigningWithTheSameFactionAgain() {
        FakeEngine engine = new FakeEngine();
        CoopCommissionSync sync = new CoopCommissionSync(engine);
        List<String> reported = new ArrayList<>();
        long now = T0;

        assertNull(sync.poll(now), "the silent seeding poll");
        engine.factionId = "hegemony";
        reported.add(sync.poll(now += CoopCommissionSync.POLL_INTERVAL_MILLIS));
        engine.factionId = null;
        reported.add(sync.poll(now += CoopCommissionSync.POLL_INTERVAL_MILLIS));
        engine.factionId = "hegemony";
        reported.add(sync.poll(now + CoopCommissionSync.POLL_INTERVAL_MILLIS));

        assertEquals(List.of("hegemony", "", "hegemony"), reported);
    }

    @Test
    void nullAndEmptyAndBlankAreTheSameValue() {
        FakeEngine engine = new FakeEngine();
        engine.factionId = "hegemony";
        CoopCommissionSync sync = new CoopCommissionSync(engine);
        long now = T0;

        assertEquals("hegemony", sync.poll(now));
        engine.factionId = null;
        assertEquals("", sync.poll(now += CoopCommissionSync.POLL_INTERVAL_MILLIS));
        engine.factionId = "";
        assertNull(sync.poll(now += CoopCommissionSync.POLL_INTERVAL_MILLIS));
        engine.factionId = "   ";
        assertNull(sync.poll(now + CoopCommissionSync.POLL_INTERVAL_MILLIS));
    }

    @Test
    void pollIsThrottledToOncePerSecond() {
        FakeEngine engine = new FakeEngine();
        engine.factionId = "hegemony";
        CoopCommissionSync sync = new CoopCommissionSync(engine);

        assertEquals("hegemony", sync.poll(T0));
        assertEquals(1, engine.reads);

        engine.factionId = "tritachyon";
        assertNull(sync.poll(T0 + CoopCommissionSync.POLL_INTERVAL_MILLIS - 1));
        assertEquals(1, engine.reads, "the pump calls this every frame; the engine sees one read");

        assertEquals("tritachyon", sync.poll(T0 + CoopCommissionSync.POLL_INTERVAL_MILLIS));
    }

    @Test
    void resetMakesTheNextPollReportAgain() {
        FakeEngine engine = new FakeEngine();
        engine.factionId = "hegemony";
        CoopCommissionSync sync = new CoopCommissionSync(engine);

        assertEquals("hegemony", sync.poll(T0));
        sync.reset();
        assertEquals("hegemony", sync.poll(T0 + 1),
                "a new session means a peer that has heard nothing");
    }

    @Test
    void applyingAValueStopsTheApplyingEngineFromReportingItBack() {
        FakeEngine engine = new FakeEngine();
        engine.factionId = "hegemony";
        CoopCommissionSync sync = new CoopCommissionSync(engine);

        sync.applyRemote("hegemony");

        assertEquals("hegemony", engine.written);
        assertNull(sync.poll(T0), "the value it was handed is not news");
        assertEquals(1, engine.reads, "and the poll still read the engine to find that out");
    }

    @Test
    void describeNamesTheFactionOrNone() {
        assertEquals("none", CoopCommissionSync.describe(null));
        assertEquals("none", CoopCommissionSync.describe(""));
        assertEquals("hegemony", CoopCommissionSync.describe("hegemony"));
    }

    // ---- The live engine, against a proxied sector ---------------------------------------------

    @Test
    void theLiveEngineWritesAndClearsTheKeyVanillaReads() {
        Map<String, Object> memory = new HashMap<>();
        Global.setSector(sectorWithCharacterMemory(memory));
        CoopCommissionSync sync = new CoopCommissionSync(CoopCommissionSync.liveEngine());

        sync.applyRemote("hegemony");
        assertEquals("hegemony", memory.get(MemFlags.FCM_FACTION));
        assertEquals("$fcm_faction", CoopCommissionSync.MEMORY_KEY);

        sync.applyRemote("");
        assertFalse(memory.containsKey(MemFlags.FCM_FACTION), "an ended commission unsets the key");
    }

    @Test
    void theLiveEngineNeverWritesTheIntelKey() {
        // Instantiating a FactionCommissionIntel on the guest would double-run the salary and the
        // termination, so FCM_EVENT stays empty there and nothing in the military-submarket access
        // check looks at it.
        Map<String, Object> memory = new HashMap<>();
        Global.setSector(sectorWithCharacterMemory(memory));

        new CoopCommissionSync(CoopCommissionSync.liveEngine()).applyRemote("hegemony");

        assertFalse(memory.containsKey(MemFlags.FCM_EVENT));
        assertEquals(1, memory.size());
    }

    @Test
    void theLiveEngineReadsBackWhatVanillaWrote() {
        Map<String, Object> memory = new HashMap<>();
        memory.put(MemFlags.FCM_FACTION, "tritachyon");
        Global.setSector(sectorWithCharacterMemory(memory));

        assertEquals("tritachyon",
                new CoopCommissionSync(CoopCommissionSync.liveEngine()).poll(T0));
    }

    @Test
    void theLiveEngineSurvivesASectorWithNoCharacterData() {
        Global.setSector(sectorWithCharacterMemory(null));
        CoopCommissionSync sync = new CoopCommissionSync(CoopCommissionSync.liveEngine());

        assertNull(sync.poll(T0), "an unreadable commission reads as none, not as a crash");
        sync.applyRemote("hegemony");
    }

    // ---- Fakes ---------------------------------------------------------------------------------

    private static SectorAPI sectorWithCharacterMemory(Map<String, Object> values) {
        MemoryAPI memory = values == null ? null : (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        values.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "unset" -> {
                        values.remove((String) args[0]);
                        yield null;
                    }
                    case "get" -> values.get((String) args[0]);
                    case "getString" -> (String) values.get((String) args[0]);
                    case "contains" -> values.containsKey((String) args[0]);
                    case "getBoolean" -> Boolean.TRUE.equals(values.get((String) args[0]));
                    case "toString" -> "Memory" + values;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        CharacterDataAPI character = memory == null ? null
                : (CharacterDataAPI) Proxy.newProxyInstance(
                        CharacterDataAPI.class.getClassLoader(),
                        new Class<?>[]{CharacterDataAPI.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getMemory", "getMemoryWithoutUpdate" -> memory;
                            case "toString" -> "CharacterData";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> defaultValue(method.getReturnType());
                        });
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCharacterData" -> character;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class FakeEngine implements CoopCommissionSync.Engine {
        private String factionId;
        private String written;
        private int reads;

        @Override
        public String commissionFactionId() {
            reads++;
            return factionId;
        }

        @Override
        public void writeCommissionFactionId(String factionId) {
            written = factionId;
        }
    }
}
