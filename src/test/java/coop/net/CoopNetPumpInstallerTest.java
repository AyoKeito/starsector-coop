package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.SectorAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CoopNetPumpInstallerTest {
    @Test
    void installsPumpAsTransientScriptSoItIsNotSaved() {
        RecordingSector recording = new RecordingSector();
        SectorAPI sector = recording.proxy();
        CoopNetService service = new CoopNetService();

        CoopNetPumpInstaller.install(sector, service);

        assertEquals(List.of(CoopNetPump.class), recording.persistentRemovals);
        assertEquals(List.of(CoopNetPump.class), recording.transientRemovals);
        assertEquals(0, recording.persistentScripts.size(), "network pump must not enter saved script list");
        assertEquals(1, recording.transientScripts.size());
        assertInstanceOf(CoopNetPump.class, recording.transientScripts.get(0));
    }

    private static final class RecordingSector {
        private final List<Class<?>> persistentRemovals = new ArrayList<>();
        private final List<Class<?>> transientRemovals = new ArrayList<>();
        private final List<EveryFrameScript> persistentScripts = new ArrayList<>();
        private final List<EveryFrameScript> transientScripts = new ArrayList<>();

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "removeScriptsOfClass" -> {
                                persistentRemovals.add((Class<?>) args[0]);
                                return null;
                            }
                            case "removeTransientScriptsOfClass" -> {
                                transientRemovals.add((Class<?>) args[0]);
                                return null;
                            }
                            case "addScript" -> {
                                persistentScripts.add((EveryFrameScript) args[0]);
                                return null;
                            }
                            case "addTransientScript" -> {
                                transientScripts.add((EveryFrameScript) args[0]);
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
