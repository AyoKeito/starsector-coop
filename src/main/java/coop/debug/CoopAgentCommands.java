package coop.debug;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.impl.campaign.GateEntityPlugin;
import com.fs.starfarer.api.impl.campaign.econ.impl.Cryorevival;
import com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager.PunExData;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager.PunExReason;
import com.fs.starfarer.api.util.Misc;
import coop.campaign.CoopBarPoolCapture;
import coop.campaign.CoopCampaignReplicator;
import coop.campaign.CoopMarketSync;
import coop.campaign.CoopMissionBoardSync;
import coop.campaign.CoopSkeletonMutationWatcher;
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
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Comparator;
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
        map.put("colonizable", CoopAgentCommands::colonizable);
        map.put("landmarks", CoopAgentCommands::landmarks);
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

    // ---- colonizable: naming a Phase 24 colony target without searching the map -------------------

    /** Rows returned when the caller does not say. Enough to choose from, short enough to read. */
    static final int COLONIZABLE_DEFAULT_LIMIT = 10;

    /** Hard ceiling on {@code limit}, so one query cannot dump ~1500 planets down the socket. */
    static final int COLONIZABLE_MAX_LIMIT = 200;

    /**
     * One uncolonized planet, reduced to what naming a colony target turns on.
     *
     * <p>{@code distanceLy} is hyperspace distance from the player fleet to the planet's system and is
     * {@code 0} for anything in the fleet's own system; {@code distanceSu} is the in-system distance
     * and is {@code 0} for everything else, so the pair sorts "here first, then nearest".
     *
     * <p>{@code x}/{@code y} are the planet's <em>current</em> location-local coordinates — the pair
     * {@code teleport} takes alongside {@code systemId}, so naming a target and flying to it is one
     * query and one action instead of a hand-derivation off the orbit. An orbiting planet's pair moves
     * with the clock; that is not a diff hazard, because both clients read it off the same shared clock.
     */
    record ColonizableCandidate(String planetId, String name, String type, boolean gasGiant,
                                String systemId, String systemName, float x, float y,
                                int marketsInSystem, float distanceLy,
                                float distanceSu, float hazard, String surveyLevel,
                                boolean unexploredRuins, List<String> conditions) {
    }

    /** Nearest first: light years, then in-system distance, then id so the order never wobbles. */
    static final Comparator<ColonizableCandidate> COLONIZABLE_ORDER =
            Comparator.comparingDouble(ColonizableCandidate::distanceLy)
                    .thenComparingDouble(ColonizableCandidate::distanceSu)
                    .thenComparing(ColonizableCandidate::planetId);

    /**
     * The uncolonized planets nearest the local player fleet, so the Phase 24 smoke can name a colony
     * target instead of hunting the map for one.
     *
     * <p><b>Pure query, any role.</b> It reads local engine state and writes nothing, so it answers on
     * host, guest and a session-less instance alike — same contract as {@code markets} and
     * {@code fleets}, and for the same reason. It is also deliberately diffable: two clients whose
     * worldgen agrees must return the same planets in the same order.
     *
     * <p><b>What "colonizable" means here is vanilla's own test</b>, not a heuristic — see
     * {@link #colonizableSystem} and {@link #colonizableCandidate} for the evidence behind each gate.
     *
     * <p><b>Two of vanilla's gates are reported rather than applied, on purpose.</b> A full survey
     * ({@code rules.csv}'s {@code $market.isSurveyed}, and {@code PlanetSurveyPanel} opening its
     * colonize screen only at {@code SurveyLevel.FULL}) and the absence of unexplored ruins
     * ({@code !$market.hasUnexploredRuins}, "The ruins on this planet must be explored before a
     * colonization effort can proceed") both block the button — but both are states the run itself
     * changes, the first with the {@code surveyset} verb and the second by salvaging. Filtering on
     * them would hide exactly the targets this verb exists to hand to the caller, so they ride along
     * as the {@code surveyLevel} and {@code unexploredRuins} fields instead. Everything else that can
     * still change under the caller — crew and machinery in the hold, a hostile fleet in sensor range,
     * a territorial claim on the system — is neither filtered nor reported: none of it is a property
     * of the planet.
     *
     * <p><b>{@code marketsInSystem} is the "is anyone already here" field</b>, and it is a count of
     * <em>economy</em> markets in the planet's location, not of markets in general. Every uncolonized
     * planet carries a planet-condition market of its own and none of those are in
     * {@code EconomyAPI.getMarketsCopy()} — vanilla's own passes iterate the economy and the
     * condition-market planets separately ({@code CoreLifecyclePluginImpl.addJunk}), and decivilizing a
     * colony calls {@code getEconomy().removeMarket} on the way out
     * ({@code DecivTracker.java:231}) — so a {@code 0} here means no faction holds anything in that
     * system, which is exactly the question "find me a system nobody is in" asks.
     *
     * <p>Args: {@code limit} (default {@value #COLONIZABLE_DEFAULT_LIMIT}, 1..{@value
     * #COLONIZABLE_MAX_LIMIT}), {@code maxLy} (0 or absent = no range filter) and {@code neutralOnly}
     * (default false; true keeps only {@code marketsInSystem == 0} rows).
     */
    static JSONObject colonizable(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        int limit = optionalInt(args, "limit", COLONIZABLE_DEFAULT_LIMIT);
        if (limit < 1 || limit > COLONIZABLE_MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + COLONIZABLE_MAX_LIMIT
                    + ", got " + limit);
        }
        double maxLy = optionalDouble(args, "maxLy", 0d);
        boolean neutralOnly = optionalBoolean(args, "neutralOnly", false);

        List<ColonizableCandidate> all = colonizableCandidates(sector, player);
        List<ColonizableCandidate> shown = selectColonizable(all, limit, maxLy, neutralOnly);

        LocationAPI here = player.getContainingLocation();
        JSONObject out = new JSONObject();
        out.put("fromLocationId", here == null ? "" : nullSafe(here.getId()));
        out.put("limit", limit);
        out.put("maxLy", round((float) maxLy));
        out.put("neutralOnly", neutralOnly);
        // Everything that passed the filters, before maxLy and limit trimmed the list: "none nearby"
        // and "none at all" are different answers and the caller has to be able to tell them apart.
        out.put("candidateCount", all.size());
        out.put("count", shown.size());

        JSONArray planets = new JSONArray();
        for (ColonizableCandidate candidate : shown) {
            JSONObject row = new JSONObject();
            row.put("planetId", candidate.planetId());
            row.put("name", candidate.name());
            row.put("type", candidate.type());
            row.put("gasGiant", candidate.gasGiant());
            row.put("systemId", candidate.systemId());
            row.put("systemName", candidate.systemName());
            row.put("x", round(candidate.x()));
            row.put("y", round(candidate.y()));
            row.put("marketsInSystem", candidate.marketsInSystem());
            row.put("distanceLy", round(candidate.distanceLy()));
            row.put("distanceSu", round(candidate.distanceSu()));
            row.put("hazard", round(candidate.hazard()));
            row.put("surveyLevel", candidate.surveyLevel());
            row.put("unexploredRuins", candidate.unexploredRuins());
            row.put("conditions", new JSONArray(candidate.conditions()));
            planets.put(row);
        }
        out.put("planets", planets);
        return out;
    }

    /**
     * Both filters, then nearest-first order, then the cap. No candidates is an empty list, not an
     * error. The cap runs last on purpose: {@code limit} has to apply to what actually passed
     * {@code maxLy} and {@code neutralOnly}, or a caller asking for three neutral planets would get
     * the three nearest planets filtered down to however many of them happened to be neutral.
     */
    static List<ColonizableCandidate> selectColonizable(List<ColonizableCandidate> candidates,
                                                        int limit, double maxLy, boolean neutralOnly) {
        List<ColonizableCandidate> kept = new ArrayList<>();
        for (ColonizableCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (maxLy > 0d && candidate.distanceLy() > maxLy) {
                continue;
            }
            if (neutralOnly && candidate.marketsInSystem() > 0) {
                continue;
            }
            kept.add(candidate);
        }
        kept.sort(COLONIZABLE_ORDER);
        if (limit > 0 && kept.size() > limit) {
            return new ArrayList<>(kept.subList(0, limit));
        }
        return kept;
    }

    /** Every uncolonized planet in the sector, unsorted and untrimmed. */
    static List<ColonizableCandidate> colonizableCandidates(SectorAPI sector, CampaignFleetAPI player) {
        LocationAPI here = player.getContainingLocation();
        // Counted once for the whole query rather than per planet: the economy walk is the same list
        // every candidate in a system would otherwise re-scan.
        Map<String, Integer> marketsByLocation = marketCountsByLocation(sector);
        List<ColonizableCandidate> found = new ArrayList<>();
        CoopLocations.forEach(sector, location -> {
            if (!colonizableSystem(location)) {
                return;
            }
            List<PlanetAPI> planets = location.getPlanets();
            if (planets == null) {
                return;
            }
            int markets = marketsByLocation.getOrDefault(nullSafe(location.getId()), 0);
            for (PlanetAPI planet : planets) {
                ColonizableCandidate candidate =
                        colonizableCandidate(planet, location, player, here, markets);
                if (candidate != null) {
                    found.add(candidate);
                }
            }
        });
        return found;
    }

    /**
     * Live economy markets per containing location id. Absent from the map means zero, which is the
     * answer {@code neutralOnly} keys on: nobody holds anything in that system.
     */
    static Map<String, Integer> marketCountsByLocation(SectorAPI sector) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        EconomyAPI economy = sector.getEconomy();
        List<MarketAPI> all = economy == null ? List.<MarketAPI>of() : economy.getMarketsCopy();
        if (all == null) {
            return counts;
        }
        for (MarketAPI market : all) {
            if (market == null) {
                continue;
            }
            String locationId = marketLocationId(market);
            if (!locationId.isEmpty()) {
                counts.merge(locationId, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Whether a colony could exist in this location at all.
     *
     * <p><b>Where the real gate lives.</b> {@code rules.csv} only decides whether the player reaches
     * the survey panel; the button that actually starts a colony is disabled by the core UI class
     * {@code com.fs.starfarer.campaign.ui.marketinfo.PlanetSurveyPanel}, and two of its four location
     * checks appear nowhere in {@code rules.csv} or the API sources. Each disqualifier below is one of
     * that panel's, with the tooltip it prints:
     *
     * <ul>
     * <li><b>{@link Tags#SYSTEM_CUT_OFF_FROM_HYPER}</b> — "This star system is cut off from hyperspace
     * and can not be colonized." Also the only one visible from script:
     * {@code CoreCampaignPluginImpl.java:207-209} derives the {@code $systemCutOffFromHyper} rules
     * variable from this tag, and {@code rules.csv}'s {@code surveySystemIsCutOffCanNotColonize} row
     * runs {@code SetEnabled surveyPerform false} off it. Set by {@code GateExplosionScript}
     * ({@code :80-84}) precisely so a colony cannot be planted mid-explosion, and removed again when a
     * jump point comes back.</li>
     * <li><b>{@link Tags#SYSTEM_ABYSSAL}</b> — "This planet is deep in abyssal hyperspace and can not
     * be colonized." Core-UI only.</li>
     * <li><b>{@link LocationAPI#isDeepSpace()}</b> — "This planet is in deep space and can not be
     * colonized." Core-UI only, and the one that rules out the hand-built deep-space pockets.</li>
     * <li><b>Hyperspace itself</b> — it carries no planets and no planet-condition markets. The guard
     * is here so the walk says so rather than relying on the planet loop coming up empty.</li>
     * </ul>
     *
     * <p><b>{@link Tags#TEMPORARY_LOCATION} is the one deliberate tightening past vanilla.</b> It is
     * not a colonize gate — it marks the throwaway systems the abyssal encounter generators mint and
     * discard ({@code AbyssalRogueStellarObjectEPEC.java:112}), and vanilla's own system scans skip
     * them for that reason ({@code NamelessRock.java:171}). Every vanilla carrier of it is already
     * abyssal deep space, so this line changes no answer today; it is here so a modded temporary
     * system that is neither cannot be offered as a target that will not exist next month.
     *
     * <p><b>{@link Tags#THEME_HIDDEN} is deliberately not a disqualifier.</b> It marks the locations
     * that stay off the map until found, and nothing in the colonize path reads it — the vanilla
     * systems carrying it are blocked by abyssal or deep space instead. Filtering on the theme tag
     * would be filtering on the wrong thing.
     */
    static boolean colonizableSystem(LocationAPI location) {
        if (location == null || location.isHyperspace() || location.isDeepSpace()) {
            return false;
        }
        return !location.hasTag(Tags.SYSTEM_CUT_OFF_FROM_HYPER)
                && !location.hasTag(Tags.SYSTEM_ABYSSAL)
                && !location.hasTag(Tags.TEMPORARY_LOCATION);
    }

    /**
     * One planet's candidacy, or null if it is not one.
     *
     * <p><b>{@code isPlanetConditionMarketOnly()} is the uncolonized test</b>, and it is the same flag
     * {@code CoopColonySync} gates its own capture on. Every planet carries a gen-time
     * planet-condition market; colonizing <em>promotes</em> that market and clears the flag, and an NPC
     * colony never had it set. It is also precisely what {@code rules.csv} requires before it offers
     * "Establish a colony" ({@code surveyAddOptionPerformedAlready}).
     *
     * <p><b>Stars are excluded</b>: {@code rules.csv}'s {@code surveyStar} row wins at score 1000 on
     * anything carrying the {@code star} tag and offers only "Leave", so a star never reaches the
     * survey panel however its market is flagged.
     *
     * <p><b>Gas giants are not excluded.</b> Vanilla colonizes them happily —
     * {@code PlanetAPI.isGasGiant()} is not referenced anywhere in the colonize path — so it is
     * reported as a field rather than used as a filter. A market-less planet is skipped for the same
     * reason a colonized one is: no market, no survey dialog, no colony.
     */
    private static ColonizableCandidate colonizableCandidate(PlanetAPI planet, LocationAPI location,
                                                             CampaignFleetAPI player, LocationAPI here,
                                                             int marketsInSystem) {
        try {
            if (planet == null || planet.isStar() || planet.getId() == null) {
                return null;
            }
            MarketAPI market = planet.getMarket();
            if (market == null || !market.isPlanetConditionMarketOnly()) {
                return null;
            }
            MarketAPI.SurveyLevel level = market.getSurveyLevel();
            Vector2f at = planet.getLocation();
            return new ColonizableCandidate(
                    nullSafe(planet.getId()),
                    nullSafe(planet.getName()),
                    nullSafe(planet.getTypeId()),
                    planet.isGasGiant(),
                    nullSafe(location.getId()),
                    nullSafe(location.getName()),
                    at == null ? 0f : at.x,
                    at == null ? 0f : at.y,
                    marketsInSystem,
                    distanceLy(planet, player),
                    location == here ? distanceSu(planet, player) : 0f,
                    market.getHazardValue(),
                    level == null ? "" : level.name(),
                    unexploredRuins(market),
                    conditionIds(market));
        } catch (RuntimeException | LinkageError ex) {
            // One unreadable planet must not cost the caller every other candidate.
            return null;
        }
    }

    /**
     * Hyperspace distance, the way vanilla measures "how far is that planet": both endpoints through
     * {@code getLocationInHyperspace()}, which for anything inside a star system is that system's
     * hyperspace position, so two planets in one system are equidistant and a planet in the fleet's own
     * system is at zero. Null-guarded because {@code Misc.getDistanceLY} is not.
     */
    private static float distanceLy(SectorEntityToken from, SectorEntityToken to) {
        Vector2f a = from.getLocationInHyperspace();
        Vector2f b = to.getLocationInHyperspace();
        if (a == null || b == null) {
            return 0f;
        }
        return Misc.getDistanceLY(a, b);
    }

    private static float distanceSu(SectorEntityToken from, SectorEntityToken to) {
        Vector2f a = from.getLocation();
        Vector2f b = to.getLocation();
        if (a == null || b == null) {
            return 0f;
        }
        return Misc.getDistance(a, b);
    }

    /**
     * Vanilla's {@code Misc.hasUnexploredRuins} without its null-memory NPE: ruins block the colonize
     * option ({@code rules.csv} requires {@code !$market.hasUnexploredRuins}), but salvaging them
     * unblocks it, so this rides along as a field rather than removing the planet.
     */
    private static boolean unexploredRuins(MarketAPI market) {
        if (!Misc.hasRuins(market)) {
            return false;
        }
        MemoryAPI memory = market.getMemoryWithoutUpdate();
        return memory == null || !memory.getBoolean(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG);
    }

    /** Sorted, so two clients' rows for the same planet compare as equal rather than as a reorder. */
    private static List<String> conditionIds(MarketAPI market) {
        Set<String> ids = new TreeSet<>();
        List<MarketConditionAPI> conditions = market.getConditions();
        if (conditions != null) {
            for (MarketConditionAPI condition : conditions) {
                if (condition != null && condition.getId() != null) {
                    ids.add(condition.getId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    // ---- landmarks: the unique objects a colony site is chosen relative to ------------------------

    /** Rows returned when the caller does not say. */
    static final int LANDMARKS_DEFAULT_LIMIT = 25;

    /**
     * One landmark kind and how to find it: by tag where vanilla gives one, by custom-entity spec id
     * where it does not. Exactly one of {@code tag} and {@code specId} is set.
     *
     * <p>The tag is preferred: {@code getEntitiesWithTag} is the indexed lookup
     * ({@code LocationAPI.java:181}), it is what vanilla's own code keys on, and a modded entity
     * carrying the tag is found too. The spec-id path exists for one entity that has no usable tag —
     * see {@link #LANDMARK_KINDS}.
     */
    record LandmarkKind(String key, String tag, String specId) {
        static LandmarkKind byTag(String key, String tag) {
            return new LandmarkKind(key, tag, "");
        }

        static LandmarkKind bySpec(String key, String specId) {
            return new LandmarkKind(key, "", specId);
        }
    }

    /**
     * The landmarks worth naming, in output order.
     *
     * <p><b>These are not all one-per-sector.</b> Hypershunts and cryosleepers are exactly two each
     * ({@code MiscellaneousThemeGenerator.java:651-652} hard-sets {@code numTaps = 2};
     * {@code DerelictThemeGenerator.java:131} sets {@code numCryo = 2}) and the gate hauler is exactly
     * one, but gates run to 15-20 plus a second pass ({@code settings.json} keys
     * {@code minNonCoreGatesInSector} / {@code maxNonCoreGatesInSector} / {@code minGatesToAddOnSecondPass})
     * and stable locations are commoner still. That is what {@code kinds} and the default limit are
     * for: the useful default is "the nearest notable things", not "every one of them".
     *
     * <p><b>The gate hauler is the odd one out.</b> Its four tags — {@code has_interaction_dialog},
     * {@code salvageable}, {@code neutrino_high}, {@code not_random_mission_target} — are all shared
     * with cryosleepers and ordinary salvage, so there is no tag that identifies it. It is found by
     * its spec id instead. ({@code $gateHauler} in its memory would work too —
     * {@code GateHaulerLocation.java:108-109} — but that is a walk of every entity either way, and the
     * spec id needs no memory allocation to read.)
     *
     * <p><b>Deliberately excluded.</b> {@link Tags#OBJECTIVE} (comm relays, nav buoys and sensor
     * arrays run to dozens and are already the {@code objective} verb's subject), {@link Tags#STATION},
     * {@link Tags#JUMP_POINT}, {@link Tags#WARNING_BEACON}, and the story one-offs — the Ziggurat
     * wreck, the Alpha Site, the red planet, the Nameless Rock, Galatia. Those are identified by
     * memory flags rather than tags, several of them do not exist at worldgen at all (the Ziggurat is
     * created only once its guardian is beaten), and none of them changes where you would put a
     * colony, which is what this verb is for. A list that includes them is a map dump.
     */
    static final List<LandmarkKind> LANDMARK_KINDS = List.of(
            LandmarkKind.byTag("hypershunt", Tags.CORONAL_TAP),
            LandmarkKind.byTag("cryosleeper", Tags.CRYOSLEEPER),
            LandmarkKind.byTag("gate", Tags.GATE),
            LandmarkKind.byTag("stable_location", Tags.STABLE_LOCATION),
            LandmarkKind.bySpec("gate_hauler", Entities.DERELICT_GATEHAULER));

    /**
     * Vanilla's "this thing has been repaired / its guardian is beaten" flag, checked with
     * {@code contains} rather than {@code getBoolean} because that is how vanilla checks it:
     * {@code PopulationAndInfrastructure.getNearestCoronalTap} and {@code Cryorevival}'s
     * {@code getNearestCryosleeper} both skip an entity whose memory does not contain it. Until it is
     * set, neither the hypershunt nor the cryosleeper counts for any colony at any distance.
     */
    static final String LANDMARK_USABLE_FLAG = "$usable";

    /**
     * One landmark. {@code extras} is the per-kind tail, flattened into the row.
     *
     * <p>{@code x}/{@code y} are the entity's current location-local coordinates, same meaning and
     * same purpose as {@code colonizable}'s: with {@code systemId} they are a {@code teleport}
     * argument, so flying to a landmark needs no orbit arithmetic.
     */
    record Landmark(String kind, String entityId, String name, String type, String systemId,
                    String systemName, boolean hyperspace, float x, float y,
                    float distanceLy, float distanceSu, Map<String, Object> extras) {
    }

    /** Nearest first, then kind, then id — total and stable, so two clients emit the same order. */
    static final Comparator<Landmark> LANDMARK_ORDER =
            Comparator.comparingDouble(Landmark::distanceLy)
                    .thenComparingDouble(Landmark::distanceSu)
                    .thenComparing(Landmark::kind)
                    .thenComparing(Landmark::entityId);

    /**
     * The sector's unique objects, nearest the local player fleet first, so a colony site can be
     * chosen relative to one instead of found by scrolling the map.
     *
     * <p><b>Pure query, any role</b> — local engine read, nothing written, same contract as
     * {@code colonizable} and {@code markets}. Deterministically ordered so {@code ss_diff} on it is a
     * real worldgen check.
     *
     * <p><b>No colony-relevance number is invented here, and the two that exist are read, not
     * copied.</b> The hypershunt and cryosleeper rows carry a {@code benefitRangeLy} taken live from
     * the engine's own fields ({@code ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS} and
     * {@code Cryorevival.MAX_BONUS_DIST_LY}, both {@code 10} in stock 0.98a, both non-final statics a
     * mod can move), so a modded install reports its own number instead of this file's memory of one.
     * Nothing else gets a range: everything else on the list has no colony effect to have a radius
     * for. <b>The cross-reference is still the caller's</b> — vanilla measures those radii from the
     * colony's hyperspace position, not from the player fleet, so the {@code distanceLy} in the same
     * row is not the distance the game will test.
     *
     * <p>Args: {@code kinds} (array or comma-separated string; default all), {@code limit} (default
     * {@value #LANDMARKS_DEFAULT_LIMIT}, 1..{@value #COLONIZABLE_MAX_LIMIT}) and {@code maxLy}
     * (0 or absent = no range filter).
     */
    static JSONObject landmarks(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        List<LandmarkKind> kinds = requestedLandmarkKinds(args);
        int limit = optionalInt(args, "limit", LANDMARKS_DEFAULT_LIMIT);
        if (limit < 1 || limit > COLONIZABLE_MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + COLONIZABLE_MAX_LIMIT
                    + ", got " + limit);
        }
        double maxLy = optionalDouble(args, "maxLy", 0d);

        List<Landmark> all = landmarkEntities(sector, player, kinds);
        List<Landmark> shown = selectLandmarks(all, limit, maxLy);

        LocationAPI here = player.getContainingLocation();
        JSONObject out = new JSONObject();
        out.put("fromLocationId", here == null ? "" : nullSafe(here.getId()));
        out.put("kinds", new JSONArray(landmarkKindKeys(kinds)));
        out.put("limit", limit);
        out.put("maxLy", round((float) maxLy));
        out.put("candidateCount", all.size());
        out.put("count", shown.size());

        JSONArray rows = new JSONArray();
        for (Landmark landmark : shown) {
            JSONObject row = new JSONObject();
            row.put("kind", landmark.kind());
            row.put("entityId", landmark.entityId());
            row.put("name", landmark.name());
            row.put("type", landmark.type());
            row.put("systemId", landmark.systemId());
            row.put("systemName", landmark.systemName());
            row.put("hyperspace", landmark.hyperspace());
            row.put("x", round(landmark.x()));
            row.put("y", round(landmark.y()));
            row.put("distanceLy", round(landmark.distanceLy()));
            row.put("distanceSu", round(landmark.distanceSu()));
            for (Map.Entry<String, Object> extra : landmark.extras().entrySet()) {
                row.put(extra.getKey(), extra.getValue());
            }
            rows.put(row);
        }
        out.put("landmarks", rows);
        return out;
    }

    /** Same shape as {@link #selectColonizable}: range filter, order, cap. */
    static List<Landmark> selectLandmarks(List<Landmark> landmarks, int limit, double maxLy) {
        List<Landmark> kept = new ArrayList<>();
        for (Landmark landmark : landmarks) {
            if (landmark == null) {
                continue;
            }
            if (maxLy > 0d && landmark.distanceLy() > maxLy) {
                continue;
            }
            kept.add(landmark);
        }
        kept.sort(LANDMARK_ORDER);
        if (limit > 0 && kept.size() > limit) {
            return new ArrayList<>(kept.subList(0, limit));
        }
        return kept;
    }

    /**
     * {@code kinds} as an array or a comma-separated string; absent means all of them. An unknown key
     * is a refusal naming the valid set, never a silently empty answer — "no landmarks of that kind"
     * and "you misspelled the kind" have to read differently.
     */
    static List<LandmarkKind> requestedLandmarkKinds(JSONObject args) throws JSONException {
        List<String> requested = new ArrayList<>();
        JSONArray array = args.optJSONArray("kinds");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                requested.add(array.getString(i).trim().toLowerCase(Locale.ROOT));
            }
        } else {
            String raw = optionalString(args, "kinds");
            for (String part : raw.split(",")) {
                if (!part.trim().isEmpty()) {
                    requested.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (requested.isEmpty()) {
            return LANDMARK_KINDS;
        }
        // Filtered out of the canonical list rather than built from the request, so the output order
        // is the registry's however the caller spelled the argument.
        List<LandmarkKind> kinds = new ArrayList<>();
        for (LandmarkKind kind : LANDMARK_KINDS) {
            if (requested.contains(kind.key())) {
                kinds.add(kind);
            }
        }
        for (String key : requested) {
            if (!landmarkKindKeys(LANDMARK_KINDS).contains(key)) {
                throw new IllegalArgumentException("unknown landmark kind " + key + "; known kinds: "
                        + String.join(", ", landmarkKindKeys(LANDMARK_KINDS)));
            }
        }
        return kinds;
    }

    static List<String> landmarkKindKeys(List<LandmarkKind> kinds) {
        List<String> keys = new ArrayList<>();
        for (LandmarkKind kind : kinds) {
            keys.add(kind.key());
        }
        return keys;
    }

    /**
     * Every landmark of the requested kinds, unsorted and untrimmed.
     *
     * <p>Hyperspace is walked like any other location — {@link CoopLocations#forEach} includes it, and
     * a landmark out there is a real one — so the row carries a {@code hyperspace} flag rather than
     * being special-cased out.
     *
     * <p>An entity carrying two landmark tags is emitted once, under the first kind in
     * {@link #LANDMARK_KINDS} that claims it. Two rows for one object would break the keyed diff.
     */
    static List<Landmark> landmarkEntities(SectorAPI sector, CampaignFleetAPI player,
                                           List<LandmarkKind> kinds) {
        LocationAPI here = player.getContainingLocation();
        GateState gates = readGateState(sector);
        List<Landmark> found = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        CoopLocations.forEach(sector, location -> {
            for (LandmarkKind kind : kinds) {
                for (SectorEntityToken entity : landmarkEntitiesIn(location, kind)) {
                    Landmark landmark = landmarkOf(kind, entity, location, player, here, gates, seen);
                    if (landmark != null) {
                        found.add(landmark);
                    }
                }
            }
        });
        return found;
    }

    /** Indexed tag lookup where there is a tag; a full entity walk only for the kind that needs one. */
    private static List<SectorEntityToken> landmarkEntitiesIn(LocationAPI location, LandmarkKind kind) {
        if (!kind.tag().isEmpty()) {
            List<SectorEntityToken> tagged = location.getEntitiesWithTag(kind.tag());
            return tagged == null ? List.of() : tagged;
        }
        List<SectorEntityToken> all = location.getAllEntities();
        if (all == null) {
            return List.of();
        }
        List<SectorEntityToken> matched = new ArrayList<>();
        for (SectorEntityToken entity : all) {
            if (entity != null && kind.specId().equals(entity.getCustomEntityType())) {
                matched.add(entity);
            }
        }
        return matched;
    }

    private static Landmark landmarkOf(LandmarkKind kind, SectorEntityToken entity,
                                       LocationAPI location, CampaignFleetAPI player,
                                       LocationAPI here, GateState gates, Set<String> seen) {
        try {
            if (entity == null || entity.getId() == null || !seen.add(entity.getId())) {
                return null;
            }
            Vector2f at = entity.getLocation();
            return new Landmark(
                    kind.key(),
                    nullSafe(entity.getId()),
                    nullSafe(entity.getName()),
                    nullSafe(entity.getCustomEntityType()),
                    nullSafe(location.getId()),
                    nullSafe(location.getName()),
                    location.isHyperspace(),
                    at == null ? 0f : at.x,
                    at == null ? 0f : at.y,
                    distanceLy(entity, player),
                    location == here ? distanceSu(entity, player) : 0f,
                    landmarkExtras(kind, entity, gates));
        } catch (RuntimeException | LinkageError ex) {
            // One unreadable entity must not cost the caller every other landmark.
            return null;
        }
    }

    /**
     * The sector-wide half of gate usability; read once per query, not once per gate.
     *
     * <p>Read straight off sector memory rather than through {@code GateEntityPlugin.areGatesActive()}
     * and {@code canUseGates()} on purpose. Those two OR in "the player is carrying a Janus Device"
     * ({@code GateEntityPlugin.java:76-91}), which is one client's cargo, not campaign state — it would
     * make the same sector answer differently on host and guest and turn a diff of this verb into a
     * report about someone's hold. The memory flags are the persistent, replicated half.
     */
    record GateState(boolean gatesActive, boolean playerCanUseGates) {
    }

    static GateState readGateState(SectorAPI sector) {
        try {
            MemoryAPI memory = sector.getMemoryWithoutUpdate();
            if (memory == null) {
                return new GateState(false, false);
            }
            return new GateState(memory.getBoolean(GateEntityPlugin.GATES_ACTIVE),
                    memory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES));
        } catch (RuntimeException | LinkageError ex) {
            return new GateState(false, false);
        }
    }

    /**
     * The per-kind tail. Every value here is a read vanilla already does somewhere and none of them
     * mutate: a query that changed the world would be useless for proving two worlds agree.
     *
     * <p><b>No extras for {@code stable_location}, and "occupied" is not a missing field.</b> Vanilla
     * does not mark a stable location as used — it destroys it. {@code Objectives.build}
     * ({@code Objectives.java:370-393}) creates the relay/array/buoy as a new entity, copies the orbit
     * and position across, then calls {@code loc.removeEntity(entity)} on the stable location and
     * stores the dead token under {@code $originalStableLocation} on the objective. A
     * {@code stable_location} that still exists is therefore free by construction, and destroying the
     * objective spawns a fresh one back ({@code Objectives.java:240-251}).
     *
     * <p><b>No extras for {@code gate_hauler}</b>: it is one entity in one hidden system and its
     * state lives in {@code GateHaulerIntel}, not on the token.
     */
    private static Map<String, Object> landmarkExtras(LandmarkKind kind, SectorEntityToken entity,
                                                      GateState gates) {
        Map<String, Object> extras = new LinkedHashMap<>();
        switch (kind.key()) {
            case "gate" -> {
                // active + scanned are vanilla's own two reads. The sector-wide pair is the same one
                // CoopCampaignReplicator's gate poll encodes, and GateEntityPlugin.advance only lets
                // the player through a gate when scanned && canUseGates() hold together.
                //
                // active is the weakest of the four: it reads the plugin's madeActive field, which is
                // only flipped inside advance() (GateEntityPlugin.java:274-285), so a gate this client
                // has never had loaded reads false even when scanned and usable. Trust
                // scanned && gatesActive over it; it is reported because it is what the sprite shows.
                extras.put("active", GateEntityPlugin.isActive(entity));
                MemoryAPI memory = entity.getMemoryWithoutUpdate();
                // isScanned dereferences the memory unguarded; a gate without one is simply unscanned.
                extras.put("scanned", memory != null && GateEntityPlugin.isScanned(entity));
                extras.put("gatesActive", gates.gatesActive());
                extras.put("playerCanUseGates", gates.playerCanUseGates());
            }
            case "hypershunt" -> {
                extras.put("usable", isLandmarkUsable(entity));
                putIfPresent(extras, "benefitRangeLy", coronalTapRangeLy());
            }
            case "cryosleeper" -> {
                extras.put("usable", isLandmarkUsable(entity));
                putIfPresent(extras, "benefitRangeLy", cryosleeperRangeLy());
                putIfPresent(extras, "minBenefitMult", cryosleeperMinBonusMult());
            }
            default -> {
                // stable_location and gate_hauler: see the javadoc.
            }
        }
        return extras;
    }

    private static boolean isLandmarkUsable(SectorEntityToken entity) {
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        return memory != null && memory.contains(LANDMARK_USABLE_FLAG);
    }

    private static void putIfPresent(Map<String, Object> extras, String key, Double value) {
        if (value != null) {
            extras.put(key, value);
        }
    }

    /**
     * The radius inside which a colony can use a hypershunt tap, read from the engine rather than
     * copied: {@code ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS}, a non-final {@code public static int}
     * that a mod can reassign. {@code BaseInstallableItemEffect.java:152-159} is the gate —
     * {@code dist > CORONAL_TAP_LIGHT_YEARS} marks the requirement unmet — and it is <b>binary</b>,
     * not graded: inside the radius the tap works, outside it does nothing.
     *
     * <p>Two things this number does not say, and the caller has to know both. The comparison is run
     * against the <em>colony's</em> hyperspace position, not the player fleet's, so the
     * {@code distanceLy} in the same row is not the distance being tested. And vanilla measures to
     * hypershunts it finds through {@code HypershuntIntel}, so an undiscovered one counts for nothing
     * however close it is.
     */
    private static Double coronalTapRangeLy() {
        try {
            return (double) ItemEffectsRepo.CORONAL_TAP_LIGHT_YEARS;
        } catch (RuntimeException | LinkageError ex) {
            // Better absent than guessed: a stale hardcoded 10 would be worse than no field at all.
            return null;
        }
    }

    /**
     * {@code Cryorevival.MAX_BONUS_DIST_LY}. Unlike the hypershunt this one is <b>graded</b>:
     * {@code Cryorevival.getDistancePopulationMult} ({@code Cryorevival.java:233-246}) returns
     * {@code MIN_BONUS_MULT + (1 - MIN_BONUS_MULT) * (1 - dist / MAX_BONUS_DIST_LY)}, so the
     * multiplier runs from 1.0 on top of the cryosleeper down to {@link #cryosleeperMinBonusMult()} at
     * the edge, and 0 — unbuildable — past it. Same two caveats as the hypershunt: measured from the
     * colony, and only against cryosleepers known through {@code CryosleeperIntel}.
     */
    private static Double cryosleeperRangeLy() {
        try {
            return round(Cryorevival.MAX_BONUS_DIST_LY);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static Double cryosleeperMinBonusMult() {
        try {
            return round(Cryorevival.MIN_BONUS_MULT);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    // ---- Setup actions --------------------------------------------------------------------------

    /** Clearance past an entity's own radius, so an entity-targeted teleport never lands inside it. */
    static final float TELEPORT_ENTITY_CLEARANCE = 200f;

    /** The x/y-mode arguments {@code entityId} replaces; naming them is how the refusal reads. */
    static final List<String> TELEPORT_COORDINATE_ARGS = List.of("x", "y", "locationId");

    /** Where a teleport is going, after either argument mode has been resolved. */
    record TeleportTarget(LocationAPI location, float x, float y, String entityId, String entityName) {
    }

    /**
     * Put the player fleet somewhere, either at raw coordinates or beside a named entity.
     *
     * <p><b>Two argument modes, mutually exclusive.</b> {@code {locationId, x, y}} is the original:
     * exact coordinates in a named location. {@code {entityId}} resolves an entity anywhere in the
     * sector and places the fleet {@code radius + }{@value #TELEPORT_ENTITY_CLEARANCE} units along +x
     * from it, which is the mode worth using for a planet — a planet's coordinates are a function of
     * its orbit and the clock, so deriving them by hand from the orbit definition is both work and a
     * source of wrong answers. Passing both is refused rather than silently preferring one.
     *
     * <p><b>Crossing locations goes through the engine's jump transition, not a raw re-parent.</b>
     * This is the fix for a live defect: a fleet moved between systems by
     * {@code removeEntity}/{@code addEntity}/{@code setLocation} rendered in the destination and then
     * could not fly at all. The reason is in {@code CampaignEngine.advance}, which hands the input
     * object only to {@code getCurrentLocation().advance(f, input)} and advances every other location
     * with {@code null} — so a fleet sitting in a location that is not the engine's <em>current</em>
     * one receives no player input. {@code setCurrentLocation} is one of the things
     * {@code doHyperspaceTransition}'s script does at its {@code SWITCHING_LOCATIONS} step, alongside
     * the re-parent, {@code setOrbit(null)}, the move-destination override and the closing
     * {@code reportFleetJumped}. Rather than reproduce that list and hope it stays complete, the
     * cross-location case calls the engine's own path, exactly as {@code FractureJumpAbility} does:
     * a throwaway destination token at the target coordinates, no {@code jumpLocation} (so the fleet
     * warps out where it stands instead of flying to a jump point first, which is also what removes
     * the abort case).
     *
     * <p><b>A jump is not instantaneous and does not run while the clock is stopped.</b> The
     * transition is an ordinary {@code EveryFrameScript}, and {@code CampaignEngine.advance} skips
     * scripts that do not opt into running while paused. So the response's {@code x}/{@code y} are the
     * <em>destination</em>, the fleet arrives a couple of seconds of game time later, and while the
     * session is paused it does not arrive at all until time runs. {@code transition} says which path
     * ran and {@code pending} says whether the fleet is still on its way; a second teleport issued
     * mid-flight is refused rather than silently swallowed by the engine's own re-entrancy guard.
     *
     * <p>A same-location teleport keeps the original direct placement, which is verified working and
     * has none of the above to worry about.
     */
    static JSONObject teleport(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        if (player.isInHyperspaceTransition()) {
            throw new IllegalStateException("player fleet is already in a jump transition; let the"
                    + " clock run until it lands before teleporting again");
        }
        TeleportTarget target = teleportTarget(sector, args);

        LocationAPI current = player.getContainingLocation();
        boolean jump = current != null && current != target.location();
        if (jump) {
            SectorEntityToken destination = target.location().createToken(target.x(), target.y());
            sector.doHyperspaceTransition(player, null,
                    new JumpPointAPI.JumpDestination(destination, null));
        } else {
            if (player.getContainingLocation() != target.location()) {
                target.location().addEntity(player);
            }
            player.setLocation(target.x(), target.y());
        }

        JSONObject out = new JSONObject();
        out.put("locationId", nullSafe(target.location().getId()));
        out.put("x", round(target.x()));
        out.put("y", round(target.y()));
        out.put("movedFrom", current == null ? "" : nullSafe(current.getId()));
        out.put("entityId", target.entityId());
        out.put("entityName", target.entityName());
        out.put("transition", jump ? "jump" : "local");
        out.put("pending", jump);
        return out;
    }

    /**
     * Which of the two argument modes was asked for, resolved down to one location and one point.
     * {@code entityId} wins the mode choice by being present at all, and then forbids the other three
     * rather than quietly ignoring them: a caller who passed both meant one of them and guessing which
     * is how a fleet ends up somewhere nobody asked for.
     */
    static TeleportTarget teleportTarget(SectorAPI sector, JSONObject args) {
        if (!args.has("entityId")) {
            String locationId = requiredString(args, "locationId");
            float x = (float) requiredDouble(args, "x");
            float y = (float) requiredDouble(args, "y");
            LocationAPI location = CoopLocations.byId(sector, locationId);
            if (location == null) {
                throw new IllegalArgumentException("no location with id " + locationId);
            }
            return new TeleportTarget(location, x, y, "", "");
        }

        for (String conflicting : TELEPORT_COORDINATE_ARGS) {
            if (args.has(conflicting)) {
                throw new IllegalArgumentException("teleport takes either entityId or "
                        + String.join("/", TELEPORT_COORDINATE_ARGS) + ", not both; got entityId and "
                        + conflicting);
            }
        }
        String entityId = requiredString(args, "entityId");
        SectorEntityToken entity = findEntity(sector, entityId);
        if (entity == null) {
            throw new IllegalArgumentException("no entity with id " + entityId
                    + " anywhere in this sector");
        }
        LocationAPI location = entity.getContainingLocation();
        if (location == null) {
            throw new IllegalArgumentException("entity " + entityId + " is in no location");
        }
        Vector2f at = entity.getLocation();
        if (at == null) {
            throw new IllegalArgumentException("entity " + entityId + " has no position");
        }
        // Straight +x rather than a random bearing: two runs of the same request have to put the
        // fleet in the same place, and Misc.getPointAtRadius (what vanilla's own jump-in uses) draws
        // its angle from Math.random().
        float clearance = entity.getRadius() + TELEPORT_ENTITY_CLEARANCE;
        return new TeleportTarget(location, at.x + clearance, at.y, entityId,
                nullSafe(entity.getName()));
    }

    /**
     * An entity anywhere in the sector, or null.
     *
     * <p>{@code SectorAPI.getEntityById} first — the engine answers it out of an id map and only falls
     * back to a walk on a miss. The explicit walk behind it is not redundant: the engine's map is
     * rebuilt lazily and its own fallback is the same walk, so doing it here costs nothing on the hit
     * path and keeps the verb answering on any {@code SectorAPI} whose id map is not populated.
     */
    static SectorEntityToken findEntity(SectorAPI sector, String entityId) {
        SectorEntityToken direct = sector.getEntityById(entityId);
        if (direct != null) {
            return direct;
        }
        for (LocationAPI location : CoopLocations.all(sector)) {
            if (location == null) {
                continue;
            }
            SectorEntityToken entity = location.getEntityById(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
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

    /** Absent means the fallback; present-but-unreadable is a refusal, never a silent fallback. */
    private static int optionalInt(JSONObject args, String key, int fallback) {
        if (!args.has(key)) {
            return fallback;
        }
        double value = args.optDouble(key, Double.NaN);
        if (Double.isNaN(value) || value != Math.rint(value)) {
            throw new IllegalArgumentException(key + " must be a whole number, got "
                    + args.optString(key, ""));
        }
        return (int) value;
    }

    private static double optionalDouble(JSONObject args, String key, double fallback) {
        if (!args.has(key)) {
            return fallback;
        }
        double value = args.optDouble(key, Double.NaN);
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException(key + " must be numeric, got "
                    + args.optString(key, ""));
        }
        return value;
    }

    /** Absent means the fallback; a word form is accepted the same way {@code pause} accepts one. */
    private static boolean optionalBoolean(JSONObject args, String key, boolean fallback) {
        if (!args.has(key)) {
            return fallback;
        }
        Object raw = args.opt(key);
        if (raw instanceof Boolean flag) {
            return flag;
        }
        return parseOnOff(String.valueOf(raw), key);
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
