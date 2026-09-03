# Changelog

## 0.1.0, first release

Two-player co-operative Starsector. One shared sector, one campaign clock, two fleets.

**Playing together**

- One sector generated from a shared seed. Both players start in it and stay in it.
- One campaign clock. Time passes for both of you or neither; a menu, a dialog or a battle on either
  side pauses the other, and the status line names who is holding it.
- Fast-forward is shared. Both clocks speed up together.
- A status line in the corner of the campaign screen: role, session state, who is pausing, round
  trip, packet loss and which transport your fleet positions are travelling over.
- A "Coop Session" page in the intel screen with link history, what your partner measures, and a log
  of everything that has gone wrong this session.
- A "Coop Stats" page tallying the campaign: battles, travel, trade, colonies and every ship lost,
  per player and as a team.
- A "Coop Options" page. The host sets the session's rules there and they travel with the campaign's
  save and sync to the guest; each player sets their own preferences separately.
- Every setting also lives in a file: `saves\common\coop_options.json.data` for yours, which the
  options page writes for you, and a shipped `data\config\coop_options.json` listing every key, its
  default and when a change takes effect. The `.data` suffix is the engine's; the contents are
  plain JSON.
- The host can let the world keep running while the guest reads its map, cargo or refit screen.
  Interaction dialogs, the menu and combat still pause both games.

**The world**

- Your partner's fleet moves on your map, with its real ships, cargo, sensor profile and abilities.
- Patrols, traders, pirates and bounty fleets are simulated once and mirrored, so you both see the
  same fleets doing the same things, including the ones chasing your partner.
- Markets, shop stock, officer pools and bar offers are shared. Docking refreshes what you see.
- Salvage, surveys and exploration are shared: one of you loots the wreck, both of you see it gone.
- Faction reputation is shared. Your partner's smuggling shows up on your standing.

**Colonies**

- One player faction, two governors. Both of you can found colonies, build industries, run the
  construction queue and use colony storage, on the same colonies.
- Monthly income is split evenly.
- Raids and bombardments work in both directions, and an incoming expedition raises a warning with a
  countdown on both screens.

**Combat**

- Each player pilots their own battles. The other is held paused and gets a banner when the fight
  starts and ends.
- Battle results are reconciled into the shared world: losses, salvage, reputation, bounties.

**Connecting**

- The host is the only side that has to be reachable. The game asks the router to open the port over
  UPnP or NAT-PMP at startup and tells you in the log what happened.
- Optional lobby password. It stops strangers from joining an open port; it does not encrypt the
  session.
- A dropped link is held for 60 seconds with a countdown on both screens. The session picks up where
  it left off when the connection returns, including when the other player had to restart the game
  and reload their co-op save.
- A lobby in front of every session. Both games load with the world paused, the guest's five join
  steps are on screen as they pass, and the clock starts when the host presses Start and a three
  second countdown runs out.
- A refused or ended session gets a dialog naming the cause and what to do about it, with a code
  (`COOP-SEED`, `COOP-MODS`, `COOP-SESSION`) that also lands in both logs.
- A "connection doctor" block in the log answers "why can't we connect" in one screen, on both sides.

**Saving**

- Co-ordinated saves on both machines. The guest rejoins by loading its co-op save.

**Fixed before release**

Found in the two-player test run on 2026-09-03 and fixed the same day.

- A player who had to restart the game was refused for the whole 60 second wait. Reloading the co-op
  save now ends the wait the moment it connects, and "wait longer" no longer pushes it further away.
- The lobby kept the row of the player who had just come back, so it listed three players and
  offered to start without the one sitting there ready.
- The reconnect countdown counted up from the drop instead of down to the deadline.
- A guest whose sector did not match was reported as a lost connection, and the host waited 60
  seconds for a guest it had already refused. Both sides now say `COOP-SEED` straight away.
- The lobby, connecting and reconnect screens rebuilt themselves once a second, which read on screen
  as a flashing panel.
- A seed longer than the game can store crashed it on the New Game screen. An unusable seed is now
  ignored with a log line, and the launch scripts refuse it before writing it into `vmparams`.
- Two of the refusal dialogs handed the host the instructions meant for the player at the other end.

**Known limits.** Both players need identical Starsector versions and identical mod lists, and both
have to edit one line in `vmparams` by hand. Traffic is plaintext. One guest. Both players
piloting in the same battle is not in this release. `docs/player/LIMITATIONS.md` has the full list of
where two co-op campaigns differ from one solo campaign.
