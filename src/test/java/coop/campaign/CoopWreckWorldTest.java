package coop.campaign;

import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.CargoPodsEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import coop.net.CoopJson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Identity, migration and durable consumption at the engine boundary, independent of transport. */
class CoopWreckWorldTest {
    @Test
    void captureAssignsStableSavedIdsOnlyToLiveBasicHyperspaceWrecks() {
        Fixture f = new Fixture();
        FakeEntity wreck = f.add("native-counter");
        FakeEntity fading = f.add("fading");
        fading.tags.add(Tags.FADING_OUT_AND_EXPIRING);
        FakeEntity dead = f.add("dead"); dead.alive = false;
        FakeEntity pod = f.add("pod"); pod.plugin = new CargoPodsEntityPlugin();
        FakeEntity quest = f.add("quest"); quest.plugin = new QuestWreck();
        FakeEntity systemWreck = f.add("system"); systemWreck.inHyper = false;

        List<CoopWreckRecord> first = f.world.captureHost();
        List<CoopWreckRecord> second = f.world.captureHost();

        assertEquals(1, first.size());
        String id = first.get(0).id();
        assertTrue(id.startsWith("wreck:"));
        assertEquals(id, wreck.saved.get(CoopWreckWorld.WRECK_ID));
        assertEquals(first, second);
        assertEquals(Set.of(id), f.world.taggedIds());
        assertFalse(fading.saved.containsKey(CoopWreckWorld.WRECK_ID));
        assertFalse(pod.saved.containsKey(CoopWreckWorld.WRECK_ID));
        assertFalse(quest.saved.containsKey(CoopWreckWorld.WRECK_ID));
    }

    @Test
    void failedCaptureCannotReturnAPartialManifest() {
        Fixture f = new Fixture();
        f.add("good"); f.add("unsupported");
        f.codec.captureFailures.add("unsupported");
        assertThrows(IllegalStateException.class, f.world::captureHost);
    }

    @Test
    void duplicateCanonicalIdsFailInsteadOfChoosingAnArbitraryWreck() {
        Fixture f = new Fixture();
        f.add("one").tag("wreck:same"); f.add("two").tag("wreck:same");
        assertThrows(IllegalStateException.class, f.world::captureHost);
        assertThrows(IllegalStateException.class, f.world::taggedIds);
        assertThrows(IllegalStateException.class, () -> f.world.upsert(record("wreck:same", "host")));
    }

    @Test
    void missingSectorIsAnUnavailableCaptureNotAnEmptyAuthoritySet() {
        CoopWreckWorld world = new CoopWreckWorld(() -> null);
        assertNull(world.captureHost());
    }

    @Test
    void upsertUpdatesTheTaggedEntityWithoutReplacingItsDiscoveryState() {
        Fixture f = new Fixture();
        FakeEntity existing = f.add("guest-counter").tag("wreck:shared");
        existing.discovery = 0.7f;
        CoopWreckRecord r = record("wreck:shared", "host-counter");

        f.world.upsert(r); f.world.upsert(r);

        assertEquals(List.of(existing.entity), f.entities);
        assertEquals(List.of(existing.entity, existing.entity), f.codec.applied);
        assertEquals(0.7f, existing.discovery);
        assertEquals(0, f.codec.creates);
    }

    @Test
    void strictLegacyMatchIsAdoptedBeforeCreatingADuplicate() {
        Fixture f = new Fixture();
        FakeEntity legacy = f.add("host-counter"); legacy.discovery = 1f;
        f.world.upsert(record("wreck:shared", "host-counter"));

        assertEquals(List.of(legacy.entity), f.entities);
        assertEquals("wreck:shared", CoopWreckWorld.ownedId(legacy.entity));
        assertEquals(1f, legacy.discovery);
        assertEquals(0, f.codec.creates);
    }

    @Test
    void canonicalDialogTargetKeepsItsNativeRecoveryRecipeUntilTheDialogCloses() {
        Fixture f = new Fixture();
        FakeEntity existing = f.add("guest-counter").tag("wreck:shared");
        existing.nativeRecovery = "guest-story-point-conversion";
        f.dialogTarget = existing.entity;

        f.world.upsert(record("wreck:shared", "host-counter"));

        assertTrue(f.codec.applied.isEmpty());
        assertEquals("guest-story-point-conversion", existing.nativeRecovery);
        assertEquals(List.of(existing.entity), f.entities);
        f.dialogTarget = null;
        f.world.upsert(record("wreck:shared", "host-counter"));
        assertEquals(List.of(existing.entity), f.codec.applied);
        assertEquals("AVERAGE", existing.nativeRecovery);
    }

