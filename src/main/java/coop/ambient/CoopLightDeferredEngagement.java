package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Native Dweller actions wait for the shared light claim while their own dialog holds the clock. */
public final class CoopLightDeferredEngagement {
    private static final int MAX_EVENTS = 256;
    private static final Map<InteractionDialogAPI, Pending> pending = new IdentityHashMap<>();
    // Only cached primitives enter this bounded journal. Native references remain in Pending only.
    private static final ArrayDeque<Map<String, Object>> journal = new ArrayDeque<>();
    private static long sequence;
    private static long evictedEvents;

    private CoopLightDeferredEngagement() { }

    /** True means this managed action is deferred or unavailable; false keeps native execution. */
    public static boolean deferIfNeeded(InteractionDialogAPI dialog, SectorEntityToken light,
                                        CampaignFleetAPI player, Runnable action) {
        String lightId = CoopLightBridge.lightId(light);
        if (lightId == null) return false;
        if (pending.containsKey(dialog)) return true;
        Object owner = CoopLightBridge.interactionOwner();
        if (owner == null) {
            new Observation(lightId, null, Global.getSector(), player, dialog, light)
                    .record("cancelled", "owner_unavailable");
            leaveOnly(dialog);
            return true;
        }
        if (CoopLightBridge.allowDwellerDialog(light, player)) return false;
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            new Observation(lightId, owner, null, player, dialog, light)
                    .record("cancelled", "sector_unavailable");
            leaveOnly(dialog);
            return true;
        }
        // The permission call above may have just queued the claim. Capture its token once here,
        // never when a diagnostic snapshot is read and never from a later replacement generation.
        Observation observation = new Observation(lightId, owner, sector, player, dialog, light);
        try {
            String token = CoopLightBridge.interactionOwner() == owner
                    ? CoopLightBridge.passiveClaimToken(lightId) : null;
            if (CoopLightBridge.interactionOwner() != owner) {
                observation.tokenReadError = "owner changed before original token capture";
            } else if (token != null && token.length() > 256) {
                observation.tokenReadError = "claim token exceeded diagnostic bound";
            } else {
                observation.originalClaimToken = token == null ? "" : token;
                observation.tokenObserved = token != null && !token.isBlank();
            }
        } catch (RuntimeException | LinkageError failure) {
            observation.tokenReadError = failureText(failure);
        }
        Pending value = new Pending(sector, dialog, light, player, owner, Objects.requireNonNull(action), observation);
        pending.put(dialog, value);
        observation.queuedAtMillis = System.currentTimeMillis();
        observation.queuedSequence = observation.record("queued", "awaiting_claim");
        sector.addTransientScript(value);
        leaveOnly(dialog);
        return true;
    }

    private static void leaveOnly(InteractionDialogAPI dialog) {
        dialog.getOptionPanel().clearOptions();
        dialog.getOptionPanel().addOption("Leave", "defaultLeave");
    }

    public static void clear() {
        clear("explicit_reset");
    }

    /** Cancels live scripts, retaining primitive lifecycle evidence across adapter replacement. */
    public static void clear(String reason) {
        Objects.requireNonNull(reason, "deferred cancellation reason");
        if (reason.isBlank() || reason.length() > 128)
            throw new IllegalArgumentException("Deferred cancellation reason must contain 1..128 characters");
        for (Pending value : List.copyOf(pending.values())) value.cancel(reason);
    }

    /** Pure snapshot: no native predicates, permission checks, token providers or pending cleanup. */
    public static Map<String, Object> diagnostics() {
        List<Observation> ordered = pending.values().stream().map(value -> value.observation)
                .sorted(Comparator.comparingLong(value -> value.queuedSequence)).toList();
        List<Map<String, Object>> active = new ArrayList<>();
        for (Observation observation : ordered) {
            if (active.size() == MAX_EVENTS) break;
            active.add(observation.snapshot("queued", "awaiting_claim"));
        }
        return Map.of("pending", List.copyOf(active), "pendingCount", ordered.size(),
                "pendingTruncated", ordered.size() > active.size(), "journal", List.copyOf(journal),
                "maxEvents", MAX_EVENTS, "evictedEvents", evictedEvents);
    }

    private static String identity(Object value) {
        return value == null ? "" : value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }

    private static String failureText(Throwable failure) {
        String text = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
        return text.substring(0, Math.min(512, text.length()));
    }

    /** Deliberately contains no gameplay objects, callbacks, or suppliers. */
    private static final class Observation {
        final String observationId = UUID.randomUUID().toString();
        final String lightId;
        final String ownerIdentity;
        final String sectorIdentity;
        final String playerIdentity;
        final String dialogIdentity;
        final String lightIdentity;
        final long startedAtMillis = System.currentTimeMillis();
        String originalClaimToken = "";
        boolean tokenObserved;
        String tokenReadError = "";
        long queuedAtMillis;
        long queuedSequence;
        int actionExecutionCount;
        int actionReturnCount;
        String actionFailure = "";

        Observation(String lightId, Object owner, SectorAPI sector, CampaignFleetAPI player,
                    InteractionDialogAPI dialog, SectorEntityToken light) {
            this.lightId = lightId;
            ownerIdentity = identity(owner); sectorIdentity = identity(sector);
            playerIdentity = identity(player); dialogIdentity = identity(dialog); lightIdentity = identity(light);
        }

        Map<String, Object> snapshot(String state, String reason) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("observationId", observationId); row.put("lightId", lightId);
            row.put("ownerIdentity", ownerIdentity); row.put("sectorIdentity", sectorIdentity);
            row.put("playerIdentity", playerIdentity); row.put("dialogIdentity", dialogIdentity);
            row.put("lightIdentity", lightIdentity); row.put("originalClaimToken", originalClaimToken);
            row.put("tokenObserved", tokenObserved); row.put("tokenReadError", tokenReadError);
            row.put("startedAtMillis", startedAtMillis); row.put("queuedAtMillis", queuedAtMillis);
            row.put("queuedSequence", queuedSequence); row.put("actionExecutionCount", actionExecutionCount);
            row.put("actionReturnCount", actionReturnCount); row.put("actionFailure", actionFailure);
            row.put("state", state); row.put("reason", reason);
            return Map.copyOf(row);
        }

        long record(String state, String reason) {
            long eventSequence = ++sequence;
            if (state.equals("queued") && queuedSequence == 0) queuedSequence = eventSequence;
            Map<String, Object> row = new LinkedHashMap<>(snapshot(state, reason));
            row.put("sequence", eventSequence); row.put("atMillis", System.currentTimeMillis());
            if (journal.size() == MAX_EVENTS) { journal.removeFirst(); evictedEvents++; }
            journal.addLast(Map.copyOf(row));
            return eventSequence;
        }
    }

    private static final class Pending implements EveryFrameScript {
        private final SectorAPI sector;
        private final InteractionDialogAPI dialog;
        private final SectorEntityToken light;
        private final CampaignFleetAPI player;
        private final Object owner;
        private final Runnable action;
        private final Observation observation;
        private boolean done;

        private Pending(SectorAPI sector, InteractionDialogAPI dialog, SectorEntityToken light,
                        CampaignFleetAPI player, Object owner, Runnable action, Observation observation) {
            this.sector = sector;
            this.dialog = dialog;
            this.light = light;
            this.player = player;
            this.owner = owner;
            this.action = action;
            this.observation = observation;
        }

        @Override public boolean isDone() { return done; }
        @Override public boolean runWhilePaused() { return true; }

        @Override public void advance(float amount) {
            if (done) return;
            if (Global.getSector() != sector) { cancel("sector_changed"); return; }
            if (CoopLightBridge.interactionOwner() != owner) { cancel("owner_changed"); return; }
            if (sector.getPlayerFleet() != player) { cancel("player_changed"); return; }
            if (!light.isAlive()) { cancel("light_not_alive"); return; }
            if (light.hasTag(Tags.FADING_OUT_AND_EXPIRING)) { cancel("light_fading"); return; }
            if (sector.getCampaignUI() == null) { cancel("campaign_ui_unavailable"); return; }
            if (sector.getCampaignUI().getCurrentInteractionDialog() != dialog) { cancel("dialog_closed_or_replaced"); return; }
            if (dialog.getInteractionTarget() != light) { cancel("dialog_target_changed"); return; }
            if (!CoopLightBridge.allowDwellerDialog(light, player)) return;
            finish();
            observation.actionExecutionCount++;
            observation.record("executed", "action_invoked");
            try {
                action.run();
                observation.actionReturnCount++;
                observation.record("executed", "action_returned");
            } catch (RuntimeException | Error failure) {
                observation.actionFailure = failureText(failure);
                observation.record("executed", "action_failed");
                throw failure;
            }
        }

        private void cancel(String reason) {
            if (done) return;
            finish();
            observation.record("cancelled", reason);
        }

        private void finish() {
            done = true;
            pending.remove(dialog, this);
        }
    }
}
