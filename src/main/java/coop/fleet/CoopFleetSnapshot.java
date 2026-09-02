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
 * fleetHash} plus the per-member records, and — added by Phase 14b — the sender's live
 * {@link CoopSensorSync.Profile}. The sensor identity is what makes the receiving client's <em>NPC
 * AI</em> see (or fail to see) the remote player exactly as vanilla would: on the host, hostile fleets
 * decide whether to hunt the guest mirror from {@code getVisibilityLevelTo}, and patrols only synthesize
 * a customs stop against a mirror they can actually detect. Without it a mirror carried a fabricated
 * profile and stealth was impossible in either direction.
 *
 * <p>It defines its own compact, self-contained text encoding
 * rather than reusing {@link coop.net.CoopMessages}'s flat JSON envelope: the envelope parser does
 * not support arrays (it is a flat key/value parser), and UDP datagrams are framed independently of
 * the TCP line protocol. The encoding is delimiter based with backslash escaping so member names
 * containing {@code |} or newlines round-trip exactly.
 */
public record CoopFleetSnapshot(String playerId, String username, String locationId,
                                float x, float y, float velocityX, float velocityY,
                                String factionId, boolean transponderOn,
                                CoopSensorSync.Profile sensors, String fleetHash,
                                List<Member> members) {

    private static final int HEADER_FIELD_COUNT = 10 + CoopSensorSync.FIELD_COUNT + 1;
    private static final int SENSOR_FIELD_OFFSET = 9;
    private static final int MEMBER_COUNT_INDEX = HEADER_FIELD_COUNT - 1;

    /**
     * Hex characters of {@link #fleetHash} that go on the wire (Phase 20 M4 payload diet). Sixteen
     * hex is 64 bits of SHA-256; what the receiver does with it is "did this one fleet's roster
     * change since the roster I hold", and 64 bits answers that with room to spare while saving
     * 48 bytes on every tick of a 10 Hz stream.
     */
    public static final int WIRE_HASH_CHARS = 16;

    public CoopFleetSnapshot {
        playerId = normalize(playerId);
        username = normalize(username);
        locationId = normalize(locationId);
        factionId = normalize(factionId);
        fleetHash = normalize(fleetHash);
        sensors = sensors == null ? CoopSensorSync.Profile.UNKNOWN : sensors;
        members = members == null ? List.of() : List.copyOf(members);
    }

    /**
     * Per-ship record. {@code variantId} is the hull-variant id used to recreate the ship on the
     * remote client; {@code cr} and {@code hullFraction} ride alongside for display but are
     * deliberately NOT part of the {@link #fleetHash} — see {@link #computeFleetHash}.
     *
     * <p><b>Contract (2026-08-19): {@code variantId} and {@code hullId} must be install-stock ids,
     * never runtime ids.</b> The receiving client resolves them against its own spec store, and a
     * spec store only ever contains what was loaded from data files. Runtime ids exist and are
     * routinely attached to live ships: {@code DefaultFleetInflater} autofits every ship of an NPC
     * fleet the moment a player fleet comes near it, onto a variant it names
     * {@code fleet.getId() + "_" + memberIndex} — an id derived from the <em>sender's</em> fleet and
     * meaningless anywhere else. Putting one of those on the wire is worse than sending nothing,
     * because {@code Global.getFactory().createFleetMember} does not reject an unknown variant id: it
     * substitutes a placeholder ship, so the receiver silently builds the wrong hull with no error to
     * catch. {@code CoopFleetSnapshotFactory#streamableVariantId} / {@code #streamableHullId} enforce
     * the contract by validating every id against the sender's own spec store before it is sent —
     * sound because the handshake already requires an identical mod manifest on both sides.
     *
     * <p><b>{@code dmodIds}, {@code sModIds} and {@code sModdedBuiltInIds} (Phase 16)</b> are
     * comma-joined, sorted lists of the variant's permanent hullmod ids — damaged, story-pointed, and
     * story-pointed built-in respectively — each empty when the ship has none. Those are stock
     * spreadsheet ids too, so they resolve on both installs exactly like {@code variantId} does; the
     * receiver re-applies them on top of the clean stock ship it built ({@link CoopShipMods}, which
     * documents why the engine keeps the three lists apart). Without them a battered, story-pointed
     * host ship mirrored as a pristine one — see {@code PHASE14_SPIKE_NOTES.md}.
     */
    public record Member(String fleetMemberId, String hullId, String variantId, String shipName,
                         String captainName, float cr, float hullFraction,
                         String dmodIds, String sModIds, String sModdedBuiltInIds) {
        public Member {
            fleetMemberId = normalize(fleetMemberId);
            hullId = normalize(hullId);
            variantId = normalize(variantId);
            shipName = normalize(shipName);
            captainName = normalize(captainName);
            dmodIds = normalize(dmodIds);
            sModIds = normalize(sModIds);
            sModdedBuiltInIds = normalize(sModdedBuiltInIds);
        }

        /**
         * A member with no replicated hullmods; keeps the pre-Phase-16 call sites (and their tests)
         * honest.
         */
        public Member(String fleetMemberId, String hullId, String variantId, String shipName,
                      String captainName, float cr, float hullFraction) {
            this(fleetMemberId, hullId, variantId, shipName, captainName, cr, hullFraction, "", "", "");
        }
    }

    /** Builds a snapshot, computing {@link #fleetHash} from {@code members}. */
    public static CoopFleetSnapshot create(String playerId, String username, String locationId,
                                           float x, float y, float velocityX, float velocityY,
                                           String factionId, boolean transponderOn,
                                           CoopSensorSync.Profile sensors,
                                           List<Member> members) {
        List<Member> safeMembers = members == null ? List.of() : members;
        return new CoopFleetSnapshot(playerId, username, locationId, x, y, velocityX, velocityY,
                factionId, transponderOn, sensors, computeFleetHash(safeMembers), safeMembers);
    }

    /**
     * SHA-256 over the member records sorted by {@code fleetMemberId}. <b>Structural fields only</b>
     * (identity, hull, variant, names, permanent hullmods): the hash gates a full mirror-roster
     * teardown and rebuild on the remote client, so it must flip only when the ship set actually
     * changes.
     *
     * <p>D-mods and S-mods are structural (Phase 16) because they are baked into the mirror ship at
     * build time — a ship that gains one has to be rebuilt for the mirror to show it. They are also
     * slow-moving (recovery, refit and fleet inflation, not repair), so including them cannot
     * reproduce the CR rebuild storm described below.
     *
     * <p>CR and hull fraction used to be included rounded to a whole percent, and that was a
     * measured performance defect: at 10 game-seconds per real second, a repairing or CR-recovering
     * ship crosses a percent boundary every real second or two, and a loaded save full of
     * battle-damaged NPC fleets turned every one-second {@code NPC_FLEET_SET} into ~10 full roster
     * rebuilds (guest at 39 fps with rubber-banding, 2026-08-17). The mirror now applies CR/hull
     * in place on the existing members instead ({@code CoopFleetMirror#updateMemberState}).
     */
    public static String computeFleetHash(List<Member> members) {
        List<Member> sorted = new ArrayList<>(members == null ? List.of() : members);
        sorted.sort(Comparator.comparing(Member::fleetMemberId)
                .thenComparing(Member::hullId)
                .thenComparing(Member::variantId)
                .thenComparing(Member::shipName)
                .thenComparing(Member::captainName)
                .thenComparing(Member::dmodIds)
                .thenComparing(Member::sModIds)
                .thenComparing(Member::sModdedBuiltInIds));

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
                    .append('|').append(member.dmodIds())
                    .append('|').append(member.sModIds())
                    .append('|').append(member.sModdedBuiltInIds());
        }
        return CoopChecksum.sha256Text(canonical.toString());
    }

    /** The first {@link #WIRE_HASH_CHARS} of {@link #fleetHash}; what the wire and the cache compare. */
    public String fleetHash16() {
        return hash16(fleetHash);
    }

    /** {@link #fleetHash16()} for a hash that arrived from somewhere else; shorter strings pass through. */
    public static String hash16(String fleetHash) {
        if (fleetHash == null) {
            return "";
        }
        return fleetHash.length() <= WIRE_HASH_CHARS ? fleetHash : fleetHash.substring(0, WIRE_HASH_CHARS);
    }

    /**
     * The volatile half of a fleet snapshot: everything that changes tick to tick, and nothing that
     * does not (Phase 20 M4 roster split). This is what rides the 10 Hz UDP {@code FLEET_SNAPSHOT}
     * datagram; the immutable roster travels once per change on reliable TCP as a
     * {@link CoopFleetRoster}, and {@link CoopRosterCache} recombines the two into the full
     * {@link CoopFleetSnapshot} the mirror consumes.
     *
     * <p>Measured motivation: on 9 of 10 ticks 63-95% of the old body was ids, hull/variant/name
     * strings and a 64-char hash that {@code CoopFleetMirror.refreshRosterIfChanged} discards
     * unread — a 30-ship fleet composed to ~4.3-5.4 KB, i.e. 3-4 IP fragments per packet ten times
     * a second.
     *
     * <p>No {@code playerId} either: the datagram envelope's {@code senderId} already identifies the
     * sender, and it is the field the transport has validated. The pump matches that against the
     * remote player rather than trusting a body field a spoofer would get to choose.
     *
     * <p>Per-ship state is positional — {@code members.get(i)} is the roster's member {@code i} —
     * because a member id costs 20-40 bytes and buys nothing the shared {@code fleetHash16} does not
     * already guarantee: a roster whose order or membership changed has a different hash, and a tick
     * whose hash does not match the cached roster is never applied to it.
     */
    public record Tick(String locationId, float x, float y, float velocityX, float velocityY,
                       boolean transponderOn, CoopSensorSync.Profile sensors, String fleetHash16,
                       List<MemberState> members) {

        /** Header fields before the per-member pairs: location, 4 motion, transponder, sensors, hash, count. */
        private static final int HEADER_FIELD_COUNT = 8 + CoopSensorSync.FIELD_COUNT;
        private static final int SENSOR_FIELD_OFFSET = 6;
        private static final int MEMBER_COUNT_INDEX = HEADER_FIELD_COUNT - 1;

        public Tick {
            locationId = normalize(locationId);
            fleetHash16 = normalize(fleetHash16);
            sensors = sensors == null ? CoopSensorSync.Profile.UNKNOWN : sensors;
            members = members == null ? List.of() : List.copyOf(members);
        }

        /** The tick view of a full snapshot, in the snapshot's own member order. */
        public static Tick of(CoopFleetSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            List<MemberState> states = new ArrayList<>(snapshot.members().size());
            for (Member member : snapshot.members()) {
                states.add(new MemberState(member.cr(), member.hullFraction()));
            }
            return new Tick(snapshot.locationId(), snapshot.x(), snapshot.y(),
                    snapshot.velocityX(), snapshot.velocityY(), snapshot.transponderOn(),
                    snapshot.sensors(), snapshot.fleetHash16(), states);
        }

        /**
         * One line, pipe delimited: the header fields followed by {@code cr|hull} per member. Flat
         * rather than line-per-member because a member record here is two short numbers and a
         * newline would be 8% of it.
         */
        public String encode() {
            StringBuilder out = new StringBuilder(96 + members.size() * 12);
            out.append(CoopFleetCodec.escape(locationId))
                    .append('|').append(CoopFleetCodec.encodeFloat(x, CoopFleetCodec.POSITION_STEP))
                    .append('|').append(CoopFleetCodec.encodeFloat(y, CoopFleetCodec.POSITION_STEP))
                    .append('|').append(CoopFleetCodec.encodeFloat(velocityX, CoopFleetCodec.POSITION_STEP))
                    .append('|').append(CoopFleetCodec.encodeFloat(velocityY, CoopFleetCodec.POSITION_STEP))
                    .append('|').append(transponderOn ? '1' : '0');
            CoopSensorSync.append(out, sensors);
            out.append('|').append(CoopFleetCodec.escape(fleetHash16))
                    .append('|').append(Integer.toString(members.size()));
            for (MemberState state : members) {
                out.append('|').append(CoopFleetCodec.encodeFloat(state.cr(), CoopFleetCodec.FRACTION_STEP))
                        .append('|').append(CoopFleetCodec.encodeFloat(state.hullFraction(),
                                CoopFleetCodec.FRACTION_STEP));
            }
            return out.toString();
        }

        public static Tick decode(String encoded) {
            Objects.requireNonNull(encoded, "encoded");
            List<String> fields = CoopFleetCodec.split(encoded);
            if (fields.size() < HEADER_FIELD_COUNT) {
                throw new IllegalArgumentException("Expected at least " + HEADER_FIELD_COUNT
                        + " tick fields, got " + fields.size());
            }
            int memberCount = Integer.parseInt(fields.get(MEMBER_COUNT_INDEX));
            if (memberCount < 0 || fields.size() != HEADER_FIELD_COUNT + memberCount * 2) {
                throw new IllegalArgumentException("Declared " + memberCount
                        + " members but the record carries " + (fields.size() - HEADER_FIELD_COUNT)
                        + " state fields");
            }
            List<MemberState> states = new ArrayList<>(memberCount);
            for (int i = 0; i < memberCount; i++) {
                int base = HEADER_FIELD_COUNT + i * 2;
                states.add(new MemberState(Float.parseFloat(fields.get(base)),
                        Float.parseFloat(fields.get(base + 1))));
            }
            return new Tick(fields.get(0),
                    Float.parseFloat(fields.get(1)), Float.parseFloat(fields.get(2)),
                    Float.parseFloat(fields.get(3)), Float.parseFloat(fields.get(4)),
                    "1".equals(fields.get(5)),
                    CoopSensorSync.parse(fields, SENSOR_FIELD_OFFSET),
                    fields.get(SENSOR_FIELD_OFFSET + CoopSensorSync.FIELD_COUNT), states);
        }
    }

    /** The two per-ship numbers that move on their own between roster changes. */
    public record MemberState(float cr, float hullFraction) {
    }

    /**
     * The full wire form, roster included. Since Phase 20 M4 this is <b>not</b> what the 10 Hz UDP
     * stream carries ({@link Tick} is) — it stays because the bridge dumps, the wiretap fixtures and
     * the round-trip tests all want one string that holds an entire snapshot.
     *
     * <p>Position, velocity, CR/hull and the sensor terms are quantized here and only here
     * ({@link CoopFleetCodec#quantize}) — a full-precision float costs 8-11 decimal digits per field
     * at 10 Hz for no benefit anyone can see. {@link #fleetHash} is computed before this from the
     * unquantized members, so nothing about the rebuild gate moves.
     */
    public String encodeFull() {
        StringBuilder out = new StringBuilder(128 + members.size() * 48);
        out.append(CoopFleetCodec.escape(playerId))
                .append('|').append(CoopFleetCodec.escape(username))
                .append('|').append(CoopFleetCodec.escape(locationId))
                .append('|').append(CoopFleetCodec.encodeFloat(x, CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.encodeFloat(y, CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.encodeFloat(velocityX, CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.encodeFloat(velocityY, CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.escape(factionId))
                .append('|').append(transponderOn ? '1' : '0');
        CoopSensorSync.append(out, sensors);
        out.append('|').append(CoopFleetCodec.escape(fleetHash))
                .append('|').append(Integer.toString(members.size()));
        for (Member member : members) {
            out.append('\n');
            CoopFleetCodec.appendMember(out, member);
        }
        return out.toString();
    }

    public static CoopFleetSnapshot decodeFull(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty fleet snapshot");
        }
        List<String> header = CoopFleetCodec.split(lines[0]);
        if (header.size() != HEADER_FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + HEADER_FIELD_COUNT
                    + " header fields, got " + header.size());
        }
        int memberCount = Integer.parseInt(header.get(MEMBER_COUNT_INDEX));
        if (lines.length - 1 < memberCount) {
            throw new IllegalArgumentException("Declared " + memberCount + " members but only "
                    + (lines.length - 1) + " member lines present");
        }
        List<Member> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            members.add(CoopFleetCodec.parseMember(CoopFleetCodec.split(lines[i + 1])));
        }
        return new CoopFleetSnapshot(header.get(0), header.get(1), header.get(2),
                Float.parseFloat(header.get(3)), Float.parseFloat(header.get(4)),
                Float.parseFloat(header.get(5)), Float.parseFloat(header.get(6)),
                header.get(7), "1".equals(header.get(8)),
                CoopSensorSync.parse(header, SENSOR_FIELD_OFFSET),
                header.get(SENSOR_FIELD_OFFSET + CoopSensorSync.FIELD_COUNT), members);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
