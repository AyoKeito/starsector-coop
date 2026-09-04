# Connecting to each other

Only the host's machine has to be reachable. The guest opens the connection outward and sends the
first UDP packet; the host answers on the path that packet came from. Nothing ever dials the guest.
So when two people cannot connect, the problem is on the host's side, every time.

## Start here

Four ways to make the host reachable, numbered by effort. The mod tries number 3 by itself at
startup and writes what happened to the log.

| Tier | What it is | Works for |
|---|---|---|
| 3 | The game asks the router to open the port | Routers with UPnP or NAT-PMP switched on |
| 2 | You open the port on the router yourself | Anyone with router access and a public IP |
| 1 | IPv6, nothing to open on the router | Anyone whose ISP hands out IPv6 |
| 0 | A VPN that puts both PCs on one private network | Everyone, carrier NAT included |

Tier 0 cannot fail. If you would rather not spend an evening on router pages, start there.

Before you load anything, find out whether the two of you can reach each other: the host presses
**Check my connection** in `Coop Launcher.cmd` and stays open, then the guest presses **Test
connection** with the invite pasted in. Both are described in the next section.

---

## Before the first session: Check my connection and Test connection

Both buttons are in `Coop Launcher.cmd`, and the order matters: the host goes first and stays open.

**The host presses Check my connection.** It runs the same UPnP and NAT-PMP request the game runs at
startup, then the same connection doctor, and shows the result as chips (mapped or not, the external
address, a carrier-grade NAT warning when it applies), with a sentence under them saying what to do
next, rather than sending you to a log file to go find. The log drawer opens on its own with the full
block; read it the way "Reading the log" at the end of this page reads it: the tier line says which
of the four routes you are on, and the `next step` line says what to do when the answer is bad. It
takes a few seconds, and the router mapping is released afterwards so the game can make its own at
launch. Once it is holding the port for the guest's test a `listening on <port>` chip appears, and
the launcher keeps the port open, and says so, until you press LAUNCH.

**The guest presses Test connection** while that is up, with the invite already pasted in. Four
chips come back, green when good, red when failed, grey when not measured:

| Chip | Reading it |
|---|---|
| TCP | The port is open from where you are sitting. A failure here is the host's port forward, the host's firewall, or a wrong address, and everything below it is moot. |
| launcher \<version\> | What accepted the connection was the host's co-op launcher, and it prints the mod version it is running. |
| UDP | Fleet movement will go over UDP. Without it the session still runs over TCP, which costs latency; a router that forwards TCP but not UDP is the usual cause. |
| \<n\> ms | Milliseconds, measured over that TCP connection. |

TCP green and no launcher answer means something else is listening on that port. Nearly always that
is the host's game already running instead of the host's launcher, in which case there is nothing to
test: press LAUNCH and join.

The test connects once and does not retry. The game counts connection attempts per address, five to
a window, and a prober that hammered the port would spend the guest's budget before the real session
started.

---

## Tier 0: a VPN pseudo-LAN

A mesh VPN gives both PCs an extra IP address on a private network that spans the Internet. The mod
opens ordinary sockets, so that address is just another network card to it. No game settings change.

It is also the only encryption in the picture. Co-op traffic is plaintext JSON; a VPN wraps the whole
session in its own encryption.

Each tier below is written as `-D` properties on the `vmparams` line. In the launcher they are the
Port field on the host, the Host address and Port fields on the guest, and Port mapping in the
Advanced card, hidden behind the footer's Advanced button until you open it.

**Tailscale.** Both install from <https://tailscale.com/download> and sign in, either on one account
or with the host inviting the guest to their tailnet. Open the Tailscale menu on the host and copy
its `100.x.y.z` address. Host launches with `-Dcoop.hostPort=7777`, guest with
`-Dcoop.connectHost=100.x.y.z -Dcoop.connectPort=7777`. If Tailscale reports "relayed" rather than
"direct" it still works, at 20 to 60 ms more round trip.

