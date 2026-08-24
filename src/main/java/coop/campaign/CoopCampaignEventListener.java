package coop.campaign;

import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.listeners.CargoScreenListener;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

import java.util.Objects;

/**
 * Bridges vanilla campaign events into {@link CoopCampaignReplicator} (Phase 12).
 *
 * <p>Registered on the sector ({@code addTransientListener}) for connected coop sessions, it
 * forwards the events Phase 12 replicates — player reputation changes (faction + person), market
 * open / cargo update, market transactions, ability activation, and economy ticks — to the supplied
 * {@link Sink}. It carries no policy of its own: role gating and the replay guard live in the
 * replicator, so this class stays a thin, engine-facing adapter (untested directly; the decision
 * logic it feeds is covered by the model unit tests).
 */
public final class CoopCampaignEventListener extends BaseCampaignEventListener
        implements CargoScreenListener {

    /** Callback surface implemented by {@link CoopCampaignReplicator}. */
    public interface Sink {
        /**
         * The local player left cargo pods behind (jettison, or cargo left in stable orbit). Phase
         * 12d replicates these so the partner can pick them up, which is the only item-transfer
         * route v1 has.
         */
        void onPlayerLeftCargoPods(SectorEntityToken pods);

        void onPlayerReputationChange(String factionId, float delta);

        void onPlayerReputationChange(PersonAPI person, float delta);

        /**
         * @param cargoUpdated true when the engine reached this through
         *                     {@code reportPlayerOpenedMarketAndCargoUpdated}, i.e. the submarket
         *                     plugins just restocked. Phase 12c gap 2e: the host re-broadcasts its
         *                     snapshot on that signal, because a restock silently rerolls the
         *                     canonical stock out from under the guest's copy.
         */
        void onPlayerOpenedMarket(MarketAPI market, boolean cargoUpdated);

        /**
         * The local player closed a market screen. Phase 18 uses it to confirm that a dialog whose
         * interaction claim was rejected is really gone; Phase 24's diff-on-close colony model is
         * the reason the signature carries the whole {@link MarketAPI} rather than an id.
         */
        void onPlayerClosedMarket(MarketAPI market);

        void onPlayerMarketTransaction(PlayerMarketTransaction transaction);

        void onPlayerActivatedAbility(AbilityPlugin ability, Object param);

        void onEconomyTick(int iterIndex);

        /**
         * A battle the local player took part in resolved (Phase 14). Enrichment only: the coop
         * battle window is opened and closed by {@code CoopBattleBridge}'s combat-frame / campaign-
         * resume seams, because neither of these callbacks fires for an engagement the player
         * disengaged from before contact. This only supplies the outcome string for
         * {@code BATTLE_END}.
         */
        void onBattleOccurred(boolean playerWon);

        /** Same, from {@code reportPlayerEngagement}: the engagement ran through to a result. */
        void onPlayerEngagement(boolean playerWon, boolean playerOutBeforeEnd);
    }

    private final Sink sink;

    public CoopCampaignEventListener(Sink sink) {
        super(false);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void reportPlayerReputationChange(String faction, float delta) {
        sink.onPlayerReputationChange(faction, delta);
    }

    @Override
    public void reportPlayerReputationChange(PersonAPI person, float delta) {
        sink.onPlayerReputationChange(person, delta);
    }

    @Override
    public void reportPlayerOpenedMarket(MarketAPI market) {
        sink.onPlayerOpenedMarket(market, false);
    }

    /**
     * Distinct from {@link #reportPlayerOpenedMarket}: this is the engine saying the submarket
     * plugins just restocked. Collapsing the two threw that signal away, which is why a market the
     * host reopened after a restock kept serving the guest a stale snapshot (Phase 12c gap 2e).
     */
    @Override
    public void reportPlayerOpenedMarketAndCargoUpdated(MarketAPI market) {
        sink.onPlayerOpenedMarket(market, true);
    }

    @Override
    public void reportPlayerClosedMarket(MarketAPI market) {
        sink.onPlayerClosedMarket(market);
    }

    @Override
    public void reportPlayerMarketTransaction(PlayerMarketTransaction transaction) {
        sink.onPlayerMarketTransaction(transaction);
    }

    @Override
    public void reportPlayerActivatedAbility(AbilityPlugin ability, Object param) {
        sink.onPlayerActivatedAbility(ability, param);
    }

    @Override
    public void reportEconomyTick(int iterIndex) {
        sink.onEconomyTick(iterIndex);
    }

    // ---- Battle lifecycle enrichment (Phase 14) -------------------------------------------------

    @Override
    public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (battle == null || !battle.isPlayerInvolved()) {
            return;
        }
        boolean playerWon;
        try {
            playerWon = primaryWinner != null && battle.isPlayerSide(battle.getSideFor(primaryWinner));
        } catch (RuntimeException | LinkageError ex) {
            playerWon = false;
        }
        sink.onBattleOccurred(playerWon);
    }

    @Override
    public void reportPlayerEngagement(EngagementResultAPI result) {
        if (result == null) {
            return;
        }
        sink.onPlayerEngagement(result.didPlayerWin(), result.isPlayerOutBeforeEnd());
    }

    // ---- CargoScreenListener (Phase 12d) --------------------------------------------------------

    @Override
    public void reportPlayerLeftCargoPods(SectorEntityToken entity) {
        sink.onPlayerLeftCargoPods(entity);
    }

    @Override
    public void reportCargoScreenOpened() {
    }

    @Override
    public void reportPlayerNonMarketTransaction(PlayerMarketTransaction transaction,
                                                 InteractionDialogAPI dialog) {
    }

    @Override
    public void reportSubmarketOpened(SubmarketAPI submarket) {
    }
}
