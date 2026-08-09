# Starsector Runtime Limitations

These notes capture constraints discovered while implementing and smoke-testing the coop Phase 3 TCP pump on Starsector `0.98a-RC8`.

## Script Sandbox

Starsector mod scripts run under a guarded script classloader. When the guard trips, the UI usually reports:

```text
Fatal: File access and reflection are not allowed to scripts.
```

The useful detail is in `starsector-core/starsector.log`.

Observed blocked patterns:

- Netty `4.1.69.Final` initializes `io.netty.util.internal.PlatformDependent0`, which loads reflection classes such as `java.lang.reflect.Method`. This crashes during host startup.
- `java.io.*` is treated as file access even when the class is only an in-memory type. `ByteArrayOutputStream` in `CoopNetService` crashed at object construction.
- `java.nio.file.Files` is blocked in campaign scripts. Phase 5 manifest capture crashed at `coop.handshake.CoopChecksum.sha256IfExists()` before sending `HANDSHAKE_MANIFEST`.
- Mod-created networking daemon threads are not reliable enough for campaign networking. In live two-client testing the `coop-net-*` threads disappeared while the game stayed running and the socket state was left half-open.

Current rules:

- Do not add Netty or similar reflection-heavy networking libraries to `mod_info.json`.
- Do not use `java.io.*` in runtime campaign/network code. Use plain arrays, strings, and sandbox-proven JDK types instead.
- Do not use `java.nio.file.*`, `java.net.URL.openStream()`, or protection-domain jar inspection in runtime handshake code. Runtime manifests may compare Starsector/mod metadata and generated coop build constants, but direct file checksums are blocked by the Starsector script sandbox unless a future non-script API is found.
- Keep coop networking progressed from `EveryFrameScript.advance()` on the campaign thread.
- Keep runtime dependencies minimal and covered by sandbox compatibility tests.

### Handshake checksums: open question, deliberately unanswered (Phase 12b, 2026-08-09)

Phase 12b called for a time-boxed spike on whether `SettingsAPI.loadText(String, String modId)` can
read a mod's `mod_info.json` from inside a campaign script. It is a public engine API rather than a
raw file read, so it might sidestep the block that killed `CoopChecksum.sha256IfExists()` in Phase 5.

**It was not settled, and it was not wired.** The reason is that it cannot be settled outside a
running game: the guard lives in the mod classloader, so the call compiles and unit-tests clean
whether or not it works in-game. There is also a specific hazard beyond the call itself, since
`loadText` declares a checked `IOException`, and handling it means the verifier must resolve a
`java.io` type in the calling class — the exact pattern this document already records as blocked.

What exists instead: `coop.handshake.CoopChecksumProbe`, a dormant diagnostic that runs from
`CoopModPlugin.onGameLoad` only when diagnostics are enabled (`-Dcoop.debug.diagnostics=true` or the
`$coopDebug` sector flag). It attempts the load, catches `Throwable`, and logs either
`Coop checksum probe: SUCCESS ...` or `Coop checksum probe: BLOCKED (<class>: <message>)`.
`CoopHandshakeManifest` still emits `unavailable("script-sandbox")` placeholders and does not depend
on the outcome.

Resolve it by reading either line out of a diagnostics-enabled session log. On SUCCESS, hashing
`mod_info.json` for real is a small follow-up; jar checksums stay unavailable regardless, because no
engine surface hands back jar bytes and the sandbox forbids opening them directly. On BLOCKED, delete
the probe and treat the placeholders as final.

This ordering is deliberate rather than cautious-by-default. The handshake is the first thing that
runs on connect, checksums are a diagnostic nicety, and the guards that actually catch a skewed
install are the git-commit comparison and the Phase 6 sector fingerprint. Risking a throw there to
gain a nicety is a bad trade, so the probe carries the risk and the handshake carries none.
`CoopHandshakeSandboxCompatibilityTest` pins the arrangement: the manifest must contain no
`loadText(` call, and the probe must stay gated and catch `Throwable`.

## Save Serialization

Persistent campaign scripts are serialized into saves by XStream. Runtime networking objects are not save-safe.

Observed failure:

```text
Error creating new game:
Error saving game.
No converter available
```

Root cause found during testing:

- A persistent `CoopNetPump` caused XStream to walk into runtime networking state, including classes such as `AtomicReference`.

Current rules:

- Install `CoopNetPump` with `SectorAPI.addTransientScript()`.
- Remove both persistent and transient old pump instances before installing a fresh pump.
- Do not store sockets, channels, threads, queues, or other runtime transport objects in saved campaign state.

