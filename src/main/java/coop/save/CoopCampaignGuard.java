package coop.save;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.config.CoopOptionsRegistry;
import coop.seed.CoopSeedSync;
import coop.util.CoopLog;

import java.util.Calendar;
import java.util.List;

/**
 * The "you loaded the wrong save" check, and the only thing in the mod that reads
 * {@code coop.expectedCampaignId}.
 *
 * <p><b>The problem it solves.</b> An invite names a campaign; a player's saves folder names
 * characters. Before this, a player who loaded the wrong one of two co-op campaigns found out at the
 * seed lock, which speaks in campaign ids, several minutes and one failed connect later. The launcher
 * now writes the campaign id the invite is for into the settings file as
 * {@code coop.expectedCampaignId}; {@code CoopModPlugin} republishes it as a system property with the
 * other launcher-written keys and strikes it out of the file, exactly as it does for
 * {@code coop.adoptCampaignId}. That one-shot handling is the point: an expected id left lying in the
 * file would nag on every unrelated launch afterwards, which is how a warning becomes noise.
 *
 * <p><b>Warn, then proceed. Never block.</b> The player may have loaded that save on purpose - to
 * check something, to keep playing solo, to take a look before the session starts. So this produces a
 * message and nothing else: no refusal, no forced return to the menu, no change to what the mod does
 * next. The seed lock still runs on connect and still has the final say; this is the early, readable
 * version of what it would eventually say.
 *
 * <p><b>The decisions are pure functions.</b> {@link #onLoad} and {@link #onNewGame} take three plain
 * values and return a {@link Notice}; {@link #forGameLoad} is the thin engine-reading wrapper that
 * fetches those three and cannot throw. That split is what lets every branch and every sentence be
 * tested with no game running.
 *
 * <p><b>ASCII only</b>, as with every other coop dialog: the mod's bitmap font renders anything else
 * as a box.
 */
public final class CoopCampaignGuard {

    /** How much of a campaign id is shown; the full one is a UUID and unreadable in a dialog. */
    static final int ID_PREFIX_LENGTH = 8;

    private CoopCampaignGuard() {
    }

    /** What the guard found. */
    public enum Kind {
        /** Nothing to say. */
        NONE,
        /** The loaded save belongs to a different campaign, and the right save is in the index. */
        WRONG_CAMPAIGN,
        /** The loaded save belongs to a different campaign, and no save for the right one is known. */
        WRONG_CAMPAIGN_NO_SAVE,
        /** A new game was started while a save for the invited campaign already exists here. */
        NEW_GAME_ALREADY_IN_FLIGHT
    }

    /**
     * A finding and the words for it.
     *
     * @param kind    which branch fired
     * @param message the full dialog text, or {@code ""} for {@link Kind#NONE}
     */
    public record Notice(Kind kind, String message) {

        public Notice {
            message = message == null ? "" : message;
        }

        /** The common case: no dialog. */
        public static Notice none() {
            return new Notice(Kind.NONE, "");
        }

        public boolean silent() {
            return kind == Kind.NONE;
        }
    }

    /**
     * The load-time check.
     *
     * @param expectedCampaignId the invite's campaign id; blank means the launcher said nothing and
     *                           there is nothing to compare against
     * @param loadedCampaignId   the loaded sector's {@code coop.campaignId}; blank means this save has
     *                           never been seed-locked, which the seed lock itself handles on connect
     * @param index              every save row known on this machine
     */
    public static Notice onLoad(String expectedCampaignId, String loadedCampaignId,
                                List<CoopSaveIndex.Row> index) {
        String expected = text(expectedCampaignId);
        String loaded = text(loadedCampaignId);
        if (expected.isEmpty() || loaded.isEmpty() || expected.equals(loaded)) {
            return Notice.none();
        }
        CoopSaveIndex.Row row = CoopSaveIndex.newestForCampaign(index, expected);
        String head = "This is not the campaign the co-op invite is for."
                + "\n\nThe invite is for campaign " + shortId(expected)
                + "; this save belongs to campaign " + shortId(loaded) + ".";
        String tail = "\n\nYou can keep playing this one. The co-op session will turn the connection"
                + " down until both players are in the same campaign.";
        if (row == null) {
            return new Notice(Kind.WRONG_CAMPAIGN_NO_SAVE, head
                    + "\n\nThere is no co-op save for that campaign on this machine. Start a New Game"
                    + " with the invite's seed to create one." + tail);
        }
        return new Notice(Kind.WRONG_CAMPAIGN, head
                + "\n\nThe save to load is " + describe(row) + "." + tail);
    }

