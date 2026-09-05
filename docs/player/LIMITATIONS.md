# What is and is not shared

Two Starsector installs, each running its own copy of the game engine, kept in agreement by messages
over the network. The host's engine is the authority. Everything below follows from that, and none
of it is a bug report; these are the places where two co-op campaigns differ from one solo campaign.

## The short list

- **You fight your own battles.** Two players in one battle is not in this release.
- **Hyperspace weather and slipstreams differ between you.** Each PC rolls its own storms, flares and
  slipstream map.
- **Deep space and sensor ghosts are host-only.** The abyss content exists on the host's engine, and
  the guest sees no sensor ghosts anywhere.
- **Story missions are the host's.** The mod removes every way into the Galatia Academy chain on
  the guest. Contacts, person bounties and system bounties are local to each player.
- **No trade screen between the two of you.** Cargo goes by jettisoned pod, ships through shared
  storage, credits through a row on the Coop Options page.
- **The host's save is the campaign.** The guest's save is co-op material and needs the mod and a
  host to be worth loading.
- **Rejoin by loading the co-op save,** never by starting a New Game on the same seed.
- **Turn the mod off for solo campaigns.** With it enabled the game is under co-op rules whether or
  not a session exists.
- **One guest.** Traffic is plaintext; the lobby password is a gate, not encryption.

---

## Battles

**You fight your own battles.** Whoever gets engaged runs the fight on their own PC and pilots it as
normal. Your partner's fleet on your screen is a mirror, not a participant, and it cannot be pulled
into your battle.

**Your partner is paused and gets a banner**, one when your battle starts and one when it ends.
There is no in-game view of the fight; people watch over a Discord screen share, where a full-screen
panel on the watching client would be in the way anyway.

**Both players piloting in one battle is not in this release.** It is the largest single item on the
list of things that could come later.

## Saves

The host's save is the campaign. It carries the world, the colonies, the markets, and a record of
what the guest owned as of the last time the guest reported in.

The guest's save is co-op material only. It is not a solo campaign you can load later: the scripts
that generate the world are not in it, so opening it without a host is unsupported.

Both saves need the mod. Once a campaign has been played in co-op it cannot be loaded without the
mod enabled.

**Rejoining is by loading the co-op autosave, not by starting a New Game.** The mod writes a
coordinated autosave carrying the campaign's id. A fresh campaign on the same seed is refused,
because the host's campaign is already in flight and a brand new one is not it. There is an override
for the case where the guest's save is genuinely gone, and it costs the guest everything they had.

The launcher names the save to load, down to the character, the level, the save time and the folder,
so two campaigns' autosaves cannot be confused. Load a save from a different campaign and the mod
says so in game and names the right one; it is a warning you close, not a refusal.

**A mod update can end a campaign in progress.** Save compatibility across releases is not promised,
because the co-op state written into a save is versioned with the code that wrote it. Unless a
release note says a save carries over, finish a campaign on the build you started it on. And update
together either way: the handshake compares the git commit baked into the jar, so one of you
updating alone refuses the session with `COOP-MODS`.

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
the session ends. The campaign speed multiplier is also forced to 2, the engine default, on both
sides for the session, so a `campaignSpeedupMult` you raised in `settings.json` does not apply while
you are playing together.

Two edges to know about: leaving the campaign mid-session (exit to menu, load another save) leaves
toggle mode on until the next session ends or you restart the game, and opening the vanilla settings
menu during a session and clicking apply writes the forced value into your settings file. Neither
breaks anything; both explain a "my Shift became a toggle" surprise.

---

## Shared, per-player, and neither

**Shared.** The world and everything in it: NPC fleets, markets and their stock, colonies and their
industries, survey levels, explored ruins, salvaged wrecks, faction reputation, and the campaign
clock. Markets share four counters, not one: the open market, the black market, the military
submarket and the storage locker.