    @Test
    void legacyDialogDefersEveryUpsertBeforeTaggingOrCreatingEvenWhenRecipeNoLongerMatches() {
        Fixture f = new Fixture();
        FakeEntity legacy = f.add("unmatching-legacy");
        legacy.nativeRecovery = "guest-story-point-conversion";
        f.dialogTarget = legacy.entity;

        assertThrows(IllegalStateException.class,
                () -> f.world.upsert(record("wreck:shared", "different-host-counter")));

        assertEquals(List.of(legacy.entity), f.entities);
        assertNull(CoopWreckWorld.ownedId(legacy.entity));
        assertTrue(f.codec.applied.isEmpty());
        assertEquals("guest-story-point-conversion", legacy.nativeRecovery);
        assertEquals(0, f.codec.creates);
        f.dialogTarget = null;
        f.world.upsert(record("wreck:shared", "different-host-counter"));
        assertEquals(1, f.codec.creates);
    }

    @Test
    void cleanupPreflightsAllLegacyWrecksAndRemovesNoneWhileOneIsTheDialogTarget() {
        Fixture f = new Fixture();
        FakeEntity first = f.add("first");
        FakeEntity active = f.add("active");
        f.dialogTarget = active.entity;

        assertThrows(IllegalStateException.class, () -> f.world.reconcileLegacy(List.of()));
        assertEquals(List.of(first.entity, active.entity), f.entities);
        f.dialogTarget = null;
        f.world.reconcileLegacy(List.of());
        assertTrue(f.entities.isEmpty());
    }

    @Test
    void anUnrelatedNonWreckDialogDoesNotBlockUpsertsOrLegacyCleanup() {
        Fixture f = new Fixture();
        FakeEntity pod = f.add("pod"); pod.plugin = new CargoPodsEntityPlugin();
        FakeEntity legacy = f.add("legacy");
        f.dialogTarget = pod.entity;
        CoopWreckRecord r = record("wreck:shared", "host-counter");

        f.world.upsert(r);
        f.world.reconcileLegacy(List.of(r));

        assertFalse(f.entities.contains(legacy.entity));
        assertTrue(f.entities.contains(pod.entity));
        assertEquals(Set.of("wreck:shared"), f.world.taggedIds());
    }

    @Test
    void aManagedWreckDialogDoesNotBlockUpdatesToOtherCanonicalWrecks() {
        Fixture f = new Fixture();
        FakeEntity active = f.add("active").tag("wreck:active");
        FakeEntity other = f.add("other").tag("wreck:other");
        active.nativeRecovery = "guest-conversion";
        f.dialogTarget = active.entity;

        f.world.upsert(record("wreck:other", "host-counter"));

        assertEquals(List.of(other.entity), f.codec.applied);
        assertEquals("guest-conversion", active.nativeRecovery);
    }

    @Test
    void unmatchingLegacyWreckIsRemovedOnlyAfterTheSuccessfulCompleteManifest() {
        Fixture f = new Fixture();
        FakeEntity legacy = f.add("independent-guest-counter");
        FakeEntity pod = f.add("pod"); pod.plugin = new CargoPodsEntityPlugin();
        FakeEntity quest = f.add("quest"); quest.plugin = new QuestWreck();
        FakeEntity system = f.add("system"); system.inHyper = false;
        CoopWreckRecord r = record("wreck:host", "host-counter");

        f.world.upsert(r);
        assertTrue(f.entities.contains(legacy.entity));
        assertEquals(1, f.codec.creates);
        f.world.reconcileLegacy(List.of(r));

        assertFalse(f.entities.contains(legacy.entity));
        assertTrue(f.entities.containsAll(List.of(pod.entity, quest.entity, system.entity)));
        assertEquals(Set.of("wreck:host"), f.world.taggedIds());
    }

