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
- A dropped link is held for 60 seconds with a countdown on both screens, and the session picks up
  where it left off if the connection comes back.
- A "connection doctor" block in the log answers "why can't we connect" in one screen, on both sides.

**Saving**

- Co-ordinated saves on both machines. The guest rejoins by loading its co-op save.

**Known limits.** Both players need identical Starsector versions and identical mod lists, and both
have to edit one line in `vmparams` by hand. Traffic is plaintext. One guest. Both players
piloting in the same battle is not in this release. `docs/player/LIMITATIONS.md` has the full list of
where two co-op campaigns differ from one solo campaign.
