package coop.fleet;

/**
 * The fleet memory keys that mark a campaign fleet as a co-op mirror: {@link #PLAYER_MIRROR_TAG} for
 * the partner's player-fleet mirror, and {@link #NPC_MIRROR_TAG} for an NPC fleet replicated from the
 * host, keyed by its coop fleet id. Every cleanup and suppression path that has to tell a mirror apart
 * from a real fleet — the orphan sweeper, the NPC suppressor, the threat watcher, the battle bridge,
 * the visibility probe — recognises a mirror by one of these two strings. A mistyped copy in any one
 * of those paths would make it silently skip or delete the wrong fleets, so this class is the one place
 * the literals are spelled.
 */
public final class CoopMirrorTags {

    public static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    public static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    private CoopMirrorTags() {
    }
}