**Per-player.** Your fleet, your cargo, your credits, your officers, your skills. Credits are yours
alone until you send some: the Coop Options page has a Send credits row and that is the only thing
that moves money between you. Salvage loot is per-player by design: the world remembers that a wreck
was taken, and each of you rolls your own contents from it. Same rule for survey data.

**Neither, in this release.** Contacts, the missions contacts offer, and person bounties are local to
each player. The guest gets no person bounties at all; the script that generates them is one of the
ones held back so it does not spawn a second copy of the host's world. System bounties are posted by
each game separately, so the guest sees bounties the host does not and is paid by its own game for
kills in them.

**Story missions are the host's.** The Galatia Academy chain and the missions it unlocks run on one
engine only, the host's, and their rewards go to the host. The mod enforces this on the guest: the
bar offer that introduces the Academy, the provost meeting at the station, the data core a survey
turns up, the relay message after the first year and the Hamatsu recovery are all removed on the
guest's game, because the story flags and the places those missions create live in each game's own
memory and are not copied across. The guest can still dock at the Academy and ask about it. One-of-a-kind
finds in the shared world are a different matter: a derelict, a ruin, a cryosleeper or a one-time bar offer goes to whoever gets there first,
and the other player sees it taken.

## Trading with each other

There is no trade screen between the two of you. Cargo moves the vanilla way: jettison it, and the pod
appears on your partner's map for them to pick up.

**Storage is one locker, at every market that has one.** Whichever of you pays the 5000 credits opens
that market's storage for both, and from then on what one of you parks the other can take out. A ship
comes back the way it went in: refit, weapon groups, hull damage, modules. Two things follow from
sharing it. Each game bills its own monthly storage fee against the same contents, so one locker
costs two fees. And it is behind the same one-player-at-a-time lockout as the rest of the dock, so
you take turns.

**The black market and the military submarket are shared as well.** You buy from the same shelf your
partner does, refreshed from the host's game each time either of you docks, so what they bought is
gone when you get there. What a trade *causes* stays local: your smuggling suspicion, your odds of
being scanned, blueprints learned from a sale and the price impact of a big trade are each game's
own, so the two of you can read different suspicion strings at the same market.

**A commission is the host's, and the guest gets the door it opens.** While the host holds one, the
guest can buy the commission-gated items at that faction's military submarket. The salary and the
commission's own bounties are paid to the host. The guest cannot sign or resign a commission of its
own; those dialog options are removed on the guest's game.

**Credits move from the Coop Options page.** Open the intel screen, Coop Options, the Send credits
row: step the amount, press Send, confirm. The money leaves your account when you press it and lands
once on the other side, including when it was sent during a dropped link, in which case it arrives
with the rest of the queued traffic on the resume. If it can never be delivered, because the session
ended first or the game is closing, it is put back in your account and the message feed says so.

One catch with pods. A pod is owned by whoever dropped it, and only that game runs its expiry timer.
If the pod expires while neither of you is in that system, the partner's copy is never told and stays
on their map, and it can still be looted. The other way round was worse: letting both games run the
timer deleted live pods out from under the player who had just dropped them.

## Talking to fleets

When a hostile fleet catches the guest, the guest gets the normal vanilla encounter on their own PC:
fight, try to disengage, spend the story point, or leave. Only a fight reaches the host, as the
reconciled result. A disengage does not, and does not need to: vanilla tells the pursuing fleet
nothing after a disengage either, and its pursuit patience decides whether it tries again. The one
difference is timing. Vanilla gives you about three seconds of grace after an encounter; the co-op
handoff waits fifteen before the same fleet can catch the guest again. A fleet the guest beat in
battle leaves the guest alone for about a day, as in vanilla.

A customs scan or inspection against the guest is the one dialog the host does drive. The host
notices the patrol's intent, tells the guest's game, and the guest resolves the scan against their
own cargo; the fine and the standing change are reported back.

Pirate and Pather fleets that spawn around the guest, in a system the host is not in, are placed at
the same distance vanilla keeps from the host. Vanilla only knows about one player; the mod applies
the same spacing to the guest.

