package coop.campaign;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import coop.util.CoopLog;

/**
 * Phase 32 addition A: the guest's {@code hostMarketId <-> localMarketId} translation table for
 * hidden-base markets.
 *
 * <p><b>Why it has to exist.</b> Almost every market id in the game agrees across the two engines
 * by construction. Gen-time markets are minted from the seed-locked worldgen, and a colony reuses
 * its planet's {@code "market_" + planetId} planet-condition market
 * ({@code PlanetConditionGenerator.java:134}), so both engines arrive at the same string. Dynamic
 * pirate and Luddic-Path bases are the exception: {@code CoopBaseAuthority} reconstructs the host's
 * base on the guest from a {@code (kind, systemId)} identity, and the vanilla constructor mints its
 * own market with {@code Global.getFactory().createMarket(Misc.genUID(), ...)}
 * ({@code PirateBaseIntel.java:173}, {@code LuddicPathBaseIntel.java:122}). The two ids are
 * unrelated random strings. Every market-id-keyed message — {@code MARKET_OPEN},
 * {@code MARKET_SNAPSHOT}, {@code MARKET_TXN}, {@code WORLD_DELTA(STORAGE_UNLOCK)} — therefore
 * failed to resolve on the far side ({@code economy.getMarket(id)} returned null) and a hidden
 * base's trade screen opened unsynced.
 *
 * <p><b>Identity when unmapped is the whole design.</b> {@link #toWire(String)} and
 * {@link #toLocal(String)} return their argument for anything they have not been taught, so the
 * ~150 colony and gen-time markets — whose ids already agree — pass through untouched and no caller
 * needs to know whether the market it is naming is a base. On the host the table is always empty
 * and both directions are the identity function, which is correct: the host's ids <em>are</em> the
 * wire ids.
 *
 * <p><b>Rebuilt, never persisted.</b> {@code CoopBaseAuthority} refills the table from the desired
 * base set on every guest reconcile, matching each host record to the live local base of the same
 * {@code (kind, systemId)}. The host resends the whole {@code BASE_SET} on every session edge (its
 * {@code lastSetHash} is cleared by {@code reset()}), so a reload — which changes nothing about the
 * bases but does throw away this in-memory table — repopulates it on the first reconcile after the
 * reconnect, including the case where the reconcile itself has no work to do because the bases are
 * already there.
 *
 * <p>This class holds no engine references and does no engine work, so it is exercised directly by
 * {@code CoopMarketIdsTest}. The one side effect it triggers is {@link Listener#onMapped}, which
 * {@code CoopCampaignReplicator} wires to the storage-unlock flag migration: a
 * {@code STORAGE_UNLOCK} that arrived before the base was mapped is flagged under the host's id,
 * and learning the mapping is the moment that flag can be moved to the local market.
 */
public final class CoopMarketIds {

    /**
     * Notified once for every mapping this table newly learns.
     *
     * @param hostMarketId          the host's id for the base's market
     * @param localMarketId         the id this engine now knows that market by
     * @param previousLocalMarketId the local id this mapping displaced, or null when it displaced
     *                              nothing. A base destroyed and rebuilt in the same system keeps
     *                              its {@code (kind, systemId)} identity but gets a fresh
     *                              {@code Misc.genUID()} market, so the listener's state may be
     *                              parked under the dead id rather than under the host's (red-team
     *                              P1-4: a storage flag stranded there left the rebuilt base locked).
     */
    @FunctionalInterface
    public interface Listener {
        void onMapped(String hostMarketId, String localMarketId, String previousLocalMarketId);
    }

    /** Insertion-ordered so {@link #mappings()} and the bridge dump read the same way twice. */
    private final Map<String, String> hostToLocal = new LinkedHashMap<>();
    private final Map<String, String> localToHost = new LinkedHashMap<>();

    private Listener listener;

    /** Wires the mapping hook. One listener; the replicator is the only owner. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * The id to put on the wire for a market this engine calls {@code localId}. The local id itself
     * for everything that is not a mapped base, which is everything except hidden bases on a guest.
     */
    public String toWire(String localId) {
        return localId == null ? null : localToHost.getOrDefault(localId, localId);
    }

