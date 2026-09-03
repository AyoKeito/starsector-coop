# Connecting to each other

Only the host's machine has to be reachable. The guest opens the connection outward and sends the
first UDP packet; the host answers on the path that packet came from. Nothing ever dials the guest.
So when two people cannot connect, the problem is on the host's side, every time.

Four ways to make the host reachable, numbered by effort. The mod tries number 3 by itself at
startup and writes what happened to the log.

| Tier | What it is | Works for |
|---|---|---|
| 3 | The game asks the router to open the port | Routers with UPnP or NAT-PMP switched on |
| 2 | You open the port on the router yourself | Anyone with router access and a public IP |
| 1 | IPv6, nothing to open on the router | Anyone whose ISP hands out IPv6 |
| 0 | A VPN that puts both PCs on one private network | Everyone, carrier NAT included |

Tier 0 cannot fail. If you would rather not spend an evening on router pages, start there.

---

## Tier 0: a VPN pseudo-LAN

A mesh VPN gives both PCs an extra IP address on a private network that spans the Internet. The mod
opens ordinary sockets, so that address is just another network card to it. No game settings change.

It is also the only encryption in the picture. Co-op traffic is plaintext JSON; a VPN wraps the whole
session in its own encryption.

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
renewed every 30 minutes, and is deleted at the next game launch if a crash left it behind.

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
retrying can change it. Fix the property and relaunch.

On the host, one line per cooldown rather than one per attempt:

```text
Coop TCP refusing connections from 203.0.113.9 for 30000 ms after 3 failed lobby password proofs
Coop TCP closing connections from 203.0.113.9 with no reply for 28000 ms after 3 failed lobby password proofs
```

Three wrong guesses buy a 30 second silent refusal, and each further failure doubles it. Practical
consequence for the guest: after fixing the password, wait out the cooldown. A corrected client
looks broken while the host is still refusing the address, and relaunching during the cooldown
extends it.

Other rejects read as plain sentences and are retried automatically every 5 seconds:
`Lobby already has a guest`, `session in reconnect grace`, and the seed and install mismatches from
`INSTALL.md` section 5.

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
  handshaking, session active, reconnecting, guest disconnected holding, or
  `rejected: <reason>`.
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
| `UPnPError 718` twice | Another device owns that external port | Pick a different `coop.hostPort`, for example 7778. |
| `UPnPError 725` | The router refuses timed leases | Nothing to do; the mod retries with a permanent lease and deletes it on exit. |
| Works on LAN, fails over the Internet | Almost always Windows Firewall on the host | Add both firewall rules from tier 1. |
| Fine but choppy | Latency, not reachability | Check round trip on the intel page. Above about 250 ms, try a VPN with a closer relay. |
| One clock runs ahead, both games on one PC | Starsector caps its frame step, so a background window runs its clock slow | Keep both windows restored and visible. The drift pulls back together within a minute. |
