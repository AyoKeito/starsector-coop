package coop.fleet;

import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The host's full authoritative set of non-player campaign fleets, carried by the reliable TCP
 * {@code NPC_FLEET_SET} message (Phase 9). The whole set is rebroadcast whenever the structural state
 * of the fleets <em>near a player</em> changes ({@link #computeNearHash}, since 2026-09-14), and —
 * since 2026-09-05 — also when the rate-limited {@link #computeSoftHash} changes, at most once every
 * {@code CoopNpcFleetReplicator.SOFT_RESYNC_INTERVAL_MILLIS}; the guest reconciles
 * against it idempotently (add fleets present here but missing locally, dispose mirrors absent here).
 * Full-set rebroadcast is chosen for v1 because it is self-correcting (no add/remove delta ordering or
 * lost-packet bugs). There are exactly two send triggers and nothing here may add a third.
 *
 * <p>{@link #setHash()} is order-independent and folds in each fleet's identity, name, faction,
 * location, transponder state and roster ({@code fleetHash}), so it flips on spawn/despawn, rename,
 * faction change, system jump, transponder toggle or roster edit — the changes the guest has to
 * re-apply promptly rather than merely interpolate.
 *
 * <p><b>Revised 2026-09-13: cosmetic action text left the structural hash.</b> The tooltip line
 * ({@code aiAssignmentSummary}) used to be folded in here, and it dominated the wire. Measured live
 * on 2026-09-13: 291 structural sends against 8 health sends, with the full 33-fleet set going out
 * once per second for minutes while the guest sat in a busy trade system — because somewhere in that
 * system a fleet's line flipped from "returning to X" to "delivering Y to Z" on nearly every tick, and
 * one fleet's cosmetic text was enough to re-send all 33. On loopback that is invisible; on a WAN link
 * it is precisely the payload the Phase 20 diet was about. Action text now rides
 * {@link #computeSoftHash}, which shares the health trigger's 10 s floor, so a text-only change costs
 * at most one set per 10 s and no structural change is delayed by even a frame.
 *
 * <p><b>Revised 2026-09-14 (finding S4-C remainder): structural is now scoped to the observers'
 * locations.</b> The 2026-09-13 split stopped the text storm but left a second one. The replicated
 * population is sector-wide on purpose ({@code forEachReplicatedFleet} walks every location), so
 * {@link #computeSetHash} flips on a spawn, despawn, jump or transponder toggle <em>anywhere</em> —
 * and NPC fleets do all four constantly in systems neither player has ever visited. Measured live
 * with both players stationary (33 bridge samples over 28 s): 12 structural sends, every one of them
 * caused by a fleet in Corvus / Arcadia / Eos Exodus / Valhalla / hyperspace despawning (roster
 * emptied, then gone), spawning, changing locationId or toggling its transponder — bursts of 19-29
 * full sets a minute, ~20-25 KB each for 38 fleets, none of it observable by either player. The rule
 * now: fold a fleet's structural fields into {@link #computeNearHash} when its {@code locationId} is
 * one of the observers' (host player fleet, guest mirror; hyperspace counts as a location like any
 * other), and into the rate-limited {@link #computeSoftHash} otherwise. A far fleet's structural
 * change still reaches the guest, just on the 10 s floor instead of within the frame — which is the
 * delay the guest already tolerates for it, since it cannot see that system until it travels there,
 * and travelling there makes those fleets near. Each send still carries the FULL set: the payload is
 * unchanged and the guest's add/dispose reconciliation still depends on seeing every fleet.
 *
 * <p><b>Name stays structural (decided 2026-09-13).</b> A fleet is named when it spawns and normally
 * keeps that name for life; renames are rare events (inflation-time relabels, the {@code "Your
 * &lt;name&gt;"} / partner labels) rather than per-tick status churn, so they were not part of the
 * measured storm. Name is identity, it has no carrier other than this set (the guest's
 * {@code refreshIdentity} only sees a rename when a set arrives), and holding it behind a 10 s floor
 * would save nothing measurable while leaving a visibly stale label on the guest.
 *
 * <p>Transponder state is in the hash because it is the only place it travels: the 10 Hz
 * {@code NPC_FLEET_MOTION} datagram does not carry it, and on the guest a mirror's transponder flag is
 * the difference between full faction identification across the entire detection range and
 * identification only inside 10% of it ({@code BaseCampaignEntity.getVisibilityLevelTo}, engine line
 * 1206/1217). Without it a mirror kept whatever transponder state it had at the last structural change
 * and rendered at the wrong tier indefinitely.
 *
 * <p>Encoding packs each (multi-line) per-fleet encoding onto a single line via
 * {@link CoopFleetCodec#escape}, joined by newlines, so the flat envelope can carry the whole set as
 * one body string.
 */
public record CoopNpcFleetSetSnapshot(List<CoopNpcFleetSnapshot> fleets, String setHash) {

    public CoopNpcFleetSetSnapshot {
        fleets = fleets == null ? List.of() : List.copyOf(fleets);
        setHash = setHash == null ? "" : setHash;
    }

    /** Builds a set, computing the order-independent {@link #setHash()}. */
    public static CoopNpcFleetSetSnapshot create(List<CoopNpcFleetSnapshot> fleets) {
        List<CoopNpcFleetSnapshot> safe = fleets == null ? List.of() : fleets;
        return new CoopNpcFleetSetSnapshot(safe, computeSetHash(safe));
    }

    /**
     * Order-independent hash over each fleet's identity/name/faction/location/transponder/roster —
     * the fields whose change the guest must act on within the tick, not within ten seconds. Name is
     * in the hash for the same reason transponder state is: the 1 Hz set is its only carrier, and
     * {@code sendSetIfChanged} only rebroadcasts when this hash moves, so a rename (the 2026-08-19
     * identity fix) that did not flip it would sit on the host until an unrelated structural change
     * happened to flush it. Location is here because a change of it moves the mirror between systems.
     *
     * <p><b>Action text is deliberately absent since 2026-09-13</b> — see the class doc for the
     * measurement. It is cosmetic tooltip prose that re-words itself several times a minute per fleet
     * in a busy system, and while it was folded in here one fleet's re-wording re-sent the whole set.
     * It moved to the rate-limited {@link #computeSoftHash} trigger, which puts the very same message
     * on the wire at most once per {@code CoopNpcFleetReplicator.SOFT_RESYNC_INTERVAL_MILLIS}.
     *
     * <p><b>Health is deliberately absent</b> — CR and hull fraction are not in {@code fleetHash}
     * (see {@link CoopFleetSnapshot#computeFleetHash}) and so are not in this hash either, because a
     * flip here means the guest re-applies structure and the guest's freeze-release logic
     * ({@code CoopFleetMirrorRegistry}) reads {@code fleetHash} as "the ship set changed". Health
     * reaches the guest through the same rate-limited {@link #computeSoftHash} trigger in
     * {@code CoopNpcFleetReplicator}, which sends the very same set message without disturbing the
     * meaning of either hash.
     *
     * <p><b>Since 2026-09-14 this is no longer the send trigger</b> — {@link #computeNearHash} is,
     * and this whole-sector hash remains as the set's own identity field ({@link #setHash()}, carried
     * on the wire and reported by the pump's diagnostics). Read the class doc before wiring it back
     * into a send decision: sector-wide structural churn in systems no player is in was the second
     * storm (2026-09-14), and this hash is exactly what flipped for it.
     */
    public static String computeSetHash(List<CoopNpcFleetSnapshot> fleets) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                records.add(structuralRecord(fleet));
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(String.join("\n", records));
    }

    /**
     * The first send trigger since 2026-09-14: {@link #computeSetHash} restricted to the fleets in a
     * location an observer is in. Same per-fleet record, so every field that used to make a send
     * immediate still does — for the fleets a player can actually see. Because a fleet's record is
     * simply absent when it is far, near-set <em>membership</em> moves this hash too: a fleet jumping
     * into an observer's system, or out of it, flips it in the frame it happens, which is what the
     * guest's add/dispose reconciliation and {@code CoopFleetMirrorRegistry}'s freeze release need.
     *
     * <p>Everything not folded in here is folded into {@link #computeSoftHash(List, Set)} instead —
     * delayed, never dropped. See the class doc for the 2026-09-14 measurement.
     *
     * <p><b>An empty observer set means "everything is near", not "nothing is".</b> The replicator
     * derives the set from live engine reads, and the one way it comes back empty is that neither
     * the host fleet nor the guest mirror could answer where it is. Treating that as "nothing is
     * observable" would hold every structural change behind the 10 s floor for as long as the engine
     * stayed unreadable; treating it as "everything is observable" degrades to exactly the
     * pre-2026-09-14 behaviour, which is bounded extra traffic rather than a stale guest.
     */
    public static String computeNearHash(List<CoopNpcFleetSnapshot> fleets,
                                         Set<String> observerLocationIds) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                if (isNear(fleet, observerLocationIds)) {
                    records.add(structuralRecord(fleet));
                }
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(String.join("\n", records));
    }

    /** The per-fleet structural line {@link #computeSetHash} and {@link #computeNearHash} both fold. */
    private static String structuralRecord(CoopNpcFleetSnapshot fleet) {
        return fleet.coopFleetId() + "|" + fleet.factionId() + "|"
                + fleet.locationId() + "|" + (fleet.transponderOn() ? "1" : "0")
                + "|" + fleet.fleetHash() + "|" + fleet.name();
    }

    /**
     * Whether a fleet sits in a location an observer is in. Null/empty observer set = every fleet is
     * near; see {@link #computeNearHash} for why that is the safe direction to fail in.
     */
    private static boolean isNear(CoopNpcFleetSnapshot fleet, Set<String> observerLocationIds) {
        return observerLocationIds == null || observerLocationIds.isEmpty()
                || observerLocationIds.contains(fleet.locationId());
    }

    /**
     * The second and last send trigger for {@code NPC_FLEET_SET} (2026-09-13): everything the guest
     * needs eventually but not promptly, folded into one hash so there is still exactly one
     * rate-limited trigger behind {@code CoopNpcFleetReplicator.SOFT_RESYNC_INTERVAL_MILLIS}. Two
     * things live here — member CR/hull in 5% buckets ({@link #computeHealthHash}) and each fleet's
     * cosmetic action text — and they share the floor because they share the reason: neither is worth
     * a full set at 1 Hz, and both are carried by nothing else on the wire.
     *
     * <p>A flip here decides only <em>whether to send</em>. The arriving set is structurally identical
     * to the last one as far as the guest is concerned, and the receive path paints health onto the
     * existing members ({@code CoopFleetMirror#updateMemberState}) and re-pins the action text
     * ({@code CoopFleetMirror#applyActionText}, which runs on every snapshot, not only on hash change)
     * without rebuilding a roster.
     *
     * <p>Since 2026-09-14 the replicator compares {@link #computeSoftHash(List, Set)}, which is this
     * hash plus the structural fields of the fleets no observer is near; this form is the health/text
     * half on its own.
     */
    public static String computeSoftHash(List<CoopNpcFleetSnapshot> fleets) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                records.add(fleet.coopFleetId() + "|" + fleet.aiAssignmentSummary());
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(
                computeHealthHash(fleets) + "\n" + String.join("\n", records));
    }

    /**
     * The rate-limited trigger the replicator actually compares since 2026-09-14: the soft hash above
     * (health + action text, sector-wide) plus the <em>structural</em> fields of every fleet
     * {@link #computeNearHash} left out because no observer is in its location.
     *
     * <p>An overload rather than a separate {@code computeFarHash} the replicator would have to
     * combine: there is still exactly one rate-limited trigger, one field to remember and one
     * comparison to make in {@code sendSetIfChanged}, and the "at most one set per
     * {@code SOFT_RESYNC_INTERVAL_MILLIS}" guarantee stays a property of a single hash instead of
     * something the caller has to reassemble correctly. The one-argument form is kept for the
     * hash-semantics tests and for anyone reasoning about health/text alone.
     *
     * <p>A far fleet despawning, spawning, jumping or toggling its transponder therefore costs one
     * full set per 10 s no matter how many of them do it, and the guest still gets the complete
     * picture on that set — the payload never shrank.
     */
    public static String computeSoftHash(List<CoopNpcFleetSnapshot> fleets,
                                         Set<String> observerLocationIds) {
        List<String> farRecords = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                if (!isNear(fleet, observerLocationIds)) {
                    farRecords.add(structuralRecord(fleet));
                }
            }
        }
        farRecords.sort(null);
        return CoopChecksum.sha256Text(
                computeSoftHash(fleets) + "\n" + String.join("\n", farRecords));
    }

    /**
     * Order-independent hash over every member's CR and hull fraction, bucketed to 5%. The health
     * half of the rate-limited {@link #computeSoftHash} trigger (it was that trigger outright until
     * 2026-09-13, when action text joined it): {@link #computeSetHash} is structural on purpose, and
     * the 10 Hz {@code NPC_FLEET_MOTION} datagram carries neither CR nor hull, so before this existed
     * a fleet that repaired from 30% hull to full produced no wire traffic at all and the guest's
     * mirror showed the damage until some unrelated field of some fleet happened to move.
     *
     * <p>The 5% buckets and the replicator's 10 s floor are what keep this from re-creating the
     * 2026-08-17 rebuild storm the structural hash was carved out to stop: this hash only decides
     * <em>whether to send</em>, and the guest's receive path treats the arriving set as unchanged
     * structure and paints CR/hull onto the existing members in place
     * ({@code CoopFleetMirror#updateMemberState}). A percent-accurate hash would fire every second on
     * any repairing fleet; a 5% step on a fleet under repair fires a few times per recovery.
     */
    public static String computeHealthHash(List<CoopNpcFleetSnapshot> fleets) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                List<String> members = new ArrayList<>();
                for (CoopFleetSnapshot.Member member : fleet.members()) {
                    members.add(member.fleetMemberId() + ":" + healthBucket(member.cr())
                            + "/" + healthBucket(member.hullFraction()));
                }
                members.sort(null);
                records.add(fleet.coopFleetId() + "|" + String.join(",", members));
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(String.join("\n", records));
    }

    /** A 0..1 fraction onto 5% steps; a NaN reading buckets as 0 rather than poisoning the hash. */
    private static int healthBucket(float value) {
        return Float.isNaN(value) ? 0 : Math.round(value * 20f);
    }

    public String encode() {
        StringBuilder out = new StringBuilder(64 + fleets.size() * 96);
        out.append(Integer.toString(fleets.size())).append('|').append(CoopFleetCodec.escape(setHash));
        for (CoopNpcFleetSnapshot fleet : fleets) {
            out.append('\n').append(CoopFleetCodec.escape(fleet.encode()));
        }
        return out.toString();
    }

    public static CoopNpcFleetSetSnapshot decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty NPC fleet set");
        }
        List<String> header = CoopFleetCodec.split(lines[0]);
        if (header.size() != 2) {
            throw new IllegalArgumentException("Expected 2 set header fields, got " + header.size());
        }
        int count = Integer.parseInt(header.get(0));
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " fleets but only "
                    + (lines.length - 1) + " fleet lines present");
        }
        List<CoopNpcFleetSnapshot> fleets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fleets.add(CoopNpcFleetSnapshot.decode(CoopFleetCodec.unescape(lines[i + 1])));
        }
        return new CoopNpcFleetSetSnapshot(fleets, header.get(1));
    }
}