    /**
     * The id this engine knows a wire id by. The wire id itself when unmapped — on the host that is
     * always, and on the guest that is every market except a mirrored hidden base.
     *
     * <p>Idempotent for a local id, and explicitly so: the reverse direction is consulted first and
     * an id this table already knows as local is returned untouched. Per-mapping the two keyspaces
     * cannot collide, but across the table they can — base A's local id and base B's host id are
     * independent {@code Misc.genUID()} draws from two engines' counters, so nothing rules out the
     * same string appearing as a value of one mapping and a key of another. The probability is
     * negligible and the consequence was not (red-team P2-8: a message about base A silently
     * resolving onto base B's market, because {@code findMarket} translates defensively on ids that
     * are already local), so the short-circuit makes the invariant real instead of merely likely.
     */
    public String toLocal(String wireId) {
        if (wireId == null || localToHost.containsKey(wireId)) {
            return wireId;
        }
        return hostToLocal.getOrDefault(wireId, wireId);
    }

    /**
     * Teaches the table that the host's {@code hostMarketId} is this engine's {@code localMarketId}.
     *
     * <p>Blank ids are ignored (a base captured before its market existed carries an empty id), and
     * so is an identity pair: on the host every record's host id <em>is</em> its local id, and
     * storing ~10 self-mappings would only make the bridge dump lie about there being translation
     * going on.
     *
     * @return true when this call changed the table, which is also when {@link Listener#onMapped}
     *         fires
     */
    public boolean learn(String hostMarketId, String localMarketId) {
        if (isBlank(hostMarketId) || isBlank(localMarketId) || hostMarketId.equals(localMarketId)) {
            return false;
        }
        String previous = hostToLocal.get(hostMarketId);
        if (localMarketId.equals(previous)) {
            return false;
        }
        if (previous != null) {
            // A base was rebuilt locally (RECREATE, or a reload that re-minted it): the old local id
            // names a market that no longer exists, and leaving its reverse entry behind would make
            // toWire answer for a dead market.
            localToHost.remove(previous);
            CoopLog.info(CoopMarketIds.class, "Coop market id remapped host=" + hostMarketId
                    + " local=" + previous + " -> " + localMarketId);
        }
        hostToLocal.put(hostMarketId, localMarketId);
        localToHost.put(localMarketId, hostMarketId);
        CoopLog.info(CoopMarketIds.class,
                "Coop learned base market id host=" + hostMarketId + " local=" + localMarketId);
        notifyMapped(hostMarketId, localMarketId, previous);
        return true;
    }

    /**
     * Session teardown: the table describes one live campaign and must not outlive it.
     *
     * <p><b>One owner.</b> {@code CoopCampaignReplicator.dispose()} is the only caller (red-team
     * P2-10). {@code CoopBaseAuthority.reset()} used to clear it too, and its edge is
     * {@code isConnected()} alone while the replicator's is
     * {@code isConnected() || reconnect.active()} — so every session edge and every reconnect resume
     * wiped a table the replicator was still translating against, opening a multi-second window in
     * which a hidden base's traffic went out under an id the host could not resolve. A stale entry
     * for a base that is gone costs nothing: {@code findMarket} returns null for it and says so.
     */
    public void clear() {
        hostToLocal.clear();
        localToHost.clear();
    }

    /** True when this engine holds a translation for the local market with this id. */
    public boolean isMappedLocal(String localId) {
        return localId != null && localToHost.containsKey(localId);
    }

    /** Every mapping, {@code hostMarketId -> localMarketId}, for the bridge dump and the logs. */
    public Map<String, String> mappings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(hostToLocal));
    }

    public int size() {
        return hostToLocal.size();
    }

    private void notifyMapped(String hostMarketId, String localMarketId, String previousLocalMarketId) {
        Listener target = listener;
        if (target == null) {
            return;
        }
        try {
            target.onMapped(hostMarketId, localMarketId, previousLocalMarketId);
        } catch (RuntimeException | LinkageError ex) {
            // The table is the load-bearing part; a failing side effect must not cost us the mapping.
            CoopLog.warn(CoopMarketIds.class, "Market-id mapping listener failed for host="
                    + hostMarketId + " local=" + localMarketId, ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "CoopMarketIds" + Objects.toString(hostToLocal, "{}");
    }
}
