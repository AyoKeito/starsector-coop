package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseManager;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseManager;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Phase 13: makes vanilla's dynamic pirate / Luddic-Path bases host-authoritative.
 *
 * <p>Bases are timer-placed campaign content and are <b>not</b> reproducible from the seed (their
 * market ids come from the engine's {@code Sector.genUID()}), so they are excluded from the seed-lock
 * fingerprint ({@code CoopSectorFingerprint.includeMarket} drops {@code isHidden} markets) and
 * replicated instead.
 *
 * <ul>
 *   <li><b>Host</b> ({@link #tickHost()}): polls {@code PirateBaseManager.getInstance().getActive()}
 *       and {@code LuddicPathBaseManager.getInstance().getActive()} once a second, builds the record
 *       set, and broadcasts the whole set over reliable TCP whenever the order-independent set hash
 *       changes. Full-set rebroadcast (never deltas) is the same self-correcting choice Phase 9 made
 *       for {@code NPC_FLEET_SET}. {@link #reset()} re-arms it at session (re)start.
 *       {@code PlayerRelatedPirateBaseManager} is deliberately <em>not</em> polled: it has no
 *       {@code getActive()} (it extends {@code EveryFrameScript} directly) and creates bases only in
 *       response to player colonies, which do not exist until Phase 24.</li>
 *   <li><b>Guest</b> ({@link #applySet(String)} + {@link #tickGuest()}): reconciles the local intel
 *       manager against the host's set, keyed by {@code (kind, systemId)}. Reconcile re-runs on a
 *       low-rate tick as well as on message arrival, because the guest's <em>mirrored</em> bases keep
 *       running their own vanilla {@code advanceImpl} (which rolls its own tier upgrades and mints
 *       fresh {@code PirateActivityIntel}); re-running the same idempotent plan pulls them back.</li>
 * </ul>
 *
 * <p>The reconcile decision is a pure function ({@link #plan}) over records; every engine touch goes
 * through the {@link BaseWorld} seam so the decision table is unit-testable without the engine
 * (the base constructors are massively side-effectful and cannot run in a test).
 */
public final class CoopBaseAuthority {

    /** Host poll cadence; matches the Phase 9 NPC_FLEET_SET cadence. */
    static final long HOST_POLL_INTERVAL_MILLIS = 1000L;
    /**
     * Guest re-reconcile cadence. Deliberately slow: the only thing it catches is the mirrored base's
     * own vanilla drift (self-rolled tier upgrades, regrown activity intel), which moves on a
     * campaign-month timescale.
     */
    static final long GUEST_RECONCILE_INTERVAL_MILLIS = 5000L;

    private final CoopNetService service;
    private final CoopSessionState sessionState;
    private final LongSupplier clockMillis;

    // Host state.
    private long nextHostPollAtMillis;
    private String lastSetHash = "";
    private int lastBaseCount;

    // Guest state.
    private List<CoopBaseRecord> desiredSet = List.of();
    private boolean desiredSetReceived;
    private long nextGuestReconcileAtMillis;
    /** Identity keys whose construction failed; retrying every tick would spam the engine. */
    private final Set<String> constructionFailures = new HashSet<>();

    public CoopBaseAuthority(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    // ---- Host ---------------------------------------------------------------------------------

    /** Called every frame on the host while the session is streaming. */
    public void tickHost() {
        long now = clockMillis.getAsLong();
        if (now < nextHostPollAtMillis) {
            return;
        }
        try {
            sendSetIfChanged(now);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to send BASE_SET", ex);
        } finally {
            nextHostPollAtMillis = now + HOST_POLL_INTERVAL_MILLIS;
        }
    }

    private void sendSetIfChanged(long now) {
        List<CoopBaseRecord> records = captureHostBases();
        if (records == null) {
            // Neither manager was readable this poll (no sector yet, or both threw). Staying silent
            // is the safe answer: broadcasting the resulting "empty set" would tell the guest to end
            // every base it mirrors.
            return;
        }
        String hash = CoopBaseRecord.setHash(records);
        if (hash.equals(lastSetHash)) {
            return;
        }
        service.send(CoopMessages.baseSet(sessionState.sessionId(), service.nextSeq(), now,
                CoopBaseRecord.encodeSet(records)));
        lastSetHash = hash;
        lastBaseCount = records.size();
        CoopLog.info(CoopBaseAuthority.class, "Coop sent BASE_SET bases=" + records.size());
    }

    /**
     * Reads the host's live base population. Defensive per manager so one broken manager cannot blank
     * the other half of the set, and returns {@code null} — "no reading this poll", not "no bases" —
     * when neither manager could be read at all.
     */
    private static List<CoopBaseRecord> captureHostBases() {
        List<CoopBaseRecord> records = new ArrayList<>();
        boolean anyRead = false;
        try {
            PirateBaseManager pirates = PirateBaseManager.getInstance();
            if (pirates != null) {
                collect(pirates.getActive(), records);
                anyRead = true;
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to read pirate base manager", ex);
        }
        try {
            LuddicPathBaseManager pathers = LuddicPathBaseManager.getInstance();
            if (pathers != null) {
                collect(pathers.getActive(), records);
                anyRead = true;
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to read Luddic-Path base manager", ex);
        }
        return anyRead ? records : null;
    }

    /** {@code BaseEventManager.getActive()} is {@code List<EveryFrameScript>} — cast per element. */
    private static void collect(List<EveryFrameScript> active, List<CoopBaseRecord> out) {
        if (active == null) {
            return;
        }
        for (EveryFrameScript script : active) {
            CoopBaseRecord record = toRecord(script);
            if (record != null) {
                out.add(record);
            }
        }
    }

    /** Forget the last-sent hash so the next host tick rebroadcasts the full set (session start). */
    public void reset() {
        lastSetHash = "";
        lastBaseCount = 0;
        desiredSet = List.of();
        desiredSetReceived = false;
        nextGuestReconcileAtMillis = 0L;
        constructionFailures.clear();
    }

    public String lastSetHash() {
        return lastSetHash;
    }

    public int lastBaseCount() {
        return lastBaseCount;
    }

    // ---- Guest --------------------------------------------------------------------------------

    /** Applies an inbound {@code BASE_SET} payload; safe to call with a byte-identical repeat. */
    public void applySet(String encodedSet) {
        desiredSet = CoopBaseRecord.decodeSet(encodedSet == null ? "" : encodedSet);
        desiredSetReceived = true;
        // A new authoritative set is a fresh chance for a base whose construction failed before.
        constructionFailures.clear();
        reconcileNow();
    }

    /**
     * Called every frame on the guest while the session is streaming; re-runs the (idempotent)
     * reconcile at a low rate so the mirrored bases' own vanilla drift gets pulled back.
     */
    public void tickGuest() {
        if (!desiredSetReceived) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now < nextGuestReconcileAtMillis) {
            return;
        }
        nextGuestReconcileAtMillis = now + GUEST_RECONCILE_INTERVAL_MILLIS;
        reconcileNow();
    }

    private void reconcileNow() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getIntelManager() == null) {
                return;
            }
            Summary summary = apply(new SectorBaseWorld(sector), desiredSet, constructionFailures);
            if (!summary.isNoOp()) {
                CoopLog.info(CoopBaseAuthority.class, "Coop guest reconciled bases " + summary);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to reconcile host base set", ex);
        }
    }

    public int desiredBaseCount() {
        return desiredSet.size();
    }

    // ---- Pure decision functions (unit-tested) ------------------------------------------------

    /** Only the guest reconstructs bases; the host runs vanilla base simulation unchanged. */
    public static boolean reconcilesForRole(CoopConnectionRole role) {
        return role == CoopConnectionRole.GUEST;
    }

    /** What the guest must do to one base identity to match the host. */
    public enum ActionType {
        /** Present locally, absent from the host set: end it. */
        REMOVE,
        /**
         * Present on both but the faction differs. Faction is fixed at construction (it is baked into
         * the market, the station entity and the commander), so there is no in-place update.
         */
        RECREATE,
        /** Present on both, only the tier / {@code isLarge} attribute differs: update in place. */
        UPDATE_ATTR,
        /** Absent locally: construct it. */
        ADD
    }

    /** One reconcile step. {@link #record()} always carries the <em>desired</em> (host) values. */
    public record Action(ActionType type, CoopBaseRecord record) {
        public Action {
            type = Objects.requireNonNull(type, "type");
            record = Objects.requireNonNull(record, "record");
        }
    }

    /**
     * The reconcile decision table, keyed by {@code (kind, systemId)}. Ordering is deterministic and
     * removal-first so a RECREATE never briefly holds two bases in one system.
     *
     * <p>Duplicate identities in either input are collapsed last-wins; vanilla cannot produce them,
     * but a malformed payload must not make the plan quadratic or non-deterministic.
     */
    public static List<Action> plan(Collection<CoopBaseRecord> desired, Collection<CoopBaseRecord> local) {
        Map<String, CoopBaseRecord> want = byIdentity(desired);
        Map<String, CoopBaseRecord> have = byIdentity(local);

        List<Action> removes = new ArrayList<>();
        List<Action> recreates = new ArrayList<>();
        List<Action> updates = new ArrayList<>();
        List<Action> adds = new ArrayList<>();

        for (Map.Entry<String, CoopBaseRecord> entry : have.entrySet()) {
            CoopBaseRecord wanted = want.get(entry.getKey());
            if (wanted == null) {
                removes.add(new Action(ActionType.REMOVE, entry.getValue()));
            }
        }
        for (Map.Entry<String, CoopBaseRecord> entry : want.entrySet()) {
            CoopBaseRecord wanted = entry.getValue();
            CoopBaseRecord mine = have.get(entry.getKey());
            if (mine == null) {
                adds.add(new Action(ActionType.ADD, wanted));
            } else if (!mine.factionId().equals(wanted.factionId())) {
                recreates.add(new Action(ActionType.RECREATE, wanted));
            } else if (!attrEquals(mine.attr(), wanted.attr())) {
                updates.add(new Action(ActionType.UPDATE_ATTR, wanted));
            }
        }

        List<Action> plan = new ArrayList<>(
                removes.size() + recreates.size() + updates.size() + adds.size());
        plan.addAll(removes);
        plan.addAll(recreates);
        plan.addAll(updates);
        plan.addAll(adds);
        return plan;
    }

    private static boolean attrEquals(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    private static Map<String, CoopBaseRecord> byIdentity(Collection<CoopBaseRecord> records) {
        // LinkedHashMap: the plan's within-bucket order follows input order, so it is reproducible.
        Map<String, CoopBaseRecord> byKey = new LinkedHashMap<>();
        if (records != null) {
            for (CoopBaseRecord record : records) {
                if (record != null) {
                    byKey.put(record.identityKey(), record);
                }
            }
        }
        return byKey;
    }

    /** What one reconcile pass did; {@code failed} counts identities we gave up constructing. */
    public record Summary(int added, int removed, int updated, int recreated, int failed) {
        public boolean isNoOp() {
            return added == 0 && removed == 0 && updated == 0 && recreated == 0 && failed == 0;
        }

        @Override
        public String toString() {
            return "added=" + added + " removed=" + removed + " updated=" + updated
                    + " recreated=" + recreated + " failed=" + failed;
        }
    }

    /**
     * Executes {@link #plan} against a {@link BaseWorld}. Idempotent: applying the same desired set
     * twice produces an empty plan the second time, because {@code world.localBases()} then reports
     * exactly what was created.
     *
     * @param failedKeys identity keys to skip constructing (mutated: failures are added). A base whose
     *                   constructor bails out — vanilla ends the intel and returns when the system has
     *                   no valid station location — would otherwise be retried on every single pass.
     */
    public static Summary apply(BaseWorld world, Collection<CoopBaseRecord> desired, Set<String> failedKeys) {
        Objects.requireNonNull(world, "world");
        int added = 0;
        int removed = 0;
        int updated = 0;
        int recreated = 0;
        int failed = 0;

        for (Action action : plan(desired, world.localBases())) {
            switch (action.type()) {
                case REMOVE -> {
                    world.remove(action.record());
                    removed++;
                }
                case RECREATE -> {
                    world.remove(action.record());
                    if (construct(world, action.record(), failedKeys)) {
                        recreated++;
                    } else {
                        failed++;
                    }
                }
                case UPDATE_ATTR -> {
                    if (world.updateAttr(action.record())) {
                        updated++;
                    } else {
                        // Documented fallback: no in-place setter worked, so rebuild the base.
                        world.remove(action.record());
                        if (construct(world, action.record(), failedKeys)) {
                            recreated++;
                        } else {
                            failed++;
                        }
                    }
                }
                case ADD -> {
                    if (failedKeys != null && failedKeys.contains(action.record().identityKey())) {
                        continue;
                    }
                    if (construct(world, action.record(), failedKeys)) {
                        added++;
                    } else {
                        failed++;
                    }
                }
            }
        }

        // Every PirateActivityIntel on a guest is derived from a base we mirror (the guest's own base
        // managers are suppressed), and the mirrored base re-mints one whenever it re-picks a target
        // against its own local system list. End them unconditionally, every pass.
        world.endDerivedActivityIntel();
        return new Summary(added, removed, updated, recreated, failed);
    }

    private static boolean construct(BaseWorld world, CoopBaseRecord record, Set<String> failedKeys) {
        if (world.create(record)) {
            return true;
        }
        if (failedKeys != null) {
            failedKeys.add(record.identityKey());
        }
        return false;
    }

    // ---- Seams --------------------------------------------------------------------------------

    /**
     * Seam over the guest's engine state so {@link #apply} stays a pure, testable decision function.
     * The engine-typed implementation is {@code SectorBaseWorld}; tests drive a fake.
     */
    public interface BaseWorld {
        /** The bases the guest currently mirrors, as records. */
        List<CoopBaseRecord> localBases();

        /** Construct the base and run its post-construction fixups. False = construction failed. */
        boolean create(CoopBaseRecord record);

        /** End + remove the base and despawn its station entity. Missing base = no-op. */
        void remove(CoopBaseRecord record);

        /** Update tier / {@code isLarge} in place. False = caller falls back to destroy + recreate. */
        boolean updateAttr(CoopBaseRecord record);

        /** End every {@code PirateActivityIntel} the mirrored bases spawned. */
        void endDerivedActivityIntel();
    }

    /**
     * Engine-typed {@link BaseWorld}. Instantiated only on the guest, only while a session is live.
     *
     * <p><b>Accepted divergence:</b> the constructors roll their own orbit, name and commander from
     * {@code Misc.random} and mint their own {@code genUID} market, so the guest's copy of a base sits
     * somewhere else in the same system under a different name. System, faction and strength — the
     * things that drive gameplay — are what this replicates.
     */
    private static final class SectorBaseWorld implements BaseWorld {
        private final SectorAPI sector;
        private final IntelManagerAPI intel;

        private SectorBaseWorld(SectorAPI sector) {
            this.sector = sector;
            this.intel = sector.getIntelManager();
        }

        @Override
        public List<CoopBaseRecord> localBases() {
            List<CoopBaseRecord> records = new ArrayList<>();
            for (Object base : liveBaseIntel()) {
                CoopBaseRecord record = toRecord(base);
                if (record != null) {
                    records.add(record);
                }
            }
            return records;
        }

        @Override
        public boolean create(CoopBaseRecord record) {
            StarSystemAPI system = resolveSystem(record.systemId());
            if (system == null) {
                CoopLog.warn(CoopBaseAuthority.class,
                        "Cannot mirror host base: unknown system id " + record.systemId());
                return false;
            }
            try {
                BaseIntelPlugin created;
                if (record.kind() == CoopBaseRecord.Kind.PIRATE) {
                    PirateBaseIntel.PirateBaseTier tier = parseTier(record.attr());
                    if (tier == null) {
                        CoopLog.warn(CoopBaseAuthority.class,
                                "Cannot mirror host pirate base: unknown tier " + record.attr());
                        return false;
                    }
                    created = new PirateBaseIntel(system, record.factionId(), tier);
                } else {
                    LuddicPathBaseIntel path = new LuddicPathBaseIntel(system, record.factionId());
                    created = path;
                    // isLarge is rolled inside the constructor and has no setter; force it to the
                    // host's value so patrol count/quality match. Known residual divergence: the
                    // station module was already picked from the locally-rolled value and vanilla
                    // never re-picks it (LuddicPathBaseIntel calls updateStationIfNeeded only from
                    // the constructor, and a second call re-adds the same industry id), so a flipped
                    // base keeps the other size's station art/defences. Cosmetic + minor defence
                    // strength; not worth re-entering a protected side-effectful method for.
                    if (!setLarge(path, record.isLarge())) {
                        CoopLog.warn(CoopBaseAuthority.class,
                                "Mirrored Pather base in " + record.systemId()
                                        + " kept its locally-rolled isLarge (field write failed)");
                    }
                }
                // The PirateBaseIntel constructor ends with updateTarget() -> new PirateActivityIntel
                // against a target IT picked locally. It is ended by the endDerivedActivityIntel()
                // sweep at the end of this same reconcile pass, so no separate call is needed here.
                //
                // Vanilla bails out of the constructor (endImmediately + return) when the system has
                // no valid station location or name. Report that as a failure so we stop retrying.
                if (created.isEnding() || created.isEnded()) {
                    CoopLog.warn(CoopBaseAuthority.class, "Host base in " + record.systemId()
                            + " could not be placed locally; vanilla ended it during construction");
                    return false;
                }
                return true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBaseAuthority.class,
                        "Failed to construct mirrored base in " + record.systemId(), ex);
                return false;
            }
        }

        @Override
        public void remove(CoopBaseRecord record) {
            for (Object base : liveBaseIntel()) {
                CoopBaseRecord local = toRecord(base);
                if (local != null && local.sameIdentity(record)) {
                    endActivityIntelFrom(base);
                    endAndRemove(base);
                }
            }
        }

        @Override
        public boolean updateAttr(CoopBaseRecord record) {
            for (Object base : liveBaseIntel()) {
                CoopBaseRecord local = toRecord(base);
                if (local == null || !local.sameIdentity(record)) {
                    continue;
                }
                if (base instanceof PirateBaseIntel pirate) {
                    PirateBaseIntel.PirateBaseTier tier = parseTier(record.attr());
                    return tier != null && setTier(pirate, tier);
                }
                if (base instanceof LuddicPathBaseIntel path) {
                    return setLarge(path, record.isLarge());
                }
            }
            return false;
        }

        @Override
        public void endDerivedActivityIntel() {
            for (Object activity : collect(PirateActivityIntel.class)) {
                endAndRemove(activity);
            }
        }

        /** Ends the activity intel a specific base spawned; ordering matters, see {@link #remove}. */
        private void endActivityIntelFrom(Object base) {
            for (Object activity : collect(PirateActivityIntel.class)) {
                try {
                    if (((PirateActivityIntel) activity).getSource() == base) {
                        endAndRemove(activity);
                    }
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopBaseAuthority.class, "Failed to read activity intel source", ex);
                }
            }
        }

        /**
         * {@code endImmediately()} drops the hidden market from the economy and clears listeners and
         * radio chatter; {@code removeIntel} is explicit because the guest's base managers are
         * suppressed and nothing drains ended intel. The station entity needs its own removal —
         * vanilla only despawns it when a bounty fleet destroys the base, so plain ending leaves a
         * floating station with a de-registered market (the same gap the session-start cleanup in
         * {@code CoopNpcFleetSuppressor} closes).
         */
        private void endAndRemove(Object item) {
            try {
                SectorEntityToken entity = baseEntity(item);
                if (item instanceof BaseIntelPlugin plugin) {
                    plugin.endImmediately();
                }
                if (item instanceof IntelInfoPlugin plugin) {
                    intel.removeIntel(plugin);
                }
                despawn(entity);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBaseAuthority.class, "Failed to end mirrored base intel", ex);
            }
        }

        private static void despawn(SectorEntityToken entity) {
            if (entity == null) {
                return;
            }
            LocationAPI location = entity.getContainingLocation();
            if (location != null) {
                location.removeEntity(entity);
            }
        }

        private static SectorEntityToken baseEntity(Object item) {
            if (item instanceof PirateBaseIntel pirate) {
                return pirate.getEntity();
            }
            if (item instanceof LuddicPathBaseIntel path) {
                return path.getEntity();
            }
            return null;
        }

        private List<Object> liveBaseIntel() {
            List<Object> bases = new ArrayList<>();
            for (Object base : collect(PirateBaseIntel.class)) {
                bases.add(base);
            }
            bases.addAll(collect(LuddicPathBaseIntel.class));
            return bases;
        }

        private List<Object> collect(Class<?> type) {
            List<Object> result = new ArrayList<>();
            List<IntelInfoPlugin> items = intel.getIntel(type);
            if (items == null) {
                return result;
            }
            for (IntelInfoPlugin item : items) {
                if (item instanceof BaseIntelPlugin plugin && (plugin.isEnding() || plugin.isEnded())) {
                    continue;
                }
                if (item != null) {
                    result.add(item);
                }
            }
            return result;
        }

        /**
         * Resolves a star system by id against the local deterministic skeleton. Id first (that is
         * what the host sent); name is a defensive second pass because {@code getStarSystem(String)}
         * matches on name, not id.
         */
        private StarSystemAPI resolveSystem(String systemId) {
            if (systemId.isEmpty()) {
                return null;
            }
            List<StarSystemAPI> systems = sector.getStarSystems();
            if (systems == null) {
                return null;
            }
            for (StarSystemAPI system : systems) {
                if (system != null && systemId.equals(system.getId())) {
                    return system;
                }
            }
            for (StarSystemAPI system : systems) {
                if (system != null && systemId.equals(system.getName())) {
                    return system;
                }
            }
            return null;
        }
    }

    // ---- Engine record mapping ----------------------------------------------------------------

    /** Maps a live {@code PirateBaseIntel} / {@code LuddicPathBaseIntel} to its wire record. */
    static CoopBaseRecord toRecord(Object intel) {
        try {
            if (intel instanceof PirateBaseIntel pirate) {
                if (pirate.isEnding() || pirate.isEnded()) {
                    return null;
                }
                StarSystemAPI system = pirate.getSystem();
                PirateBaseIntel.PirateBaseTier tier = pirate.getTier();
                if (system == null || tier == null) {
                    return null;
                }
                return CoopBaseRecord.pirate(system.getId(), factionId(pirate.getMarket()), tier.name());
            }
            if (intel instanceof LuddicPathBaseIntel path) {
                if (path.isEnding() || path.isEnded()) {
                    return null;
                }
                StarSystemAPI system = path.getSystem();
                if (system == null) {
                    return null;
                }
                return CoopBaseRecord.pather(system.getId(), factionId(path.getMarket()), path.isLarge());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to read base intel", ex);
        }
        return null;
    }

    private static String factionId(MarketAPI market) {
        return market == null || market.getFactionId() == null ? "" : market.getFactionId();
    }

    private static PirateBaseIntel.PirateBaseTier parseTier(String attr) {
        try {
            return PirateBaseIntel.PirateBaseTier.valueOf(attr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    // ---- MethodHandles field access -----------------------------------------------------------
    //
    // Starfarer's script classloader hard-blocks java.lang.reflect ("File access and reflection are
    // not allowed to scripts"). java.lang.invoke is NOT on that denylist, so the two private base
    // strength fields are read/written with MethodHandles - the proven CoopBarSync pattern. Both are
    // set-only here and every failure degrades to "the mirrored base keeps its locally-rolled
    // strength", never to a crash.

    private static MethodHandle tierSetter;
    private static MethodHandle largeSetter;
    private static boolean handlesResolved;

    /**
     * {@code PirateBaseIntel.tier} is {@code protected} with a getter but no setter. Writing it is
     * enough: the intel's own {@code advanceImpl} ends with {@code updateStationIfNeeded()}, which
     * compares {@code matchedStationToTier} against {@code tier} and rebuilds the station module the
     * next frame.
     */
    static boolean setTier(PirateBaseIntel intel, PirateBaseIntel.PirateBaseTier tier) {
        try {
            resolveHandles();
            if (tierSetter == null) {
                return false;
            }
            tierSetter.invoke(intel, tier);
            return true;
        } catch (Throwable t) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to set pirate base tier", t);
            return false;
        }
    }

    /**
     * {@code LuddicPathBaseIntel.large} is {@code protected}, rolled inside the constructor
     * ({@code random.nextFloat() > 0.5f}) and never a parameter, so the host's value can only be
     * forced in. Writing it fixes patrol count/quality (every read of {@code large} in the fleet
     * sizing path). It does <em>not</em> retro-fit the station module — see {@code create}.
     */
    static boolean setLarge(LuddicPathBaseIntel intel, boolean large) {
        try {
            resolveHandles();
            if (largeSetter == null) {
                return false;
            }
            largeSetter.invoke(intel, large);
            return true;
        } catch (Throwable t) {
            CoopLog.warn(CoopBaseAuthority.class, "Failed to set Luddic-Path base isLarge", t);
            return false;
        }
    }

    private static synchronized void resolveHandles() throws Throwable {
        if (handlesResolved) {
            return;
        }
        handlesResolved = true;
        MethodHandles.Lookup pirate =
                MethodHandles.privateLookupIn(PirateBaseIntel.class, MethodHandles.lookup());
        tierSetter = pirate.findSetter(PirateBaseIntel.class, "tier", PirateBaseIntel.PirateBaseTier.class);
        MethodHandles.Lookup path =
                MethodHandles.privateLookupIn(LuddicPathBaseIntel.class, MethodHandles.lookup());
        largeSetter = path.findSetter(LuddicPathBaseIntel.class, "large", boolean.class);
    }
}
