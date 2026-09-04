package coop.fleet;

import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Receiver half of the Phase 20 M4 roster split: holds the remote player's last
 * {@code FLEET_ROSTER} and recombines it with each 10 Hz {@link CoopFleetSnapshot.Tick} into the
 * full {@link CoopFleetSnapshot} {@code CoopFleetMirror.apply} has always taken. The mirror is
 * deliberately untouched by this phase — the split is a wire change, not a semantics change.
 *
 * <p>Capacity is one roster, because v1 is host + one guest. It is a class rather than two fields on
 * the pump so that the multi-guest phase can key it by sender without moving the hold logic.
 *
 * <h2>The hash-mismatch window</h2>
 * A roster travels on TCP and a tick on UDP, so a tick describing a roster that has not landed yet is
 * normal for a frame or two after a ship is gained, lost or refitted. Applying such a tick against
 * the cached roster's ships would be wrong in one narrow way and right in every other: the CR/hull
 * pairs are positional, so a tick for a 9-ship fleet must not be indexed into a 10-ship roster. The
 * rule is therefore: motion, transponder and sensors from the tick always (the mirror keeps moving);
 * per-ship state only when the hashes match; and the composed snapshot carries the <em>cached
 * roster's</em> hash on a mismatch, so the mirror's rebuild gate never fires on a roster it does not
 * have yet. With no roster at all the member list is empty, which lands on the mirror's existing
 * "kept its last roster" path rather than tearing anything down.
 */
public final class CoopRosterCache {

    /** A mismatch this long is no longer the TCP/UDP race; it is a roster that never arrived. */
    static final long MISMATCH_LOG_AFTER_MILLIS = 5_000L;

    private CoopFleetRoster roster;
    /** Last per-ship state actually applied for {@link #roster}; positional, same size as its members. */
    private List<CoopFleetSnapshot.MemberState> lastStates;
    private boolean mismatchActive;
    private long mismatchSinceMillis;
    private boolean mismatchLogged;

    /** Stores an arrived roster. The next tick applies normally; nothing is synthesized from it here. */
    public void accept(CoopFleetRoster arrived) {
        Objects.requireNonNull(arrived, "arrived");
        roster = arrived;
        lastStates = null;
        clearMismatch();
    }

    /** The cached roster, or null when none has arrived this session. */
    public CoopFleetRoster current() {
        return roster;
    }

    /** True when {@code fleetHash16} is the roster this cache holds. */
    public boolean matches(String fleetHash16) {
        return roster != null && roster.fleetHash16().equals(fleetHash16);
    }

    /** Forgets everything (session edge, resume, disconnect). */
    public void reset() {
        roster = null;
        lastStates = null;
        clearMismatch();
    }

    /**
     * Builds the snapshot the mirror consumes from this tick and the cached roster.
     *
     * @param fallbackPlayerId identity to use before any roster has arrived; the pump passes the
     *                         session's remote player id, which is what the roster would carry
     * @param fallbackUsername ditto for the mirror's label
     */
    public CoopFleetSnapshot compose(CoopFleetSnapshot.Tick tick, String fallbackPlayerId,
                                     String fallbackUsername, long nowMillis) {
        Objects.requireNonNull(tick, "tick");
        if (roster == null) {
            noteMismatch(tick.fleetHash16(), nowMillis, "no roster has arrived yet");
            return snapshot(tick, fallbackPlayerId, fallbackUsername, "", tick.fleetHash16(), List.of());
        }
        boolean usable = roster.fleetHash16().equals(tick.fleetHash16())
                && tick.members().size() == roster.members().size();
        if (usable) {
            clearMismatch();
            lastStates = tick.members();
        } else {
            noteMismatch(tick.fleetHash16(), nowMillis,
                    "the cached roster is " + roster.fleetHash16() + " with "
                            + roster.members().size() + " ships");
        }
        return snapshot(tick, roster.playerId(), roster.username(), roster.factionId(),
                roster.fleetHash16(), applyStates(roster.members(), lastStates));
    }

    /**
     * Roster members with their CR/hull replaced by {@code states}, when there are any. The pairing is
     * the roster's <em>canonical</em> order, which is exactly the order the sender's
     * {@link CoopFleetSnapshot.Tick#of} emitted them in — the sender's raw fleet order is not on the
     * wire and, because the hash is order-independent, is not even guaranteed to be this roster's.
     * The result keeps the roster's own order, which is the order the mirror was built in.
     */
    private static List<CoopFleetSnapshot.Member> applyStates(List<CoopFleetSnapshot.Member> members,
                                                              List<CoopFleetSnapshot.MemberState> states) {
        if (states == null || states.size() != members.size()) {
            // Either nothing has been applied yet (the roster's own CR/hull are the last known
            // values) or a size disagreement that the caller already refused to index into.
            return members;
        }
        int[] order = CoopFleetSnapshot.canonicalOrderIndexes(members);
        List<CoopFleetSnapshot.Member> out = new ArrayList<>(members);
        for (int k = 0; k < order.length; k++) {
            CoopFleetSnapshot.Member member = members.get(order[k]);
            CoopFleetSnapshot.MemberState state = states.get(k);
            out.set(order[k], new CoopFleetSnapshot.Member(member.fleetMemberId(), member.hullId(),
                    member.variantId(), member.shipName(), member.captainName(),
                    state.cr(), state.hullFraction(), member.dmodIds(), member.sModIds(),
                    member.sModdedBuiltInIds()));
        }
        return out;
    }

    private static CoopFleetSnapshot snapshot(CoopFleetSnapshot.Tick tick, String playerId,
                                              String username, String factionId, String fleetHash,
                                              List<CoopFleetSnapshot.Member> members) {
        return new CoopFleetSnapshot(playerId, username, tick.locationId(),
                tick.x(), tick.y(), tick.velocityX(), tick.velocityY(),
                factionId, tick.transponderOn(), tick.sensors(), fleetHash, members);
    }

    /**
     * One log line per stuck window, not one per tick: this runs at 10 Hz and the ordinary case is a
     * mismatch that resolves within a frame or two.
     */
    private void noteMismatch(String tickHash, long nowMillis, String detail) {
        if (!mismatchActive) {
            mismatchActive = true;
            mismatchSinceMillis = nowMillis;
            return;
        }
        if (mismatchLogged || nowMillis - mismatchSinceMillis < MISMATCH_LOG_AFTER_MILLIS) {
            return;
        }
        mismatchLogged = true;
        CoopLog.warn(CoopRosterCache.class, "Coop partner fleet ticks have named roster " + tickHash
                + " for " + ((nowMillis - mismatchSinceMillis) / 1000L) + " s but " + detail
                + "; the mirror is holding its last roster");
    }

    private void clearMismatch() {
        mismatchActive = false;
        mismatchSinceMillis = 0L;
        mismatchLogged = false;
    }

    /**
     * Whether the stuck-mismatch warning has fired for the current hold. Diagnostics only — read by
     * the pump's tests to prove a tick did or did not reach {@link #compose}.
     */
    public boolean mismatchLogged() {
        return mismatchLogged;
    }
}