    @Test
    void failedMaterializationLeavesUnmatchedLegacyWrecksIntact() {
        Fixture f = new Fixture();
        FakeEntity legacy = f.add("legacy");
        f.codec.failCreate = true;
        assertThrows(IllegalStateException.class, () -> f.world.upsert(record("wreck:host", "host")));
        assertEquals(List.of(legacy.entity), f.entities);
    }

    @Test
    void incompleteManifestCannotDestructivelyCleanLegacyWrecks() {
        Fixture f = new Fixture();
        FakeEntity legacy = f.add("legacy");
        assertThrows(IllegalStateException.class,
                () -> f.world.reconcileLegacy(List.of(record("wreck:not-materialized", "host"))));
        assertEquals(List.of(legacy.entity), f.entities);
    }

    @Test
    void pendingConsumptionPreventsAnOldHostCheckpointFromRecreatingTheWreck() {
        Fixture f = new Fixture();
        f.world.markConsumed("wreck:gone");
        f.world.upsert(record("wreck:gone", "host-counter"));
        assertTrue(f.entities.isEmpty());
        assertEquals(0, f.codec.creates);
    }

    @Test
    void fadeStartsConsumptionBeforeRemovalAndUpsertCannotResetIt() {
        Fixture f = new Fixture();
        FakeEntity wreck = f.add("guest").tag("wreck:salvaged");
        wreck.tags.add(Tags.FADING_OUT_AND_EXPIRING);
        f.world.upsert(record("wreck:salvaged", "host"));

        assertTrue(f.codec.applied.isEmpty());
        assertEquals(Set.of("wreck:salvaged"), f.world.locallyConsumed(Set.of()));
        f.entities.remove(wreck.entity);
        assertEquals(Set.of("wreck:salvaged"), f.world.locallyConsumed(Set.of("wreck:salvaged")));
        assertTrue(f.world.locallyConsumed(Set.of()).isEmpty(), "A forgotten remote removal is not local salvage");
    }

    @Test
    void untaggedAndCustomWrecksNeverBecomeConsumptionReports() {
        Fixture f = new Fixture();
        f.add("local").tags.add(Tags.FADING_OUT_AND_EXPIRING);
        FakeEntity quest = f.add("quest").tag("wreck:quest"); quest.plugin = new QuestWreck();
        quest.tags.add(Tags.FADING_OUT_AND_EXPIRING);
        assertTrue(f.world.locallyConsumed(Set.of()).isEmpty());
    }

    @Test
    void removalUsesCanonicalMemoryNeverAnUnrelatedEngineIdCollision() {
        Fixture f = new Fixture();
        FakeEntity unrelated = f.add("wreck:shared");
        FakeEntity target = f.add("guest-counter").tag("wreck:shared");

        f.world.remove("wreck:shared");

        assertEquals(List.of(unrelated.entity), f.entities);
        assertFalse(f.entities.contains(target.entity));
    }

    @Test
    void tombstonesSurviveNewAdaptersAsOnlyPrimitiveTextAndRejectUnsafeIdentities() {
        Fixture f = new Fixture();
        String unusual = "wreck:f372213e-3cf4-4a36-ab0c-7cd91bd73a83";
        f.world.markConsumed("wreck:second"); f.world.markConsumed(unusual);
        int writes = f.memoryWrites;
        f.world.markConsumed(unusual);

        assertInstanceOf(String.class, f.saved.get(CoopWreckWorld.CONSUMED_IDS));
        CoopWreckWorld restored = new CoopWreckWorld(() -> f.sector, f.codec);
        assertEquals(Set.of("wreck:second", unusual), restored.consumedIds());
        assertEquals(writes, f.memoryWrites, "Duplicate echoes must not rewrite saved state");
        for (String invalid : List.of("wreck:quote\"", "wreck:space here", "wreck:slash\\", "wreck:newline\n")) {
            assertThrows(IllegalArgumentException.class, () -> f.world.markConsumed(invalid));
        }
        f.saved.put(CoopWreckWorld.CONSUMED_IDS, "[\"wreck:unsafe suffix\"]");
        assertThrows(IllegalArgumentException.class, restored::consumedIds);
    }

    @Test
    void consumedIdsCannotReenterTheHostManifest() {
        Fixture f = new Fixture();
        f.add("host").tag("wreck:consumed");
        f.world.markConsumed("wreck:consumed");
        assertTrue(f.world.captureHost().isEmpty());
        assertTrue(f.entities.isEmpty());
    }

