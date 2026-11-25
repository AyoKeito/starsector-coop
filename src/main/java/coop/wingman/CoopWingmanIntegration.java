package coop.wingman;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import coop.config.CoopOptionsPolicy;
import coop.config.CoopOptionsRegistry;
import coop.fleet.CoopGuestMirrorHandle;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.ui.CoopFeed;
import coop.util.CoopLog;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Phase 34: Wingman battles inside a coop campaign session.
 *
 * <p>The coop mod owns the campaign; The Wingman Project owns the battle. When both are
 * installed and {@code coop.wingmanBattles} is on (default), the host opens its Wingman session
 * from the campaign map, the guest's Wingman client connects to the host's PC, and Wingman
 * serves every battle the host fights — so both players fly in the same battle instead of the
 * solo own-fleet model. The coop layer is unchanged on the host side (it still streams
 * {@code BATTLE_BEGIN/STATUS/END} and reconciles the result into the shared world, including
 * real losses on the guest's ships through the existing ally-battle outcome path).
 *
 * <p>Host responsibilities: open the Wingman session, pump it, learn the guest's Wingman name
 * (via the {@code WINGMAN_HELLO} coop message) and reserve the guest's mirror-fleet flagship for
 * that name, so Wingman hands the guest their own ship whenever their fleet is deployed
 * (Fight Alongside).
 *
 * <p>Guest responsibilities: report its Wingman name, and post a one-time hint telling the
 * player where to connect Wingman (Wingman has no programmatic join; one manual step).
 *
 * <p>Everything degrades to the vanilla coop behaviour when Wingman is absent, disabled, or any
 * call fails. All state is per-session and resets on a session id change.
 */
public final class CoopWingmanIntegration {

    private static final String DEFAULT_WINGMAN_PORT = "24816";
    /** Joiner names are matched ignoring case by Wingman; we compare the same way. */
    private static final int MAX_NAME_CHARS = 64;

    private static volatile CoopWingmanIntegration active;

    public static CoopWingmanIntegration active() {
        return active;
    }

    /** Production wiring. Registers this instance as the active integration for the bridge. */
    public static CoopWingmanIntegration create(CoopNetService service, CoopSessionState sessionState,
                                                LongSupplier clockMillis,
                                                Consumer<CoopMessages.Message> reliableSender) {
        return create(service::role, service::nextSeq, sessionState::sessionId, clockMillis,
                reliableSender);
    }

    /** Production wiring with explicit seams. */
    public static CoopWingmanIntegration create(Supplier<CoopConnectionRole> roleSupplier,
                                                LongSupplier seqSupplier,
                                                Supplier<String> sessionIdSupplier,
                                                LongSupplier clockMillis,
                                                Consumer<CoopMessages.Message> reliableSender) {
        CoopWingmanApi api = new ReflectiveCoopWingmanApi();
        BooleanSupplier enabledOption = () -> {
            CoopOptionsPolicy policy = CoopOptionsPolicy.active();
            return policy != null && policy.effectiveBool(CoopOptionsRegistry.WINGMAN_BATTLES);
        };
        Supplier<String> guestFlagshipId = () -> {
            CampaignFleetAPI mirror = CoopGuestMirrorHandle.current();
            if (mirror == null) {
                return "";
            }
            FleetMemberAPI flagship = mirror.getFlagship();
            return flagship == null ? "" : flagship.getId();
        };
        Consumer<String> feedPoster = text -> CoopFeed.post(text, null);
        CoopWingmanIntegration integration = new CoopWingmanIntegration(roleSupplier, seqSupplier,
                sessionIdSupplier, clockMillis, api, enabledOption, guestFlagshipId, reliableSender,
                feedPoster);
        active = integration;
        return integration;
    }

    private final Supplier<CoopConnectionRole> roleSupplier;
    private final LongSupplier seqSupplier;
    private final Supplier<String> sessionIdSupplier;
    private final LongSupplier clockMillis;
    private final CoopWingmanApi api;
    private final BooleanSupplier enabledOption;
    private final Supplier<String> guestFlagshipMemberId;
    private final Consumer<CoopMessages.Message> reliableSender;
    private final Consumer<String> feedPoster;

    private String lastSessionId = "";
    private boolean hostOpenedForSession;
    private boolean reservedForSession;
    private boolean helloSentForSession;
    private boolean hintSentForSession;
    private String guestWingmanName = "";

    public CoopWingmanIntegration(Supplier<CoopConnectionRole> roleSupplier,
                                  LongSupplier seqSupplier, Supplier<String> sessionIdSupplier,
                                  LongSupplier clockMillis, CoopWingmanApi api,
                                  BooleanSupplier enabledOption,
                                  Supplier<String> guestFlagshipMemberId,
                                  Consumer<CoopMessages.Message> reliableSender,
                                  Consumer<String> feedPoster) {
        this.roleSupplier = Objects.requireNonNull(roleSupplier, "roleSupplier");
        this.seqSupplier = Objects.requireNonNull(seqSupplier, "seqSupplier");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.api = Objects.requireNonNull(api, "api");
        this.enabledOption = Objects.requireNonNull(enabledOption, "enabledOption");
        this.guestFlagshipMemberId =
                Objects.requireNonNull(guestFlagshipMemberId, "guestFlagshipMemberId");
        this.reliableSender = Objects.requireNonNull(reliableSender, "reliableSender");
        this.feedPoster = Objects.requireNonNull(feedPoster, "feedPoster");
    }

    // ---- per-frame tick (campaign frames, both roles) -----------------------------------------

    /** Never throws; called once per pump frame. */
    public void tick() {
        try {
            if (!enabledOption.getAsBoolean() || !api.wingmanEnabled()) {
                return;
            }
            String sessionId = sessionIdOrEmpty();
            if (sessionId.isEmpty() || !sessionId.equals(lastSessionId)) {
                resetForSession(sessionId);
            }
            if (sessionId.isEmpty()) {
                return;
            }
            if (roleSupplier.get() == CoopConnectionRole.HOST) {
                tickHost(sessionId);
            } else if (roleSupplier.get() == CoopConnectionRole.GUEST) {
                tickGuest(sessionId);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopWingmanIntegration.class, "Coop wingman integration frame failed", ex);
        }
    }

    private void tickHost(String sessionId) {
        if (!hostOpenedForSession) {
            hostOpenedForSession = true;
            boolean opened = api.openHost();
            CoopLog.info(CoopWingmanIntegration.class,
                    "Coop wingman: host session " + (opened ? "opened" : "could not open")
                            + " for coop session " + sessionId);
        }
        String lines = api.pumpHost();
        if (!lines.isEmpty()) {
            CoopLog.info(CoopWingmanIntegration.class,
                    "Coop wingman: " + lines.replace('\n', ' '));
        }
        if (!guestWingmanName.isEmpty() && !reservedForSession) {
            maybeReserve();
        }
    }

    private void maybeReserve() {
        List<String> names = api.playerNames();
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(guestWingmanName)) {
                String memberId = guestFlagshipMemberId.get();
                if (memberId.isEmpty()) {
                    CoopLog.info(CoopWingmanIntegration.class,
                            "Coop wingman: " + guestWingmanName + " is connected but the guest "
                                    + "mirror fleet has no flagship yet; will reserve later");
                    return;
                }
                api.reserveShip(guestWingmanName, memberId);
                reservedForSession = true;
                CoopLog.info(CoopWingmanIntegration.class,
                        "Coop wingman: reserved the guest flagship " + memberId + " for "
                                + guestWingmanName);
                feedPoster.accept("Co-op: Wingman battles are on - " + guestWingmanName
                        + " will get their flagship when it deploys.");
                return;
            }
        }
    }

    private void tickGuest(String sessionId) {
        if (!helloSentForSession) {
            String name = api.localName();
            if (!name.isEmpty()) {
                helloSentForSession = true;
                reliableSender.accept(CoopMessages.wingmanHello(sessionId, seqSupplier.getAsLong(),
                        clockMillis.getAsLong(), name));
            }
        }
        if (!hintSentForSession) {
            hintSentForSession = true;
            String host = "";
            try {
                host = CoopOptionsPolicy.appliedOrDefault(CoopOptionsRegistry.CONNECT_HOST);
            } catch (RuntimeException | LinkageError ex) {
                host = "";
            }
            feedPoster.accept(host == null || host.isEmpty()
                    ? "Co-op: Wingman battles are on - open Wingman's CO-OP panel (title screen) "
                            + "and connect to the host's PC on port " + DEFAULT_WINGMAN_PORT
                            + " to fly in battles."
                    : "Co-op: Wingman battles are on - open Wingman's CO-OP panel (title screen) "
                            + "and connect to " + host + ":" + DEFAULT_WINGMAN_PORT
                            + " to fly in battles.");
        }
    }

    private void resetForSession(String sessionId) {
        lastSessionId = sessionId;
        hostOpenedForSession = false;
        reservedForSession = false;
        helloSentForSession = false;
        hintSentForSession = false;
        guestWingmanName = "";
    }

    private String sessionIdOrEmpty() {
        String id = sessionIdSupplier.get();
        return id == null ? "" : id;
    }

    // ---- WINGMAN_HELLO (host side) --------------------------------------------------------------

    /** Host: the guest reported its Wingman panel name. */
    public void onGuestWingmanName(String name) {
        if (roleSupplier.get() != CoopConnectionRole.HOST) {
            return;
        }
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_CHARS) {
            trimmed = trimmed.substring(0, MAX_NAME_CHARS);
        }
        guestWingmanName = trimmed;
        reservedForSession = false; // a changed name invalidates an earlier reservation
        CoopLog.info(CoopWingmanIntegration.class,
                "Coop wingman: guest wingman name is \"" + trimmed + "\"");
    }

    // ---- combat-frame consultation (guest side, via CoopBattleBridge) ---------------------------

    /**
     * The guest's combat frames in a campaign battle while this is true are Wingman join-battle
     * frames, not the guest's own engagement: the bridge must stay a spectator.
     *
     * <p>Gates: this client is the guest, a coop session is live, and this client is connected to
     * a Wingman host. The bridge adds the battle-side conditions (a campaign battle, no local
     * battle, the host's battle known via {@code BATTLE_BEGIN}).
     */
    public boolean guestCombatIsWingmanJoin() {
        return roleSupplier.get() == CoopConnectionRole.GUEST
                && !sessionIdOrEmpty().isEmpty()
                && api.isJoined();
    }

    /** Pure decision helper for the bridge, unit-tested. */
    public static boolean classifyGuestWingmanJoin(boolean localBattleActive,
                                                   boolean remoteBattleActive,
                                                   boolean guestRole,
                                                   boolean wingmanJoined) {
        if (localBattleActive || !remoteBattleActive || !guestRole || !wingmanJoined) {
            return false;
        }
        return true;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Session ended or the pump went away: forget per-session state. */
    public void reset() {
        resetForSession("");
    }
}
