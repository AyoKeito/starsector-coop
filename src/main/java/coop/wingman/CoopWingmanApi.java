package coop.wingman;

import java.util.List;

/**
 * Phase 34 seam between the coop mod and The Wingman Project (combat co-op mod, GPLv3).
 *
 * <p>Wingman is an optional dependency: nothing here is linked at compile time. The production
 * implementation ({@link ReflectiveCoopWingmanApi}) reaches {@code wingman.api.WingmanAPI} by
 * reflection, and every call fails closed to the exact behaviour the coop mod has without
 * Wingman installed.
 *
 * <p>Only {@link ReflectiveCoopWingmanApi} mentions Wingman class names, and it is only loaded
 * when {@link CoopWingmanIntegration} is created, so a game without the Wingman mod never loads
 * a class that references it.
 */
public interface CoopWingmanApi {

    /** True when the Wingman mod is present and enabled in this install. */
    boolean wingmanEnabled();

    /** True when this PC is currently connected to a Wingman host session. */
    boolean isJoined();

    /** This player's Wingman name (the one typed in Wingman's CO-OP panel), or {@code ""}. */
    String localName();

    /**
     * Opens the Wingman host session from the campaign map so joiners can connect before the
     * first battle. Idempotent; returns false when the call failed or Wingman is not there.
     */
    boolean openHost();

    /**
     * Host-only per-frame pump: lets joiners come and go and delivers mod messages. Returns the
     * human-readable lines Wingman produced (join/leave notices), joined with newlines; {@code ""}
     * when nothing happened or the call failed.
     */
    String pumpHost();

    /** Host: the names of the joiners connected right now. Empty list anywhere else. */
    List<String> playerNames();

    /**
     * Host: tells Wingman to claim {@code fleetMemberId} for {@code name} when that member is
     * deployed and {@code name} has nothing to fly. {@code null} name clears the reservation.
     */
    void reserveShip(String name, String fleetMemberId);
}