## TCP Pump Constraints

The working Phase 3 transport is intentionally conservative:

- Host uses `ServerSocketChannel` in non-blocking mode.
- Guest uses `SocketChannel` in non-blocking mode.
- No `Selector`, no background worker thread, no Netty event loop.
- Message frames are UTF-8 JSON lines buffered with a fixed byte array.
- Guest connect attempts retry because host and guest campaign loads are not ordered.

Two-client smoke evidence to preserve:

```text
Host:  Coop TCP channel active as HOST
Host:  Coop net HOST inbound PING seq=1
Host:  Coop net HOST outbound PONG seq=1
Guest: Coop TCP channel active as GUEST
Guest: Coop TCP guest connected to 127.0.0.1:7777
Guest: Coop net GUEST outbound PING seq=1
Guest: Coop net GUEST inbound PONG seq=1
```

## Phase 7 Time Lock - Control Names and Fast-Forward

Discovered while implementing the guest time lock on `0.98a-RC8`.

### Control enum-constant names (input blocker)

`InputEventAPI.isControlActivated/isControlDownEvent/isControlUpEvent(String)` resolve the
argument via `Enum.valueOf` on the obfuscated control enum (`com.fs.starfarer.title.<obf>$oo` in
`starsector-core/starfarer_obf.jar`). An unknown name throws `IllegalArgumentException: No enum
constant ...<name>`; if uncaught it becomes a **Fatal** crash dialog back to the title screen.

- The campaign pause control is **`GENERAL_PAUSE`**, not `PAUSE`. Passing `"PAUSE"` crashed the client.
- Campaign fast-forward control is **`FAST_FORWARD`**; combat slow-mo is `GO_SLOW`.
- To dump the readable list: extract the `$oo` class from `starfarer_obf.jar` and
  `javap -v <class> | grep "= Utf8"` (constants like `CORE_*`, `CMENU_*`, `C2_*`).
- `CoopCampaignInputBlocker` wraps the lookups in `try/catch (IllegalArgumentException)` as a
  defensive net, but tests must assert against the real constant names - the test mocks
  `InputEventAPI` with arbitrary strings, so a wrong name passes tests yet crashes the real engine.

### Fast-forward has no public on/off setter