**ZeroTier.** Install from <https://www.zerotier.com/download/>. The host creates a network at
<https://my.zerotier.com> and both join it with `zerotier-cli join <network-id>`. The host then has to
authorise both members in the web console; until that box is ticked they cannot talk. The console
shows each member's managed IP, usually `10.147.x.x`.

**Radmin VPN.** Install from <https://www.radmin-vpn.com/>, host clicks Network then Create network,
guest clicks Network then Join network with the same name and password. Both PCs appear in each
other's window with a `26.x.x.x` address. No account needed, fastest to set up, and the only closed
source option of the three.

## Tier 1: IPv6

If your ISP gives you IPv6 your PC already has a globally routable address with no NAT in front of
it. Only Windows Firewall is in the way.

Check that you have it:

```powershell
Get-NetConnectionProfile | Select-Object Name, IPv6Connectivity
```

`Internet` means yes. `NoTraffic` or `LocalNetwork` means skip this tier.

Find the address to share:

```powershell
Get-NetIPAddress -AddressFamily IPv6 -SuffixOrigin Random,Dhcp | Where-Object { $_.IPAddress -notlike 'fe80*' -and $_.IPAddress -notlike 'fd*' } | Select-Object IPAddress
```

Open the port, once, from an Administrator PowerShell:

```powershell
New-NetFirewallRule -DisplayName "Starsector coop TCP" -Direction Inbound -Protocol TCP -LocalPort 7777 -Action Allow
New-NetFirewallRule -DisplayName "Starsector coop UDP" -Direction Inbound -Protocol UDP -LocalPort 7777 -Action Allow
```

The guest uses the address as-is: `-Dcoop.connectHost=2001:db8:...:6789 -Dcoop.connectPort=7777`.
The bracketed form `[2001:db8::1]` also works. What does not work is address and port in one string;
the port always goes in its own property.

Many ISPs rotate the IPv6 prefix when the router reboots, so re-run the address command each session
rather than reusing last week's.

## Tier 2: forwarding the port yourself

Find the host PC's LAN address:

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' } | Select-Object InterfaceAlias, IPAddress
```

You want the `192.168.x.x` or `10.x.x.x` one.

On the router, find the page called Port Forwarding, Virtual Server or NAT, and add one rule:
external port `7777`, internal port `7777`, internal address the one you just found, protocol
**both TCP and UDP**. If the router only accepts one protocol per rule, add two rules.

That "both" is the single most common mistake in this whole document. Fleet positions travel over
UDP and everything else over TCP. Forward only TCP and the session connects, the clock syncs, and
then the fleets stand still.

Get the address to give the guest from <https://ifconfig.me> on the host PC. Check the forward from
outside by pasting that address and port into
<https://www.yougetsignal.com/tools/open-ports/> while the host game is running. That checker only
tests TCP; the UDP half is confirmed by the guest's `UDP path up` line.

Add the two firewall rules from tier 1 as well. A forwarded port still dies at Windows Firewall.

## Tier 3: letting the game open the port

At startup the host asks the router for a mapping over UPnP, then NAT-PMP, and gives up after about
five seconds. It never blocks the session; a failure is a log line. The lease runs an hour and is
renewed every 30 minutes.

Success looks like this in `starsector.log`:

```text
Coop port mapper: mapped 203.0.113.7:7777 (TCP+UDP) via UPNP on gateway 192.168.1.1 "ASUS RT-AX88U", lease 3600 s
```

That address is what you send the guest. Failure looks like this:

```text
Coop port mapper gave up: NAT-PMP gateway 192.168.1.1 did not answer -- host the session on a manual port forward, IPv6, or a VPN (see docs/CONNECTIVITY.md)
```

The usual cause is UPnP switched off in the router's admin page, under Advanced, NAT, or WAN,
labelled UPnP or "NAT traversal". On a MikroTik running RouterOS, check
`/ip upnp print` says `enabled: yes` and that `/ip upnp interfaces print` names the interface that
actually holds the LAN address. On a bridge-VLAN setup the bridge carries no address, so pointing
UPnP at the bridge produces a responder nothing ever reaches. RouterOS does not implement NAT-PMP at
all, so its failure line there is a red herring.

If a crashed session left a mapping behind, the next launch hits `UPnPError 718` on that port. The
mod then asks the router who owns the entry and deletes it only when the router names this machine;
a port held by another device on the LAN is reported, not evicted, and you pick a different
`coop.hostPort`.

`-Dcoop.portMapping=off` turns the attempt off. The only other accepted value is `auto`.

### CGNAT

Some ISPs put a second layer of NAT between your router and the Internet. Your router believes its
public address is something like `100.71.4.9`, but that address belongs to a box in the ISP's
building and is shared with a few hundred other customers. You cannot forward a port on a router you
do not own.

The mod detects it and says so:

```text
CGNAT/double NAT: direct IPv4 impossible; use IPv6 or a VPN
```

Tiers 2 and 3 are both dead in that case. Use IPv6 if you have it and a VPN if you do not. Some ISPs
will move you off CGNAT for free if you ask.

---

## The lobby

Loading the campaign does not start play. Both games open a dialog, the world is held paused behind
it, and the clock starts only when the host presses Start and a three second countdown runs out.

### What the host sees

One line per player, host first then join order, your own line marked with `>`:

```text
> Ayo - Ready
  Keito - Syncing 3/5
