package coop.save;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.util.Map;

/**
 * Holds the most recent {@link CoopGuestSnapshot} the host received, and writes it into the host
 * save (Phase 16).
 *
 * <p>Static, because the write happens from {@code CoopModPlugin.beforeGameSave()} — a
 * {@code ModPlugin} callback with no handle on the campaign pump — the same arrangement
 * {@code CoopFullFidelitySystemDriver.beginSave()} uses for the same reason.
 *
 * <p><b>{@link #PERSISTENT_KEY} is write-only in v1, by decision (2026-06-10).</b> Nothing reads it
 * back. It is the raw material for recovering a guest who loses their save: the fleet, cargo,
 * credits and officers the host last saw. Documented in {@code README_DEV.md} so a later audit does
 * not flag it as dead state and delete it.
 */
public final class CoopGuestSnapshotStore {

    /** The sector persistent-data key holding the guest's snapshot inside the host save. */
    public static final String PERSISTENT_KEY = "coop.guestFleetSnapshot";

    private static volatile CoopGuestSnapshot latest;

    private CoopGuestSnapshotStore() {
    }

    /** Host: record the guest state that arrived on the wire. Latest wins; there is no history. */
    public static void publish(CoopGuestSnapshot snapshot) {
        latest = snapshot;
    }

    /** The snapshot that would be embedded by the next save, or null when none has arrived. */
    public static CoopGuestSnapshot latest() {
        return latest;
    }

    /**
     * Drops the held snapshot (game load, session end). Deliberately does <em>not</em> remove the key
     * from any save: a stale guest snapshot in an old save is exactly the recovery material this
     * exists for, and clearing it would throw away the only copy.
     */
    public static void clear() {
        latest = null;
    }

    /**
     * Writes the held snapshot into {@code persistentData} under {@link #PERSISTENT_KEY}. A missing
     * snapshot leaves whatever is already stored alone — an older snapshot beats none at all.
     *
     * @return true when a snapshot was written.
     */
    public static boolean writeInto(Map<String, Object> persistentData) {
        CoopGuestSnapshot snapshot = latest;
        if (persistentData == null || snapshot == null) {
            return false;
        }
        persistentData.put(PERSISTENT_KEY, snapshot);
        return true;
    }

    /**
     * Engine-facing form for {@code CoopModPlugin.beforeGameSave()}. Never throws: a snapshot that
     * cannot be embedded must not be able to fail the player's save.
     */
    public static void writeIntoCurrentSector() {
        try {
            CoopGuestSnapshot snapshot = latest;
            if (snapshot == null) {
                return;
            }
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            if (writeInto(sector.getPersistentData())) {
                CoopLog.info(CoopGuestSnapshotStore.class, "Coop embedded the guest snapshot in this"
                        + " save under " + PERSISTENT_KEY + ": " + snapshot.summary());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopGuestSnapshotStore.class,
                    "Failed to embed the coop guest snapshot in the save", ex);
        }
    }
}
