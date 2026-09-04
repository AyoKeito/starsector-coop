package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import coop.testing.RecordingNetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;

/**
 * {@code SpecialItemData} stacks — AI cores, nanoforges, blueprints, modspecs (Phase 12c gap 2c).
 *
 * <p>Two things are being pinned. A special is identified by <em>both</em> its id and its nullable
 * data payload, so a modspec whose data (the hullmod it teaches) is dropped, or an AI core whose null
 * data is reconstructed as {@code ""}, is a different item as far as {@code SpecialItemData.equals}
 * and therefore {@code removeItems} are concerned. And a special stack used to fall through the pod
 * classifier's {@code default -> COMMODITY}, which silently re-materialized a jettisoned AI core on
 * the partner's client as a commodity of the same id — i.e. as nothing.
 */
class CoopSpecialStackSyncTest {

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    // ---- Item id codec ---------------------------------------------------------------------------

    @Test
    void nullDataRoundTripsAsNullNotAsEmptyString() {
        String packed = CoopMarketSync.specialItemId("alpha_core", null);

        assertEquals("alpha_core", CoopMarketSync.specialId(packed));
        assertNull(CoopMarketSync.specialData(packed),
                "an AI core carries no data; reconstructing \"\" instead of null makes it un-removable");
        assertEquals(new SpecialItemData("alpha_core", null),
                new SpecialItemData(CoopMarketSync.specialId(packed), CoopMarketSync.specialData(packed)));
    }

    @Test
    void modspecDataSurvives() {
        String packed = CoopMarketSync.specialItemId("modspec", "converted_hangar");

        assertEquals("modspec", CoopMarketSync.specialId(packed));
        assertEquals("converted_hangar", CoopMarketSync.specialData(packed));
        assertEquals(new SpecialItemData("modspec", "converted_hangar"),
                new SpecialItemData(CoopMarketSync.specialId(packed), CoopMarketSync.specialData(packed)));
    }

    @Test
    void emptyDataIsNormalizedToNull() {
        // The engine's own null and an empty-string data are the same "no payload" state; collapsing
        // them keeps SpecialItemData.equals from splitting one item into two.
        assertNull(CoopMarketSync.specialData(CoopMarketSync.specialItemId("pristine_nanoforge", "")));
    }

