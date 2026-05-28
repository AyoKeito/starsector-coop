package coop;

import com.fs.starfarer.api.BaseModPlugin;
import coop.util.CoopLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopScaffoldTest {
    @Test
    void pluginClassIsLoadableByTheTestJvm() {
        assertEquals("coop.CoopModPlugin", CoopModPlugin.class.getName());
        assertTrue(BaseModPlugin.class.isAssignableFrom(CoopModPlugin.class));
    }

    @Test
    void coopModulePackageIsLoadableByTheTestJvm() {
        assertEquals("coop", CoopModPlugin.class.getPackageName());
        assertNotNull(CoopLog.class);
    }
}

