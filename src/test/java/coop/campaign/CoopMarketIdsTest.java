package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32 addition A: the hidden-base {@code hostMarketId <-> localMarketId} table.
 *
 * <p>Two properties carry the whole feature and are pinned here. A learned pair translates in both
 * directions, so a guest's request names the host's base and the host's snapshot lands on the
 * guest's. And <b>anything unlearned is the identity function</b>, which is what lets the ~150
 * colony and gen-time markets — whose ids agree across the two engines by construction — go through
 * the same calls untouched, and what makes the whole table a no-op on the host.
 */
class CoopMarketIdsTest {

    private static final String HOST_BASE = "market_00A1F";
    private static final String LOCAL_BASE = "market_77CD2";

    @Test
    void aLearnedPairTranslatesInBothDirections() {
        CoopMarketIds ids = new CoopMarketIds();
        assertTrue(ids.learn(HOST_BASE, LOCAL_BASE));

        assertEquals(LOCAL_BASE, ids.toLocal(HOST_BASE), "an inbound host id resolves to our market");
        assertEquals(HOST_BASE, ids.toWire(LOCAL_BASE), "an outbound local id names the host's");
        assertEquals(Map.of(HOST_BASE, LOCAL_BASE), ids.mappings());
        assertTrue(ids.isMappedLocal(LOCAL_BASE));
        assertFalse(ids.isMappedLocal(HOST_BASE), "the host id is not a market on this engine");
    }

    @Test
    void anUnmappedIdPassesThroughUnchangedInBothDirections() {
        CoopMarketIds ids = new CoopMarketIds();
        ids.learn(HOST_BASE, LOCAL_BASE);

        // Every colony and gen-time market. Colonies reuse their planet's "market_<planetId>"
        // planet-condition market, and seed-locked worldgen mints the same id on both engines, so
        // these must never be rewritten.
        assertEquals("market_jangala", ids.toLocal("market_jangala"));
        assertEquals("market_jangala", ids.toWire("market_jangala"));
        assertFalse(ids.isMappedLocal("market_jangala"));
    }

    @Test
    void translatingAnAlreadyLocalIdIsANoOp() {
        // Callers translate defensively without knowing where their id came from, so toLocal must be
        // idempotent: local ids are values of the host->local direction, never keys of it.
        CoopMarketIds ids = new CoopMarketIds();
        ids.learn(HOST_BASE, LOCAL_BASE);

        assertEquals(LOCAL_BASE, ids.toLocal(ids.toLocal(HOST_BASE)));
        assertEquals(HOST_BASE, ids.toWire(ids.toWire(LOCAL_BASE)));
    }

    @Test
    void theHostsEmptyTableIsTheIdentityFunction() {
        CoopMarketIds ids = new CoopMarketIds();
        assertEquals(0, ids.size());
        assertEquals(HOST_BASE, ids.toLocal(HOST_BASE));
        assertEquals(HOST_BASE, ids.toWire(HOST_BASE));
    }

    @Test
    void blankAndIdentityPairsAreNotStored() {
        CoopMarketIds ids = new CoopMarketIds();

        // A base captured before its market existed carries an empty id on one side or the other.
        assertFalse(ids.learn("", LOCAL_BASE));
        assertFalse(ids.learn(HOST_BASE, ""));
        assertFalse(ids.learn(null, LOCAL_BASE));
        assertFalse(ids.learn(HOST_BASE, null));
        // The host pairs every record with itself; storing that would make the bridge dump claim
        // there is translation going on when there is none.
        assertFalse(ids.learn(HOST_BASE, HOST_BASE));

        assertEquals(0, ids.size());
    }

    @Test
    void relearningTheSamePairChangesNothingAndFiresNoListener() {
        CoopMarketIds ids = new CoopMarketIds();
        List<String> mapped = new ArrayList<>();
        ids.setListener((host, local, previousLocal) -> mapped.add(host + "->" + local));

        assertTrue(ids.learn(HOST_BASE, LOCAL_BASE));
        assertFalse(ids.learn(HOST_BASE, LOCAL_BASE), "the guest reconcile re-pairs every 5 seconds");
        assertFalse(ids.learn(HOST_BASE, LOCAL_BASE));

        assertEquals(List.of(HOST_BASE + "->" + LOCAL_BASE), mapped);
    }

