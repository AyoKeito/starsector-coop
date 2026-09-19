# Starsector Coop

**A two-player campaign mod for Starsector.** Fly your own fleet through a shared sector, trade from
the same markets, and manage colonies together. The host runs the world; the guest's game mirrors it.
Your ships, cargo, credits, officers, and skills stay yours.

<p>
  <a href="https://github.com/AyoKeito/starsector-coop/releases">Releases</a> &nbsp;·&nbsp;
  <a href="#quick-start">Quick start</a> &nbsp;·&nbsp;
  <a href="docs/player/LIMITATIONS.md">Known limitations</a> &nbsp;·&nbsp;
  <a href="CHANGELOG.md">Changelog</a>
</p>

> [!IMPORTANT]
> **Version 0.1.3 is in private testing.** Each player fights their own battles on their own PC;
> the other player waits on a paused campaign. Two players piloting in the same battle is not
> supported in this release.

## What you share

| Part of the campaign | How it works |
| --- | --- |
| Sector and exploration | See your partner's fleet and the same NPC fleets. Survey progress, explored ruins, and salvaged wrecks are shared. |
| Campaign time | Pause and fast-forward together. Battles and interaction screens pause the campaign for your partner. |
| Markets and storage | Buy from shared open, black, and military markets. Use the same storage locker, including stored ships. Take turns docking at a market. |
| Colonies and reputation | Govern the same colonies under one faction. Faction reputation changes affect both players. |
| Player transfers | Jettison cargo for your partner to collect, exchange ships through storage, or send credits from **Intel → Coop Options**. |

The [limitations guide](docs/player/LIMITATIONS.md) explains the details, including storage fees,
local trade effects, missions, and differences between the two screens.

## Requirements

- **Starsector `0.98a-RC8` on both PCs**, with Ironman off.
- **The same co-op build and other mods on both PCs**, including matching mod versions.
- **Windows for the launcher.** It uses the Java runtime bundled with Starsector.
- **A connection to the host's PC.** Use a LAN, VPN, IPv6, port forwarding, or a router with
  UPnP/NAT-PMP. The default port is `7777`; forward both TCP and UDP if configuring it manually.
  See the [connection guide](docs/player/CONNECT.md).

The games check versions and builds when connecting and reject mismatched installations.

## Quick start

### 1. Install on both PCs

Use the packaged mod archive for your build. GitHub's source ZIP does not include the compiled jars.
Extract it into `<Starsector>\mods\coop` so the files sit directly inside that folder:

```text
Starsector/
└── mods/
    └── coop/
        ├── Coop Launcher.cmd
        ├── mod_info.json
        └── jars/
            ├── coop.jar
            ├── coop-forks.jar
            ├── coop-launcher.jar
            └── flatlaf.jar
```

Open **Coop Launcher.cmd**, then **Details** beside the installation status. Press **Fix** on each
repairable problem. The launcher enables the mod and adds the required `coop-forks.jar` entry to
the game's startup classpath, with a backup of `vmparams` before editing it.

For manual setup or a failed install check, follow the [installation guide](docs/player/INSTALL.md).

### 2. Send an invite

| Host | Guest |
| --- | --- |
| Choose **Host a game**. Leave **Campaign** on `New campaign`, or select a saved campaign to continue. | Choose **Join a game**. |
| Press **Copy invite** and send the line to your partner. | Press **Paste invite** to fill in the address, port, password, and world settings. |

The invite includes the session password. Share it privately with your partner.

### 3. Check the connection and play

1. The host presses **Check connection** and leaves the launcher open.
2. The guest presses **Check connection** with the invite pasted in.
3. Both press **Launch Starsector**, then **Play** in Starsector's own launcher.
4. Follow the save advice shown in the co-op launcher: start a **New Game** for a fresh campaign,
   or load the co-op save it names.

A failed check has details in the launcher. The [connection guide](docs/player/CONNECT.md) covers
router setup, firewall rules, and the messages shown when a connection fails.

## Before starting a campaign

**Keep your co-op saves and stay on the same build.** The host's save holds the campaign, and both
players' saves require the mod. To rejoin, load your co-op save; starting a new game with the same
seed does not restore your progress. Save compatibility across mod releases is not guaranteed.

A few other limits affect how you play:

- **Story content:** the Galatia Academy chain runs on the host. Deep-space content and sensor
  ghosts are also host-only.
- **Weather:** hyperspace storms, star flares, and slipstreams differ between PCs.
- **Solo play:** disable the mod for separate solo campaigns. Co-op rules apply while it is enabled,
  even without a connected partner.

Read [what is and is not shared](docs/player/LIMITATIONS.md) before committing to a long campaign.

## Report a problem

**Collect reports before relaunching the game**, which rewrites `starsector.log`.

On both PCs, open **Logs → Save a bug report** in the co-op launcher. Then
[open an issue](https://github.com/AyoKeito/starsector-coop/issues/new/choose) with both report ZIPs,
what each player was doing, and any `[COOP-DOCTOR]` line from the session-ending dialog or
`report.txt` inside the ZIP.

Reports include connection addresses and, by default, your newest save. Review them before posting
publicly. The [reporting guide](docs/player/REPORTING.md) lists the contents and explains how to
collect logs if the launcher cannot run.

## Development

Start with the [developer guide](README_DEV.md) for builds, tests, the two-client setup, the agent
bridge, and release packaging. The [design document](docs/COOP_MP_DESIGN.md) describes the
architecture; the [implementation plan](docs/COOP_MP_IMPLEMENTATION_PLAN_V1.md) records the phases.

## License

The mod's own code, data, and documentation use **CC BY-NC 4.0**. See [LICENSE](LICENSE) for the
full terms, the exception for Fractal Softworks, and notices covering game-derived and third-party code.
