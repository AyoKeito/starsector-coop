package coop.campaign;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;

import coop.util.CoopLog;

/**
 * The two {@code protected long seed} fields shared bars are built on, and the only place the mod
 * touches either of them. Both are read and written through {@link MethodHandles}: the {@code seed}
 * fields have no public accessor, and plain reflection is out because Starfarer's script classloader
 * hard-blocks {@code java.lang.reflect} ("File access and reflection are not allowed to scripts").
 * {@code java.lang.invoke} is not on that denylist (the same workaround MagicLib uses). Every access
 * is defensive: a failure is logged and degrades to "that part of the bar is unsynced".
 *
 * <p><b>Two seeds, two jobs.</b> Phase 12c splits what an earlier attempt tried to do with one:
 * <ul>
 *   <li><b>{@code BarEventManager.seed} — the shown-subset seed</b> ({@link #hostSeed()},
 *   {@link #applySeed(long)}). {@code BarCMD.showOptions} builds its {@code Random} from
 *   {@code getSeed(entity, null, null)} and uses it both to pick how many offers a market shows and
 *   to {@code Collections.shuffle} the global pool before taking that many. Equal seeds therefore
 *   mean equal shuffles — <em>given equal pools</em>, which is the other half.</li>
 *   <li><b>Per-event {@code seed} — the content seed</b> ({@link #readEventSeed},
 *   {@link #writeEventSeed}). Every concrete offer regenerates its person, commodity, quantity,
 *   price and mission body from one {@code long} plus {@code market.getId().hashCode()}. So an offer
 *   need not be serialized: send the id and the seed, reconstruct the object on the guest, overwrite
 *   the seed, and the guest's own engine regenerates identical content.</li>
 * </ul>
 *
 * <p><b>What the earlier seed-only design got wrong.</b> Equalizing the manager seed alone cannot
 * equalize offers: {@code BarEventManager.advance} picks which creators fire through a
 * {@code WeightedRandomPicker} with a null {@code Random}, so {@code pick()} falls back to the global
 * unseeded {@code Math.random()}. Which offers <em>exist</em> is therefore non-deterministic and
 * independent of the seed — confirmed in bytecode and in two live tests. That is why the pool itself
 * is now replicated ({@link CoopBarPoolCapture} captures it on the host, {@link CoopBarPoolInjector}
 * rebuilds it on the guest, {@link CoopBarGenerationSuppressor} stops the guest generating its own).
 * The manager seed sync is still needed on top of that, for the shuffle.
 */
public final class CoopBarSync {

    private static MethodHandle seedGetter;
    private static MethodHandle seedSetter;
    private static boolean handlesResolved;

    /**
     * Per-event-class {@code seed} accessors, resolved once per concrete class. {@link #ABSENT} is
     * the negative cache: a class with no {@code long seed} anywhere in its hierarchy (a bar event
     * with no regenerable content) must not be re-walked on every snapshot.
     */
    private static final SeedHandles ABSENT = new SeedHandles(null, null);
    private static final Map<Class<?>, SeedHandles> EVENT_SEED_HANDLES = new HashMap<>();

    private record SeedHandles(MethodHandle getter, MethodHandle setter) {
        boolean usable() {
            return getter != null && setter != null;
        }
    }

    private CoopBarSync() {
    }

    /** The host's current global bar-event seed, or {@code null} if it can't be read. */
    public static Long hostSeed() {
        try {
            BarEventManager mgr = BarEventManager.getInstance();
            resolveHandles();
            return seedGetter == null || mgr == null ? null : (long) seedGetter.invoke(mgr);
        } catch (Throwable t) {
            CoopLog.warn(CoopBarSync.class, "Failed to read bar-event seed", t);
            return null;
        }
    }

    /** Force the local bar-event seed to {@code seed} so future offers match the host. */
    public static boolean applySeed(long seed) {
        try {
            BarEventManager mgr = BarEventManager.getInstance();
            resolveHandles();
            if (seedSetter == null || mgr == null) {
                return false;
            }
            seedSetter.invoke(mgr, seed);
            return true;
        } catch (Throwable t) {
            CoopLog.warn(CoopBarSync.class, "Failed to apply bar-event seed", t);
            return false;
        }
    }

    // ---- Per-event content seed (Phase 12c bar pool) -----------------------------------------

    /**
     * The seed a single bar offer regenerates its content from, or {@code null} when the event class
     * has no such field (nothing to replicate) or the handle could not be resolved.
     */
    public static Long readEventSeed(PortsideBarEvent event) {
        if (event == null) {
            return null;
        }
        SeedHandles handles = handlesFor(event.getClass());
        if (!handles.usable()) {
            return null;
        }
        try {
            return (long) handles.getter().invoke(event);
        } catch (Throwable t) {
            CoopLog.warn(CoopBarSync.class,
                    "Failed to read bar-event seed from " + event.getClass().getSimpleName(), t);
            return null;
        }
    }

    /** Overwrite a freshly constructed offer's content seed with the host's. */
    public static boolean writeEventSeed(PortsideBarEvent event, long seed) {
        if (event == null) {
            return false;
        }
        SeedHandles handles = handlesFor(event.getClass());
        if (!handles.usable()) {
            return false;
        }
        try {
            handles.setter().invoke(event, seed);
            return true;
        } catch (Throwable t) {
            CoopLog.warn(CoopBarSync.class,
                    "Failed to write bar-event seed on " + event.getClass().getSimpleName(), t);
            return false;
        }
    }

    /**
     * Resolve (and cache) the {@code seed} accessors for one concrete event class.
     *
     * <p>The field is declared by a base class, not by the concrete one — {@code
     * HubMissionBarEventWrapper}, {@code BaseBarEventWithPerson}, {@code BaseGetCommodityBarEvent},
     * {@code HistorianBarEvent} each declare their own — so the lookup walks up the hierarchy until a
     * class declares it. {@code NoSuchFieldException} is the "keep walking" signal and is not an
     * error; anything else is, and is logged once per class because the negative result is cached.
     */
    private static synchronized SeedHandles handlesFor(Class<?> eventClass) {
        SeedHandles cached = EVENT_SEED_HANDLES.get(eventClass);
        if (cached != null) {
            return cached;
        }
        SeedHandles resolved = ABSENT;
        for (Class<?> c = eventClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                MethodHandles.Lookup priv = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                resolved = new SeedHandles(priv.findGetter(c, "seed", long.class),
                        priv.findSetter(c, "seed", long.class));
                break;
            } catch (NoSuchFieldException ignored) {
                // This class does not declare it; try the superclass.
            } catch (RuntimeException | LinkageError | IllegalAccessException ex) {
                CoopLog.warn(CoopBarSync.class,
                        "Failed to resolve bar-event seed handles for " + c.getName(), ex);
                break;
            }
        }
        EVENT_SEED_HANDLES.put(eventClass, resolved);
        return resolved;
    }

    private static synchronized void resolveHandles() throws Throwable {
        if (handlesResolved) {
            return;
        }
        handlesResolved = true;
        MethodHandles.Lookup priv = MethodHandles.privateLookupIn(BarEventManager.class, MethodHandles.lookup());
        seedGetter = priv.findGetter(BarEventManager.class, "seed", long.class);
        seedSetter = priv.findSetter(BarEventManager.class, "seed", long.class);
    }
}