    @Test
    void remappingABaseDropsTheDeadReverseEntry() {
        // A RECREATE (faction changed) or a reload re-mints the local market: the old local id names
        // a market that no longer exists, and toWire must stop answering for it.
        CoopMarketIds ids = new CoopMarketIds();
        ids.learn(HOST_BASE, LOCAL_BASE);
        assertTrue(ids.learn(HOST_BASE, "market_REBUILT"));

        assertEquals("market_REBUILT", ids.toLocal(HOST_BASE));
        assertEquals(HOST_BASE, ids.toWire("market_REBUILT"), "the new local id names the host's");
        assertEquals(LOCAL_BASE, ids.toWire(LOCAL_BASE), "the dead local id is back to identity");
        assertEquals(1, ids.size());
    }

    @Test
    void aRemapTellsTheListenerWhichLocalIdItDisplaced() {
        // Red-team P1-4: the listener's state (a storage flag) is parked under the OLD local id
        // after the first mapping, not under the host's, so a remap that does not name the displaced
        // id leaves it stranded and the rebuilt base locked.
        CoopMarketIds ids = new CoopMarketIds();
        List<String> mapped = new ArrayList<>();
        ids.setListener((host, local, previousLocal) ->
                mapped.add(host + "->" + local + " was=" + previousLocal));

        ids.learn(HOST_BASE, LOCAL_BASE);
        ids.learn(HOST_BASE, "market_REBUILT");

        assertEquals(List.of(
                HOST_BASE + "->" + LOCAL_BASE + " was=null",
                HOST_BASE + "->market_REBUILT was=" + LOCAL_BASE), mapped);
    }

    @Test
    void toLocalShortCircuitsAnIdThisTableAlreadyKnowsAsLocal() {
        // Red-team P2-8. Base A's local id and base B's host id are independent Misc.genUID() draws
        // from two engines' counters, so nothing rules out the same string being a value of one
        // mapping and a key of another -- and findMarket translates defensively on ids that are
        // already local, so a collision would silently resolve a message about A onto B's market.
        CoopMarketIds ids = new CoopMarketIds();
        ids.learn("host-A", "collision");
        ids.learn("host-B", "local-B");
        // ...and now the same string turns up as base C's host id.
        ids.learn("collision", "local-C");

        assertEquals("collision", ids.toLocal("collision"),
                "an id this table knows as local comes back untouched; it used to resolve to local-B");
        assertEquals("collision", ids.toLocal(ids.toLocal("collision")), "and stays idempotent");
        assertEquals("local-B", ids.toLocal("host-B"), "a genuine host id still translates");
        assertEquals("unmapped", ids.toLocal("unmapped"), "and an unknown id is still the identity");
    }

    @Test
    void clearForgetsEverything() {
        CoopMarketIds ids = new CoopMarketIds();
        ids.learn(HOST_BASE, LOCAL_BASE);
        ids.clear();

        assertEquals(0, ids.size());
        assertEquals(HOST_BASE, ids.toLocal(HOST_BASE));
        assertEquals(LOCAL_BASE, ids.toWire(LOCAL_BASE));
    }

    @Test
    void aThrowingListenerCostsNothingButTheSideEffect() {
        // The table is the load-bearing part. A failing storage-unlock migration must not take the
        // mapping down with it, or every market message for that base stays broken.
        CoopMarketIds ids = new CoopMarketIds();
        ids.setListener((host, local, previousLocal) -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(ids.learn(HOST_BASE, LOCAL_BASE));
        assertEquals(LOCAL_BASE, ids.toLocal(HOST_BASE));
    }

    @Test
    void mappingsIsAnOrderedSnapshotThatDoesNotAliasTheTable() {
        CoopMarketIds ids = new CoopMarketIds();
        ids.learn("host-1", "local-1");
        ids.learn("host-2", "local-2");

        Map<String, String> snapshot = ids.mappings();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("host-1", "local-1");
        expected.put("host-2", "local-2");
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(snapshot.keySet()));

        ids.learn("host-3", "local-3");
        assertEquals(2, snapshot.size(), "the bridge dump must not mutate under the caller");
    }
}
