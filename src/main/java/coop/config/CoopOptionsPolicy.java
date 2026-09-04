package coop.config;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Phase 28 milestone 2: the host-authoritative {@link CoopOptionsRegistry.Tier#POLICY} values for
 * <em>this campaign</em>, plus the pending/applied split that keeps a change from applying
 * retroactively.
 *
 * <h2>Where the values live</h2>
 *
 * <p>On the host, in {@code sector.getPersistentData()} under {@code coop.options.&lt;key&gt;} as
 * plain strings, right next to {@code coop.campaignId}. Plain strings mean no XStream alias is
 * needed and no class of ours reaches the save - the same reason the seed markers are stored that
 * way. So a campaign's rules travel with its save, which is the decision the plan settled: a host
 * running two campaigns can play them under different rules.
 *
 * <p><b>Seeding.</b> A campaign with no stored policy takes its values from the host's install-level
 * {@link CoopOptionsStore} ({@code -D} &rarr; {@code saves/common} &rarr; shipped defaults) on the
 * first host frame that has a sector, and writes them down. From then on the stored value wins: an
 * install default is a starting point, not a standing override, or editing the settings file would
 * silently rewrite the rules of a campaign already in progress.
 *
 * <p><b>The guest never reads a file for these.</b> Its view is populated only by
 * {@code OPTIONS_SNAPSHOT} and is read-only ({@link #effective(String)}); before the first snapshot
 * it reads registry defaults. A guest whose own {@code coop_options.json} says
 * {@code pauseOnGuestScreens=false} still plays under whatever the host set, which is the whole
 * point of the tier.
 *
 * <h2>Pending vs applied</h2>
 *
 * <p>Every key declares an {@link CoopOptionsRegistry.ApplyBoundary}. {@link #effective(String)} is
 * the current policy - what the page shows and what the wire carries. {@link #applied(String)} is
 * what a <em>consumer</em> must read, and it only catches up when the consumer calls
 * {@link #advanceBoundary(String)} at its declared boundary. {@link CoopOptionsRegistry.ApplyBoundary#IMMEDIATE}
 * keys promote on the spot, so for them the two are always equal.
 *
 * <p>This is what makes the acceptance criterion true: flipping {@code coop.pauseOnGuestScreens} to
 * {@code false} while the guest is reading the map does not yank the pause out from under it. The
 * guest's pump advances that boundary only when no screen is open.
 *
 * <h2>Inert keys</h2>
 *
 * <p>Five policy keys have no consumer in this build - {@code coop.lootSplit},
 * {@code coop.incomeSplit}, {@code coop.guestColonizationConsent}, {@code coop.allowMidSessionJoin}
 * and {@code coop.allowGuestPause}. They are stored, synced and displayed so the page and the file
 * are a complete reference, and the owning phase (22/24/25/27) wires the consumer when it builds.
 * {@code coop.maxGuests} and {@code coop.reconnectGraceSeconds} are a different kind of inert: they
 * are read at launch by {@code CoopNetStartupConfig}, before any campaign policy exists, and
 * deliberately stay there (see the milestone 2 notes in the plan).
 *
 * <h2>Failure policy</h2>
 *
 * <p>Nothing here may take a frame down. Every engine touch is wrapped; a persistent-data read or
 * write that fails degrades to "in memory only" with one WARN, because a session that keeps running
 * under the right rules and forgets them at save time is strictly better than a crash.
 */
public final class CoopOptionsPolicy {

    /** Persistent-data key prefix; the full key is this plus the registry key. */
    public static final String PERSIST_PREFIX = "coop.options.";

    /**
     * Persistent-data key for the monotonic version counter. Deliberately <em>not</em> under
     * {@link #PERSIST_PREFIX}: everything under that prefix is a registry key, and a bookkeeping
     * field that looked like one would be read back as an unknown option.
     */
    public static final String PERSIST_VERSION_KEY = "coop.optionsPolicyVersion";

    /** The version a freshly seeded campaign starts at. Restarts at 1 per campaign, by design. */
    public static final int FIRST_VERSION = 1;

    /**
     * {@link #lastChangedKey()} for a {@link #resetToDefaults()} that moved more than one key, and so
     * the {@code changedKey} the snapshot carries for it.
     *
     * <p>A sentinel rather than {@code ""} because empty already means "this send is an establish or
     * resume broadcast, narrate nothing", and a reset must be narrated on both sides. Cannot collide
     * with a registry key: every one of those starts with {@code coop.}.
     */
    public static final String RESET_MARKER = "*reset*";

    private static volatile CoopOptionsPolicy active;

    private final BooleanSupplier hostAuthority;
    private final Supplier<Map<String, Object>> persistentData;

    /** The authoritative current value per policy key; never missing a key. */
    private final Map<String, String> effective = new LinkedHashMap<>();

    /** What consumers see; catches up to {@link #effective} at each key's boundary. */
    private final Map<String, String> applied = new LinkedHashMap<>();

    private int version;
    private boolean seeded;
    private String lastChangedKey = "";
    private boolean persistFailureLogged;

    public CoopOptionsPolicy(BooleanSupplier hostAuthority) {
        this(hostAuthority, CoopOptionsPolicy::sectorPersistentData);
    }

    /**
     * @param hostAuthority  true when this client owns the policy (it is the host)
     * @param persistentData the sector's persistent data map, or a supplier returning {@code null}
     *                       when there is no sector yet; injectable so the whole class tests
     *                       headless
     */
    public CoopOptionsPolicy(BooleanSupplier hostAuthority,
                             Supplier<Map<String, Object>> persistentData) {
        this.hostAuthority = Objects.requireNonNull(hostAuthority, "hostAuthority");
        this.persistentData = Objects.requireNonNull(persistentData, "persistentData");
        resetToRegistryDefaults();
    }

    // ---- static handle ---------------------------------------------------------------------------

    /**
     * Installs the live policy so the options page (constructed by the intel screen, not by us) can
     * find it. Newest pump wins, exactly like {@code CoopSessionIntelFeed}.
     */
    public static void install(CoopOptionsPolicy policy) {
        active = policy;
    }

    /** Session teardown: the page falls back to install-level values. */
    public static void uninstall() {
        active = null;
    }

    /** The live policy, or {@code null} when no pump has installed one. */
    public static CoopOptionsPolicy active() {
        return active;
    }

    /**
     * The value a consumer outside the pump should read for {@code key}: the live policy's
     * <em>applied</em> value when there is one, and the registry default when there is not.
     *
     * <p>Deliberately the applied value rather than {@link #effective(String)}: a consumer that
     * reaches for the policy statically is exactly the caller that has no boundary of its own to
     * advance, so handing it the pending value would be the retroactive apply this class exists to
     * prevent.
     */
    public static String appliedOrDefault(String key) {
        CoopOptionsPolicy policy = active;
        if (policy != null) {
            return policy.applied(key);
        }
        return CoopOptionsRegistry.require(key).defaultValue();
    }

    // ---- the policy keys -------------------------------------------------------------------------

    /** Every {@link CoopOptionsRegistry.Tier#POLICY} option, in registration order. */
    public static List<CoopOptionsRegistry.Option> policyOptions() {
        return CoopOptionsRegistry.byTier(CoopOptionsRegistry.Tier.POLICY);
    }

    /** True when {@code key} is a registered policy key (and so travels in the snapshot). */
    public static boolean isPolicyKey(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.option(key);
        return option != null && option.tier() == CoopOptionsRegistry.Tier.POLICY;
    }

    private static CoopOptionsRegistry.Option requirePolicy(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        if (option.tier() != CoopOptionsRegistry.Tier.POLICY) {
            throw new IllegalArgumentException(key + " is not a policy-tier option");
        }
        return option;
    }

    // ---- reads -----------------------------------------------------------------------------------

    /** The current policy value: what the page shows and what the snapshot carries. */
    public synchronized String effective(String key) {
        requirePolicy(key);
        return effective.get(key);
    }

    /** {@link #effective(String)} as a boolean. */
    public synchronized boolean effectiveBool(String key) {
        return Boolean.parseBoolean(effective(key));
    }

    /**
     * The value a consumer must act on. Equal to {@link #effective(String)} except between a change
     * and the next {@link #advanceBoundary(String)} for keys whose boundary is not
     * {@link CoopOptionsRegistry.ApplyBoundary#IMMEDIATE}.
     */
    public synchronized String applied(String key) {
        requirePolicy(key);
        return applied.get(key);
    }

    /** {@link #applied(String)} as a boolean. */
    public synchronized boolean appliedBool(String key) {
        return Boolean.parseBoolean(applied(key));
    }

    /** True when a change is waiting for this key's boundary. */
    public synchronized boolean hasPendingChange(String key) {
        requirePolicy(key);
        return !Objects.equals(effective.get(key), applied.get(key));
    }

    /** True when any key is waiting for its boundary. What the guest's acknowledgement gates on. */
    public synchronized boolean hasPendingChanges() {
        for (CoopOptionsRegistry.Option option : policyOptions()) {
            if (!Objects.equals(effective.get(option.key()), applied.get(option.key()))) {
                return true;
            }
        }
        return false;
    }

    /** Every policy key and its current value, in registration order; the snapshot body. */
    public synchronized Map<String, String> values() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(effective));
    }

    /** Monotonic per campaign; 0 until seeded or until the first snapshot lands. */
    public synchronized int version() {
        return version;
    }

    /** The key changed by the most recent {@link #set} or snapshot; "" when none. */
    public synchronized String lastChangedKey() {
        return lastChangedKey;
    }

    /** True once this campaign's policy has been read from (or written to) persistent data. */
    public synchronized boolean seeded() {
        return seeded;
    }

    // ---- boundaries ------------------------------------------------------------------------------

    /**
     * The consumer's half of the apply-boundary contract: "my boundary just came round". Promotes
     * the pending value to the applied one.
     *
     * @return true when the applied value actually moved
     */
    public synchronized boolean advanceBoundary(String key) {
        requirePolicy(key);
        String pending = effective.get(key);
        if (Objects.equals(pending, applied.get(key))) {
            return false;
        }
        applied.put(key, pending);
        CoopLog.info(CoopOptionsPolicy.class, "Coop option " + key + " now in effect: " + pending);
        return true;
    }

    /**
     * Host: the guest reports that its own boundaries have been crossed for {@code ackVersion}.
     *
     * <p>The host has no screen of its own that the pending keys govern, so it has no boundary to
     * cross; what it is waiting for is the guest, and this is the guest saying so. An acknowledgement
     * that is not for the version currently held is refused - the host has changed something since,
     * and the newer change is still owed its own boundary.
     *
     * @return true when an applied value actually moved
     */
    public synchronized boolean acknowledgeApplied(int ackVersion) {
        if (ackVersion != version) {
            return false;
        }
        return promoteAll();
    }

    /**
     * Host with no guest to wait for: promote everything now. A pending change with nobody on the
     * other end would otherwise sit on the options page forever.
     *
     * @return true when an applied value actually moved
     */
    public synchronized boolean acknowledgeAllApplied() {
        return promoteAll();
    }

    private boolean promoteAll() {
        boolean moved = false;
        for (CoopOptionsRegistry.Option option : policyOptions()) {
            if (advanceBoundary(option.key())) {
                moved = true;
            }
        }
        return moved;
    }

    // ---- host writes -----------------------------------------------------------------------------

    /**
     * Host: change one policy value. Validated through the registry, so a bad value is clamped or
     * refused rather than stored.
     *
     * <p>Refused on a guest - the guest's view is read-only by construction, and this returning
     * false rather than throwing is what lets the page render the row without a button instead of
     * an error.
     *
     * @return true when the stored value actually changed (and so a snapshot is owed)
     */
    public synchronized boolean set(String key, String value) {
        CoopOptionsRegistry.Option option = requirePolicy(key);
        if (!isHost()) {
            CoopLog.warn(CoopOptionsPolicy.class,
                    "Ignoring a guest-side attempt to set the host policy " + key);
            return false;
        }
        CoopOptionsRegistry.Coercion coercion = option.coerce(value);
        if (!coercion.clean()) {
            CoopLog.warn(CoopOptionsPolicy.class, "Coop options: " + coercion.warning());
        }
        String next = coercion.value();
        if (next.equals(effective.get(key))) {
            return false;
        }
        effective.put(key, next);
        if (option.boundary() == CoopOptionsRegistry.ApplyBoundary.IMMEDIATE) {
            applied.put(key, next);
        }
        version = nextVersion(version);
        lastChangedKey = key;
        persist();
        CoopLog.info(CoopOptionsPolicy.class, "Coop policy " + key + "=" + next
                + " (v" + version + ", applies " + option.appliesAt() + ")");
        return true;
    }

    /**
     * Host: put every policy key back to the value the registry ships. One version bump for the
     * whole sweep, because it is one player action.
     *
     * @return the keys that actually changed
     */
    public synchronized List<String> resetToDefaults() {
        if (!isHost()) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        for (CoopOptionsRegistry.Option option : policyOptions()) {
            String value = option.defaultValue();
            if (value.equals(effective.get(option.key()))) {
                continue;
            }
            effective.put(option.key(), value);
            if (option.boundary() == CoopOptionsRegistry.ApplyBoundary.IMMEDIATE) {
                applied.put(option.key(), value);
            }
            changed.add(option.key());
        }
        if (changed.isEmpty()) {
            return changed;
        }
        version = nextVersion(version);
        lastChangedKey = changed.size() == 1 ? changed.get(0) : RESET_MARKER;
        persist();
        CoopLog.info(CoopOptionsPolicy.class, "Coop policy reset to defaults (v" + version + "): "
                + String.join(", ", changed));
        return changed;
    }

    // ---- seeding ---------------------------------------------------------------------------------

    /**
     * Host, once per campaign: read the stored policy, and seed anything missing from {@code store}
     * (the install-level stack). Idempotent and cheap to call every frame - it returns immediately
     * once seeded, and it refuses to latch while there is no sector to read.
     *
     * @return true when the policy is now seeded
     */
    public synchronized boolean ensureSeeded(CoopOptionsStore store) {
        if (seeded) {
            return true;
        }
        if (!isHost()) {
            return false;
        }
        Map<String, Object> data = data();
        if (data == null) {
            // No sector yet. Deliberately not latched: seeding against no campaign would freeze the
            // registry defaults in as this campaign's rules.
            return false;
        }
        boolean wroteAnything = false;
        for (CoopOptionsRegistry.Option option : policyOptions()) {
            String stored = readStored(data, option);
            String value;
            if (stored != null) {
                // A stored value wins over the install default, always: these are this campaign's
                // rules and editing coop_options.json must not rewrite a campaign in progress.
                value = option.coerce(stored).value();
            } else {
                value = seedValue(store, option);
                wroteAnything = true;
            }
            effective.put(option.key(), value);
            applied.put(option.key(), value);
        }
        Integer storedVersion = readStoredVersion(data);
        version = storedVersion == null ? FIRST_VERSION : Math.max(FIRST_VERSION, storedVersion);
        seeded = true;
        if (wroteAnything || storedVersion == null) {
            persist();
        }
        CoopLog.info(CoopOptionsPolicy.class, "Coop campaign policy " + (wroteAnything
                ? "seeded from install defaults" : "loaded from the save") + " (v" + version + ")");
        return true;
    }

    private String seedValue(CoopOptionsStore store, CoopOptionsRegistry.Option option) {
        if (store == null) {
            return option.defaultValue();
        }
        try {
            return option.coerce(store.string(option.key())).value();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopOptionsPolicy.class, "Could not seed " + option.key()
                    + " from the settings stack; using the built-in default", ex);
            return option.defaultValue();
        }
    }

    // ---- guest view ------------------------------------------------------------------------------

    /** What {@link #applySnapshot} did, so the caller can decide what to say about it. */
    public record SnapshotResult(boolean accepted, int version, List<String> changedKeys) {
        public SnapshotResult {
            changedKeys = changedKeys == null ? List.of() : List.copyOf(changedKeys);
        }

        /** True when the snapshot was older than what this client already holds. */
        public boolean stale() {
            return !accepted;
        }
    }

    /**
     * Guest: replace the whole view from an {@code OPTIONS_SNAPSHOT}.
     *
     * <p>A full replacement, not a merge: a key the snapshot omits goes back to its registry default
     * rather than keeping whatever this client happened to hold, so two clients can never disagree
     * about a key because one of them remembers an older session.
     *
     * <p><b>Stale snapshots.</b> {@code snapshotVersion} below the version already held is refused
     * outright. This is the guard the plan asks for around a resume: the host re-sends on resume,
     * and a datagram-era reordering or a late duplicate must not walk the policy backwards.
     */
    public synchronized SnapshotResult applySnapshot(Map<String, String> values, int snapshotVersion,
                                                     String changedKey) {
        if (snapshotVersion < version) {
            CoopLog.warn(CoopOptionsPolicy.class, "Ignoring a stale OPTIONS_SNAPSHOT (v"
                    + snapshotVersion + " behind the held v" + version + ")");
            return new SnapshotResult(false, version, List.of());
        }
        List<String> changed = new ArrayList<>();
        for (CoopOptionsRegistry.Option option : policyOptions()) {
            String raw = values == null ? null : values.get(option.key());
            String next = option.coerce(raw).value();
            if (!next.equals(effective.get(option.key()))) {
                effective.put(option.key(), next);
                changed.add(option.key());
            }
            if (!GUEST_CROSSED_BOUNDARIES.contains(option.boundary())) {
                // Nothing on this client will ever cross this key's boundary, so holding the value
                // back would not delay an apply - it would only leave the guest permanently pending.
                applied.put(option.key(), next);
            }
        }
        version = snapshotVersion;
        seeded = true;
        lastChangedKey = changedKey == null ? "" : changedKey;
        return new SnapshotResult(true, version, changed);
    }

    /**
     * The apply boundaries a guest crosses for itself, and therefore the only ones
     * {@link #applySnapshot} may leave pending.
     *
     * <p>There is exactly one. On a guest an applied value moves either in {@code applySnapshot} or
     * through a consumer calling {@link #advanceBoundary(String)}, and the guest has a single such
     * consumer: {@code CoopNetPump.syncGuestSharedPauseIntent} advances
     * {@link CoopOptionsRegistry#PAUSE_ON_GUEST_SCREENS} the next time no core tab is open. Every
     * other boundary in the registry is host-side machinery - a connection attempt, a link drop, a
     * battle result, a month tick, a colony founding - that a guest never reaches.
     *
     * <p>Promoting only {@code IMMEDIATE} keys therefore left the guest pending forever on any key
     * the host had moved off its registry default (a launcher-set {@code reconnectGraceSeconds}, an
     * {@code incomeSplit} flip). {@code CoopNetPump.maybeSendOptionsApplied} is gated on nothing
     * being pending, so the {@code OPTIONS_APPLIED} acknowledgement was never sent; and because the
     * host has a guest to wait for it never fell back to {@code acknowledgeAllApplied} either, so
     * every later host change - including the one key the guest does advance - sat on the host's
     * options page reading "pending" for the whole session.
     *
     * <p>A phase that gives the guest a real consumer for another boundary adds it here, and the
     * acknowledgement starts waiting for that one too.
     */
    private static final Set<CoopOptionsRegistry.ApplyBoundary> GUEST_CROSSED_BOUNDARIES =
            java.util.EnumSet.of(CoopOptionsRegistry.ApplyBoundary.NEXT_SCREEN_TOGGLE);

    /**
     * Session teardown on a guest: forget the host's rules and go back to registry defaults, so a
     * campaign that keeps running after the link ends is not still living under a policy nobody is
     * broadcasting any more.
     */
    public synchronized void clearSyncedView() {
        resetToRegistryDefaults();
        version = 0;
        seeded = false;
        lastChangedKey = "";
    }

    // ---- internals -------------------------------------------------------------------------------

    private void resetToRegistryDefaults() {
        effective.clear();
        applied.clear();
        for (CoopOptionsRegistry.Option option : policyOptions()) {
            effective.put(option.key(), option.defaultValue());
            applied.put(option.key(), option.defaultValue());
        }
    }

    private boolean isHost() {
        try {
            return hostAuthority.getAsBoolean();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Wraps at {@link Integer#MAX_VALUE} back to 1 rather than going negative. */
    private static int nextVersion(int current) {
        return current >= Integer.MAX_VALUE ? FIRST_VERSION : Math.max(FIRST_VERSION, current + 1);
    }

    private Map<String, Object> data() {
        try {
            return persistentData.get();
        } catch (RuntimeException | LinkageError ex) {
            warnPersistOnce("Could not reach the campaign's persistent data", ex);
            return null;
        }
    }

    private static String readStored(Map<String, Object> data, CoopOptionsRegistry.Option option) {
        Object stored = data.get(PERSIST_PREFIX + option.key());
        if (stored == null) {
            return null;
        }
        String text = String.valueOf(stored);
        // An explicitly empty stored value is meaningful for the keys that allow one (an unset
        // password); for the rest, coerce() reads it as "not set" and hands back the default.
        return text;
    }

    private static Integer readStoredVersion(Map<String, Object> data) {
        Object stored = data.get(PERSIST_VERSION_KEY);
        if (stored == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(stored).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Writes the whole policy back. Whole rather than one key because the write is a handful of map
     * puts and "the save always carries a complete policy" is one fewer state to reason about.
     */
    private void persist() {
        if (!isHost()) {
            // The guest's campaign is not where these rules live; it gets them from the host on
            // every session. Writing them into its save would make a guest that later hosts inherit
            // the other player's rules without ever having chosen them.
            return;
        }
        Map<String, Object> data = data();
        if (data == null) {
            return;
        }
        try {
            for (Map.Entry<String, String> entry : effective.entrySet()) {
                data.put(PERSIST_PREFIX + entry.getKey(), entry.getValue());
            }
            data.put(PERSIST_VERSION_KEY, String.valueOf(version));
        } catch (RuntimeException | LinkageError ex) {
            warnPersistOnce("Could not store the campaign policy; it holds for this session only", ex);
        }
    }

    private void warnPersistOnce(String message, Throwable ex) {
        if (persistFailureLogged) {
            return;
        }
        persistFailureLogged = true;
        CoopLog.warn(CoopOptionsPolicy.class, message, ex);
    }

    /** The live campaign's persistent data, or null when there is no sector. Never throws. */
    private static Map<String, Object> sectorPersistentData() {
        try {
            SectorAPI sector = Global.getSector();
            return sector == null ? null : sector.getPersistentData();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
