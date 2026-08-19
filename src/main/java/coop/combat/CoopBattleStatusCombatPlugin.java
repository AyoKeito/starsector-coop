package coop.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.util.CoopLog;

import java.util.List;

/**
 * The only mod code that runs while a client is inside a battle (Phase 14).
 *
 * <p>Registered through the mod's {@code data/config/settings.json} {@code "plugins"} map, which is
 * vanilla's documented surface for it ("add EveryFrameCombatPlugins here (with unique keys)",
 * core {@code settings.json}) and the same merge the mod already relies on for
 * {@code newGameSectorProcGen}. The engine instantiates it and adds it to <em>every</em> combat
 * engine, including the refit simulator, missions, and the title-screen sim — so this class carries
 * no gate of its own beyond "is there a bridge"; {@link CoopBattleBridge#onCombatFrame} does the
 * campaign-battle and session checks.
 *
 * <p>It is deliberately a dumb forwarder. All state lives in {@link CoopBattleBridge}, which the
 * campaign pump owns, because this object's lifetime is one battle while the battle's coop identity
 * has to outlive it.
 *
 * <p>Never throws: an exception escaping a combat-plugin frame would take the player's battle down.
 */
public class CoopBattleStatusCombatPlugin extends BaseEveryFrameCombatPlugin {

    private CombatEngineAPI engine;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        try {
            CoopBattleBridge bridge = CoopBattleBridge.active();
            if (bridge == null) {
                return;
            }
            CombatEngineAPI target = engine != null ? engine : Global.getCombatEngine();
            if (target == null) {
                return;
            }
            bridge.onCombatFrame(target, System.currentTimeMillis());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleStatusCombatPlugin.class, "Coop combat status frame failed", ex);
        }
    }
}
