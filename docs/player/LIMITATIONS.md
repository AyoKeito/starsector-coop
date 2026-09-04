# What is and is not shared

Two Starsector installs, each running its own copy of the game engine, kept in agreement by messages
over the network. The host's engine is the authority. Everything below follows from that, and none
of it is a bug report; these are the places where two co-op campaigns differ from one solo campaign.

---

## Battles

**You fight your own battles.** Whoever gets engaged runs the fight on their own PC and pilots it as
normal. Your partner's fleet on your screen is a mirror, not a participant, and it cannot be pulled
into your battle.

**Your partner is paused and gets a banner**, one when your battle starts and one when it ends. There
is no in-game view of the fight. A live-updating status panel was built and then removed: it flickered
on every redraw, and in practice people watch over Discord screen-share, where a full-screen dialog on
the watching client is in the way.

**Both players piloting in one battle is not in this release.** It is the largest single item on the
list of things that could come later.

## Saves

The host's save is the campaign. It carries the world, the colonies, the markets, and a record of what
the guest owned as of the last time the guest reported in.

The guest's save is co-op material only. It is not a solo campaign you can load later: the scripts
that generate the world are not in it, so opening it without a host is unsupported.

Both saves need the mod. Once a campaign has been played in co-op it cannot be loaded without the mod
enabled.

**A mod update can end a campaign in progress.** Save compatibility across releases is not promised,
because the co-op state written into a save is versioned with the code that wrote it. Unless a
release note says a save carries over, finish a campaign on the build you started it on. And update
together either way: the handshake compares the git commit baked into the jar, so one of you updating
alone refuses the session with `COOP-MODS`.

**Rejoining is by loading the co-op autosave, not by starting a New Game.** The mod writes a
coordinated autosave carrying the campaign's id. A fresh campaign on the same seed is refused, because
the host's campaign is already in flight and a brand new one is not it. There is an override for the
case where the guest's save is genuinely gone, and it costs the guest everything they had.

## Playing alone

Turn the mod off for solo campaigns. With it enabled the game is under co-op rules whether or not a
session exists, and three of those are visible immediately:

- Difficulty is forced to Normal and the difficulty question is never asked.
- The tutorial is skipped. The vanilla tutorial rewrites Galatia system state, which would pull two
  seed-locked campaigns apart.
- The career list has a sixth entry, "COOP TEST", that starts you in a Ziggurat with a million
  credits. It exists so a test session has something worth fighting over.

## Fast-forward

Fast-forward works and both clocks move together, but the mod switches Starsector to toggle mode for
the duration of a session, so Shift is a tap rather than a hold. Your own preference is restored when
the session ends. Two edges to know about: leaving the campaign mid-session (exit to menu, load another
save) leaves toggle mode on until the next session ends or you restart the game, and opening the
vanilla settings menu during a session and clicking apply writes the forced value into your settings
file. Neither breaks anything; both explain a "my Shift became a toggle" surprise.

---

## Shared, per-player, and neither

**Shared.** The world and everything in it: NPC fleets, markets and their stock, colonies and their
industries, survey levels, explored ruins, salvaged wrecks, faction reputation, and the campaign
clock.

**Per-player.** Your fleet, your cargo, your credits, your officers, your skills. Salvage loot is
per-player by design: the world remembers that a wreck was taken, and each of you rolls your own
contents from it. Same rule for survey data.

**Neither, in this release.** Contacts, the missions contacts offer, and person bounties are local to
each player. The guest gets no person bounties at all; the script that generates them is one of the
ones held back so it does not spawn a second copy of the host's world.

**Story missions are the host's.** The Galatia Academy chain and the missions it unlocks run on one
engine only, the host's, and their rewards go to the host. This is a rule you keep, not one the mod
enforces: nothing stops the guest from docking at the Academy and accepting the same missions, but
the story flags and the places those missions create live in each game's own memory and are not
copied across. A guest who starts the chain gets a private second storyline the host never sees,
and the two worlds drift apart from there. One-of-a-kind finds in the shared world are a different
matter: a derelict, a ruin, a cryosleeper or a one-time bar offer goes to whoever gets there first,
and the other player sees it taken.

## Divergences you will actually run into

**Colonies.** Both of you govern the same colonies under one faction. Two things do not line up. A
construction bar can read differently on the two screens until the industry finishes, at which point
the lagging side is forced to completion; the drift never grows past the gap between the two starts.
And a colony's commodity shortage markers can disagree, because each engine solves supply for the
whole sector on its own schedule. The host's reading is the one to trust.

Also on the guest: the vanilla hostile-activity meter runs its own simulation and predicts nothing
real, because the fleets it would spawn are suppressed. The co-op expedition warning, the one with a
countdown, is the entry that matches the fleets actually coming.

**Bar offers.** The same offers appear for both of you, from the same people, for the same
commodities, at different tonnage and different pay. The wire carries the offer's seed and each game
sizes the numbers against the local fleet and the local market. Both of you completing the same job is
the point; both being quoted the same fee is not.

