package coop.launcher;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The model behind the host's <b>Campaign</b> drop-down and the one line of advice under both
 * cards. No Swing in here on purpose: what the player is told about which save to load is the part
 * worth testing, and a window is a bad place to test it from.
 *
 * <p><b>The drop-down.</b> "New campaign" is always first, because a first-time host has nothing
 * else. After it comes one entry per campaign this player has a save for, newest campaign first,
 * each naming that campaign's newest surviving save. A campaign this player has only ever been the
 * guest in is left out: hosting it would put the wrong side in charge of the world.
 *
 * <p><b>The advice line.</b> The launcher cannot load a save for anybody - the game's own menu does
 * that - so the whole job here is to name the right slot precisely enough that it cannot be
 * confused with the one next to it: character, level, save time, folder. When there is no save for
 * the campaign the answer is the other half of the co-op start, which is a new game on the shared
 * seed.
 */
public final class CoopCampaignPicker {

    /** What the picker calls a brand new campaign. Its campaign id is {@code ""}. */
    public static final String NEW_CAMPAIGN_ID = "";

    private CoopCampaignPicker() {
    }

    /**
     * One line of the drop-down.
     *
     * @param campaignId {@code ""} for the new-campaign entry
     * @param label      what the drop-down shows
     * @param folderName the save folder this entry loads, {@code ""} for a new campaign
     */
    public record Entry(String campaignId, String label, String folderName) {

        public Entry {
            campaignId = text(campaignId);
            label = text(label);
            folderName = text(folderName);
        }

        public boolean newCampaign() {
            return campaignId.isEmpty();
        }

        /** {@link javax.swing.JComboBox} renders its items with this. */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The drop-down contents for a host: the new-campaign entry, then one entry per campaign with a
     * save on this machine.
     *
     * @param seed  the seed in the host's field; shown on the new-campaign entry so the two players
     *              can check they match before either of them presses anything
     */
    public static List<Entry> entries(String seed, CoopSaveIndexReader.Index index, ZoneId zone) {
        List<Entry> entries = new ArrayList<>();
        entries.add(newCampaignEntry(seed));
        if (index != null && index.ok()) {
            for (CoopSaveIndexReader.Save save : index.newestPerHostCampaign()) {
                entries.add(new Entry(save.campaignId(), save.label(zone), save.saveDirName()));
            }
        }
        return List.copyOf(entries);
    }

    /** The first line of the drop-down on its own, for a seed that changed under it. */
    public static Entry newCampaignEntry(String seed) {
        String trimmed = text(seed);
        return new Entry(NEW_CAMPAIGN_ID,
                trimmed.isEmpty() ? "New campaign" : "New campaign (seed " + trimmed + ")", "");
    }

    /**
     * True when the seed, sector size and star age fields still mean anything. They are new-game
     * settings: a save already has its sector, and letting somebody edit them next to a loaded
     * campaign says they can change it.
     */
    public static boolean worldControlsEnabled(Entry selected) {
        return selected == null || selected.newCampaign();
    }

    /** The folder line under the picker, or {@code ""} when a new campaign is selected. */
    public static String folderLine(Entry selected) {
        if (selected == null || selected.newCampaign()) {
            return "";
        }
        return "folder " + selected.folderName();
    }

    /**
     * The single line of advice for a campaign id: which save to load, or that there is none and a
     * new game is the way in.
     *
     * @param campaignId the campaign the invite or the picker names; {@code ""} for a new campaign
     */
    public static String hint(String campaignId, CoopSaveIndexReader.Index index, ZoneId zone) {
        String wanted = text(campaignId);
        if (wanted.isEmpty()) {
            return "Start a New Game with the seed above.";
        }
        if (index == null) {
            return noSave();
        }
        switch (index.status()) {
            case ABSENT:
                // Not an error: an install where nobody has saved a co-op campaign yet looks
                // exactly like this, and that is the ordinary first session.
                return "No co-op saves have been recorded on this machine yet: start a New Game"
                        + " with the seed above.";
            case UNREADABLE:
                return "The co-op save list (" + CoopSaveIndexReader.INDEX_DISPLAY_PATH + ") could"
                        + " not be read, so the launcher cannot name a save: " + index.problem()
                        + ". Launching still works.";
            case TOO_NEW:
                return "The co-op save list (" + CoopSaveIndexReader.INDEX_DISPLAY_PATH + ") was"
                        + " written by a newer version of the mod, so the launcher cannot name a"
                        + " save: " + index.problem() + ". Launching still works.";
            case OK:
            default:
                break;
        }
        CoopSaveIndexReader.Save save = index.newestFor(wanted);
        if (save == null) {
            return noSave();
        }
        return "Load the save \"" + save.characterName() + "\", level " + save.level()
                + ", saved " + save.savedLocal(zone) + " (folder " + save.saveDirName() + ").";
    }

    private static String noSave() {
        return "No co-op save for this campaign on this machine: start a New Game with the seed"
                + " above.";
    }

    /**
     * The entry in {@code entries} for {@code campaignId}, or the new-campaign entry when there is
     * none. Used to keep the player's pick across a refresh, and to fall back gracefully when the
     * campaign they had picked has just had its last save pruned.
     */
    public static Entry select(List<Entry> entries, String campaignId) {
        String wanted = text(campaignId);
        for (Entry entry : entries) {
            if (entry.campaignId().equals(wanted)) {
                return entry;
            }
        }
        return entries.isEmpty() ? newCampaignEntry("") : entries.get(0);
    }

    /**
     * Why a campaign id cannot travel in an invite, or {@code null} when it can. Kept next to the
     * picker because both ends of the wire have to agree on it; {@link CoopInvite} calls this.
     */
    static String campaignIdProblem(String campaignId) {
        String trimmed = text(campaignId);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 128) {
            return "it is " + trimmed.length() + " characters long, and a campaign id is a UUID";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char character = trimmed.charAt(i);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_' || character == '.';
            if (!allowed) {
                return "\"" + character + "\" is not something a campaign id contains";
            }
        }
        return null;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