```

The word after the dash is the state. There are five:

| Row reads | Means |
|---|---|
| `Connecting...` | The socket is up and nothing past that has happened. |
| `Syncing 2/5` | The join is working through its five steps. |
| `Not ready` | The guest holds your world and has not pressed Ready. |
| `Ready` | Pressed Ready. |
| `Reconnecting 0:42` | That player dropped and the grace window is running; the number is the time left in it. The row and its ready value are kept until the wait ends, either way. |

A refused join replaces the state word with the refusal instead.

Under the roster are three lines built from the same measurements the intel page uses,
`Connection: ...`, `Endpoint: ...` and `Link: 42 ms over UDP`, then `Waiting 1:24.`, which ticks.

The Start option names what it is waiting for. With nobody connected it reads
`Waiting for a player to connect...` and cannot be selected. With a guest who has not readied it
names them: `Waiting for Keito...`. It becomes `Start session`, and becomes selectable, only when
every guest is ready.

Pressing it arms a 3 second countdown: the text panel adds `Starting in 3...` and the option is
replaced by `Cancel countdown`, which either player may press.

Two minutes in with the guest still not ready, the dialog adds a line about the wait and a second
option appears under Start: `Start anyway (guest not ready)`. It does not fire on the first press.
The options become `Yes, start anyway` and `Back`, and confirming starts the session with a guest who
will mirror an already running world. `Cancel countdown` is on the guest's screen during that
countdown too, and stops it.

ESC does nothing here. The dialog has no escape option at all, deliberately: Start, the override and
the game menu are the ways out.

### What the guest sees

First a connecting screen that lists all five steps at once, `>` on the current one and `done:` on
the ones behind it:

```text
Joining the co-op session.
  done: Connecting (1/5)
  done: Checking versions (2/5)
> Locking the sector (3/5)
  Syncing the world (4/5)
  Ready (5/5)
Waiting 0:12.
```

Step 4 lands when the host's campaign clock arrives. That message is sent five times a second and is
sent unconditionally, so it cannot fail to turn up on a link that works at all, which is why the
ready gate hangs off it rather than off the world data. It is also the step that hands the guest over
to the lobby.

`Cancel` is live on this screen the whole time. It stops the retry loop and leaves your campaign
loaded and paused; the host sees the ordinary disconnect it would have seen anyway. The feed line is
`Co-op: joining cancelled. Your campaign stays paused.`

Three failures are named on the screen rather than left spinning:

- `This install and the host's do not match, so the session cannot start.` then
  `Match the host's game version and mod list, then reconnect.`
- `The host turned this connection down.` then `The host's own words are below. Nothing here retries
  on its own.`, then the host's reason text.
