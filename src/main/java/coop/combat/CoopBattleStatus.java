package coop.combat;

import coop.campaign.CoopDelimited;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One 2-5 Hz snapshot of a coop battle as seen by the <em>engaging</em> client, plus its codec
 * (Phase 14).
 *
 * <p><b>Why a delimited body.</b> The reliable-TCP {@link coop.net.CoopMessages} envelope parser is
 * flat: no JSON arrays. The ship list and the kill feed therefore ride as a single string field,
 * newline-separated records with {@code |}-separated fields escaped through
 * {@link CoopDelimited#field(String)} — the same pattern as {@code CoopBaseRecord} and
 * {@code CoopMarketSync}.
 *
 * <p><b>Forward compatibility (Phase 22 hook).</b> Each record line starts with a one-letter type
 * token ({@code S} = ship, {@code K} = kill-feed entry). {@link #decode} ignores unknown tokens and
 * ignores extra trailing fields on a known one, so a later phase can append optional position/facing
 * fields to {@code S} lines, or add a new record type, without breaking an older peer. (Older peers
 * are impossible inside a session — the Phase 5 handshake pins the exact build — but the tolerance
 * costs nothing and keeps the codec honest for the tactical-map upgrade.)
 *
 * <p><b>Latest-wins.</b> {@code BATTLE_STATUS} is stateless: {@link #isNewer} decides whether an
 * arriving snapshot supersedes the one on screen. A snapshot for a <em>different</em> battle id
 * always supersedes (a new battle started), otherwise the strictly-higher {@code statusSeq} wins, so
 * a reordered or duplicated frame costs nothing.
 *
 * <p>Fighters, drones and shuttle pods are deliberately not captured (see
 * {@link CoopBattleBridge}): they are numerous, short-lived, and would dominate the payload.
 */
public record CoopBattleStatus(String battleId, long statusSeq, long elapsedMillis,
                               List<ShipRecord> ships, List<String> killFeed) {

    /** Per-ship liveness as the spectator panel renders it. */
    public enum ShipState {
        ALIVE,
        DISABLED,
        DESTROYED
    }

    /**
     * One ship in the battle. {@code enemy} is the side <em>relative to the engaging player</em>
     * (owner 0 = the engaging player's side, anything else = the opposing side).
     */
    public record ShipRecord(String shipId, String hullId, String name, boolean enemy,
                             float hullFraction, float fluxLevel, ShipState state) {
        public ShipRecord {
            shipId = CoopDelimited.normalize(shipId);
            hullId = CoopDelimited.normalize(hullId);
            name = CoopDelimited.normalize(name);
            state = state == null ? ShipState.ALIVE : state;
            hullFraction = clamp(hullFraction);
            fluxLevel = clamp(fluxLevel);
        }
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final String RECORD_SEPARATOR = "\n";
    private static final String SHIP_TOKEN = "S";
    private static final String KILL_TOKEN = "K";
    /** Minimum field count of an {@code S} line; extra trailing fields are tolerated (see class doc). */
    private static final int SHIP_FIELD_COUNT = 8;

    public CoopBattleStatus {
        battleId = CoopDelimited.normalize(battleId);
        ships = ships == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(ships));
        killFeed = killFeed == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(killFeed));
    }

    /**
     * True when {@code incoming} (identified by battle id + seq) should replace {@code current} on the
     * spectator's screen. A different battle id always wins; within one battle, a strictly higher seq.
     */
    public static boolean isNewer(String incomingBattleId, long incomingSeq, CoopBattleStatus current) {
        if (current == null) {
            return true;
        }
        String incoming = CoopDelimited.normalize(incomingBattleId);
        if (!incoming.equals(current.battleId())) {
            return true;
        }
        return incomingSeq > current.statusSeq();
    }

    public boolean isNewerThan(CoopBattleStatus current) {
        return isNewer(battleId, statusSeq, current);
    }

    /** Ships on the engaging player's own side, in capture order. */
    public List<ShipRecord> ownShips() {
        return sideShips(false);
    }

    /** Ships on the side the engaging player is fighting, in capture order. */
    public List<ShipRecord> enemyShips() {
        return sideShips(true);
    }

    private List<ShipRecord> sideShips(boolean enemy) {
        List<ShipRecord> out = new ArrayList<>();
        for (ShipRecord ship : ships) {
            if (ship.enemy() == enemy) {
                out.add(ship);
            }
        }
        return out;
    }

    /** The self-contained delimited body that rides in the {@code ships} payload field. */
    public String encodeBody() {
        List<String> lines = new ArrayList<>(ships.size() + killFeed.size());
        for (ShipRecord ship : ships) {
            lines.add(SHIP_TOKEN
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.shipId())
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.hullId())
                    + FIELD_SEPARATOR + CoopDelimited.field(ship.name())
                    + FIELD_SEPARATOR + (ship.enemy() ? "1" : "0")
                    + FIELD_SEPARATOR + ship.hullFraction()
                    + FIELD_SEPARATOR + ship.fluxLevel()
                    + FIELD_SEPARATOR + ship.state().name());
        }
        for (String kill : killFeed) {
            lines.add(KILL_TOKEN + FIELD_SEPARATOR + CoopDelimited.field(CoopDelimited.normalize(kill)));
        }
        return String.join(RECORD_SEPARATOR, lines);
    }

    /** Reverses {@link #encodeBody()}. Unknown record types and extra fields are ignored. */
    public static CoopBattleStatus decode(String battleId, long statusSeq, long elapsedMillis, String body) {
        Objects.requireNonNull(body, "body");
        List<ShipRecord> ships = new ArrayList<>();
        List<String> killFeed = new ArrayList<>();
        if (!body.isEmpty()) {
            for (String line : body.split(RECORD_SEPARATOR, -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> fields = CoopDelimited.split(line);
                String token = fields.get(0);
                if (SHIP_TOKEN.equals(token)) {
                    if (fields.size() < SHIP_FIELD_COUNT) {
                        throw new IllegalArgumentException("Expected at least " + SHIP_FIELD_COUNT
                                + " battle-status ship fields, got " + fields.size());
                    }
                    ships.add(new ShipRecord(
                            fields.get(1),
                            fields.get(2),
                            fields.get(3),
                            "1".equals(fields.get(4)),
                            parseFloat(fields.get(5)),
                            parseFloat(fields.get(6)),
                            parseState(fields.get(7))));
                } else if (KILL_TOKEN.equals(token)) {
                    killFeed.add(fields.size() > 1 ? fields.get(1) : "");
                }
                // Any other token is a record type this build does not know: ignore it.
            }
        }
        return new CoopBattleStatus(battleId, statusSeq, elapsedMillis, ships, killFeed);
    }

    private static ShipState parseState(String raw) {
        try {
            return ShipState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            // An unknown state from a future build is still a live ship as far as this one is
            // concerned; never fail the whole snapshot over one enum value.
            return ShipState.ALIVE;
        }
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (RuntimeException ex) {
            return 0f;
        }
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || value < 0f) {
            return 0f;
        }
        return value > 1f ? 1f : value;
    }
}
