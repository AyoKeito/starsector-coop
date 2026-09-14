package coop.debug;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainPlugin;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.impl.campaign.GateEntityPlugin;
import com.fs.starfarer.api.impl.campaign.econ.impl.Cryorevival;
import com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.EventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionIntel;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager.PunExData;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager.PunExReason;
import com.fs.starfarer.api.util.Misc;
import coop.campaign.CoopBarPoolCapture;
import coop.campaign.CoopCampaignReplicator;
import coop.campaign.CoopCreditTransfer;
import coop.campaign.CoopMarketSync;
import coop.campaign.CoopMissionBoardSync;
import coop.campaign.CoopSkeletonMutationWatcher;
import coop.combat.CoopPreBattleAutosave;
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
import coop.net.CoopNetFault;
import coop.net.CoopNetPump;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import coop.util.CoopLog;
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
     * Ships one {@code addship} call may add. Twenty is well past any setup a smoke run needs and
     * short of the "paste the wrong number and rebuild the sector's economy" range.
     */
    static final int MAX_ADDED_SHIPS = 20;

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
        map.put("cargo", CoopAgentCommands::cargo);
        map.put("market", CoopAgentCommands::market);
        map.put("markets", CoopAgentCommands::markets);
        map.put("barpool", CoopAgentCommands::barpool);
        map.put("survey", CoopAgentCommands::survey);
        map.put("visibility", CoopAgentCommands::visibility);
        map.put("ownfleet", CoopAgentCommands::ownfleet);
        map.put("colonizable", CoopAgentCommands::colonizable);
        map.put("landmarks", CoopAgentCommands::landmarks);
        map.put("entities", CoopAgentCommands::entities);
        map.put("intel", CoopAgentCommands::intel);
        map.put("feed", CoopAgentCommands::feed);
        map.put("screen", CoopAgentCommands::screen);
        map.put("teleport", CoopAgentCommands::teleport);
        map.put("pause", CoopAgentCommands::pause);
        map.put("ability", CoopAgentCommands::ability);
        map.put("setcr", CoopAgentCommands::setcr);
        map.put("give", CoopAgentCommands::give);
        map.put("addship", CoopAgentCommands::addship);
        map.put("objective", CoopAgentCommands::objective);
        map.put("surveyset", CoopAgentCommands::surveyset);
        map.put("expedition", CoopAgentCommands::expedition);
        map.put("rep", CoopAgentCommands::rep);
        map.put("netfault", CoopAgentCommands::netfault);
        map.put("save", CoopAgentCommands::save);
        map.put("mark", CoopAgentCommands::mark);
        map.put("memory", CoopAgentCommands::memory);
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
        // Phase 32 addition A: the guest's hidden-base market-id translations, so a smoke can see
        // from outside the game whether a base has been paired. Always empty on the host, where the
        // local ids are the wire ids.
        out.put("baseMarketIds", baseMarketIds(pump == null ? null : pump.campaignReplicatorForBridge()));
        // Phase 32 addition B: the credit-transfer ledgers, so a money smoke can be verified from
        // outside the game. Pass a "ledgerId" argument to ask about one specific grant.
        out.put("credits", creditsBlock(optionalString(args, "ledgerId")));
        // 0.1.1: always present, so a netfault someone forgot to clear can never be read as a bug.
        out.put("netfault", netFaultBlock(netFaultStatusOf(pump)));
        // 0.1.1: the reliable-delivery layer's three counters, which are the only evidence outside a
        // log that a replay after a link drop landed exactly once.
        out.put("reliable", reliableBlock(pump));
        return out;
    }

    /**
     * What the reliable-delivery layer owes and what it has already seen.
     *
     * <p>{@code unacked} is the sender's side — actions written but not yet acknowledged, the queue
     * the replay walks. {@code duplicatesDropped} and {@code appliedSeqs} are the receiver's:
     * a replayed message this side had already applied, and how many applied sequence numbers it
     * still remembers across every sender. Together they are the whole check a {@code netfault} run
     * makes — the queue drains, the duplicate counter moves, and nothing is applied twice — and none
     * of it is visible from the outside otherwise.
     *
     * <p>Every field is zero with no pump rather than absent: a smoke script reads the same shape
     * before a session as during one.
     */
    static JSONObject reliableBlock(CoopNetPump pump) throws JSONException {
        JSONObject out = new JSONObject();
        CoopNetService service = pump == null ? null : pump.netServiceForBridge();
        out.put("unacked", service == null ? 0 : service.unackedReliableCount());
        out.put("duplicatesDropped", pump == null ? 0L : pump.reliableDuplicatesDropped());
        out.put("appliedSeqs", pump == null ? 0 : pump.appliedReliableSeqTotal());
        return out;
    }

    /** {@link CoopNetService.NetFaultStatus#INACTIVE} when there is no transport to ask. */
    static CoopNetService.NetFaultStatus netFaultStatusOf(CoopNetPump pump) {
        CoopNetService service = pump == null ? null : pump.netServiceForBridge();
        return service == null ? CoopNetService.NetFaultStatus.INACTIVE : service.netFaultStatus();
    }

    /**
     * The state of the {@code netfault} verb's deliberate outage on this instance. Reported on every
     * {@code status} on purpose: a fault is invisible from the outside — it looks exactly like a dead
     * link — so the one place a smoke run always looks has to say whether one is running.
     */
    static JSONObject netFaultBlock(CoopNetService.NetFaultStatus status) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("active", status.active());
        out.put("mode", status.mode());
        out.put("remainingSeconds", status.remainingSeconds());
        out.put("discardedBytes", status.discardedBytes());
        out.put("droppedDatagrams", status.droppedDatagrams());
        // 0.1.1 arming. Exclusive with "active" by construction: armed means the fault exists and has
        // dropped nothing yet, so a script that polls "active" to decide whether the link should be
        // silent is never told yes by a fault that has not started.
        out.put("armed", status.armed());
        out.put("startsInSeconds", status.startsInSeconds());
        return out;
    }

    /**
     * What the credit transfer has moved this session: how many grants this engine credited and how
     * many it minted and still considers in flight. Read off the installed transfer's static handle,
     * so the bridge needs no path through the pump.
     *
     * <p>With {@code ledgerId} in the request the block also answers {@code applied} for that one
     * grant. That is the check a money smoke actually wants: a wallet total moves for a dozen
     * reasons, but "the id the sender minted is in the receiver's applied ledger" is the transfer
     * itself, and it is the one fact neither log line proves on the receiving side after a restart.
     *
     * @param ledgerId a grant id to ask about, or {@code ""} for the counts alone
     */
    static JSONObject creditsBlock(String ledgerId) throws JSONException {
        JSONObject out = new JSONObject();
        CoopCreditTransfer transfer = CoopCreditTransfer.active();
        out.put("installed", transfer != null);
        if (transfer == null) {
            return out;
        }
        out.put("appliedCount", transfer.appliedCount());
        out.put("sentCount", transfer.sentCount());
        out.put("canSend", transfer.canSend());
        if (!ledgerId.isEmpty()) {
            out.put("ledgerId", ledgerId);
            out.put("applied", transfer.hasApplied(ledgerId));
            out.put("inFlight", transfer.hasSent(ledgerId));
        }
        return out;
    }

    /** {@code hostMarketId -> localMarketId} for every mirrored hidden base; empty on the host. */
    static JSONObject baseMarketIds(CoopCampaignReplicator replicator) throws JSONException {
        JSONObject out = new JSONObject();
        if (replicator == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : replicator.marketIds().mappings().entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
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

    /**
     * The local player fleet's logistics state: what it is carrying, what it can carry, and whether
     * it is over any of the three limits.
     *
     * <p>Exists because {@code fleets} reports ships and CR and nothing else, so "top up supplies
     * before the drill" had to be done blind with {@code give} (found in the Phase 20 QA matrix,
     * 2026-09-02). Every number is the engine's own: {@code CargoAPI} has three independent
     * capacities — cargo space, fuel and personnel — and vanilla's own {@code $cargoRoom} /
     * {@code $fuelRoom} / {@code $crewRoom} memory keys are exactly the {@code capacity - used}
     * subtractions reported here as {@code free}.
     *
     * <p>There is no engine "overloaded" flag to read: over-capacity is a per-dimension comparison the
     * UI paints red and the burn/upkeep maths reads off the same subtraction. {@code overloaded} is
     * therefore true when any of the three is over, and {@code over} names which — a bare boolean
     * would leave the caller re-deriving the interesting half.
     */
    static JSONObject cargo(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        CargoAPI cargo = player.getCargo();
        if (cargo == null) {
            throw new IllegalStateException("player fleet has no cargo");
        }
        // Derived and cached by the engine; recomputed here so a dump taken right after a `give`
        // reports the space that stack actually takes rather than the value from before it landed.
        try {
            cargo.updateSpaceUsed();
        } catch (RuntimeException | LinkageError ignored) {
            // Best-effort refresh only. A stale read is worth reporting; a failed verb is not.
        }

        JSONObject out = new JSONObject();
        // Named engineId like every other per-instance id in these dumps, so the MCP diff drops it
        // by default: the two players own different fleets and that id can never match.
        out.put("engineId", nullSafe(player.getId()));
        out.put("supplies", round(cargo.getSupplies()));
        out.put("fuel", round(cargo.getFuel()));
        out.put("crew", cargo.getCrew());
        out.put("marines", cargo.getMarines());
        out.put("credits", cargo.getCredits() == null ? 0d : round(cargo.getCredits().get()));

        JSONArray over = new JSONArray();
        out.put("cargoSpace", capacityBlock(cargo.getMaxCapacity(), cargo.getSpaceUsed(), "cargoSpace", over));
        out.put("fuelSpace", capacityBlock(cargo.getMaxFuel(), cargo.getFuel(), "fuelSpace", over));
        out.put("personnel",
                capacityBlock(cargo.getMaxPersonnel(), cargo.getTotalPersonnel(), "personnel", over));
        out.put("overloaded", over.length() > 0);
        out.put("over", over);
        return out;
    }

    /** One capacity dimension, plus a note in {@code over} when it is past its limit. */
    private static JSONObject capacityBlock(float capacity, float used, String name, JSONArray over)
            throws JSONException {
        JSONObject block = new JSONObject();
        block.put("capacity", round(capacity));
        block.put("used", round(used));
        block.put("free", round(capacity - used));
        if (used > capacity) {
            over.put(name);
        }
        return block;
    }

    static JSONObject market(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        String requestedId = requiredString(args, "marketId");
        CoopCampaignReplicator replicator = requireReplicator(context);

        // Phase 32 addition A: the verb takes either id for a mirrored hidden base. A smoke reads a
        // market id out of the host's dump and asks the guest about it, and translating here is what
        // lets the same id name the same base on both sockets. Identity for every other market.
        String marketId = replicator.marketIds().toLocal(requestedId);
        MarketAPI market = sector.getEconomy() == null ? null : sector.getEconomy().getMarket(marketId);
        if (market == null) {
            throw new IllegalArgumentException("no market with id " + requestedId);
        }

        boolean host = roleOf(context.pump()) == CoopConnectionRole.HOST;
        // Phase 32: one market has up to four shared submarkets, so the verb names one. Defaulting to
        // the open market keeps every existing runbook line working unchanged.
        String submarketId = optionalString(args, "submarketId");
        if (submarketId == null || submarketId.isBlank()) {
            submarketId = Submarkets.SUBMARKET_OPEN;
        }
        JSONObject out = new JSONObject();
        out.put("marketId", marketId);
        out.put("requestedMarketId", requestedId);
        out.put("submarketId", submarketId);
        out.put("role", roleOf(context.pump()).name());

        if (!host && !replicator.submarketStockedForBridge(market, submarketId)) {
            // A guest that has never docked here has no stock at all. Reporting that as an empty
            // shop would read as "host and guest disagree"; it is "there is nothing to compare yet".
            out.put("stocked", false);
            return out;
        }

        // HOST: dock-equivalent by design. This runs the same updateCargoPrePlayerInteraction() a
        // real dock (and the market snapshot broadcast) runs before capturing, because a market the
        // host has never docked at has never had stock generated. The generation is the point, not a
        // side effect — see CoopCampaignReplicator#captureMarketStockForBridge.
        List<CoopMarketSync.StockItem> items =
                replicator.captureMarketStockForBridge(market, submarketId, host);
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
     * <p>Enumeration only — deliberately no {@code ensureSubmarketStocked}. The {@code market} verb
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

    /** The literal every verb that takes a location accepts for the hyperspace map. */
    static final String HYPERSPACE_KEYWORD = "hyperspace";

    /**
     * {@link #resolveSurveyScope} plus the {@code "hyperspace"} keyword, which is the one location a
     * caller cannot name without knowing the engine's generated id for it. Used by {@code entities}
     * and by {@code teleport}'s coordinate mode, so an id, a system name and the keyword all work in
     * either.
     */
    static LocationAPI resolveLocation(SectorAPI sector, String locationId) {
        if (sector == null || locationId == null || locationId.trim().isEmpty()) {
            return null;
        }
        String wanted = locationId.trim();
        if (HYPERSPACE_KEYWORD.equalsIgnoreCase(wanted)) {
            return sector.getHyperspace();
        }
        return resolveSurveyScope(sector, wanted);
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

    // ---- ownfleet: the local player's own fleet, in the detail the S5-B CR mystery needs ----------

    /**
     * The local player's own fleet, read live, plus the {@link CoopOwnFleetProbe} drop ring.
     *
     * <p>Exists because the S5-B smoke could see <em>that</em> the guest's own ship lost CR and hull
     * and could not see <em>why</em>: the fleet screen shows a number, not the engine's CR-event
     * trail. Every {@code RepairTrackerAPI} getter that bears on that question is here, including
     * {@code getRecentEvents()} and {@code getNoSupplyCRLossEvent()} — vanilla's own labels for why a
     * ship is losing readiness.
     *
     * <p>The member table is a live read and needs no diagnostics. The {@code drops} array is the
     * probe's ring buffer, which only fills while {@code CoopDebug.diagnosticsEnabled()} — with
     * diagnostics off it is simply empty, which {@code diagnostics:false} in the response explains.
     */
    static JSONObject ownfleet(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        JSONObject out = new JSONObject();
        out.put("role", roleOf(context.pump()).name());
        out.put("diagnostics", coop.util.CoopDebug.diagnosticsEnabled());
        out.put("battleActive", CoopOwnFleetProbe.battleActive());
        out.put("modWrites", CoopOwnFleetProbe.INSTANCE.modWriteCount());

        CampaignClockAPI clock = sector.getClock();
        out.put("date", clock == null || clock.getDateString() == null ? "" : clock.getDateString());
        out.put("hour", clock == null ? 0 : clock.getHour());
        out.put("timestamp", clock == null ? 0L : clock.getTimestamp());

        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet == null) {
            out.put("members", new JSONArray());
            out.put("drops", dropsJson());
            return out;
        }
        LocationAPI location = fleet.getContainingLocation();
        out.put("locationId", location == null || location.getId() == null ? "" : location.getId());
        out.put("inHyperspace", fleet.isInHyperspace());
        out.put("paused", sector.isPaused());
        CargoAPI cargo = fleet.getCargo();
        out.put("supplies", cargo == null ? 0d : round(cargo.getSupplies()));
        out.put("fuel", cargo == null ? 0d : round(cargo.getFuel()));
        out.put("crew", cargo == null ? 0 : cargo.getTotalCrew());
        out.put("maxCapacity", cargo == null ? 0d : round(cargo.getMaxCapacity()));

        JSONArray members = new JSONArray();
        float minCrew = 0f;
        FleetDataAPI data = fleet.getFleetData();
        List<FleetMemberAPI> list = data == null ? List.of() : data.getMembersListCopy();
        for (FleetMemberAPI member : list) {
            if (member == null) {
                continue;
            }
            minCrew += member.getMinCrew();
            members.put(ownFleetMember(member));
        }
        out.put("fleetMinCrew", round(minCrew));
        out.put("memberCount", members.length());
        out.put("members", members);
        out.put("drops", dropsJson());
        return out;
    }

    private static JSONObject ownFleetMember(FleetMemberAPI member) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("id", CoopOwnFleetProbe.safeId(member));
        entry.put("hullId", CoopOwnFleetProbe.safeHullId(member));
        entry.put("variantId", CoopOwnFleetProbe.safeVariantId(member));
        entry.put("name", CoopOwnFleetProbe.safeShipName(member));
        entry.put("minCrew", round(member.getMinCrew()));
        entry.put("neededCrew", round(member.getNeededCrew()));
        entry.put("maxCrew", round(member.getMaxCrew()));
        entry.put("crewFraction", round(member.getCrewFraction()));
        entry.put("mothballed", member.isMothballed());

        FleetMemberStatusAPI status = member.getStatus();
        entry.put("hullFraction", status == null ? 0d : round(status.getHullFraction()));
        entry.put("hullDamageTaken", status == null ? 0d : round(status.getHullDamageTaken()));
        entry.put("needsRepairs", status != null && status.needsRepairs());

        RepairTrackerAPI repair = member.getRepairTracker();
        if (repair == null) {
            return entry;
        }
        entry.put("cr", round(repair.getCR()));
        entry.put("maxCr", round(repair.getMaxCR()));
        entry.put("baseCr", round(repair.getBaseCR()));
        entry.put("crashMothballing", repair.isCrashMothballed());
        entry.put("suspendRepairs", repair.isSuspendRepairs());
        entry.put("recoveryRate", round(repair.getRecoveryRate()));
        entry.put("decreaseRate", round(repair.getDecreaseRate()));
        entry.put("repairRatePerDay", round(repair.getRepairRatePerDay()));
        entry.put("remainingRepairTime", round(repair.getRemainingRepairTime()));
        entry.put("repairednessFraction", round(repair.computeRepairednessFraction()));
        entry.put("crPriorToMothballing", round(repair.getCRPriorToMothballing()));
        entry.put("noSupplyCrLoss", crEventJson(repair.getNoSupplyCRLossEvent()));
        JSONArray events = new JSONArray();
        List<RepairTrackerAPI.CREvent> recent = repair.getRecentEvents();
        if (recent != null) {
            for (RepairTrackerAPI.CREvent event : recent) {
                if (event != null) {
                    events.put(crEventJson(event));
                }
            }
        }
        entry.put("recentEvents", events);
        return entry;
    }

    private static JSONObject crEventJson(RepairTrackerAPI.CREvent event) throws JSONException {
        JSONObject out = new JSONObject();
        if (event == null) {
            return out;
        }
        out.put("id", event.id == null ? "" : event.id);
        out.put("crAmount", round(event.getCrAmount()));
        out.put("text", event.getText() == null ? "" : event.getText());
        out.put("elapsed", round(event.getElapsed()));
        return out;
    }

    private static JSONArray dropsJson() {
        JSONArray out = new JSONArray();
        for (String line : CoopOwnFleetProbe.INSTANCE.recentDrops()) {
            out.put(line);
        }
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
    static final List<String> TELEPORT_COORDINATE_ARGS = List.of("x", "y", "locationId", "system");

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
            // {system, x, y} is the same mode under the name the `entities` verb answers to, so a
            // caller who just listed a system's contents can teleport into it without translating
            // the argument name. Both spellings resolve the same widened way (see resolveLocation),
            // which is what lets a system *name* or "hyperspace" be passed where only a generated id
            // used to work.
            String locationId = args.has("system")
                    ? requiredString(args, "system")
                    : requiredString(args, "locationId");
            float x = (float) requiredDouble(args, "x");
            float y = (float) requiredDouble(args, "y");
            LocationAPI location = resolveLocation(sector, locationId);
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
     * {@code effectivePaused()} on its next frame. Guest: {@code setBridgePauseIntent}, a
     * screen-level lever the pump ORs into the intent it recomputes every frame and ships as a
     * {@code PAUSE_INTENT(SCREEN)} exactly as opening a blocking screen would, and the host decides.
     * Writing the coordinator's guest latch directly instead looks identical and does nothing: the
     * pump overwrites it from the real UI on the next frame and the host never hears about it.
     *
     * <p>With no live session this is a refusal, not a local {@code setPaused} — a bridge that could
     * desync the clock locally would defeat its own purpose, and nothing applies an intent raised for
     * a session that does not exist.
     */
    static JSONObject pause(JSONObject args, Context context) throws JSONException {
        boolean on = requiredPauseState(args);
        CoopNetPump pump = context.pump();
        if (pump == null) {
            throw new IllegalStateException("no coop pump installed; pause has no shared coordinator");
        }
        CoopSharedPauseCoordinator coordinator = pump.pauseCoordinatorForBridge();
        CoopConnectionRole role = roleOf(pump);
        if (role == CoopConnectionRole.NONE || !pump.gameplaySessionActiveForBridge()) {
            throw new IllegalStateException("no coop session; pause has no shared clock to hold"
                    + " (role=" + role.name() + ")");
        }

        boolean changed;
        if (role == CoopConnectionRole.GUEST) {
            changed = pump.setBridgePauseIntent(on);
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

    /**
     * Adds {@code count} copies of {@code variantId} to the local player fleet, combat-ready.
     *
     * <p>Setup verb, and the counterpart to {@code give}: a single-ship test fleet overloads at 200
     * supplies and there was no way to add a freighter without restarting the instance (Phase 20 QA
     * matrix, 2026-09-02). Creation goes through {@code Global.getFactory().createFleetMember}, the
     * same call {@code CoopFleetMirror} builds mirror rosters with, so the member is a real one and
     * every downstream listener sees a normal roster change.
     *
     * <p><b>The variant is validated first, and that is not belt-and-braces.</b>
     * {@code createFleetMember} does not reject an unknown id — it substitutes a placeholder hull and
     * returns successfully (see {@code CoopFleetSnapshot.Member}), so a typo would otherwise add N
     * silent wrong ships. {@code CoopFleetSnapshotFactory.variantExists} is the same spec-store check
     * the wire path uses.
     *
     * <p>The fleet hash is captured either side of the change and logged, because that hash is what
     * makes the replicator resend the roster: a caller reading the log can tell an added ship that
     * will replicate from one that somehow did not move it.
     */
    static JSONObject addship(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CampaignFleetAPI player = requirePlayerFleet(sector);
        String variantId = requiredString(args, "variantId");
        int count = optionalInt(args, "count", 1);
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        if (count > MAX_ADDED_SHIPS) {
            throw new IllegalArgumentException("count " + count + " is over the " + MAX_ADDED_SHIPS
                    + "-ship cap for one call; this is a setup verb, not a fleet builder");
        }
        if (!CoopFleetSnapshotFactory.variantExists(variantId)) {
            throw new IllegalArgumentException("unknown variant " + variantId
                    + "; the fleet factory would substitute a placeholder hull rather than refuse it");
        }
        FleetDataAPI data = player.getFleetData();
        if (data == null) {
            throw new IllegalStateException("player fleet has no fleet data");
        }

        String hashBefore = fleetHash(player);
        JSONArray added = new JSONArray();
        for (int i = 0; i < count; i++) {
            FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
            if (member == null) {
                throw new IllegalStateException("the fleet factory returned no member for " + variantId
                        + " after adding " + added.length());
            }
            data.addFleetMember(member);
            readyForService(member);

            JSONObject entry = new JSONObject();
            entry.put("memberId", nullSafe(member.getId()));
            entry.put("variantId", nullSafe(member.getSpecId()));
            entry.put("hullId", nullSafe(member.getHullId()));
            entry.put("cr", member.getRepairTracker() == null
                    ? 0d : round(member.getRepairTracker().getCR()));
            added.put(entry);
        }
        data.setSyncNeeded();
        String hashAfter = fleetHash(player);

        CoopLog.info(CoopAgentCommands.class, "Coop agent bridge addship: +" + added.length() + " "
                + variantId + "; fleet hash " + hashBefore + " -> " + hashAfter
                + (hashBefore.equals(hashAfter)
                        ? " (UNCHANGED — no roster resend will follow)"
                        : " (changed; the roster resend follows on the next snapshot)"));

        JSONObject out = new JSONObject();
        out.put("variantId", variantId);
        out.put("requested", count);
        out.put("added", added.length());
        out.put("members", added);
        out.put("fleetSize", data.getMembersListCopy() == null ? 0 : data.getMembersListCopy().size());
        out.put("fleetHashBefore", hashBefore);
        out.put("fleetHashAfter", hashAfter);
        out.put("fleetHashChanged", !hashBefore.equals(hashAfter));
        return out;
    }

    /**
     * Full CR and no damage, so an added ship is usable the frame it appears. The existing dev fleets
     * a smoke run starts from are in that state, and a ship that arrived mothballed or at base CR
     * would quietly change what the check downstream is measuring.
     */
    private static void readyForService(FleetMemberAPI member) {
        RepairTrackerAPI repair = member.getRepairTracker();
        if (repair != null) {
            repair.setMothballed(false);
            repair.setCR(repair.getMaxCR());
        }
        if (member.getStatus() != null) {
            member.getStatus().repairFully();
        }
    }

    /**
     * The same hash the replication path keys a roster resend on. Best-effort: a capture failure
     * costs the log line its before/after pair, not the caller its verb.
     */
    private static String fleetHash(CampaignFleetAPI fleet) {
        try {
            return CoopFleetSnapshot.computeFleetHash(CoopFleetSnapshotFactory.captureMembers(fleet));
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
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

    // ---- rep: player-faction standing with an NPC faction ----------------------------------------

    /**
     * Sets the player faction's standing with {@code factionId} directly through the public API, for
     * smoke checks that need a specific relationship without waiting out the drift it would otherwise
     * take to get there.
     *
     * <p>Accepts either {@code value} (the raw {@code -1..1} float the API itself carries) or
     * {@code points} (the {@code -100..100} integer the game's UI shows) — exactly one of the two, so
     * the caller is never left guessing which one would win if both were given.
     *
     * <p><b>Host-only.</b> {@code PLAYER_REP_SNAPSHOT} (see {@code CoopCampaignReplicator}) is a
     * periodic full overwrite of the guest's standings from the host's, so a value set on the guest
     * would last only until the next one lands.
     */
    static JSONObject rep(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopConnectionRole role = roleOf(context.pump());
        requireRepAuthority(role);

        String factionId = requiredString(args, "factionId");
        if (sector.getFaction(factionId) == null) {
            throw new IllegalArgumentException("no faction with id " + factionId);
        }
        FactionAPI player = sector.getPlayerFaction();
        if (player == null) {
            throw new IllegalStateException("no player faction");
        }

        boolean hasValue = args.has("value");
        boolean hasPoints = args.has("points");
        if (hasValue == hasPoints) {
            throw new IllegalArgumentException("rep needs exactly one of {\"value\": -1..1} or"
                    + " {\"points\": -100..100}, got " + (hasValue ? "both" : "neither"));
        }

        float value;
        if (hasValue) {
            value = (float) requiredDouble(args, "value");
            if (value < -1f || value > 1f) {
                throw new IllegalArgumentException("value must be between -1 and 1, got " + value);
            }
        } else {
            int points = optionalInt(args, "points", 0);
            if (points < -100 || points > 100) {
                throw new IllegalArgumentException("points must be between -100 and 100, got " + points);
            }
            value = points / 100f;
        }

        float before = player.getRelationship(factionId);
        player.setRelationship(factionId, value);
        float after = player.getRelationship(factionId);

        CoopLog.info(CoopAgentCommands.class, "Coop agent bridge rep: " + factionId + " "
                + round(before) + " -> " + round(after));

        JSONObject out = new JSONObject();
        out.put("factionId", factionId);
        out.put("before", round(before));
        out.put("after", round(after));
        return out;
    }

    /** Refuses the guest: standings are host-authoritative and a guest edit would not survive a sync. */
    static void requireRepAuthority(CoopConnectionRole role) {
        if (role == CoopConnectionRole.GUEST) {
            throw new IllegalStateException("rep is host-only: standings are host-authoritative and the"
                    + " next PLAYER_REP_SNAPSHOT would overwrite a guest edit");
        }
    }

    // ---- memory: one campaign-memory key, read or written -----------------------------------------

    /** The memories {@code memory} can address, and the argument spelling for each. */
    static final List<String> MEMORY_SCOPES = List.of("global", "player", "entity");

    /** The rules-file prefix that names sector memory rather than part of the key. */
    private static final String MEMORY_GLOBAL_PREFIX = "global.";

    /**
     * Read or write one key in sector memory, the player fleet's memory, or any entity's.
     *
     * <p><b>Why it exists.</b> The flags that gate vanilla dialog options are campaign memory, and
     * nothing outside the game could set one. The case that forced the verb is the gate scan:
     * rules.csv gates the "Scan the Gate" option on {@code $global.canScanGates}, and the only thing
     * in vanilla that ever sets it is the At the Gates story chain. Without a way to set it on the
     * host, two code paths the mod owns are unreachable from a smoke run — the host&rarr;guest
     * replication of the flag ({@code CoopSkeletonMutationWatcher} / {@code WORLD_DELTA}
     * {@code GATE_ACTIVATED}) and the guest's own scan of a gate — so neither has ever been exercised
     * end to end.
     *
     * <p><b>Keys are normalised, not guessed.</b> The rules spelling and the engine spelling differ:
     * {@code $global.canScanGates} in a rule is the sector-memory key {@code "$canScanGates"}, where
     * {@code global.} names <em>which memory</em> and is no part of the key. So {@code canScanGates},
     * {@code $canScanGates} and {@code $global.canScanGates} all mean the same key here, and a
     * {@code global.} prefix in any other scope is refused rather than silently written to a key
     * nobody will ever read.
     *
     * <p><b>Types.</b> A boolean stays a boolean and a string stays a string; a number becomes a
     * {@code Float}, because that is what {@code MemoryAPI} stores and what {@code getFloat} and the
     * rules comparisons read back. Anything else (a JSON null, an object, an array) is a refusal.
     *
     * <p><b>Writes are allowed on both roles, and logged at WARN.</b> This is a test harness and half
     * its purpose is putting the two instances into deliberately unequal states, so there is no role
     * gate — but a flag set by hand is exactly what makes a later "desync" unexplainable, so every
     * write leaves a {@code Coop bridge memory write} line in the log that outlives the run. Reads
     * write nothing and log nothing.
     *
     * <p><b>No deferral.</b> {@code CoopAgentBridge} dispatches inside its own {@code advance()} on
     * the campaign thread, so a write lands on the same thread the engine mutates memory from; there
     * is nothing to marshal and nothing to wait for. That also makes the verb unsafe to replay blind
     * after a dropped socket, which is why the MCP client keeps it out of its retry allowlist with
     * the other mutations even when the request carries no {@code value}.
     *
     * <p>Args: {@code scope} (one of {@link #MEMORY_SCOPES}), {@code entityId} (required by, and only
     * accepted by, the {@code entity} scope; resolved by {@link #findEntity} exactly as
     * {@code teleport}'s entity mode resolves it), {@code key}, optional {@code value} and optional
     * {@code expireDays} (a positive number, only meaningful alongside a value).
     */
    static JSONObject memory(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        String scope = requiredString(args, "scope").toLowerCase(Locale.ROOT);
        String key = memoryKey(scope, requiredString(args, "key"));
        MemoryAPI memory = resolveMemory(sector, scope, args);

        JSONObject out = new JSONObject();
        out.put("scope", scope);
        out.put("key", key);

        boolean present = memory.contains(key);
        Object before = present ? memory.get(key) : null;
        if (!args.has("value")) {
            out.put("present", present);
            out.put("value", memoryJsonValue(before));
            out.put("type", before == null ? "" : before.getClass().getSimpleName());
            return out;
        }

        Object value = memoryValue(args.opt("value"));
        boolean expires = args.has("expireDays");
        double expireDays = optionalDouble(args, "expireDays", 0d);
        if (expires && expireDays <= 0d) {
            throw new IllegalArgumentException("expireDays must be positive, got " + expireDays);
        }
        if (expires) {
            memory.set(key, value, (float) expireDays);
        } else {
            memory.set(key, value);
        }
        Object after = memory.contains(key) ? memory.get(key) : null;

        CoopLog.warn(CoopAgentCommands.class, "Coop bridge memory write scope=" + scope
                + " key=" + key + " before=" + before + " after=" + after
                + (expires ? " expireDays=" + expireDays : ""));

        out.put("before", memoryJsonValue(before));
        out.put("after", memoryJsonValue(after));
        return out;
    }

    /**
     * The engine key for the key a caller asked for. Strips one leading {@code $} and, in the global
     * scope only, the {@code global.} that names the memory rather than the key; re-adds the
     * {@code $} every engine memory key carries.
     */
    static String memoryKey(String scope, String key) {
        String name = key.trim();
        if (name.startsWith("$")) {
            name = name.substring(1);
        }
        if (name.regionMatches(true, 0, MEMORY_GLOBAL_PREFIX, 0, MEMORY_GLOBAL_PREFIX.length())) {
            if (!"global".equals(scope)) {
                throw new IllegalArgumentException("the \"global.\" prefix names sector memory and means"
                        + " nothing in the " + scope + " scope; got " + key);
            }
            name = name.substring(MEMORY_GLOBAL_PREFIX.length());
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("key is empty once $ and global. are stripped: " + key);
        }
        return "$" + name;
    }

    /**
     * The memory the scope names. {@code entityId} is required by the entity scope and refused by the
     * other two: a caller who passed one meant an entity, and answering out of sector memory instead
     * would be a wrong answer wearing a right one's shape.
     */
    private static MemoryAPI resolveMemory(SectorAPI sector, String scope, JSONObject args) {
        if (!MEMORY_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("unknown memory scope " + scope + "; known scopes: "
                    + String.join(", ", MEMORY_SCOPES));
        }
        if (!"entity".equals(scope) && args.has("entityId")) {
            throw new IllegalArgumentException("entityId belongs to the entity scope; got it with scope "
                    + scope);
        }
        MemoryAPI memory = switch (scope) {
            case "global" -> sector.getMemoryWithoutUpdate();
            case "player" -> requirePlayerFleet(sector).getMemoryWithoutUpdate();
            default -> entityMemory(sector, requiredString(args, "entityId"));
        };
        if (memory == null) {
            throw new IllegalStateException("the " + scope + " scope has no memory");
        }
        return memory;
    }

    private static MemoryAPI entityMemory(SectorAPI sector, String entityId) {
        SectorEntityToken entity = findEntity(sector, entityId);
        if (entity == null) {
            throw new IllegalArgumentException("no entity with id " + entityId
                    + " anywhere in this sector");
        }
        return entity.getMemoryWithoutUpdate();
    }

    /** The JSON value a caller passed, as the engine type {@code MemoryAPI} stores for it. */
    static Object memoryValue(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof Number number) {
            return number.floatValue();
        }
        if (raw instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("memory value must be a boolean, a number or a string, got "
                + (raw == null ? "nothing" : raw.getClass().getSimpleName()));
    }

    /**
     * A stored value as JSON. Numbers go through the same quantization every other numeric response
     * field uses; anything the bridge has no JSON shape for (a {@code Vector2f}, an entity) is
     * reported as its string form rather than failing the read, which is the whole reason a read of
     * an unexpected key is still useful.
     */
    static Object memoryJsonValue(Object stored) {
        if (stored == null) {
            return JSONObject.NULL;
        }
        if (stored instanceof Boolean || stored instanceof String) {
            return stored;
        }
        if (stored instanceof Number number) {
            return round(number.floatValue());
        }
        try {
            return String.valueOf(stored);
        } catch (RuntimeException | LinkageError ex) {
            return stored.getClass().getSimpleName();
        }
    }

    // ---- feed: the campaign notices this instance has actually shown ------------------------------

    /** Feed lines returned when the caller does not say. One screenful. */
    static final int FEED_DEFAULT_LIMIT = 20;

    /** The ring's own depth; asking for more than exists is not an error, it just returns fewer. */
    static final int FEED_MAX_LIMIT = coop.ui.CoopSessionIntelFeed.MAX_BRIDGE_EVENTS;

    /**
     * The co-op feed lines the local player has seen on screen, oldest first.
     *
     * <p><b>Why this is not readable any other way.</b> The banners are the mod's own account of what
     * it thought was happening — the link fell back to TCP, the partner left, the checkpoint was
     * deferred — and each one scrolls off the campaign feed in seconds. A tester who was looking at
     * the other window has no way back to them, and the log line beside a banner is not the same
     * sentence.
     *
     * <p><b>It survives the session ending, deliberately.</b> The lines come out of a 200-deep ring
     * inside {@code CoopSessionIntelFeed} that only a full {@code reset()} clears
     * ({@code endSession()} does not), which is what makes "what did the guest's screen say when the
     * session ended" answerable afterwards — the moment worth asking about. That ring is separate
     * from the intel page's twenty-row event list rather than a widening of it: the page is a screen
     * a player reads and twenty rows is what fits, while this is a transcript.
     *
     * <p>The feed is read off <em>this pump's</em> instance, not the static handle, because the
     * static handle is uninstalled at teardown and the transcript is wanted after teardown. With no
     * pump at all the static handle is the fallback, and with neither the answer is
     * {@code installed:false} rather than a refusal — an instance that has never run a session has an
     * empty feed, which is a fact, not an error.
     */
    static JSONObject feed(JSONObject args, Context context) throws JSONException {
        int limit = optionalInt(args, "limit", FEED_DEFAULT_LIMIT);
        if (limit < 1 || limit > FEED_MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + FEED_MAX_LIMIT
                    + ", got " + limit);
        }
        CoopNetPump pump = context == null ? null : context.pump();
        coop.ui.CoopSessionIntelFeed feed = pump == null
                ? coop.ui.CoopSessionIntelFeed.active()
                : pump.intelFeedForBridge();

        JSONObject out = new JSONObject();
        out.put("limit", limit);
        out.put("installed", feed != null);
        JSONArray lines = new JSONArray();
        if (feed == null) {
            out.put("count", 0);
            out.put("lines", lines);
            return out;
        }
        out.put("sessionEnded", feed.sessionEnded());
        CoopConnectionRole role = feed.currentRole();
        out.put("role", role == null ? CoopConnectionRole.NONE.name() : role.name());
        for (coop.ui.CoopSessionIntelFeed.BridgeEvent event : feed.bridgeEvents(limit)) {
            JSONObject row = new JSONObject();
            row.put("atMillis", event.atMillis());
            row.put("kind", event.kind());
            row.put("text", event.text());
            row.put("color", event.color());
            lines.put(row);
        }
        out.put("count", lines.length());
        out.put("lines", lines);
        return out;
    }

    // ---- screen: what the UI is doing right now ----------------------------------------------------

    /**
     * The local UI's current state, in one object.
     *
     * <p>Every field here is something a smoke run currently establishes by asking the person driving
     * the game to look, and half of them are things that silently invalidate another verb: an open
     * dialog makes {@code save} a no-op, an open blocking screen holds the shared clock, and a coop
     * dialog waiting for the slot means the instance is not where the tester thinks it is.
     *
     * <p><b>Degrades field by field.</b> Each engine read is wrapped, so a UI that will not answer one
     * accessor still reports the rest — the alternative is a verb that refuses entirely at exactly
     * the moment (a torn-down UI, a load in progress) it is most worth asking.
     *
     * <p>{@code pause} is the same block {@code status} carries, from the same coordinator, so the two
     * verbs can never disagree about who is holding the clock.
     */
    static JSONObject screen(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        CoopNetPump pump = context.pump();
        CampaignUIAPI ui = campaignUiOrNull(sector);

        JSONObject out = new JSONObject();
        out.put("state", currentStateName());
        out.put("paused", sector.isPaused());
        out.put("uiAvailable", ui != null);
        out.put("dialogOpen", ui != null && safeBoolean(ui::isShowingDialog));
        out.put("menuOpen", ui != null && safeBoolean(ui::isShowingMenu));
        out.put("fastForward", ui != null && safeBoolean(ui::isFastForward));

        InteractionDialogAPI dialog = null;
        if (ui != null) {
            try {
                dialog = ui.getCurrentInteractionDialog();
            } catch (RuntimeException | LinkageError ignored) {
                dialog = null;
            }
        }
        // The plugin's class rather than the dialog's: the dialog object is an obfuscated engine
        // class and says nothing, while the plugin is the thing that decides what is on screen and
        // is what the mod's own dialogs are identified by everywhere else.
        out.put("interactionDialog", dialog == null ? JSONObject.NULL : interactionDialogName(dialog));
        SectorEntityToken target = interactionTarget(dialog);
        out.put("interactionTargetId", target == null ? "" : nullSafe(target.getId()));
        out.put("interactionTargetName", target == null ? "" : nullSafe(target.getName()));

        String coreTab = coreTabName(ui);
        out.put("coreTab", coreTab.isEmpty() ? JSONObject.NULL : coreTab);

        JSONObject coopDialog = coopDialogBlock(pump);
        out.put("coopDialog", coopDialog == null ? JSONObject.NULL : coopDialog);

        out.put("pause", pauseBlock(roleOf(pump),
                pump == null ? null : pump.pauseCoordinatorForBridge(),
                CoopNetPump.blockingScreenOpenForBridge(sector)));
        return out;
    }

    /**
     * The coop-owned dialog that is on screen or waiting for the slot, or null when there is none.
     *
     * <p>Walked in {@code CoopDialogArbiter}'s precedence order (reconnect, desync, lobby,
     * connecting), so the one reported is the one that would win the slot. {@code shown} is the
     * distinction that matters when something else holds it: a coop dialog can be requested for
     * minutes while a market screen is open, and "requested but not shown" is a state a tester
     * otherwise cannot see at all.
     */
    static JSONObject coopDialogBlock(CoopNetPump pump) throws JSONException {
        if (pump == null) {
            return null;
        }
        for (coop.ui.CoopDialogController controller : pump.coopDialogsForBridge()) {
            if (controller == null || !controller.isRequested()) {
                continue;
            }
            JSONObject out = new JSONObject();
            out.put("kind", controller.kind());
            out.put("title", controller.pendingTitle());
            out.put("shown", controller.isShown());
            return out;
        }
        return null;
    }

    /** {@code CAMPAIGN}, {@code TITLE}, {@code COMBAT}, or {@code ""} when the engine will not say. */
    static String currentStateName() {
        try {
            GameState state = Global.getCurrentState();
            return state == null ? "" : state.name();
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static CampaignUIAPI campaignUiOrNull(SectorAPI sector) {
        try {
            return sector.getCampaignUI();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String interactionDialogName(InteractionDialogAPI dialog) {
        try {
            InteractionDialogPlugin plugin = dialog.getPlugin();
            return plugin == null ? dialog.getClass().getSimpleName()
                    : plugin.getClass().getSimpleName();
        } catch (RuntimeException | LinkageError ex) {
            return dialog.getClass().getSimpleName();
        }
    }

    private static SectorEntityToken interactionTarget(InteractionDialogAPI dialog) {
        if (dialog == null) {
            return null;
        }
        try {
            return dialog.getInteractionTarget();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String coreTabName(CampaignUIAPI ui) {
        if (ui == null) {
            return "";
        }
        try {
            CoreUITabId tab = ui.getCurrentCoreTab();
            return tab == null ? "" : tab.name();
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    /** A boolean engine read that must not be able to take the whole verb down. */
    private static boolean safeBoolean(java.util.function.BooleanSupplier read) {
        try {
            return read.getAsBoolean();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- entities: everything in one location, addressable ----------------------------------------

    /** Rows one {@code entities} call may return. A busy core system is well under this. */
    static final int ENTITIES_MAX_ROWS = 300;

    /**
     * The kinds, in output order. {@code other} is a real kind, not a leftover bucket to be ashamed
     * of: asteroids, wrecks, debris fields, derelict probes and station terrain all land in it, and a
     * caller who wants "everything else in this system" has to be able to ask for it.
     */
    static final List<String> ENTITY_KINDS =
            List.of("planet", "station", "jumpPoint", "relay", "base", "fleet", "other");

    /**
     * Everything in one location, so a hidden base or a comm relay can be named instead of hunted for
     * on the map.
     *
     * <p><b>This is the verb that makes {@code teleport} usable against a hidden base.</b> Nothing
     * refused those before — {@code teleport}'s entity mode walks every location's own
     * {@code getEntityById} and a pirate base is an ordinary {@code SectorEntityToken} in that walk —
     * but nothing <em>emitted</em> their ids either, and an id you cannot obtain is not an argument
     * you can pass. {@code landmarks} covers worldgen one-offs by tag and {@code markets} covers the
     * economy, and a hidden base is in neither: it is minted at runtime by a timer and its market is
     * excluded from the economy index. So the ids this verb returns are exactly the set
     * {@code teleport} accepts, hidden bases included, and the {@code marketId} on a base row is the
     * key to look up in {@code status}'s {@code baseMarketIds} to see whether the guest has paired it.
     *
     * <p><b>Fleets are unioned in.</b> {@code LocationAPI.getAllEntities()} and
     * {@code getFleets()} are separate lists in the engine, so a walk of the first alone would report
     * a system with no fleets in it. De-duped by identity, because a modded location that puts a
     * fleet in both must not produce two rows.
     *
     * <p><b>Clutter is excluded by default, and that is a bug fix.</b> Measured 2026-09-13 in Corvus
     * and Askonia: the 300-row cap was spent entirely on {@code CampaignAsteroid},
     * {@code orbital_junk}, {@code RingBand} and terrain, and the system's <em>gate</em> — the one
     * entity the run was looking for — never appeared in a list of everything in the system. None of
     * those four is an interaction target, so with no {@code kinds} filter they are dropped before the
     * cap. Terrain is decided by its terrain id rather than its class, because a debris field is a
     * {@code CampaignTerrain} too and a debris field <em>is</em> a target; wrecks, derelict probes,
     * stations, planets, jump points, relays, bases and fleets were never in the excluded set at all.
     * Passing {@code kinds} at all, or {@code includeClutter: true}, restores the unfiltered walk —
     * {@code kinds} because the only way to ask for asteroids is {@code "other"}, and asking for a
     * thing that is then filtered out is not an answer.
     *
     * <p>Args: {@code system} (star system id or name, or {@code "hyperspace"}; default is wherever
     * the local player fleet is), {@code kinds} (array or comma-separated; default all),
     * {@code includeClutter} (default false). Sorted by kind then id, so {@code ss_diff} on it is a
     * real comparison, and capped at {@value #ENTITIES_MAX_ROWS} with a {@code truncated} flag rather
     * than silently short.
     */
    static JSONObject entities(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        List<String> kinds = requestedEntityKinds(args);
        // A kinds filter is itself a statement about what the caller wants, and "other" is the only
        // kind asteroids and terrain are ever in; filtering them out of an explicit request for them
        // would leave no way to ask at all.
        boolean excludeClutter = !optionalBoolean(args, "includeClutter", false)
                && !hasKindsArgument(args);
        String requested = optionalString(args, "system");

        LocationAPI location;
        if (requested.isEmpty()) {
            LocationAPI here = requirePlayerFleet(sector).getContainingLocation();
            if (here == null) {
                throw new IllegalStateException("the player fleet is in no location; pass a system id");
            }
            location = here;
        } else {
            location = resolveLocation(sector, requested);
            if (location == null) {
                throw new IllegalArgumentException("no location with id or name " + requested
                        + "; pass \"" + HYPERSPACE_KEYWORD + "\" for the hyperspace map");
            }
        }

        List<JSONObject> rows = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (SectorEntityToken entity : locationEntities(location)) {
            if (entity == null) {
                continue;
            }
            String id = nullSafe(entity.getId());
            if (!id.isEmpty() && !seen.add(id)) {
                continue;
            }
            String kind = entityKind(entity);
            if (!kinds.contains(kind)) {
                continue;
            }
            String type = entityType(entity);
            if (excludeClutter && isClutterEntity(type, terrainIdOf(entity))) {
                continue;
            }
            rows.add(entityRow(entity, kind, type));
        }
        rows.sort(Comparator
                .comparingInt((JSONObject row) -> ENTITY_KINDS.indexOf(row.optString("kind", "other")))
                .thenComparing(row -> row.optString("id", "")));

        JSONObject out = new JSONObject();
        out.put("locationId", nullSafe(location.getId()));
        out.put("locationName", nullSafe(location.getName()));
        out.put("kinds", new JSONArray(kinds));
        out.put("clutterExcluded", excludeClutter);
        out.put("candidateCount", rows.size());
        boolean truncated = rows.size() > ENTITIES_MAX_ROWS;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, ENTITIES_MAX_ROWS));
        }
        out.put("truncated", truncated);
        out.put("count", rows.size());
        out.put("entities", new JSONArray(rows));
        return out;
    }

    /** {@code getAllEntities()} plus {@code getFleets()}; either may be null on a bare location. */
    private static List<SectorEntityToken> locationEntities(LocationAPI location) {
        List<SectorEntityToken> all = new ArrayList<>();
        List<SectorEntityToken> entities = location.getAllEntities();
        if (entities != null) {
            all.addAll(entities);
        }
        List<CampaignFleetAPI> fleets = location.getFleets();
        if (fleets != null) {
            all.addAll(fleets);
        }
        return all;
    }

    /**
     * {@code kinds} as an array or a comma-separated string; absent means all of them. Same contract
     * as {@code landmarks}: an unknown key is a refusal naming the valid set, because "no entities of
     * that kind" and "you misspelled the kind" have to read differently.
     */
    static List<String> requestedEntityKinds(JSONObject args) throws JSONException {
        List<String> requested = new ArrayList<>();
        JSONArray array = args.optJSONArray("kinds");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                requested.add(array.getString(i).trim());
            }
        } else {
            for (String part : optionalString(args, "kinds").split(",")) {
                if (!part.trim().isEmpty()) {
                    requested.add(part.trim());
                }
            }
        }
        if (requested.isEmpty()) {
            return ENTITY_KINDS;
        }
        List<String> kinds = new ArrayList<>();
        for (String known : ENTITY_KINDS) {
            for (String want : requested) {
                if (known.equalsIgnoreCase(want) && !kinds.contains(known)) {
                    kinds.add(known);
                }
            }
        }
        for (String want : requested) {
            boolean known = false;
            for (String kind : ENTITY_KINDS) {
                known |= kind.equalsIgnoreCase(want);
            }
            if (!known) {
                throw new IllegalArgumentException("unknown entity kind " + want + "; known kinds: "
                        + String.join(", ", ENTITY_KINDS));
            }
        }
        return kinds;
    }

    /**
     * One kind per entity, first match wins.
     *
     * <p>The order is the interesting part. {@code base} is tested before {@code station} because a
     * hidden pirate/Path base <em>is</em> a station with a hidden market, and the hidden market is
     * the discriminator the mod itself uses everywhere else ({@code CoopCampaignReplicator}'s
     * {@code isHiddenMarket}, {@code CoopSectorFingerprint}'s market filter) — matching it here means
     * the bridge and the replicator can never disagree about what a base is. {@code relay} is
     * {@link Tags#OBJECTIVE}, which is the comm relay / sensor array / nav buoy set and exactly what
     * the {@code objective} verb acts on, so an id from this list is an {@code objective} argument.
     */
    static String entityKind(SectorEntityToken entity) {
        if (entity instanceof CampaignFleetAPI) {
            return "fleet";
        }
        if (isHiddenBaseEntity(entity)) {
            return "base";
        }
        if (entity instanceof JumpPointAPI || hasTag(entity, Tags.JUMP_POINT)) {
            return "jumpPoint";
        }
        if (hasTag(entity, Tags.OBJECTIVE)) {
            return "relay";
        }
        if (entity instanceof PlanetAPI) {
            return "planet";
        }
        if (hasTag(entity, Tags.STATION)) {
            return "station";
        }
        return "other";
    }

    /** The mod's own definition of a hidden base: an entity whose market is hidden. Total. */
    static boolean isHiddenBaseEntity(SectorEntityToken entity) {
        try {
            MarketAPI market = entity.getMarket();
            return market != null && market.isHidden();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean hasTag(SectorEntityToken entity, String tag) {
        try {
            return entity.hasTag(tag);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * The entity types that are never an interaction target, lower-cased for matching. Three class
     * names and one custom-entity spec id, which is exactly how {@link #entityType} reports them.
     * Terrain is in the set only as the fallback for a terrain entity whose plugin will not name
     * itself — {@link #isClutterEntity} decides terrain by its terrain id first.
     */
    static final Set<String> CLUTTER_ENTITY_TYPES =
            Set.of("campaignasteroid", "orbital_junk", "ringband", "campaignterrain");

    /**
     * The clutter decision, as a pure function of the two strings a row already carries. Terrain wins
     * the decision when it is identified: every terrain type is clutter <em>except</em>
     * {@link Terrain#DEBRIS_FIELD}, which is an ordinary salvage target that happens to be
     * implemented as terrain. Otherwise the type name decides.
     *
     * @param type      {@link #entityType}'s answer: the custom entity spec id, else the class name
     * @param terrainId the entity's terrain id, or empty when it is not terrain (or will not say)
     */
    static boolean isClutterEntity(String type, String terrainId) {
        String terrain = terrainId == null ? "" : terrainId.trim();
        if (!terrain.isEmpty()) {
            return !Terrain.DEBRIS_FIELD.equalsIgnoreCase(terrain);
        }
        String name = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return CLUTTER_ENTITY_TYPES.contains(name);
    }

    /** Whether the request carried a {@code kinds} argument at all, in either accepted spelling. */
    static boolean hasKindsArgument(JSONObject args) {
        return args.optJSONArray("kinds") != null || !optionalString(args, "kinds").isEmpty();
    }

    /** The terrain id of a terrain entity, or empty. Never throws: an unreadable plugin is "unknown". */
    private static String terrainIdOf(SectorEntityToken entity) {
        if (!(entity instanceof CampaignTerrainAPI terrain)) {
            return "";
        }
        try {
            CampaignTerrainPlugin plugin = terrain.getPlugin();
            String id = plugin == null ? null : plugin.getTerrainId();
            return id == null || id.isEmpty() ? nullSafe(terrain.getType()) : id;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static JSONObject entityRow(SectorEntityToken entity, String kind, String type)
            throws JSONException {
        JSONObject row = new JSONObject();
        row.put("id", nullSafe(entity.getId()));
        row.put("name", nullSafe(entity.getName()));
        row.put("kind", kind);
        row.put("type", type);
        FactionAPI faction = safeRead(entity::getFaction);
        row.put("faction", faction == null ? "" : nullSafe(faction.getId()));
        Vector2f at = safeRead(entity::getLocation);
        row.put("x", round(at == null ? 0f : at.x));
        row.put("y", round(at == null ? 0f : at.y));
        row.put("tags", new JSONArray(entityTags(entity)));
        SectorEntityToken focus = safeRead(entity::getOrbitFocus);
        row.put("orbitFocus", focus == null ? "" : nullSafe(focus.getId()));
        row.put("hidden", isHiddenBaseEntity(entity));
        // isDiscoverable() is vanilla's "not found yet" flag, not "cannot be found": it goes false the
        // moment the player discovers the entity, so true here means undiscovered.
        row.put("discoverable", safeBoolean(entity::isDiscoverable));
        MarketAPI market = safeRead(entity::getMarket);
        row.put("marketId", market == null ? "" : nullSafe(market.getId()));
        return row;
    }

    /** The custom entity spec id where there is one, otherwise the entity's class. */
    private static String entityType(SectorEntityToken entity) {
        String custom = safeRead(entity::getCustomEntityType);
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        return entity.getClass().getSimpleName();
    }

    private static List<String> entityTags(SectorEntityToken entity) {
        java.util.Collection<String> tags = safeRead(entity::getTags);
        if (tags == null) {
            return List.of();
        }
        // Sorted: the engine's tag set has no defined iteration order and two clients must emit the
        // same row for the same entity or every diff is noise.
        return new ArrayList<>(new TreeSet<>(tags));
    }

    /** An object-valued engine read that must not be able to take the whole row down. */
    private static <T> T safeRead(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    // ---- intel: the player's intel entries ---------------------------------------------------------

    static final int INTEL_DEFAULT_LIMIT = 50;
    static final int INTEL_MAX_LIMIT = 300;

    /**
     * The local player's intel entries, so "the guest has a Hostile Activity entry the host does not"
     * is a query rather than a screenshot.
     *
     * <p><b>The check this exists for.</b> Colony crises are host-authoritative; a Hostile Activity
     * entry on the guest means a suppressed manager ran anyway, and the smoke has to be able to say
     * so after a reload as well as during play. The {@code hostileActivity} block answers that
     * directly off {@code HostileActivityEventIntel.get()} — present or not, and if present its
     * progress out of {@link com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityEventIntel#MAX_PROGRESS}
     * and the factors driving it — rather than leaving the caller to infer absence from a missing row,
     * which would also be what a mis-spelled filter produced.
     *
     * <p>Every row carries the flags an entry's visibility turns on ({@code isNew}, {@code isEnding},
     * {@code isEnded}, {@code important}) plus an {@code extra} object: progress and factor names for
     * anything descending from {@code BaseEventIntel}, and for the mod's own pages whatever
     * {@link coop.util.CoopIntelFacts} says their key numbers are — asked of the entry rather than
     * reached into, so a page that grows a field answers with it here for free.
     *
     * <p>Args: {@code filter} (case-insensitive substring of the class name or the title),
     * {@code limit} (default {@value #INTEL_DEFAULT_LIMIT}). Sorted by class then title.
     */
    static JSONObject intel(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        String filter = optionalString(args, "filter").toLowerCase(Locale.ROOT);
        int limit = optionalInt(args, "limit", INTEL_DEFAULT_LIMIT);
        if (limit < 1 || limit > INTEL_MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + INTEL_MAX_LIMIT
                    + ", got " + limit);
        }
        IntelManagerAPI manager = sector.getIntelManager();
        if (manager == null) {
            throw new IllegalStateException("no intel manager; this campaign has no intel screen yet");
        }
        List<IntelInfoPlugin> all = manager.getIntel();
        if (all == null) {
            all = List.of();
        }

        List<JSONObject> rows = new ArrayList<>();
        for (IntelInfoPlugin entry : all) {
            if (entry == null) {
                continue;
            }
            JSONObject row = intelRow(entry);
            if (!filter.isEmpty()
                    && !row.optString("class", "").toLowerCase(Locale.ROOT).contains(filter)
                    && !row.optString("title", "").toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((JSONObject row) -> row.optString("class", ""))
                .thenComparing(row -> row.optString("title", "")));

        JSONObject out = new JSONObject();
        out.put("filter", filter);
        out.put("limit", limit);
        out.put("total", all.size());
        out.put("matched", rows.size());
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        out.put("count", rows.size());
        out.put("intel", new JSONArray(rows));
        out.put("hostileActivity", hostileActivityBlock());
        return out;
    }

    private static JSONObject intelRow(IntelInfoPlugin entry) throws JSONException {
        JSONObject row = new JSONObject();
        row.put("class", entry.getClass().getSimpleName());
        row.put("title", intelTitle(entry));
        row.put("tags", new JSONArray(intelTags(entry)));
        row.put("isNew", safeBoolean(entry::isNew));
        row.put("isEnding", safeBoolean(entry::isEnding));
        row.put("isEnded", safeBoolean(entry::isEnded));
        row.put("important", safeBoolean(entry::isImportant));
        row.put("hidden", safeBoolean(entry::isHidden));
        FactionAPI faction = safeRead(entry::getFactionForUIColors);
        row.put("factionId", faction == null ? "" : nullSafe(faction.getId()));
        row.put("extra", intelExtra(entry));
        return row;
    }

    /**
     * {@code getSmallDescriptionTitle()}, falling back to the class name.
     *
     * <p>That accessor <em>is</em> the entry's name for anything descending from
     * {@code BaseIntelPlugin}, which is everything the mod and vanilla register:
     * {@code BaseIntelPlugin.getSmallDescriptionTitle()} returns {@code getName()}. Going through it
     * rather than {@code getName()} directly is not a workaround — {@code getName()} is
     * {@code protected} and unreachable from here, and the sandbox forbids the reflection that would
     * open it, so the public accessor that delegates to it is the only honest route to the string
     * the intel list row shows.
     */
    static String intelTitle(IntelInfoPlugin entry) {
        String title = safeRead(entry::getSmallDescriptionTitle);
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        return entry.getClass().getSimpleName();
    }

    /**
     * {@code getIntelTags(null)} first, because that is the set the intel screen filters on; several
     * vanilla implementations touch the map argument, so a null map is a real throw risk and
     * {@code getTagsForSort()} is the fallback. Sorted, for the same diff reason entity tags are.
     */
    static List<String> intelTags(IntelInfoPlugin entry) {
        Set<String> tags = safeRead(() -> entry.getIntelTags(null));
        if (tags == null) {
            tags = safeRead(entry::getTagsForSort);
        }
        if (tags == null) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String tag : tags) {
            if (tag != null) {
                sorted.add(tag);
            }
        }
        return new ArrayList<>(sorted);
    }

    /** Progress and factors for an event intel, plus whatever a coop page says about itself. */
    static JSONObject intelExtra(IntelInfoPlugin entry) throws JSONException {
        JSONObject extra = new JSONObject();
        if (entry instanceof BaseEventIntel event) {
            extra.put("progress", event.getProgress());
            extra.put("maxProgress", event.getMaxProgress());
            extra.put("factors", new JSONArray(eventFactors(event)));
        }
        if (entry instanceof coop.util.CoopIntelFacts facts) {
            Map<String, Object> values = safeRead(facts::intelFacts);
            if (values != null) {
                for (Map.Entry<String, Object> value : values.entrySet()) {
                    extra.put(value.getKey(), value.getValue());
                }
            }
        }
        return extra;
    }

    /**
     * The active factors' names and their contributions. {@code getDesc} is the sentence the event
     * page renders for each one ("Hostile activity in the Askonia system"), which is what names the
     * cause; a factor whose description throws is reported by its class rather than dropped, because
     * a factor existing at all is itself the finding.
     */
    static List<JSONObject> eventFactors(BaseEventIntel event) throws JSONException {
        List<EventFactor> factors = safeRead(event::getFactors);
        if (factors == null) {
            return List.of();
        }
        List<JSONObject> rows = new ArrayList<>();
        for (EventFactor factor : factors) {
            if (factor == null) {
                continue;
            }
            JSONObject row = new JSONObject();
            String desc = safeRead(() -> factor.getDesc(event));
            row.put("name", desc == null || desc.trim().isEmpty()
                    ? factor.getClass().getSimpleName() : desc.trim());
            Integer progress = safeRead(() -> factor.getProgress(event));
            row.put("progress", progress == null ? 0 : progress);
            rows.add(row);
        }
        rows.sort(Comparator.comparing(row -> row.optString("name", "")));
        return rows;
    }

    /**
     * Whether this engine has a Hostile Activity event at all, and where it stands.
     *
     * <p>Read off the vanilla static handle rather than by scanning the intel list, so "no entry" is
     * a positive answer instead of the absence of a row: the entry can be present and hidden, and a
     * scan would report that as absent, which is precisely backwards for a check whose finding is
     * "the guest has one and should not".
     *
     * <p>Totally wrapped, including {@code LinkageError}: touching the class runs its static
     * initialiser, which reads a settings key, and an instance with no settings loaded must report
     * {@code present:false} rather than take the verb down.
     */
    static JSONObject hostileActivityBlock() throws JSONException {
        JSONObject out = new JSONObject();
        HostileActivityEventIntel event;
        try {
            event = HostileActivityEventIntel.get();
        } catch (RuntimeException | LinkageError ex) {
            out.put("present", false);
            out.put("unreadable", ex.getClass().getSimpleName());
            return out;
        }
        out.put("present", event != null);
        if (event == null) {
            return out;
        }
        out.put("progress", safeInt(event::getProgress));
        out.put("maxProgress", safeInt(event::getMaxProgress));
        out.put("factors", new JSONArray(eventFactors(event)));
        return out;
    }

    private static int safeInt(java.util.function.IntSupplier read) {
        try {
            return read.getAsInt();
        } catch (RuntimeException | LinkageError ex) {
            return 0;
        }
    }

    // ---- save: this instance takes its own vanilla autosave ---------------------------------------

    /**
     * Runs {@code CampaignUIAPI.autosave()} here, which is an F5 in everything but the keypress.
     *
     * <p><b>The hooks are the point.</b> Autosave is a real save: the engine fires
     * {@code ModPlugin.beforeGameSave()} and {@code afterGameSave()} around it, and the mod's
     * {@code afterGameSave} is where {@code CoopSaveCheckpoint.notifyLocalGameSaved} sends the guest
     * its {@code SAVE_CHECKPOINT}. So a host {@code save} produces the coordinated pair exactly as a
     * manual save does. That is not an assumption about the engine: {@code CoopPreBattleAutosave} is
     * built on the same call for the same reason and the checkpoint has ridden it since Phase 16.
     * {@code CoopSaveIndex.beginCoopAutosave()} brackets it, as it does there, so the row this writes
     * is tagged an autosave rather than guessed at afterwards.
     *
     * <p><b>Guest saves are refused by default.</b> The guest's save is supposed to be the one the
     * host's checkpoint ordered — that is what keeps the two temporally aligned and what a rejoin
     * loads — and a guest-side save taken at some unrelated moment is a save no host state matches.
     * {@code force:true} takes it anyway, because a tester sometimes wants exactly that.
     *
     * <p><b>It runs inline and does not defer.</b> The bridge dispatches inside its own
     * {@code advance()}, on the campaign thread, so the save happens before this returns; there is no
     * queue to park it in and nothing to wait for. What it will not do is pretend: {@code autosave()}
     * is silently a no-op while any dialog is open or outside the campaign state, so those are
     * refusals naming the screen to close rather than a {@code requested:true} for a save that never
     * happened. The precondition is {@code CoopPreBattleAutosave.canAutosaveNow}, the same predicate
     * the pre-battle path parks on.
     */
    static JSONObject save(JSONObject args, Context context) throws JSONException {
        SectorAPI sector = requireSector(context);
        boolean force = optionalBoolean(args, "force", false);
        CoopConnectionRole role = roleOf(context.pump());
        requireSaveAuthority(role, force);

        CampaignUIAPI ui = campaignUiOrNull(sector);
        if (ui == null) {
            throw new IllegalStateException("no campaign UI; there is nothing to save through");
        }
        // Both halves, exactly as CoopPreBattleAutosave reads them: isShowingDialog covers the core
        // screens, getCurrentInteractionDialog covers a conversation, and either one makes the call
        // a no-op.
        boolean dialogOpen = safeBoolean(ui::isShowingDialog)
                || safeRead(ui::getCurrentInteractionDialog) != null;
        String state = currentStateName();
        if (!CoopPreBattleAutosave.canAutosaveNow(true, dialogOpen, "CAMPAIGN".equals(state))) {
            throw new IllegalStateException("autosave() is silently skipped unless the campaign is on"
                    + " screen with no dialog open (state=" + (state.isEmpty() ? "unknown" : state)
                    + ", dialogOpen=" + dialogOpen + "); close the screen and try again");
        }

        coop.save.CoopSaveIndex.beginCoopAutosave();
        try {
            ui.autosave();
        } finally {
            coop.save.CoopSaveIndex.endCoopAutosave();
        }
        String reason = role == CoopConnectionRole.GUEST
                ? "forced guest-side autosave"
                : "bridge autosave on " + role.name();
        CoopLog.info(CoopAgentCommands.class, "Coop agent bridge save: " + reason);

        JSONObject out = new JSONObject();
        out.put("requested", true);
        out.put("performed", true);
        out.put("role", role.name());
        out.put("forced", force);
        out.put("reason", reason);
        return out;
    }

    /**
     * Refuses an unforced guest save: a save the host did not order is one no host save is aligned
     * with, which is the whole property the coordinated pair exists for.
     */
    static void requireSaveAuthority(CoopConnectionRole role, boolean force) {
        if (role == CoopConnectionRole.GUEST && !force) {
            throw new IllegalStateException("guest saves are coordinated by the host's checkpoint; use"
                    + " save on the host (pass {\"force\":true} to save on the guest anyway)");
        }
    }

    // ---- mark: one line in this instance's log, on demand -----------------------------------------

    /** Longest {@code mark} text. Long enough for a step description, short enough to stay one line. */
    static final int MARK_MAX_LENGTH = 200;

    /** The prefix every mark carries, so two logs can be lined up with one grep. */
    static final String MARK_PREFIX = "Coop MARK ";

    /**
     * Writes one {@code Coop MARK <text>} line at INFO and returns the wall clock it was written at.
     *
     * <p>Two logs from two instances have no shared reference point: the campaign clock is shared but
     * a log line is stamped in wall time, and the two JVMs started minutes apart. A mark on both sides
     * at the top of a step gives every line after it a known offset, which is the difference between
     * reading two logs and correlating them.
     *
     * <p>Needs no sector and no session — it works at the title screen and before a session exists,
     * which is when a smoke run is setting up and most wants to timestamp what it just did. Newlines
     * are refused rather than stripped: a mark that silently became two log lines would break the very
     * grep it exists for.
     */
    static JSONObject mark(JSONObject args, Context context) throws JSONException {
        String text = requiredString(args, "text");
        if (text.length() > MARK_MAX_LENGTH) {
            throw new IllegalArgumentException("text must be at most " + MARK_MAX_LENGTH
                    + " characters, got " + text.length());
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("text must be a single line; it contains a newline");
        }

        long atMillis = System.currentTimeMillis();
        CoopLog.info(CoopAgentCommands.class, MARK_PREFIX + text);

        JSONObject out = new JSONObject();
        out.put("atMillis", atMillis);
        out.put("text", text);
        return out;
    }

    // ---- netfault: a deliberate inbound outage on this instance -----------------------------------

    /**
     * Makes <em>this</em> instance stop hearing its peer for a while, so a link drop can be
     * reproduced on demand without freezing a JVM from outside.
     *
     * <p><b>What it reproduces.</b> The 0.1.1 loss — bytes the sender's TCP stack considers delivered
     * that the receiver never applied — is what the reliable-delivery layer (RELIABLE_ACK plus the
     * unacked replay) exists to heal. Suspending the peer process does not produce it: that stops the
     * peer from sending. Going deaf here does. See {@link CoopNetFault} for the mechanics.
     *
     * <p><b>Modes.</b> {@code discard} throws away every inbound byte, TCP and UDP; {@code loss}
     * drops {@code lossPercent} of inbound datagrams and leaves TCP alone; {@code clear} ends an
     * active fault now. {@code seconds} is 1..{@link CoopNetFault#MAX_SECONDS} and the fault expires
     * by itself, so a forgotten one heals.
     *
     * <p><b>Arming.</b> {@code delaySeconds} (0..{@link CoopNetFault#MAX_DELAY_SECONDS}, default 0)
     * schedules the outage instead of starting it. The tester is a human at two game windows, and a
     * fault that lands while they are still in a menu proves nothing: an armed fault logs its warning
     * line, shows up as {@code armed} with a {@code startsInSeconds} countdown, and drops nothing
     * until it flips. {@code clear} while armed cancels it before it ever touches a byte, which the
     * response says with {@code wasArmed}.
     *
     * <p><b>Outbound is never touched, in any mode.</b> This side keeps writing and the peer keeps
     * hearing it, which is what makes the sender believe it delivered. A <em>symmetric</em> outage is
     * this verb run on both instances.
     *
     * <p>Works on host and guest alike: both sides own the same transport and either can be the one
     * that goes deaf. Arguments are validated before the transport is looked up, so a malformed
     * request is refused for what is wrong with it rather than for the session it was aimed at.
     */
    static JSONObject netfault(JSONObject args, Context context) throws JSONException {
        String mode = requiredString(args, "mode").toLowerCase(Locale.ROOT);
        JSONObject out = new JSONObject();
        if ("clear".equals(mode)) {
            CoopNetService.NetFaultClear cleared = requireTransport(context).clearNetFault();
            out.put("mode", "clear");
            out.put("cleared", cleared.cleared());
            // Worth its own field rather than inferable from the counters: "cancelled before it
            // started" and "ended after it dropped nothing measurable" look identical otherwise.
            out.put("wasArmed", cleared.wasArmed());
            return out;
        }

        CoopNetFault.Mode faultMode = switch (mode) {
            case "discard" -> CoopNetFault.Mode.DISCARD;
            case "loss" -> CoopNetFault.Mode.LOSS;
            default -> throw new IllegalArgumentException(
                    "netfault mode must be discard|loss|clear, got " + mode);
        };
        if (!args.has("seconds")) {
            throw new IllegalArgumentException("netfault " + mode + " needs {\"seconds\": 1.."
                    + CoopNetFault.MAX_SECONDS + "}");
        }
        int seconds = optionalInt(args, "seconds", 0);
        if (seconds < 1 || seconds > CoopNetFault.MAX_SECONDS) {
            throw new IllegalArgumentException("seconds must be between 1 and "
                    + CoopNetFault.MAX_SECONDS + ", got " + seconds);
        }
        int lossPercent = 0;
        if (faultMode == CoopNetFault.Mode.LOSS) {
            if (!args.has("lossPercent")) {
                throw new IllegalArgumentException("netfault loss needs {\"lossPercent\": 1..100}");
            }
            lossPercent = optionalInt(args, "lossPercent", 0);
            if (lossPercent < 1 || lossPercent > 100) {
                throw new IllegalArgumentException("lossPercent must be between 1 and 100, got "
                        + lossPercent);
            }
        }
        // Optional, unlike seconds: an absent delay is a fault that starts now, which is the shape
        // every caller written before arming existed still sends.
        int delaySeconds = optionalInt(args, "delaySeconds", 0);
        if (delaySeconds < 0 || delaySeconds > CoopNetFault.MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("delaySeconds must be between 0 and "
                    + CoopNetFault.MAX_DELAY_SECONDS + ", got " + delaySeconds);
        }

        CoopNetService.NetFaultStatus status = requireTransport(context)
                .applyNetFault(faultMode, seconds, lossPercent, delaySeconds);
        out.put("mode", status.mode());
        out.put("endsAtMillis", status.endsAtMillis());
        out.put("remainingSeconds", status.remainingSeconds());
        out.put("armed", status.armed());
        out.put("startsInSeconds", status.startsInSeconds());
        if (faultMode == CoopNetFault.Mode.LOSS) {
            out.put("lossPercent", lossPercent);
        }
        return out;
    }

    /** A fault only means anything against a running transport; with none, say so rather than no-op. */
    private static CoopNetService requireTransport(Context context) {
        CoopNetPump pump = context == null ? null : context.pump();
        CoopNetService service = pump == null ? null : pump.netServiceForBridge();
        if (service == null || service.role() == CoopConnectionRole.NONE) {
            throw new IllegalStateException("netfault needs a running coop transport; this instance has"
                    + " no session (role " + (service == null ? "NONE, no pump installed"
                    : service.role().name()) + ")");
        }
        return service;
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
