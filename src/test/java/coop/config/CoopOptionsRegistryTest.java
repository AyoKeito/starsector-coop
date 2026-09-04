package coop.config;

import coop.config.CoopOptionsRegistry.Coercion;
import coop.config.CoopOptionsRegistry.Option;
import coop.config.CoopOptionsRegistry.Tier;
import coop.config.CoopOptionsRegistry.Type;
import coop.util.CoopDebug;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopOptionsRegistryTest {

    @Test
    void everyKeyIsUniqueAndNamespaced() {
        Set<String> seen = new HashSet<>();
        for (Option option : CoopOptionsRegistry.options()) {
            assertTrue(seen.add(option.key()), "duplicate key " + option.key());
            assertTrue(option.key().startsWith("coop."), option.key() + " is outside the coop.* namespace");
        }
        assertEquals(seen.size(), CoopOptionsRegistry.options().size());
    }

    @Test
    void everyOptionCarriesItsDocumentation() {
        for (Option option : CoopOptionsRegistry.options()) {
            assertNotNull(option.defaultValue(), option.key() + " has a null default");
            assertFalse(option.owner().isBlank(), option.key() + " has no owning phase");
            assertFalse(option.appliesAt().isBlank(), option.key() + " has no apply boundary");
            assertNotNull(option.boundary(), option.key() + " has no machine-readable boundary");
            assertFalse(option.description().isBlank(), option.key() + " has no description");
        }
    }

    /**
     * Phase 28 milestone 2: the words and the enum must agree, because the page prints one and the
     * policy enforces the other. Only the two spellings that carry behaviour are pinned - the rest
     * of the vocabulary ("next launch", "next new game") is prose the enum deliberately folds into
     * {@link CoopOptionsRegistry.ApplyBoundary#NEXT_CONNECTION}.
     */
    @Test
    void theBoundaryEnumAgreesWithTheWordsForTheKeysThatHaveConsumers() {
        assertEquals(CoopOptionsRegistry.ApplyBoundary.NEXT_SCREEN_TOGGLE,
                CoopOptionsRegistry.require(CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS).boundary());
        assertEquals(CoopOptionsRegistry.ApplyBoundary.NEXT_DROP,
                CoopOptionsRegistry.require(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS).boundary());
        for (Option option : CoopOptionsRegistry.options()) {
            if ("immediately".equals(option.appliesAt())) {
                assertEquals(CoopOptionsRegistry.ApplyBoundary.IMMEDIATE, option.boundary(),
                        option.key() + " says immediately but does not declare IMMEDIATE");
            }
        }
    }

    @Test
    void everyNonDOnlyKeyHasAUsableDefault() {
        for (Option option : CoopOptionsRegistry.fileBackedOptions()) {
            assertFalse(option.dOnly());
            // The default must itself survive validation, or the registry ships a value the store
            // would immediately warn about.
            Coercion coercion = option.coerce(option.defaultValue());
            assertTrue(coercion.clean(), option.key() + ": default " + option.defaultValue()
                    + " does not validate (" + coercion.warning() + ")");
            assertEquals(option.defaultValue(), coercion.value());
            if (!option.allowsEmpty()) {
                assertFalse(option.defaultValue().isEmpty(),
                        option.key() + " has no default but is not marked allowsEmpty");
            }
        }
    }

    @Test
    void theDOnlySetIsExactlyTheOneShotGesturesAndDebugHatches() {
        Set<String> dOnly = new HashSet<>();
        for (Option option : CoopOptionsRegistry.options()) {
            if (option.dOnly()) {
                dOnly.add(option.key());
            }
        }
        assertEquals(Set.of(
                "coop.adoptCampaignId",
                "coop.expectedCampaignId",
                "coop.allowGameVersionMismatch",
                "coop.newGameSeed",
                "coop.sectorSize",
                "coop.sectorAge",
                "coop.fullFidelityGuestSystem",
                "coop.ff.disable",
                "coop.clock.disable",
                "coop.debug.diagnostics",
                "coop.debug.bridge",
                "coop.debug.wiretap",
                "coop.debug.wiretapSample",
                "coop.debug.frameProfile",
                "coop.debug.interactionDelayMs"), dOnly);
    }

    /**
     * The bound on the latency lever lived in three places at once: {@code CoopDebug} clamped at
     * 60 s, the registry said "unbounded", and the launcher's spinner therefore offered its
     * unbounded-key fallback of 65535. A player could pick 65535 and get 60000 with no sign of it.
     * The registry now names {@code CoopDebug}'s constant, so there is one number to change.
     */
    @Test
    void theLatencyLeverIsBoundedByTheConstantTheGameClampsWith() {
        Option option = CoopOptionsRegistry.require("coop.debug.interactionDelayMs");

        assertEquals(CoopDebug.MAX_INTERACTION_DELAY_MILLIS, option.max());
        assertEquals(0, option.min());

        Coercion tooBig = option.coerce("70000");
        assertEquals(String.valueOf(CoopDebug.MAX_INTERACTION_DELAY_MILLIS), tooBig.value());
        assertFalse(tooBig.clean());
        assertTrue(tooBig.warning().contains(String.valueOf(CoopDebug.MAX_INTERACTION_DELAY_MILLIS)),
                tooBig.warning());

        assertTrue(option.coerce(String.valueOf(CoopDebug.MAX_INTERACTION_DELAY_MILLIS)).clean());
    }

    /** The bridge key is a TCP port, and 0 is its "no socket ever" value rather than a bad one. */
    @Test
    void theBridgePortIsBoundedLikeAPort() {
        Option option = CoopOptionsRegistry.require("coop.debug.bridge");

        assertEquals(0, option.min());
        assertEquals(65535, option.max());
        assertEquals("65535", option.coerce("70000").value());
        assertTrue(option.coerce("0").clean());
    }

    /** The wiretap interval has no useful ceiling, but CoopWiretap floors it at 1, so the registry does too. */
    @Test
    void theWiretapSampleIsFlooredWhereTheWiretapFloorsIt() {
        Option option = CoopOptionsRegistry.require("coop.debug.wiretapSample");

        assertEquals(1, option.min());
        assertEquals(Integer.MAX_VALUE, option.max());
        assertEquals("1", option.coerce("0").value());
    }

    @Test
    void theInventoryCoversThePlansTable() {
        for (String key : List.of(
                "coop.hostPort", "coop.connectHost", "coop.connectPort", "coop.portMapping",
                "coop.password", "coop.playerName",
                "coop.maxGuests", "coop.reconnectGraceSeconds", "coop.allowGuestPause",
                "coop.pauseOnGuestScreens", "coop.allowMidSessionJoin", "coop.lootSplit",
                "coop.incomeSplit", "coop.guestColonizationConsent",
                "coop.hud.disable", "coop.hudCorner", "coop.feedVerbosity", "coop.partnerColor")) {
            assertTrue(CoopOptionsRegistry.isRegistered(key), key + " is missing from the registry");
        }
    }

    @Test
    void tiersMatchThePlan() {
        assertEquals(Tier.LAUNCH, CoopOptionsRegistry.require("coop.hostPort").tier());
        assertEquals(Tier.POLICY, CoopOptionsRegistry.require("coop.pauseOnGuestScreens").tier());
        assertEquals(Tier.CLIENT, CoopOptionsRegistry.require("coop.hudCorner").tier());
        assertFalse(CoopOptionsRegistry.byTier(Tier.POLICY).isEmpty());
        assertEquals(CoopOptionsRegistry.options().size(),
                CoopOptionsRegistry.byTier(Tier.LAUNCH).size()
                        + CoopOptionsRegistry.byTier(Tier.POLICY).size()
                        + CoopOptionsRegistry.byTier(Tier.CLIENT).size());
    }

    @Test
    void unknownKeysAreRejectedLoudly() {
        assertNull(CoopOptionsRegistry.option("coop.notAThing"));
        assertFalse(CoopOptionsRegistry.isRegistered("coop.notAThing"));
        assertThrows(IllegalArgumentException.class, () -> CoopOptionsRegistry.require("coop.notAThing"));
    }

    @Test
    void boolValuesAreValidatedCaseInsensitively() {
        Option option = CoopOptionsRegistry.require("coop.pauseOnGuestScreens");
        assertEquals("false", option.coerce("FALSE").value());
        assertEquals("true", option.coerce("  true ").value());

        Coercion bad = option.coerce("yes");
        assertEquals("true", bad.value());
        assertFalse(bad.clean());
        assertTrue(bad.warning().contains("coop.pauseOnGuestScreens=yes"));
    }

    @Test
    void intValuesClampToTheirBounds() {
        Option grace = CoopOptionsRegistry.require("coop.reconnectGraceSeconds");
        assertEquals("0", grace.coerce("0").value());
        assertEquals("3600", grace.coerce("3600").value());

        Coercion high = grace.coerce("99999");
        assertEquals("3600", high.value());
        assertTrue(high.warning().contains("clamped"));

        Coercion low = grace.coerce("-5");
        assertEquals("0", low.value());
        assertTrue(low.warning().contains("clamped"));

        Coercion garbage = grace.coerce("soon");
        assertEquals("60", garbage.value());
        assertTrue(garbage.warning().contains("not an integer"));
    }

    @Test
    void maxGuestsIsPinnedToOneForV1() {
        Option option = CoopOptionsRegistry.require("coop.maxGuests");
        assertEquals(Type.INT, option.type());
        assertEquals(1, option.min());
        assertEquals(1, option.max());
        assertEquals("1", option.coerce("3").value());
    }

    @Test
    void enumValuesAreCanonicalisedAndRejected() {
        Option corner = CoopOptionsRegistry.require("coop.hudCorner");
        assertEquals("BL", corner.coerce("bl").value());
        Coercion bad = corner.coerce("middle");
        assertEquals("TR", bad.value());
        assertTrue(bad.warning().contains("TR/TL/BR/BL"));

        Option mapping = CoopOptionsRegistry.require("coop.portMapping");
        assertEquals("off", mapping.coerce("OFF").value());
        assertEquals("auto", mapping.coerce("").value());
    }

    @Test
    void blankMeansUnsetNotInvalid() {
        Option password = CoopOptionsRegistry.require("coop.password");
        assertTrue(password.allowsEmpty());
        Coercion coercion = password.coerce("   ");
        assertEquals("", coercion.value());
        assertTrue(coercion.clean());

        Option grace = CoopOptionsRegistry.require("coop.reconnectGraceSeconds");
        assertFalse(grace.allowsEmpty());
        assertEquals("60", grace.coerce("  ").value());
    }

    @Test
    void nullCoercesToTheDefaultWithoutComplaint() {
        for (Option option : CoopOptionsRegistry.options()) {
            Coercion coercion = option.coerce(null);
            assertEquals(option.defaultValue(), coercion.value());
            assertTrue(coercion.clean());
        }
    }

    @Test
    void anEnumOptionMustDeclareItsValues() {
        assertThrows(IllegalArgumentException.class, () -> new Option("coop.x", Type.ENUM,
                Tier.CLIENT, "a", false, false, 0, 0, List.of(), "test", "immediately",
                CoopOptionsRegistry.ApplyBoundary.IMMEDIATE, "doc"));
    }

    @Test
    void theCorrectnessListIsCarriedWithReasons() {
        assertFalse(CoopOptionsRegistry.notConfigurable().isEmpty());
        for (CoopOptionsRegistry.NotConfigurable entry : CoopOptionsRegistry.notConfigurable()) {
            assertFalse(entry.name().isBlank());
            assertFalse(entry.reason().isBlank());
            // Nothing on the list may also be an option: that is the erosion this guards against.
            assertFalse(CoopOptionsRegistry.isRegistered(entry.name()));
        }
    }

    @Test
    void constraintTextDescribesEachType() {
        assertEquals("true|false", CoopOptionsRegistry.require("coop.allowGuestPause").constraintText());
        assertEquals("integer 0..3600",
                CoopOptionsRegistry.require("coop.reconnectGraceSeconds").constraintText());
        assertEquals("TR|TL|BR|BL", CoopOptionsRegistry.require("coop.hudCorner").constraintText());
        assertEquals("text", CoopOptionsRegistry.require("coop.playerName").constraintText());
    }
}
