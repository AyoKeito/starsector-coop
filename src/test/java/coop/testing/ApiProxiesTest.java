package coop.testing;

import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiProxiesTest {

    @Test
    void settingsAnswersEveryColourWithWhite() {
        SettingsAPI settings = ApiProxies.whiteSettings();

        assertSame(Color.WHITE, settings.getColor("textFriendColor"));
        assertSame(Color.WHITE, settings.getColor("anything at all"));
    }

    @Test
    void anUnstubbedPrimitiveGetterReturnsItsZeroRatherThanNull() {
        // A null here would surface as a NullPointerException thrown out of the proxy, which reads
        // like a bug in the code under test instead of a gap in the stub.
        SettingsAPI settings = ApiProxies.whiteSettings();

        assertEquals(0, settings.getBattleSize());
        assertEquals(0f, settings.getScreenWidth());
        assertFalse(settings.isDevMode());
    }

    @Test
    void settingsIsUsableAsAMapKeyAndPrintsItsName() {
        SettingsAPI settings = ApiProxies.whiteSettings();

        assertEquals(settings, settings);
        assertEquals(System.identityHashCode(settings), settings.hashCode());
        assertEquals("Settings", settings.toString());
    }

    @Test
    void theListenerManagerWritesThroughToTheCallersCollection() {
        List<Object> listeners = new ArrayList<>();
        ListenerManagerAPI manager = ApiProxies.listenerManager(listeners);
        Object listener = new Object();

        manager.addListener(listener);
        assertEquals(List.of(listener), listeners, "the test reads registrations out of its own list");

        manager.removeListener(listener);
        assertTrue(listeners.isEmpty(), "and a removal has to be visible there too");
    }

    @Test
    void theListenerManagerPrintsItsName() {
        ListenerManagerAPI manager = ApiProxies.listenerManager(new ArrayList<>());

        assertEquals("ListenerManager", manager.toString());
        assertEquals(manager, manager);
    }
}
