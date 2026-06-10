package coop.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

/**
 * Opt-in switch for the dormant coop diagnostics (orbit-dump, dialog-state). These are off by default
 * so they don't spam the log, but stay in the code so a desync can be re-investigated without a new
 * build. Enable either way:
 *
 * <ul>
 *   <li>at launch: JVM arg {@code -Dcoop.debug.diagnostics=true} (set on the instance you want to trace);</li>
 *   <li>in-game, no relaunch: set the sector memory flag, e.g. console {@code SetMemoryKey $coopDebug true},
 *       or {@code Global.getSector().getMemoryWithoutUpdate().set("$coopDebug", true)}.</li>
 * </ul>
 */
public final class CoopDebug {
    public static final String PROPERTY = "coop.debug.diagnostics";
    public static final String MEMORY_FLAG = "$coopDebug";

    private CoopDebug() {
    }

    /** True when coop diagnostics should log, via the JVM property or the in-game memory flag. */
    public static boolean diagnosticsEnabled() {
        if (Boolean.getBoolean(PROPERTY)) {
            return true;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return false;
            }
            MemoryAPI memory = sector.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean(MEMORY_FLAG);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