    @Test
    void cachedTombstonesFollowTheLoadedSectorAndObserveExternalSavedStateChanges() {
        Fixture one = new Fixture(), two = new Fixture();
        SectorAPI[] selected = {one.sector};
        CoopWreckWorld adapter = new CoopWreckWorld(() -> selected[0], one.codec);
        adapter.markConsumed("wreck:first-campaign");
        selected[0] = two.sector;
        assertTrue(adapter.consumedIds().isEmpty());
        adapter.markConsumed("wreck:second-campaign");
        selected[0] = one.sector;
        assertEquals(Set.of("wreck:first-campaign"), adapter.consumedIds());
        one.saved.put(CoopWreckWorld.CONSUMED_IDS, "[\"wreck:external-change\"]");
        assertEquals(Set.of("wreck:external-change"), adapter.consumedIds());
        assertThrows(UnsupportedOperationException.class, () -> adapter.consumedIds().add("wreck:mutation"));
    }

    @Test
    void malformedPersistentStateFailsLoudly() {
        Fixture f = new Fixture();
        for (Object corrupt : List.of(1L, "{}", "[42]", "[\"raw-engine-id\"]",
                "[\"wreck:duplicate\",\"wreck:duplicate\"]", "[\"wreck:ok\"] trailing")) {
            f.saved.put(CoopWreckWorld.CONSUMED_IDS, corrupt);
            assertThrows(RuntimeException.class, f.world::consumedIds, String.valueOf(corrupt));
        }
    }

    @Test
    void aPartialSalvageOutcomeIsDurableAndCannotBeOverwrittenByOldHostState() {
        Fixture f = new Fixture();
        CoopWreckRecord before = recipe(record("wreck:shared", "host"), "original", false);
        f.world.upsert(before, 7);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        entity.recipe = recipe(before, null, false);
        f.world.observeMutations();

        CoopWreckMutation pending = f.world.pendingMutations().get(0);
        assertEquals(7, pending.baseRevision());
        assertNull(pending.after().recovery());
        assertInstanceOf(String.class, f.saved.get(CoopWreckWorld.PENDING_MUTATIONS));
        f.world.upsert(before, 7);
        assertNull(entity.recipe.recovery(), "A heartbeat must not regrant the already-collected special");
        CoopWreckWorld restored = new CoopWreckWorld(() -> f.sector, f.codec);
        assertEquals(List.of(pending), restored.pendingMutations());
    }

    @Test
    void nativePreviewIsCapturedOnlyAfterDialogCloseButTrapActivationIsCapturedImmediately() {
        Fixture f = new Fixture();
        CoopWreckRecord before = recipe(record("wreck:shared", "host"), "original", false);
        f.world.upsert(before, 0);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        f.dialogTarget = entity.entity;
        entity.recipe = recipe(before, "preview", false);
        f.world.observeMutations();
        assertTrue(f.world.pendingMutations().isEmpty());
        f.dialogTarget = null;
        f.world.observeMutations();
        assertEquals(1, f.world.pendingMutations().size());
        f.dialogTarget = entity.entity;
        entity.saved.put(CoopWreckWorld.TRAP_ACTIVATED, true);
        entity.recipe = recipe(before, null, true);
        f.world.observeMutations();
        assertEquals(2, f.world.pendingMutations().size());
        assertEquals("preview", f.world.pendingMutations().get(1).after().recovery().description(),
                "During an active trap dialog only the irreversible activation marker is captured");
    }

    @Test
    void hostCommitsOnlySalvageStateWithAMonotonicRevisionAndDuplicateAcknowledgement() {
        Fixture f = new Fixture();
        FakeEntity entity = f.add("host").tag("wreck:shared");
        entity.recipe = recipe(record("wreck:shared", "host"), "loot", false);
        CoopWreckRecord before = f.world.captureHost().get(0);
        CoopWreckRecord after = recipe(before, null, false);
        CoopWreckMutation mutation = new CoopWreckMutation("one", 0, after);

        CoopWreckMutation.Ack accepted = f.world.applyMutation(mutation);
        CoopWreckMutation.Ack duplicate = f.world.applyMutation(mutation);

        assertEquals(CoopWreckMutation.Status.ACCEPTED, accepted.status());
        assertEquals(1, accepted.revision());
        assertEquals(accepted, duplicate);
        assertNull(entity.recipe.recovery());
        assertEquals(1, f.codec.applied.size());
        assertEquals("one", entity.saved.get(CoopWreckWorld.LAST_MUTATION));
        assertEquals(before.x(), accepted.current().x());
        assertEquals(before.ship(), accepted.current().ship());
        assertEquals(before.salvageSeed(), accepted.current().salvageSeed());
    }

