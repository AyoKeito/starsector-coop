package coop.fleet;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoopEntityIdsTest {
    @AfterEach void clearLocations() { CoopLocations.invalidate(); }

    @Test void metadataOverridesNativeIdWithoutChangingEitherClientEngineId() {
        String key = "coopenc_shared_system0";
        SectorEntityToken host = entity("host-engine-id", key, "shared");
        SectorEntityToken guest = entity("guest-engine-id", key, "shared");
        assertEquals(key, CoopEntityIds.keyOf(host));
        assertEquals(key, CoopEntityIds.keyOf(guest));
        assertEquals("host-engine-id", host.getId());
        assertEquals("guest-engine-id", guest.getId());
        assertEquals("native", CoopEntityIds.keyOf(entity("native", null, null)));
        assertNull(CoopEntityIds.keyOf(null));
    }

    @Test void twoPseudoCentresAtTheSameEncounterPointResolveByUniqueSystemAndMetadata() {
        SectorEntityToken first = entity("abyssal_100_200", "coopenc_first_system0", "first");
        SectorEntityToken second = entity("abyssal_100_200", "coopenc_second_system0", "second");
        World world = new World();
        world.locations.add(system("first", List.of(first)));
        world.locations.add(system("second", List.of(second)));
        world.rejectNativeLookup = true;

        assertSame(first, CoopEntityIds.byId(world.sector, "coopenc_first_system0"));
        assertSame(second, CoopEntityIds.byId(world.sector, "coopenc_second_system0"));
        assertEquals(0, world.nativeLookups.get());
    }

    @Test void separateClientEngineIdsResolveToTheirOwnCanonicalEntity() {
        String key = "coopenc_shared_system3";
        SectorEntityToken host = entity("native-host", key, "shared"), guest = entity("native-guest", key, "shared");
        World hostWorld = new World(), guestWorld = new World();
        hostWorld.locations.add(system("shared", List.of(host)));
        guestWorld.locations.add(system("shared", List.of(guest)));
        hostWorld.rejectNativeLookup = true; guestWorld.rejectNativeLookup = true;
        assertSame(host, CoopEntityIds.byId(hostWorld.sector, key));
        assertSame(guest, CoopEntityIds.byId(guestWorld.sector, key));
        assertEquals(0, hostWorld.nativeLookups.get() + guestWorld.nativeLookups.get());
    }

    @Test void suffixParserUsesTheLastRoleSuffixInsideAnUnderscoredInstanceId() {
        String instance = "host_jump_point1_system22";
        String key = "coopenc_" + instance + "_well7";
        SectorEntityToken well = entity("native-well", key, instance);
        World world = new World(); world.locations.add(system(instance, List.of()));
        world.hyperEntities.add(well); world.rejectNativeLookup = true;
        assertSame(well, CoopEntityIds.byId(world.sector, key));
        assertEquals(0, world.nativeLookups.get());
    }

    @Test void hyperRolesScanOnlyHyperspaceAndRequireTheOwningSystem() {
        World world = new World();
        List<SectorEntityToken> inside = new ArrayList<>();
        world.locations.add(system("shared", inside)); world.rejectNativeLookup = true;
        for (String role : List.of("anchor", "jump_point", "well")) {
            String key = "coopenc_shared_" + role + "0";
            SectorEntityToken entity = entity("native-" + role, key, "shared");
            inside.add(entity);
            assertNull(CoopEntityIds.byId(world.sector, key));
            world.hyperEntities.add(entity);
            assertSame(entity, CoopEntityIds.byId(world.sector, key));
        }
        world.hyperEntities.add(entity("orphan", "coopenc_missing_anchor0", "missing"));
        assertNull(CoopEntityIds.byId(world.sector, "coopenc_missing_anchor0"));
        assertEquals(0, world.nativeLookups.get());
    }

    @Test void systemRoleDoesNotAcceptATokenInHyperspaceOrOneWithWrongOwnership() {
        String key = "coopenc_shared_system0";
        List<SectorEntityToken> inside = new ArrayList<>();
        World world = new World(); world.locations.add(system("shared", inside));
        world.hyperEntities.add(entity("native", key, "shared")); world.rejectNativeLookup = true;
        assertNull(CoopEntityIds.byId(world.sector, key));
        inside.add(entity("native-wrong", key, "different"));
        assertNull(CoopEntityIds.byId(world.sector, key));
        SectorEntityToken right = entity("native-right", key, "shared"); inside.add(right);
        assertSame(right, CoopEntityIds.byId(world.sector, key));
        assertEquals(0, world.nativeLookups.get());
    }

    @Test void oldRenamedNativeIdsStillResolveWithoutTheNewMetadataKey() {
        String key = "coopenc_legacy_system2";
        SectorEntityToken old = entity(key, null, "legacy");
        World world = new World(); world.locations.add(system("legacy", List.of(old))); world.rejectNativeLookup = true;
        assertEquals(key, CoopEntityIds.keyOf(old));
        assertSame(old, CoopEntityIds.byId(world.sector, key));
        assertEquals(0, world.nativeLookups.get());
    }

    @Test void duplicateCanonicalObjectsFailLoudlyButRepeatedReferencesDoNot() {
        String key = "coopenc_shared_system0";
        SectorEntityToken first = entity("native-one", key, "shared");
        List<SectorEntityToken> entities = new ArrayList<>(List.of(first, first));
        World world = new World(); world.locations.add(system("shared", entities)); world.rejectNativeLookup = true;
        assertSame(first, CoopEntityIds.byId(world.sector, key));
        entities.add(entity("native-two", key, "shared"));
        assertThrows(IllegalStateException.class, () -> CoopEntityIds.byId(world.sector, key));
    }

    @Test void nullMalformedOrUnresolvedCompositeKeysNeverReachTheNativeGlobalCache() {
        World world = new World(); world.rejectNativeLookup = true;
        assertNull(CoopEntityIds.byId(null, "coopenc_shared_system0"));
        assertNull(CoopEntityIds.byId(world.sector, null)); assertNull(CoopEntityIds.byId(world.sector, ""));
        for (String key : List.of("coopenc_", "coopenc_shared_bad0", "coopenc_shared_system00",
                "coopenc_shared_system4096", "coopenc_shared_system999999999999999999999",
                "coopenc_shared_system0", "coopenc_invalid/instance_system0"))
            assertNull(CoopEntityIds.byId(world.sector, key));
        assertEquals(0, world.nativeLookups.get());
    }

    @Test void nativeIdsUseTheExistingSectorFastPath() {
        World world = new World(); SectorEntityToken target = entity("native", null, null); world.cached = target;
        assertSame(target, CoopEntityIds.byId(world.sector, "native"));
        assertEquals(1, world.nativeLookups.get());
        assertEquals(0, world.locationWalks.get());
    }

    @Test void nativeLocationTokenClassCastFallsBackToDirectEntityLists() {
        World world = new World(); world.nativeCastFailure = true;
        SectorEntityToken centre = entity("abyssal_100_200", null, null);
        world.locations.add(system("shared", List.of(centre)));
        assertSame(centre, CoopEntityIds.byId(world.sector, "abyssal_100_200"));
        assertEquals(1, world.nativeLookups.get());
        assertEquals(1, world.locationWalks.get());
    }

    @Test void nativeCacheMissCanFindAnEntityAddedAfterTheEngineCacheSnapshot() {
        World world = new World(); SectorEntityToken target = entity("native", null, null);
        world.hyperEntities.add(target);
        assertSame(target, CoopEntityIds.byId(world.sector, "native"));
        assertEquals(1, world.nativeLookups.get());
        assertEquals(1, world.locationWalks.get());
        assertNull(CoopEntityIds.byId(world.sector, "missing"));
    }

    @Test void ambiguousNativeFallbackDoesNotSilentlySelectADifferentPseudoCentre() {
        World world = new World(); world.nativeCastFailure = true;
        world.locations.add(system("first", List.of(entity("same-point", null, null))));
        world.locations.add(system("second", List.of(entity("same-point", null, null))));
        assertThrows(IllegalStateException.class, () -> CoopEntityIds.byId(world.sector, "same-point"));
    }

    private static SectorEntityToken entity(String engineId, String canonicalId, String instance) {
        Map<String, Object> values = new HashMap<>();
        if (canonicalId != null) values.put(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, canonicalId);
        if (instance != null) values.put("$coopEncId", instance);
        MemoryAPI memory = (MemoryAPI) Proxy.newProxyInstance(MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get(args[0]);
                    case "contains" -> values.containsKey(args[0]);
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        return (SectorEntityToken) Proxy.newProxyInstance(SectorEntityToken.class.getClassLoader(),
                new Class<?>[]{SectorEntityToken.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> engineId;
                    case "getMemoryWithoutUpdate" -> memory;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }

    private static StarSystemAPI system(String instance, List<SectorEntityToken> entities) {
        return (StarSystemAPI) Proxy.newProxyInstance(StarSystemAPI.class.getClassLoader(),
                new Class<?>[]{StarSystemAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "deep space";
                    case "getOptionalUniqueId" -> instance;
                    case "getAllEntities" -> entities;
                    case "getEntityById" -> throw new AssertionError("native location cache must not be used");
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }

    private static final class World {
        final List<LocationAPI> locations = new ArrayList<>();
        final List<SectorEntityToken> hyperEntities = new ArrayList<>();
        final AtomicInteger nativeLookups = new AtomicInteger();
        final AtomicInteger locationWalks = new AtomicInteger();
        boolean rejectNativeLookup, nativeCastFailure;
        SectorEntityToken cached;
        final LocationAPI hyper = (LocationAPI) Proxy.newProxyInstance(LocationAPI.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "hyperspace";
                    case "getAllEntities" -> hyperEntities;
                    case "getEntityById" -> throw new AssertionError("native location cache must not be used");
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        final SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAllLocations" -> { locationWalks.incrementAndGet(); yield new ArrayList<>(locations); }
                    case "getHyperspace" -> hyper;
                    case "getEntityById" -> {
                        nativeLookups.incrementAndGet();
                        if (rejectNativeLookup) throw new AssertionError("composite key reached native global lookup");
                        if (nativeCastFailure) throw new ClassCastException("LocationToken cannot be cast to BaseCampaignEntity");
                        yield cached;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }
}
