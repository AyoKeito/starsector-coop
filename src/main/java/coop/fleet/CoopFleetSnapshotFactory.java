package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Captures the local player's fleet into a {@link CoopFleetSnapshot} for replication.
 *
 * <p>All engine reads are best-effort: a single ship that fails to report a field must not abort the
 * whole snapshot, because dropping one campaign tick of mirror state is harmless (the next 10 Hz
 * snapshot supersedes it).
 */
public final class CoopFleetSnapshotFactory {

    /** {@code Misc.D_HULL_SUFFIX}: the suffix {@code DModManager.setDHull} appends to a hull id. */
    static final String D_HULL_SUFFIX = "_default_D";

    /**
     * Holds each NPC fleet's inflated fit across the engine's deflations so the structural fleet hash
     * stops oscillating; see {@link CoopInflationLatch} for the measured defect. Static because the
     * capture entry points are static and the latch has to outlive a single call — it is weakly keyed
     * on the fleet, so it needs no lifecycle of its own. Written only from the campaign thread.
     */
    private static final CoopInflationLatch INFLATION_LATCH = new CoopInflationLatch();

    private CoopFleetSnapshotFactory() {
    }

    public static CoopFleetSnapshot captureLocalPlayer(SectorAPI sector, String playerId, String username) {
        Objects.requireNonNull(sector, "sector");
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet == null) {
            return null;
        }
        return capture(fleet, playerId, username);
    }

    public static CoopFleetSnapshot capture(CampaignFleetAPI fleet, String playerId, String username) {
        Objects.requireNonNull(fleet, "fleet");

        Vector2f location = fleet.getLocation();
        Vector2f velocity = fleet.getVelocity();
        String locationId = locationId(fleet.getContainingLocation());
        String factionId = factionId(fleet);
        boolean transponderOn = transponderOn(fleet);

        return CoopFleetSnapshot.create(
                playerId,
                username,
                locationId,
                location == null ? 0f : location.x,
                location == null ? 0f : location.y,
                velocity == null ? 0f : velocity.x,
                velocity == null ? 0f : velocity.y,
                factionId,
                transponderOn,
                // Phase 14b: the remote client pins this onto the mirror so its NPC AI detects the
                // remote player at vanilla ranges — transponder, Go Dark, burns, sensor burst, terrain.
                CoopSensorSync.capture(fleet),
                captureMembers(fleet));
    }

    /**
     * Captures a fleet's ship roster as replicable members, shared by the Phase 8 player snapshot and
     * the Phase 9 NPC fleet snapshots ({@link CoopNpcFleetReplicator}). Best-effort: a member that
     * fails to report a field is skipped rather than aborting the whole roster.
     *
     * <p><b>The per-member catch is load-bearing (2026-08-19).</b> It used to be a single try around
     * the whole loop, which meant one ship that threw while being read truncated the roster at that
     * point — and a throw on the <em>first</em> ship replicated the fleet as zero ships. That is not a
     * dropped frame: {@code CoopFleetSnapshot#computeFleetHash} of the truncated list is perfectly
     * stable, so the guest's {@code CoopFleetMirror#refreshRosterIfChanged} gate accepts it once and
     * then never rebuilds until the host fleet's real roster changes. The guest log for build 56b025f
     * shows the end state directly: 20 "roster refreshed to 0 ship(s)" lines, six of them inside a
     * single set apply.
     *
     * <p><b>Inflation latch (2026-08-20).</b> What leaves here for a fleet the engine has currently
     * deflated is its last <em>inflated</em> fit, not the stripped stock roster — see
     * {@link CoopInflationLatch}. Fleets without an inflater, the player's included, are untouched.
     */
    public static List<CoopFleetSnapshot.Member> captureMembers(CampaignFleetAPI fleet) {
        Objects.requireNonNull(fleet, "fleet");
        List<CoopFleetSnapshot.Member> members = new ArrayList<>();
        List<FleetMemberAPI> source;
        try {
            source = fleet.getFleetData().getMembersListCopy();
        } catch (RuntimeException ex) {
            // The fleet itself cannot report a roster: nothing to salvage.
            CoopLog.warn(CoopFleetSnapshotFactory.class,
                    "Coop could not read the fleet data of " + safeName(fleet), ex);
            return members;
        }
        if (source == null) {
            return members;
        }
        int skipped = captureInto(members, engineSource(source));
        if (skipped > 0) {
            CoopLog.warn(CoopFleetSnapshotFactory.class, "Coop skipped " + skipped
                    + " unreadable ship(s) while capturing the roster of " + safeName(fleet)
                    + "; the mirror will be short by that many");
        }
        return INFLATION_LATCH.reconcile(fleet, capturesRealFit(fleet), members);
    }

    /**
     * True when what we just read off this fleet is its real fitted state: the engine has inflated it,
     * or it has no {@code FleetInflater} and therefore nothing to deflate. Player fleets are always
     * the second case, so their capture is unchanged by the latch — it only ever records what was
     * already going on the wire.
     *
     * <p>{@code CampaignFleetAPI#getInflater()} / {@code #isInflated()} verified against the 0.98a API
     * sources ({@code api_pristine/com/fs/starfarer/api/campaign/CampaignFleetAPI.java:249,257}); the
     * pairing is vanilla's own idiom ({@code Misc.java:4857}).
     */
    private static boolean capturesRealFit(CampaignFleetAPI fleet) {
        try {
            return fleet.getInflater() == null || fleet.isInflated();
        } catch (RuntimeException | LinkageError ignored) {
            // A fleet that cannot answer either question degrades to pre-fix behaviour: stream what
            // was read rather than replay a latch we can no longer justify.
            return true;
        }
    }

    /**
     * The resilience rule on its own, behind a seam so it can be unit-tested without an engine: read
     * every slot, keep every slot that reads, and let a slot that throws cost exactly itself.
     *
     * @return how many members were skipped because reading them threw.
     */
    static int captureInto(List<CoopFleetSnapshot.Member> out, MemberSource source) {
        int skipped = 0;
        for (int i = 0; i < source.size(); i++) {
            boolean wing;
            try {
                wing = source.isFighterWing(i);
            } catch (RuntimeException ignored) {
                // A member that cannot answer "am I a wing?" is treated as one, i.e. not replicated.
                wing = true;
            }
            if (wing) {
                continue;
            }
            try {
                out.add(source.capture(i));
            } catch (RuntimeException ignored) {
                skipped++;
            }
        }
        return skipped;
    }

    /** One fleet's replicable ship slots. {@link #captureInto} assumes any call here can throw. */
    interface MemberSource {
        int size();

        boolean isFighterWing(int index);

        CoopFleetSnapshot.Member capture(int index);
    }

    private static MemberSource engineSource(List<FleetMemberAPI> members) {
        return new MemberSource() {
            @Override
            public int size() {
                return members.size();
            }

            @Override
            public boolean isFighterWing(int index) {
                FleetMemberAPI member = members.get(index);
                return member == null || member.isFighterWing();
            }

            @Override
            public CoopFleetSnapshot.Member capture(int index) {
                return captureMember(members.get(index));
            }
        };
    }

    private static String safeName(CampaignFleetAPI fleet) {
        try {
            String name = fleet.getName();
            return name == null ? "?" : name;
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    private static CoopFleetSnapshot.Member captureMember(FleetMemberAPI member) {
        ShipVariantAPI variant = null;
        try {
            variant = member.getVariant();
        } catch (RuntimeException ignored) {
            variant = null;
        }
        String variantId = streamableVariantId(
                originalVariantId(variant),
                variant == null ? "" : variant.getHullVariantId(),
                specIdOrEmpty(member),
                CoopFleetSnapshotFactory::variantExists);
        String hullId = streamableHullId(hullIdOrEmpty(member), CoopFleetSnapshotFactory::hullExists);

        String captainName = "";
        try {
            PersonAPI captain = member.getCaptain();
            if (captain != null && !captain.isDefault()) {
                captainName = captain.getNameString();
            }
        } catch (RuntimeException ignored) {
            captainName = "";
        }

        float cr = readFloat(() -> member.getRepairTracker().getCR(), 0f);
        float hullFraction = readFloat(() -> member.getStatus().getHullFraction(), 1f);

        return new CoopFleetSnapshot.Member(
                member.getId(),
                hullId,
                variantId,
                member.getShipName(),
                captainName,
                cr,
                hullFraction,
                captureDmodIds(variant),
                captureSModIds(variant),
                captureSModdedBuiltInIds(variant));
    }

    // ---- Permanent hullmod capture (Phase 16) ---------------------------------------------------

    /**
     * The variant's d-mods as the wire field. Best-effort like every other per-member read: a ship
     * that cannot report its hullmods replicates as a clean one rather than costing the whole roster.
     */
    private static String captureDmodIds(ShipVariantAPI variant) {
        if (variant == null) {
            return "";
        }
        try {
            return CoopShipMods.encode(variant.getHullMods(), CoopFleetSnapshotFactory::isDmodHullMod);
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /**
     * The variant's story-pointed hullmods, minus any that are S-modded <em>built-ins</em> — those
     * ride in their own field and must not be re-applied as ordinary perma-mods. The exclusion is the
     * engine's own: {@code CoreAutofitPlugin} skips exactly these when copying S-mods across
     * ({@code api_pristine/.../CoreAutofitPlugin.java:386-387}).
     */
    private static String captureSModIds(ShipVariantAPI variant) {
        if (variant == null) {
            return "";
        }
        try {
            Set<String> builtIns = variant.getSModdedBuiltIns();
            return CoopShipMods.encode(variant.getSMods(),
                    id -> builtIns == null || !builtIns.contains(id));
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /**
     * The hull's own built-in hullmods that a story point upgraded. Only the player's refit screen
     * writes this set — the fleet inflater spends its S-mod budget through
     * {@code addPermaMod(id, true)} and never touches it — so in practice this field is populated for
     * player mirrors and empty for replicated NPC fleets.
     */
    private static String captureSModdedBuiltInIds(ShipVariantAPI variant) {
        if (variant == null) {
            return "";
        }
        try {
            return CoopShipMods.encode(variant.getSModdedBuiltIns(), null);
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /**
     * The engine's own d-mod test, not a hardcoded id list: {@code DModManager.getNumDMods} counts a
     * hullmod as a d-mod exactly when its spec carries {@code Tags.HULLMOD_DMOD}
     * ({@code api_pristine/.../DModManager.java:333-339}), and {@code DModManager.getMod(id)} is just
     * {@code Global.getSettings().getHullModSpec(id)}. Reading the spec directly avoids loading
     * {@code DModManager}, whose static initializer touches {@code Global.getSettings()} eagerly.
     */
    private static boolean isDmodHullMod(String hullModId) {
        try {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(hullModId);
            return spec != null && spec.hasTag(Tags.HULLMOD_DMOD);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    // ---- Resolvable-by-construction ship ids (2026-08-19) --------------------------------------

    /**
     * Picks the variant id to put on the wire: the first candidate that actually exists in this
     * install's spec store, or {@code ""} when none do (the receiver then falls back to the hull).
     *
     * <p><b>Why validation and not just {@code getHullVariantId()}.</b> When a player fleet comes near
     * an NPC fleet the engine <em>inflates</em> it: {@code DefaultFleetInflater.inflate} autofits every
     * ship onto a brand-new variant whose id is literally
     * {@code Global.getSettings().createEmptyVariant(fleet.getId() + "_" + memberIndex, ...)}
     * ({@code api_pristine/.../fleets/DefaultFleetInflater.java:476}) and marks it
     * {@code VariantSource.REFIT} ({@code :497}). That id — {@code "905d_3"} — is derived from the
     * <em>host's</em> fleet id, is never persisted and never exists in the guest's spec store. The
     * inflater does record where it autofit from, though: {@code setOriginalVariant(target
     * .getHullVariantId())} when the target was a stock variant ({@code :480-482}), which is the
     * install-stock id both sides share. Prefer it.
     *
     * <p>Validating against the local spec store is sound for the remote side because the handshake
     * already requires an identical mod manifest, so "resolvable here" means "resolvable there".
     */
    public static String streamableVariantId(String originalVariantId, String hullVariantId,
                                             String specId,
                                             java.util.function.Predicate<String> variantExists) {
        for (String candidate : List.of(normalize(originalVariantId), normalize(hullVariantId),
                normalize(specId))) {
            if (!candidate.isEmpty() && variantExists.test(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * The hull id to put on the wire: the live one when it resolves, otherwise its non-D parent.
     *
     * <p>Inflation also swaps the hull spec itself — {@code DModManager.setDHull}
     * ({@code api_pristine/.../DModManager.java:38-49}) replaces it with
     * {@code Misc.getDHullId(spec)}, which is just {@code hullId + "_default_D"}
     * ({@code Misc.java:3552-3556}). Those D hulls are generated at load from the same ship data on
     * both installs, so they normally resolve fine and are kept — the fallback only matters for a hull
     * this install cannot name, where the base hull is still better than nothing.
     */
    static String streamableHullId(String hullId, java.util.function.Predicate<String> hullExists) {
        String live = normalize(hullId);
        if (live.isEmpty() || hullExists.test(live)) {
            return live;
        }
        String base = baseHullId(live);
        return !base.equals(live) && hullExists.test(base) ? base : live;
    }

    /**
     * Strips the auto-generated D-hull suffix, the exact inverse of {@code Misc.getDHullId}. Kept as a
     * literal rather than reading {@code Misc.D_HULL_SUFFIX} so this stays a pure function the tests
     * can drive without the engine on the classpath.
     */
    static String baseHullId(String hullId) {
        String value = normalize(hullId);
        return value.endsWith(D_HULL_SUFFIX)
                ? value.substring(0, value.length() - D_HULL_SUFFIX.length())
                : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static String originalVariantId(ShipVariantAPI variant) {
        try {
            return variant == null ? "" : normalize(variant.getOriginalVariant());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String specIdOrEmpty(FleetMemberAPI member) {
        try {
            return normalize(member.getSpecId());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String hullIdOrEmpty(FleetMemberAPI member) {
        try {
            return normalize(member.getHullId());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Public so the host-side battle-result reconciler can key its roster exactly as the wire does. */
    public static boolean variantExists(String variantId) {
        try {
            return Global.getSettings().doesVariantExist(variantId);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * {@code SettingsAPI} has no {@code doesHullExist} counterpart to {@code doesVariantExist}, and
     * {@code getHullSpec} <em>throws</em> {@code "Ship hull spec [x] not found!"} for an unknown id
     * rather than returning null — so the catch is the existence test, not defensive padding.
     */
    private static boolean hullExists(String hullId) {
        try {
            return Global.getSettings().getHullSpec(hullId) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String locationId(LocationAPI location) {
        if (location == null) {
            return "";
        }
        String id = location.getId();
        return id == null ? "" : id;
    }

    private static String factionId(CampaignFleetAPI fleet) {
        try {
            if (fleet.getFaction() != null) {
                return fleet.getFaction().getId();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
    }

    private static boolean transponderOn(CampaignFleetAPI fleet) {
        try {
            return fleet.isTransponderOn();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private interface FloatRead {
        float read();
    }

    private static float readFloat(FloatRead read, float fallback) {
        try {
            return read.read();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
