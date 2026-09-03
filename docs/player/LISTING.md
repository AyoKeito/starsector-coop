# Starsector Coop V1 (0.1.0)

Two people, two copies of Starsector 0.98a-RC8, one campaign. The sector is generated once from a
shared seed and both of you fly around in it at the same time, on one calendar. The host's game is
the authority: it runs the NPC fleets, the markets, the economy and the colonies, and the guest's
game mirrors them. Each player pilots their own battles on their own PC. Author: AyoKeito.

## What it is

- Two players. One host, one guest. Not a server, and not a lobby browser: the guest is given the
  host's address and connects to it.
- The host's engine decides the world. Where the two games disagree, the host is right and the guest
  is corrected.
- Your fleet, cargo, credits, officers and skills are yours. The world is shared.

## What works

**Time.** One campaign clock. It moves for both of you or for neither: a menu, an interaction dialog
or a battle on either side pauses the other, and the status line names who is holding it. Fast
forward is shared and both clocks speed up together.

**The world.** Your partner's fleet is on your map with its real ships, cargo, sensor profile and
abilities. Patrols, traders, pirates and bounty fleets are simulated once by the host and mirrored,
including the ones chasing your partner. Salvage, surveys and exploration are shared, so one of you
looting a wreck means both of you see it gone. Markets, shop stock, officer pools and bar offers are
shared, and docking refreshes what you see. Faction reputation is shared, which means your partner's
smuggling shows up on your standing.

**Colonies.** One player faction with two governors. Both of you can found colonies, build
industries, run the construction queue and use colony storage on the same colonies. Monthly income
splits evenly. Raids and bombardments work in both directions, and an incoming expedition raises a
warning with a countdown on both screens.

**Combat.** Whoever gets engaged fights on their own PC and pilots it as normal. The other player is
held paused and gets a banner when the fight starts and another when it ends. The result is
reconciled back into the shared world: ship losses, salvage, reputation, bounties.

**Connecting.** Only the host has to be reachable. At startup the host's game asks the router to open
its port over UPnP, then NAT-PMP, and writes what happened to the log. Before the first session the
host can press Check my connection in the launcher, which runs that same mapping and puts the result
on screen instead of in a log file, and the guest can then press Test connection to find out whether
TCP and UDP reach the host at all. There is an optional lobby password, and the launcher generates
one for the host and puts it in the invite. A dropped link is held for 60 seconds with a countdown on
both screens and the session resumes where it stopped if the connection comes back. When a session is
refused or ends with a reason, you get a dialog naming the cause and the remedy.

**Reading the state.** A status line in a corner of the campaign screen shows role, session state,
who is pausing, round trip, packet loss and which transport your fleet positions travel over. A
"Coop Session" page in the intel screen adds link history, what your partner measures from their
side, and a log of everything that has gone wrong this session. A "Coop Stats" page tallies the
campaign.

**Settings.** A "Coop Options" page in the same intel screen: the host sets the session's rules and
they sync to the guest, each player sets their own preferences, and the page writes them to your
settings file for you.

**Saving.** Coordinated saves on both machines. The guest rejoins by loading its co-op save.

## What does not work, and what differs

The full list is in `docs/player/LIMITATIONS.md`. The ones worth knowing before you download:

- **Identical installs.** Same Starsector version, same mod list, same versions, same download of
  this mod, on both PCs. The two games compare manifests at connect and refuse a session over one
  differing field. Ironman off on both.
- **One line of `vmparams` edited by hand,** by both players. `jars/coop-forks.jar` holds engine
  classes the JVM only prefers if they come first on the system classpath, and Starsector's mod
  loader is too late for that. Skip the edit and the game still runs, with NPC fleets clustering
  around the host and largely ignoring the guest.
- **Traffic is plaintext.** Anyone on the network path between the two of you can read the message
  payloads. The lobby password stops strangers from joining an open port; it does not encrypt
  anything. A VPN is the only thing that does.
- **One guest.** The wire format can carry more, and the setting exists, but any value other than 1
  is clamped back to 1: the gameplay side of a third player is not built.
- **Both players in one battle is not in this release.** There is no in-game view of your partner's
  fight either. People watch over Discord screen share.
- **Solo play is not supported with the mod enabled.** Difficulty is forced to Normal, the tutorial
  is skipped, and the career list gains a test start. Turn the mod off for solo campaigns.
- **The guest's save is co-op material,** not a solo campaign you can load later. Both saves need the
  mod from then on.
- **Fast forward becomes a toggle** rather than a hold for the duration of a session. Your setting is
  restored when the session ends.

Accepted divergences, in short: contacts, their missions and person bounties are local to each
player, and the guest gets no person bounties at all. Sensor ghosts do not appear for the guest. The
abyss content lives on the host's engine. The two of you see different slipstream maps and different
hyperspace storms. Bar offers are the same jobs from the same people at different tonnage and pay.
A system can be remote-surveyed once by each of you. Colony construction bars and shortage markers
can read differently on the two screens until they converge, with the host's reading canonical.

## Requirements

- Starsector `0.98a-RC8` on both PCs. A different RC is refused at connect.
- The host's machine reachable from the guest, by one of: a VPN pseudo-LAN, IPv6, a port forward you
  add yourself, or UPnP/NAT-PMP. `docs/player/CONNECT.md` walks through all four and explains what
  the connection doctor writes to the log. Only the host needs this; nothing dials the guest.

## Installing

`docs/player/INSTALL.md` is the guide, and both players follow all of it. Three steps: unzip so
`mod_info.json` sits in `<Starsector>\mods\coop`, add `..\mods\coop\jars\coop-forks.jar;` to the
front of the `-classpath` in `<Starsector>\vmparams`, tick the mod in Starsector's launcher. That
`vmparams` line is edited by hand, and it is the only thing you edit by hand.

The rest is `Coop Launcher.cmd`, in the mod folder. It runs on the JRE that ships with the game, so
there is nothing to install. It checks the install and names what is wrong with it, writes your
settings into `saves\common\coop_options.json.data`, and starts the game. The host picks a port,
presses Generate for a seed and presses Copy invite; the guest presses Paste invite and has the
address, port, password and seed in one go. Windows only in this release; on any other OS the
settings file and the `-D` properties still work by hand.

## Reporting a problem

Both of you press **Save a bug report** in the launcher, and attach both zips. That packs the logs
before the next launch overwrites them, together with your settings, your newest save and a summary;
the password is blanked out of everything in it. One side's zip tells half the story, because a
refusal is one machine rejecting the other.

Paste the `[COOP-DOCTOR]` line from each side into the text of the report. It carries the code the
dialog showed you and a session id that is identical in both logs, which is what pairs the two zips
up. `docs/player/REPORTING.md` has the rest, including what to send when the launcher itself will not
run.

## License

CC BY-NC 4.0 for everyone except the game's developer. Use it, modify it, redistribute it and
build on it with credit; do not sell it or ship it inside anything sold. Fractal Softworks is
exempt from all of that: they may put any of this code into Starsector, sell the game with it, and
owe no credit. The forked engine classes in `coop-forks.jar` stay Fractal Softworks' property. Full
text in `LICENSE` at the repo root.
