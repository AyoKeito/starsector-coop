package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the two mirror-tag literals. Every class that recognises a mirror fleet now reads these
 * constants rather than its own copy of the string, so a change here is the one deliberate place a
 * future edit to the wire format has to touch — this test exists so that edit is never silent.
 */
class CoopMirrorTagsTest {

    @Test
    void playerMirrorTagIsThePinnedLiteral() {
        assertEquals("$coopMirrorFleet", CoopMirrorTags.PLAYER_MIRROR_TAG);
    }

    @Test
    void npcMirrorTagIsThePinnedLiteral() {
        assertEquals("$coopNpcFleetId", CoopMirrorTags.NPC_MIRROR_TAG);
    }

    @Test
    void allyAllowedFlagIsThePinnedLiteral() {
        // Phase 33. Written by the mirror's posture apply and read by the threat watcher's battle
        // eject and the customs shield restore, neither of which has anything but the fleet.
        assertEquals("$coopAllyAllowed", CoopMirrorTags.ALLY_ALLOWED_FLAG);
    }
}