    @Test
    void delimiterCharactersInIdOrDataSurviveTheStockLine() {
        String packed = CoopMarketSync.specialItemId("weird|id", "data\\with|pipes\nand newline");
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(
                List.of(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SPECIAL, packed, 2, 0f))));

        assertEquals(1, back.size());
        assertEquals("weird|id", CoopMarketSync.specialId(back.get(0).itemId()));
        assertEquals("data\\with|pipes\nand newline", CoopMarketSync.specialData(back.get(0).itemId()));
    }

    // ---- classify(): the host captures specials as SPECIAL, not as nothing -----------------------

    @Test
    void hostSnapshotCapturesSpecialStacks() {
        FakeSectorWithSpecials fake = new FakeSectorWithSpecials();
        Global.setSector(fake.sector());
        RecordingNetService net = new RecordingNetService(CoopConnectionRole.HOST);

        new CoopCampaignReplicator(net, activeHostSession(), () -> 1L)
                .handle(CoopMessages.marketOpen("session-a", 1L, 1L, "sindria", "guest-player"));

        List<CoopMarketSync.StockItem> items = CoopMarketSync.decodeStock(
                CoopMessages.requiredPayloadString(net.lastOfType(CoopMessages.Type.MARKET_SNAPSHOT), "stock"));
        Map<String, CoopMarketSync.ItemKind> byId = new LinkedHashMap<>();
        for (CoopMarketSync.StockItem item : items) {
            byId.put(item.itemId(), item.kind());
        }

        assertEquals(CoopMarketSync.ItemKind.COMMODITY, byId.get("fuel"));
        assertEquals(CoopMarketSync.ItemKind.SPECIAL,
                byId.get(CoopMarketSync.specialItemId("alpha_core", null)),
                "an AI core must be captured as a SPECIAL: " + byId.keySet());
        assertEquals(CoopMarketSync.ItemKind.SPECIAL,
                byId.get(CoopMarketSync.specialItemId("modspec", "converted_hangar")),
                "a modspec's hullmod id is part of its identity: " + byId.keySet());
    }

    // ---- spawnKindOf(): a jettisoned special is a SPECIAL, not a mangled commodity ---------------

    @Test
    void jettisonedSpecialsRideThePodDeltaAsSpecials() {
        Global.setSector(new FakeSectorWithSpecials().sector());
        RecordingNetService net = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(net, activeGuestSession(), () -> 1L);

        replicator.onPlayerLeftCargoPods(pods());

        CoopWorldEntitySpawn spawn = CoopWorldEntitySpawn.decode(CoopMessages.requiredPayloadString(
                net.lastOfType(CoopMessages.Type.WORLD_DELTA), "newStateJson"));

        String coreKey = CoopWorldEntitySpawn.key(CoopWorldEntitySpawn.ItemKind.SPECIAL,
                CoopMarketSync.specialItemId("alpha_core", null));
        assertEquals(1, spawn.contents().get(coreKey),
                "an AI core in a pod must ride as SPECIAL, not fall through to COMMODITY: "
                        + spawn.contents().keySet());
        assertEquals(30, spawn.contents().get(CoopWorldEntitySpawn.key(
                CoopWorldEntitySpawn.ItemKind.COMMODITY, "fuel")));
        assertTrue(spawn.contents().keySet().stream().noneMatch(k -> k.equals("COMMODITY:alpha_core")),
                "the old default mangled the core into a commodity of the same id");
    }

    // ---- Harness ---------------------------------------------------------------------------------

    private static CargoStackAPI specialStack(String id, String data, int size) {
        SpecialItemData special = new SpecialItemData(id, data);
        return (CargoStackAPI) Proxy.newProxyInstance(
                CargoStackAPI.class.getClassLoader(),
                new Class<?>[]{CargoStackAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isSpecialStack" -> Boolean.TRUE;
                    case "isCommodityStack", "isWeaponStack", "isFighterWingStack" -> Boolean.FALSE;
                    case "getSpecialDataIfSpecial" -> special;
                    case "getSize" -> (float) size;
                    case "toString" -> "FakeSpecialStack[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static CargoStackAPI commodityStack(String id, int size) {
        return (CargoStackAPI) Proxy.newProxyInstance(
                CargoStackAPI.class.getClassLoader(),
                new Class<?>[]{CargoStackAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isCommodityStack" -> Boolean.TRUE;
                    case "isSpecialStack", "isWeaponStack", "isFighterWingStack" -> Boolean.FALSE;
                    case "getCommodityId" -> id;
                    case "getSize" -> (float) size;
                    case "toString" -> "FakeStack[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static CargoAPI cargoWith(List<CargoStackAPI> stacks) {
        return (CargoAPI) Proxy.newProxyInstance(
                CargoAPI.class.getClassLoader(),
                new Class<?>[]{CargoAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStacksCopy" -> stacks;
                    case "toString" -> "FakeCargo";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static List<CargoStackAPI> mixedStacks() {
        List<CargoStackAPI> stacks = new ArrayList<>();
        stacks.add(commodityStack("fuel", 30));
        stacks.add(specialStack("alpha_core", null, 1));
        stacks.add(specialStack("modspec", "converted_hangar", 2));
        return stacks;
    }

    /** Cargo pods holding fuel plus an AI core. */
    private static SectorEntityToken pods() {
        CargoAPI cargo = cargoWith(mixedStacks());
        MemoryAPI memory = (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeMemory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        LocationAPI location = (LocationAPI) Proxy.newProxyInstance(
                LocationAPI.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "corvus";
                    case "toString" -> "FakeLocation";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return (SectorEntityToken) Proxy.newProxyInstance(
                CustomCampaignEntityAPI.class.getClassLoader(),
                new Class<?>[]{CustomCampaignEntityAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCargo" -> cargo;
                    case "getId" -> "pods-1";
                    case "getContainingLocation" -> location;
                    case "getMemoryWithoutUpdate" -> memory;
                    case "getLocation" -> new Vector2f(10f, 20f);
                    case "getVelocity" -> new Vector2f(1f, 2f);
                    case "toString" -> "FakePods";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    /** A "sindria" whose open market holds fuel, an AI core and a modspec. */
    private static final class FakeSectorWithSpecials {
        private SectorAPI sector() {
            CargoAPI cargo = cargoWith(mixedStacks());
            SubmarketPlugin plugin = (SubmarketPlugin) Proxy.newProxyInstance(
                    SubmarketPlugin.class.getClassLoader(),
                    new Class<?>[]{SubmarketPlugin.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo", "getCargoNullOk" -> cargo;
                        case "toString" -> "FakePlugin";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            SubmarketAPI submarket = (SubmarketAPI) Proxy.newProxyInstance(
                    SubmarketAPI.class.getClassLoader(),
                    new Class<?>[]{SubmarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPlugin" -> plugin;
                        case "getCargo", "getCargoNullOk" -> cargo;
                        case "toString" -> "FakeSubmarket";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasSubmarket" -> Boolean.TRUE;
                        case "getSubmarket" -> submarket;
                        case "getId" -> "sindria";
                        case "toString" -> "FakeMarket[sindria]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> market;
                        case "toString" -> "FakeEconomy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEconomy" -> economy;
                        case "toString" -> "FakeSector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

}