    @Test
    void aHostRecipeChangeRejectsAnOldRevisionWithoutRestoringOldSalvage() {
        Fixture f = new Fixture();
        FakeEntity entity = f.add("host").tag("wreck:shared");
        entity.recipe = recipe(record("wreck:shared", "host"), "original", false);
        CoopWreckRecord before = f.world.captureHost().get(0);
        entity.recipe = recipe(before, "new-host-special", false);
        f.world.captureHost();

        CoopWreckMutation.Ack conflict = f.world.applyMutation(new CoopWreckMutation("stale", 0, recipe(before, null, false)));

        assertEquals(CoopWreckMutation.Status.CONFLICT, conflict.status());
        assertEquals(1, conflict.revision());
        assertEquals("new-host-special", entity.recipe.recovery().description());
        assertTrue(f.codec.applied.isEmpty());
    }

    @Test
    void pendingMutationChainsAdvanceOnlyWhenTheExactAcknowledgementArrives() {
        Fixture f = new Fixture();
        CoopWreckRecord before = recipe(record("wreck:shared", "host"), "original", false);
        f.world.upsert(before, 3);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        entity.recipe = recipe(before, "first-result", false); f.world.observeMutations();
        entity.recipe = recipe(before, null, false); f.world.observeMutations();
        List<CoopWreckMutation> chain = f.world.pendingMutations();
        assertEquals(List.of(3L, 4L), chain.stream().map(CoopWreckMutation::baseRevision).toList());
        f.world.acknowledgeMutation(new CoopWreckMutation.Ack("unrelated", before.id(), CoopWreckMutation.Status.ACCEPTED, 4, before));
        assertEquals(2, f.world.pendingMutations().size());
        f.world.acknowledgeMutation(new CoopWreckMutation.Ack(chain.get(0).mutationId(), before.id(),
                CoopWreckMutation.Status.ACCEPTED, 4, chain.get(0).after()));
        assertEquals(1, f.world.pendingMutations().size());
        assertNull(entity.recipe.recovery(), "The newer local outcome survives the earlier acknowledgement");
        f.world.acknowledgeMutation(new CoopWreckMutation.Ack(chain.get(1).mutationId(), before.id(),
                CoopWreckMutation.Status.ACCEPTED, 5, chain.get(1).after()));
        assertTrue(f.world.pendingMutations().isEmpty());
        assertEquals(5, f.world.revision(before.id()));
    }

    @Test
    void conflictAcknowledgementKeepsTheDurableLocalOutcome() {
        Fixture f = new Fixture();
        CoopWreckRecord before = recipe(record("wreck:shared", "host"), "original", false);
        f.world.upsert(before, 0);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        entity.recipe = recipe(before, null, false); f.world.observeMutations();
        CoopWreckMutation pending = f.world.pendingMutations().get(0);
        assertThrows(IllegalStateException.class, () -> f.world.acknowledgeMutation(new CoopWreckMutation.Ack(
                pending.mutationId(), before.id(), CoopWreckMutation.Status.CONFLICT, 9, before)));
        assertEquals(List.of(pending), f.world.pendingMutations());
        assertNull(entity.recipe.recovery());
    }

    @Test
    void pendingTrapOutcomeSurvivesNativeConsumptionUntilAcknowledged() {
        Fixture f = new Fixture();
        CoopWreckRecord before = recipe(record("wreck:shared", "host"), "trap", false);
        f.world.upsert(before, 0);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        entity.recipe = recipe(before, null, true); f.world.observeMutations();
        CoopWreckMutation pending = f.world.pendingMutations().get(0);
        f.world.markConsumed(before.id()); f.world.remove(before.id());
        assertEquals(List.of(pending), f.world.pendingMutations());
        f.world.acknowledgeMutation(new CoopWreckMutation.Ack(pending.mutationId(), before.id(),
                CoopWreckMutation.Status.ACCEPTED, 1, pending.after()));
        assertTrue(f.world.pendingMutations().isEmpty());
        assertTrue(f.entities.isEmpty());
    }

