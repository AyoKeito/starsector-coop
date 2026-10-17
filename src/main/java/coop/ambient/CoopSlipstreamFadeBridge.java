package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.abilities.GenerateSlipsurgeAbility.SlipsurgeFadeInScript;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2.SlipstreamSegment;
import com.fs.starfarer.api.util.FaderUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transient ownership of native slipstream fades. This class belongs only to coop-forks.jar, in
 * the system classloader, and must never depend on coop.jar or be stored on a saved native object.
 * Native hooks read this identity registry; the mod's campaign controller supplies canonical steps.
 */
public final class CoopSlipstreamFadeBridge {
    public static final String PINNED_VERSION = "0.98a-RC8";
    private static final String HOOK_VERSION = "0.98a-RC8/canonical-fade-1";
    private static final Map<SlipstreamTerrainPlugin2, Control> CONTROLS = new IdentityHashMap<>();

    private CoopSlipstreamFadeBridge() {
    }

    /** The complete primitive cursor of the native SlipsurgeFadeInScript, never a saved field. */
    public record SurgeWaveState(boolean present, boolean done, int index, float elapsed, float maxElapsed) {
        public SurgeWaveState {
            if (index < 0 || !Float.isFinite(elapsed) || elapsed < 0f
                    || !Float.isFinite(maxElapsed) || maxElapsed < 0f) {
                throw new IllegalArgumentException("Invalid native slipsurge cursor");
            }
            if (!present && (!done || index != 0 || elapsed != 0f || maxElapsed != 0f)) {
                throw new IllegalArgumentException("Absent slipsurge cursor must use canonical empty state");
            }
        }

        public static SurgeWaveState absent() {
            return new SurgeWaveState(false, true, 0, 0f, 0f);
        }
    }

    /** Registers only the actual native object; a reloaded object must be registered again. */
    public static synchronized void register(SlipstreamTerrainPlugin2 plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (plugin.isDynamic()) throw new IllegalArgumentException("Dynamic slipstreams cannot be controlled");
        Objects.requireNonNull(plugin.getEntity(), "slipstream entity");
        requirePinnedHooks();
        if (!CONTROLS.containsKey(plugin)) CONTROLS.put(plugin, new Control(findWave(plugin)));
    }

    public static synchronized void unregister(SlipstreamTerrainPlugin2 plugin) {
        CONTROLS.remove(plugin);
    }

    /** Releases all old object identities on session/load teardown; native scripts resume normally. */
    public static synchronized void clear() {
        CONTROLS.clear();
    }

    public static synchronized boolean isControlled(SlipstreamTerrainPlugin2 plugin) {
        return plugin != null && !plugin.isDynamic() && CONTROLS.containsKey(plugin);
    }

    /** Native hook input: true only within a host-owned canonical step. */
    public static synchronized boolean allowsNativeExpiry(SlipstreamTerrainPlugin2 plugin) {
        Control control = CONTROLS.get(plugin);
        return control != null && control.stepping && control.allowNativeExpiry;
    }

    /**
     * Executes native faders, spawn, despawn, then surge wave exactly once. The host's hyperspace
     * mode replaces the guest's local current-location shortcut. Guests pass allowNativeExpiry=false:
     * their zero-brightness terrain remains until the host's STREAM_REMOVE arrives.
     */
    public static synchronized void advanceControlled(SlipstreamTerrainPlugin2 plugin, float seconds,
                                                       boolean hostInHyperspace, boolean allowNativeExpiry) {
        if (!Float.isFinite(seconds) || seconds < 0f) {
            throw new IllegalArgumentException("Canonical fade step must be finite and nonnegative");
        }
        Control control = requireControl(plugin);
        if (control.stepping) throw new IllegalStateException("Recursive canonical slipstream step");
        if (seconds == 0f) return;
        SlipsurgeFadeInScript wave = wave(plugin, control);
        control.stepping = true;
        control.allowNativeExpiry = allowNativeExpiry;
        try {
            plugin.coopAdvanceControlledFade(seconds, hostInHyperspace);
            if (wave != null) wave.coopAdvanceControlledFade(seconds, hostInHyperspace);
        } finally {
            control.allowNativeExpiry = false;
            control.stepping = false;
        }
    }

    /** Finds both entity-attached and location-attached deserialized native scripts by their owner. */
    public static synchronized SurgeWaveState captureSurgeWave(SlipstreamTerrainPlugin2 plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Control control = CONTROLS.get(plugin);
        SlipsurgeFadeInScript wave = control == null ? findWave(plugin) : wave(plugin, control);
        return wave == null ? SurgeWaveState.absent() : wave.coopCaptureFadeState();
    }

