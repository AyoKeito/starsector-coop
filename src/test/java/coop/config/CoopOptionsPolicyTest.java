package coop.config;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 28 milestone 2. Everything here runs headless: the policy reaches the engine through a
 * supplier of the persistent-data map, which is a plain {@link Map} in these tests.
 */
class CoopOptionsPolicyTest {

    private static final String PAUSE = CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS;
    private static final String ALLOW_PAUSE = CoopOptionsRegistry.ALLOW_GUEST_PAUSE;

    private final Map<String, Object> persistent = new LinkedHashMap<>();

    private CoopOptionsPolicy host() {
        return new CoopOptionsPolicy(() -> true, () -> persistent);
    }

    private CoopOptionsPolicy guest() {
        return new CoopOptionsPolicy(() -> false, () -> persistent);
    }

    private static CoopOptionsStore storeWith(Map<String, Object> common) {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : common.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        return new CoopOptionsStore(new CoopOptionsStore.JsonSource() {
            @Override
            public JSONObject shipped() {
                return null;
            }

            @Override
            public JSONObject common() {
                return json;
            }
        }, key -> null);
    }

    // ---- seeding ---------------------------------------------------------------------------------

    @Test
    void aFreshCampaignSeedsFromTheInstallDefaultsAndWritesThemDown() {
        CoopOptionsPolicy policy = host();

        assertTrue(policy.ensureSeeded(storeWith(Map.of(PAUSE, false))));

        assertEquals("false", policy.effective(PAUSE));
        assertEquals("false", persistent.get(CoopOptionsPolicy.PERSIST_PREFIX + PAUSE),
                "the seeded value has to be written or the next load would re-seed from a file the"
                        + " player may have changed since");
        assertEquals("1", persistent.get(CoopOptionsPolicy.PERSIST_VERSION_KEY));
        assertEquals(CoopOptionsPolicy.FIRST_VERSION, policy.version());
    }

    @Test
    void aStoredValueWinsOverTheInstallDefault() {
        persistent.put(CoopOptionsPolicy.PERSIST_PREFIX + PAUSE, "false");
        persistent.put(CoopOptionsPolicy.PERSIST_VERSION_KEY, "7");
        CoopOptionsPolicy policy = host();

        assertTrue(policy.ensureSeeded(storeWith(Map.of(PAUSE, true))));

        assertEquals("false", policy.effective(PAUSE),
                "editing coop_options.json must not rewrite a campaign already in progress");
        assertEquals(7, policy.version(), "the campaign's own version continues where it left off");
    }

    @Test
    void policySurvivesSaveAndLoad() {
        CoopOptionsPolicy first = host();
        first.ensureSeeded(storeWith(Map.of()));
        first.set(PAUSE, "false");
        first.set(ALLOW_PAUSE, "false");

        // A game load builds a brand-new policy over the same persistent data.
        CoopOptionsPolicy loaded = host();
        loaded.ensureSeeded(storeWith(Map.of(PAUSE, true, ALLOW_PAUSE, true)));

        assertEquals("false", loaded.effective(PAUSE));
        assertEquals("false", loaded.effective(ALLOW_PAUSE));
        assertEquals(first.version(), loaded.version());
    }

    @Test
    void seedingDoesNotLatchWhileThereIsNoSector() {
        CoopOptionsPolicy policy = new CoopOptionsPolicy(() -> true, () -> null);

        assertFalse(policy.ensureSeeded(storeWith(Map.of(PAUSE, false))));
        assertFalse(policy.seeded());
        assertEquals("true", policy.effective(PAUSE), "the registry default holds until a sector");
    }

    @Test
    void aGuestNeverSeedsFromItsOwnFiles() {
        CoopOptionsPolicy policy = guest();

        assertFalse(policy.ensureSeeded(storeWith(Map.of(PAUSE, false))));
        assertEquals("true", policy.effective(PAUSE));
        assertTrue(persistent.isEmpty(), "a guest's campaign save must not gain a policy it did not"
                + " choose");
    }

    // ---- writes ----------------------------------------------------------------------------------

    @Test
    void aGuestCannotSetPolicy() {
        CoopOptionsPolicy policy = guest();

        assertFalse(policy.set(PAUSE, "false"));
        assertEquals("true", policy.effective(PAUSE));
        assertEquals(0, policy.version());
    }

