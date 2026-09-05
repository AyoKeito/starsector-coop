package coop.campaign;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;

import coop.util.CoopLog;

/**
 * Phase 32: the "is this market's storage open to the coop crew" question, answered in one place.
 *
 * <p>Vanilla keeps the answer in {@code StoragePlugin.playerPaidToUnlock}, a private field with a
 * setter and <em>no getter</em>, so the shop UI is the only thing that can ask. Two facts of the
 * mod's world make that insufficient: (1) the unlock is paid once by either player and must open
 * the locker on both engines, and (2) the guest's engine rebuilds mirrored colonies and bases, so
 * the plugin instance on one side may be younger than the unlock. The mod therefore holds its own
 * per-market flag in sector persistent data ({@link #FLAG_PREFIX}{@code <marketId>}, host
 * authoritative, saved with the game) and treats the plugin field as the local truth the flag is
 * reconciled with.
 *
 * <p>The private field is read through {@link MethodHandles}: the script classloader hard-blocks
 * {@code java.lang.reflect} but not {@code java.lang.invoke} (the {@code CoopBarSync} pattern).
 * Every read is defensive: a failure to resolve the handle logs once and reads as "not paid", so a
 * future engine rename degrades to "the coop flag alone decides" rather than to a crash.
 */
public final class CoopStorageUnlock {

    /** Persistent-data key prefix; the market id follows. Value is {@link Boolean#TRUE}. */
    public static final String FLAG_PREFIX = "coop.storageUnlocked:";

    private static MethodHandle paidGetter;
    private static boolean getterResolved;
    private static boolean getterWarned;

    private CoopStorageUnlock() {
    }

    public static String flagKey(String marketId) {
        return FLAG_PREFIX + marketId;
    }

    /** True when the coop flag for this market is set in persistent data. */
    public static boolean flagSet(SectorAPI sector, String marketId) {
        Map<String, Object> data = persistentData(sector);
        return data != null && marketId != null && Boolean.TRUE.equals(data.get(flagKey(marketId)));
    }

    /** Sets the coop flag. Returns true when the flag was not set before. */
    public static boolean setFlag(SectorAPI sector, String marketId) {
        Map<String, Object> data = persistentData(sector);
        if (data == null || marketId == null || marketId.isEmpty()) {
            return false;
        }
        return data.put(flagKey(marketId), Boolean.TRUE) == null;
    }

    /**
     * Clears the coop flag. Returns true when it was set.
     *
     * <p>Exists for one case (Phase 32 addition A): a {@code STORAGE_UNLOCK} for a hidden base that
     * arrived before {@code CoopBaseAuthority} had paired that base is flagged under the <em>host's</em>
     * market id, which names no market on this engine. When the pairing lands the flag is moved to
     * the local id, and leaving the host-id key behind would put a phantom market into
     * {@link #flaggedMarketIds(SectorAPI)} -- which the host resends as its session-start baseline,
     * so the phantom would propagate rather than die.
     */
    public static boolean clearFlag(SectorAPI sector, String marketId) {
        Map<String, Object> data = persistentData(sector);
        if (data == null || marketId == null || marketId.isEmpty()) {
            return false;
        }
        return data.remove(flagKey(marketId)) != null;
    }

    /**
     * Every market id the coop flag is set for, sorted so the order does not depend on a
     * {@code HashMap}'s iteration. The host's session-start baseline resend reads this: a guest
     * joining a campaign where unlocks were paid before it arrived has no other way to learn about
     * them, since nothing in the save carries them across the wire.
     *
     * <p>Snapshots the key set before walking it, so a poll running in the same frame as a write
     * cannot throw {@code ConcurrentModificationException} out of a baseline.
     */
    public static List<String> flaggedMarketIds(SectorAPI sector) {
        Map<String, Object> data = persistentData(sector);
        if (data == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try {
            for (String key : new ArrayList<>(data.keySet())) {
                if (key == null || !key.startsWith(FLAG_PREFIX)
                        || !Boolean.TRUE.equals(data.get(key))) {
                    continue;
                }
                String marketId = key.substring(FLAG_PREFIX.length());
                if (!marketId.isEmpty()) {
                    ids.add(marketId);
                }
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopStorageUnlock.class, "Could not list unlocked storage markets", ex);
            return List.of();
        }
        Collections.sort(ids);
        return ids;
    }

    /** The market's storage submarket, or null when it has none. */
    public static SubmarketAPI storageSubmarket(MarketAPI market) {
        if (market == null || !market.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) {
            return null;
        }
        return market.getSubmarket(Submarkets.SUBMARKET_STORAGE);
    }

    /**
     * Reads the local engine's {@code StoragePlugin.playerPaidToUnlock}. False when the market has
     * no storage, the plugin is not a {@link StoragePlugin}, or the handle cannot be resolved.
     */
    public static boolean pluginPaid(MarketAPI market) {
        SubmarketAPI storage = storageSubmarket(market);
        SubmarketPlugin plugin = storage == null ? null : storage.getPlugin();
        if (!(plugin instanceof StoragePlugin storagePlugin)) {
            return false;
        }
        try {
            MethodHandle getter = paidGetter();
            return getter != null && (boolean) getter.invoke(storagePlugin);
        } catch (Throwable ex) {
            warnOnce("Could not read StoragePlugin.playerPaidToUnlock", ex);
            return false;
        }
    }

    /** Writes {@code setPlayerPaidToUnlock(true)} on the local plugin when there is one. */
    public static void unlockPlugin(MarketAPI market) {
        SubmarketAPI storage = storageSubmarket(market);
        SubmarketPlugin plugin = storage == null ? null : storage.getPlugin();
        if (plugin instanceof StoragePlugin storagePlugin) {
            storagePlugin.setPlayerPaidToUnlock(true);
        }
    }

    /** The coop answer: the persistent flag, or failing that the local plugin's own field. */
    public static boolean isUnlocked(SectorAPI sector, MarketAPI market) {
        if (market == null) {
            return false;
        }
        return flagSet(sector, market.getId()) || pluginPaid(market);
    }

    /**
     * Opens storage on this engine: sets the coop flag and the plugin field. Returns true when the
     * coop flag was newly set (the caller's cue to tell the other engine).
     */
    public static boolean unlock(SectorAPI sector, MarketAPI market) {
        if (market == null) {
            return false;
        }
        boolean newlyFlagged = setFlag(sector, market.getId());
        unlockPlugin(market);
        return newlyFlagged;
    }

    private static Map<String, Object> persistentData(SectorAPI sector) {
        try {
            return sector == null ? null : sector.getPersistentData();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static synchronized MethodHandle paidGetter() throws Throwable {
        if (!getterResolved) {
            getterResolved = true;
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(StoragePlugin.class, MethodHandles.lookup());
            paidGetter = priv.findGetter(StoragePlugin.class, "playerPaidToUnlock", boolean.class);
        }
        return paidGetter;
    }

    private static synchronized void warnOnce(String text, Throwable ex) {
        if (!getterWarned) {
            getterWarned = true;
            CoopLog.warn(CoopStorageUnlock.class, text, ex);
        }
    }
}
