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
- `com.fs.starfarer.api.impl.campaign.world.GateHaulerLocation`
  - Temporarily swaps `StarSystemGenerator.random` to an independent
    `CoopRandom.of("GateHaulerLocation")` stream for `generate()`, then restores it.
- `com.fs.starfarer.api.impl.campaign.world.NamelessRock`
  - Uses the same method-duration swap with `CoopRandom.of("NamelessRock")`.
- `com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC`
  - Reseeds abyss encounter `data.random` from a stream keyed by encounter id and rounded
    hyperspace coordinates. This is for later abyss parity; it is not needed for the
    session-start seed-lock fingerprint.

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
