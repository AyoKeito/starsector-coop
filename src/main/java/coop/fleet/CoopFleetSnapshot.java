package coop.fleet;

import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Campaign fleet state replicated over the UDP campaign stream (Phase 8, 10 Hz).
 *
 * <p>This DTO carries the exact fields named in {@code COOP_MP_IMPLEMENTATION_PLAN_V1.md} Phase 8:
 * {@code playerId, username, locationId, x, y, velocityX, velocityY, factionId, transponderOn,
 * fleetHash} plus the per-member records. It defines its own compact, self-contained text encoding
 * rather than reusing {@link coop.net.CoopMessages}'s flat JSON envelope: the envelope parser does
 * not support arrays (it is a flat key/value parser), and UDP datagrams are framed independently of
 * the TCP line protocol. The encoding is delimiter based with backslash escaping so member names
 * containing {@code |} or newlines round-trip exactly.
 */
public record CoopFleetSnapshot(String playerId, String username, String locationId,
                                float x, float y, float velocityX, float velocityY,
                                String factionId, boolean transponderOn, String fleetHash,
                                List<Member> members) {

    public CoopFleetSnapshot {
        playerId = normalize(playerId);
        username = normalize(username);
        locationId = normalize(locationId);
        factionId = normalize(factionId);
        fleetHash = normalize(fleetHash);
        members = members == null ? List.of() : List.copyOf(members);
    }

    /**
     * Per-ship record. {@code variantId} is the hull-variant id used to recreate the ship on the
     * remote client; {@code cr} and {@code hullFraction} are rounded into the {@link #fleetHash} so
     * roster equality is insensitive to sub-percent drift.
     */
    public record Member(String fleetMemberId, String hullId, String variantId, String shipName,
                         String captainName, float cr, float hullFraction) {
        public Member {
            fleetMemberId = normalize(fleetMemberId);
            hullId = normalize(hullId);
            variantId = normalize(variantId);
            shipName = normalize(shipName);
            captainName = normalize(captainName);
        }
    }

    /** Builds a snapshot, computing {@link #fleetHash} from {@code members}. */
    public static CoopFleetSnapshot create(String playerId, String username, String locationId,
                                           float x, float y, float velocityX, float velocityY,
                                           String factionId, boolean transponderOn,
                                           List<Member> members) {
        List<Member> safeMembers = members == null ? List.of() : members;
        return new CoopFleetSnapshot(playerId, username, locationId, x, y, velocityX, velocityY,
                factionId, transponderOn, computeFleetHash(safeMembers), safeMembers);
    }

    /**
     * SHA-256 over the member records sorted by {@code fleetMemberId}, using the fields named in the
     * Phase 8 plan with CR and hull fraction rounded to whole percent. Independent of iteration
     * order; changes when any member's hull/variant/identity/rounded-state changes.
     */
    public static String computeFleetHash(List<Member> members) {
        List<Member> sorted = new ArrayList<>(members == null ? List.of() : members);
        sorted.sort(Comparator.comparing(Member::fleetMemberId)
                .thenComparing(Member::hullId)
                .thenComparing(Member::variantId)
                .thenComparing(Member::shipName)
                .thenComparing(Member::captainName));

        StringBuilder canonical = new StringBuilder(sorted.size() * 48);
        for (Member member : sorted) {
            if (canonical.length() > 0) {
                canonical.append('\n');
            }
            canonical.append(member.fleetMemberId())
                    .append('|').append(member.hullId())
                    .append('|').append(member.variantId())
                    .append('|').append(member.shipName())
                    .append('|').append(member.captainName())
                    .append('|').append(Math.round(member.cr() * 100f))
                    .append('|').append(Math.round(member.hullFraction() * 100f));
        }
        return CoopChecksum.sha256Text(canonical.toString());
    }

    public String encode() {
        StringBuilder out = new StringBuilder(128 + members.size() * 48);
        out.append(field(playerId))
                .append('|').append(field(username))
                .append('|').append(field(locationId))
                .append('|').append(Float.toString(x))
                .append('|').append(Float.toString(y))
                .append('|').append(Float.toString(velocityX))
                .append('|').append(Float.toString(velocityY))
                .append('|').append(field(factionId))
                .append('|').append(transponderOn ? '1' : '0')
                .append('|').append(field(fleetHash))
                .append('|').append(Integer.toString(members.size()));
        for (Member member : members) {
            out.append('\n')
                    .append(field(member.fleetMemberId()))
                    .append('|').append(field(member.hullId()))
                    .append('|').append(field(member.variantId()))
                    .append('|').append(field(member.shipName()))
                    .append('|').append(field(member.captainName()))
                    .append('|').append(Float.toString(member.cr()))
                    .append('|').append(Float.toString(member.hullFraction()));
        }
        return out.toString();
    }

    public static CoopFleetSnapshot decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty fleet snapshot");
        }
        List<String> header = splitFields(lines[0]);
        if (header.size() != 11) {
            throw new IllegalArgumentException("Expected 11 header fields, got " + header.size());
        }
        int memberCount = Integer.parseInt(header.get(10));
        if (lines.length - 1 < memberCount) {
            throw new IllegalArgumentException("Declared " + memberCount + " members but only "
                    + (lines.length - 1) + " member lines present");
        }
        List<Member> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            List<String> fields = splitFields(lines[i + 1]);
            if (fields.size() != 7) {
                throw new IllegalArgumentException("Expected 7 member fields, got " + fields.size());
            }
            members.add(new Member(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                    fields.get(4), Float.parseFloat(fields.get(5)), Float.parseFloat(fields.get(6))));
        }
        return new CoopFleetSnapshot(header.get(0), header.get(1), header.get(2),
                Float.parseFloat(header.get(3)), Float.parseFloat(header.get(4)),
                Float.parseFloat(header.get(5)), Float.parseFloat(header.get(6)),
                header.get(7), "1".equals(header.get(8)), header.get(9), members);
    }

    private static String field(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\|");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                switch (c) {
                    case '\\' -> token.append('\\');
                    case '|' -> token.append('|');
                    case 'n' -> token.append('\n');
                    case 'r' -> token.append('\r');
                    default -> token.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                fields.add(token.toString());
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        fields.add(token.toString());
        return fields;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
