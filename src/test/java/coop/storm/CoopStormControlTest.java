package coop.storm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoopStormControlTest {
    private static final String ID = "77032526-2d55-48e2-a9a0-7b866330ab20";

    @Test void typedFactoriesCarryOnlyNativeClearingAndWindowParameters() {
        var off = CoopStormControl.tileOff(ID, 3, -200, 100, 750, 0.75f);
        assertEquals(CoopStormControl.Action.TILE_OFF, off.action());
        assertEquals(-1, off.minSignalDays()); assertEquals(-1, off.maxSignalDays());
        assertEquals(0, off.extraRadius()); assertEquals(1, off.timeScale());
        var window = CoopStormControl.surgeWindow(ID, 3, -200, 100, 16_000, 5);
        assertEquals(CoopStormControl.Action.SURGE_WINDOW, window.action());
        assertEquals(0, window.radius()); assertEquals(0, window.waitDays());
    }

    @Test void canonicalIdentityBoundsAndFiniteSpatialValuesAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.tileOff("1-1-1-1-1", 1, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.tileOff(ID, -1, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.tileOff(ID, 1, Float.NaN, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.tileOff(ID, 1, 0, 0, CoopStormControl.MAX_RADIUS + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.tileOff(ID, 1, 0, 0, 1, 1.01f));
    }

    @Test void arbitrarySignalAndNonNativeWindowMultipliersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 1, CoopStormControl.Action.TILE_OFF,
                0, 0, 1, 0, 0.1f, 0.2f, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeWindow(ID, 1, 0, 0, 16_001, 5));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeWindow(ID, 1, 0, 0, 16_000, 6));
    }

    @Test void endedNativeWindowHasOneExactClearShape() {
        var ended = CoopStormControl.surgeWindow(ID, 1, 0, 0, 0, 1);
        assertEquals(0, ended.extraRadius()); assertEquals(1, ended.timeScale());
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeWindow(ID, 1, 0, 0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeWindow(ID, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeWindow(ID, 1, 0, 0, -1, 1));
    }

    @Test void sourceAdvertisementCannotModifyWeatherOrAcceleratedCoverage() {
        var source = CoopStormControl.surgeSource(ID, 7, -200, 100);
        assertEquals(CoopStormControl.Action.SURGE_SOURCE, source.action());
        assertEquals(7, source.baseRevision()); assertEquals(-200, source.x()); assertEquals(100, source.y());
        assertEquals(0, source.radius()); assertEquals(0, source.waitDays());
        assertEquals(-1, source.minSignalDays()); assertEquals(-1, source.maxSignalDays());
        assertEquals(0, source.extraRadius()); assertEquals(1, source.timeScale());
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeSource(ID, 7, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> CoopStormControl.surgeSource(ID, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 7, CoopStormControl.Action.SURGE_SOURCE,
                0, 0, 1, 0, -1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 7, CoopStormControl.Action.SURGE_SOURCE,
                0, 0, 0, 0.1f, -1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 7, CoopStormControl.Action.SURGE_SOURCE,
                0, 0, 0, 0, 0, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 7, CoopStormControl.Action.SURGE_SOURCE,
                0, 0, 0, 0, -1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 7, CoopStormControl.Action.SURGE_SOURCE,
                0, 0, 0, 0, -1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormControl(ID, 7, CoopStormControl.Action.SURGE_SOURCE,
                0, 0, 0, 0, -1, -1, 0, 5));
    }
}