    @Test
    void versionIsMonotonicAndOnlyMovesOnARealChange() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));

        assertTrue(policy.set(PAUSE, "false"));
        int afterFirst = policy.version();
        assertEquals(CoopOptionsPolicy.FIRST_VERSION + 1, afterFirst);

        assertFalse(policy.set(PAUSE, "false"), "setting the same value again is not a change");
        assertEquals(afterFirst, policy.version());

        assertTrue(policy.set(PAUSE, "true"));
        assertEquals(afterFirst + 1, policy.version());
        assertEquals(PAUSE, policy.lastChangedKey());
    }

    @Test
    void aBadValueIsCoercedRatherThanStored() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));

        policy.set(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "99999");

        assertEquals("3600", policy.effective(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS));
    }

    @Test
    void resetToDefaultsIsOneVersionBumpForTheWholeSweep() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));
        policy.set(PAUSE, "false");
        policy.set(ALLOW_PAUSE, "false");
        int before = policy.version();

        assertEquals(2, policy.resetToDefaults().size());

        assertEquals(before + 1, policy.version());
        assertEquals("true", policy.effective(PAUSE));
        assertEquals("true", policy.effective(ALLOW_PAUSE));
    }

    // ---- apply boundaries ------------------------------------------------------------------------

    @Test
    void aBoundedKeyDoesNotApplyUntilItsBoundaryComesRound() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));

        policy.set(PAUSE, "false");

        assertEquals("false", policy.effective(PAUSE));
        assertEquals("true", policy.applied(PAUSE), "nothing applies retroactively");
        assertTrue(policy.hasPendingChange(PAUSE));

        assertTrue(policy.advanceBoundary(PAUSE));
        assertEquals("false", policy.applied(PAUSE));
        assertFalse(policy.hasPendingChange(PAUSE));
        assertFalse(policy.advanceBoundary(PAUSE), "a boundary with nothing pending is a no-op");
    }

    @Test
    void anImmediateKeyAppliesOnTheSpot() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));

        policy.set(ALLOW_PAUSE, "false");

        assertEquals("false", policy.applied(ALLOW_PAUSE));
        assertFalse(policy.hasPendingChange(ALLOW_PAUSE));
    }

    // ---- the synced guest view -------------------------------------------------------------------

    @Test
    void aSnapshotReplacesTheWholeViewAndRespectsBoundaries() {
        CoopOptionsPolicy policy = guest();

        Map<String, String> values = new HashMap<>();
        values.put(PAUSE, "false");
        values.put(ALLOW_PAUSE, "false");
        CoopOptionsPolicy.SnapshotResult result = policy.applySnapshot(values, 4, PAUSE);

        assertTrue(result.accepted());
        assertEquals(4, policy.version());
        assertTrue(result.changedKeys().contains(PAUSE));
        assertEquals("false", policy.effective(PAUSE));
        assertEquals("true", policy.applied(PAUSE), "the guest's screen boundary has not come round");
        assertEquals("false", policy.applied(ALLOW_PAUSE), "an IMMEDIATE key needs no boundary");
        assertTrue(persistent.isEmpty(), "a synced view is not written into the guest's save");
    }

    @Test
    void aGuestDoesNotStayPendingOnABoundaryItNeverCrosses() {
        CoopOptionsPolicy host = host();
        host.ensureSeeded(storeWith(Map.of()));
        assertTrue(host.set(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "120"));

        CoopOptionsPolicy guest = guest();
        guest.applySnapshot(host.values(), host.version(),
                CoopOptionsRegistry.RECONNECT_GRACE_SECONDS);

        assertEquals("120", guest.effective(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS));
        // Nothing on a guest crosses NEXT_DROP, so pre-fix applied stayed at the registry default
        // forever: hasPendingChanges() was true every frame, CoopNetPump.maybeSendOptionsApplied
        // never sent the ack, and the host's own options page read "pending" for the whole session.
        assertEquals("120", guest.applied(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS));
        assertFalse(guest.hasPendingChanges());
    }

    @Test
    void theOneBoundaryAGuestDoesCrossStillWaitsForIt() {
        CoopOptionsPolicy guest = guest();

        guest.applySnapshot(Map.of(PAUSE, "false"), 5, PAUSE);

        assertEquals("true", guest.applied(PAUSE), "no core tab has opened or closed yet");
        assertTrue(guest.hasPendingChanges());
        assertTrue(guest.advanceBoundary(PAUSE));
        assertFalse(guest.hasPendingChanges());
    }

    @Test
    void aKeyMissingFromTheSnapshotGoesBackToItsDefault() {
        CoopOptionsPolicy policy = guest();
        policy.applySnapshot(Map.of(PAUSE, "false"), 2, PAUSE);
        policy.advanceBoundary(PAUSE);

        policy.applySnapshot(Map.of(), 3, "");

        assertEquals("true", policy.effective(PAUSE),
                "a full replacement, so a guest cannot hold a key from an older session");
    }

    @Test
    void aStaleSnapshotIsIgnored() {
        CoopOptionsPolicy policy = guest();
        policy.applySnapshot(Map.of(PAUSE, "false"), 9, PAUSE);

        CoopOptionsPolicy.SnapshotResult result = policy.applySnapshot(Map.of(PAUSE, "true"), 8, PAUSE);

        assertTrue(result.stale());
        assertEquals(9, policy.version());
        assertEquals("false", policy.effective(PAUSE),
                "a late duplicate after a resume must not walk the policy backwards");
    }

    @Test
    void aResentSnapshotAtTheSameVersionIsAcceptedAndChangesNothing() {
        CoopOptionsPolicy policy = guest();
        policy.applySnapshot(Map.of(PAUSE, "false"), 5, PAUSE);

        CoopOptionsPolicy.SnapshotResult result = policy.applySnapshot(Map.of(PAUSE, "false"), 5, "");

        assertTrue(result.accepted());
        assertTrue(result.changedKeys().isEmpty(), "a resume re-send is not an event to narrate");
    }

    @Test
    void clearingTheSyncedViewGoesBackToTheSafeDefaults() {
        CoopOptionsPolicy policy = guest();
        policy.applySnapshot(Map.of(PAUSE, "false"), 5, PAUSE);
        policy.advanceBoundary(PAUSE);

        policy.clearSyncedView();

        assertEquals("true", policy.applied(PAUSE));
        assertEquals(0, policy.version());
        assertFalse(policy.seeded());
    }

    // ---- the static handle -----------------------------------------------------------------------

    @Test
    void theStaticHandleFallsBackToTheRegistryDefault() {
        CoopOptionsPolicy.uninstall();
        assertNull(CoopOptionsPolicy.active());
        assertEquals("true", CoopOptionsPolicy.appliedOrDefault(PAUSE));

        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));
        policy.set(PAUSE, "false");
        policy.advanceBoundary(PAUSE);
        CoopOptionsPolicy.install(policy);
        try {
            assertEquals("false", CoopOptionsPolicy.appliedOrDefault(PAUSE));
        } finally {
            CoopOptionsPolicy.uninstall();
        }
    }

    // ---- review item 5: a reset says so on the wire ----------------------------------------------

    @Test
    void aMultiKeyResetIsMarkedSoBothSidesCanNarrateIt() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));
        policy.set(PAUSE, "false");
        policy.set(ALLOW_PAUSE, "false");

        policy.resetToDefaults();

        assertEquals(CoopOptionsPolicy.RESET_MARKER, policy.lastChangedKey(),
                "\"\" already means \"establish broadcast, narrate nothing\"");
        assertFalse(CoopOptionsPolicy.isPolicyKey(CoopOptionsPolicy.RESET_MARKER),
                "the marker must not be mistakable for a key");
    }

    @Test
    void aResetThatMovesOneKeyStillNamesThatKey() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));
        policy.set(PAUSE, "false");

        policy.resetToDefaults();

        assertEquals(PAUSE, policy.lastChangedKey());
    }

    // ---- review item 2: the host's pending clears on the guest's acknowledgement ------------------

    @Test
    void theHostsPendingClearsOnlyOnAnAcknowledgementOfTheCurrentVersion() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));
        policy.set(PAUSE, "false");
        int version = policy.version();

        assertTrue(policy.hasPendingChanges());
        assertFalse(policy.acknowledgeApplied(version - 1),
                "an ack for a version we have already moved past says nothing about this change");
        assertTrue(policy.hasPendingChange(PAUSE));

        assertTrue(policy.acknowledgeApplied(version));
        assertFalse(policy.hasPendingChange(PAUSE));
        assertFalse(policy.hasPendingChanges());
        assertEquals("false", policy.applied(PAUSE));
    }

    @Test
    void withNoGuestToWaitForEverythingPromotesAtOnce() {
        CoopOptionsPolicy policy = host();
        policy.ensureSeeded(storeWith(Map.of()));
        policy.set(PAUSE, "false");

        assertTrue(policy.acknowledgeAllApplied());

        assertFalse(policy.hasPendingChanges());
        assertFalse(policy.acknowledgeAllApplied(), "nothing left to promote");
    }

    @Test
    void onlyPolicyKeysAreAccepted() {
        CoopOptionsPolicy policy = host();
        assertTrue(CoopOptionsPolicy.isPolicyKey(PAUSE));
        assertFalse(CoopOptionsPolicy.isPolicyKey(CoopOptionsRegistry.HUD_CORNER));
        try {
            policy.effective(CoopOptionsRegistry.HUD_CORNER);
            throw new AssertionError("a client-tier key is not policy and must not read as one");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(CoopOptionsRegistry.HUD_CORNER));
        }
    }
}
