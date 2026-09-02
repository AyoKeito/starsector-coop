package coop.newgame;

import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetStartupConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNewGameChoicesTest {

    @Test
    void absentPropertiesKeepThePanelDefaults() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve(null, null, "normal", StarAge.OLD);

        assertEquals("normal", choices.sectorSize());
        assertEquals(StarAge.OLD, choices.sectorAge());
        assertTrue(choices.warnings().isEmpty());
    }

    @Test
    void blankPropertiesKeepThePanelDefaults() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve("   ", "\t", "small", StarAge.YOUNG);

        assertEquals("small", choices.sectorSize());
        assertEquals(StarAge.YOUNG, choices.sectorAge());
        assertTrue(choices.warnings().isEmpty());
    }

    @Test
    void missingPanelDefaultsFallBackToTheEngineInitialValues() {
        // CharacterCreationData inits sectorSize to "normal" and leaves sectorAge null, which
        // SectorProcGen reads as ANY.
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve(null, null, null, null);

        assertEquals("normal", choices.sectorSize());
        assertEquals(StarAge.ANY, choices.sectorAge());
        assertTrue(choices.warnings().isEmpty());
    }

    @Test
    void propertiesOverrideThePanelDefaults() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve("small", "young", "normal", StarAge.ANY);

        assertEquals("small", choices.sectorSize());
        assertEquals(StarAge.YOUNG, choices.sectorAge());
        assertTrue(choices.warnings().isEmpty());
    }

    @Test
    void propertyParsingIsCaseInsensitive() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve(" SMALL ", " Average ", "normal", StarAge.ANY);

        assertEquals("small", choices.sectorSize());
        assertEquals(StarAge.AVERAGE, choices.sectorAge());
        assertTrue(choices.warnings().isEmpty());
    }

    @Test
    void mixedIsAcceptedAsTheAnyStarAgeLabel() {
        assertEquals(StarAge.ANY, CoopNewGameChoices.parseStarAge("mixed"));
        assertEquals(StarAge.ANY, CoopNewGameChoices.parseStarAge("ANY"));
        assertNull(CoopNewGameChoices.parseStarAge("ancient"));
        assertNull(CoopNewGameChoices.parseStarAge(null));
    }

    @Test
    void badSectorSizeFallsBackToTheDefaultAndWarns() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve("huge", null, "small", StarAge.OLD);

        assertEquals("small", choices.sectorSize());
        assertEquals(StarAge.OLD, choices.sectorAge());
        assertEquals(1, choices.warnings().size());
        String warning = choices.warnings().get(0);
        assertTrue(warning.contains("coop.sectorSize=huge"), warning);
        assertTrue(warning.contains("small"), warning);
    }

    @Test
    void badStarAgeFallsBackToTheDefaultAndWarns() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve(null, "ancient", "normal", StarAge.AVERAGE);

        assertEquals("normal", choices.sectorSize());
        assertEquals(StarAge.AVERAGE, choices.sectorAge());
        assertEquals(1, choices.warnings().size());
        String warning = choices.warnings().get(0);
        assertTrue(warning.contains("coop.sectorAge=ancient"), warning);
        assertTrue(warning.contains("AVERAGE"), warning);
    }

    @Test
    void bothBadValuesWarnIndependently() {
        CoopNewGameChoices.Choices choices = CoopNewGameChoices.resolve("enormous", "prehistoric", "normal", null);

        assertEquals("normal", choices.sectorSize());
        assertEquals(StarAge.ANY, choices.sectorAge());
        assertEquals(2, choices.warnings().size());
    }

    @Test
    void guestBannerNamesTheHostAndSaysTheFieldsAreLocked() {
        String banner = CoopNewGameChoices.bannerText(CoopConnectionRole.GUEST, "10.0.0.5", 7777, "coop-seed");

        assertEquals("Joining coop host 10.0.0.5:7777. Seed and world settings come from the host;"
                + " the seed, sector size and star age fields below are locked.", banner);
    }

    @Test
    void hostBannerNamesThePortAndTheSeed() {
        String banner = CoopNewGameChoices.bannerText(CoopConnectionRole.HOST, "", 7777, "coop-seed");

        assertEquals("Hosting a coop game on port 7777. Seed coop-seed;"
                + " sector size and star age are pinned so the guest can generate the same sector.", banner);
    }

    @Test
    void bannerIsEmptyWithoutACoopRole() {
        assertEquals("", CoopNewGameChoices.bannerText(CoopConnectionRole.NONE, "host", 1, "seed"));
        assertEquals("", CoopNewGameChoices.bannerText(null, "host", 1, "seed"));
    }

    @Test
    void pinnedLogLineCarriesEveryPinnedValueAndTheRole() {
        assertEquals("Coop new game pinned seed=coop-seed sectorSize=small sectorAge=YOUNG (role GUEST)",
                CoopNewGameChoices.pinnedLogLine("coop-seed", "small", StarAge.YOUNG, CoopConnectionRole.GUEST));
        assertEquals("Coop new game pinned seed=coop-seed sectorSize=normal sectorAge=ANY (role HOST)",
                CoopNewGameChoices.pinnedLogLine(" coop-seed ", "normal", StarAge.ANY, CoopConnectionRole.HOST));
    }

    @Test
    void hostAndGuestLaunchesAreCoopLaunches() {
        assertTrue(CoopNewGameChoices.isCoopLaunch(configFrom(CoopNetStartupConfig.HOST_PORT_PROPERTY, "7777")));

        Properties guest = new Properties();
        guest.setProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, "127.0.0.1");
        guest.setProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, "7777");
        assertTrue(CoopNewGameChoices.isCoopLaunch(CoopNetStartupConfig.from(guest)));
    }

    @Test
    void soloAndSeedOnlyLaunchesAreNotCoopLaunches() {
        assertFalse(CoopNewGameChoices.isCoopLaunch(null));
        assertFalse(CoopNewGameChoices.isCoopLaunch(CoopNetStartupConfig.from(new Properties())));
        // A bare seed property is not a session; the dialog must stay vanilla.
        assertFalse(CoopNewGameChoices.isCoopLaunch(
                configFrom(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, "coop-seed")));
    }

    private static CoopNetStartupConfig configFrom(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return CoopNetStartupConfig.from(properties);
    }
}
