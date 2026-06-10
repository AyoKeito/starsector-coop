package coop.campaign;

import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.characters.PersonAPI;

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
public final class CoopCampaignEventListener extends BaseCampaignEventListener {

    /** Callback surface implemented by {@link CoopCampaignReplicator}. */
    public interface Sink {
        void onPlayerReputationChange(String factionId, float delta);

        void onPlayerReputationChange(PersonAPI person, float delta);

        void onPlayerOpenedMarket(MarketAPI market);

        void onPlayerMarketTransaction(PlayerMarketTransaction transaction);

        void onPlayerActivatedAbility(AbilityPlugin ability, Object param);

        void onEconomyTick(int iterIndex);
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
        sink.onPlayerOpenedMarket(market);
    }

    @Override
    public void reportPlayerOpenedMarketAndCargoUpdated(MarketAPI market) {
        sink.onPlayerOpenedMarket(market);
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
}
