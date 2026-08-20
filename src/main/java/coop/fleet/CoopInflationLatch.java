package coop.fleet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Remembers the fitted state fleet <em>inflation</em> gives an NPC fleet, so a capture taken while
 * the engine has that fleet <em>deflated</em> still reports the ships it really has.
 *
 * <p><b>The defect this exists for (measured 2026-08-20, Phase 16 smoke).</b> An NPC fleet with a
 * {@code FleetInflater} lives in two different shapes. Deflated it is the stock roster the spawner
 * asked for ({@code atlas}, {@code enforcer}); inflated, {@code DefaultFleetInflater} has autofit it
 * — swapping in D hulls ({@code atlas_default_D}) and adding d-mods. The engine flips a fleet between
 * those two shapes freely as the player moves around, and the host capture faithfully streamed
 * whichever shape it found. Since Phase 16 put the per-member variant id and d-/S-mod fields into
 * {@code CoopFleetSnapshot#computeFleetHash}, that flip changes the structural fleet hash, and the
 * hash is the gate on a full mirror-roster teardown and rebuild on the guest. One convoy alternating
 * between hash {@code a53b7877…} (deflated) and {@code 0844f99f…} (inflated) rebuilt its 24-ship
 * mirror five times in 45 seconds of play.
 *
 * <p><b>The rule.</b> A capture that reflects the fleet's real fitted state — it is inflated, or it
 * has no inflater and so has nothing to deflate — is recorded here verbatim, per fleet member id.
 * A capture taken while the fleet has an inflater and is not currently inflated replays the recorded
 * fields over the stripped ones, so the wire never sees the deflated shape of a fleet that has been
 * seen inflated. A fleet never yet seen inflated has nothing latched and streams exactly as it did
 * before Phase 16.
 *
 * <p><b>Storage.</b> Weakly keyed on the fleet object itself, so a despawned fleet's entry is
 * collected with it and no separate pruning pass is needed. Recorded entries are replaced wholesale
 * on each authoritative capture, which is what drops members the fleet has genuinely lost; while a
 * fleet is deflated nothing is written, so a member lost <em>during</em> deflation simply leaves an
 * entry nobody looks up.
 *
 * <p><b>Threading.</b> Capture runs only on the campaign thread (Thread-2), so the maps here are
 * deliberately unsynchronised. Anything that starts capturing off-thread must revisit that.
 */
final class CoopInflationLatch {

    /** The per-member fields inflation writes and deflation throws away. */
    record Fitted(String hullId, String variantId, String dmodIds, String sModIds,
                  String sModdedBuiltInIds) {

        static Fitted of(CoopFleetSnapshot.Member member) {
            return new Fitted(member.hullId(), member.variantId(), member.dmodIds(),
                    member.sModIds(), member.sModdedBuiltInIds());
        }

        boolean matches(CoopFleetSnapshot.Member member) {
            return hullId.equals(member.hullId())
                    && variantId.equals(member.variantId())
                    && dmodIds.equals(member.dmodIds())
                    && sModIds.equals(member.sModIds())
                    && sModdedBuiltInIds.equals(member.sModdedBuiltInIds());
        }

        /** The same member, wearing the latched fit instead of the freshly-read (stripped) one. */
        CoopFleetSnapshot.Member onto(CoopFleetSnapshot.Member member) {
            return new CoopFleetSnapshot.Member(member.fleetMemberId(), hullId, variantId,
                    member.shipName(), member.captainName(), member.cr(), member.hullFraction(),
                    dmodIds, sModIds, sModdedBuiltInIds);
        }
    }

    private final Map<Object, Map<String, Fitted>> byFleet = new WeakHashMap<>();

    /**
     * Records or replays one fleet's fitted state.
     *
     * @param fleet         the fleet handle, used as the weak key; identity in practice, since no
     *                      engine fleet redefines equality
     * @param authoritative {@code true} when {@code captured} is the fleet's real fitted state
     *                      (inflated, or no inflater at all — every player fleet takes this branch)
     * @param captured      what the capture just read, never null
     * @return the members to put on the wire: {@code captured} itself when nothing was replayed
     */
    List<CoopFleetSnapshot.Member> reconcile(Object fleet, boolean authoritative,
                                             List<CoopFleetSnapshot.Member> captured) {
        if (fleet == null || captured == null) {
            return captured;
        }
        return authoritative ? record(fleet, captured) : replay(fleet, captured);
    }

    private List<CoopFleetSnapshot.Member> record(Object fleet,
                                                  List<CoopFleetSnapshot.Member> captured) {
        Map<String, Fitted> fitted = new HashMap<>(Math.max(4, captured.size() * 2));
        for (CoopFleetSnapshot.Member member : captured) {
            if (member != null) {
                fitted.put(member.fleetMemberId(), Fitted.of(member));
            }
        }
        byFleet.put(fleet, fitted);
        return captured;
    }

    private List<CoopFleetSnapshot.Member> replay(Object fleet,
                                                  List<CoopFleetSnapshot.Member> captured) {
        Map<String, Fitted> fitted = byFleet.get(fleet);
        if (fitted == null || fitted.isEmpty()) {
            return captured;
        }
        List<CoopFleetSnapshot.Member> out = null;
        for (int i = 0; i < captured.size(); i++) {
            CoopFleetSnapshot.Member member = captured.get(i);
            Fitted latched = member == null ? null : fitted.get(member.fleetMemberId());
            if (latched == null || latched.matches(member)) {
                if (out != null) {
                    out.add(member);
                }
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(captured.subList(0, i));
            }
            out.add(latched.onto(member));
        }
        return out == null ? captured : out;
    }

    /** Test seam: how many fleets currently hold a latched fit. */
    int trackedFleetCount() {
        return byFleet.size();
    }
}
