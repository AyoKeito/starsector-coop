# Installing the co-op mod

Two people, two copies of Starsector, one shared campaign. Both of you do everything on this page.
There is no "client-only" install; the two machines run the same mod and check each other at
connect time.

The one step that is not like other mods is the `vmparams` edit in section 3. Do not skip it.

---

## 1. What has to match on both PCs

| Thing | Requirement |
|---|---|
| Starsector version | `0.98a-RC8` on both. A different RC is refused at connect. |
| The co-op mod | The same download, unzipped on both. Two different builds are refused even when the version number matches. |
| Mod folder name | Exactly `coop`, so the path reads `<Starsector>\mods\coop` on both. |
| Every other mod | Same list, same versions, on both. |
| Ironman | Off on both. A session where either side is in Ironman mode is refused. |

Section 5 explains what the refusal message looks like and how the check works.

## 2. Unpack the mod

Unzip so that `mod_info.json` sits directly in `<Starsector>\mods\coop`:

```text
K:\Starsector\mods\coop\mod_info.json
K:\Starsector\mods\coop\jars\coop.jar
K:\Starsector\mods\coop\jars\coop-forks.jar
K:\Starsector\mods\coop\data\
```

If your unzip produced `mods\coop\coop\mod_info.json`, move the inner folder up one level. If it
produced `mods\starsector-coop-1.0\`, rename it to `coop`. The folder name travels over the wire as
part of the install comparison, so `coop-v1` on one PC and `coop` on the other will not connect.

## 3. Edit `vmparams` (the unusual step)

`jars/coop.jar` loads the way every mod's jar loads: the launcher reads it from `mod_info.json`.
`jars/coop-forks.jar` cannot. It holds ten engine classes copied from Starsector and modified
(`Misc`, `RouteManager`, `SourceBasedFleetManager` and seven more), and the JVM only prefers a copy
over the original if the copy comes earlier on the system classpath. Starsector's mod loader is a
child classloader, which is too late. So the jar has to be named in `vmparams`, by hand, once.

**Back up the file first.** Copy `<Starsector>\vmparams` to `vmparams.backup`.

Open `<Starsector>\vmparams` in a text editor. It has no extension and holds one very long line.
Find the text ` -classpath ` and insert this immediately after it, semicolon included:

```text
..\mods\coop\jars\coop-forks.jar;
```

The classpath on a stock 0.98a-RC8 Windows install starts like this:

```text
 -classpath janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.api.jar;...
```

After the edit:

```text
 -classpath ..\mods\coop\jars\coop-forks.jar;janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.api.jar;...
