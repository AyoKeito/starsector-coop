package coop.campaign;

import com.fs.starfarer.api.Global;
import coop.CoopModPlugin;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetStartupConfig;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The guest-side gate on the Galatia Academy story chain: one sector-memory flag, published from the
 * prologue both campaign-entry hooks run, which the replaced rows in {@code data/campaign/rules.csv}
 * read as {@code !$global.coopIsGuest}.
 */
class CoopStoryChainGateTest {

    @AfterEach
    void clearLaunchProperties() {
        System.clearProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.clearProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY);
        System.clearProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY);
        Global.setSector(null);
    }

    @Test
    void aGuestLaunchPublishesTheFlagTheRulesRead() {
        Map<String, Object> memory = new HashMap<>();

        CoopStoryChainGate.publish(sector(memory), CoopConnectionRole.GUEST);

        assertEquals(Boolean.TRUE, memory.get(CoopStoryChainGate.GUEST_MEMORY_FLAG));
    }

    @Test
    void aHostLaunchRemovesTheFlagRatherThanWritingItFalse() {
        // As the key would arrive out of a save written while this client was the guest.
        Map<String, Object> memory = new HashMap<>();
        memory.put(CoopStoryChainGate.GUEST_MEMORY_FLAG, Boolean.TRUE);

        CoopStoryChainGate.publish(sector(memory), CoopConnectionRole.HOST);

        // Removed, not false. The rules engine reads a missing key as false either way, but a stored
        // false is one more thing that has to have been written by the right client.
        assertFalse(memory.containsKey(CoopStoryChainGate.GUEST_MEMORY_FLAG));
    }

    @Test
    void aLaunchWithNoCoopRoleAlsoLeavesTheChainOpen() {
        Map<String, Object> memory = new HashMap<>();
        memory.put(CoopStoryChainGate.GUEST_MEMORY_FLAG, Boolean.TRUE);

        CoopStoryChainGate.publish(sector(memory), CoopConnectionRole.NONE);

        assertFalse(memory.containsKey(CoopStoryChainGate.GUEST_MEMORY_FLAG));
    }

    @Test
    void noSectorAndNoMemoryAreNoOpsRatherThanThrows() {
        CoopStoryChainGate.publish(null, CoopConnectionRole.GUEST);
        CoopStoryChainGate.publish(ApiProxies.sectorWithMemory(null), CoopConnectionRole.GUEST);
    }

    @Test
    void theRoleComesFromTheSameLaunchPropertiesTheTransportReads() {
        System.setProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, "127.0.0.1");
        System.setProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, "7777");

        assertEquals(CoopConnectionRole.GUEST, CoopStoryChainGate.launchRole());
    }

    @Test
    void aLaunchConfigurationThatCannotBeParsedLeavesTheChainOpen() {
        // Host and guest keys together: CoopNetStartupConfig refuses this rather than guessing. A
        // client that cannot work out its own role cannot connect either, so it is nobody's guest and
        // vanilla behaviour is the right answer.
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "7777");
        System.setProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, "127.0.0.1");
        System.setProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, "7777");

        assertEquals(CoopConnectionRole.NONE, CoopStoryChainGate.launchRole());
    }

    @Test
    void theNewGameHookPublishesTheFlagBeforeProcgen() {
        Map<String, Object> memory = new HashMap<>();
        Global.setSector(sector(memory));
        System.setProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, "127.0.0.1");
        System.setProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, "7777");

        new CoopModPlugin().onNewGame();

        assertEquals(Boolean.TRUE, memory.get(CoopStoryChainGate.GUEST_MEMORY_FLAG));
    }

    private static com.fs.starfarer.api.campaign.SectorAPI sector(Map<String, Object> values) {
        return ApiProxies.sectorWithMemory(ApiProxies.memory(values));
    }
}
