package coop.stats;

import coop.campaign.CoopDelimited;
import coop.net.CoopMessages;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wire codec for {@link CoopSessionStats}: the payload half of the additive {@code SESSION_STATS}
 * message the wiring wave adds to {@link CoopMessages}.
 *
 * <h2>Why the payload looks like this</h2>
 *
 * <p><b>The envelope parser is flat.</b> {@code CoopMessages.Parser} (CoopMessages.java:1431-1560)
 * understands objects whose values are strings, {@code null}, or longs — no arrays, no nested
 * objects, no booleans, no floating point. So the team-level gauges ride as flat JSON fields (floats
 * as quoted strings, exactly as {@code marketTxn}'s {@code unitPrice} does at CoopMessages.java:682)
 * and everything list-shaped rides inside one {@code body} string encoded with
 * {@link CoopDelimited}, the same arrangement {@link coop.combat.CoopBattleResult} and
 * {@code CoopMarketSync} already use.
 *
 * <p><b>Line tokens, not positional records.</b> The body is newline-separated records whose first
 * field is a one-letter token:
 * <pre>
 *   P|playerId|name                                              a column, in display order
 *   S|playerId|fought|won|lost|distance|netWorth|bestTrade|missions|colonies
 *   M|playerId|marketId                                          one market traded with
 *   V|playerId|systemId                                          one system visited
 *   L|playerId|hullName|hullClass|systemName|day|cause           one ship-loss ledger entry
 * </pre>
 * Unknown tokens are skipped and extra trailing fields are ignored, so a later phase can add a stat
 * without breaking a peer that predates it — the same forward-compatibility rule the battle-result
 * body follows.
 *
 * <p><b>Decoding borrows the envelope's parser instead of copying it.</b> {@code decodePayload}
 * takes a {@code Message}, and {@code CoopMessages.escapeJson} and the parser itself are private, so
 * {@link #decodePayload(String)} wraps the JSON in a throwaway {@code Message} to reach the one
 * parser both sides of the wire already agree on. A second copy of a JSON parser in the tree is a
 * second thing to keep in step; the escape function has to be duplicated (twenty lines, no state)
 * because there is no way to reach it at all.
 */
public final class CoopSessionStatsCodec {

    private static final String RECORD_SEPARATOR = "\n";
    private static final String PLAYER_TOKEN = "P";
    private static final String COUNTERS_TOKEN = "S";
    private static final String MARKET_TOKEN = "M";
    private static final String SYSTEM_TOKEN = "V";
    private static final String LOSS_TOKEN = "L";

    /** Field count of an {@code S} line including its token; extras are tolerated. */
    private static final int COUNTERS_FIELD_COUNT = 10;
    /** Field count of an {@code L} line including its token; extras are tolerated. */
    private static final int LOSS_FIELD_COUNT = 7;

    private CoopSessionStatsCodec() {
    }

    // ---- encode ----------------------------------------------------------------------------------

    /** The flat JSON object a {@code SESSION_STATS} message carries as its payload. */
    public static String encodePayload(CoopSessionStats stats) {
        CoopSessionStats value = stats == null ? new CoopSessionStats() : stats;
        Float lastLoss = value.lastHullLossDay();
        return "{\"fleetsDestroyed\":" + value.fleetsDestroyedTeam()
                + ",\"salvageEvents\":" + value.salvageEventsTeam()
                + ",\"coloniesHeld\":" + value.coloniesHeldTeam()
                + ",\"togetherSeconds\":\"" + value.timeFlownTogetherSeconds() + "\""
                + ",\"daysElapsed\":\"" + value.daysElapsed() + "\""
                + ",\"lastHullLossDay\":\"" + (lastLoss == null ? -1f : lastLoss) + "\""
                + ",\"body\":\"" + escapeJson(encodeBody(value)) + "\"}";
    }

    static String encodeBody(CoopSessionStats stats) {
        StringBuilder body = new StringBuilder(256);
        for (String playerId : stats.playerIds()) {
            append(body, PLAYER_TOKEN, playerId, stats.playerName(playerId));
        }
        for (String playerId : stats.playerIds()) {
            CoopSessionStats.PlayerStats player = stats.player(playerId);
            append(body, COUNTERS_TOKEN, playerId,
                    Long.toString(player.battlesFought()),
                    Long.toString(player.battlesWon()),
                    Long.toString(player.shipsLost()),
                    Float.toString(player.distanceTraveledSu()),
                    Long.toString(player.netWorthCredits()),
                    Long.toString(player.bestSingleTradeCredits()),
                    Long.toString(player.missionsClaimed()),
                    Long.toString(player.coloniesFounded()));
            for (String marketId : player.marketsTradedWith()) {
                append(body, MARKET_TOKEN, playerId, marketId);
            }
            for (String systemId : player.systemsVisited()) {
                append(body, SYSTEM_TOKEN, playerId, systemId);
            }
        }
        for (CoopSessionStats.ShipLoss loss : stats.shipLossLedger()) {
            append(body, LOSS_TOKEN, loss.playerId(), loss.hullName(), loss.hullClass(),
                    loss.systemName(), Float.toString(loss.day()), loss.cause());
        }
        return body.toString();
    }

    private static void append(StringBuilder body, String token, String... fields) {
        if (body.length() > 0) {
            body.append(RECORD_SEPARATOR);
        }
        body.append(token);
        for (String field : fields) {
            body.append('|').append(CoopDelimited.field(field));
        }
    }

    // ---- decode ----------------------------------------------------------------------------------

    /**
     * Rebuilds the stats from a payload produced by {@link #encodePayload}. Unknown JSON fields and
     * unknown body tokens are ignored; a malformed number falls back to zero rather than failing the
     * whole page, because these counters are cosmetic and a dropped digit must not cost a session.
     *
     * @throws IllegalArgumentException when {@code json} is not a flat JSON object at all
     */
    public static CoopSessionStats decodePayload(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, Object> fields = parseFlatObject(json);
        CoopSessionStats stats = new CoopSessionStats();
        decodeBody(stats, string(fields, "body"));
        stats.restoreTeam(
                number(fields, "fleetsDestroyed"),
                number(fields, "salvageEvents"),
                number(fields, "coloniesHeld"),
                decimal(fields, "togetherSeconds"),
                decimal(fields, "daysElapsed"),
                // Absent means "nothing lost yet", which restoreTeam reads off a negative day.
                decimal(fields, "lastHullLossDay", -1f));
        return stats;
    }

    private static void decodeBody(CoopSessionStats stats, String body) {
        if (body.isEmpty()) {
            return;
        }
        for (String line : body.split(RECORD_SEPARATOR, -1)) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> fields = CoopDelimited.split(line);
            String token = fields.get(0);
            switch (token) {
                case PLAYER_TOKEN -> {
                    if (fields.size() >= 3) {
                        stats.notePlayer(fields.get(1), fields.get(2));
                    }
                }
                case COUNTERS_TOKEN -> {
                    if (fields.size() >= COUNTERS_FIELD_COUNT) {
                        stats.player(fields.get(1)).restore(
                                parseLong(fields.get(2)),
                                parseLong(fields.get(3)),
                                parseLong(fields.get(4)),
                                parseFloat(fields.get(5)),
                                parseLong(fields.get(6)),
                                parseLong(fields.get(7)),
                                parseLong(fields.get(8)),
                                parseLong(fields.get(9)));
                    }
                }
                case MARKET_TOKEN -> {
                    if (fields.size() >= 3 && !fields.get(2).isEmpty()) {
                        List<String> markets = stats.player(fields.get(1)).marketsTradedWith();
                        if (!markets.contains(fields.get(2))) {
                            markets.add(fields.get(2));
                        }
                    }
                }
                case SYSTEM_TOKEN -> {
                    if (fields.size() >= 3) {
                        stats.noteSystemVisited(fields.get(1), fields.get(2));
                    }
                }
                case LOSS_TOKEN -> {
                    if (fields.size() >= LOSS_FIELD_COUNT) {
                        stats.restoreShipLoss(new CoopSessionStats.ShipLoss(fields.get(1),
                                fields.get(2), fields.get(3), fields.get(4),
                                parseFloat(fields.get(5)), fields.get(6)));
                    }
                }
                default -> {
                    // A token from a future build. Skipping it is the forward-compatibility rule.
                }
            }
        }
    }

    /**
     * Runs {@code json} through the envelope's own parser by handing it a throwaway message. The
     * type is arbitrary — {@code decodePayload} only ever touches {@code payloadJson}.
     */
    static Map<String, Object> parseFlatObject(String json) {
        return CoopMessages.decodePayload(
                new CoopMessages.Message(CoopMessages.Type.PING, null, 0L, 0L, json));
    }

    private static String string(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value instanceof String text ? text : "";
    }

    private static long number(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Long longValue) {
            return longValue;
        }
        return value instanceof String text ? parseLong(text) : 0L;
    }

    private static float decimal(Map<String, Object> fields, String name) {
        return decimal(fields, name, 0f);
    }

    private static float decimal(Map<String, Object> fields, String name, float fallback) {
        Object value = fields.get(name);
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(text.trim());
            return Float.isNaN(parsed) || Float.isInfinite(parsed) ? fallback : parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static float parseFloat(String text) {
        try {
            float parsed = Float.parseFloat(text.trim());
            return Float.isNaN(parsed) || Float.isInfinite(parsed) ? 0f : parsed;
        } catch (NumberFormatException ex) {
            return 0f;
        }
    }

    /**
     * Local copy of the envelope's string escape, because {@code CoopMessages.escapeJson} is private
     * and this class is not allowed to edit that file. Identical rules, so the two sides of the wire
     * agree byte for byte.
     */
    static String escapeJson(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