    /**
     * Restores an existing native script cursor, or installs the native script when a guest first
     * sees it. Constructor writes to segment faders/bMult are undone before returning. No mod script
     * or DTO is attached to the engine or persisted in its place.
     */
    public static synchronized void restoreSurgeWave(SlipstreamTerrainPlugin2 plugin, SurgeWaveState state) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(state, "state");
        if (state.index() > plugin.getSegments().size()) {
            throw new IllegalArgumentException("Slipsurge cursor exceeds native segment count");
        }
        Control control = CONTROLS.get(plugin);
        SlipsurgeFadeInScript wave = control == null ? findWave(plugin) : wave(plugin, control);
        if (!state.present()) {
            removeWaves(plugin);
            if (control != null) control.wave = null;
            return;
        }
        if (wave == null) {
            List<SegmentState> segments = captureSegments(plugin);
            try {
                wave = new SlipsurgeFadeInScript(plugin);
            } finally {
                restoreSegments(plugin, segments);
            }
            plugin.getEntity().addScript(wave);
        } else if (!state.done() && !isAttached(plugin, wave)) {
            // The engine may have removed a completed script before an older checkpoint rewinds it.
            plugin.getEntity().addScript(wave);
        }
        wave.coopRestoreFadeState(state);
        if (control != null) control.wave = wave;
    }

    private static Control requireControl(SlipstreamTerrainPlugin2 plugin) {
        if (!isControlled(plugin)) throw new IllegalStateException("Slipstream is not registered for canonical fades");
        return CONTROLS.get(plugin);
    }

    private static SlipsurgeFadeInScript wave(SlipstreamTerrainPlugin2 plugin, Control control) {
        if (control.wave == null) control.wave = findWave(plugin);
        return control.wave;
    }

    private static SlipsurgeFadeInScript findWave(SlipstreamTerrainPlugin2 plugin) {
        SlipsurgeFadeInScript result = null;
        for (EveryFrameScript script : attachedScripts(plugin)) {
            if (script instanceof SlipsurgeFadeInScript candidate && candidate.coopFadeOwner() == plugin) {
                if (result != null && result != candidate) {
                    throw new IllegalStateException("Multiple native slipsurge fade scripts own one terrain");
                }
                result = candidate;
            }
        }
        return result;
    }

    private static List<EveryFrameScript> attachedScripts(SlipstreamTerrainPlugin2 plugin) {
        List<EveryFrameScript> scripts = new ArrayList<>(plugin.getEntity().getScripts());
        LocationAPI hyper = hyperspace();
        if (hyper != null) scripts.addAll(hyper.getScripts());
        return scripts;
    }

    private static boolean isAttached(SlipstreamTerrainPlugin2 plugin, SlipsurgeFadeInScript wave) {
        for (EveryFrameScript script : attachedScripts(plugin)) if (script == wave) return true;
        return false;
    }

    private static void removeWaves(SlipstreamTerrainPlugin2 plugin) {
        SectorEntityToken entity = plugin.getEntity();
        for (EveryFrameScript script : new ArrayList<>(entity.getScripts())) {
            if (script instanceof SlipsurgeFadeInScript candidate && candidate.coopFadeOwner() == plugin) {
                entity.removeScript(script);
            }
        }
        LocationAPI hyper = hyperspace();
        if (hyper != null) {
            for (EveryFrameScript script : new ArrayList<>(hyper.getScripts())) {
                if (script instanceof SlipsurgeFadeInScript candidate && candidate.coopFadeOwner() == plugin) {
                    hyper.removeScript(script);
                }
            }
        }
    }

    private static LocationAPI hyperspace() {
        return Global.getSector() == null ? null : Global.getSector().getHyperspace();
    }

    private static void requirePinnedHooks() {
        try {
            if (!HOOK_VERSION.equals(SlipstreamTerrainPlugin2.coopFadeHookVersion())
                    || !HOOK_VERSION.equals(SlipsurgeFadeInScript.coopFadeHookVersion())) {
                throw new IllegalStateException("Canonical slipstream native hook version mismatch");
            }
        } catch (LinkageError error) {
            throw new IllegalStateException("Canonical slipstream hooks are missing from coop-forks.jar", error);
        }
        String version = Global.getSettings() == null ? null : Global.getSettings().getVersionString();
        if (version == null || !version.contains(PINNED_VERSION)) {
            throw new IllegalStateException("Canonical slipstream hooks require Starsector " + PINNED_VERSION
                    + "; running version is " + version);
        }
    }

    private static List<SegmentState> captureSegments(SlipstreamTerrainPlugin2 plugin) {
        List<SegmentState> result = new ArrayList<>();
        for (SlipstreamSegment segment : plugin.getSegments()) {
            FaderUtil fader = segment.fader;
            result.add(new SegmentState(segment.bMult, fader.getBrightness(), fader.getDurationIn(),
                    fader.getDurationOut(), fader.getState(), fader.isBounceUp(), fader.isBounceDown()));
        }
        return result;
    }

    private static void restoreSegments(SlipstreamTerrainPlugin2 plugin, List<SegmentState> states) {
        for (int i = 0; i < states.size(); i++) {
            SlipstreamSegment segment = plugin.getSegments().get(i);
            SegmentState state = states.get(i);
            segment.bMult = state.bMult();
            segment.fader.setBrightness(state.brightness());
            segment.fader.setDurationIn(state.durationIn());
            segment.fader.setDurationOut(state.durationOut());
            segment.fader.setState(state.state());
            segment.fader.setBounceUp(state.bounceUp());
            segment.fader.setBounceDown(state.bounceDown());
        }
    }

    private static final class Control {
        private SlipsurgeFadeInScript wave;
        private boolean stepping;
        private boolean allowNativeExpiry;

        private Control(SlipsurgeFadeInScript wave) {
            this.wave = wave;
        }
    }

    private record SegmentState(float bMult, float brightness, float durationIn, float durationOut,
                                FaderUtil.State state, boolean bounceUp, boolean bounceDown) {
    }
}