- `The host's port answered but the session never started.` after 30 seconds with no answer from the
  lobby, then `Nothing arrived in 30 seconds. Check that the host is still on the lobby screen, then
  try again.`

Seed and install mismatches do not stop here. They take the screen over with a dialog of their own,
covered under "The three refusal dialogs".

Past step 4 the guest gets the same roster the host is reading, with `Ready` where the host has
Start. Taking it back is `Not ready`, allowed at any point before the session starts; taking it back
also cancels a running countdown.

### The pause

From the moment a client takes a co-op role, the mod re-asserts the pause every frame until two
things are true at once: the session is live, and the lobby has been released. Nothing you do in the
lobby moves the clock. The one exception is a guest with an interaction dialog open, where vanilla
already owns the clock and forcing a pause underneath it caused the frozen-dialog bug.

---

## What the game shows you

### The status line

One line in a corner of the campaign screen (top right by default; `-Dcoop.hudCorner=TL|BR|BL`
moves it, `-Dcoop.hud.disable=true` removes it). Segments are separated by a dot and only appear
when they mean something:

```text
HOST · session active · paused by guest's screen · 42 ms · loss 0% · udp
GUEST · session active · paused by host · guest 2h behind · 118 ms · loss 3% · tcp fallback · 5 Hz
```

- **Badge and status.** `HOST` or `GUEST`, then one of: no session, waiting for guest, connecting,
  handshaking, in lobby, session active, reconnecting, guest disconnected holding, or
  `rejected: <reason>`. `in lobby` is the window between the handshake finishing and somebody
  pressing Start; a refusal fills the reason in as `rejected: COOP-SEED, seed mismatch`.
- **paused by ...** names whoever is holding the shared pause, worded from your side, so you read as
  "you" and the other player as "host" or "guest". "your screen" or "guest's screen" means a menu or
  dialog is open. "combat" means somebody is in a battle. "reconnect" means the grace window is
  holding the world.
- **guest Nh behind / ahead** appears on the guest when the two campaign clocks differ by an hour or
  more of game time. It should shrink on its own.
- **Round trip and loss** are the live link measurements.
- **udp** or **tcp fallback** is the transport carrying fleet positions.
- **A rate in Hz** appears only when the mod has moved off its usual 10 Hz update rate, which it does
  on a link with sustained loss.

### The "Coop Session" intel page

Under the Coop tag in the intel screen, present for the whole campaign. It holds what the status line
has no room for:

- **Role, session state and partner name.**
- **Link.** Round trip and 95th percentile, datagram loss, which transport the state stream is on,
  and how long TCP has been quiet.
- **Peer sees.** The same numbers as your partner measures them, with how long ago they were
  reported. Two different pictures of one link is the fastest way to tell a one-way problem from a
  shared one.
- **Reachability.** The port-mapping tier, the external endpoint and the CGNAT verdict: the same
  question the doctor's host block answers, without opening the log.
- **Recent events.** Fallbacks, recoveries, disconnects and rejects with ages, or "Nothing has gone
  wrong yet."
- **History.** Round trip and loss as sparklines over the last samples, about five seconds apart,
  with min, median and max. This is the section that answers "has it been like this all evening".

### The "Coop Stats" intel page

The second entry under the same Coop tag. It opens on a team band (days elapsed, time flown together,
battles fought and won, days since the last hull lost), then record cards: Longest haul, Best deal,
Explorer, Veteran and Together, each hidden until it clears a floor so the page never congratulates
you on 12 su travelled. Under those, a table with one column per player plus a Team column, split
into Combat, Travel, Trade and Colonies, and a player who is away keeps their column with `(away)` on
the header rather than going blank. Your id is stored in your save, so restarting the game and
reloading it puts you back in your own column instead of opening a second, empty one beside it. Then
the ship-loss ledger, newest first, and a closing line per
stat saying how that stat is credited. Nothing on the page marks a winner. The host tallies it all
and broadcasts every 30 seconds, so the guest's copy can be that far behind. Two numbers need
reading carefully: "Time flown together" counts the seconds where both fleets are in the same
location, hyperspace included, and only while the shared clock is running; and the guest's "Best
single trade" is always 0 in this release, because a guest transaction crosses the wire with no price
attached. Guest trades still count towards "Markets traded with".

