package coop.combat;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.debug.CoopOwnFleetProbe;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 33: applies an {@link CoopAllyBattleOutcome} to the owner's <em>real</em> fleet. This is the
 * one place in the mod that is allowed to remove ships from, or write hull and CR on, the local
 * player's fleet; everything else that touches a {@code FleetMemberAPI} is guarded by
 * {@link CoopOwnFleetProbe#noteWrite} and treated as a bug when it lands here.
 *
 * <p>The piloting engine is the authority: its battle already happened and its numbers are final.
 * The applier therefore writes what it is told and reports what it did; it never argues with a
 * survivor's hull fraction or refuses a destruction. The two things it does guard against are
 * <b>unknown ids</b> (a ship the owner no longer has, or never had: skipped and reported, never
 * matched by position) and <b>double application</b> (a re-sent outcome finds its destroyed ids gone
 * and its survivors already at the reported values, so the second pass changes nothing and the
 * report says so).
 *
 * <p>A ship listed as destroyed is removed even if it is the flagship or the last ship; the owner
 * chose to let its fleet fight, and Phase 17's wipe handling covers an empty player fleet the same
 * way it covers one lost in the owner's own battle.
 *
 * <p>The logic is pure over {@link PlayerFleetOps} so it is unit-tested without an engine;
 * {@link #ops(CampaignFleetAPI)} is the engine adapter.
 */
public final class CoopAllyLossApplier {

    /** Hull or CR has to drop by at least this much before a ship is reported as damaged. */
    static final float DAMAGE_EPSILON = 0.005f;

    /** How many ship names a banner lists per group before it says "and N more". */
    static final int BANNER_NAMES_MAX = 4;

    private CoopAllyLossApplier() {
    }

    /** The owner's fleet as the applier needs it: ids, a display name per id, and the four writes. */
    public interface PlayerFleetOps {
        /** Current member ids in roster order. */
        List<String> memberIds();

        /** Display name for a ship, e.g. {@code "Wolf ISS Kestrel"}; never null. */
        String describe(String memberId);

        float hullFraction(String memberId);

        float cr(String memberId);

        /** @return false when the engine refused; the report counts it and moves on */
        boolean remove(String memberId);

        boolean setHullFraction(String memberId, float value);

        boolean setCr(String memberId, float value);

        /** Called once after the last write, whether or not anything changed. */
        void finish();
    }

    /**
     * What one application did.
     *
     * @param removed      display names of the ships taken out of the fleet, in outcome order
     * @param damaged      display names of the survivors whose hull or CR went down
     * @param unknownIds   ids the outcome named that the fleet does not hold; skipped
     * @param failedWrites writes the engine refused (a remove or a set that returned false)
     */
    public record Report(List<String> removed, List<String> damaged, List<String> unknownIds,
                         int failedWrites) {
        public Report {
            removed = List.copyOf(removed);
            damaged = List.copyOf(damaged);
            unknownIds = List.copyOf(unknownIds);
        }

        public boolean changedAnything() {
            return !removed.isEmpty() || !damaged.isEmpty();
        }

        public static Report nothing() {
            return new Report(List.of(), List.of(), List.of(), 0);
        }
    }

    /**
     * Applies {@code outcome} through {@code ops}. Destroyed ids are removed first; a survivor entry
     * for a destroyed id is ignored. Survivors get the reported hull fraction and CR whether the
     * numbers went up or down, and count as damaged only when one of them dropped.
     */
    public static Report apply(CoopAllyBattleOutcome outcome, PlayerFleetOps ops) {
        if (outcome == null || ops == null) {
            return Report.nothing();
        }
        List<String> removed = new ArrayList<>();
        List<String> damaged = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int failed = 0;
        try {
            Set<String> present = new LinkedHashSet<>(ops.memberIds());
            Set<String> destroyed = new HashSet<>();
            for (String id : outcome.destroyedMemberIds()) {
                if (!destroyed.add(id)) {
                    continue;
                }
                if (!present.contains(id)) {
                    unknown.add(id);
                    continue;
                }
                String name = ops.describe(id);
                if (ops.remove(id)) {
                    present.remove(id);
                    removed.add(name);
                } else {
                    failed++;
                }
            }
            Map<String, CoopAllyBattleOutcome.Survivor> byId = new LinkedHashMap<>();
            for (CoopAllyBattleOutcome.Survivor survivor : outcome.survivors()) {
                if (!survivor.memberId().isEmpty() && !destroyed.contains(survivor.memberId())) {
                    byId.putIfAbsent(survivor.memberId(), survivor);
                }
            }
            for (CoopAllyBattleOutcome.Survivor survivor : byId.values()) {
                String id = survivor.memberId();
                if (!present.contains(id)) {
                    unknown.add(id);
                    continue;
                }
                float hullBefore = ops.hullFraction(id);
                float crBefore = ops.cr(id);
                boolean hullOk = ops.setHullFraction(id, survivor.hullFraction());
                boolean crOk = ops.setCr(id, survivor.cr());
                if (!hullOk) {
                    failed++;
                }
                if (!crOk) {
                    failed++;
                }
                boolean dropped = (hullOk && hullBefore - survivor.hullFraction() > DAMAGE_EPSILON)
                        || (crOk && crBefore - survivor.cr() > DAMAGE_EPSILON);
                if (dropped) {
                    damaged.add(ops.describe(id));
                }
            }
        } finally {
            ops.finish();
        }
        return new Report(removed, damaged, unknown, failed);
    }

    /**
     * The owner's one-line banner. {@code partnerName} is the piloting player's display name; blank
     * falls back to "your partner".
     */
    public static String banner(String partnerName, Report report) {
        String partner = partnerName == null || partnerName.trim().isEmpty()
                ? "your partner" : partnerName.trim();
        if (report == null || !report.changedAnything()) {
            return "Your fleet fought alongside " + partner + " and came through untouched.";
        }
        StringBuilder out = new StringBuilder("Your fleet fought alongside ").append(partner).append('.');
        if (!report.removed().isEmpty()) {
            out.append(" Lost: ").append(names(report.removed())).append('.');
        }
        if (!report.damaged().isEmpty()) {
            out.append(" Damaged: ").append(names(report.damaged())).append('.');
        }
        return out.toString();
    }

    static String names(List<String> names) {
        StringBuilder out = new StringBuilder();
        int shown = Math.min(names.size(), BANNER_NAMES_MAX);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i));
        }
        int more = names.size() - shown;
        if (more > 0) {
            out.append(" and ").append(more).append(" more");
        }
        return out.toString();
    }

    // ---- engine adapter ---------------------------------------------------------------------------

    /**
     * {@link PlayerFleetOps} over a live fleet. Each write is recorded with
     * {@link CoopOwnFleetProbe#noteSanctionedWrite} so the own-fleet probe's history names this path
     * instead of flagging it. Engine exceptions on a single write are swallowed into a {@code false}
     * return; the report counts them.
     */
    public static PlayerFleetOps ops(CampaignFleetAPI fleet) {
        return new EngineOps(fleet);
    }

    private static final class EngineOps implements PlayerFleetOps {
        private final CampaignFleetAPI fleet;
        private final Map<String, FleetMemberAPI> members = new LinkedHashMap<>();

        EngineOps(CampaignFleetAPI fleet) {
            this.fleet = fleet;
            if (fleet != null && fleet.getFleetData() != null) {
                for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                    if (member != null && member.getId() != null) {
                        members.put(member.getId(), member);
                    }
                }
            }
        }

        @Override
        public List<String> memberIds() {
            return new ArrayList<>(members.keySet());
        }

        @Override
        public String describe(String memberId) {
            FleetMemberAPI member = members.get(memberId);
            if (member == null) {
                return memberId == null ? "" : memberId;
            }
            String hull = "";
            String name = "";
            try {
                hull = member.getHullSpec() == null ? "" : member.getHullSpec().getHullName();
            } catch (RuntimeException | LinkageError ignored) {
                // display only
            }
            try {
                name = member.getShipName() == null ? "" : member.getShipName();
            } catch (RuntimeException | LinkageError ignored) {
                // display only
            }
            String joined = (hull + " " + name).trim();
            return joined.isEmpty() ? memberId : joined;
        }

        @Override
        public float hullFraction(String memberId) {
            FleetMemberAPI member = members.get(memberId);
            try {
                return member == null ? 1f : member.getStatus().getHullFraction();
            } catch (RuntimeException | LinkageError ex) {
                return 1f;
            }
        }

        @Override
        public float cr(String memberId) {
            FleetMemberAPI member = members.get(memberId);
            try {
                return member == null ? 0f : member.getRepairTracker().getCR();
            } catch (RuntimeException | LinkageError ex) {
                return 0f;
            }
        }

        @Override
        public boolean remove(String memberId) {
            FleetMemberAPI member = members.get(memberId);
            if (member == null || fleet == null) {
                return false;
            }
            try {
                CoopOwnFleetProbe.noteSanctionedWrite(member, "allyBattleResult.remove");
                fleet.getFleetData().removeFleetMember(member);
                members.remove(memberId);
                return true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopAllyLossApplier.class, "Coop ally loss: could not remove " + memberId, ex);
                return false;
            }
        }

        @Override
        public boolean setHullFraction(String memberId, float value) {
            FleetMemberAPI member = members.get(memberId);
            if (member == null) {
                return false;
            }
            try {
                CoopOwnFleetProbe.noteSanctionedWrite(member, "allyBattleResult.hull");
                member.getStatus().setHullFraction(value);
                return true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopAllyLossApplier.class, "Coop ally loss: could not set hull on " + memberId, ex);
                return false;
            }
        }

        @Override
        public boolean setCr(String memberId, float value) {
            FleetMemberAPI member = members.get(memberId);
            if (member == null) {
                return false;
            }
            try {
                CoopOwnFleetProbe.noteSanctionedWrite(member, "allyBattleResult.cr");
                member.getRepairTracker().setCR(value);
                member.setStatUpdateNeeded(true);
                return true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopAllyLossApplier.class, "Coop ally loss: could not set CR on " + memberId, ex);
                return false;
            }
        }

        @Override
        public void finish() {
            if (fleet == null) {
                return;
            }
            try {
                fleet.getFleetData().setSyncNeeded();
                fleet.forceSync();
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopAllyLossApplier.class, "Coop ally loss: fleet sync after apply failed", ex);
            }
        }
    }
}