    @Test
    void nativeTrapEffectRunsAtMostOnceAndItsAppliedMarkerSurvivesRecipeApply() {
        Fixture f = new Fixture();
        FakeEntity entity = f.add("host").tag("wreck:shared");
        entity.recipe = recipe(record("wreck:shared", "host"), "trap", false);
        CoopWreckRecord before = f.world.captureHost().get(0);
        CoopWreckMutation activation = new CoopWreckMutation("trap-once", 0, recipe(before, null, true));
        f.world.applyMutation(activation); f.world.applyMutation(activation);
        assertEquals(1, f.codec.trapCalls);
        assertEquals(Boolean.TRUE, entity.saved.get(CoopWreckWorld.TRAP_APPLIED));
        assertNull(entity.recipe.recovery());
    }

    @Test
    void anUncertainTrapFailureNeverAcknowledgesCommitOrRetriesTheSideEffectBlindly() {
        Fixture f = new Fixture();
        FakeEntity entity = f.add("host").tag("wreck:shared");
        entity.recipe = recipe(record("wreck:shared", "host"), "trap", false);
        CoopWreckRecord before = f.world.captureHost().get(0);
        f.codec.failTrap = true;
        CoopWreckMutation activation = new CoopWreckMutation("trap-failed", 0, recipe(before, null, true));
        assertThrows(IllegalStateException.class, () -> f.world.applyMutation(activation));
        assertThrows(IllegalStateException.class, () -> f.world.applyMutation(activation));
        assertEquals(1, f.codec.trapCalls);
        assertFalse(entity.saved.containsKey(CoopWreckWorld.TRAP_APPLIED));
        assertFalse(entity.saved.containsKey(CoopWreckWorld.LAST_MUTATION));
        assertEquals("trap", entity.recipe.recovery().description());
    }

    @Test
    void aReplayedAcknowledgementCannotRebaseQueuedLootAcrossANewerHostRecipe() {
        Fixture f = new Fixture();
        CoopWreckRecord before = recipe(record("wreck:shared", "host"), "original", false);
        f.world.upsert(before, 0);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        entity.recipe = recipe(before, "first-result", false); f.world.observeMutations();
        entity.recipe = recipe(before, null, false); f.world.observeMutations();
        List<CoopWreckMutation> pending = f.world.pendingMutations();
        CoopWreckRecord newerHost = recipe(before, "newer-host-special", false);

        assertThrows(IllegalStateException.class, () -> f.world.acknowledgeMutation(new CoopWreckMutation.Ack(
                pending.get(0).mutationId(), before.id(), CoopWreckMutation.Status.ACCEPTED, 2, newerHost)));

        assertEquals(pending, f.world.pendingMutations());
        assertNull(entity.recipe.recovery());
        assertEquals(0, f.world.revision(before.id()));
    }

    @Test
    void nativeTimerExpiryAloneCreatesNeitherGuestMutationsNorHostRevisionConflicts() {
        Fixture f = new Fixture();
        CoopWreckRecord before = timed(record("wreck:shared", "host"), true);
        f.world.upsert(before, 0);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        entity.recipe = timed(before, false);
        f.world.observeMutations();
        assertTrue(f.world.pendingMutations().isEmpty());
        f.world.captureHost();
        assertEquals(0, f.world.revision(before.id()));
    }

    @Test
    void actualDialogConsumptionOfTimedCargoRemainsASharedMutation() {
        Fixture f = new Fixture();
        CoopWreckRecord before = timed(record("wreck:shared", "host"), true);
        f.world.upsert(before, 0);
        FakeEntity entity = f.byEntity.get(f.entities.get(0));
        f.dialogTarget = entity.entity; f.world.observeMutations();
        entity.recipe = timed(before, false);
        f.dialogTarget = null; f.world.observeMutations();
        assertEquals(1, f.world.pendingMutations().size());
        assertTrue(f.world.pendingMutations().get(0).interacted());
    }

