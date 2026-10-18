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
        final LocationAPI hyper = stub(LocationAPI.class, (name, args) -> "isHyperspace".equals(name) ? true : null);
        final CampaignFleetAPI player = stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
            case "getMemoryWithoutUpdate" -> ApiProxies.memory(Map.of());
            case "isPlayerFleet" -> true;
            default -> null;
        });
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
            case "getPlayerFleet" -> player;
            case "getCampaignUI" -> ui;
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
                @Override public boolean allowDwellerDialog(SectorEntityToken light) { return accepted; }
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
