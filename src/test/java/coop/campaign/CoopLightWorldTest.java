package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.CargoPodsEntityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.ambient.CoopLightBridge;
import coop.interaction.CoopInteractionGate;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoopLightWorldTest {
    private SettingsAPI oldSettings;

    @BeforeEach void setup() {
        oldSettings = Global.getSettings();
        Global.setSettings(proxy(SettingsAPI.class, (self, method, args) -> switch (method.getName()) {
            case "getVersionString" -> CoopPresenceRegistry.PINNED_VERSION;
            default -> ProxyDefaults.defaultValue(method.getReturnType());
        }));
        CoopLightBridge.clear();
    }
    @AfterEach void cleanup() { CoopLightBridge.clear(); Global.setSettings(oldSettings); }

    @Test void captureAdoptsOnlyExactLiveNativeHyperspaceLightsAndKeepsIds() {
        Fixture f = new Fixture(false);
        Light live = f.add("native-light");
        f.add("fading").fading = true;
        f.add("dead").alive = false;
        f.add("system").inHyper = false;
        f.add("pod").plugin = new CargoPodsEntityPlugin();
        f.add("modded").plugin = new ModdedLight();
        List<CoopLightRecord> initial = f.world.captureHost();
        assertEquals(1, initial.size());
        assertEquals(initial, f.world.captureHost());
        assertEquals(initial.get(0).id(), live.memory.get(CoopLightWorld.LIGHT_ID_KEY));
        assertEquals(Set.of(initial.get(0).id()), f.world.taggedIds());
    }

    @Test void partialOrDuplicateHostCaptureFailsLoudly() {
        Fixture f = new Fixture(false);
        f.add("first"); f.add("unsupported"); f.codec.failCapture.add("unsupported");
        assertThrows(IllegalStateException.class, f.world::captureHost);
        f.codec.failCapture.clear();
        String id = id(); f.lights.get(0).tag(id); f.lights.get(1).tag(id);
        assertThrows(IllegalStateException.class, f.world::captureHost);
        assertThrows(IllegalStateException.class, f.world::taggedIds);
    }

    @Test void unavailableSectorIsNotAnEmptyAuthoritativeManifest() {
        assertNull(new CoopLightWorld(() -> null).captureHost());
    }

    @Test void upsertAdoptsStrictNativeRecipeAndPreservesLocalEntity() {
        Fixture f = new Fixture(false);
        Light legacy = f.add("same-native");
        CoopLightRecord record = record(id(), "same-native", false);
        f.world.upsert(record); f.world.upsert(record);
        assertEquals(0, f.codec.creates);
        assertEquals(record.id(), CoopLightWorld.ownedId(legacy.entity));
        assertEquals(2, f.codec.applies);
        assertEquals(List.of(legacy.entity), f.entities);
    }

    @Test void collidingNativeIdWithDifferentRecipeCreatesSeparateManagedLight() {
        Fixture f = new Fixture(false);
        Light legacy = f.add("same-native"); legacy.position.set(9, 9);
        CoopLightRecord record = record(id(), "same-native", false);
        f.world.upsert(record);
        assertEquals(1, f.codec.creates);
        assertNull(CoopLightWorld.ownedId(legacy.entity));
        f.world.reconcileLegacy(List.of(record));
        assertEquals(1, f.entities.size());
        assertFalse(f.entities.contains(legacy.entity));
    }

    @Test void fullSetCleanupWaitsForAllRecordsAndLegacyDialogBeforeRemovingAnything() {
        Fixture f = new Fixture(false);
        Light a = f.add("a"), b = f.add("b");
        assertThrows(IllegalStateException.class, () -> f.world.reconcileLegacy(List.of(record(id(), "missing", false))));
        assertEquals(2, f.entities.size());
        f.dialogTarget = b.entity;
        assertThrows(IllegalStateException.class, () -> f.world.reconcileLegacy(List.of()));
        assertThrows(IllegalStateException.class, () -> f.world.upsert(record(id(), "new", false)));
        assertTrue(f.entities.contains(a.entity)); assertTrue(f.entities.contains(b.entity));
        f.dialogTarget = null; f.world.reconcileLegacy(List.of());
        assertTrue(f.entities.isEmpty());
    }

    @Test void firstActorWinsAndResultReplaySurvivesANewWorldAdapter() {
        Fixture f = new Fixture(); f.world.installBridge(false, "host");
        String id = f.world.captureHost().get(0).id();
        CoopLightCodec.Request guestRequest = request(id, CoopLightCodec.Action.PULSE);
        CoopLightCodec.Outcome win = f.world.applyRequest(guestRequest, "guest");
        assertTrue(win.accepted()); assertEquals(0.5f, win.bonus()); assertEquals(7, win.points());
        assertTrue(f.codec.grants.isEmpty(), "Host must not award the Guest's mirror");
        assertFalse(f.world.applyRequest(request(id, CoopLightCodec.Action.SENSOR_BURST), "host").accepted());
        CoopLightWorld reloaded = f.newWorld(); reloaded.installBridge(false, "host");
        assertEquals(win, reloaded.applyRequest(guestRequest, "guest"));
        assertThrows(IllegalStateException.class, () -> reloaded.applyRequest(guestRequest, "other"));
        assertThrows(IllegalStateException.class, () -> reloaded.applyRequest(
                new CoopLightCodec.Request(id, guestRequest.requestId(), CoopLightCodec.Action.SENSOR_BURST), "guest"));
        assertTrue(reloaded.captureHost().isEmpty());
        assertInstanceOf(String.class, f.saved.get(CoopLightWorld.STATE_KEY));
    }

    @Test void localHostPulseUsesNativeActorOnlyRewardAndCannotGrantTwice() {
        Fixture f = new Fixture(); f.world.installBridge(false, "host");
        f.world.captureHost();
        assertTrue(CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, true));
        assertEquals(List.of("sensor:0.5:7"), f.codec.grants);
        assertTrue(f.world.pendingRequests().isEmpty());
        assertTrue(f.world.captureHost().isEmpty());
        assertTrue(CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, true));
        assertEquals(1, f.codec.grants.size());
    }

    @Test void guestPendingAndAppliedGuardsSurviveReloadAndAckMovement() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        String id = id(); f.lights.get(0).tag(id);
        assertTrue(CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, false));
        CoopLightCodec.Request pending = f.world.pendingRequests().get(0);
        CoopLightWorld reloaded = f.newWorld(); reloaded.installBridge(true, "guest");
        assertEquals(List.of(pending), reloaded.pendingRequests());
        f.local.position.set(100_000, 100_000);
        CoopLightCodec.Outcome accepted = outcome(pending, "guest", true, 0.75f, 13);
        reloaded.acceptOutcome(accepted);
        assertEquals(List.of("burn:0.75:13"), f.codec.grants);
        CoopLightWorld again = f.newWorld(); again.installBridge(true, "guest"); again.acceptOutcome(accepted);
        assertEquals(1, f.codec.grants.size()); assertTrue(again.pendingRequests().isEmpty());
        assertFalse(again.isAvailable(id));
    }

    @Test void reloadedGuestPendingRequestWaitsForItsMirrorAndKeepsItsOriginalIdentity() {
        Fixture host = new Fixture(); host.world.installBridge(false, "host");
        String id = host.world.captureHost().get(0).id();
        Fixture guest = new Fixture(); guest.world.installBridge(true, "guest");
        guest.lights.get(0).tag(id);
        assertTrue(CoopLightBridge.handlePulse(guest.lights.get(0).entity, guest.local.entity, false));
        CoopLightCodec.Request request = guest.world.pendingRequests().get(0);
        CoopLightWorld reloadedGuest = guest.newWorld(); reloadedGuest.installBridge(true, "guest");
        assertEquals(List.of(request), reloadedGuest.pendingRequests());
        Object savedPending = guest.saved.get(CoopLightWorld.STATE_KEY);
        Object savedHost = host.saved.get(CoopLightWorld.STATE_KEY);
        host.remoteAvailable = false;

        IllegalStateException waiting = assertThrows(IllegalStateException.class,
                () -> host.world.applyRequest(request, "guest"));

        assertTrue(waiting.getMessage().contains("actor is not ready"));
        assertEquals(savedHost, host.saved.get(CoopLightWorld.STATE_KEY), "no permanent denial is recorded before placement");
        assertEquals(savedPending, guest.saved.get(CoopLightWorld.STATE_KEY));
        assertEquals(List.of(request), reloadedGuest.pendingRequests());
        assertEquals(Map.of("pending", 0, "results", 0, "tombstones", 0, "applied", 0, "uncertain", 0),
                host.world.readOnlyStateCounts());
        assertFalse(host.lights.get(0).fading);
        assertTrue(host.codec.grants.isEmpty()); assertTrue(guest.codec.grants.isEmpty());

        host.remoteAvailable = true;
        CoopLightCodec.Outcome accepted = host.world.applyRequest(request, "guest");
        assertTrue(accepted.accepted()); assertEquals(request.requestId(), accepted.requestId());
        reloadedGuest.acceptOutcome(accepted);
        assertTrue(reloadedGuest.pendingRequests().isEmpty());
        assertEquals(List.of("burn:0.5:7"), guest.codec.grants);
        assertEquals(Map.of("pending", 0, "results", 1, "tombstones", 1, "applied", 0, "uncertain", 0),
                host.world.readOnlyStateCounts());
        assertEquals(Map.of("pending", 0, "results", 0, "tombstones", 1, "applied", 1, "uncertain", 0),
                reloadedGuest.readOnlyStateCounts());

        Object committedHost = host.saved.get(CoopLightWorld.STATE_KEY);
        host.remoteAvailable = false;
        CoopLightWorld reloadedHost = host.newWorld(); reloadedHost.installBridge(false, "host");
        assertEquals(accepted, reloadedHost.applyRequest(request, "guest"),
                "cached decisions replay even when the mirror is unavailable again");
        assertEquals(committedHost, host.saved.get(CoopLightWorld.STATE_KEY));
        reloadedGuest.acceptOutcome(accepted);
        assertEquals(1, guest.codec.grants.size()); assertTrue(host.codec.grants.isEmpty());
    }

    @Test void newConsumptionCannotRecordAnOutcomeUntilItsActorIsAliveAndPlaced() {
        for (String state : List.of("absent", "dead", "unlocated")) {
            Fixture f = new Fixture(); f.world.installBridge(false, "host");
            String id = f.world.captureHost().get(0).id();
            CoopLightCodec.Request request = request(id, CoopLightCodec.Action.PULSE);
            f.remoteAvailable = !state.equals("absent");
            f.remote.alive = !state.equals("dead");
            f.remote.located = !state.equals("unlocated");
            Object saved = f.saved.get(CoopLightWorld.STATE_KEY);

            assertThrows(IllegalStateException.class, () -> f.world.applyRequest(request, "guest"), state);

            assertEquals(saved, f.saved.get(CoopLightWorld.STATE_KEY), state);
            assertEquals(0, f.world.readOnlyStateCounts().get("results"), state);
            assertEquals(0, f.world.readOnlyStateCounts().get("tombstones"), state);
            assertFalse(f.lights.get(0).fading, state);
            f.remoteAvailable = true; f.remote.alive = true; f.remote.located = true;
            assertTrue(f.world.applyRequest(request, "guest").accepted(), state);
        }
    }

    @Test void unrelatedResultCannotAwardLocalPlayerAndDoesNotErasePending() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        String id = id(); f.lights.get(0).tag(id);
        CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, true);
        CoopLightCodec.Request request = f.world.pendingRequests().get(0);
        assertThrows(IllegalStateException.class, () -> f.world.acceptOutcome(outcome(request, "host", true, 1, 7)));
        assertEquals(1, f.world.pendingRequests().size()); assertTrue(f.codec.grants.isEmpty());
        f.world.acceptOutcome(outcome(request(id, CoopLightCodec.Action.PULSE), "guest", true, 1, 7));
        assertTrue(f.codec.grants.isEmpty()); assertEquals(1, f.world.pendingRequests().size());
    }

    @Test void rejectedResultClearsPendingWithoutRewardOrConsumption() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        String id = id(); f.lights.get(0).tag(id);
        CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, false);
        CoopLightCodec.Request pending = f.world.pendingRequests().get(0);
        f.world.acceptOutcome(outcome(pending, "guest", false, 0, 0));
        assertTrue(f.world.pendingRequests().isEmpty()); assertTrue(f.codec.grants.isEmpty());
        assertTrue(f.world.isAvailable(id));
    }

    @Test void invalidActorGeometryAndWrongActionsCannotConsumeLights() {
        Fixture f = new Fixture(); f.world.installBridge(false, "host");
        String id = f.world.captureHost().get(0).id();
        f.remote.position.set(10_000, 0);
        assertFalse(f.world.applyRequest(request(id, CoopLightCodec.Action.PULSE), "guest").accepted());
        f.remote.position.set(0, 0); f.remote.inHyper = false;
        assertFalse(f.world.applyRequest(request(id, CoopLightCodec.Action.SENSOR_BURST), "guest").accepted());
        f.remote.inHyper = true;
        assertFalse(f.world.applyRequest(request(id, CoopLightCodec.Action.DWELLER), "guest").accepted());
        assertTrue(f.world.isAvailable(id));
        assertTrue(f.world.applyRequest(request(id, CoopLightCodec.Action.PULSE), "guest").accepted());
    }

    @Test void dwellerConsumptionQueuesWithoutAwardAndIsHostAuthorized() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        String id = id(); Light light = f.lights.get(0); light.tag(id); light.tags.add(Tags.DWELLER_LIGHT);
        assertFalse(CoopLightBridge.allowNativeDespawn(light.entity));
        CoopLightCodec.Request request = f.world.pendingRequests().get(0);
        assertEquals(CoopLightCodec.Action.DWELLER, request.action());
        f.world.acceptOutcome(outcome(request, "guest", true, 0, 0));
        assertTrue(f.codec.grants.isEmpty()); assertFalse(f.world.isAvailable(id));
    }

    @Test void aConflictingHostClaimRejectsAnUnseenGuestDwellerWithoutConsumingTheLight() {
        Fixture f = new Fixture(); f.world.installBridge(false, "host");
        String id = id(); Light light = f.lights.get(0); light.tag(id); light.tags.add(Tags.DWELLER_LIGHT);
        CoopInteractionGate gate = new CoopInteractionGate();
        gate.arbitrate(id, "host", "Light");
        f.world.setDwellerClaimPolicy((lightId, actorId) -> gate.holderOf(lightId) == null
                || gate.holderOf(lightId).equals(actorId));

        CoopLightCodec.Outcome rejected = f.world.applyRequest(request(id, CoopLightCodec.Action.DWELLER), "guest");

        assertFalse(rejected.accepted());
        assertEquals("light claimed by another actor", rejected.reason());
        assertFalse(light.fading);
        assertTrue(f.world.isAvailable(id));
        assertTrue(f.codec.grants.isEmpty());
        assertFalse(((String) f.saved.get(CoopLightWorld.STATE_KEY)).contains("\"T" + id));

        gate.release(id, "host");
        gate.arbitrate(id, "guest", "Light");
        assertTrue(f.world.applyRequest(request(id, CoopLightCodec.Action.DWELLER), "guest").accepted(),
                "the actor holding the current claim may still consume the light");
    }

    @Test void aCachedAcceptedDwellerOutcomeSurvivesAChangedClaimWithoutReauthorizingOrDespawningAgain() {
        Fixture f = new Fixture(); f.world.installBridge(false, "host");
        String id = id(); Light light = f.lights.get(0); light.tag(id); light.tags.add(Tags.DWELLER_LIGHT);
        CoopLightCodec.Request request = request(id, CoopLightCodec.Action.DWELLER);
        CoopLightCodec.Outcome accepted = f.world.applyRequest(request, "guest");
        assertTrue(accepted.accepted()); assertTrue(light.fading);
        CoopLightWorld reloaded = f.newWorld(); reloaded.installBridge(false, "host");
        reloaded.setDwellerClaimPolicy((lightId, actorId) -> { throw new AssertionError("cached results must bypass current claims"); });
        f.codec.failDespawn = true;

        assertEquals(accepted, reloaded.applyRequest(request, "guest"));
        assertTrue(f.codec.grants.isEmpty());
    }

    @Test void aSavedGuestDwellerRequestCanReplayWhenNoCurrentActorClaimsTheLight() {
        String id = id();
        Fixture host = new Fixture(); host.world.installBridge(false, "host");
        host.lights.get(0).tag(id); host.lights.get(0).tags.add(Tags.DWELLER_LIGHT);
        CoopInteractionGate gate = new CoopInteractionGate();
        host.world.setDwellerClaimPolicy((lightId, actorId) -> gate.holderOf(lightId) == null
                || gate.holderOf(lightId).equals(actorId));
        Fixture guest = new Fixture(); guest.world.installBridge(true, "guest");
        guest.lights.get(0).tag(id); guest.lights.get(0).tags.add(Tags.DWELLER_LIGHT);
        assertFalse(CoopLightBridge.allowNativeDespawn(guest.lights.get(0).entity));
        CoopLightWorld reloadedGuest = guest.newWorld(); reloadedGuest.installBridge(true, "guest");
        CoopLightCodec.Request pending = reloadedGuest.pendingRequests().get(0);

        CoopLightCodec.Outcome result = host.world.applyRequest(pending, "guest");
        reloadedGuest.acceptOutcome(result);

        assertTrue(result.accepted());
        assertTrue(host.lights.get(0).fading);
        assertTrue(reloadedGuest.pendingRequests().isEmpty());
        assertTrue(host.codec.grants.isEmpty()); assertTrue(guest.codec.grants.isEmpty());
    }

    @Test void pulseAndSensorConsumptionDoNotRequireADwellerInteractionClaim() {
        for (CoopLightCodec.Action action : List.of(CoopLightCodec.Action.PULSE, CoopLightCodec.Action.SENSOR_BURST)) {
            Fixture f = new Fixture(); f.world.installBridge(false, "host");
            String id = f.world.captureHost().get(0).id();
            f.world.setDwellerClaimPolicy((lightId, actorId) -> { throw new AssertionError("ordinary pulse must not consult the Dweller claim"); });

            assertTrue(f.world.applyRequest(request(id, action), "guest").accepted());
        }
    }

    @Test void guestCannotAuthorizeAnIncomingConsumptionRequest() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        assertThrows(IllegalStateException.class, () -> f.world.applyRequest(request(id(), CoopLightCodec.Action.PULSE), "host"));
    }

    @Test void malformedSavedStateAndBadOwnershipFailInsteadOfResettingHistory() {
        Fixture f = new Fixture();
        f.saved.put(CoopLightWorld.STATE_KEY, List.of());
        assertThrows(IllegalStateException.class, f.world::observeGuest);
        f.saved.put(CoopLightWorld.STATE_KEY, "[\"Tlight:bad\"]");
        assertThrows(IllegalArgumentException.class, f.world::observeGuest);
        f.saved.remove(CoopLightWorld.STATE_KEY);
        f.lights.get(0).memory.put(CoopLightWorld.LIGHT_ID_KEY, 4);
        assertThrows(IllegalStateException.class, f.world::captureHost);
    }

    @Test void partialNativeGrantFailureRemainsUncertainAcrossReloadAndCannotGrantAgain() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        f.lights.get(0).tag(id());
        CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, true);
        CoopLightCodec.Request request = f.world.pendingRequests().get(0);
        CoopLightCodec.Outcome accepted = outcome(request, "guest", true, 1, 7);
        f.codec.failGrant = true;
        f.codec.partialGrantBeforeFailure = true;
        assertThrows(IllegalStateException.class, () -> f.world.acceptOutcome(accepted));
        assertEquals(List.of(request), f.world.pendingRequests());
        assertEquals(1, f.codec.grants.size());
        CoopLightWorld reloaded = f.newWorld(); reloaded.installBridge(true, "guest");
        f.codec.failGrant = false;
        assertThrows(IllegalStateException.class, () -> reloaded.acceptOutcome(accepted));
        assertEquals(1, f.codec.grants.size()); assertEquals(1, f.codec.grantAttempts);
        assertEquals(1, reloaded.readOnlyStateCounts().get("uncertain"));
    }

    @Test void hostReloadRetriesCommittedLocalRewardWhenFailurePrecedesNativeGrant() {
        Fixture f = new Fixture(); f.world.installBridge(false, "host"); f.world.captureHost();
        f.codec.failDespawn = true;
        assertThrows(IllegalStateException.class,
                () -> CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, false));
        assertEquals(Map.of("pending", 1, "results", 1, "tombstones", 1, "applied", 0, "uncertain", 0), f.world.readOnlyStateCounts());
        CoopLightWorld reloaded = f.newWorld(); reloaded.installBridge(false, "host");
        f.codec.failDespawn = false;
        assertTrue(reloaded.captureHost().isEmpty());
        assertTrue(reloaded.captureHost().isEmpty());
        assertEquals(List.of("burn:0.5:7"), f.codec.grants);
        assertTrue(reloaded.pendingRequests().isEmpty());
    }

    @Test void diagnosticsDoNotAdoptNativeLightsOrWriteLedgerState() {
        Fixture f = new Fixture();
        assertTrue(f.world.readOnlySnapshot().isEmpty());
        assertEquals(Map.of("pending", 0, "results", 0, "tombstones", 0, "applied", 0, "uncertain", 0), f.world.readOnlyStateCounts());
        assertTrue(f.saved.isEmpty()); assertNull(CoopLightWorld.ownedId(f.lights.get(0).entity));
    }

    @Test void pendingDwellerConsumptionBlocksAnotherDialogUntilItsResult() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        String id = id(); Light light = f.lights.get(0); light.tag(id); light.tags.add(Tags.DWELLER_LIGHT);
        assertTrue(f.world.isAvailable(id));
        assertFalse(CoopLightBridge.allowNativeDespawn(light.entity));
        assertFalse(f.world.isAvailable(id));
        assertFalse(CoopLightBridge.allowDwellerDialog(light.entity, f.local.entity));
        CoopLightCodec.Request request = f.world.pendingRequests().get(0);
        f.world.acceptOutcome(outcome(request, "guest", false, 0, 0));
        assertTrue(f.world.isAvailable(id));
    }

    @Test void failedFinalRewardCommitKeepsUncertainGuardAndNeverRepeatsSuccessfulGrant() {
        Fixture f = new Fixture(); f.world.installBridge(true, "guest");
        f.lights.get(0).tag(id());
        CoopLightBridge.handlePulse(f.lights.get(0).entity, f.local.entity, true);
        CoopLightCodec.Request request = f.world.pendingRequests().get(0);
        CoopLightCodec.Outcome accepted = outcome(request, "guest", true, 1, 7);
        f.failFinalRewardCommit = true;
        assertThrows(IllegalStateException.class, () -> f.world.acceptOutcome(accepted));
        assertEquals(1, f.codec.grants.size());
        f.failFinalRewardCommit = false;
        CoopLightWorld reloaded = f.newWorld(); reloaded.installBridge(true, "guest");
        assertThrows(IllegalStateException.class, () -> reloaded.acceptOutcome(accepted));
        assertEquals(1, f.codec.grants.size()); assertEquals(1, f.codec.grantAttempts);
        assertEquals(1, reloaded.readOnlyStateCounts().get("uncertain"));
    }

    private static String id() { return "light:" + UUID.randomUUID(); }
    private static CoopLightCodec.Request request(String id, CoopLightCodec.Action action) {
        return new CoopLightCodec.Request(id, UUID.randomUUID().toString(), action);
    }
    private static CoopLightCodec.Outcome outcome(CoopLightCodec.Request request, String actor, boolean accepted, float bonus, int points) {
        return new CoopLightCodec.Outcome(request.requestId(), request.lightId(), actor, request.action(), accepted,
                null, bonus, points, accepted ? "accepted" : "rejected");
    }
    private static CoopLightRecord record(String id, String nativeId, boolean dweller) {
        return new CoopLightRecord(1, id, nativeId, 0, 0, 0xffc8c8ff, 0.5f, 1.5f, 1,
                900, 2700, 1, 25, 0, dweller, dweller ? 17 : 0);
    }
    private static class ModdedLight extends AbyssalLightEntityPlugin { }

    private static final class Fixture {
        final List<SectorEntityToken> entities = new ArrayList<>();
        final List<Light> lights = new ArrayList<>();
        final Map<String, Object> saved = new HashMap<>();
        final Map<CustomCampaignEntityAPI, Light> byEntity = new HashMap<>();
        final LocationAPI hyper;
        final LocationAPI system;
        final SectorAPI sector;
        final Actor local;
        final Actor remote;
        final Codec codec = new Codec();
        final CoopLightWorld world;
        SectorEntityToken dialogTarget;
        boolean failFinalRewardCommit;
        boolean remoteAvailable = true;
        Fixture() { this(true); }
        Fixture(boolean initialLight) {
            hyper = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isHyperspace" -> true;
                case "getAllEntities" -> new ArrayList<>(entities);
                case "removeEntity" -> { entities.remove(args[0]); yield null; }
                default -> throw unexpected(method.getName());
            });
            system = proxy(LocationAPI.class, (self, method, args) -> method.getName().equals("isHyperspace") ? false : null);
            local = new Actor(this, true); remote = new Actor(this, false);
            MemoryAPI memory = proxy(MemoryAPI.class, (self, method, args) -> switch (method.getName()) {
                case "get" -> saved.get(args[0]);
                case "contains" -> saved.containsKey(args[0]);
                case "set" -> {
                    if (failFinalRewardCommit && CoopLightWorld.STATE_KEY.equals(args[0])
                            && saved.get(args[0]) instanceof String old && old.contains("\"U")
                            && args[1] instanceof String next && !next.contains("\"U")) {
                        throw new IllegalStateException("Final native-memory commit failed");
                    }
                    saved.put((String) args[0], args[1]); yield null;
                }
                case "unset" -> { saved.remove(args[0]); yield null; }
                default -> throw unexpected(method.getName());
            });
            InteractionDialogAPI dialog = proxy(InteractionDialogAPI.class, (self, method, args) ->
                    method.getName().equals("getInteractionTarget") ? dialogTarget : null);
            CampaignUIAPI ui = proxy(CampaignUIAPI.class, (self, method, args) ->
                    method.getName().equals("getCurrentInteractionDialog") && dialogTarget != null ? dialog : null);
            sector = proxy(SectorAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getHyperspace" -> hyper;
                case "getMemoryWithoutUpdate" -> memory;
                case "getCampaignUI" -> ui;
                case "getPlayerFleet" -> local.entity;
                default -> throw unexpected(method.getName());
            });
            world = newWorld(); if (initialLight) add("native");
        }
        CoopLightWorld newWorld() { return new CoopLightWorld(() -> sector, () -> remoteAvailable ? remote.entity : null, codec); }
        Light add(String nativeId) {
            Light light = new Light(this, nativeId); lights.add(light); entities.add(light.entity);
            byEntity.put(light.entity, light); return light;
        }
        final class Codec implements CoopLightWorld.NativeCodec {
            final Set<String> failCapture = new HashSet<>();
            final List<String> grants = new ArrayList<>();
            int creates, applies;
            boolean failGrant, partialGrantBeforeFailure, failDespawn;
            int grantAttempts;
            @Override public CoopLightRecord capture(CustomCampaignEntityAPI entity, String id) {
                if (failCapture.contains(entity.getId())) throw new IllegalStateException("Unsupported native recipe");
                Light l = byEntity.get(entity); CoopLightRecord r = record(id, entity.getId(), l.tags.contains(Tags.DWELLER_LIGHT));
                return new CoopLightRecord(r.version(), id, r.hostEntityId(), l.position.x, l.position.y, r.colorArgb(),
                        r.frequencyMultMin(), r.frequencyMultMax(), r.frequencyChangeMult(), r.size(), r.detectedRange(),
                        r.bonusMult(), r.remainingDays(), r.pointsOverride(), r.dweller(), r.salvageSeed());
            }
            @Override public CustomCampaignEntityAPI create(LocationAPI location, CoopLightRecord record) { creates++; return add("created" + creates).entity; }
            @Override public void apply(CustomCampaignEntityAPI entity, CoopLightRecord record) { applies++; byEntity.get(entity).position.set(record.x(), record.y()); }
            @Override public boolean isDespawning(CustomCampaignEntityAPI entity) { return byEntity.get(entity).fading; }
            @Override public void despawn(CustomCampaignEntityAPI entity, boolean expand) {
                if (failDespawn) throw new IllegalStateException("Native despawn failed");
                byEntity.get(entity).fading = true;
            }
            @Override public float pulseRange(CampaignFleetAPI actor) { return 1000; }
            @Override public float brightness(CampaignFleetAPI actor, CustomCampaignEntityAPI entity) { return 0.5f; }
            @Override public void grant(CampaignFleetAPI actor, boolean sensor, float bonus, int points) {
                grantAttempts++;
                if (failGrant && !partialGrantBeforeFailure) throw new IllegalStateException("Native grant failed");
                assertSame(local.entity, actor); grants.add((sensor ? "sensor:" : "burn:") + bonus + ":" + points);
                if (failGrant) throw new IllegalStateException("Native grant failed after a side effect");
            }
        }
    }

    private static final class Light {
        final Map<String, Object> memory = new HashMap<>();
        final Set<String> tags = new HashSet<>();
        final Vector2f position = new Vector2f();
        final CustomCampaignEntityAPI entity;
        Object plugin = new AbyssalLightEntityPlugin();
        boolean fading, inHyper = true, alive = true;
        Light(Fixture f, String nativeId) {
            MemoryAPI api = ApiProxies.memory(memory);
            entity = proxy(CustomCampaignEntityAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> nativeId;
                case "getCustomPlugin" -> plugin;
                case "getMemoryWithoutUpdate" -> api;
                case "getContainingLocation" -> inHyper ? f.hyper : f.system;
                case "getLocation" -> position;
                case "getRadius" -> 100f;
                case "isAlive" -> alive;
                case "hasTag" -> tags.contains(args[0]);
                default -> throw unexpected(method.getName());
            });
        }
        Light tag(String id) { memory.put(CoopLightWorld.LIGHT_ID_KEY, id); return this; }
    }

    private static final class Actor {
        final Vector2f position = new Vector2f();
        final Map<String, Object> saved = new HashMap<>();
        final CampaignFleetAPI entity;
        boolean inHyper = true;
        boolean alive = true;
        boolean located = true;
        Actor(Fixture f, boolean player) {
            MemoryAPI memory = ApiProxies.memory(saved);
            entity = proxy(CampaignFleetAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isPlayerFleet" -> player;
                case "isAlive" -> alive;
                case "getContainingLocation" -> located ? inHyper ? f.hyper : f.system : null;
                case "getMemoryWithoutUpdate" -> memory;
                case "getLocation" -> position;
                case "getRadius" -> 50f;
                default -> throw unexpected(method.getName());
            });
        }
    }

    private static AssertionError unexpected(String method) { return new AssertionError("Unexpected native call " + method); }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString" -> type.getSimpleName() + " proxy";
                default -> throw unexpected(method.getName());
            };
            return handler.invoke(self, method, args);
        }));
    }
}