    @Test
    void lifetimeHistoryLimitNeverEvictsAnOldTombstone() {
        Fixture f = new Fixture();
        StringBuilder encoded = new StringBuilder("[");
        for (int i = 0; i < CoopWreckWorld.MAX_CONSUMED_IDS; i++) {
            if (i != 0) encoded.append(',');
            encoded.append('"').append(CoopJson.escape("wreck:old-" + i)).append('"');
        }
        encoded.append(']');
        String full = encoded.toString();
        f.saved.put(CoopWreckWorld.CONSUMED_IDS, full);

        assertThrows(IllegalStateException.class, () -> f.world.markConsumed("wreck:new"));
        assertSame(full, f.saved.get(CoopWreckWorld.CONSUMED_IDS));
        assertEquals(CoopWreckWorld.MAX_CONSUMED_IDS, f.world.consumedIds().size());
    }

    private static CoopWreckRecord record(String id, String engineId) {
        return new CoopWreckRecord(1, id, engineId, "Derelict", "neutral", 1, 2, 3, 4,
                0, 0, 0, 100, null, true,
                new CoopWreckRecord.Ship("AVERAGE", "hound_Standard", null, null,
                        null, null, true, true, null, 0),
                17L, null, null, List.of(), List.of(),
                CoopWreckRecord.ExtraCargo.absent(), CoopWreckRecord.ExtraCargo.absent(),
                null, null, 0, true, 20, List.of(), List.of(), List.of());
    }

    private static CoopWreckRecord recipe(CoopWreckRecord r, String description, boolean trap) {
        return new CoopWreckRecord(r.version(), r.id(), r.hostEntityId(), r.name(), r.factionId(),
                r.x(), r.y(), r.vx(), r.vy(), r.facing(), r.angularVelocity(), r.elapsedDays(), r.durationDays(),
                r.expiring(), r.canHaveExtraCargo(), r.ship(), r.salvageSeed(), description == null ? null
                : new CoopWreckRecord.Recovery(description, null, null, null, List.of(r.ship())), r.previousRecovery(),
                r.dropValue(), r.dropRandom(), r.extraSalvage(), r.temporaryExtraSalvage(), r.discoveryXP(), r.salvageXP(),
                r.sensorProfile(), r.discoverable(), r.radius(), r.detectionModifiers(), r.tags(), trap
                ? List.of(new CoopWreckRecord.MemoryValue(CoopWreckWorld.TRAP_ACTIVATED, "BOOLEAN", "true", -1)) : List.of());
    }

    private static CoopWreckRecord timed(CoopWreckRecord r, boolean present) {
        return new CoopWreckRecord(r.version(), r.id(), r.hostEntityId(), r.name(), r.factionId(),
                r.x(), r.y(), r.vx(), r.vy(), r.facing(), r.angularVelocity(), r.elapsedDays(), r.durationDays(),
                r.expiring(), r.canHaveExtraCargo(), r.ship(), r.salvageSeed(), r.recovery(), r.previousRecovery(),
                r.dropValue(), r.dropRandom(), present ? new CoopWreckRecord.ExtraCargo(true, 0.01f,
                List.of(new CoopWreckRecord.Cargo("RESOURCES", "supplies", null, 10))) : CoopWreckRecord.ExtraCargo.absent(),
                CoopWreckRecord.ExtraCargo.absent(), r.discoveryXP(), r.salvageXP(), r.sensorProfile(), r.discoverable(),
                r.radius(), r.detectionModifiers(), r.tags(), present ? List.of(new CoopWreckRecord.MemoryValue(
                com.fs.starfarer.api.impl.campaign.ids.MemFlags.RECENTLY_SALVAGED, "BOOLEAN", "true", 0.01f)) : List.of());
    }

    private static final class QuestWreck extends DerelictShipEntityPlugin { }

    private static final class Fixture {
        final List<SectorEntityToken> entities = new ArrayList<>();
        final Map<String, Object> saved = new HashMap<>();
        final Map<CustomCampaignEntityAPI, FakeEntity> byEntity = new HashMap<>();
        final LocationAPI hyper;
        final LocationAPI system;
        final SectorAPI sector;
        final FakeCodec codec = new FakeCodec();
        final CoopWreckWorld world;
        SectorEntityToken dialogTarget;
        int memoryWrites;

