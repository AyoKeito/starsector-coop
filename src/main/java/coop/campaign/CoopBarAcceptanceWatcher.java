package coop.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.util.TimeoutTracker;

import coop.util.CoopLog;

/**
 * Detects that the <em>local</em> player accepted a portside bar offer, so the Phase 12 first-come
 * claim can actually be raised (host: {@code hostClaimMissionLocally}; guest:
 * {@code guestRequestMissionClaim}). Without this nothing ever sent a
 * {@code MISSION_CLAIM_REQUEST} and both players could take the same offer.
 *
 * <h2>The signal</h2>
 *
 * <p>Vanilla exposes no "mission accepted" listener and no per-event accepted flag. What it does have
 * is one funnel: every acceptance path goes through
 * {@code BarEventManager.notifyWasInteractedWith(event)} — {@code BarCMD}'s {@code accept} command for
 * {@code HubMissionBarEventWrapper} missions, and {@code BaseGetCommodityBarEvent},
 * {@code HistorianBarEvent} and {@code PlanetaryShieldBarEvent} calling it on themselves for one-shot
 * offers. That method removes the event from {@code PortsideBarData}. So the observable is <b>an
 * offer that was in the local pool at the previous poll and is gone now</b>.
 *
 * <p>Disappearance alone is not enough, because offers also <em>expire</em>, and claiming on expiry
 * would hand the accepting player's partner a mission neither of them took. The discriminator is
 * {@code BarEventManager.getCreatorFor(event)}, read against the event reference retained from the
 * previous poll. The two removal paths differ in exactly this field:
 *
 * <ul>
 *   <li><b>Accepted</b> — {@code notifyWasInteractedWith} removes the event from the pool and from
 *   {@code active}, but leaves it in {@code barEventCreators}. {@code getCreatorFor} still returns
 *   its creator.</li>
 *   <li><b>Expired</b> — {@code BarEventManager.advance}'s orphan sweep does
 *   {@code barEventCreators.remove(event)} <em>before</em> {@code PortsideBarData.removeEvent}.
 *   {@code getCreatorFor} returns null.</li>
 * </ul>
 *
 * <p>An event the manager never owned at all ({@code getCreatorFor} null <em>and</em> never seen in
 * {@code getActive()}) is treated as accepted, because nothing but acceptance removes such an event
 * from the pool. That is the guest's whole pool: {@link CoopBarPoolInjector} deliberately keeps its
 * injected offers out of both manager maps, and {@link CoopBarGenerationSuppressor} stops the
 * manager's script entirely, so on the guest there is no expiry path at all.
 *
 * <h2>Known false positives</h2>
 *
 * <ul>
 *   <li>A third-party mod that removes an offer straight off {@code PortsideBarData} without going
 *   through the manager reads as an acceptance. Vanilla has no such path for the replicated subset:
 *   {@code PortsideBarData.advance} prunes on {@code shouldRemoveEvent()}, and the only vanilla
 *   events that return true there are the two intel-backed classes this watcher skips.</li>
 *   <li>Our own bulk pool rewrites are <em>not</em> false positives only because the callers say so:
 *   the guest's snapshot apply calls {@link #resync()} after {@link CoopBarPoolInjector#apply}, and
 *   the host calls {@link #forget(String)} when it consumes one of its own offers on the guest's
 *   behalf. Miss either call and the next poll reports a bogus acceptance.</li>
 * </ul>
 *
 * <h2>Known false negatives</h2>
 *
 * <ul>
 *   <li>The {@code getCreatorFor} evidence decays. The next orphan sweep (every 0.4-0.6 game days)
 *   finds the accepted event in {@code barEventCreators} but not in {@code active} and removes it
 *   there too. A poll that lands after that sweep sees the offer gone with no creator and reads it
 *   as an expiry. At a two-second poll and a ~10 s/day clock that needs several seconds of dropped
 *   frames; heavy fast-forward narrows the margin. A missed claim degrades to today's behaviour (no
 *   arbitration for that offer), it does not corrupt anything.</li>
 *   <li>Accept-then-expire inside a single poll interval is indistinguishable, and reads as expiry.</li>
 *   <li>An offer with a blank {@code getBarEventId()} is skipped outright: the claim protocol is
 *   keyed by that id, so there is nothing to name.</li>
 * </ul>
 */
