package coop.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link CoopEngageDialogStaging} writes onto a guest-side mirror, keyed by the literal memory
 * keys the engine reads rather than by {@code MemFlags} constants — the contract here is with
 * {@code TacticalModule}, and a constant compared against itself would pass whatever it held.
 *
 * <p>Finding S4-E (live 2026-09-14): staging only {@code $cfai_makeAggressive} left every handoff
 * opening with {@code hostileToPlayer=false} and a free Leave, because a mirror carries no vanilla
 * fleet memory and so is never hostile to a transponder-off guest. These tests pin the added
 * {@code $cfai_makeHostileWhileTOff} write and — the part that is easy to get wrong — that clearing
 * it touches our reason key and nothing else.
 *
 * <h2>Why the class under test is reloaded</h2>
 * {@code Misc}, which owns the reason-key idiom, has a static initialiser that reads
 * {@code Global.getSettings()} ({@code Misc}:196-223: fluxPerCapacitor, mount colours, colony caps).
 * Some earlier class in the same Gradle test worker touches {@code Misc} with no settings installed,
 * its initialiser fails, and the JVM then answers every later use with
 * {@code NoClassDefFoundError: Could not initialize class ... Misc} — permanently, for the rest of the
 * worker. Run alone this class passed; run after the rest of {@code coop.combat} every write became
 * {@code =THREW}, which is exactly the shape of pass-alone-fail-in-suite coverage that is worth
 * nothing.
 *
 * <p>So each test loads its own copy of {@code Misc} and of the class under test (which is compiled
 * against it) in a child loader, with settings installed first. Everything else — {@code Global},
 * {@code MemoryAPI}, {@code CampaignFleetAPI}, {@code MemFlags} — still comes from the parent loader,
 * so the proxies below are the ordinary ones and the settings the copy reads are the ones this class
 * sets. The suite-wide landmine is left alone; fixing it belongs to whoever owns the test bootstrap.
 *
 * <p>The memory fake is a plain map ({@link ApiProxies#memory}), so it does not model the engine's
 * required-key bookkeeping (real {@code MemoryAPI} drops a base key once its last required key is
 * gone). That is deliberate: what is worth pinning is which keys the mod writes and unsets, and a map
 * shows a stray unset of a base key or of a foreign reason that a self-clearing fake would hide.
 */
class CoopEngageDialogStagingTest {

    private static final String AGGRESSIVE = "$cfai_makeAggressive";
    private static final String AGGRESSIVE_OURS = AGGRESSIVE + "_coopEngage";
    private static final String ONE_BATTLE = "$cfai_makeAggressiveLastsOneBattle";
    private static final String TOFF = "$cfai_makeHostileWhileTOff";
    private static final String TOFF_OURS = TOFF + "_coopEngage";
    /** Vanilla's own reason on the same flag (TacticalModule:284), the one clear() must not touch. */
    private static final String TOFF_VANILLA = TOFF + "_tOff";

    private final Map<String, Object> values = new LinkedHashMap<>();
    private Class<?> staging;

    @BeforeEach
    void setUp() {
        Global.setSettings(ApiProxies.whiteSettings());
        staging = freshStagingClass();
    }

    @AfterEach
    void tearDown() {
        Global.setSettings(null);
    }

    @Test
    void stageWritesBothPostureFlagsUnderTheCoopReason() {
        String flags = stage(fleetWith(ApiProxies.memory(values)));

        // Factor 2 of fleetWantsToFight: pickEncounterOption returns ENGAGE.
        assertEquals(Boolean.TRUE, values.get(AGGRESSIVE));
        assertEquals(Boolean.TRUE, values.get(AGGRESSIVE_OURS));
        // Factor 1 (S4-E): isHostileTo(:1108) now answers true for a transponder-off guest.
        assertEquals(Boolean.TRUE, values.get(TOFF));
        assertEquals(Boolean.TRUE, values.get(TOFF_OURS));
        // Vanilla's self-cleanup for the aggressive flag when the handoff turns into a battle.
        assertEquals(Boolean.TRUE, values.get(ONE_BATTLE));
        // The staging line in the guest log has to name the new flag, or the next live diagnosis of a
        // neutral-posture handoff reads exactly like the broken one did.
        assertTrue(flags.contains(AGGRESSIVE_OURS + "=true"), flags);
        assertTrue(flags.contains(TOFF_OURS + "=true"), flags);
        assertTrue(flags.contains(ONE_BATTLE + "=true"), flags);
    }

    @Test
    void stageGivesTheHostilityReasonVanillasOwnOneDayExpiry() {
        AtomicReference<Object> expiry = new AtomicReference<>();
        stage(fleetWith(expiryRecordingMemory(TOFF_OURS, expiry)));

        // The same number vanilla writes for its "tOff" reason (TacticalModule:284), so a mirror is no
        // stickier than a fleet that made the guest by itself.
        assertEquals(1f, expiry.get());
    }

    @Test
    void clearDropsOurReasonAndLeavesVanillasAlone() {
        values.put(AGGRESSIVE, Boolean.TRUE);
        values.put(AGGRESSIVE_OURS, Boolean.TRUE);
        values.put(TOFF, Boolean.TRUE);
        values.put(TOFF_OURS, Boolean.TRUE);
        values.put(TOFF_VANILLA, Boolean.TRUE);
        values.put(ONE_BATTLE, Boolean.TRUE);

        String flags = clear(fleetWith(ApiProxies.memory(values)));

        assertFalse(values.containsKey(AGGRESSIVE_OURS), values.toString());
        assertFalse(values.containsKey(TOFF_OURS), values.toString());
        // The fleet also identified the guest on its own account; that reason outlives the handoff and
        // keeps the base flag alive, which is the engine's call to make and not ours.
        assertEquals(Boolean.TRUE, values.get(TOFF_VANILLA), values.toString());
        assertEquals(Boolean.TRUE, values.get(TOFF), values.toString());
        assertEquals(Boolean.TRUE, values.get(AGGRESSIVE), values.toString());
        // The one-battle marker is a bare set, so it is a bare unset.
        assertFalse(values.containsKey(ONE_BATTLE), values.toString());
        assertTrue(flags.contains(AGGRESSIVE_OURS + "=false"), flags);
        assertTrue(flags.contains(TOFF_OURS + "=false"), flags);
        assertTrue(flags.contains("-" + ONE_BATTLE), flags);
    }

    @Test
    void aMirrorWithNoMemorySaysSoRatherThanThrowing() {
        assertEquals("no-memory", stage(fleetWith(null)));
        assertEquals("no-memory", clear(fleetWith(null)));
        assertEquals("no-memory", stage(null));
        assertEquals("no-memory", clear(null));
    }

    @Test
    void aMemoryThatThrowsIsReportedPerFlagAndNeverEscapes() {
        String flags = stage(fleetWith(throwingMemory()));

        // A half-staged mirror still opens a real encounter dialog; an exception here would take the
        // pump frame down instead.
        assertTrue(flags.contains(AGGRESSIVE_OURS + "=THREW"), flags);
        assertTrue(flags.contains(TOFF_OURS + "=THREW"), flags);
        assertTrue(flags.contains(ONE_BATTLE + "=THREW"), flags);
    }

    private String stage(CampaignFleetAPI mirror) {
        return call("stage", mirror);
    }

    private String clear(CampaignFleetAPI mirror) {
        return call("clear", mirror);
    }

    private String call(String method, CampaignFleetAPI mirror) {
        try {
            return (String) staging.getMethod(method, CampaignFleetAPI.class).invoke(null, mirror);
        } catch (InvocationTargetException ex) {
            // stage/clear promise never to throw; if one does, this is the bug, not a test artefact.
            throw new AssertionError(method + " threw", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot call " + method + " on the reloaded staging class", ex);
        }
    }

    /**
     * The class under test, reloaded together with {@code Misc} so that {@code Misc}'s static
     * initialiser runs here, under the settings this test installed - see the class javadoc.
     */
    private static Class<?> freshStagingClass() {
        ClassLoader loader = new ReloadingLoader(CoopEngageDialogStagingTest.class.getClassLoader());
        try {
            return Class.forName(CoopEngageDialogStaging.class.getName(), true, loader);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("cannot reload the staging class", ex);
        }
    }

    /** A fleet whose only real answer is {@code memory}; null models one with no memory at all. */
    private static CampaignFleetAPI fleetWith(MemoryAPI memory) {
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMemoryWithoutUpdate" -> memory;
                    case "toString" -> "mirrorProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** Records the expiry {@code Misc.setFlagWithReason} passes to {@code set(key, value, expire)}. */
    private static MemoryAPI expiryRecordingMemory(String watched, AtomicReference<Object> seen) {
        return (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> {
                    if ("set".equals(method.getName()) && args.length == 3 && watched.equals(args[0])) {
                        seen.set(args[2]);
                    }
                    return switch (method.getName()) {
                        case "toString" -> "expiryMemory";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    /** Every read and write blows up - the mirror is being torn down under the pump, say. */
    private static MemoryAPI throwingMemory() {
        return (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "throwingMemory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new IllegalStateException("memory is gone");
                });
    }

    /**
     * Defines {@link #RELOADED} from the parent's own bytes and delegates everything else upward, so
     * the reloaded staging class still speaks the parent's {@code CampaignFleetAPI} and
     * {@code MemoryAPI} (the proxies above are usable as-is) and still reads the parent's
     * {@code Global} (the settings this test installs are the ones it sees).
     */
    private static final class ReloadingLoader extends ClassLoader {

        private static final Set<String> RELOADED = Set.of(
                "com.fs.starfarer.api.util.Misc",
                "coop.combat.CoopEngageDialogStaging");

        ReloadingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!RELOADED.contains(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = bytesOf(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private byte[] bytesOf(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name + " (" + resource + " not on the test classpath)");
                }
                return in.readAllBytes();
            } catch (IOException ex) {
                throw new ClassNotFoundException("cannot read " + resource, ex);
            }
        }
    }
}
