package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Native Dweller actions wait for the shared light claim while their own dialog holds the clock. */
public final class CoopLightDeferredEngagement {
    private static final Map<InteractionDialogAPI, Pending> pending = new IdentityHashMap<>();

    private CoopLightDeferredEngagement() { }

    /** True means this managed action is deferred or unavailable; false keeps native execution. */
    public static boolean deferIfNeeded(InteractionDialogAPI dialog, SectorEntityToken light,
                                        CampaignFleetAPI player, Runnable action) {
        if (CoopLightBridge.lightId(light) == null) return false;
        if (pending.containsKey(dialog)) return true;
        Object owner = CoopLightBridge.interactionOwner();
        if (owner == null) {
            leaveOnly(dialog);
            return true;
        }
        if (CoopLightBridge.allowDwellerDialog(light, player)) return false;
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            leaveOnly(dialog);
            return true;
        }
        Pending value = new Pending(sector, dialog, light, player, owner, Objects.requireNonNull(action));
        pending.put(dialog, value);
        sector.addTransientScript(value);
        leaveOnly(dialog);
        return true;
    }

    private static void leaveOnly(InteractionDialogAPI dialog) {
        dialog.getOptionPanel().clearOptions();
        dialog.getOptionPanel().addOption("Leave", "defaultLeave");
    }

    public static void clear() {
        for (Pending value : pending.values()) value.done = true;
        pending.clear();
    }

    private static final class Pending implements EveryFrameScript {
        private final SectorAPI sector;
        private final InteractionDialogAPI dialog;
        private final SectorEntityToken light;
        private final CampaignFleetAPI player;
        private final Object owner;
        private final Runnable action;
        private boolean done;

        private Pending(SectorAPI sector, InteractionDialogAPI dialog, SectorEntityToken light,
                        CampaignFleetAPI player, Object owner, Runnable action) {
            this.sector = sector;
            this.dialog = dialog;
            this.light = light;
            this.player = player;
            this.owner = owner;
            this.action = action;
        }

        @Override public boolean isDone() { return done; }
        @Override public boolean runWhilePaused() { return true; }

        @Override public void advance(float amount) {
            if (done) return;
            if (Global.getSector() != sector || CoopLightBridge.interactionOwner() != owner
                    || sector.getPlayerFleet() != player || !light.isAlive()
                    || light.hasTag(Tags.FADING_OUT_AND_EXPIRING)
                    || sector.getCampaignUI() == null
                    || sector.getCampaignUI().getCurrentInteractionDialog() != dialog
                    || dialog.getInteractionTarget() != light) {
                finish();
                return;
            }
            if (!CoopLightBridge.allowDwellerDialog(light, player)) return;
            finish();
            action.run();
        }

        private void finish() {
            done = true;
            pending.remove(dialog, this);
        }
    }
}
