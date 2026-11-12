package coop.campaign;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoopWreckFactoryIdentityTest {
    @Test void encounterFactoryAllowsNativeIdAllocationWhileFloatingWreckFactoryKeepsWireId() {
        List<Object[]> creations = new ArrayList<>();
        RuntimeException created = new RuntimeException("Native creation boundary reached");
        LocationAPI location = (LocationAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LocationAPI.class}, (p, m, args) -> {
                    if (m.getName().equals("addCustomEntity")) { creations.add(args.clone()); throw created; }
                    return ProxyDefaults.defaultValue(m.getReturnType());
                });
        CoopWreckRecord record = CoopWreckFixtures.sample("wreck:encounter-fixture");
        assertSame(created, assertThrows(RuntimeException.class, () -> CoopWreckCodec.create(location, record, null)));
        assertSame(created, assertThrows(RuntimeException.class, () -> CoopWreckCodec.create(location, record)));
        assertNull(creations.get(0)[0]);
        assertEquals(record.id(), creations.get(1)[0]);
        for (Object[] args : creations) {
            assertEquals(record.name(), args[1]); assertEquals(Entities.WRECK, args[2]);
            assertEquals(record.factionId(), args[3]); assertInstanceOf(DerelictShipData.class, args[4]);
            assertEquals(record.durationDays(), ((DerelictShipData) args[4]).durationDays);
        }
    }
}