        Fixture() {
            hyper = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isHyperspace" -> true;
                case "getAllEntities" -> new ArrayList<>(entities);
                case "removeEntity" -> { entities.remove(args[0]); yield null; }
                default -> throw unexpected(method.getName());
            });
            system = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isHyperspace" -> false;
                default -> throw unexpected(method.getName());
            });
            MemoryAPI memory = memory(saved, () -> memoryWrites++);
            InteractionDialogAPI dialog = proxy(InteractionDialogAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getInteractionTarget" -> dialogTarget;
                default -> throw unexpected(method.getName());
            });
            CampaignUIAPI ui = proxy(CampaignUIAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getCurrentInteractionDialog" -> dialogTarget == null ? null : dialog;
                default -> throw unexpected(method.getName());
            });
            sector = proxy(SectorAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getHyperspace" -> hyper;
                case "getMemoryWithoutUpdate" -> memory;
                case "getCampaignUI" -> ui;
                default -> throw unexpected(method.getName());
            });
            world = new CoopWreckWorld(() -> sector, codec);
        }

        FakeEntity add(String id) {
            FakeEntity entity = new FakeEntity(this, id);
            entities.add(entity.entity); byEntity.put(entity.entity, entity);
            return entity;
        }

        final class FakeCodec implements CoopWreckWorld.NativeCodec {
            final Set<String> captureFailures = new HashSet<>();
            final List<CustomCampaignEntityAPI> applied = new ArrayList<>();
            boolean failCreate;
            boolean failTrap;
            int trapCalls;
            int creates;
            @Override public CoopWreckRecord capture(CustomCampaignEntityAPI entity, String id) {
                if (captureFailures.contains(entity.getId())) throw new IllegalStateException("Unsupported recipe");
                FakeEntity state = byEntity.get(entity);
                CoopWreckRecord target = record(id, entity.getId());
                return state.recipe == null ? target : new CoopWreckMutation("capture", 0, state.recipe, true).onto(target);
            }
            @Override public CustomCampaignEntityAPI create(LocationAPI location, CoopWreckRecord record) {
                if (failCreate) throw new IllegalStateException("Could not create recipe");
                creates++;
                return add("guest-created-" + creates).entity;
            }
            @Override public void apply(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
                applied.add(entity);
                byEntity.get(entity).nativeRecovery = record.ship().condition();
                byEntity.get(entity).recipe = record;
            }
            @Override public boolean matchesLegacy(CustomCampaignEntityAPI entity, CoopWreckRecord record) {
                return entity.getId().equals(record.hostEntityId());
            }
            @Override public void activateTrap(CustomCampaignEntityAPI entity) {
                trapCalls++;
                if (failTrap) throw new IllegalStateException("Native trap effect failed");
            }
        }
    }

    private static final class FakeEntity {
        final Map<String, Object> saved = new HashMap<>();
        final Set<String> tags = new LinkedHashSet<>();
        final CustomCampaignEntityAPI entity;
        Object plugin = new DerelictShipEntityPlugin();
        boolean inHyper = true;
        boolean alive = true;
        float discovery;
        String nativeRecovery = "native";
        CoopWreckRecord recipe;

        FakeEntity(Fixture f, String id) {
            MemoryAPI memory = memory(saved, () -> { });
            entity = proxy(CustomCampaignEntityAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "getCustomEntityType" -> Entities.WRECK;
                case "getContainingLocation" -> inHyper ? f.hyper : f.system;
                case "getCustomPlugin" -> plugin;
                case "getMemoryWithoutUpdate" -> memory;
                case "isAlive" -> alive;
                case "hasTag" -> tags.contains(args[0]);
                default -> throw unexpected(method.getName());
            });
        }
        FakeEntity tag(String id) { saved.put(CoopWreckWorld.WRECK_ID, id); return this; }
    }

    private static MemoryAPI memory(Map<String, Object> values, Runnable onWrite) {
        return proxy(MemoryAPI.class, (self, method, args) -> switch (method.getName()) {
            case "get" -> values.get(args[0]);
            case "set" -> { values.put((String) args[0], args[1]); onWrite.run(); yield null; }
            case "unset" -> { values.remove(args[0]); yield null; }
            default -> throw unexpected(method.getName());
        });
    }

    private static AssertionError unexpected(String method) { return new AssertionError("Unexpected engine call " + method); }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString" -> type.getSimpleName() + " test proxy";
                default -> throw unexpected(method.getName());
            };
            return handler.invoke(self, method, args);
        }));
    }
}
