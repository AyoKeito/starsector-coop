package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.awt.Color;

/**
 * One-line notices in the campaign message feed (Phase 20.6): the connection events a player has to
 * be told about — the state stream falling back to TCP, the link going bad, and both recovering.
 *
 * <p>The HUD line ({@link CoopLinkHud}) is a live readout you have to be looking at; the feed is what
 * catches your eye when something changes. Deliberately tiny and total: no sector, no campaign UI, or
 * any throwable at all, and nothing happens. Connection telemetry must never be able to take a frame
 * down, and every caller here is on a rate limiter, so a flapping link cannot spam the feed either.
 */
public final class CoopFeed {

    private CoopFeed() {
    }

    /** Posts one message, or silently does nothing when there is no campaign UI to post it to. */
    public static void post(String text, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui == null) {
                return;
            }
            if (color == null) {
                ui.addMessage(text);
            } else {
                ui.addMessage(text, color);
            }
        } catch (Throwable ex) {
            CoopLog.warn(CoopFeed.class, "Coop could not post a campaign feed message", ex);
        }
    }
}
