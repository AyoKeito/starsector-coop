package coop.combat.shared;

import coop.campaign.CoopDelimited;

/**
 * Portable claim payload for remote hyperspace support (mirrors Suite variant-tree intent).
 *
 * <p>V1 carries hull/name/variantId plus an optional opaque {@code treeBlob} (Base64 or delimited
 * tree). Empty treeBlob is allowed for proximity-only paths; remote claim prefers a non-empty tree.
 */
public final class CoopSharedCombatClaimCodec {

    private CoopSharedCombatClaimCodec() {
    }

    public record ClaimRequest(String battleId, String targetShipId, String hullId, String shipName,
                               String variantHint) {
        public ClaimRequest {
            battleId = CoopDelimited.normalize(battleId);
            targetShipId = CoopDelimited.normalize(targetShipId);
            hullId = CoopDelimited.normalize(hullId);
            shipName = CoopDelimited.normalize(shipName);
            variantHint = CoopDelimited.normalize(variantHint);
        }

        public String encode() {
            return CoopDelimited.field(battleId) + '|'
                    + CoopDelimited.field(targetShipId) + '|'
                    + CoopDelimited.field(hullId) + '|'
                    + CoopDelimited.field(shipName) + '|'
                    + CoopDelimited.field(variantHint);
        }

        public static ClaimRequest decode(String raw) {
            java.util.List<String> f = CoopDelimited.split(raw == null ? "" : raw);
            return new ClaimRequest(
                    f.size() > 0 ? f.get(0) : "",
                    f.size() > 1 ? f.get(1) : "",
                    f.size() > 2 ? f.get(2) : "",
                    f.size() > 3 ? f.get(3) : "",
                    f.size() > 4 ? f.get(4) : "");
        }
    }

    public record ClaimAck(boolean ok, String battleId, String hullId, String shipName,
                           float x, float y, float facing, String treeOrReason) {
        public ClaimAck {
            battleId = CoopDelimited.normalize(battleId);
            hullId = CoopDelimited.normalize(hullId);
            shipName = CoopDelimited.normalize(shipName);
            treeOrReason = treeOrReason == null ? "" : treeOrReason;
        }

        public String encode() {
            return (ok ? '1' : '0') + "|"
                    + CoopDelimited.field(battleId) + '|'
                    + CoopDelimited.field(hullId) + '|'
                    + CoopDelimited.field(shipName) + '|'
                    + x + '|' + y + '|' + facing + '|'
                    + CoopDelimited.field(treeOrReason);
        }

        public static ClaimAck decode(String raw) {
            java.util.List<String> f = CoopDelimited.split(raw == null ? "" : raw);
            boolean ok = !f.isEmpty() && "1".equals(f.get(0));
            float x = 0f;
            float y = 0f;
            float facing = 0f;
            try {
                if (f.size() > 4) {
                    x = Float.parseFloat(f.get(4).trim());
                }
                if (f.size() > 5) {
                    y = Float.parseFloat(f.get(5).trim());
                }
                if (f.size() > 6) {
                    facing = Float.parseFloat(f.get(6).trim());
                }
            } catch (RuntimeException ignored) {
            }
            return new ClaimAck(
                    ok,
                    f.size() > 1 ? f.get(1) : "",
                    f.size() > 2 ? f.get(2) : "",
                    f.size() > 3 ? f.get(3) : "",
                    x, y, facing,
                    f.size() > 7 ? f.get(7) : "");
        }
    }
}
