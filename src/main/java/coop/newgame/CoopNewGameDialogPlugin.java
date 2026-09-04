package coop.newgame;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.impl.campaign.NewGameDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetStartupConfig;
import coop.seed.CoopSectorProcGen;
import coop.config.CoopOptionsStore;
import coop.util.CoopLog;

/**
 * Guest-aware New Game dialog. Registered as {@code newGameDialogPlugin} in the mod's
 * data/config/settings.json, which replaces the vanilla entry by key.
 *
 * <p><b>Vanilla is the default.</b> Everything below is gated on a coop launch (the host or guest
 * JVM properties read by {@link CoopNetStartupConfig}). Without them this class is a pass-through to
 * {@link NewGameDialogPluginImpl}, and every engine call it does add is wrapped so a failure logs
 * once and falls through to vanilla behaviour rather than breaking character creation.
 *
 * <p><b>Why the pinning is repeated.</b> The engine's new-game options panel
 * ({@code visual.showNewGameOptionsPanel}) is one atomic widget: name, portrait, gender, seed text,
 * sector size and star age, with no per-field API to disable. It writes seed, seedString, sectorSize
 * and sectorAge straight onto the {@link CharacterCreationData} when its own state changes, so the
 * only way to make the coop values stick is to overwrite them after the panel has had its say. This
 * plugin therefore pins in {@code init} (right after the panel opens), on every {@code advance}
 * frame while the panel is up, and once more in {@code optionSelected} for Continue before the
 * {@code BeginNewGameCreation} rule fires. Procgen reads the data object, so the last write wins.
 *
 * <p><b>Where the join cue goes.</b> Not in the text panel: {@code super.init} hides it, and showing
 * it again (tried 2026-09-02) pushes the whole options panel to the right while the paragraph still
 * does not render. The cue is the Continue option itself: a short suffix in its label naming the host
 * and port, with the full explanation as the option's tooltip. Layout stays vanilla.
 */
public class CoopNewGameDialogPlugin extends NewGameDialogPluginImpl {

    /**
     * {@code NewGameDialogPluginImpl.OptionId.CONTINUE_CHOICES}. The enum is private to the impl, so
     * the Continue option is recognised by constant name off the {@link Enum} instance itself -- no
     * java.lang.reflect, which the mod classloader blocks outright.
     */
    private static final String CONTINUE_OPTION_NAME = "CONTINUE_CHOICES";

    private static final String CHARACTER_DATA_KEY = "$characterData";

    private CoopNetStartupConfig config;
    private boolean coopLaunch;
    private CharacterCreationData data;
    private CoopNewGameChoices.Choices choices;
    private String lastPinLogLine = "";
    private boolean pinFailureLogged;

    @Override
    public void init(InteractionDialogAPI dialog) {
        resetCoopState();

        config = readStartupConfig();
        coopLaunch = CoopNewGameChoices.isCoopLaunch(config);

        super.init(dialog);

        if (!coopLaunch) {
            return;
        }

        data = readCharacterCreationData(dialog);
        if (data == null) {
            CoopLog.warn(CoopNewGameDialogPlugin.class,
                    "Coop launch detected but the new game dialog has no " + CHARACTER_DATA_KEY
                            + "; leaving the vanilla panel unpinned");
            coopLaunch = false;
            return;
        }

        choices = resolveChoices(data);
        pin("init");
        showBanner(dialog);
    }

    @Override
    public void advance(float amount) {
        super.advance(amount);
        if (coopLaunch) {
            // Silent unless a value actually changed -- the panel can write back at any time.
            pin("advance", false);
        }
    }

    @Override
    public void optionSelected(String text, Object optionData) {
        if (coopLaunch && isContinueOption(optionData)) {
            pin("continue");
        }
        super.optionSelected(text, optionData);
    }

    private void resetCoopState() {
        // The engine instantiates settings plugins once and reuses the instance across dialogs.
        // A dialog that is opened and abandoned must not leave its settings behind for the next one.
        CoopWorldSettings.clearPending();
        config = null;
        coopLaunch = false;
        data = null;
        choices = null;
        lastPinLogLine = "";
        pinFailureLogged = false;
    }

    private static boolean isContinueOption(Object optionData) {
        return optionData instanceof Enum<?> option && CONTINUE_OPTION_NAME.equals(option.name());
    }

    private static CoopNetStartupConfig readStartupConfig() {
        try {
            return CoopNetStartupConfig.fromSystemProperties();
        } catch (Throwable ex) {
            CoopLog.warn(CoopNewGameDialogPlugin.class,
                    "Unusable coop startup properties; new game dialog stays vanilla", ex);
            return null;
        }
    }

    private static CharacterCreationData readCharacterCreationData(InteractionDialogAPI dialog) {
        try {
            SectorEntityToken entity = dialog.getInteractionTarget();
            if (entity == null) {
                return null;
            }
            MemoryAPI memory = entity.getMemoryWithoutUpdate();
            if (memory == null) {
                return null;
            }
            Object raw = memory.get(CHARACTER_DATA_KEY);
            return raw instanceof CharacterCreationData characterData ? characterData : null;
        } catch (Throwable ex) {
            CoopLog.warn(CoopNewGameDialogPlugin.class,
                    "Unable to read " + CHARACTER_DATA_KEY + " from the new game dialog", ex);
            return null;
        }
    }

