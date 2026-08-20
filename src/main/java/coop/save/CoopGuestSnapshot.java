package coop.save;

import coop.campaign.CoopDelimited;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The guest player's own campaign state, as the host stores it (Phase 16).
 *
 * <p><b>Why it exists.</b> The host owns the canonical save; the guest owns a real save of its own.
 * That is enough for the supported resume flow, but it leaves one hole: a guest who loses their save
 * cannot be put back together, because a fresh same-seed re-roll fails the Phase 6b fingerprint check
 * once host campaign state has drifted. So every host save embeds the guest's fleet, cargo, credits
 * and officers under {@link CoopGuestSnapshotStore#PERSISTENT_KEY}. It is
 * <b>deliberately write-only in v1</b> — disaster-recovery raw material, not live state. Nothing
 * reads it back, and that is a decision (2026-06-10), not an oversight.
 *
 * <p><b>Why it is a hand-written POJO.</b> It is serialized by Starsector's bundled XStream 1.4.10,
 * through the sector's persistent-data map, so: no records (1.4.10 predates them), a public no-arg
 * constructor, and <em>no engine references at all</em> — no {@code CampaignFleetAPI}, no
 * {@code SectorAPI}, no spec objects. A single live engine reference in here would drag an arbitrary
 * slice of the object graph into the save and pin it across loads.
 *
 * <p>It also carries its own compact wire codec ({@link #encodeBody()}/{@link #decodeBody(String)}),
 * the same arrangement {@link coop.fleet.CoopFleetSnapshot} uses and for the same reason: the flat
 * TCP envelope parser has no arrays, so a list-bearing payload ships as one self-contained string.
 */
public class CoopGuestSnapshot {

    /** Bumped whenever the field set changes, so a future reader can tell the shapes apart. */
    public static final int FORMAT_VERSION = 1;

    private static final int HEADER_FIELD_COUNT = 13;
    private static final int CARGO_FIELD_COUNT = 3;
    private static final int SHIP_FIELD_COUNT = 10;
    private static final int OFFICER_FIELD_COUNT = 4;

    private int formatVersion = FORMAT_VERSION;
    private long capturedAtMillis;
    private String sessionId = "";
    private String playerId = "";
    private String playerName = "";
    /** The Phase 6b campaign UUID this snapshot belongs to; a snapshot from another campaign is junk. */
    private String campaignId = "";
    private String seedString = "";
    private String locationId = "";
    private String factionId = "";
    private double credits;
    private List<CargoStack> cargo = new ArrayList<>();
    private List<Ship> ships = new ArrayList<>();
    private List<Officer> officers = new ArrayList<>();

    public CoopGuestSnapshot() {
    }

    /** One cargo stack: {@code type} is the engine's {@code CargoItemType} name, e.g. {@code RESOURCES}. */
    public static class CargoStack {
        private String type = "";
        private String id = "";
        private double quantity;

        public CargoStack() {
        }

        public CargoStack(String type, String id, double quantity) {
            this.type = normalize(type);
            this.id = normalize(id);
            this.quantity = quantity;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = normalize(type);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = normalize(id);
        }

        public double getQuantity() {
            return quantity;
        }

        public void setQuantity(double quantity) {
            this.quantity = quantity;
        }
    }

    /**
     * One ship. The fields mirror {@link coop.fleet.CoopFleetSnapshot.Member} — including the Phase 16
     * permanent-hullmod lists — because they are captured from it, and because stock ids are the only
     * ship identity that means anything on a different install.
     */
    public static class Ship {
        private String fleetMemberId = "";
        private String hullId = "";
        private String variantId = "";
        private String shipName = "";
        private String captainName = "";
        private float cr;
        private float hullFraction = 1f;
        private String dmodIds = "";
        private String sModIds = "";
        private String sModdedBuiltInIds = "";

        public Ship() {
        }

        public Ship(String fleetMemberId, String hullId, String variantId, String shipName,
                    String captainName, float cr, float hullFraction, String dmodIds,
                    String sModIds, String sModdedBuiltInIds) {
            this.fleetMemberId = normalize(fleetMemberId);
            this.hullId = normalize(hullId);
            this.variantId = normalize(variantId);
            this.shipName = normalize(shipName);
            this.captainName = normalize(captainName);
            this.cr = cr;
            this.hullFraction = hullFraction;
            this.dmodIds = normalize(dmodIds);
            this.sModIds = normalize(sModIds);
            this.sModdedBuiltInIds = normalize(sModdedBuiltInIds);
        }

        public String getFleetMemberId() {
            return fleetMemberId;
        }

        public void setFleetMemberId(String fleetMemberId) {
            this.fleetMemberId = normalize(fleetMemberId);
        }

        public String getHullId() {
            return hullId;
        }

        public void setHullId(String hullId) {
            this.hullId = normalize(hullId);
        }

        public String getVariantId() {
            return variantId;
        }

        public void setVariantId(String variantId) {
            this.variantId = normalize(variantId);
        }

        public String getShipName() {
            return shipName;
        }

        public void setShipName(String shipName) {
            this.shipName = normalize(shipName);
        }

        public String getCaptainName() {
            return captainName;
        }

        public void setCaptainName(String captainName) {
            this.captainName = normalize(captainName);
        }

        public float getCr() {
            return cr;
        }

        public void setCr(float cr) {
            this.cr = cr;
        }

        public float getHullFraction() {
            return hullFraction;
        }

        public void setHullFraction(float hullFraction) {
            this.hullFraction = hullFraction;
        }

        public String getDmodIds() {
            return dmodIds;
        }

        public void setDmodIds(String dmodIds) {
            this.dmodIds = normalize(dmodIds);
        }

        public String getSModIds() {
            return sModIds;
        }

        public void setSModIds(String sModIds) {
            this.sModIds = normalize(sModIds);
        }

        public String getSModdedBuiltInIds() {
            return sModdedBuiltInIds;
        }

        public void setSModdedBuiltInIds(String sModdedBuiltInIds) {
            this.sModdedBuiltInIds = normalize(sModdedBuiltInIds);
        }
    }

    /**
     * One officer. {@code skills} is a compact {@code id:level} list rather than a structured record:
     * the snapshot is human-readable recovery material, and a skill row is worth less than the
     * flatness that keeps this DTO free of nested collections.
     */
    public static class Officer {
        private String name = "";
        private String personality = "";
        private int level;
        private String skills = "";

        public Officer() {
        }

        public Officer(String name, String personality, int level, String skills) {
            this.name = normalize(name);
            this.personality = normalize(personality);
            this.level = level;
            this.skills = normalize(skills);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = normalize(name);
        }

        public String getPersonality() {
            return personality;
        }

        public void setPersonality(String personality) {
            this.personality = normalize(personality);
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getSkills() {
            return skills;
        }

        public void setSkills(String skills) {
            this.skills = normalize(skills);
        }
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public long getCapturedAtMillis() {
        return capturedAtMillis;
    }

    public void setCapturedAtMillis(long capturedAtMillis) {
        this.capturedAtMillis = capturedAtMillis;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = normalize(sessionId);
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = normalize(playerId);
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = normalize(playerName);
    }

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = normalize(campaignId);
    }

    public String getSeedString() {
        return seedString;
    }

    public void setSeedString(String seedString) {
        this.seedString = normalize(seedString);
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = normalize(locationId);
    }

    public String getFactionId() {
        return factionId;
    }

    public void setFactionId(String factionId) {
        this.factionId = normalize(factionId);
    }

    public double getCredits() {
        return credits;
    }

    public void setCredits(double credits) {
        this.credits = credits;
    }

    public List<CargoStack> getCargo() {
        return cargo;
    }

    public void setCargo(List<CargoStack> cargo) {
        this.cargo = cargo == null ? new ArrayList<>() : new ArrayList<>(cargo);
    }

    public List<Ship> getShips() {
        return ships;
    }

    public void setShips(List<Ship> ships) {
        this.ships = ships == null ? new ArrayList<>() : new ArrayList<>(ships);
    }

    public List<Officer> getOfficers() {
        return officers;
    }

    public void setOfficers(List<Officer> officers) {
        this.officers = officers == null ? new ArrayList<>() : new ArrayList<>(officers);
    }

    /** One greppable line for the logs; never the wire format. */
    public String summary() {
        return "campaignId=" + campaignId
                + " playerId=" + playerId
                + " credits=" + Math.round(credits)
                + " ships=" + ships.size()
                + " cargoStacks=" + cargo.size()
                + " officers=" + officers.size()
                + " capturedAtMillis=" + capturedAtMillis;
    }

    // ---- Wire codec ----------------------------------------------------------------------------

    /**
     * The self-contained body carried by {@code GUEST_SNAPSHOT}. Header line, then one line per cargo
     * stack, ship and officer, in that order and in the counts the header declares.
     */
    public String encodeBody() {
        StringBuilder out = new StringBuilder(256 + (cargo.size() + ships.size()) * 48);
        out.append(formatVersion)
                .append('|').append(capturedAtMillis)
                .append('|').append(CoopDelimited.field(sessionId))
                .append('|').append(CoopDelimited.field(playerId))
                .append('|').append(CoopDelimited.field(playerName))
                .append('|').append(CoopDelimited.field(campaignId))
                .append('|').append(CoopDelimited.field(seedString))
                .append('|').append(CoopDelimited.field(locationId))
                .append('|').append(CoopDelimited.field(factionId))
                .append('|').append(credits)
                .append('|').append(cargo.size())
                .append('|').append(ships.size())
                .append('|').append(officers.size());
        for (CargoStack stack : cargo) {
            out.append('\n')
                    .append(CoopDelimited.field(stack.getType()))
                    .append('|').append(CoopDelimited.field(stack.getId()))
                    .append('|').append(stack.getQuantity());
        }
        for (Ship ship : ships) {
            out.append('\n')
                    .append(CoopDelimited.field(ship.getFleetMemberId()))
                    .append('|').append(CoopDelimited.field(ship.getHullId()))
                    .append('|').append(CoopDelimited.field(ship.getVariantId()))
                    .append('|').append(CoopDelimited.field(ship.getShipName()))
                    .append('|').append(CoopDelimited.field(ship.getCaptainName()))
                    .append('|').append(ship.getCr())
                    .append('|').append(ship.getHullFraction())
                    .append('|').append(CoopDelimited.field(ship.getDmodIds()))
                    .append('|').append(CoopDelimited.field(ship.getSModIds()))
                    .append('|').append(CoopDelimited.field(ship.getSModdedBuiltInIds()));
        }
        for (Officer officer : officers) {
            out.append('\n')
                    .append(CoopDelimited.field(officer.getName()))
                    .append('|').append(CoopDelimited.field(officer.getPersonality()))
                    .append('|').append(officer.getLevel())
                    .append('|').append(CoopDelimited.field(officer.getSkills()));
        }
        return out.toString();
    }

    /** Reverses {@link #encodeBody()}; throws {@link IllegalArgumentException} on a malformed body. */
    public static CoopGuestSnapshot decodeBody(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        List<String> header = CoopDelimited.split(lines[0]);
        if (header.size() != HEADER_FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + HEADER_FIELD_COUNT
                    + " guest snapshot header fields, got " + header.size());
        }
        CoopGuestSnapshot snapshot = new CoopGuestSnapshot();
        snapshot.setFormatVersion(Integer.parseInt(header.get(0).trim()));
        snapshot.setCapturedAtMillis(Long.parseLong(header.get(1).trim()));
        snapshot.setSessionId(header.get(2));
        snapshot.setPlayerId(header.get(3));
        snapshot.setPlayerName(header.get(4));
        snapshot.setCampaignId(header.get(5));
        snapshot.setSeedString(header.get(6));
        snapshot.setLocationId(header.get(7));
        snapshot.setFactionId(header.get(8));
        snapshot.setCredits(Double.parseDouble(header.get(9).trim()));

        int cargoCount = Integer.parseInt(header.get(10).trim());
        int shipCount = Integer.parseInt(header.get(11).trim());
        int officerCount = Integer.parseInt(header.get(12).trim());
        int declared = cargoCount + shipCount + officerCount;
        if (cargoCount < 0 || shipCount < 0 || officerCount < 0 || lines.length - 1 < declared) {
            throw new IllegalArgumentException("Guest snapshot declares " + declared
                    + " record(s) but only " + (lines.length - 1) + " line(s) are present");
        }

        int line = 1;
        for (int i = 0; i < cargoCount; i++, line++) {
            List<String> fields = requireFields(lines[line], CARGO_FIELD_COUNT, "cargo");
            snapshot.cargo.add(new CargoStack(fields.get(0), fields.get(1),
                    Double.parseDouble(fields.get(2).trim())));
        }
        for (int i = 0; i < shipCount; i++, line++) {
            List<String> fields = requireFields(lines[line], SHIP_FIELD_COUNT, "ship");
            snapshot.ships.add(new Ship(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                    fields.get(4), Float.parseFloat(fields.get(5).trim()),
                    Float.parseFloat(fields.get(6).trim()), fields.get(7), fields.get(8),
                    fields.get(9)));
        }
        for (int i = 0; i < officerCount; i++, line++) {
            List<String> fields = requireFields(lines[line], OFFICER_FIELD_COUNT, "officer");
            snapshot.officers.add(new Officer(fields.get(0), fields.get(1),
                    Integer.parseInt(fields.get(2).trim()), fields.get(3)));
        }
        return snapshot;
    }

    private static List<String> requireFields(String line, int expected, String what) {
        List<String> fields = CoopDelimited.split(line);
        if (fields.size() != expected) {
            throw new IllegalArgumentException("Expected " + expected + " guest snapshot " + what
                    + " fields, got " + fields.size());
        }
        return fields;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
