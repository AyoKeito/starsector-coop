package coop.save;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopGuestSnapshotStoreTest {

    @AfterEach
    void clearStore() {
        CoopGuestSnapshotStore.clear();
    }

    @Test
    void theLatestPublishedSnapshotIsTheOneWritten() {
        CoopGuestSnapshot first = named("first");
        CoopGuestSnapshot second = named("second");
        Map<String, Object> persistentData = new HashMap<>();

        CoopGuestSnapshotStore.publish(first);
        CoopGuestSnapshotStore.publish(second);

        assertTrue(CoopGuestSnapshotStore.writeInto(persistentData));
        assertSame(second, persistentData.get(CoopGuestSnapshotStore.PERSISTENT_KEY));
    }

    @Test
    void withoutASnapshotTheExistingSaveEntryIsLeftAlone() {
        // An older snapshot from a previous session beats none at all: this is recovery material, and
        // a host that saves before the guest connects must not wipe what the last session left.
        CoopGuestSnapshot previousSession = named("previous");
        Map<String, Object> persistentData = new HashMap<>();
        persistentData.put(CoopGuestSnapshotStore.PERSISTENT_KEY, previousSession);

        assertFalse(CoopGuestSnapshotStore.writeInto(persistentData));

        assertSame(previousSession, persistentData.get(CoopGuestSnapshotStore.PERSISTENT_KEY));
    }

    @Test
    void clearDropsTheHeldSnapshotOnly() {
        CoopGuestSnapshot snapshot = named("held");
        Map<String, Object> persistentData = new HashMap<>();
        CoopGuestSnapshotStore.publish(snapshot);
        CoopGuestSnapshotStore.writeInto(persistentData);

        CoopGuestSnapshotStore.clear();

        assertNull(CoopGuestSnapshotStore.latest());
        // The copy already written into a save is untouched: clearing it would throw away the only one.
        assertSame(snapshot, persistentData.get(CoopGuestSnapshotStore.PERSISTENT_KEY));
    }

    @Test
    void theDocumentedKeyIsPinned() {
        // README_DEV.md documents this key as deliberately write-only; renaming it silently would
        // strand every existing host save's recovery material.
        assertEquals("coop.guestFleetSnapshot", CoopGuestSnapshotStore.PERSISTENT_KEY);
    }

    private static CoopGuestSnapshot named(String playerName) {
        CoopGuestSnapshot snapshot = new CoopGuestSnapshot();
        snapshot.setPlayerName(playerName);
        return snapshot;
    }
}
