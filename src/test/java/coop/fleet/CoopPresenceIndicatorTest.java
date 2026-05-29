package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopPresenceIndicatorTest {
    @Test
    void presenceLabelUsesUsernameTrimmed() {
        assertEquals("Alice", CoopPresenceIndicator.presenceLabel("  Alice  "));
    }

    @Test
    void presenceLabelFallsBackWhenUsernameMissing() {
        assertEquals("Coop Player", CoopPresenceIndicator.presenceLabel(null));
        assertEquals("Coop Player", CoopPresenceIndicator.presenceLabel("   "));
    }

    @Test
    void presenceFactionIdUsesLocalPlayerFactionForOwnColor() {
        assertEquals("tritachyon", CoopPresenceIndicator.presenceFactionId("  tritachyon "));
    }

    @Test
    void presenceFactionIdFallsBackToPlayerFaction() {
        assertEquals("player", CoopPresenceIndicator.presenceFactionId(null));
        assertEquals("player", CoopPresenceIndicator.presenceFactionId(""));
    }
}
