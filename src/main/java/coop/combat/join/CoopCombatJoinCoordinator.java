package coop.combat.join;

import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Orchestrates proximity vs remote participation when the partner begins a battle.
 *
 * <p>Injected into {@link coop.combat.CoopBattleBridge} so the upstream pause/banner path stays
 * intact while join-mode banners and remote-support prompts are layered on.
 */
public final class CoopCombatJoinCoordinator {

    private static volatile CoopCombatJoinCoordinator ACTIVE;

    private final CoopRemoteSupportPrompt prompt = new CoopRemoteSupportPrompt();
    private Function<SectorAPI, CoopPartnerSeparation> separationProbe = CoopPartnerSeparationProbe::measure;
    private Function<SectorAPI, Boolean> markedShipProbe = CoopMarkedRemoteShipProbe::hasMarkedNonFlagship;
    private CoopSharedBattleHooks hooks = CoopSharedBattleHooks.NOOP;

    private CoopCombatJoinMode lastMode = CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE;
    private String lastBattleId = "";

    /** Process-wide active coordinator (set by the live battle bridge). */
    public static CoopCombatJoinCoordinator active() {
        return ACTIVE;
    }

    public void attachAsActive() {
        ACTIVE = this;
    }

    public void detachIfActive() {
        if (ACTIVE == this) {
            ACTIVE = null;
        }
    }

    public CoopRemoteSupportPrompt prompt() {
        return prompt;
    }

    public CoopCombatJoinMode lastMode() {
        return lastMode;
    }

    public String lastBattleId() {
        return lastBattleId;
    }

    public void setSeparationProbe(Function<SectorAPI, CoopPartnerSeparation> probe) {
        this.separationProbe = Objects.requireNonNull(probe);
    }

    public void setMarkedShipProbe(Function<SectorAPI, Boolean> probe) {
        this.markedShipProbe = Objects.requireNonNull(probe);
    }

    public void setSharedBattleHooks(CoopSharedBattleHooks hooks) {
        this.hooks = hooks == null ? CoopSharedBattleHooks.NOOP : hooks;
    }

    /**
     * Called on the spectator when {@code BATTLE_BEGIN} arrives.
     *
     * @return extra banners to queue after the stock begin banner
     */
    public List<String> onPartnerBattleBegin(SectorAPI sector, String battleId, String partnerName,
                                             String enemySummary) {
        List<String> banners = new ArrayList<>();
        CoopPartnerSeparation separation = separationProbe.apply(sector);
        lastMode = CoopCombatJoinClassifier.classify(separation);
        lastBattleId = battleId == null ? "" : battleId.trim();

        CoopLog.info(CoopCombatJoinCoordinator.class,
                "Coop join mode=" + lastMode + " battleId=" + lastBattleId
                        + " known=" + separation.known()
                        + " sameLoc=" + separation.sameLocation()
                        + " dist=" + separation.distanceSu());

        if (!lastMode.isRemote()) {
            for (String line : prompt.beginProximityNotice(partnerName, enemySummary)) {
                banners.add(line);
            }
            hooks.onProximityJoinAvailable(lastBattleId, partnerName);
            return banners;
        }

        boolean hasMarked = Boolean.TRUE.equals(markedShipProbe.apply(sector));
        for (String line : prompt.beginRemoteCall(lastBattleId, partnerName, enemySummary, hasMarked)) {
            banners.add(line);
        }
        if (prompt.state() == CoopRemoteSupportPrompt.State.NO_MARKED_SHIP) {
            hooks.onRemoteSupportUnavailable(lastBattleId, CoopRemoteSupportPrompt.State.NO_MARKED_SHIP);
        }
        return banners;
    }

    public List<String> acceptRemoteSupport() {
        List<String> banners = new ArrayList<>();
        if (!prompt.accept()) {
            return banners;
        }
        banners.add(prompt.acceptBanner());
        hooks.onRemoteSupportAccepted(lastBattleId, "");
        return banners;
    }

    public List<String> declineRemoteSupport() {
        List<String> banners = new ArrayList<>();
        if (!prompt.decline()) {
            return banners;
        }
        banners.add(prompt.declineBanner());
        hooks.onRemoteSupportUnavailable(lastBattleId, CoopRemoteSupportPrompt.State.DECLINED);
        return banners;
    }

    public void onPartnerBattleEnd(String battleId) {
        String id = battleId == null ? "" : battleId.trim();
        hooks.onRemoteBattleEnded(id.isEmpty() ? lastBattleId : id);
        prompt.clear();
        lastBattleId = "";
        lastMode = CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE;
    }
}
