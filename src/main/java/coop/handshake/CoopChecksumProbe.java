package coop.handshake;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import coop.util.CoopDebug;
import coop.util.CoopLog;

/**
 * Dormant diagnostic that answers one open question: can a campaign script read a mod's
 * {@code mod_info.json} through the engine's own text loader without tripping the script sandbox?
 *
 * <p>Phase 12b called for a time-boxed spike on this. The answer decides whether handshake checksums
 * can ever be real or stay {@link CoopChecksum#unavailable} placeholders forever, and it cannot be
 * settled outside a running game: the block lives in the mod classloader, so the call compiles and
 * unit-tests clean either way.
 *
 * <p>It is a <b>probe, not a feature</b>. {@link CoopHandshakeManifest} still emits placeholders, so
 * nothing on the handshake path depends on the outcome. Wiring the real hash is a small follow-up
 * once a session log shows this succeeding. That ordering is deliberate: the handshake is the first
 * thing that runs on connect, and checksums are a diagnostic nicety, while the git-commit comparison
 * and the Phase 6 sector fingerprint are the guards that actually catch a skewed install.
 *
 * <p>Runs only when diagnostics are enabled ({@code -Dcoop.debug.diagnostics=true} or the
 * {@code $coopDebug} sector flag), and swallows everything, so an unproven engine call can never
 * affect a normal session.
 */
public final class CoopChecksumProbe {

    private CoopChecksumProbe() {
    }

    /** Logs whether the engine text loader is usable from inside a campaign script. */
    public static void probe() {
        if (!CoopDebug.diagnosticsEnabled()) {
            return;
        }
        try {
            ModSpecAPI self = null;
            for (ModSpecAPI spec : Global.getSettings().getModManager().getEnabledModsCopy()) {
                if (spec != null && "coop".equals(spec.getId())) {
                    self = spec;
                    break;
                }
            }
            if (self == null) {
                CoopLog.info(CoopChecksumProbe.class, "Coop checksum probe: coop mod spec not found");
                return;
            }
            String text = Global.getSettings().loadText("mod_info.json", self.getId());
            if (text == null || text.isBlank()) {
                CoopLog.info(CoopChecksumProbe.class,
                        "Coop checksum probe: loader returned empty text; checksums stay unavailable");
                return;
            }
            // Normalize line endings so a CRLF/LF checkout difference would not read as a mismatch.
            String hash = CoopChecksum.sha256Text(text.replace("\r\n", "\n").replace('\r', '\n'));
            CoopLog.info(CoopChecksumProbe.class,
                    "Coop checksum probe: SUCCESS, mod_info.json hashed to " + hash
                            + " (" + text.length() + " chars). Real checksums are viable; wire them.");
        } catch (Throwable ex) {
            // Throwable, not Exception: a sandbox rejection surfaces as an Error/LinkageError at
            // resolution time, which is exactly the outcome being measured.
            CoopLog.info(CoopChecksumProbe.class,
                    "Coop checksum probe: BLOCKED (" + ex.getClass().getName() + ": " + ex.getMessage()
                            + "). Checksums stay unavailable placeholders.");
        }
    }
}
