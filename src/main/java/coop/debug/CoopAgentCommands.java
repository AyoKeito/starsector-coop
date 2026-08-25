package coop.debug;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager.PunExData;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager.PunExReason;
import coop.campaign.CoopBarPoolCapture;
import coop.campaign.CoopCampaignReplicator;
import coop.campaign.CoopMarketSync;
import coop.campaign.CoopMissionBoardSync;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopFleetSnapshotFactory;
import coop.fleet.CoopFleetVisibilityProbe;
import coop.fleet.CoopGuestMirrorHandle;
import coop.fleet.CoopLocations;
import coop.fleet.CoopNpcActionTextCapture;
import coop.fleet.CoopNpcFleetReplicator;
import coop.fleet.CoopPresenceIndicator;
import coop.fleet.CoopSensorSync;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Phase 30 agent-bridge command registry: verb -&gt; handler, plus the newline-delimited JSON
 * request/response codec {@link CoopAgentBridge} pumps over its localhost socket.
 *
 * <p><b>Reuse, not re-reading.</b> Every query verb answers out of the same capture code the
 * replication path uses — {@code CoopCampaignReplicator}'s market/survey facades,
 * {@code CoopBarPoolCapture}, {@code CoopFleetVisibilityProbe}, {@code CoopSensorSync},
 * {@code CoopFleetSnapshotFactory}, {@code CoopNpcActionTextCapture}. A parallel state reader would
 * be worse than useless here: the whole point of the bridge is to prove host and guest agree, and a
 * reader that disagreed with the wire would report agreement the session does not have.
 *
 * <p><b>Refusals are the feature.</b> Market buy/sell, officer hire, bar-offer accept and market
 * open/close are deliberately absent. Each of those smoke checks exists precisely because a UI
 * listener drives it; a bridge version would exercise the engine underneath the listener and green
 * -light broken wiring. They are answered with {@link #UNSUPPORTED_MESSAGE} rather than silently
 * missing, so the caller learns the check is manual instead of assuming the verb was a typo.
 *
 * <p><b>Nothing here may throw at the caller.</b> {@link #dispatch} converts a malformed request, an
 * unknown verb and a throwing handler alike into an {@code ok:false} response line.
 */
public final class CoopAgentCommands {

    /** Refusal text for the verbs whose check has to stay a manual UI click-through. */
    public static final String UNSUPPORTED_MESSAGE = "unsupported: UI-path check stays manual";

    /**
     * The explicit non-verbs. They are not "not implemented yet" — implementing them would test the
     * wrong code path (see the class javadoc), so they are a permanent, named refusal.
     */
    static final Set<String> UI_PATH_VERBS = Set.of(
            "buy", "sell", "hire", "baraccept", "openmarket", "closemarket", "market_open",
            "market_close", "open", "close");

    /** Cap on a single response line, mostly so a runaway dump cannot wedge the socket. */
    static final int MAX_MEMBER_DUMP = 500;

    /**
     * Everything a handler is allowed to reach the game through. Small on purpose: a unit test fakes
     * it with two nulls and drives the registry with fake handlers, and the live implementation
     * ({@link CoopAgentBridge}) is the only place that touches {@code Global}.
     */
    public interface Context {
        /** The live sector, or {@code null} when no campaign is loaded. */
        SectorAPI sector();

        /** The live coop pump, or {@code null} when none is installed (no mod session yet). */
        CoopNetPump pump();
    }

    /** One command. Runs synchronously on the campaign thread inside the bridge's frame. */
    @FunctionalInterface
    public interface Handler {
        JSONObject run(JSONObject args, Context context) throws JSONException;
    }

    private final Map<String, Handler> handlers;

    public CoopAgentCommands() {
        this(liveHandlers());
    }

    /** Test seam: a registry of fakes, so the codec and the error isolation can be tested cheaply. */
    CoopAgentCommands(Map<String, Handler> handlers) {
        this.handlers = new LinkedHashMap<>(handlers);
    }

    /** The verbs this registry answers, sorted. */
    public Set<String> verbs() {
        return new TreeSet<>(handlers.keySet());
    }

    // ---- Codec ----------------------------------------------------------------------------------

    /**
     * Parses one request line, runs its handler, and returns the response line (no trailing
     * newline). Never throws and never returns null: every failure becomes an {@code ok:false}
     * response so the connection survives and the caller learns why.
     */
    public String dispatch(String requestLine, Context context) {
        int id = 0;
        try {
            JSONObject request = new JSONObject(requestLine == null ? "" : requestLine);
            id = request.optInt("id", 0);
            String verb = request.optString("cmd", "").trim().toLowerCase(Locale.ROOT);
            JSONObject args = request.optJSONObject("args");
            if (args == null) {
                args = new JSONObject();
            }
            Handler handler = handlers.get(verb);
            if (handler == null) {
                throw unknownVerb(verb);
            }
            JSONObject data = handler.run(args, context);
            return okResponse(id, data);
            // JSONException is checked in the bundled org.json (it predates the unchecked rewrite),
            // so it is named alongside the spec's RuntimeException | LinkageError rather than being
            // covered by it. LinkageError is here because an engine class that will not load in the
            // script sandbox must degrade to an error response, not kill the script.
        } catch (JSONException | RuntimeException | LinkageError ex) {
            return errorResponse(id, ex);
        }
    }

    private static RuntimeException unknownVerb(String verb) {
        if (UI_PATH_VERBS.contains(verb)) {
            return new UnsupportedOperationException(UNSUPPORTED_MESSAGE + " (" + verb + ")");
        }
        return new IllegalArgumentException("unknown command: " + verb);
    }

    /**
     * Built by hand rather than through {@code JSONObject.put} so that serializing a response can
     * never itself be the thing that throws. {@code data.toString()} on the bundled org.json
     * swallows its own failures and returns null; an empty object is the safe stand-in.
     */
    static String okResponse(int id, JSONObject data) {
        String body = data == null ? null : data.toString();
        if (body == null) {
            body = "{}";
        }
        return "{\"id\":" + id + ",\"ok\":true,\"data\":" + body + "}";
    }

    /** {@code "<ExceptionClass>: <message>"}, quoted. Same hand-built shape, same reason. */
    static String errorResponse(int id, Throwable ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return "{\"id\":" + id + ",\"ok\":false,\"error\":"
                + JSONObject.quote(ex.getClass().getSimpleName() + ": " + message) + "}";
    }

    // ---- Registry -------------------------------------------------------------------------------

    static Map<String, Handler> liveHandlers() {
        Map<String, Handler> map = new LinkedHashMap<>();
        map.put("status", CoopAgentCommands::status);
        map.put("fleets", CoopAgentCommands::fleets);
        map.put("market", CoopAgentCommands::market);
        map.put("markets", CoopAgentCommands::markets);
        map.put("barpool", CoopAgentCommands::barpool);
        map.put("survey", CoopAgentCommands::survey);
        map.put("visibility", CoopAgentCommands::visibility);
        map.put("teleport", CoopAgentCommands::teleport);
        map.put("pause", CoopAgentCommands::pause);
        map.put("ability", CoopAgentCommands::ability);
        map.put("setcr", CoopAgentCommands::setcr);
        map.put("give", CoopAgentCommands::give);
        map.put("objective", CoopAgentCommands::objective);
        map.put("surveyset", CoopAgentCommands::surveyset);
        map.put("expedition", CoopAgentCommands::expedition);
        return map;
    }

    // ---- Queries --------------------------------------------------------------------------------

    static JSONObject status(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopNetPump pump = context.pump();

        JSONObject out = new JSONObject();
        out.put("role", roleOf(pump).name());
        out.put("sessionActive", pump != null && pump.gameplaySessionActiveForBridge());
        out.put("paused", sector.isPaused());

        CoopSessionState session = pump == null ? null : pump.sessionStateForBridge();
        out.put("sessionId", session == null || session.sessionId() == null ? "" : session.sessionId());
        out.put("localPlayerId", session == null || session.localPlayerId() == null
                ? "" : session.localPlayerId());

        CampaignClockAPI clock = sector.getClock();
        JSONObject clockJson = new JSONObject();
        if (clock != null) {
            clockJson.put("date", clock.getDateString());
            clockJson.put("timestamp", clock.getTimestamp());
            clockJson.put("cycle", clock.getCycle());
            clockJson.put("month", clock.getMonth());
            clockJson.put("day", clock.getDay());
            clockJson.put("hour", clock.getHour());
        }
        out.put("clock", clockJson);

        CampaignFleetAPI player = sector.getPlayerFleet();
        JSONObject fleetJson = new JSONObject();
        if (player != null) {
            LocationAPI location = player.getContainingLocation();
            fleetJson.put("locationId", location == null ? "" : location.getId());
            fleetJson.put("x", round(player.getLocation() == null ? 0f : player.getLocation().x));
            fleetJson.put("y", round(player.getLocation() == null ? 0f : player.getLocation().y));
        }
        out.put("playerFleet", fleetJson);

        out.put("pause", pauseBlock(roleOf(pump),
                pump == null ? null : pump.pauseCoordinatorForBridge(),
                CoopNetPump.blockingScreenOpenForBridge(sector)));
        return out;
    }

    /**
     * Why the clock is where it is. {@code blockingScreenOpen} is on both roles because either client
     * can hold the shared clock by opening a screen, and it is read through
     * {@link CoopNetPump#blockingScreenOpenForBridge} — the same predicate that drives the guest's
     * {@code PAUSE_INTENT(SCREEN)} — rather than a second opinion about what blocks.
     *
     * <p>The intent breakdown is host-only because the host is the only client that has one: the
     * coordinator's fields on a guest are its own outgoing intents, not the authority's. When an
     * advance stalls, this block names which term of the OR is holding it.
     */
    static JSONObject pauseBlock(CoopConnectionRole role, CoopSharedPauseCoordinator coordinator,
                                 boolean blockingScreenOpen) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("blockingScreenOpen", blockingScreenOpen);
        if (role == CoopConnectionRole.HOST && coordinator != null) {
            out.put("hostIntent", coordinator.hostPauseIntent());
            out.put("guestIntent", coordinator.guestKeyPauseIntent() || coordinator.guestScreenPauseIntent());
            out.put("guestKeyIntent", coordinator.guestKeyPauseIntent());
            out.put("guestScreenIntent", coordinator.guestScreenPauseIntent());
            out.put("eitherInCombat", coordinator.eitherInCombat());
            out.put("effective", coordinator.effectivePaused());
        }
        return out;
    }

    /**
     * Every fleet in the sector (or in one location), in the same record the wire carries.
     *
     * <p>Deliberately <em>not</em> routed through {@code CoopNpcFleetReplicator}'s own set capture:
     * that one skips coop mirrors and runs positions through the motion smoother, both of which are
     * wire concerns. A host-vs-guest diff needs the opposite — the guest's mirrors <em>are</em> the
     * fleets to compare, and it wants the raw engine position, not the smoothed one it would have
     * sent. Every per-field capture below is still the shared one.
     */
    static JSONObject fleets(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        String locationFilter = optionalString(args, "locationId");

        CampaignFleetAPI playerFleet = sector.getPlayerFleet();
        CampaignFleetAPI guestMirror = CoopGuestMirrorHandle.current();
        CoopNetPump pump = context.pump();
        CoopSessionState session = pump == null ? null : pump.sessionStateForBridge();
        String playerLabel = CoopPresenceIndicator.presenceLabel(session == null ? null : session.localName());
        String localPlayerId = session == null ? null : session.localPlayerId();
        String remotePlayerId = session == null ? null : session.remotePlayerId();

        List<JSONObject> rows = new ArrayList<>();
        CoopLocations.forEach(sector, location -> {
            if (location == null) {
                return;
            }
            if (!locationFilter.isEmpty() && !locationFilter.equals(location.getId())) {
                return;
            }
            List<CampaignFleetAPI> present = location.getFleets();
            if (present == null) {
                return;
            }
            for (CampaignFleetAPI fleet : present) {
                if (fleet == null) {
                    continue;
                }
                JSONObject row = fleetRow(fleet, location, playerFleet, guestMirror, playerLabel,
                        localPlayerId, remotePlayerId);
                if (row != null) {
                    rows.add(row);
                }
            }
        });

        // Sorted by coopFleetId so two dumps line up positionally; the engine's list order is
        // insertion order and legitimately differs between clients.
        rows.sort((left, right) -> left.optString("coopFleetId").compareTo(right.optString("coopFleetId")));

        JSONObject out = new JSONObject();
        out.put("locationId", locationFilter);
        out.put("count", rows.size());
        out.put("fleets", new JSONArray(rows));
        return out;
    }

    private static JSONObject fleetRow(CampaignFleetAPI fleet, LocationAPI location,
                                       CampaignFleetAPI playerFleet, CampaignFleetAPI guestMirror,
                                       String playerLabel, String localPlayerId, String remotePlayerId) {
        try {
            JSONObject row = new JSONObject();
            row.put("engineId", nullSafe(fleet.getId()));
            row.put("coopFleetId",
                    coopFleetKey(fleet, playerFleet, guestMirror, localPlayerId, remotePlayerId));
            row.put("name", nullSafe(fleet.getName()));
            row.put("factionId", fleet.getFaction() == null ? "" : nullSafe(fleet.getFaction().getId()));
            row.put("locationId", location.getId() == null ? "" : location.getId());
            row.put("x", round(fleet.getLocation() == null ? 0f : fleet.getLocation().x));
            row.put("y", round(fleet.getLocation() == null ? 0f : fleet.getLocation().y));
            row.put("vx", round(fleet.getVelocity() == null ? 0f : fleet.getVelocity().x));
            row.put("vy", round(fleet.getVelocity() == null ? 0f : fleet.getVelocity().y));
            row.put("transponder", fleet.isTransponderOn());
            row.put("isPlayer", fleet == playerFleet);
            row.put("actionText", CoopNpcActionTextCapture.capture(fleet, playerFleet, guestMirror, playerLabel));

            CoopSensorSync.Profile sensors = CoopSensorSync.capture(fleet);
            JSONObject sensorJson = new JSONObject();
            sensorJson.put("sensorProfile", round(sensors.sensorProfile()));
            sensorJson.put("sensorStrength", round(sensors.sensorStrength()));
            row.put("sensors", sensorJson);

            List<CoopFleetSnapshot.Member> members = CoopFleetSnapshotFactory.captureMembers(fleet);
            JSONArray memberJson = new JSONArray();
            int limit = Math.min(members.size(), MAX_MEMBER_DUMP);
            for (int i = 0; i < limit; i++) {
                CoopFleetSnapshot.Member member = members.get(i);
                JSONObject entry = new JSONObject();
                entry.put("variantId", nullSafe(member.variantId()));
                entry.put("cr", round(member.cr()));
                entry.put("hullFraction", round(member.hullFraction()));
                memberJson.put(entry);
            }
            row.put("members", memberJson);
            row.put("fleetHash", CoopFleetSnapshot.computeFleetHash(members));
            return row;
        } catch (RuntimeException | LinkageError | JSONException ex) {
            // One unreadable fleet must not cost the caller the whole dump.
            return null;
        }
    }

    /**
     * The key a host-vs-guest fleet diff lines up on. NPC fleets already agree (guest mirrors carry the
     * host's fleet id in memory), but the two <em>player</em> fleets did not: each client saw its own
     * fleet under a local engine id and its partner under another, so one logical fleet showed up as
     * four one-sided rows. Both clients know both player ids from the handshake, so a player fleet and
     * its remote mirror are keyed {@code player:<playerId>} on both sides. Engine ids stay in
     * {@code engineId}, which is per-instance by nature and excluded from the default diff.
     */
    static String coopFleetKey(CampaignFleetAPI fleet, CampaignFleetAPI playerFleet,
                               CampaignFleetAPI guestMirror, String localPlayerId, String remotePlayerId) {
        if (fleet == playerFleet && localPlayerId != null && !localPlayerId.trim().isEmpty()) {
            return "player:" + localPlayerId.trim();
        }
        if (isPlayerMirror(fleet, guestMirror) && remotePlayerId != null && !remotePlayerId.trim().isEmpty()) {
            return "player:" + remotePlayerId.trim();
        }
        return coopFleetId(fleet);
    }

    /** The published handle first (it is the only producer), the memory tag as the cold-start fallback. */
    private static boolean isPlayerMirror(CampaignFleetAPI fleet, CampaignFleetAPI guestMirror) {
        if (fleet == null) {
            return false;
        }
        if (guestMirror != null && fleet == guestMirror) {
            return true;
        }
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        return memory != null && memory.getBoolean(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG);
    }

    /** Guest mirrors carry the host's fleet id in memory; on the host the engine id already is it. */
    private static String coopFleetId(CampaignFleetAPI fleet) {
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        if (memory != null) {
            String tagged = memory.getString(CoopNpcFleetReplicator.NPC_MIRROR_TAG);
            if (tagged != null && !tagged.trim().isEmpty()) {
                return tagged;
            }
        }
        return nullSafe(fleet.getId());
    }

    static JSONObject market(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        String marketId = requiredString(args, "marketId");
        CoopCampaignReplicator replicator = requireReplicator(context);

        MarketAPI market = sector.getEconomy() == null ? null : sector.getEconomy().getMarket(marketId);
        if (market == null) {
            throw new IllegalArgumentException("no market with id " + marketId);
        }

        boolean host = roleOf(context.pump()) == CoopConnectionRole.HOST;
        JSONObject out = new JSONObject();
        out.put("marketId", marketId);
        out.put("role", roleOf(context.pump()).name());

        if (!host && !replicator.openMarketStockedForBridge(market)) {
            // A guest that has never docked here has no stock at all. Reporting that as an empty
            // shop would read as "host and guest disagree"; it is "there is nothing to compare yet".
            out.put("stocked", false);
            return out;
        }

        // HOST: dock-equivalent by design. This runs the same updateCargoPrePlayerInteraction() a
        // real dock (and the market snapshot broadcast) runs before capturing, because a market the
        // host has never docked at has never had stock generated. The generation is the point, not a
        // side effect — see CoopCampaignReplicator#captureMarketStockForBridge.
        List<CoopMarketSync.StockItem> items = replicator.captureMarketStockForBridge(market, host);
        out.put("stocked", true);

        JSONArray stock = new JSONArray();
        for (CoopMarketSync.StockItem item : items) {
            JSONObject entry = new JSONObject();
            entry.put("kind", item.kind().name());
            entry.put("itemId", nullSafe(item.itemId()));
            entry.put("quantity", item.quantity());
            entry.put("unitPrice", round(item.unitPrice()));
            entry.put("detail", nullSafe(item.detail()));
            stock.put(entry);
        }
        out.put("count", items.size());
        out.put("items", stock);
        return out;
    }

    /**
     * Every market in the economy: the index the {@code market} verb's {@code marketId} comes from.
     *
     * <p>Enumeration only — deliberately no {@code ensureOpenMarketStocked}. The {@code market} verb
     * stocks on the host because a stock dump of an ungenerated market is meaningless; running that
     * over the whole economy would generate stock at ~150 markets as a side effect of asking what
     * exists, which is a world change nobody asked for and a diff nobody could interpret.
     */
    static JSONObject markets(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        EconomyAPI economy = sector.getEconomy();
        List<MarketAPI> all = economy == null ? List.<MarketAPI>of() : economy.getMarketsCopy();

        List<JSONObject> rows = new ArrayList<>();
        for (MarketAPI market : all) {
            if (market == null) {
                continue;
            }
            JSONObject row = new JSONObject();
            row.put("marketId", nullSafe(market.getId()));
            row.put("name", nullSafe(market.getName()));
            row.put("factionId", nullSafe(market.getFactionId()));
            row.put("size", market.getSize());
            row.put("locationId", marketLocationId(market));
            rows.add(row);
        }
        rows.sort((left, right) -> left.optString("marketId").compareTo(right.optString("marketId")));

        JSONObject out = new JSONObject();
        out.put("count", rows.size());
        out.put("markets", new JSONArray(rows));
        return out;
    }

    private static String marketLocationId(MarketAPI market) {
        LocationAPI location = market.getContainingLocation();
        if (location == null && market.getPrimaryEntity() != null) {
            location = market.getPrimaryEntity().getContainingLocation();
        }
        return location == null ? "" : nullSafe(location.getId());
    }

    /**
     * The portside bar pool. {@code CoopBarPoolCapture#capture()} walks
     * {@code PortsideBarData.getEvents()} in place, so its order <em>is</em> the render order; the
     * flat id list is emitted alongside so an order-only difference is one array compare rather than
     * a field-by-field walk.
     */
    static JSONObject barpool(JSONObject args, Context context) throws JSONException {
        List<CoopMissionBoardSync.Entry> entries = new CoopBarPoolCapture().capture();
        JSONObject out = new JSONObject();
        if (entries == null) {
            // capture() returns null (not empty) when there was no pool to read. That distinction is
            // load-bearing on the wire and it is load-bearing here too: "could not look" is not
            // "the bar is empty".
            out.put("readable", false);
            return out;
        }
        out.put("readable", true);
        out.put("count", entries.size());

        JSONArray offers = new JSONArray();
        JSONArray renderOrder = new JSONArray();
        for (CoopMissionBoardSync.Entry entry : entries) {
            JSONObject offer = new JSONObject();
            offer.put("barEventId", nullSafe(entry.missionId()));
            offer.put("seed", entry.contentSeed());
            offer.put("shownAt", nullSafe(entry.marketId()));
            offer.put("eventKind", nullSafe(entry.eventKind()));
            offer.put("expiresAtDay", entry.expiresAtDay());
            offers.put(offer);
            renderOrder.put(nullSafe(entry.missionId()));
        }
        out.put("offers", offers);
        out.put("renderOrder", renderOrder);
        return out;
    }

    static JSONObject survey(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopCampaignReplicator replicator = requireReplicator(context);
        String systemId = optionalString(args, "systemId");
        if (systemId.isEmpty()) {
            systemId = "all";
        }

        Map<String, String> levels = new TreeMap<>();
        Map<String, String> ruins = new TreeMap<>();
        if ("all".equalsIgnoreCase(systemId)) {
            CoopLocations.forEach(sector, location ->
                    replicator.collectSurveyStateForBridge(location, levels, ruins));
        } else {
            LocationAPI scope = resolveSurveyScope(sector, systemId);
            if (scope == null) {
                throw new IllegalArgumentException("no star system with id " + systemId);
            }
            replicator.collectSurveyStateForBridge(scope, levels, ruins);
        }

        JSONObject planets = new JSONObject();
        for (Map.Entry<String, String> planet : levels.entrySet()) {
            JSONObject entry = new JSONObject();
            entry.put("level", planet.getValue());
            entry.put("ruinsExplored", Boolean.parseBoolean(ruins.get(planet.getKey())));
            entry.put("hasRuins", ruins.containsKey(planet.getKey()));
            planets.put(planet.getKey(), entry);
        }

        JSONObject out = new JSONObject();
        out.put("scope", systemId);
        out.put("count", levels.size());
        out.put("planets", planets);
        return out;
    }

    /**
     * Resolve a single-system survey scope by the id the {@code all} dump emits.
     *
     * <p>{@code SectorAPI#getStarSystem} matches on the system's <em>name</em>, so a generated id like
     * {@code system_16cf} — which is exactly what every other verb emits as {@code locationId} — came
     * back null and the verb refused an id it had just handed out. Id first over
     * {@code getStarSystems()}, then the name lookup, then any location (hyperspace, constellations)
     * so an id from any dump resolves.
     */
    static LocationAPI resolveSurveyScope(SectorAPI sector, String systemId) {
        List<StarSystemAPI> systems = sector.getStarSystems();
        if (systems != null) {
            for (StarSystemAPI system : systems) {
                if (system != null && systemId.equals(system.getId())) {
                    return system;
                }
            }
        }
        StarSystemAPI byName = sector.getStarSystem(systemId);
        if (byName != null) {
            return byName;
        }
        return CoopLocations.byId(sector, systemId);
    }

    /**
     * Detectability, in two forms. {@code lines} is the probe's own text dump, unchanged, for reading.
     * {@code view} is the same computation as a {@code coopFleetId -> visibility level} map, and it is
     * built so the two clients' maps are directly comparable: the guest reports what it actually sees,
     * the host reports what it predicts the guest sees (asked of the engine through the guest's reverse
     * mirror). Equal maps mean the sensor model agrees; the entries that differ are the gaps. Diffing
     * the text lines instead would compare two different sentences about the same fact.
     */
    static JSONObject visibility(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        String fleetId = optionalString(args, "fleetId");
        CoopConnectionRole role = roleOf(context.pump());
        return visibilityFor(sector, role, fleetId);
    }

    static JSONObject visibilityFor(SectorAPI sector, CoopConnectionRole role, String fleetId)
            throws JSONException {
        String dump = role == CoopConnectionRole.GUEST
                ? CoopFleetVisibilityProbe.dumpGuest(sector)
                : CoopFleetVisibilityProbe.dumpHost(sector);

        JSONArray lines = new JSONArray();
        for (String line : dump.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!fleetId.isEmpty() && !trimmed.contains(fleetId)) {
                continue;
            }
            lines.put(trimmed);
        }

        Map<String, String> view = role == CoopConnectionRole.GUEST
                ? CoopFleetVisibilityProbe.guestVisibilityActual(sector)
                : CoopFleetVisibilityProbe.guestVisibilityEstimate(sector);
        JSONObject viewJson = new JSONObject();
        for (Map.Entry<String, String> entry : view.entrySet()) {
            if (!fleetId.isEmpty() && !entry.getKey().contains(fleetId)) {
                continue;
            }
            viewJson.put(entry.getKey(), entry.getValue());
        }

        JSONObject out = new JSONObject();
        out.put("role", role.name());
        out.put("fleetId", fleetId);
        out.put("lines", lines);
        out.put("viewCount", viewJson.length());
        out.put("view", viewJson);
        return out;
    }

    // ---- Setup actions --------------------------------------------------------------------------

    /** The mirror's own relocation pattern: leave the old location, join the new one, then place. */
    static JSONObject teleport(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        String locationId = requiredString(args, "locationId");
        float x = (float) requiredDouble(args, "x");
        float y = (float) requiredDouble(args, "y");

        LocationAPI target = CoopLocations.byId(sector, locationId);
        if (target == null) {
            throw new IllegalArgumentException("no location with id " + locationId);
        }

        LocationAPI current = player.getContainingLocation();
        if (current != null && current != target) {
            current.removeEntity(player);
        }
        if (player.getContainingLocation() != target) {
            target.addEntity(player);
        }
        player.setLocation(x, y);

        JSONObject out = new JSONObject();
        out.put("locationId", locationId);
        out.put("x", round(x));
        out.put("y", round(y));
        out.put("movedFrom", current == null ? "" : nullSafe(current.getId()));
        return out;
    }

    /**
     * Pause through the shared coordinator, never {@code sector.setPaused} directly.
     *
     * <p>Host: {@code setHostPauseIntent} — the non-toggling authority lever the pump ORs into
     * {@code effectivePaused()} on its next frame. Guest: the screen-level intent, which is the only
     * guest-side lever with on/off (rather than toggle) semantics; the pump ships it as a
     * {@code PAUSE_INTENT(SCREEN)} exactly as opening a blocking screen would, and the host decides.
     * With no session at all the coordinator is absent and this is a refusal, not a local
     * {@code setPaused} — a bridge that could desync the clock locally would defeat its own purpose.
     */
    static JSONObject pause(JSONObject args, Context context) throws JSONException {
        boolean on = requiredPauseState(args);
        CoopNetPump pump = context.pump();
        if (pump == null) {
            throw new IllegalStateException("no coop pump installed; pause has no shared coordinator");
        }
        CoopSharedPauseCoordinator coordinator = pump.pauseCoordinatorForBridge();
        CoopConnectionRole role = roleOf(pump);

        boolean changed;
        if (role == CoopConnectionRole.GUEST) {
            changed = coordinator.updateGuestScreenLevel(on);
        } else {
            coordinator.setHostPauseIntent(on);
            changed = true;
        }

        JSONObject out = new JSONObject();
        out.put("role", role.name());
        out.put("requested", on);
        out.put("changed", changed);
        out.put("effectivePaused", coordinator.effectivePaused());
        SectorAPI sector = context.sector();
        out.put("sectorPaused", sector != null && sector.isPaused());
        return out;
    }

    /**
     * UI-faithful on purpose: this is the same {@code AbilityPlugin#activate()} the toolbar button
     * calls, so the engine's {@code isPlayerFleet} check, its
     * {@code reportPlayerActivatedAbility} callback and therefore the mod's own listener all fire.
     * An ability applied by poking its effect directly would test a path no player can reach.
     *
     * <p>With no {@code on} argument that is all this does — one press of the button, whatever state
     * the ability was in. That is the right default for a one-shot like the distress call, and it is
     * useless for a toggle like the transponder: pressing a toggle that is already on re-arms it
     * rather than turning it off, so a script could not put the fleet into a known state. The optional
     * {@code on} makes the request a level rather than a press: {@code true} activates only if the
     * ability is off, {@code false} deactivates only if it is on, and either is a no-op otherwise, so
     * a setup step can be re-run without flipping what it just set. The guard reads
     * {@code isActiveOrInProgress} rather than {@code isActive} so an ability mid-turn-on is treated
     * as on, which is what the toolbar shows.
     */
    static JSONObject ability(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        String abilityId = requiredString(args, "abilityId");

        AbilityPlugin plugin = player.getAbility(abilityId);
        if (plugin == null) {
            throw new IllegalArgumentException("player fleet has no ability " + abilityId);
        }

        Boolean desired = optionalAbilityState(args);
        if (desired == null) {
            plugin.activate();
        } else if (desired) {
            if (!plugin.isActiveOrInProgress()) {
                plugin.activate();
            }
        } else if (plugin.isActiveOrInProgress()) {
            plugin.deactivate();
        }

        JSONObject out = new JSONObject();
        out.put("abilityId", abilityId);
        out.put("active", plugin.isActive());
        out.put("activeOrInProgress", plugin.isActiveOrInProgress());
        return out;
    }

    /** CR-recovery drill setup: drop own members' CR so the climb back is observable in a diff. */
    static JSONObject setcr(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        float value = (float) requiredDouble(args, "value");
        String memberSelector = optionalString(args, "memberIndex");
        if (memberSelector.isEmpty()) {
            memberSelector = "all";
        }

        List<FleetMemberAPI> members = player.getFleetData() == null
                ? List.of() : player.getFleetData().getMembersListCopy();
        if (members.isEmpty()) {
            throw new IllegalStateException("player fleet has no members");
        }

        List<Integer> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(memberSelector)) {
            for (int i = 0; i < members.size(); i++) {
                targets.add(i);
            }
        } else {
            int index;
            try {
                index = Integer.parseInt(memberSelector.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("memberIndex must be an integer or \"all\", got "
                        + memberSelector);
            }
            if (index < 0 || index >= members.size()) {
                throw new IllegalArgumentException("memberIndex " + index + " out of range 0.."
                        + (members.size() - 1));
            }
            targets.add(index);
        }

        JSONArray applied = new JSONArray();
        for (int index : targets) {
            FleetMemberAPI member = members.get(index);
            RepairTrackerAPI repair = member == null ? null : member.getRepairTracker();
            if (repair == null) {
                continue;
            }
            repair.setCR(value);
            JSONObject entry = new JSONObject();
            entry.put("index", index);
            entry.put("memberId", nullSafe(member.getId()));
            entry.put("cr", round(repair.getCR()));
            applied.put(entry);
        }

        JSONObject out = new JSONObject();
        out.put("value", round(value));
        out.put("count", applied.length());
        out.put("members", applied);
        return out;
    }

    static JSONObject give(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        CargoAPI cargo = player.getCargo();
        if (cargo == null) {
            throw new IllegalStateException("player fleet has no cargo");
        }

        String commodityId = optionalString(args, "commodityId");
        double qty = args.optDouble("qty", 0d);
        double credits = args.optDouble("credits", 0d);
        if (commodityId.isEmpty() && credits == 0d) {
            throw new IllegalArgumentException("give needs commodityId+qty, credits, or both");
        }

        JSONObject out = new JSONObject();
        if (!commodityId.isEmpty()) {
            if (qty == 0d || Double.isNaN(qty)) {
                throw new IllegalArgumentException("give with commodityId needs a non-zero qty");
            }
            cargo.addCommodity(commodityId, (float) qty);
            out.put("commodityId", commodityId);
            out.put("qty", round((float) qty));
        }
        if (credits != 0d && !Double.isNaN(credits)) {
            cargo.getCredits().add((float) credits);
            out.put("credits", round((float) credits));
            out.put("creditsTotal", round(cargo.getCredits().get()));
        }
        return out;
    }

    static JSONObject objective(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopCampaignReplicator replicator = requireReplicator(context);
        String entityId = requiredString(args, "entityId");
        String factionId = requiredString(args, "factionId");

        replicator.applyObjectiveOwnershipForBridge(entityId, factionId);

        SectorEntityToken entity = sector.getEntityById(entityId);
        JSONObject out = new JSONObject();
        out.put("entityId", entityId);
        out.put("requestedFactionId", factionId);
        out.put("factionId", entity == null || entity.getFaction() == null
                ? "" : nullSafe(entity.getFaction().getId()));
        return out;
    }

    static JSONObject surveyset(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopCampaignReplicator replicator = requireReplicator(context);
        String planetId = requiredString(args, "planetId");
        String level = requiredString(args, "level").trim().toUpperCase(Locale.ROOT);

        // Validate before applying: the shared apply logs-and-skips an unknown level, which from a
        // bridge caller's side would look like a silent success.
        try {
            MarketAPI.SurveyLevel.valueOf(level);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown survey level " + level);
        }
        replicator.applySurveyLevelForBridge(planetId, level);

        JSONObject out = new JSONObject();
        out.put("planetId", planetId);
        out.put("requestedLevel", level);
        SectorEntityToken entity = sector.getEntityById(planetId);
        MarketAPI market = entity instanceof com.fs.starfarer.api.campaign.PlanetAPI planet
                ? planet.getMarket() : null;
        out.put("level", market == null || market.getSurveyLevel() == null
                ? "" : market.getSurveyLevel().name());
        return out;
    }

    // ---- expedition: forcing the Phase 24 milestone-3 warning ------------------------------------

    /**
     * What the free-port precondition is, in one sentence, for every error path that needs it.
     *
     * <p>{@code ANTI_FREE_PORT} is the only reason a dev caller can conjure on demand: the second
     * block of {@code getExpeditionReasons} adds one, unconditionally and with no commodity maths, for
     * every player-owned non-hyperspace market with free port on. The other two reasons need real
     * campaign conditions — a production share the faction actually notices, or a claimed system — and
     * this verb deliberately does not fake either.
     */
    static final String FREE_PORT_HINT = "Toggle free port on a player colony first: any player-owned "
            + "colony outside hyperspace with free port on gives every vsFreePort faction (in vanilla "
            + "hegemony, luddic_church and sindrian_diktat) a reason with no other preconditions.";

    /** One faction's eligibility for a forced expedition, reduced to what the choice turns on. */
    record ExpeditionCandidate(String factionId, int reasonCount, boolean freePortReason,
                               boolean ongoing) {
    }

    /**
     * Forces one punitive expedition so the Phase 24 milestone-3 warning check does not have to wait
     * out months of game time for an organic one.
     *
     * <p>This is campaign state through a public vanilla API, not a UI shortcut:
     * {@code PunitiveExpeditionManager.createExpedition} is the same call the manager's own
     * {@code checkExpedition} makes once a faction's anger crosses its threshold, and the
     * {@code PunitiveExpeditionIntel} it builds registers itself with the intel manager exactly as an
     * organic one does. The only guard it skips is {@code MAX_CONCURRENT}, which is a pacing knob.
     *
     * <p><b>Host-only.</b> The guest's manager is on the Phase 13 suppressor list, so an expedition
     * forced there would belong to no authority and would be overwritten by the next sync. Role
     * {@code NONE} is allowed: a single instance being set up for a check has a live manager and no
     * session yet.
     *
     * <p><b>Success is detected, not assumed.</b> {@code createExpedition} is {@code void} and returns
     * silently from five different bail-outs, so this reads {@code PunExData.intel} afterwards — the
     * field the manager itself uses to know an expedition is running — and reports a diagnostic naming
     * the likely bail-out when it is still null.
     */
    static JSONObject expedition(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopConnectionRole role = roleOf(context.pump());
        requireExpeditionAuthority(role);
        String requestedFactionId = optionalString(args, "factionId");

        PunitiveExpeditionManager manager = PunitiveExpeditionManager.getInstance();
        if (manager == null) {
            throw new IllegalStateException("this campaign has no PunitiveExpeditionManager ("
                    + PunitiveExpeditionManager.KEY + " is unset in sector memory), so nothing can send"
                    + " a punitive expedition");
        }

        List<FactionAPI> factions = punitiveFactions(sector, requestedFactionId);
        List<ExpeditionCandidate> candidates = new ArrayList<>();
        Map<String, FactionAPI> byId = new LinkedHashMap<>();
        Map<String, List<PunExReason>> reasonsById = new LinkedHashMap<>();
        for (FactionAPI faction : factions) {
            String factionId = nullSafe(faction.getId());
            PunExData tracked = manager.getDataFor(faction);
            List<PunExReason> reasons = expeditionReasons(manager, faction, tracked);
            byId.put(factionId, faction);
            reasonsById.put(factionId, reasons);
            candidates.add(new ExpeditionCandidate(factionId, reasons.size(),
                    hasFreePortReason(reasons), tracked != null && tracked.intel != null));
        }

        ExpeditionCandidate chosen = chooseExpeditionFaction(candidates);
        if (chosen == null) {
            throw new IllegalStateException(noExpeditionCandidateMessage(candidates));
        }

        FactionAPI faction = byId.get(chosen.factionId());
        List<PunExReason> reasons = reasonsById.get(chosen.factionId());
        // The manager's own record when it has one, so anger, threshold and the intel handle stay the
        // ones it tracks. Its map is populated from live markets on advance(); a faction it has not
        // reached yet is registered here the same way, so the expedition is tracked rather than
        // orphaned the moment it is created.
        PunExData data = manager.getDataFor(faction);
        boolean tracked = data != null;
        if (data == null) {
            data = new PunExData();
            data.faction = faction;
            manager.getData().put(faction, data);
        }

        manager.createExpedition(data);

        JSONObject out = new JSONObject();
        out.put("role", role.name());
        out.put("factionId", chosen.factionId());
        out.put("reasonCount", chosen.reasonCount());
        out.put("reasonTypes", new JSONArray(reasonTypes(reasons)));
        out.put("trackedBefore", tracked);
        out.put("ongoing", manager.getOngoing());

        if (!(data.intel instanceof PunitiveExpeditionIntel expedition)) {
            throw new IllegalStateException("createExpedition made nothing for " + chosen.factionId()
                    + " (reasons=" + chosen.reasonCount() + "); vanilla picked a reason and then bailed"
                    + " — no player colony matched it at or above punExMinColonySizeForNonTerritorial,"
                    + " the faction has no market to stage the fleet from, or the target has no"
                    + " raidable spaceport");
        }
        out.put("created", true);
        MarketAPI target = expedition.getTarget();
        out.put("targetMarketId", target == null ? "" : nullSafe(target.getId()));
        out.put("targetMarketName", target == null ? "" : nullSafe(target.getName()));
        out.put("etaDays", round(expedition.getETA()));
        return out;
    }

    /** Refuses the guest, allows the host and a session-less instance. */
    static void requireExpeditionAuthority(CoopConnectionRole role) {
        if (role == CoopConnectionRole.GUEST) {
            throw new IllegalStateException("expedition is host-only: the guest's"
                    + " PunitiveExpeditionManager is suppressed (Phase 13), so one forced here would"
                    + " belong to no authority. Force it on the host — the warning reaches the guest"
                    + " through the Phase 24 expedition sync.");
        }
    }

    /**
     * The factions worth asking about: those carrying {@code punitiveExpeditionData}. A faction
     * without it makes {@code createExpedition} return on its first line, so refusing here is the
     * difference between a named error and a silent no-op.
     */
    private static List<FactionAPI> punitiveFactions(SectorAPI sector, String requestedFactionId) {
        if (!requestedFactionId.isEmpty()) {
            FactionAPI faction = sector.getFaction(requestedFactionId);
            if (faction == null) {
                throw new IllegalArgumentException("no faction with id " + requestedFactionId);
            }
            if (!hasPunitiveData(faction)) {
                throw new IllegalArgumentException("faction " + requestedFactionId + " has no "
                        + Factions.CUSTOM_PUNITIVE_EXPEDITION_DATA + " custom data, so vanilla can"
                        + " never send a punitive expedition from it");
            }
            return List.of(faction);
        }
        List<FactionAPI> all = sector.getAllFactions();
        List<FactionAPI> found = new ArrayList<>();
        if (all != null) {
            for (FactionAPI faction : all) {
                if (faction != null && faction.getId() != null && hasPunitiveData(faction)) {
                    found.add(faction);
                }
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no faction in this campaign carries "
                    + Factions.CUSTOM_PUNITIVE_EXPEDITION_DATA + " custom data");
        }
        return found;
    }

    private static boolean hasPunitiveData(FactionAPI faction) {
        try {
            return faction.getCustomJSONObject(Factions.CUSTOM_PUNITIVE_EXPEDITION_DATA) != null;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * The faction's live reasons. Asked through a throwaway {@code PunExData} when the manager has no
     * record yet: {@code getExpeditionReasons} reads only the faction off it, and scanning must not
     * register every faction in the sector as a side effect of being asked what is possible.
     */
    private static List<PunExReason> expeditionReasons(PunitiveExpeditionManager manager,
                                                       FactionAPI faction, PunExData tracked) {
        PunExData probe = tracked;
        if (probe == null) {
            probe = new PunExData();
            probe.faction = faction;
        }
        try {
            List<PunExReason> reasons = manager.getExpeditionReasons(probe);
            return reasons == null ? List.<PunExReason>of() : reasons;
        } catch (RuntimeException | LinkageError ex) {
            // One unreadable faction must not cost the caller every other candidate.
            return List.of();
        }
    }

    private static boolean hasFreePortReason(List<PunExReason> reasons) {
        for (PunExReason reason : reasons) {
            if (reason != null
                    && reason.type == PunitiveExpeditionManager.PunExType.ANTI_FREE_PORT) {
                return true;
            }
        }
        return false;
    }

    private static List<String> reasonTypes(List<PunExReason> reasons) {
        Set<String> types = new TreeSet<>();
        for (PunExReason reason : reasons) {
            if (reason != null && reason.type != null) {
                types.add(reason.type.name());
            }
        }
        return new ArrayList<>(types);
    }

    /**
     * The first faction that can actually send one, preferring an {@code ANTI_FREE_PORT} reason so a
     * repeated smoke run picks the same faction rather than whichever one the economy angered today.
     * Factions already running an expedition are skipped: the manager tracks one intel handle per
     * faction, so forcing a second would orphan the first.
     */
    static ExpeditionCandidate chooseExpeditionFaction(List<ExpeditionCandidate> candidates) {
        ExpeditionCandidate fallback = null;
        for (ExpeditionCandidate candidate : candidates) {
            if (candidate == null || candidate.ongoing() || candidate.reasonCount() <= 0) {
                continue;
            }
            if (candidate.freePortReason()) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /** Why nothing was eligible, in the caller's terms rather than the engine's. */
    static String noExpeditionCandidateMessage(List<ExpeditionCandidate> candidates) {
        int ongoing = 0;
        for (ExpeditionCandidate candidate : candidates) {
            if (candidate != null && candidate.ongoing()) {
                ongoing++;
            }
        }
        if (candidates.size() == 1) {
            ExpeditionCandidate only = candidates.get(0);
            if (only.ongoing()) {
                return "faction " + only.factionId() + " is already running a punitive expedition;"
                        + " the manager tracks one per faction, so forcing another would orphan it."
                        + " Name a different faction or let this one resolve.";
            }
            return "faction " + only.factionId() + " has no live punitive expedition reason. "
                    + FREE_PORT_HINT;
        }
        if (ongoing == candidates.size()) {
            return "all " + candidates.size() + " factions with punitive expedition data are already"
                    + " running one; let one resolve first";
        }
        return "none of the " + candidates.size() + " factions with punitive expedition data has a"
                + " live reason (" + ongoing + " already running one). " + FREE_PORT_HINT;
    }

    // ---- Shared helpers -------------------------------------------------------------------------

    private static CoopConnectionRole roleOf(CoopNetPump pump) {
        if (pump == null) {
            return CoopConnectionRole.NONE;
        }
        CoopConnectionRole role = pump.netServiceForBridge().role();
        return role == null ? CoopConnectionRole.NONE : role;
    }

    private static SectorAPI requireSector(Context context) {
        SectorAPI sector = context == null ? null : context.sector();
        if (sector == null) {
            throw new IllegalStateException("no campaign loaded");
        }
        return sector;
    }

    private static CampaignFleetAPI requirePlayerFleet(SectorAPI sector) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        if (player == null) {
            throw new IllegalStateException("no player fleet");
        }
        return player;
    }

    private static CoopCampaignReplicator requireReplicator(Context context) {
        CoopNetPump pump = context == null ? null : context.pump();
        if (pump == null) {
            throw new IllegalStateException("no coop pump installed; this verb reuses its capture code");
        }
        return pump.campaignReplicatorForBridge();
    }

    private static String requiredString(JSONObject args, String key) {
        String value = args.optString(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing required argument " + key);
        }
        return value;
    }

    private static String optionalString(JSONObject args, String key) {
        String value = args.optString(key, "");
        return value == null ? "" : value.trim();
    }

    private static double requiredDouble(JSONObject args, String key) {
        double value = args.optDouble(key, Double.NaN);
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("missing or non-numeric argument " + key);
        }
        return value;
    }

    private static boolean requiredPauseState(JSONObject args) {
        if (args.has("on")) {
            Object raw = args.opt("on");
            if (raw instanceof Boolean flag) {
                return flag;
            }
            return parseOnOff(String.valueOf(raw), "pause");
        }
        if (args.has("state")) {
            return parseOnOff(args.optString("state", ""), "pause");
        }
        throw new IllegalArgumentException("pause needs {\"on\":true|false} or {\"state\":\"on\"|\"off\"}");
    }

    /** {@code null} = the argument was absent, which for {@code ability} means "just press the button". */
    private static Boolean optionalAbilityState(JSONObject args) {
        if (!args.has("on")) {
            return null;
        }
        Object raw = args.opt("on");
        if (raw instanceof Boolean flag) {
            return flag;
        }
        return parseOnOff(String.valueOf(raw), "ability");
    }

    private static boolean parseOnOff(String word, String verb) {
        String normalized = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "on", "true", "1", "yes" -> true;
            case "off", "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException(verb + " state must be on|off, got " + word);
        };
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Floats are quantized to 3 decimals before they go into a response. A structural diff on the
     * MCP side compares numbers literally, and the last bits of a float that travelled through the
     * engine differ between clients for reasons that are not desync.
     */
    private static double round(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0d;
        }
        return Math.round(value * 1000d) / 1000d;
    }
}