    private CoopNewGameChoices.Choices resolveChoices(CharacterCreationData characterData) {
        String panelSize = null;
        StarAge panelAge = null;
        try {
            panelSize = characterData.getSectorSize();
            panelAge = characterData.getSectorAge();
        } catch (Throwable ex) {
            CoopLog.warn(CoopNewGameDialogPlugin.class,
                    "Unable to read the new game panel's default world settings", ex);
        }
        CoopLog.info(CoopNewGameDialogPlugin.class,
                "Coop new game panel defaults sectorSize=" + panelSize + " sectorAge=" + panelAge);

        // Through the store, not System.getProperty: -D still wins, but the Phase 31 launcher's
        // only channel is saves/common/coop_options.json.data, and reading the property directly
        // made the file layer invisible for exactly these two keys.
        CoopOptionsStore store = CoopOptionsStore.system();
        CoopNewGameChoices.Choices resolved = CoopNewGameChoices.resolve(
                store.rawOneShot(CoopNewGameChoices.SECTOR_SIZE_PROPERTY),
                store.rawOneShot(CoopNewGameChoices.SECTOR_AGE_PROPERTY),
                panelSize,
                panelAge);
        for (String warning : resolved.warnings()) {
            CoopLog.warn(CoopNewGameDialogPlugin.class, warning);
        }
        return resolved;
    }

    private void pin(String stage) {
        pin(stage, true);
    }

    /**
     * @param announce false for the per-frame caller: re-applying the seed must happen every frame,
     *                 but saying so in the log every frame is what the "silent" in
     *                 {@link #advance(float)} was always supposed to mean
     */
    private void pin(String stage, boolean announce) {
        if (data == null || choices == null) {
            return;
        }
        try {
            // Seed derivation is CoopSectorProcGen's, reused rather than repeated, so the dialog and
            // procgen can never disagree about what -Dcoop.newGameSeed means.
            CoopSectorProcGen.applyCoopSeedIfPresent(data, announce);
            data.setSectorSize(choices.sectorSize());
            data.setSectorAge(choices.sectorAge());
            // The sector about to be generated will not remember either of these, and no getter
            // brings them back: this is the one moment they are known. CoopModPlugin moves them onto
            // the sector once procgen has made one.
            CoopWorldSettings.rememberPending(choices.sectorSize(), choices.sectorAge());

            String line = CoopNewGameChoices.pinnedLogLine(
                    seedStringOf(data), choices.sectorSize(), choices.sectorAge(), role());
            if (!line.equals(lastPinLogLine)) {
                lastPinLogLine = line;
                CoopLog.info(CoopNewGameDialogPlugin.class, line);
            }
        } catch (Throwable ex) {
            if (!pinFailureLogged) {
                pinFailureLogged = true;
                CoopLog.warn(CoopNewGameDialogPlugin.class,
                        "Unable to pin coop new game world settings (stage " + stage + ")", ex);
            }
        }
    }

    private void showBanner(InteractionDialogAPI dialog) {
        String banner = CoopNewGameChoices.bannerText(
                role(),
                config == null ? "" : config.host(),
                config == null ? 0 : config.port(),
                seedStringOf(data));
        if (banner.isEmpty()) {
            return;
        }
        try {
            // The Continue option is the one control the player must use, so the coop cue lives on
            // it: a short suffix in the label and the full banner as its tooltip. The option's data
            // is the impl's private OptionId enum; Class.forName plus Enum.valueOf reaches the constant
            // without java.lang.reflect (the same route Phase 7b uses for the fast-forward class).
            Object continueId = continueOptionId();
            OptionPanelAPI options = dialog.getOptionPanel();
            if (options == null || !options.hasOption(continueId)) {
                CoopLog.warn(CoopNewGameDialogPlugin.class,
                        "Coop new game: Continue option not found; no join cue shown");
                return;
            }
            options.setOptionText(continueLabel(), continueId);
            options.setTooltip(continueId, banner);
        } catch (Throwable ex) {
            CoopLog.warn(CoopNewGameDialogPlugin.class, "Unable to show the coop new game banner", ex);
        }
    }

    private String continueLabel() {
        String host = config == null ? "" : config.host();
        int port = config == null ? 0 : config.port();
        return role() == CoopConnectionRole.GUEST
                ? "Continue (join coop host " + host + ":" + port + ")"
                : "Continue (host coop game on port " + port + ")";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object continueOptionId() throws Throwable {
        Class<?> optionIdClass = Class.forName(
                NewGameDialogPluginImpl.class.getName() + "$OptionId", true,
                NewGameDialogPluginImpl.class.getClassLoader());
        return Enum.valueOf((Class) optionIdClass, CONTINUE_OPTION_NAME);
    }

    private CoopConnectionRole role() {
        return config == null ? CoopConnectionRole.NONE : config.role();
    }

    private static String seedStringOf(CharacterCreationData characterData) {
        if (characterData == null) {
            return "";
        }
        try {
            String seedString = characterData.getSeedString();
            return seedString == null ? "" : seedString.trim();
        } catch (Throwable ex) {
            return "";
        }
    }
}