---

## Pause while a guest reads a screen

Out of the box the clock stops whenever either player opens one of the vanilla auto-pause screens,
which is what "paused by guest's screen" on the status line is reporting. The host can turn that off
for the guest's screens: intel screen, **Coop** tag, **Coop Options**, under **Session rules (host)**,
the row `Pause while a guest reads a screen`. Only the host has the button. The guest reads the same
row with `(host setting)` after it, and gets `Co-op: the host set Pause while a guest reads a screen
to off.` in its feed when it changes.

Off, the world keeps running while the guest sits in the map, fleet, character, refit, cargo or intel
tab: fleets move, fuel burns, the month ticks. Four things still stop time whatever this is set to:
an interaction dialog (a market is traded against the stock it had when it opened, so that pause is
correctness rather than comfort), the in-game menu, combat, and the pause key.

Turning it off asks first:

> Turn off the pause while a guest reads a screen?
>
> The world moves while your partner reads: their map, cargo and refit screens will no longer stop
> time for either of you. Interaction dialogs and combat still pause.
>
> The change takes effect the next time a screen opens or closes, never underneath one that is
> already open.

The last line is literal. Flip the setting while the guest has the map open and the row reads
`pending - applies next screen open/close` until they close it; the pause they are already holding is
not taken out from under them. Turning it back on waits the same way.

---

## When the link drops

Nothing is lost the moment a connection dies.

Detection takes about 15 seconds of silence, and not less. The network layer does not run during
combat or while the game is writing a save, so a shorter timer would kill sessions that were never
broken. A partner who is in a battle, who just announced a save, or whose process stalled is exempt
for as long as that lasts.

Both players get a countdown, the world is held paused on both sides, and each of you can choose to
end the session or wait another five minutes. If the guest gets back inside the window, the session
carries on: the whole world state is rebroadcast so both sides restart from one picture, and nothing
is rolled back.

You do not have to sit the window out. If the game on the dropped side went down rather than just
the connection, load the co-op save from that campaign and let it reconnect. Which save that is, the
launcher will tell you: paste the invite again (guest) or leave the host's **Campaign** drop-down on
the campaign you were playing, and the line under the card names the save down to the folder, the
character, the level and the time you saved it. `INSTALL.md` section 5 covers that line. A lobby
hello arriving while the countdown is running ends the wait there and then, the roster's reconnecting
row is dropped, and the pair goes through an ordinary lobby round on the new connection. The
countdown on the other screen is a deadline, not a delay you have to serve.

One consequence of that, worth knowing before you host on an open port: the host runs its lobby
password gate on the client that knocks and checks nothing else, so it cannot tell your returning
partner from any other client dialling in. Whoever gets through the password ends the wait, and on a
host with no `coop.password` set, that is anyone who can reach the port.

If the window expires, the session ends on both sides. That is not the end of the campaign. The
guest's ordinary connect retry reconnects through the normal lobby handshake and, as long as both
games are on the same campaign, becomes a new session with a full resync. You lose the convenience,
not the save.

---

## Troubleshooting