public final class CoopBarAcceptanceWatcher {

    /** How many accepted-event handles are retained for a possible rollback. */
    static final int MAX_ROLLBACK_HANDLES = 16;

    /**
     * The seam the diff runs against, so the id bookkeeping is unit-testable without an engine.
     * {@code EngineProbe} is the real implementation.
     */
    public interface Probe {
        /**
         * Bar-offer ids currently in the local pool, in pool order, or {@code null} when the pool
         * could not be read. Null must not be reported as "everything vanished".
         */
        List<String> poolIds();

        /** For an id present at the previous poll and absent now: did the local player accept it? */
        boolean acceptedLocally(String missionId);

        /** Promote this poll's reading to the baseline the next {@link #acceptedLocally} reads. */
        void commit();
    }

    private final EngineProbe engineProbe = new EngineProbe();
    /** Ids seen at the previous poll; {@code null} means disarmed (next poll only re-baselines). */
    private Set<String> lastIds;

    /** Session (re)start: forget everything, including retained rollback handles. */
    public void reset() {
        lastIds = null;
        engineProbe.reset();
    }

    /**
     * Disarm: the next poll adopts the current pool as its baseline and reports nothing. Called
     * whenever <em>we</em> rewrote the pool, so our own removals are never read as acceptances.
     */
    public void resync() {
        lastIds = null;
    }

    /** Drop one id from the baseline, for an offer this client consumed on the peer's behalf. */
    public void forget(String missionId) {
        if (missionId == null) {
            return;
        }
        String id = missionId.trim();
        if (lastIds != null) {
            lastIds.remove(id);
        }
        engineProbe.forget(id);
    }

    /** Poll the live sector pool. Returns the ids the local player accepted since the last poll. */
    public List<String> poll() {
        return poll(engineProbe);
    }

    /** Pool-id diff. Package-visible seam so the decision logic is tested without an engine. */
    public List<String> poll(Probe probe) {
        List<String> current = probe.poolIds();
        if (current == null) {
            // "I could not look" is not "the pool is empty": keep the old baseline and try again.
            return List.of();
        }
        List<String> accepted = new ArrayList<>();
        if (lastIds != null) {
            Set<String> present = new LinkedHashSet<>(current);
            for (String id : lastIds) {
                if (!present.contains(id) && probe.acceptedLocally(id)) {
                    accepted.add(id);
                }
            }
        }
        lastIds = new LinkedHashSet<>(current);
        probe.commit();
        return accepted;
    }

    /**
     * The engine object behind a detected acceptance, retained so a rejected claim has something to
     * roll back. Null once it has aged out of {@link #MAX_ROLLBACK_HANDLES} or been dropped.
     */
    public PortsideBarEvent rollbackHandle(String missionId) {
        return missionId == null ? null : engineProbe.rollbackHandles.get(missionId.trim());
    }

    /** Release a retained handle once the claim is settled either way. */
    public void dropRollbackHandle(String missionId) {
        if (missionId != null) {
            engineProbe.rollbackHandles.remove(missionId.trim());
        }
    }

    // ---- Engine wiring -------------------------------------------------------------------------

    /** {@link Probe} over the live {@code PortsideBarData} / {@code BarEventManager}. */
    private static final class EngineProbe implements Probe {

        /** Event objects seen at the previous committed poll, by bar-event id. */
        private Map<String, PortsideBarEvent> previous = new LinkedHashMap<>();
        /** Sticky "the manager owned this id at some point", by bar-event id. */
        private Map<String, Boolean> previousManagerOwned = new LinkedHashMap<>();
        private Map<String, PortsideBarEvent> pending;
        private Map<String, Boolean> pendingManagerOwned;
        /** Rate limiter: this polls every frame, so a persistent failure must not spam the log. */
        private boolean warnedUnreadable;

