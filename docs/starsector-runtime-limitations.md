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
- Mod-created networking daemon threads are not reliable enough for campaign networking. In live two-client testing the `coop-net-*` threads disappeared while the game stayed running and the socket state was left half-open.

Current rules:

- Do not add Netty or similar reflection-heavy networking libraries to `mod_info.json`.
- Do not use `java.io.*` in runtime campaign/network code. Use plain arrays, strings, and sandbox-proven JDK types instead.
- Keep coop networking progressed from `EveryFrameScript.advance()` on the campaign thread.
- Keep runtime dependencies minimal and covered by sandbox compatibility tests.

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