| What you see | Where the problem is | What to do |
|---|---|---|
| Guest sits on "connecting" forever | The host is not reachable at all | Read the host's `tier reached` line. If it is 0, pick tier 0, 1 or 2. |
| Guest connects, then drops within seconds | Wrong port, or a second router in the chain | Check the guest's `coop.connectPort` against the host's `coop.hostPort`. |
| Session works, fleets do not move | UDP is blocked, TCP is not | Add the UDP forwarding rule and the UDP firewall rule. |
| `CGNAT/double NAT` in the host log | The ISP, not your router | Tier 1 if you have IPv6, tier 0 otherwise. |
| "no UPnP gateway answered" | UPnP is off, or the router does not speak it | Turn UPnP on, or use tier 2. |
| "no UPnP gateway answered" on a router with VLANs | UPnP is pointed at the bridge, which holds no LAN address | Point it at the interface that holds the LAN address. |
| `UPnPError 718`, then `already mapped to <device>` | Another device on the LAN owns that external port | Pick a different `coop.hostPort`, for example 7778. A stale mapping of your own is deleted and retried without you. |
| `UPnPError 725` | The router refuses timed leases | Nothing to do; the mod retries with a permanent lease and deletes it on exit. |
| Works on LAN, fails over the Internet | Almost always Windows Firewall on the host | Add both firewall rules from tier 1. |
| Fine but choppy | Latency, not reachability | Check round trip on the intel page. Above about 250 ms, try a VPN with a closer relay. |
| One clock runs ahead, both games on one PC | Starsector caps its frame step, so a background window runs its clock slow | Keep both windows restored and visible. The drift pulls back together within a minute. |

---

## The three refusal dialogs

When a session is refused or ends with a reason, you get a dialog written for that reason, and a code
you can search the log for. There are three codes.

**`COOP-SEED` means the two of you are not in the same sector, or not in the same campaign.** The
sector version opens with "Your sector and the host's sector are not the same." and gives each side
the instruction that side can act on: the host is told to read its seed out, the guest is told to
start a new campaign and type that seed into the seed field on the New Game screen. Below the remedy
sit both seeds and the first 8 characters of each sector fingerprint, side by side, worded as "yours"
and "the host's", so you can read them to each other and confirm you are looking at the same
difference. The campaign version opens with "This save is not from the host's co-op campaign.",
because co-op stamps a campaign with an id the first time a session runs in it, and points you at the
co-op save from that campaign. `launch-guest.ps1 -AdoptCampaign` is named there as the way to take
the host's world instead, at the cost of this save's progress. Neither version offers a "join anyway".

**`COOP-MODS` means the two installs differ.** One line per differing mod, each with its own verdict
and its own remedy, and the remedy points at whichever side is actually behind rather than always at
you. Iron Mode on either side, a Starsector version difference and a co-op build difference each get
a line above the mod list. The list is capped, with "... and N more" pointing at the log. A mod that
matches on version but not on file contents is called out separately, in its own paragraph, because
that is the case people refuse to believe: a partial download does it.

**`COOP-SESSION` means the session itself could not be picked back up.** Six causes, each with its
own body: the reconnect window closed, the partner is holding a different session, that place belongs
to a different player id, the partner is mid-grace for somebody else, a player pressed the end
option, or something that did not classify. The grace window is always printed as a number of
seconds. An unrecognised reason lands here too and prints the raw text verbatim, so a session never
ends in silence. Only one cause is marked retryable (the partner being mid-grace for someone else)
and even that ships without a "Try again" button, on purpose: the guest's connect loop was never
stopped for it, it is still dialling every few seconds, and the dialog closes itself the moment a
fresh handshake goes through.

After `COOP-SEED` and `COOP-MODS` the guest stops reconnecting. Both are deterministic, so retrying
earns the identical refusal every 5 seconds and buries the dialog under a stack of new ones; fix the
save or the mod list and relaunch. `COOP-SESSION` leaves the retry loop alone, because a fresh lobby
round is the documented way back in after a grace expiry. The host is untouched by all three: it
rewinds to waiting and keeps its lobby open for a corrected guest.

Every one of these dialogs ends with the same line, naming the file and the exact string to search:

```text
Support code COOP-SEED. The full detail is in starsector.log - search it for: [COOP-DOCTOR] code=COOP-SEED
```

The options are "Open support thread", which opens the issue tracker behind the game and leaves the
dialog standing, and "Close". ESC does not dismiss them. If you close one before reading it, the code
is still in the campaign feed (`Co-op: seed mismatch (COOP-SEED) - see the dialog`), on the intel
page's event log, and on the status line (`rejected: COOP-SEED, seed mismatch`).

---

## Reading the log

