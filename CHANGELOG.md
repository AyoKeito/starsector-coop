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

**Starting the game**

- `Coop Launcher.cmd` in the mod folder: a dark-themed window with the settings in it, so nobody has
  to learn a `-D` property to play. It runs on the JRE that ships with the game, writes
  `saves\common\coop_options.json.data`, and starts the game; the vanilla launcher still comes up and
  you still press Play there. Windows only in this release. Its Advanced card, folded shut by default,
  exposes the developer flags (diagnostics, datagram wiretap, frame profiler, the full-fidelity
  guest-system and shared fast-forward and clock-reconciler switches, and the guest's one-shot
  start-over-in-the-host's-campaign override) as checkboxes instead of `-D` properties.
- An invite line, `coop://host:port/?seed=...&pw=...&size=...&age=...`. The host presses Copy invite,
  the guest presses Paste invite and has the address, the port, the password, the seed, the sector
  size and the star age filled in at once. The password is generated for the host rather than left
  empty.
- Check my connection on the host runs the port mapping and the connection doctor before the game is
  loaded and puts the result on screen. It then holds the port so the guest's Test connection can
  measure TCP, UDP and round trip against it.
- An install check that names what is wrong: the missing classpath entry, an unticked mod, a mixed-up
  pair of jars, an unreadable settings file, and a leftover `-Dcoop.*` in `vmparams` that would
  silently outrank everything you set in the launcher. It reports and never edits `vmparams` or
  `enabled_mods.json`.
- The mod checks the Starsector it is running on against the one it was built for, and refuses to
  start a session on any other version with the code `COOP-GAME`. Parts of the mod are compiled
  against the game's own classes, so this is the failure that produced the strangest bug reports.
  The launcher shows it as a `Game version` row, read out of `starsector.log`, before you press
  LAUNCH. Testers can turn the refusal off with **Allow game version mismatch** under Advanced.
- Save a bug report: one zip on your Desktop with both game logs, the launcher log, your settings,
  your `vmparams`, your mod list, your newest save and a summary carrying the last `[COOP-DOCTOR]`
  line. The password is blanked out of everything in it. Both players press it; `REPORTING.md` says
  what to do with the two files.
- An update check at start, one request to GitHub's releases API, shown as a row in the install
  check. LazyWizard's Version Checker is supported too, through a `coop.version` file. Both matter
  more here than in a single-player mod: the handshake compares the commit baked into the jar, so a
  stale download on one side refuses the session.
- The release is one packaged artifact. `scripts\package-release.ps1` builds the zip and refuses to
  make one from an uncommitted tree, mismatched version strings, or jars built at another commit, so
  the download both players install is the same download.

**Saving**

- Co-ordinated saves on both machines. The guest rejoins by loading its co-op save.

**Fixed before release**

Found in the two-player test run on 2026-09-03 and in the launcher work that followed, and fixed the
same day.

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
- The seed, the sector size and the star age could only be given as `-D` properties, which left the
  launcher no way to set them. All three now read from `saves\common\coop_options.json.data` as well.
- The guest's New Game banner said the seed and world settings come from the host. They do not: the
  guest generates its own sector and the seed lock verifies it afterwards. It now says the seed comes
  from the invite.
- The mod's description in Starsector's own mod list was reworded.

**Fixed in the pre-release audit**

A full read of the code, the scripts and the documents on 2026-09-03 and 2026-09-04, before the
release build. 107 confirmed defects, fixed across twelve passes. The ones a player would have met:

- The launcher replayed the whole game log from the top whenever the game wrote a byte it could not
  decode, which spun a core and eventually truncated the live log during a rollover. It also kept
  holding the co-op port after a connection check that finished late, so the game it then started
  could not bind it.
- A colony upgrade the guest cancelled came back on the next sync, and an industry the mirror had
  already finished could be reported as a downgrade. Colony state is now compared as content, per
  industry, and a failure in one industry no longer starves the ones behind it.
- Two players could edit the same colony from the command tab at once and one player's edit was
  dropped without a refund. The colony editor is now claimed by whoever opens it.
- A second hostile act in the same raid was dropped, so part of a raid's outcome never reached the
  other player.
- A battle result built from a roster the engine would not fully read could report a live fleet as
  destroyed. An unreadable roster is now omitted from the result instead.
- Ship repair state was paired to the wrong ships whenever a fleet was merely reordered.
- A guest that pressed "Cancel countdown" during a start-anyway countdown was ignored; the session
  started anyway.
- Mirror fleets stopped for a customs stop stayed eligible to be pulled into someone else's battle
  for the rest of their lives.
- A port already mapped by another device on the network was evicted on every renewal. The mod now
  asks who owns it and says to pick another port.
- A guest that lost its save could be told the campaign was already in flight because a failed seed
  lock had written the campaign id anyway.
- A build from a modified checkout was stamped with the last commit's hash, so two players could
  shake hands on different code. It reports `<hash>-dirty` now.

Development tooling gained the agent bridge verbs the smoke checks were missing: `cargo` (supplies,
fuel, crew, capacity, overload) and `addship`, and the bridge accepts four clients at once instead of
one. None of it runs unless the bridge is switched on.

**Known limits.** Both players need identical Starsector versions and identical mod lists, and both
have to edit one line in `vmparams` by hand. Traffic is plaintext. One guest. Both players
piloting in the same battle is not in this release. `docs/player/LIMITATIONS.md` has the full list of
where two co-op campaigns differ from one solo campaign.
