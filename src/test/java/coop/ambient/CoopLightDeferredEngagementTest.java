package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.rulecmd.DwellerCMD;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class CoopLightDeferredEngagementTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;

    @BeforeEach void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        SettingsAPI white = ApiProxies.whiteSettings();
        Global.setSettings((SettingsAPI) Proxy.newProxyInstance(SettingsAPI.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class}, (proxy, method, args) ->
                        "getVersionString".equals(method.getName()) ? CoopPresenceRegistry.PINNED_VERSION
                                : Proxy.getInvocationHandler(white).invoke(proxy, method, args)));
        CoopLightBridge.clear();
    }

    @AfterEach void cleanup() {
        CoopLightBridge.clear();
        Global.setSector(previousSector);
        Global.setSettings(previousSettings);
    }

    @Test void nativeManualChoiceWaitsForOwnershipThenResumesExactlyOnceWhilePaused() {
        Scene scene = new Scene();
        Command command = new Command();
        Random random = new Random(123);

        assertTrue(command.begin(scene.dialog, DwellerCMD.DwellerStrength.MEDIUM, random));
        assertTrue(command.begin(scene.dialog, DwellerCMD.DwellerStrength.HIGH, new Random(999)));
        assertEquals(0, command.calls);
        assertEquals(1, scene.scripts.size(), "repeated selections retain the first native action");
        EveryFrameScript pending = scene.scripts.get(0);
        assertTrue(pending.runWhilePaused());
        pending.advance(0f);
        assertEquals(0, command.calls);
        scene.accepted = true;
        pending.advance(0f);
        pending.advance(0f);

        assertEquals(1, command.calls);
        assertEquals(DwellerCMD.DwellerStrength.MEDIUM, command.strength);
        assertSame(random, command.random, "the original native random stream is retained");
        assertTrue(pending.isDone());
    }

    @Test void closedRetargetedExpiredOrFadingDialogsNeverStartADeferredBattle() {
        for (String cancellation : List.of("closed", "retargeted", "dead", "fading")) {
            Scene scene = new Scene();
            Command command = new Command();
            command.begin(scene.dialog, DwellerCMD.DwellerStrength.LOW, new Random(1));
            switch (cancellation) {
                case "closed" -> scene.open = false;
                case "retargeted" -> scene.target = scene.player;
                case "dead" -> scene.alive = false;
                case "fading" -> scene.fading = true;
            }
            scene.accepted = true;
            scene.scripts.get(0).advance(0f);
            assertEquals(0, command.calls, cancellation);
            assertTrue(scene.scripts.get(0).isDone(), cancellation);
        }
    }

    @Test void sessionEndOrReplacementCannotResurrectAnOldNativeChoice() {
        Scene scene = new Scene();
        Command command = new Command();
        command.begin(scene.dialog, DwellerCMD.DwellerStrength.EXTREME, new Random(1));
        EveryFrameScript pending = scene.scripts.get(0);

        CoopLightBridge.uninstall(scene.owner);
        scene.accepted = true;
        CoopLightBridge.install(true, scene.newOwner());
        pending.advance(0f);

        assertTrue(pending.isDone());
        assertEquals(0, command.calls);
    }

    @Test void aLosingClaimCannotCreateTheFleetAndTheRejectionCanCloseItsDialog() {
        Scene scene = new Scene();
        Command command = new Command();
        command.begin(scene.dialog, DwellerCMD.DwellerStrength.HIGH, new Random(1));
        for (int frame = 0; frame < 5; frame++) scene.scripts.get(0).advance(0f);
        assertEquals(0, command.calls);
        scene.open = false;
        scene.scripts.get(0).advance(0f);
        assertTrue(scene.scripts.get(0).isDone());
    }

    @Test void anAlreadyOwnedLightStartsImmediatelyAndUnmanagedNativeBehaviorStaysImmediate() {
        Scene scene = new Scene();
        Command command = new Command();
        scene.accepted = true;
        command.begin(scene.dialog, DwellerCMD.DwellerStrength.LOW, new Random(1));
        assertEquals(1, command.calls);
        assertTrue(scene.scripts.isEmpty());

        scene.memory.clear();
        scene.accepted = false;
        command.begin(scene.dialog, DwellerCMD.DwellerStrength.HIGH, new Random(2));
        assertEquals(2, command.calls);
        assertTrue(scene.scripts.isEmpty());
    }

    @Test void aChoiceAfterSessionEndOffersAnExitWithoutStartingOrSchedulingCombat() {
        Scene scene = new Scene();
        scene.optionIds.addAll(List.of("abyssalLight_maxSensors", "abyssalLight_wait", "abyssalLight_emCon"));
        CoopLightBridge.uninstall(scene.owner);
        Command command = new Command();
        assertTrue(command.begin(scene.dialog, DwellerCMD.DwellerStrength.MEDIUM, new Random(1)));
        assertEquals(List.of("defaultLeave"), scene.optionIds);
        assertEquals(0, command.calls);
        assertTrue(scene.scripts.isEmpty());
    }

    @Test void unavailableSectorOffersAnExitInsteadOfLeavingOnlyDwellerBattleChoices() {
        Scene scene = new Scene();
        scene.optionIds.addAll(List.of("abyssalLight_maxSensors", "abyssalLight_wait", "abyssalLight_emCon"));
        Global.setSector(null);
        int[] calls = {0};
        assertTrue(CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0]++));
        assertEquals(List.of("defaultLeave"), scene.optionIds);
        assertEquals(0, calls[0]);
        assertTrue(scene.scripts.isEmpty());
    }

    @Test void diagnosticsAreImmutablePrimitiveSnapshotsAndCaptureTheOriginalTokenAfterTheGate() {
        Scene scene = new Scene();
        scene.queueTokenOnGate = true;
        int[] calls = {0};
        assertTrue(CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0]++));
        Map<String, Object> first = CoopLightBridge.deferredDiagnostics();
        Map<String, Object> queued = rows(first, "pending").get(0);
        assertEquals("original-claim-token", queued.get("originalClaimToken"));
        assertEquals(true, queued.get("tokenObserved"));
        assertEquals("", queued.get("tokenReadError"));
        assertEquals(1, scene.permissionCalls);
        assertEquals(1, scene.tokenReads);
        assertEquals(queued.get("queuedSequence"), lastEvent((String) queued.get("observationId")).get("sequence"));
        for (String key : List.of("ownerIdentity", "sectorIdentity", "playerIdentity", "dialogIdentity", "lightIdentity"))
            assertFalse(((String) queued.get(key)).isBlank(), key);

        scene.token = "later-claim-token";
        scene.fading = true;
        assertTrue(CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0] += 10));
        assertEquals(first, CoopLightBridge.deferredDiagnostics(), "reads and repeated selections cannot retire or replace a pending attempt");
        assertEquals(1, scene.permissionCalls);
        assertEquals(1, scene.tokenReads);
        assertEquals(0, calls[0]);
        assertTrue(scene.scripts.stream().noneMatch(EveryFrameScript::isDone));
        assertPrimitives(first);
        assertThrows(UnsupportedOperationException.class, () -> first.put("pendingCount", 42));
        assertThrows(UnsupportedOperationException.class, () -> rows(first, "journal").clear());
        assertThrows(UnsupportedOperationException.class, () -> queued.put("actionExecutionCount", 42));
        assertEquals(1, scene.memory.size(), "diagnostics never add saved state");
    }

    @Test void executionCountersDistinguishInvocationFromReturnAndNeverRepeat() {
        Scene scene = new Scene();
        int[] calls = {0};
        CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> {
            calls[0]++;
            List<Map<String, Object>> journal = rows(CoopLightBridge.deferredDiagnostics(), "journal");
            Map<String, Object> invoked = journal.get(journal.size() - 1);
            assertEquals("action_invoked", invoked.get("reason"));
            assertEquals(1, invoked.get("actionExecutionCount"));
            assertEquals(0, invoked.get("actionReturnCount"));
        });
        String id = (String) rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId");
        scene.accepted = true;
        EveryFrameScript script = scene.scripts.get(0);
        script.advance(0f);
        Map<String, Object> after = CoopLightBridge.deferredDiagnostics();
        script.advance(0f);
        assertEquals(after, CoopLightBridge.deferredDiagnostics());
        assertEquals(1, calls[0]);
        assertEquals(0, after.get("pendingCount"));
        Map<String, Object> returned = lastEvent(id);
        assertEquals("executed", returned.get("state"));
        assertEquals("action_returned", returned.get("reason"));
        assertEquals(1, returned.get("actionExecutionCount"));
        assertEquals(1, returned.get("actionReturnCount"));
        assertEquals("", returned.get("actionFailure"));
    }

    @Test void actionFailureRemainsVisibleWithoutBeingReportedAsSuccessfulOrRetried() {
        Scene scene = new Scene();
        IllegalStateException failure = new IllegalStateException("native action failed");
        CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> { throw failure; });
        String id = (String) rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId");
        scene.accepted = true;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> scene.scripts.get(0).advance(0f)));
        Map<String, Object> failed = lastEvent(id);
        assertEquals("action_failed", failed.get("reason"));
        assertEquals(1, failed.get("actionExecutionCount"));
        assertEquals(0, failed.get("actionReturnCount"));
        assertTrue(((String) failed.get("actionFailure")).contains("native action failed"));
        scene.scripts.get(0).advance(0f);
        assertSame(failed, lastEvent(id));
        assertEquals(0, CoopLightBridge.deferredDiagnostics().get("pendingCount"));
    }

    @Test void eachNativeCancellationPredicateRecordsItsReasonWithoutCallingTheGateAgain() {
        Map<String, String> reasons = Map.of("closed", "dialog_closed_or_replaced", "retargeted", "dialog_target_changed",
                "dead", "light_not_alive", "fading", "light_fading", "sector", "sector_changed",
                "player", "player_changed", "ui", "campaign_ui_unavailable");
        for (var example : reasons.entrySet()) {
            Scene scene = new Scene();
            int[] calls = {0};
            CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0]++);
            String id = (String) rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId");
            switch (example.getKey()) {
                case "closed" -> scene.open = false;
                case "retargeted" -> scene.target = scene.player;
                case "dead" -> scene.alive = false;
                case "fading" -> scene.fading = true;
                case "sector" -> Global.setSector(null);
                case "player" -> scene.currentPlayer = null;
                case "ui" -> scene.uiAvailable = false;
            }
            scene.accepted = true;
            scene.scripts.get(0).advance(0f);
            Map<String, Object> cancelled = lastEvent(id);
            assertEquals("cancelled", cancelled.get("state"), example.getKey());
            assertEquals(example.getValue(), cancelled.get("reason"));
            assertEquals(0, cancelled.get("actionExecutionCount"));
            assertEquals(0, cancelled.get("actionReturnCount"));
            assertEquals(1, scene.permissionCalls, "cancellation must precede any permission check");
            assertEquals(0, calls[0]);
        }
    }

    @Test void bridgeReplacementUninstallAndClearKeepExplicitCancellationReceiptsAcrossResets() {
        for (String change : List.of("replacement", "uninstall", "clear", "explicit")) {
            Scene scene = new Scene();
            CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> fail("cancelled action ran"));
            String id = (String) rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId");
            switch (change) {
                case "replacement" -> CoopLightBridge.install(true, scene.newOwner());
                case "uninstall" -> CoopLightBridge.uninstall(scene.owner);
                case "clear" -> CoopLightBridge.clear();
                case "explicit" -> CoopLightDeferredEngagement.clear();
            }
            assertTrue(scene.scripts.get(0).isDone());
            assertEquals("cancelled", lastEvent(id).get("state"));
            assertEquals(switch (change) {
                case "replacement" -> "bridge_replaced";
                case "uninstall" -> "bridge_uninstalled";
                case "clear" -> "bridge_cleared";
                default -> "explicit_reset";
            }, lastEvent(id).get("reason"));
            CoopLightBridge.clear();
            assertEquals("cancelled", lastEvent(id).get("state"), "global resets retain primitive evidence");
        }
    }

    @Test void aRetiredOwnerCannotCancelOrRetokenizeTheReplacementAttempt() {
        Scene scene = new Scene();
        scene.token = "old-token";
        int[] calls = {0};
        CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0] += 100);
        String oldId = (String) rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId");
        CoopLightBridge.install(true, scene.newOwner());
        scene.token = "new-token";
        CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0]++);
        Map<String, Object> current = rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0);
        String newId = (String) current.get("observationId");
        assertNotEquals(oldId, newId);
        assertEquals("old-token", lastEvent(oldId).get("originalClaimToken"));
        assertEquals("new-token", current.get("originalClaimToken"));
        CoopLightBridge.uninstall(scene.owner);
        assertEquals(newId, rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId"));
        scene.accepted = true;
        scene.scripts.forEach(script -> script.advance(0f));
        assertEquals(1, calls[0]);
        assertEquals("cancelled", lastEvent(oldId).get("state"));
        assertEquals("action_returned", lastEvent(newId).get("reason"));
    }

    @Test void journalIsBoundedAndNewAttemptsHaveDistinctIdsWithoutSavedReferences() {
        Scene scene = new Scene();
        long beforeEvicted = (long) CoopLightBridge.deferredDiagnostics().get("evictedEvents");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 300; i++) {
            CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> fail("cancelled action ran"));
            ids.add((String) rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0).get("observationId"));
            CoopLightDeferredEngagement.clear("bounded_test");
        }
        Map<String, Object> result = CoopLightBridge.deferredDiagnostics();
        assertEquals(300, ids.size());
        assertEquals(256, rows(result, "journal").size());
        assertTrue((long) result.get("evictedEvents") - beforeEvicted >= 344);
        assertEquals(0, result.get("pendingCount"));
        assertPrimitives(result);
        assertEquals(1, scene.memory.size());
    }

    @Test void aMissingOrFailedPassiveTokenProviderDoesNotChangeNativeDeferralBehavior() {
        for (boolean failing : List.of(false, true)) {
            Scene scene = new Scene();
            if (failing) scene.tokenFailure = new IllegalStateException("token probe unavailable");
            int[] calls = {0};
            CoopLightDeferredEngagement.deferIfNeeded(scene.dialog, scene.light, scene.player, () -> calls[0]++);
            Map<String, Object> row = rows(CoopLightBridge.deferredDiagnostics(), "pending").get(0);
            assertEquals(false, row.get("tokenObserved"));
            assertEquals("", row.get("originalClaimToken"));
            assertEquals(failing, !((String) row.get("tokenReadError")).isBlank());
            scene.accepted = true;
            scene.scripts.get(0).advance(0f);
            assertEquals(1, calls[0]);
            assertEquals(1, scene.tokenReads);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> snapshot, String key) {
        return (List<Map<String, Object>>) snapshot.get(key);
    }

    private static Map<String, Object> lastEvent(String observationId) {
        return rows(CoopLightBridge.deferredDiagnostics(), "journal").stream()
                .filter(row -> observationId.equals(row.get("observationId"))).reduce((a, b) -> b).orElseThrow();
    }

    private static void assertPrimitives(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> { assertInstanceOf(String.class, key); assertPrimitives(child); });
        } else if (value instanceof List<?> list) list.forEach(CoopLightDeferredEngagementTest::assertPrimitives);
        else assertTrue(value instanceof String || value instanceof Number || value instanceof Boolean,
                    "diagnostic value must be an immutable primitive: " + value.getClass());
    }

    private static final class Command extends DwellerCMD {
        int calls;
        DwellerStrength strength;
        Random random;
        boolean begin(InteractionDialogAPI dialog, DwellerStrength strength, Random random) {
            return engageFleet(dialog, Map.of(), null, strength, random);
        }
        @Override protected boolean engageFleetNow(InteractionDialogAPI dialog, Map<String, MemoryAPI> memories,
                                                  MemoryAPI memory, DwellerStrength strength, Random random) {
            calls++;
            this.strength = strength;
            this.random = random;
            return true;
        }
    }

    private static final class Scene {
        final List<EveryFrameScript> scripts = new ArrayList<>();
        final List<String> optionIds = new ArrayList<>();
        final Map<String, Object> memory = new HashMap<>();
        boolean accepted, fading;
        boolean open = true, alive = true;
        boolean uiAvailable = true, queueTokenOnGate;
        int permissionCalls, tokenReads;
        String token;
        RuntimeException tokenFailure;
        final LocationAPI hyper = stub(LocationAPI.class, (name, args) -> "isHyperspace".equals(name) ? true : null);
        final CampaignFleetAPI player = stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
            case "getMemoryWithoutUpdate" -> ApiProxies.memory(Map.of());
            case "isPlayerFleet" -> true;
            default -> null;
        });
        CampaignFleetAPI currentPlayer = player;
        final AbyssalLightEntityPlugin plugin = new AbyssalLightEntityPlugin();
        final SectorEntityToken light = stub(CustomCampaignEntityAPI.class, (name, args) -> switch (name) {
            case "getCustomPlugin" -> plugin;
            case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory);
            case "getContainingLocation" -> hyper;
            case "isAlive" -> alive;
            case "hasTag" -> Tags.FADING_OUT_AND_EXPIRING.equals(args[0]) && fading;
            default -> null;
        });
        SectorEntityToken target = light;
        final OptionPanelAPI options = stub(OptionPanelAPI.class, (name, args) -> switch (name) {
            case "clearOptions" -> { optionIds.clear(); yield null; }
            case "addOption" -> { optionIds.add((String) args[1]); yield null; }
            default -> null;
        });
        final InteractionDialogAPI dialog = stub(InteractionDialogAPI.class, (name, args) -> switch (name) {
            case "getInteractionTarget" -> target;
            case "getOptionPanel" -> options;
            default -> null;
        });
        final CampaignUIAPI ui = stub(CampaignUIAPI.class, (name, args) ->
                "getCurrentInteractionDialog".equals(name) && open ? dialog : null);
        final SectorAPI sector = stub(SectorAPI.class, (name, args) -> switch (name) {
            case "getPlayerFleet" -> currentPlayer;
            case "getCampaignUI" -> uiAvailable ? ui : null;
            case "isPaused" -> true;
            case "addTransientScript" -> { scripts.add((EveryFrameScript) args[0]); yield null; }
            default -> null;
        });
        final CoopLightBridge.Handler owner = newOwner();

        Scene() {
            memory.put(CoopLightBridge.LIGHT_ID_KEY, "light:01234567-89ab-4def-8123-0123456789ab");
            Global.setSector(sector);
            CoopLightBridge.install(true, owner);
        }

        CoopLightBridge.Handler newOwner() {
            return new CoopLightBridge.Handler() {
                @Override public void requestPulse(SectorEntityToken light, boolean sensor) { }
                @Override public void requestDwellerConsumption(SectorEntityToken light) { }
                @Override public boolean allowDwellerDialog(SectorEntityToken light) {
                    permissionCalls++;
                    if (queueTokenOnGate && token == null) token = "original-claim-token";
                    return accepted;
                }
                @Override public String claimToken(String canonicalLightId) {
                    tokenReads++;
                    if (tokenFailure != null) throw tokenFailure;
                    return token;
                }
            };
        }
    }

    private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + "Proxy";
                    default -> {
                        Object answer = answers.apply(method.getName(), args);
                        yield answer == null ? ProxyDefaults.defaultValue(method.getReturnType()) : answer;
                    }
                }));
    }
}
