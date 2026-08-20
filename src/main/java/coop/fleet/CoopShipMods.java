package coop.fleet;

import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * D-mod and S-mod replication for mirror rosters (Phase 16 fold-in of the Phase 14b known issue).
 *
 * <p>Phase 14b deliberately streams the <em>stock</em> variant a host ship was autofit from, because
 * the runtime variant the engine autofit it <em>to</em> cannot exist on the other install (see
 * {@link CoopFleetSnapshot.Member}). The accepted cost was that a battered, story-pointed host ship
 * mirrored as a pristine stock one: a player sizing up the other side's fleet — or a target NPC fleet —
 * read it as weaker or stronger than it really was. This class closes that gap without giving up the
 * stock-id contract: the permanent hullmods ride as their own stock hullmod ids (identical on both
 * installs by the handshake's manifest check) and are re-applied on top of the clean ship the receiver
 * builds.
 *
 * <p><b>Three id lists, because the engine keeps three (verified against the 0.98a API and
 * {@code HullVariantSpec}).</b>
 *
 * <ul>
 *   <li><b>D-mods</b> — perma-mods whose spec carries {@code Tags.HULLMOD_DMOD}, added by
 *       {@code DModManager} as {@code addPermaMod(id, false)}. They come with a hull-spec swap to the
 *       generated {@code <hullId>_default_D}, which is why {@link VariantOps#setDamagedHull} exists.</li>
 *   <li><b>S-mods</b> — {@code variant.getSMods()}, written only by {@code addPermaMod(id, true)}
 *       ({@code HullVariantSpec.addPermaMod} adds to {@code permaMods} <em>and</em> {@code sMods}).
 *       Both players' own refits and NPC fleets carry these: the fleet inflater passes a
 *       {@code maxSmods} budget into {@code CoreAutofitPlugin.doFit}
 *       ({@code DefaultFleetInflater.java:493-496}), which spends it through
 *       {@code current.addPermaMod(id, true)} ({@code CoreAutofitPlugin.java:576}).</li>
 *   <li><b>S-modded built-ins</b> — {@code variant.getSModdedBuiltIns()}, a <em>separate</em> set for
 *       hullmods already built into the hull that a story point upgraded. It is disjoint in intent from
 *       {@code sMods}: {@code BaseHullMod.isSMod} has to ask both
 *       ({@code getSMods().contains(id) || getSModdedBuiltIns().contains(id)}), and
 *       {@code CoreAutofitPlugin} skips any {@code sMods} entry that is also a built-in
 *       ({@code CoreAutofitPlugin.java:386-387}) — the filter this class applies at capture time. Only
 *       the player's own refit screen writes this set; the inflater never does, so it matters for
 *       player mirrors rather than replicated NPC fleets.</li>
 * </ul>
 *
 * <p><b>Plain (non-S) perma-mods need no field of their own.</b> On both paths that produce them,
 * {@code permaMods} is exactly the union of the d-mods and the S-mods: {@code DModManager} writes
 * {@code addPermaMod(id, false)} only for {@code HULLMOD_DMOD}-tagged specs, and the autofit plugin
 * writes {@code addPermaMod(id, true)} only for S-mods. A hull's own built-in mods live on the hull
 * spec, which the receiver already reproduces from {@code hullId}/{@code variantId}. The
 * non-permanent half of an autofit loadout (weapons, ordinary hullmods) stays out of scope — that is
 * the "full loadout replication" item still parked in {@code PHASE14_SPIKE_NOTES.md}.
 *
 * <p><b>The clone is the whole safety story.</b> A member created from a stock variant id carries the
 * <em>shared global spec object</em> for that variant, not a copy of it. Adding perma-mods to it in
 * place would not mod one mirror ship; it would mod every {@code falcon_Assault} in the receiving
 * player's universe, including the ones in their own fleet and every market's stock, for the rest of
 * the session. {@link #apply} therefore copies first, refuses outright if the copy came back identical
 * to the source, and only ever mutates the copy — the mutation path is expressed through
 * {@link VariantOps} so the invariant is enforced by a test rather than by reading the code.
 * ({@code HullVariantSpec.clone()} deep-copies {@code hullMods}, {@code permaMods}, {@code sMods} and
 * {@code sModdedBuiltIns}, so the copy is genuinely independent for all three lists.)
 */
final class CoopShipMods {

    /** Field-internal separator. Hullmod ids are spreadsheet ids and never contain a comma. */
    static final String SEPARATOR = ",";

    private CoopShipMods() {
    }

    /**
     * Renders a hullmod id list as one wire field, keeping only the ids {@code keep} accepts.
     *
     * <p>Sorted, because these fields feed {@link CoopFleetSnapshot#computeFleetHash}: two captures of
     * the same ship must produce the same bytes, and hullmod iteration order is not part of any
     * contract.
     */
    static String encode(Collection<String> hullModIds, Predicate<String> keep) {
        if (hullModIds == null || hullModIds.isEmpty()) {
            return "";
        }
        List<String> ids = new ArrayList<>(hullModIds.size());
        for (String id : hullModIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (keep == null || keep.test(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return "";
        }
        Collections.sort(ids);
        return String.join(SEPARATOR, ids);
    }

    /** Reverses {@link #encode}; an empty or blank field is simply "none of this kind". */
    static List<String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(4);
        for (String raw : encoded.split(SEPARATOR, -1)) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** How many ids a wire field declares, for the roster diagnostics. */
    static int count(String encoded) {
        return decode(encoded).size();
    }

    /**
     * The engine operations {@link #apply} needs, behind a seam so the clone-before-mutate invariant
     * is unit-testable without an engine. Implementations must never let {@link #copyOf} return the
     * argument itself.
     *
     * @param <V> the engine's variant type ({@code ShipVariantAPI} in the mirror).
     */
    interface VariantOps<V> {
        /** The variant currently installed on the member, or null when it cannot be read. */
        V currentVariant();

        /** An independent copy of {@code variant}; returning {@code variant} is a contract breach. */
        V copyOf(V variant);

        /** Runs the engine's own damaged-hull swap ({@code DModManager.setDHull}) on the copy. */
        void setDamagedHull(V variant);

        /** Adds one d-mod as an ordinary permanent hullmod on the copy. */
        void addDmod(V variant, String hullModId);

        /** Adds one story-pointed hullmod on the copy ({@code addPermaMod(id, true)}). */
        void addSMod(V variant, String hullModId);

        /** Marks one of the hull's built-in hullmods as story-pointed on the copy. */
        void addSModdedBuiltIn(V variant, String hullModId);

        /** Installs the finished copy on the member as its runtime variant. */
        void install(V variant);
    }

    /**
     * Re-applies the sender's permanent hullmods onto a freshly built mirror ship. No-op when the
     * member carries none of them, so a clean roster pays nothing.
     *
     * <p><b>Apply order: hull swap, then d-mods, then S-mods, then S-modded built-ins.</b> The swap
     * goes first so every later id resolves against the final hull spec, and it is safe there because
     * {@code DModManager.setDHull} does <em>not</em> replace the variant object — it only calls
     * {@code setSource(REFIT)} and {@code setHullSpecAPI(dHull)}
     * ({@code api_pristine/.../DModManager.java:39-50}), leaving {@code permaMods}/{@code sMods}/
     * {@code sModdedBuiltIns} untouched. The swap is skipped entirely when there are no d-mods: an
     * S-modded but undamaged ship must not arrive wearing a damaged hull.
     *
     * @return true when a modified variant was installed.
     */
    static <V> boolean apply(String dmodIds, String sModIds, String sModdedBuiltInIds,
                             VariantOps<V> ops) {
        List<String> dmods = decode(dmodIds);
        List<String> sMods = decode(sModIds);
        List<String> sModdedBuiltIns = decode(sModdedBuiltInIds);
        if (ops == null || (dmods.isEmpty() && sMods.isEmpty() && sModdedBuiltIns.isEmpty())) {
            return false;
        }
        V source = ops.currentVariant();
        if (source == null) {
            return false;
        }
        V copy = ops.copyOf(source);
        if (copy == null || copy == source) {
            // Refusing here is the point: mutating the shared global variant spec would d-mod or
            // S-mod every clean hull of that variant in the receiving player's universe.
            CoopLog.warn(CoopShipMods.class, "Coop refused to apply replicated hullmods (dmods="
                    + dmodIds + " sMods=" + sModIds + " sModdedBuiltIns=" + sModdedBuiltInIds
                    + "): the variant copy is the source variant itself, so applying them would"
                    + " mutate the shared variant spec. The mirror ship stays clean.");
            return false;
        }
        if (!dmods.isEmpty()) {
            ops.setDamagedHull(copy);
        }
        for (String id : dmods) {
            ops.addDmod(copy, id);
        }
        for (String id : sMods) {
            ops.addSMod(copy, id);
        }
        for (String id : sModdedBuiltIns) {
            ops.addSModdedBuiltIn(copy, id);
        }
        ops.install(copy);
        return true;
    }
}
