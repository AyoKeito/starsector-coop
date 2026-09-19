package coop.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The immutable half of a player fleet snapshot (Phase 20 M4 roster split): who the player is, and
 * exactly which ships they have. Sent over reliable TCP as {@code FLEET_ROSTER} whenever
 * {@link #fleetHash16()} changes, at the start of a gameplay session, and on an accepted resume — the
 * same "datablock" shape the 1 Hz {@code NPC_FLEET_SET} already uses for NPC fleets.
 *
 * <p>Why it left the 10 Hz datagram: the receiving mirror reads a member's hull, variant, names and
 * hullmods exactly once per roster change ({@code CoopFleetMirror.refreshRosterIfChanged} returns
 * early on an unchanged hash and touches only CR and hull fraction), and re-sending 64-129 bytes per
 * ship ten times a second to deliver two floats is what made a 30-ship fleet compose to 4-5 KB and
 * fragment into 3-4 IP packets. What rides UDP now is {@link CoopFleetSnapshot.Tick}; this rides the
 * wire that already guarantees delivery, because losing it is not a dropped frame of motion, it is a
 * mirror with the wrong ships in it.
 *
 * <p>{@code fleetHash16} is the join key between the two halves — a tick whose hash matches this
 * roster is applied against it, one that does not is held (see {@link CoopRosterCache}).
 */
public record CoopFleetRoster(String playerId, String username, String factionId,
                              String fleetHash16, List<CoopFleetSnapshot.Member> members,
                              String commanderSkills, int commanderLevel) {

    /** The roster header before Phase 33 appended the owner's commander character. */
    private static final int HEADER_FIELD_COUNT_PRE_OFFICERS = 5;
    private static final int HEADER_FIELD_COUNT = HEADER_FIELD_COUNT_PRE_OFFICERS + 2;

    public CoopFleetRoster {
        playerId = playerId == null ? "" : playerId;
        username = username == null ? "" : username;
        factionId = factionId == null ? "" : factionId;
        fleetHash16 = fleetHash16 == null ? "" : fleetHash16;
        members = members == null ? List.of() : List.copyOf(members);
        commanderSkills = commanderSkills == null ? "" : commanderSkills;
        commanderLevel = Math.max(0, commanderLevel);
    }

    /** A roster from a sender with no replicated commander character (pre-0.1.4 shape). */
    public CoopFleetRoster(String playerId, String username, String factionId, String fleetHash16,
                           List<CoopFleetSnapshot.Member> members) {
        this(playerId, username, factionId, fleetHash16, members, "", 0);
    }

    /** The roster half of a full snapshot, truncating its hash to the wire's 16 hex characters. */
    public static CoopFleetRoster of(CoopFleetSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CoopFleetRoster(snapshot.playerId(), snapshot.username(), snapshot.factionId(),
                snapshot.fleetHash16(), snapshot.members(), snapshot.commanderSkills(),
                snapshot.commanderLevel());
    }

    /**
     * Header line then one member record per line, in the {@link CoopFleetCodec} member encoding.
     *
     * <p>The commander fields ride here rather than on the 10 Hz tick because they are exactly as
     * structural as the officers beside them: the mirror seats them on its placeholder commander at
     * roster-build time and never again, and they move only when the owner spends a skill point.
     * They sit after the member count so the count keeps its index (see {@link CoopFleetSnapshot}).
     */
    public String encode() {
        StringBuilder out = new StringBuilder(96 + members.size() * 64);
        out.append(CoopFleetCodec.escape(playerId))
                .append('|').append(CoopFleetCodec.escape(username))
                .append('|').append(CoopFleetCodec.escape(factionId))
                .append('|').append(CoopFleetCodec.escape(fleetHash16))
                .append('|').append(Integer.toString(members.size()))
                .append('|').append(CoopFleetCodec.escape(commanderSkills))
                .append('|').append(Integer.toString(commanderLevel));
        for (CoopFleetSnapshot.Member member : members) {
            out.append('\n');
            CoopFleetCodec.appendMember(out, member);
        }
        return out.toString();
    }

    public static CoopFleetRoster decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        List<String> header = CoopFleetCodec.split(lines[0]);
        if (header.size() != HEADER_FIELD_COUNT && header.size() != HEADER_FIELD_COUNT_PRE_OFFICERS) {
            throw new IllegalArgumentException("Expected " + HEADER_FIELD_COUNT
                    + " roster header fields, got " + header.size());
        }
        boolean carriesCommander = header.size() == HEADER_FIELD_COUNT;
        int memberCount = Integer.parseInt(header.get(4));
        // Explicit rather than left to ArrayList's capacity check (red-team A15): a negative count
        // sails past the "enough lines present" test below, and the exception a reader eventually
        // gets names a capacity rather than the malformed field that caused it.
        if (memberCount < 0) {
            throw new IllegalArgumentException("Negative roster member count: " + memberCount);
        }
        if (lines.length - 1 < memberCount) {
            throw new IllegalArgumentException("Declared " + memberCount + " members but only "
                    + (lines.length - 1) + " member lines present");
        }
        List<CoopFleetSnapshot.Member> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            members.add(CoopFleetCodec.parseMember(CoopFleetCodec.split(lines[i + 1])));
        }
        return new CoopFleetRoster(header.get(0), header.get(1), header.get(2), header.get(3), members,
                carriesCommander ? header.get(5) : "",
                carriesCommander ? parseCommanderLevel(header.get(6)) : 0);
    }

    /** Unparsable reads as "no level": the roster's ships are worth more than the commander's tier. */
    private static int parseCommanderLevel(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
