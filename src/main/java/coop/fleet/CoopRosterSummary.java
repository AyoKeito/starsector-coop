package coop.fleet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a fleet roster as one short, greppable line: {@code "hound x2, cerberus x1, nebula x3"}.
 *
 * <p>Exists so the host's capture diagnostic and the guest's build diagnostic print the <em>same</em>
 * shape, which is the whole point: put the two lines side by side and any divergence between what the
 * host read off its live {@code FleetData} and what the guest actually created is visible without
 * opening a save. Hull ids (not variant ids) are the unit, because the failure this was written for —
 * a varied patrol arriving on the guest as N identical civilian freighters (2026-08-19) — is a
 * hull-level divergence, and variant ids would bury it in noise.
 *
 * <p>Order is first appearance, not sorted: roster order is itself evidence (the mirror builds in
 * snapshot order, and {@code CoopFleetMirror#updateMemberState} pairs CR/hull by list position).
 */
final class CoopRosterSummary {

    /** What an empty roster prints as, so an empty line is never mistaken for a missing field. */
    static final String EMPTY = "(none)";

    private CoopRosterSummary() {
    }

    /** Summarises the hull ids of a snapshot's members (what the host says the fleet contains). */
    static String ofMembers(List<CoopFleetSnapshot.Member> members) {
        List<String> hullIds = new ArrayList<>(members == null ? 0 : members.size());
        if (members != null) {
            for (CoopFleetSnapshot.Member member : members) {
                if (member != null) {
                    hullIds.add(member.hullId());
                }
            }
        }
        return ofHullIds(hullIds);
    }

    /** Summarises a plain list of hull ids (what a client actually built). */
    static String ofHullIds(List<String> hullIds) {
        if (hullIds == null || hullIds.isEmpty()) {
            return EMPTY;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String hullId : hullIds) {
            String key = hullId == null || hullId.isEmpty() ? "?" : hullId;
            counts.merge(key, 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder(counts.size() * 16);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return out.toString();
    }
}
