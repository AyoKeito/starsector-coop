# Starsector Coop

Two people, two copies of Starsector 0.98a-RC8, one campaign. The sector is generated once from a
shared seed and both players fly in it on one calendar. The host's game runs the world: NPC fleets,
markets, the economy, the colonies. The guest's game mirrors it. Each player pilots their own battles
on their own PC.

Version 0.1.0, Windows, in a private test period before a public forum post. Expect rough edges and
report them.

## Quick start

Both players do all of this.

1. Unzip so `mod_info.json` sits in `<Starsector>\mods\coop`.
2. Double-click `<Starsector>\mods\coop\Coop Launcher.cmd`.
3. Press **Fix** on every red row in the Install card. That puts `..\mods\coop\jars\coop-forks.jar;`
   at the front of the `-classpath` in `<Starsector>\vmparams` and adds `coop` to
   `mods\enabled_mods.json`.
4. Host: press **Host** at the top right, leave **Campaign** on `New campaign`, press **Copy** next
   to the invite line, send that line to your partner.
5. Guest: press **Guest**, press **Paste** next to the invite field. Address, port, password, seed,
   sector size and star age all come out of the line.
6. Both press **LAUNCH**, then **Play** in the vanilla launcher window that follows.
7. Both start a New Game, or load the save named on the line under the card.

The full guide is `docs/player/INSTALL.md`.

## What you need

- Starsector 0.98a-RC8 on both PCs, the same mod list on both, and this mod from the same download
  on both. The two games compare versions at connect and refuse a session over one difference.
- The host's PC reachable from the guest's: a VPN, IPv6, a port forward, or a router that answers
  UPnP or NAT-PMP. Only the host needs this. `docs/player/CONNECT.md` walks through each.

## Docs

- [Install](docs/player/INSTALL.md)
- [Connect](docs/player/CONNECT.md)
- [Known limitations](docs/player/LIMITATIONS.md): read this before filing a bug
- [Reporting a problem](docs/player/REPORTING.md)
- [Changelog](CHANGELOG.md)

## Reporting a problem

Both players press **Log**, then **Save a bug report**, in the launcher and attach the zip it writes
to the desktop. Open an issue with the template; it asks for the `[COOP-DOCTOR]` line that any
session-ending dialog prints.

## License

CC BY-NC 4.0. Use it, change it, share it, do not sell it. Fractal Softworks may use any of it in
Starsector without conditions. Full text in `LICENSE`.

## Developers

`README_DEV.md` covers the build, the two-client test setup, the agent bridge and the release
checklist. `docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md` is the design and phase record.
