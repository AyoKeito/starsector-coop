# Phase 11 - RNG Determinism Notes

Status as of 2026-05-29. These notes record the seed-lock convergence fix found while
testing Phase 6.

## Result

Seed-lock accepts when both clients launch with the same `-Dcoop.newGameSeed` and start a
fresh game with matching new-game options. The verified matching fingerprint contained 272
canonical entries, including deep-space systems.

## Implementation Learned

- The vanilla new-game seed field is not enough for automated two-client runs. The mod now
  registers `coop.seed.CoopSectorProcGen` through `data/config/settings.json`; that procgen
  subclass applies `coop.newGameSeed` to `CharacterCreationData.setSeed(long)` and
  `setSeedString(String)` before vanilla `SectorProcGen.prepare()` runs.
- Derived seed longs must be positive. Vanilla `SectorProcGen.prepare()` only seeds
  `StarSystemGenerator.random` when `data.getSeed() > 0`, so the SHA-256-derived long is
  masked with `Long.MAX_VALUE`.
- `data/scripts/com/fs/starfarer/api/impl/...` source forks are not reliable for already
  loaded API classes. Forked engine classes that must override `starfarer.api.jar` are
  compiled into `jars/coop-forks.jar` and prepended to the JVM `-classpath` by the host and
  guest launch scripts.
- Forked classes load in the JVM system classloader, while `coop.jar` loads in Starsector's
  child mod classloader. Any helper used by a fork must also be visible to the system
  classloader. `coop.rng.CoopRandom` is therefore built into `coop-forks.jar` and excluded
  from `coop.jar`.
- `CoopRandom` reads only the JVM `coop.newGameSeed` property. It must not depend on mod
  runtime state, because forked engine classes cannot see child-loader classes or objects.

## Forks In This Fix

- `com.fs.starfarer.api.util.Misc`
  - Seeds `Misc.random` from `CoopRandom.ofOrDefault("Misc.random")` during coop launches.
  - Replaces `genRandomSeed()` wall-clock `System.nanoTime()` entropy with the shared
    positive session seed while preserving vanilla's deterministic `seedUniquifier()`
    counter.
  - Logs one `[COOP-FORK] Misc fork active...` probe to prove the classpath override loaded.
  - Both the field and the probe are bound during core data loading, about ten seconds before any
    mod plugin exists, so the seeding above only happens in time on the `-D` path. The Phase 31
    launcher's seed lives in `saves/common/coop_options.json.data` and becomes a system property
    only when `CoopModPlugin.onApplicationLoad` republishes it; on that path the probe legitimately
    reads `coopSession=false` and `CoopModPlugin.rebindTheForkedSharedRandom` writes the field from
    the same `CoopRandom` stream immediately afterwards. The line that says the seed took is
    `Coop reseeded the forked Misc.random`, not the probe.
- `com.fs.starfarer.api.impl.campaign.world.GateHaulerLocation`
  - Temporarily swaps `StarSystemGenerator.random` to an independent
    `CoopRandom.of("GateHaulerLocation")` stream for `generate()`, then restores it.
- `com.fs.starfarer.api.impl.campaign.world.NamelessRock`
  - Uses the same method-duration swap with `CoopRandom.of("NamelessRock")`.
- `com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC`
  - Reseeds abyss encounter `data.random` from a stream keyed by encounter id and rounded
    hyperspace coordinates. This is for later abyss parity; it is not needed for the
    session-start seed-lock fingerprint.

## Other Forks In `coop-forks.jar` (not RNG)

Added 2026-08-19 by the Phase 14 spike follow-up. These use the same classpath-shadow mechanism
and the same classloader rules as the RNG forks above, but a different helper: the guest-presence
slot `coop.presence.CoopPresenceRegistry`, which also owns the shared pinned-version guard
(`PINNED_VERSION` + `getForFork(String)`) — one constant to change on a Starsector version bump,
one verdict logged once per process, and on a mismatch every presence term goes silent and the
forks behave as stock. Every edit in them is additive and guarded on `presence != null`, and none
adds an instance field (all are save-serialised `EveryFrameScript`s).

- `com.fs.starfarer.api.impl.campaign.fleets.RouteManager`
- `com.fs.starfarer.api.impl.campaign.fleets.PlayerVisibleFleetManager`
- `com.fs.starfarer.api.impl.campaign.fleets.DisposableFleetManager`
- `com.fs.starfarer.api.impl.campaign.fleets.SourceBasedFleetManager`
- `com.fs.starfarer.api.impl.campaign.intel.events.DisposableHostileActivityFleetManager`
- `com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager`

Re-fork procedure for any of them: copy the new vanilla source over the fork byte-identically,
diff to confirm, then re-apply only the `COOP FORK`-tagged hunks listed in the file's header
banner. `diff` will also show whitespace-only blank-line hunks; those are editor normalisation and
carry no meaning.

## Fingerprint Scope

`CoopSectorFingerprint` is a session-start tripwire, not a full world equality proof.
It includes system id, market id, market size, faction id, and rounded hyperspace anchor
coordinates.

Hidden markets are excluded with `MarketAPI.isHidden()`. These are dynamic pirate/Pather
bases with engine-generated ids and timer-driven lifecycle, so they are not reproducible
from the new-game seed and should be covered by later host-authoritative replication.

## Open Follow-Ups

- Replicate hidden pirate/Pather base state from the host in the host-authoritative campaign
  phases.
- If later gameplay parity testing finds additional dynamic RNG divergence, add narrowly
  scoped forks under `forks/`, not under `data/scripts/`.

## Fingerprint Mutability Contract (Phase 6b)

The fingerprint includes two fields of **mutable campaign state**: `marketSize` and `factionId`.
That is deliberate. The fingerprint is re-validated on every session start, including loaded-save
reconnects, so those fields are the tripwire proving both saves evolved identically.

The contract this creates for every future phase: **any feature that mutates market size, faction
ownership, or market existence must ship with replication of that mutation to the guest's save, or
the next reconnect hard-rejects with no heal path.** Player colonization is the first realistic
vector (Phase 24); decivilization is the second (Phase 13's `WORLD_DELTA(DECIV)`). The fix for a
tripped fingerprint is always to add the missing replication, never to relax the check — splitting
structural vs mutable fingerprints was considered in 6b and rejected for exactly that reason.

On a mismatch, both sides log their full canonical fingerprint text (one line per entry, the exact
SHA input), so the diverged entry is found by diffing the two log files. There is no diff protocol
on purpose: the framed transport has a fixed buffer and the canonical text is ~11 KB.

Related principle: seeds are **gen-time only** — runtime RNG must be replicated, never re-seeded.
The full statement and per-script audit live in plan Phase 13; the Phase 6 audit that established it
is folded into the plan's Phase 6b section.