        private final LinkedHashMap<String, PortsideBarEvent> rollbackHandles =
                new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, PortsideBarEvent> eldest) {
                        return size() > MAX_ROLLBACK_HANDLES;
                    }
                };

        private void reset() {
            previous = new LinkedHashMap<>();
            previousManagerOwned = new LinkedHashMap<>();
            pending = null;
            pendingManagerOwned = null;
            warnedUnreadable = false;
            rollbackHandles.clear();
        }

        private void forget(String missionId) {
            previous.remove(missionId);
            previousManagerOwned.remove(missionId);
            rollbackHandles.remove(missionId);
        }

        @Override
        public List<String> poolIds() {
            try {
                // Explicit, because this runs every frame: PortsideBarData.getInstance() would NPE
                // its way through the catch below on the frames around a load or a teardown, and a
                // once-per-frame stack trace in the log is its own bug.
                if (Global.getSector() == null) {
                    return null;
                }
                PortsideBarData data = PortsideBarData.getInstance();
                if (data == null || data.getEvents() == null) {
                    return null;
                }
                BarEventManager manager = BarEventManager.getInstance();
                TimeoutTracker<PortsideBarEvent> active = manager == null ? null : manager.getActive();
                Map<String, PortsideBarEvent> events = new LinkedHashMap<>();
                Map<String, Boolean> owned = new LinkedHashMap<>();
                List<String> ids = new ArrayList<>();
                // Defensive copy: the pool is a live engine list and this walk must not trip over a
                // concurrent modification from a script advancing on the same frame.
                for (PortsideBarEvent event : new ArrayList<>(data.getEvents())) {
                    if (event == null) {
                        continue;
                    }
                    // Intel-backed offers are never replicated and remove themselves from the pool
                    // on their own schedule; claiming one would name a mission the board never had.
                    if (CoopBarPoolCapture.isIntelBacked(event.getClass().getSimpleName())) {
                        continue;
                    }
                    String barEventId = event.getBarEventId();
                    if (barEventId == null || barEventId.trim().isEmpty()) {
                        continue;
                    }
                    String id = barEventId.trim();
                    if (events.containsKey(id)) {
                        continue;
                    }
                    events.put(id, event);
                    owned.put(id, isManagerOwned(id, event, manager, active));
                    ids.add(id);
                }
                pending = events;
                pendingManagerOwned = owned;
                warnedUnreadable = false;
                return ids;
            } catch (RuntimeException | LinkageError ex) {
                // Once per run of failures, not once per frame.
                if (!warnedUnreadable) {
                    warnedUnreadable = true;
                    CoopLog.warn(CoopBarAcceptanceWatcher.class,
                            "Failed to read the portside bar pool for acceptance detection", ex);
                }
                pending = null;
                pendingManagerOwned = null;
                return null;
            }
        }

        /**
         * Sticky on purpose: {@code notifyWasInteractedWith} drops the event from {@code active} in
         * the same call that removes it from the pool, so ownership has to be remembered from before
         * the acceptance rather than re-derived after it.
         */
        private boolean isManagerOwned(String id, PortsideBarEvent event, BarEventManager manager,
                                       TimeoutTracker<PortsideBarEvent> active) {
            if (Boolean.TRUE.equals(previousManagerOwned.get(id))) {
                return true;
            }
            if (manager != null && manager.getCreatorFor(event) != null) {
                return true;
            }
            // contains(), never getRemaining(): TimeoutTracker.getData inserts a missing item with
            // remaining = 0, so an unguarded read from a read-only poll would mutate the tracker.
            return active != null && active.contains(event);
        }

        @Override
        public boolean acceptedLocally(String missionId) {
            PortsideBarEvent event = previous.get(missionId);
            if (event == null) {
                // No retained reference means no evidence at all; never claim on a guess.
                return false;
            }
            boolean accepted = decide(event, previousManagerOwned.get(missionId));
            if (accepted) {
                rollbackHandles.put(missionId, event);
            }
            return accepted;
        }

        private boolean decide(PortsideBarEvent event, Boolean managerOwned) {
            try {
                BarEventManager manager = BarEventManager.getInstance();
                if (manager != null && manager.getCreatorFor(event) != null) {
                    // Still registered after leaving the pool: notifyWasInteractedWith, i.e. accepted.
                    return true;
                }
                // Never manager-owned (an injected or script-added offer): the orphan sweep cannot
                // touch it and nothing else prunes it, so acceptance is the only way it can be gone.
                return !Boolean.TRUE.equals(managerOwned);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopBarAcceptanceWatcher.class,
                        "Failed to classify a vanished portside bar offer; treating it as expired", ex);
                return false;
            }
        }

        @Override
        public void commit() {
            if (pending != null) {
                previous = pending;
                previousManagerOwned = pendingManagerOwned;
                pending = null;
                pendingManagerOwned = null;
            }
        }
    }
}