## Divergences you will actually run into

**Colonies.** Both of you govern the same colonies under one faction. Only one of you can have the
colony screen open at a time: the second player to open it is bounced back to the intel tab with a
banner naming `colony management`, the same way two players cannot dock at one market at once. Walk
back in once the other player leaves.

Two things still do not line up. A construction bar can read differently on the two screens until the
industry finishes, at which point the lagging side is forced to completion; the drift never grows
past the gap between the two starts. And a colony's commodity shortage markers can disagree, because
each engine solves supply for the whole sector on its own schedule. The host's reading is the one to
trust.

The guest has no hostile-activity meter. Its own copy predicted nothing real, because the fleets it
would have spawned are suppressed, so the mod ends it. The co-op expedition warning, the one with a
countdown, is the entry that shows the fleets actually coming, on both screens.

**Bar offers.** The same offers appear for both of you, from the same people, for the same
commodities, at different tonnage and different pay. The wire carries the offer's seed and each game
sizes the numbers against the local fleet and the local market. Both of you completing the same job is
the point; both being quoted the same fee is not.

Two smaller effects on the same screen. An offer the host has already taken keeps showing on the
guest until the next pool update drops it, so an accept can come back "already taken". And a pirate or
Pather base rumour is generated locally on each side, which can shuffle the whole list into a
different order, so the two bars occasionally show different picks out of the same pool.

**Hidden bases share their shop, not their name.** A pirate or Luddic Path base is built by whichever
game found it, and each game names it and places its orbit itself, so the same base can read
differently on your two maps. The shop is shared: dock in turn and the second of you sees the first's
leftovers. A base carries an open market and a black market and nothing else, so there is no storage
locker to share there.

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
the second or two before the host's snapshot lands.

**Two abilities behave slightly differently for the guest.** An interdiction pulse the guest fires
charges its reputation cost when the charge-up starts rather than when the pulse lands, and only for
the fleets the guest's own game could see at the time. And a distress call the guest makes brings the
responding fleet in near the host, though it does route to the right system.

## Clocks

The two campaign clocks are not identical to the second. Starsector advances its calendar in whole
seconds per frame, so two machines drift apart structurally even at the same speed. The guest corrects
itself continuously and the status line shows the gap once it reaches an hour of game time.

Running both games on one PC has its own version of this: Starsector caps its per-frame step, so a
minimised or background window runs its clock slow and looks from the other side like the other client
running fast. Keep both windows restored and visible.

## Networking

Traffic is plaintext. Message payloads are readable by anyone on the path between you. The lobby
password stops strangers from joining an open port; it is a gate, not encryption. A VPN (see
`CONNECT.md`, tier 0) is the only thing that encrypts the session.

One guest. The wire format can carry more and the setting exists, but any value other than 1 is
clamped back to 1 with a warning, because the gameplay side of a third player is not built.

The 60 second reconnect wait ends for whoever clears the lobby password first. The host runs the
password gate on an incoming hello and nothing else: it does not check that the client knocking is
the partner who dropped. With no `coop.password` set that means anyone who can reach the port while
the countdown is on screen.

If the host's game crashes rather than exits, the port mapping it asked the router for outlives it.
Most routers expire it within the hour. At the next launch the mod hits the conflict, asks the router
who owns that external port, and deletes the entry only when the router names this machine; a port
held by another device is reported and left alone, and you pick a different `coop.hostPort`. A router
that refuses timed leases keeps the port open until you next start the game.

## The launcher

**Windows only in this release.** `Coop Launcher.cmd` is a Windows batch file that starts
`jre\bin\javaw.exe`, a Windows executable at a Windows path. A Mac or Linux install starts the game
from a shell script that carries the classpath inline, and none of that was tested here. Those
players set the same values by hand: everything the launcher does to your configuration is writing
`saves\common\coop_options.json.data`, and `INSTALL.md` section 9 is the same settings without it.

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
