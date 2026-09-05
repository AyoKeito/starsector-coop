# Starsector Coop V1 (0.1.0)

Two people, two copies of Starsector 0.98a-RC8, one campaign. The sector is generated once from a
shared seed and both of you fly around in it at the same time, on one calendar. The host's game is
the authority: it runs the NPC fleets, the markets, the economy and the colonies, and the guest's
game mirrors them. Each player pilots their own battles on their own PC. Author: AyoKeito.

## Requirements

- Starsector `0.98a-RC8` on both PCs. A different RC is refused at connect. Same mod list, same
  versions, same download of this mod, on both. Ironman off on both.
- The host's machine reachable from the guest, by one of: a VPN pseudo-LAN, IPv6, a port forward you
  add yourself, or UPnP/NAT-PMP. `docs/player/CONNECT.md` walks through all four and explains what
  the connection doctor writes to the log. Only the host needs this; nothing dials the guest.
- Windows for the launcher. On any other OS the settings file and the `-D` properties still work by
  hand.

## Installing

`docs/player/INSTALL.md` is the guide, and both players follow all of it. Three steps: unzip so
`mod_info.json` sits in `<Starsector>\mods\coop`, put `..\mods\coop\jars\coop-forks.jar;` at the
front of the `-classpath` in `<Starsector>\vmparams`, tick the mod in Starsector's launcher. The
co-op launcher's install check does the last two with a **Fix** button; the guide keeps the hand edit
for installs where that write is refused.

The rest is `Coop Launcher.cmd`, in the mod folder. It runs on the JRE that ships with the game, so
there is nothing to install. It checks the install and names what is wrong with it, writes your
settings into `saves\common\coop_options.json.data`, and starts the game. The host picks a port,
presses Generate for a seed and presses Copy next to the invite line; the guest presses Paste and has
the address, port, password and seed in one go.

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
smuggling shows up on your standing. Cargo pods are shared too, and they are how you hand your partner
fuel or supplies: jettison it, they fly over and pick it up.

**Colonies.** One player faction with two governors. Both of you can found colonies, build
industries, run the construction queue and use colony storage on the same colonies, one of you at a
time in the colony screen. Monthly income splits evenly. Raids and bombardments work in both
directions, and an incoming expedition raises a warning with a countdown on both screens.

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

**Saving.** Coordinated saves on both machines. The guest rejoins by loading its co-op save, and the
launcher names which save that is: character, level, save time and folder. Load the wrong one and the
mod says so in game and points at the right one.

**Reading the state.** A status line in a corner of the campaign screen shows role, session state,
who is pausing, round trip, packet loss and which transport your fleet positions travel over. A
"Coop Session" page in the intel screen adds link history, what your partner measures from their
side, and a log of everything that has gone wrong this session. A "Coop Stats" page tallies the
campaign.

**Settings.** A "Coop Options" page in the same intel screen: the host sets the session's rules and
they sync to the guest, each player sets their own preferences, and the page writes them to your
settings file for you.

## What does not work

The full list is in `docs/player/LIMITATIONS.md`. The ones worth knowing before you download:

- **Both players in one battle is not in this release.** There is no in-game view of your partner's
  fight either. People watch over Discord screen share.
- **Hyperspace is only partly shared.** Storm cells, star flares and slipstreams are rolled on each PC
  separately, so the two of you see different weather and different slipstream maps, and your
  partner's fleet can appear to cross empty hyperspace faster than it should. A storm only hits the
  fleet standing in it, so what you lose is the matching sky, not ships.
- **Deep space is the host's.** The abyss content (rogue stellar objects, the lights, Threat
  encounters) exists on the host's engine only. The guest can fly in, and finds it empty. Sensor
  ghosts do not appear for the guest anywhere.
- **Story missions are the host's.** The Galatia Academy chain runs on the host's engine and its
  rewards go to the host. The mod does not stop the guest from accepting those missions, but the story
  state is not shared, so a guest who does gets a private storyline the host never sees.
- **Contacts, their missions and person bounties are local to each player,** and the guest gets no
  person bounties at all.
- **No direct trade between players.** Nothing on screen moves credits or cargo from you to your
  partner. Cargo goes by jettisoned pod, ships through a colony's storage, and credits cannot be
  handed over at all.
- **Talking your way out is local on the guest.** When a fleet catches the guest, the vanilla
  encounter runs on the guest's PC: pay them off, spend the story point, or leave. The host's copy of
  that fleet hears none of it, so the same fleet can come back for another try two minutes later.
  Fighting is different: losses on both sides are reconciled into the shared world.
- **No text chat.** The mod assumes you are on voice.
- **Solo play is not supported with the mod enabled.** Difficulty is forced to Normal, the tutorial
  is skipped, and the career list gains a test start. Turn the mod off for solo campaigns.
- **The guest's save is co-op material,** not a solo campaign you can load later. Both saves need the
  mod from then on.
- **Fast forward becomes a toggle** rather than a hold for the duration of a session, at the engine's
  own 2x. Your setting is restored when the session ends.
- **One guest.** The wire format can carry more, and the setting exists, but any value other than 1
  is clamped back to 1: the gameplay side of a third player is not built.
- **Traffic is plaintext.** The lobby password stops strangers from joining an open port; it does not
  encrypt anything. A VPN is the only thing that does.

## Accepted divergences

Places where the two games legitimately read differently. None of these is a bug report.

- Bar offers are the same jobs from the same people at different tonnage and pay.
- A system can be remote-surveyed once by each of you.
- Colony construction bars and shortage markers can read differently on the two screens until they
  converge, with the host's reading canonical.
- A pirate or Luddic Path base is built by whichever game found it, so its market is that game's own:
  the stock is not synchronised between you.

## Reporting a problem

Both of you press **Log**, then **Save a bug report**, in the launcher, and attach both zips. That packs the logs
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