Two smaller effects on the same screen. An offer the host has already taken keeps showing on the
guest until the next pool update drops it, so an accept can come back "already taken". And a pirate or
Pather base rumour is generated locally on each side, which can shuffle the whole list into a
different order, so the two bars occasionally show different picks out of the same pool.

**Surveying.** A system can be remote-surveyed once by each of you, where a solo campaign allows one
sweep. The survey data commodity goes to whoever ran the survey; the other player gets the survey
level without the cargo. A survey contract completes and pays when the target reads fully surveyed,
without asking who did it, so a contract the guest is holding pays out when the host surveys the
planet. And either player entering a system marks its planets as seen on both maps.

**Weather and hyperspace.** Hyperspace storm cells and star flares are rolled independently on each
PC. A storm only hits the fleet standing in it and both fleets are owner-authoritative, so this costs
nothing but a different sky. Slipstreams are the same story with a visible artifact: the two of you
see different slipstream maps, so your partner's fleet can appear to cross empty hyperspace
impossibly fast.

**The abyss.** The guest can fly into it. The deep content there (rogue stellar objects, lights,
Threat encounters) exists on the host's engine only, so those are host experiences.

**Sensor ghosts** do not appear for the guest at all. Several ghost types spawn real fleets or touch
story state, and an independent roll on the guest would be a real divergence rather than a visual one,
so they are held back entirely.

**Markets neither of you has visited recently** can drift apart, because Starsector rerolls shop stock
on save when the last roll is over 30 days old, and the two of you save at different moments. Docking
converges them. Right after a guest opens a market, mercenary captains and their levels can differ for
the second or two before the host's snapshot lands. A damaged multi-module ship in a listing arrives
on the other side with clean modules.

**Two abilities behave slightly differently for the guest.** An interdiction pulse the guest fires is
resolved on the host at slightly the wrong radius and strength, because one of the stats behind it is
not mirrored; the reputation cost is also charged when you start the charge-up rather than when the
pulse lands. And a distress call the guest makes brings the responding fleet in near the host, though
it does route to the right system.

## Clocks

The two campaign clocks are not identical to the second. Starsector advances its calendar in whole
seconds per frame, so two machines drift apart structurally even at the same speed. The guest corrects
itself continuously and the status line shows the gap once it reaches an hour of game time.

Running both games on one PC has its own version of this: Starsector caps its per-frame step, so a
minimised or background window runs its clock slow and looks from the other side like the other client
running fast. Keep both windows restored and visible.

## The launcher

**Windows only in this release.** `Coop Launcher.cmd` is a Windows batch file that starts
`jre\bin\javaw.exe`, a Windows executable at a Windows path. A Mac or Linux install starts the game from a shell script
that carries the classpath inline, and none of that was tested here. Those players set the same
values by hand: everything the launcher does to your configuration is writing
`saves\common\coop_options.json.data`, and `INSTALL.md` section 7 is the same settings without it.

**It edits two install files, and only when you press Fix.** The two things that have to be right
and are outside the mod's reach are the classpath entry in `vmparams` and the mod tick in
`enabled_mods.json`. The launcher reads both, and when one is wrong the red row carries a Fix button
that makes the exact edit described in `INSTALL.md`: `vmparams` is backed up to `vmparams.backup`
first and rewritten as the same single line with the entry moved to the front, and `enabled_mods.json`
gains `coop` with the other mods left in their order. An install under Program Files may refuse the
write; the launcher then offers to restart itself as administrator, and if you decline it shows the
manual edit instead. Beyond those two, it writes your settings file and its own log next to it in the
mod folder.

**The invite carries the password in clear text.** The `pw=` part of that one line is the password
itself, so whatever you send the line through can read it. Send it the way you would send a password.
The guest can read it out of their own launcher afterwards, which is the point.

**Look up sends one request to an outside service.** Finding your public address means asking a
machine on the Internet what address your packets arrive from; the launcher asks `api.ipify.org`, and
`icanhazip.com` if the first does not answer. That service learns your IP address, which it would
also learn from any web page you open. If you would rather not, leave the button alone and type the
address in yourself. The update check is the only other outbound request: one call to GitHub's
releases API when the window opens.

**A green Test connection is not a promise the session will start.** It proves the port is reachable
and that the thing answering is the co-op launcher. Mod lists, versions and seeds are compared later,
by the games, at connect.

## Networking

Traffic is plaintext. Message payloads are readable by anyone on the path between you. The lobby
password stops strangers from joining an open port; it is a gate, not encryption. A VPN (see
`CONNECT.md`, tier 0) is the only thing that encrypts the session.

One guest. The wire format can carry more and the setting exists, but any value other than 1 is
clamped back to 1 with a warning, because the gameplay side of a third player is not built.

The 60 second reconnect wait is not tied to the player who dropped. A restarted game arrives with a
new player id, so whoever clears the lobby password first ends the wait, and with no `coop.password`
set that is anyone who can reach the port while the countdown is on screen.

If the host's game crashes rather than exits, the port mapping it asked the router for outlives it.
Most routers expire it within the hour, and the mod deletes any stale mapping it finds at the next
launch. A router that refuses timed leases keeps the port open until then.