```

Rules for the edit:

- The relative path is correct as written. The launcher runs the JVM from `starsector-core`, so `..`
  is the install root.
- It must be first in the list. Behind `starfarer.api.jar` it does nothing.
- Save as plain text. Do not let the editor turn the single line into several, and do not change
  anything else on it.
- Redo the edit after any Starsector update; the installer overwrites `vmparams`.
- On a modded JRE setup where the launcher is a `.bat` file instead, put the same entry at the front
  of that file's `-classpath`.

**If you skip it, the game still starts.** The forked classes fall back to stock behaviour and the
log carries one warning:

```text
Coop guest presence unavailable: coop-forks.jar is not on the JVM classpath, so the spawner forks
cannot be reached. NPC fleets will only spawn near the host.
```

That means patrols, traders and pirates cluster around the host and largely ignore the guest. The
session works; the world is lopsided.

## 4. Enable the mod

Start the launcher, click MODS, tick **Starsector Coop V1**, click OK. Both players. If either of you
has other mods ticked, the other must tick the identical set.

## 5. Why identical installs are required

Before a session starts, both games send each other a manifest and compare it field by field. One
difference in any field ends the connection with the difference printed as the reason. What gets
compared, from `coop.handshake.CoopHandshakeManifest` and `CoopHandshakeDiff`:

- The Starsector version string.
- The co-op mod's own build version and git commit, so two separately built copies of "the same"
  version are still caught.
- For every enabled mod: id, display name, version, declared game version, folder path as
  `mods/<name>`, and the list of jars it declares.
- For every enabled mod: a SHA-256 of its `mod_info.json` text, with line endings normalised so a
  CRLF/LF difference does not read as a mismatch.

Jar contents are not hashed. Starsector's script sandbox hands the mod no way to read jar bytes, so
two people who edited a mod's jar without touching its `mod_info.json` would pass this check. Install
mods from the same downloads and it does not come up.

Ironman is checked separately and rejects the session on its own, on either side.

## 6. Launch settings

Everything the mod reads at launch is a JVM property. They go on the same `vmparams` line, anywhere
before ` -classpath `, each starting with `-D` and separated by spaces:

```text
... -Xss4m -Dcoop.hostPort=7777 -Dcoop.newGameSeed=MN-1234567890123456789 -classpath ..\mods\coop\jars\coop-forks.jar;janino.jar;...
```

Most of them can also go in a settings file instead; section 7 covers it. A property on the command
line always wins over the file.

### Host

| Property | Meaning |
|---|---|
| `coop.hostPort` | The TCP and UDP port to listen on, 1 to 65535. Setting it is what makes this install the host. There is no default; without it the game runs with no co-op role. |

### Guest

| Property | Meaning |
|---|---|
| `coop.connectHost` | The host's address. Hostname, IPv4, or IPv6 (bare `2001:db8::1` or bracketed `[2001:db8::1]`, never with the port glued on). |
| `coop.connectPort` | The host's port. Required whenever `coop.connectHost` is set. |

Setting host and guest properties on the same install stops the game at startup with
"Configure either host or guest coop startup properties, not both".

### Both, for a fresh campaign

| Property | Meaning |
|---|---|
| `coop.newGameSeed` | The sector seed, e.g. `MN-1234567890123456789`. Set the same value on both PCs and both New Game dialogs are pinned to it. Without it you each type a seed by hand and a typo ends the session at the seed check. |
| `coop.sectorSize` | `small` or `normal`. Omit for the panel default (`normal`). Must match. |
| `coop.sectorAge` | `young`, `average`, `old` or `mixed`. Omit for the panel default (`mixed`). Must match. |

Seed, size and age all feed the sector fingerprint the two games compare, so a difference in any of
them is caught at connect rather than discovered hours later.

### Optional

| Property | Default | Meaning |
|---|---|---|
| `coop.password` | none | Lobby password. Set the same string on both. The guest proves it knows the password without sending it, but the rest of the traffic is plaintext: this stops strangers from joining an open port, it does not encrypt the session. |
| `coop.portMapping` | `auto` | `auto` asks the router to open the host port (UPnP first, NAT-PMP second). `off` skips it. Any other value stops the game at startup. Guests ignore it. |
| `coop.reconnectGraceSeconds` | `60` | How long a dropped link is held open before the session ends. `0` ends it on the first drop; the ceiling is 3600. Each side sets its own. |
| `coop.playerName` | your character's name | The name your partner sees in the HUD and on the session page. |
| `coop.hudCorner` | `TR` | Where the co-op status line is drawn: `TR`, `TL`, `BR`, `BL`. |
| `coop.hud.disable` | `false` | `true` removes the status line. |
| `coop.adoptCampaignId` | `false` | Guest only, and only for the case in the note below. |
| `coop.maxGuests` | `1` | Peer capacity. Anything other than 1 is clamped back to 1 with a warning in the log. |

Rejoining after the guest quits: load the co-op autosave the mod wrote, not New Game. A fresh
campaign on the same seed is refused because the host's campaign is already in flight.
`-Dcoop.adoptCampaignId=true` forces it through at the cost of everything the guest had.

The `coop.debug.*` properties are diagnostics for bug reports and development; `docs/REPORTING.md`
covers the ones a player is ever asked for.

## 7. Settings file

The same `coop.*` names can live in a JSON file instead of on the `vmparams` line. Yours goes here,
and you create it yourself:

```text
<Starsector>\saves\common\coop_options.json
```

Flat JSON, only the keys you want to change:

```json
{
	"coop.hostPort": 7777,
	"coop.password": "hullmod",
	"coop.playerName": "Ayo",
	"coop.hudCorner": "BL"
}
```

Numbers, booleans and strings are all accepted for any key; the value is validated after it is read,
not before. `saves\common` is a Starsector folder rather than a mod folder, which is the entire
reason the file lives there: it survives updating or reinstalling the mod.

The reference is the copy the mod ships with:

```text
<Starsector>\mods\coop\data\config\coop_options.json
```

That file lists every key with its type, its range, its default, when a change takes effect and which
phase owns the behaviour, in a commented block each. Read it rather than a table here; it is the
table. It is overwritten on every mod update, so your values do not go in it. Two things to watch for
while reading it: a key marked INERT is wired to nothing in this build, and the keys in the `policy`
tier are read per-client today and become the host's to decide in a later milestone.

Precedence, highest first:

1. `-Dcoop.<key>=<value>` on the launch command line
2. `saves\common\coop_options.json`
3. `mods\coop\data\config\coop_options.json`
4. the default compiled into the mod

Both files are read once at launch, so a change needs a relaunch. A bad value never stops the game:
an integer outside its range is clamped to the nearest bound, anything else unusable falls back to
the default, and either way one warning goes to the log. An unrecognised key is also logged and
skipped.

The keys most people actually set:

| Key | Why you would set it |
|---|---|
| `coop.hostPort` | Makes this install the host. |
| `coop.connectHost`, `coop.connectPort` | Make it the guest. Both together or neither. |
| `coop.password` | Lobby password, same string on both. |
| `coop.playerName` | The name your partner sees. |
| `coop.portMapping` | `off` when you forwarded the port yourself, run a VPN, or the router answers UPnP badly. |
| `coop.reconnectGraceSeconds` | How long a dropped link is held before the session ends. |
| `coop.hudCorner`, `coop.hud.disable` | Where the status line sits, or whether it is drawn. |

Some keys are `-D` only, forever, and a value for them in either file is skipped with a warning:
`coop.newGameSeed`, `coop.sectorSize`, `coop.sectorAge`, `coop.adoptCampaignId`, and the debug
hatches `coop.debug.*`, `coop.ff.disable`, `coop.clock.disable`, `coop.fullFidelityGuestSystem`. The
first four are one-shot gestures for starting a campaign, where the friction is the point; the rest
are not meant to read as ordinary settings.

**The role keys carry one extra rule.** If any of `coop.hostPort`, `coop.connectHost` or
`coop.connectPort` is given as `-D`, the role is decided by the `-D` layer alone and the file's role
keys are ignored. Without that, a `coop.hostPort` sitting in your settings file would combine with a
`-Dcoop.connectHost` on the command line into "Configure either host or guest coop startup
properties, not both", and a machine set up to host could never be launched as a guest. Every other
key keeps plain per-key precedence.

Cadences, gate semantics, the seed and campaign checks, combat auto-pause, host authority itself: not
options, and not going to become options. The shipped file names that list too, so you can tell a
missing setting from a deliberate one.

## 8. First session

1. Both players finish the `vmparams` edit and tick the mod.
2. The host sets `coop.hostPort` and `coop.newGameSeed`; the guest sets `coop.connectHost`,
   `coop.connectPort` and the same `coop.newGameSeed`.
3. The host starts the game and reads `starsector-core\starsector.log` for the line beginning
   `Coop connection doctor:`. Its `share with guest` line is the address to send. `CONNECT.md`
   covers what to do when that line has nothing useful on it.
4. Both start New Game. The guest's Continue option names the host it will connect to.
5. The status line in the corner reads `HOST · waiting for guest`, then `session active` on both.

If it does not get that far, `CONNECT.md` is the next stop.
