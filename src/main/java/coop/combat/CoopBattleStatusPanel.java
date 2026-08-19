package coop.combat;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The spectator's view of the partner's battle (Phase 14): a read-only custom
 * {@link InteractionDialogPlugin} opened with
 * {@code CampaignUIAPI.showInteractionDialog(plugin, target)} and re-rendered in {@link #advance}
 * from the latest {@code BATTLE_STATUS}.
 *
 * <p>This is the <em>only</em> viable spectator (engine facts, plan line ~1320): a rendered "puppet
 * battle" is infeasible — entering the combat state needs a real player fleet with a flagship, and
 * projectiles/fighters cannot be replicated by any API.
 *
 * <p><b>It never touches the clock.</b> That is the whole trick to avoiding the documented trade-tab
 * freeze (memory {@code guest-pause-on-change-only}): a vanilla interaction dialog already pauses the
 * campaign by itself, and while one is open {@code CoopNetPump.maybeApplyTimeSnapshot} deliberately
 * stops applying host snapshots so vanilla owns the local clock. If this panel also called
 * {@code setPaused} it would be exactly the fight that froze the guest's trade tab. It also does not
 * hold the partner's world open on its own: the guest's screen-pause <em>intent</em> keeps the host
 * paused through the existing Phase 11 path, and the host spectator is held by the combat intent.
 *
 * <p><b>Dismissal.</b> While the coop session is alive the panel has no options and the escape key is
 * disabled, so the spectator is genuinely held for the duration; the bridge dismisses it on
 * {@code BATTLE_END}. Once {@code canDismiss} goes true — connection lost, or the escape-hatch
 * timeout in {@link CoopBattleBridge} — a Close option and the escape key appear so the player can
 * never be trapped.
 *
 * <p>Rendering is redraw-on-change only ({@code statusSeq} + dismissability), not per frame: the
 * text panel has to be cleared and rebuilt, and doing that at 60 Hz flickers.
 */
public final class CoopBattleStatusPanel implements InteractionDialogPlugin {

    private static final Object OPTION_CLOSE = "coopBattleStatusClose";
    private static final int BAR_CELLS = 12;
    private static final Color OWN_COLOR = new Color(150, 220, 150);
    private static final Color ENEMY_COLOR = new Color(230, 160, 160);
    private static final Color DIM_COLOR = new Color(160, 160, 160);
    private static final Color ALERT_COLOR = new Color(255, 120, 120);

    private final Supplier<CoopBattleStatus> statusSupplier;
    private final Supplier<String> headerSupplier;
    private final BooleanSupplier battleOver;
    private final BooleanSupplier dismissAllowed;
    private final Supplier<String> dismissReason;

    private InteractionDialogAPI dialog;
    private boolean rendered;
    private long renderedStatusSeq = Long.MIN_VALUE;
    private String renderedBattleId = "";
    private boolean renderedDismissable;
    private boolean dismissRequested;

    public CoopBattleStatusPanel(Supplier<CoopBattleStatus> statusSupplier,
                                 Supplier<String> headerSupplier,
                                 BooleanSupplier battleOver,
                                 BooleanSupplier dismissAllowed,
                                 Supplier<String> dismissReason) {
        this.statusSupplier = statusSupplier;
        this.headerSupplier = headerSupplier;
        this.battleOver = battleOver;
        this.dismissAllowed = dismissAllowed;
        this.dismissReason = dismissReason;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.setPromptText("Partner engagement in progress");
            dialog.hideVisualPanel();
            // Escape does nothing until dismissal is allowed; re-armed in render().
            dialog.setOptionOnEscape(null, null);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleStatusPanel.class, "Coop battle status panel init failed", ex);
        }
        render(true);
    }

    /** Bridge-driven close (battle ended). Safe to call more than once. */
    public void requestDismiss() {
        if (dismissRequested) {
            return;
        }
        dismissRequested = true;
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleStatusPanel.class, "Coop battle status panel dismiss failed", ex);
        }
    }

    @Override
    public void advance(float amount) {
        // Self-dismiss on BATTLE_END rather than letting the pump call dismiss() on us from outside:
        // closing a dialog from its own advance is the path vanilla plugins use, and it keeps the
        // teardown on the dialog's own frame. The bridge's requestDismiss() stays as a backstop.
        if (battleOver != null && battleOver.getAsBoolean()) {
            requestDismiss();
            return;
        }
        render(false);
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (OPTION_CLOSE.equals(optionData)) {
            requestDismiss();
        }
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
        // The spectator never fights from this panel; nothing to fold back.
    }

    @Override
    public Object getContext() {
        return null;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        // Never null: engine paths that walk the rule-memory scopes would NPE on one.
        return new HashMap<>();
    }

    // ---- rendering -------------------------------------------------------------------------------

    private void render(boolean force) {
        if (dialog == null) {
            return;
        }
        try {
            CoopBattleStatus status = statusSupplier == null ? null : statusSupplier.get();
            boolean dismissable = dismissAllowed != null && dismissAllowed.getAsBoolean();
            long seq = status == null ? Long.MIN_VALUE : status.statusSeq();
            String battleId = status == null ? "" : status.battleId();
            if (!force && rendered
                    && seq == renderedStatusSeq
                    && battleId.equals(renderedBattleId)
                    && dismissable == renderedDismissable) {
                return;
            }
            renderedStatusSeq = seq;
            renderedBattleId = battleId;
            renderedDismissable = dismissable;
            rendered = true;
            renderText(status, dismissable);
            renderOptions(dismissable);
        } catch (RuntimeException | LinkageError ex) {
            // A render failure must never take the campaign frame down; the panel just stops updating.
            CoopLog.warn(CoopBattleStatusPanel.class, "Coop battle status panel render failed", ex);
        }
    }

    private void renderText(CoopBattleStatus status, boolean dismissable) {
        TextPanelAPI text = dialog.getTextPanel();
        if (text == null) {
            return;
        }
        text.clear();
        text.addPara(headerSupplier == null ? "Partner is engaged." : headerSupplier.get());
        if (dismissable) {
            String reason = dismissReason == null ? null : dismissReason.get();
            text.addPara(reason == null || reason.isEmpty() ? "Connection lost." : reason, ALERT_COLOR);
        }
        if (status == null) {
            text.addPara("Battle in progress. Waiting for the first status report...", DIM_COLOR);
            return;
        }
        text.addPara("Elapsed: " + formatElapsed(status.elapsedMillis()), DIM_COLOR);
        renderSide(text, "Partner's ships", status.ownShips(), OWN_COLOR);
        renderSide(text, "Enemy ships", status.enemyShips(), ENEMY_COLOR);
        List<String> killFeed = status.killFeed();
        if (!killFeed.isEmpty()) {
            text.addPara("--- Losses ---", DIM_COLOR);
            for (String kill : killFeed) {
                text.addPara(kill, DIM_COLOR);
            }
        }
    }

    private void renderSide(TextPanelAPI text, String heading, List<CoopBattleStatus.ShipRecord> ships,
                            Color color) {
        text.addPara("--- " + heading + " (" + ships.size() + ") ---", color);
        if (ships.isEmpty()) {
            text.addPara("  (none)", DIM_COLOR);
            return;
        }
        for (CoopBattleStatus.ShipRecord ship : ships) {
            text.addPara(shipLine(ship), ship.state() == CoopBattleStatus.ShipState.ALIVE ? color : DIM_COLOR);
        }
    }

    /** Package-private so the codec/formatting is unit-testable without an engine. */
    static String shipLine(CoopBattleStatus.ShipRecord ship) {
        String name = ship.name().isEmpty() ? ship.hullId() : ship.name();
        String marker = switch (ship.state()) {
            case DESTROYED -> "[X]";
            case DISABLED -> "[!]";
            case ALIVE -> "[ ]";
        };
        if (ship.state() == CoopBattleStatus.ShipState.DESTROYED) {
            return marker + " " + name + "  destroyed";
        }
        return marker + " " + name
                + "  hull " + bar(ship.hullFraction()) + percent(ship.hullFraction())
                + "  flux " + bar(ship.fluxLevel()) + percent(ship.fluxLevel());
    }

    static String bar(float fraction) {
        int filled = Math.round(Math.max(0f, Math.min(1f, fraction)) * BAR_CELLS);
        StringBuilder out = new StringBuilder(BAR_CELLS + 2);
        out.append('[');
        for (int i = 0; i < BAR_CELLS; i++) {
            out.append(i < filled ? '|' : '.');
        }
        out.append(']');
        return out.toString();
    }

    private static String percent(float fraction) {
        return " " + Math.round(Math.max(0f, Math.min(1f, fraction)) * 100f) + "%";
    }

    static String formatElapsed(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private void renderOptions(boolean dismissable) {
        OptionPanelAPI options = dialog.getOptionPanel();
        if (options == null) {
            return;
        }
        options.clearOptions();
        if (dismissable) {
            options.addOption("Close", OPTION_CLOSE);
            dialog.setOptionOnEscape("Close", OPTION_CLOSE);
        } else {
            // Informational only: no options, and escape deliberately dead, so the spectator is held
            // for the duration of the partner's battle exactly as the combat model requires.
            dialog.setOptionOnEscape(null, null);
        }
    }
}
