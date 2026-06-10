package coop.campaign;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;

import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;

import coop.util.CoopLog;

/**
 * Bar-event field access — <b>currently unused</b>, retained for a future host-authoritative bar
 * mirroring phase and as the reference example of the MethodHandles field-access workaround.
 *
 * <p><b>Why the seed-sync idea it was built for was abandoned:</b> the original plan was to make both
 * clients roll identical dockside offers by equalizing {@code BarEventManager.seed}. That can't work —
 * {@code advance()} selects which offers spawn via a {@code WeightedRandomPicker} with a null
 * {@code Random}, so {@code pick()} falls back to {@code Math.random()} (global, unseeded). Offer
 * selection is therefore non-deterministic and independent of the seed; equal seeds only flavor an
 * already-spawned event's internal content via {@code getSeed()}. Confirmed in bytecode and by two
 * live tests. True shared bars need host-authoritative mirroring of the chosen event objects, deferred
 * to its own phase. The {@code MethodHandles} access below stays valid and reusable.
 *
 * <p>Bar/mission offers are <em>not</em> plain data — each {@link PortsideBarEvent} is a code object
 * whose dialog and reward logic live in the instance, so they can't be serialized and rebuilt on the
 * guest. But {@code BarEventManager} selects which offers spawn at each market purely from
 * {@code getSeed(token, person, id) = (nameHash * personHash * idHash) * CONST + this.seed}. Every
 * factor except {@code this.seed} is deterministic across instances (named markets/persons are
 * identical), so <b>if the guest's manager seed equals the host's, both clients roll byte-identical
 * offers natively</b> — each generates its own event objects, so completion/reward works locally on
 * whichever player accepts. Two fresh games otherwise roll independent seeds (via {@code updateSeed},
 * every 20-40 days) and show entirely different bars, which is the divergence we fix here.
 *
 * <p>The {@code seed} field is {@code protected} with no public accessor. Plain reflection is out:
 * Starfarer's script classloader hard-blocks {@code java.lang.reflect} ("File access and reflection
 * are not allowed to scripts"). {@code java.lang.invoke} is <em>not</em> on that denylist, so we read
 * and write the field with {@link MethodHandles} (the same workaround MagicLib uses). All access is
 * isolated here and fully defensive: any failure is logged once and degrades to "unsynced bars".
 */
public final class CoopBarSync {

    private static MethodHandle seedGetter;
    private static MethodHandle seedSetter;
    private static boolean handlesResolved;

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

    /**
     * Drop already-rolled offers so they re-roll under the freshly-synced seed. Called once on the
     * guest at first sync (offers spawned with its old seed are otherwise stale); not on later seed
     * changes, where vanilla also keeps existing offers until they time out.
     */
    public static void clearActiveOffers() {
        try {
            BarEventManager mgr = BarEventManager.getInstance();
            if (mgr != null && mgr.getActive() != null) {
                mgr.getActive().clear();
            }
            if (mgr != null && mgr.getTimeout() != null) {
                mgr.getTimeout().clear();
            }
            PortsideBarData bar = PortsideBarData.getInstance();
            if (bar != null) {
                for (PortsideBarEvent event : new ArrayList<>(bar.getEvents())) {
                    bar.removeEvent(event);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBarSync.class, "Failed to clear active bar offers", ex);
        }
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