> **⚠️ SUPERSEDED IN PART (2026-06-10, see plan Phase 7b).** Everything below is true only for
> vanilla's default **hold**-Shift input mode. Vanilla also has a **toggle** fast-forward mode
> (settings-menu checkbox `Campaign "speed up time" is a toggle`, backed by a static boolean in
> `com.fs.starfarer.settings.StarfarerSettings`: getter `Oo0000()Z`, public static setter
> `?00000(Z)V`). In toggle mode the per-frame key poll is skipped entirely; the persistent
> `CampaignState.fastForward` field (plain name, private) is flipped only by a consumable
> `FAST_FORWARD` key event — so the guest's existing pre-core event consumption blocks it, and a
> `MethodHandles` field write sticks. The speed loop is
> `iters = fastForward ? Math.round(getFloat("campaignSpeedupMult")) : 1` with a live `getFloat`
> each frame, so the multiplier is also runtime-settable via public `SettingsAPI.setFloat`.
> Verified by `javap` disassembly of `starfarer_obf.jar` (dumps in `K:\Starsector\tmp_ff_analysis\`).
> Phase 7b restores shared fast-forward on this basis; the 1x lock below remains the fallback when
> the handles fail to resolve.

Hold-Shift fast-forward is implemented inside the obfuscated
`com.fs.starfarer.campaign.CampaignState.advance(float, ...)` as a per-frame loop that calls
`CampaignEngine.advance()` ~2x while the key is held. **Runtime-verified via TIMEDIAG logging:**
while fast-forwarding, the campaign clock advanced at exactly 2x, yet both
`SectorAPI.isInFastAdvance()` and `SectorAPI.isFastForwardIteration()` read `false` at the point an
`EveryFrameScript` observes them.

- `setFastForwardIteration(boolean)` - internal per-frame flag the engine overwrites; setting it
  does nothing. (Original plan assumption - wrong.)
- `setInFastAdvance(boolean)` - drives a *separate* extra `CampaignClock.advance()` inside
  `CampaignEngine.advance()` (the ">>" toggle path), NOT the hold-Shift loop; reads `false` on the
  host during hold-Shift, so it cannot *capture* fast-forward.
- **Capture lever (host):** `CampaignUIAPI.isFastForward()` - the public getter that reflects the
  fast-forward state. Reached via `Global.getSector().getCampaignUI().isFastForward()`.
- **Apply lever (guest):** `SectorAPI.setInFastAdvance(hostValue)` each frame makes the guest's
  clock run ~2x to mirror the host.
- `setInFastAdvance(true)` sticks on the guest (read-back is true) but does **not** change the
  guest clock rate (verified: `dGuestClockTs` stays 1x). The real 2x comes only from
  `CampaignState.advance` calling `CampaignEngine.advance()` twice per frame.
- The guest's own hold-Shift loop lives in obfuscated `CampaignState` and polls the key directly,
  so it cannot be blocked via public API (consuming the `FAST_FORWARD` input event does not stop
  it). Forking `CampaignState` is impractical because it is obfuscated engine code (unlike the
  Phase 11 forks, which were readable `com.fs.starfarer.api.impl.*` source).

### Resolution adopted for v1

> **⚠️ SUPERSEDED (plan Phase 7b):** the 1x lock is demoted from "the resolution" to "the fallback";
> Phase 7b removes the static `campaignSpeedupMult:1` override and restores shared fast-forward via
> toggle mode, re-applying a 1x lock at runtime (`setFloat`) only when the MethodHandles are
> unavailable.

Because hold-mode fast-forward cannot be mirrored or blocked via public API, the v1 coop session was
locked to 1x instead:

- `data/config/settings.json` sets `"campaignSpeedupMult":1` (engine default is 2). Hold-Shift then
  advances at 1x for any client running the mod, so no client can fast-forward and there is nothing
  to mirror. Verified: with the override, `clockTs` stays at the 1x delta even while
  `CampaignUIAPI.isFastForward()` reports true. (Side effect: fast-forward is also disabled in solo
  games while the coop mod is enabled.)
- `CoopTimeLock.capture()` still reads `CampaignUIAPI.isFastForward()` and `apply()` still calls
  `setInFastAdvance(...)` to keep the guest's flag consistent with the host (animation/UI only); the
  1x lock - not these calls - is what enforces equal time rate.

### Connect-time clock alignment

> **⚠️ SUPERSEDED IN PART (2026-06-10, see plan Phase 7c).** The "no public clock-setter" claim
> below is wrong on 0.98a-RC8: `com.fs.starfarer.campaign.CampaignClock` is `DoNotObfuscate`, its
> source of truth is a `private transient GregorianCalendar cal` exposed by a **public `getCal()`**
> (so the clock is writable via plain `cal.setTimeInMillis(...)`), with the cached
> `private long timestamp` field re-syncable via `MethodHandles` (javap dump:
> `K:\Starsector\tmp_ff_analysis\CampaignClock.javap.txt`). Also note `advance(float)` int-truncates
> calendar-seconds per frame, so clocks drift structurally across machines even at shared 1x.
> Phase 7c builds a guest-side drift reconciler on this basis (bounded monotonic slew + forward-only
> snaps). The host-pause-hold during connect described below REMAINS correct and primary —
> prevention still beats correction for the connect gap.

There is **no public clock-setter on the API surface** (`CampaignClockAPI` has no `setTimestamp`;
`SectorAPI` has no `setClock`; `createClock(long)` makes a detached clock that cannot be installed),
and fast-advance cannot be driven, so a guest that starts behind the host **cannot be made to catch
up** *via API-only means*. If the host
runs unpaused during the multi-second connect/handshake/seed-lock, the guest starts several campaign
days behind permanently.

Fix: the host **holds the campaign paused** (`CoopNetPump.maybeHoldHostPausedUntilSessionReady`) from
the moment it starts hosting until the session is active (`handshakeValidated && seedLong != null`).
No time passes during connection, so the guest starts aligned; afterwards the host's normal
pause/unpause mirrors to the guest (residual offset = network latency, sub-second). Prevent the gap
rather than close it.

Pause itself is a real lever: `setPaused`/`isPaused` is read by the engine every frame, so the guest
pause lock works fully.

## Regression Tests

Keep these tests aligned with the rules above:

- `coop.net.CoopNetServiceSandboxCompatibilityTest`
- `coop.net.CoopNetServiceTest`
- `coop.net.CoopNetPumpInstallerTest`

Before declaring runtime networking safe, run:

```powershell
rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"
```

Then deploy to both test clients and verify PING/PONG in both logs.