    /**
     * The new-game check.
     *
     * @param expectedCampaignId the invite's campaign id; blank means nothing to check
     * @param index              every save row known on this machine
     * @param adoptConsent       whether {@code coop.adoptCampaignId} was given for this launch, which
     *                           is the one gesture that overrides the seed lock
     */
    public static Notice onNewGame(String expectedCampaignId, List<CoopSaveIndex.Row> index,
                                   boolean adoptConsent) {
        String expected = text(expectedCampaignId);
        if (expected.isEmpty() || adoptConsent) {
            return Notice.none();
        }
        CoopSaveIndex.Row row = CoopSaveIndex.newestForCampaign(index, expected);
        if (row == null) {
            return Notice.none();
        }
        return new Notice(Kind.NEW_GAME_ALREADY_IN_FLIGHT,
                "You already have a save for the campaign this co-op invite is for."
                        + "\n\nThe save is " + describe(row) + "."
                        + "\n\nThe seed lock turns a fresh start down as already in flight, so this"
                        + " new game will not be let into the session. Load that save instead, or"
                        + " start over on purpose with Start over inside the host's campaign on the"
                        + " launcher's Advanced card (it loses the other player's progress).");
    }

    /**
     * The engine-reading wrapper: reads the expected id, the loaded sector's id and the index, and
     * picks the branch. Never throws.
     *
     * @param newGame the flag {@code onGameLoad} is handed; a new game takes the in-flight branch,
     *                because a sector that was generated seconds ago has no campaign id worth
     *                comparing
     */
    public static Notice forGameLoad(boolean newGame) {
        try {
            String expected = expectedCampaignId();
            if (expected.isEmpty()) {
                return Notice.none();
            }
            List<CoopSaveIndex.Row> index = CoopSaveIndex.readRows();
            Notice notice = newGame
                    ? onNewGame(expected, index, adoptConsentGiven())
                    : onLoad(expected, loadedCampaignId(), index);
            if (!notice.silent()) {
                CoopLog.warn(CoopCampaignGuard.class, "Coop expected campaign " + expected
                        + " but this game is " + (newGame ? "a new one" : loadedCampaignId())
                        + " (" + notice.kind() + "); the player has been told and nothing is blocked");
            }
            return notice;
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopCampaignGuard.class,
                    "Coop could not check this save against the invite's campaign id", ex);
            return Notice.none();
        }
    }

    /** The invite's campaign id, published as a system property at application load. */
    public static String expectedCampaignId() {
        try {
            return text(System.getProperty(CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID));
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    private static boolean adoptConsentGiven() {
        try {
            return Boolean.parseBoolean(System.getProperty(CoopOptionsRegistry.ADOPT_CAMPAIGN_ID));
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    private static String loadedCampaignId() {
        try {
            SectorAPI sector = Global.getSector();
            return sector == null ? "" : text(CoopSeedSync.currentCampaignId());
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    /** One row as a player reads it: who, how far, when, and where the folder is. */
    static String describe(CoopSaveIndex.Row row) {
        StringBuilder text = new StringBuilder();
        text.append('"')
                .append(row.characterName().isEmpty() ? "unnamed character" : row.characterName())
                .append('"');
        if (row.level() > 0) {
            text.append(", level ").append(row.level());
        }
        if (!row.gameDate().isEmpty()) {
            text.append(", ").append(row.gameDate());
        }
        if (row.savedAtMillis() > 0L) {
            text.append(", saved ").append(wallClock(row.savedAtMillis()));
        }
        if (row.hasSaveDirName()) {
            text.append(" (folder saves\\").append(row.saveDirName()).append(")");
        }
        return text.toString();
    }

    /**
     * {@code YYYY-MM-DD HH:MM} in local time. Hand-built from {@link Calendar} rather than a
     * formatter: it is four numbers, it must not depend on a locale for a string a player compares
     * with a folder timestamp, and the mod keeps its runtime surface to {@code java.util}.
     */
    static String wallClock(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format("%04d-%02d-%02d %02d:%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE));
    }

    /** The readable head of a campaign id. */
    static String shortId(String campaignId) {
        String id = text(campaignId);
        return id.length() <= ID_PREFIX_LENGTH ? id : id.substring(0, ID_PREFIX_LENGTH) + "...";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