The file is `<Starsector>\starsector-core\starsector.log`. Everything below is searchable text.

### `Coop connection doctor:`

Written by both sides. The host's block enumerates the machine's addresses, what the port mapper
managed, whether the ISP is running CGNAT, which tier that leaves you on, and one next step:

```text
Coop connection doctor:
  role              host, listening on port 7777 (TCP+UDP)
  password          none - anyone who reaches this port may join
  peer capacity     1 guest (v1; extra connections are rejected)
  local IPv4        192.168.1.5
  global IPv6       none (no IPv6, or only link-local/private addresses)
  port mapping      UPnP IGD via 192.168.1.1 "ASUS RT-AX88U" - external 203.0.113.7:7777
  CGNAT             no - 203.0.113.7 is a public address
  tier reached      3 - automatic port mapping (UPnP IGD)
  share with guest  203.0.113.7:7777
  next step         none - give the guest 203.0.113.7:7777 and start the session.
```

`tier reached` is the line that matters, with one honest exception: **tier 0 cannot be detected**. A
Tailscale address is a private address and the mod cannot tell whether your partner is on the same
tailnet, so a working VPN session reports `0/unknown`, the same as a broken direct one. If you set up
a VPN deliberately, ignore that line.

The guest's block is shorter and answers a different question:

```text
Coop connection doctor:
  role              guest, connecting to 203.0.113.7:7777
  TCP               up
  UDP path          blocked - fleet state falls back to TCP; movement will be less smooth
  RTT               118 ms, p95 140 ms, loss 0%
  UDP send target   none (validations 0, probes sent 12, echoes 0)
  dropped inbound   token mismatch 0, foreign source 0, malformed 0, no token 0
  keepalives        sent 14, received 14; ICMP transients 0
  abuse counters    connection attempts 1, throttled 0, invalid frames 0, dropped for garbage 0
  next step         Something between the two machines drops UDP. The session works over TCP; if
                    mirrors look choppy, both sides should try a VPN pseudo-LAN (tier 0).
```

`TCP up` with `UDP path blocked` is the signature of a port forward covering only TCP. The
`UDP send target` line separates the two ways that happens: `none` with probes sent and no echoes
means the path never came up at all; a real address there means it came up and then went quiet.

A non-zero `invalid frames` count is the one line that tells you something on that port answered and
it was not a co-op host.

### `Coop UDP return address validated <address>`

The host writes this when it has confirmed where to send datagrams. Until it appears, fleet
positions are travelling over TCP.

### `Coop state stream switching to TCP fallback` / `Coop state stream returning to UDP`

Written the moment the transport changes, with the reason and the silence timers in brackets. The
first also puts a message in the campaign feed: `Co-op: UDP blocked on this connection - partner
updates now travel over TCP.` Nothing is lost when this happens; movement gets less smooth.

### Password rejects

On the guest, three words plus what to do about them:

```text
Coop lobby rejected: the host's lobby password did not match. Not retrying; relaunch this client with -Dcoop.password=<the host's password>.
```

The guest stops retrying after that, because the password is read once at startup and no amount of
retrying can change it. Fix the property and relaunch. A guest with no password set at all against a
host that has one is turned down the same way rather than left knocking.

On the host, one line per cooldown rather than one per attempt:

```text
Coop TCP refusing connections from 203.0.113.9 for 30000 ms after 3 failed lobby password proofs
Coop TCP closing connections from 203.0.113.9 with no reply for 28000 ms after 3 failed lobby password proofs
```

Three wrong guesses buy a 30 second silent refusal, and each further failure doubles it up to ten
minutes. Practical consequence for the guest: after fixing the password, wait out the cooldown. A
corrected client looks broken while the host is still refusing the address, and relaunching during
the cooldown extends it.

Other rejects read as plain sentences. `Lobby already has a guest` and `session in reconnect grace`
are retried automatically every 5 seconds. The seed and install mismatches from `INSTALL.md`
section 8 are not: they stop the guest's retry loop and open a dialog of their own, covered under
"The three refusal dialogs".
