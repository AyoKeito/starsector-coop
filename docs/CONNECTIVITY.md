# Playing over the Internet

Everything here is about one machine: the host's. The guest connects outward, and outward
connections punch their own hole through any home router, so a guest never has to configure
anything. If two people cannot connect, the problem is on the host side, every time.

That is not a design accident. The mod's network shape is a star: the guest opens a TCP connection
to the host and sends the first UDP packet, and the host answers on the return path it learned from
that packet. Nothing ever tries to reach the guest first.

So the question this document answers is: **how does the guest reach the host's machine?** There are
four ways, numbered by how much work they take. Start at the bottom of the list and work up only if
you have to; the mod tries tier 3 on its own at startup and tells you what happened.

| Tier | What it is | Who it works for |
|------|-----------|------------------|
| 3 | The game asks the router to open the port | Anyone whose router has UPnP or NAT-PMP switched on |
| 2 | You open the port on the router yourself | Anyone with router access and a public IP |
| 1 | IPv6, no port opening at all | Anyone whose ISP gives out IPv6 |
| 0 | A VPN that makes both PCs look like one LAN | Everyone, including behind carrier NAT |

Tier 0 always works. If you are short on patience, go straight there.

---

## Tier 0: a VPN pseudo-LAN

This is the fallback that cannot fail, and it needs no changes to the game at all. A mesh VPN gives
both PCs an extra IP address on a private network that spans the Internet. The mod binds ordinary
sockets, so it sees that address as just another network card.

It is also the answer to the privacy question. Coop traffic is plaintext: message payloads are JSON
and readable by anyone on the path. A VPN wraps the whole session in the VPN's own encryption, which
is the only encryption in this picture until per-packet crypto lands (recorded in the plan's Maybe
list as a pre-public-release item).

### Tailscale (recommended)

1. Both players install Tailscale from <https://tailscale.com/download> and sign in.
2. The host invites the guest to their tailnet, or both sign in with the same account.
3. On the host, open the Tailscale menu and copy the machine's `100.x.y.z` address.
4. Host launches with `-Dcoop.hostPort=7777`.
5. Guest launches with `-Dcoop.connectHost=100.x.y.z -Dcoop.connectPort=7777`.
6. If Tailscale shows "relayed" instead of "direct", it still works; expect 20 to 60 ms more RTT.

Tailscale is WireGuard underneath and does its own NAT traversal, including a relay fallback when
direct traversal fails. That relay is why it works behind carrier NAT where nothing else does.

### ZeroTier

1. Both install from <https://www.zerotier.com/download/>.
2. The host creates a network at <https://my.zerotier.com> and notes the 16-character network ID.
3. Both join it: `zerotier-cli join <network-id>`.
4. The host authorises both members in the web console (they will not talk until you tick the box).
5. The console shows each member's managed IP, usually `10.147.x.x`. Copy the host's.
6. Launch as above with that address.

### Radmin VPN

1. Both install from <https://www.radmin-vpn.com/>.
2. The host clicks Network, then Create network, and sets a name and password.
3. The guest clicks Network, then Join network, with the same name and password.
4. Both PCs now appear in each other's Radmin window with a `26.x.x.x` address.
5. Copy the host's address from the guest's Radmin window.
6. Launch as above with that address.

Radmin needs no account and is the fastest to set up. It is also the only one of the three that is
closed source and free-with-a-catch, so pick it for a one-off evening, not for a regular game.

---

## Tier 1: IPv6

If your ISP hands out IPv6, your PC already has a globally routable address and there is no NAT in
front of it. Nothing needs forwarding. The only thing standing between the guest and the host is
Windows Firewall.

Check whether you have it:

```powershell
Get-NetConnectionProfile | Select-Object Name, IPv6Connectivity
```

`IPv6Connectivity : Internet` means yes. `NoTraffic` or `LocalNetwork` means no, skip this tier.

Then find the address to share:

```powershell
Get-NetIPAddress -AddressFamily IPv6 -SuffixOrigin Random,Dhcp | Where-Object { $_.IPAddress -notlike 'fe80*' -and $_.IPAddress -notlike 'fd*' } | Select-Object IPAddress
```

Open the port (run PowerShell as Administrator, once):

```powershell
New-NetFirewallRule -DisplayName "Starsector coop TCP" -Direction Inbound -Protocol TCP -LocalPort 7777 -Action Allow
New-NetFirewallRule -DisplayName "Starsector coop UDP" -Direction Inbound -Protocol UDP -LocalPort 7777 -Action Allow
```

The guest then uses the address as-is:

```
-Dcoop.connectHost=2001:db8:1234:5678:abcd:ef01:2345:6789 -Dcoop.connectPort=7777
```

Two details the spike settled (see "Spike results" below): the game's listening sockets are
dual-stack, so no code change is needed to serve IPv6, and `coop.connectHost` accepts both the bare
literal `2001:db8::1` and the bracketed form `[2001:db8::1]`. What it does not accept is the address
and port jammed into one string; the port always goes in `coop.connectPort`.

One caveat worth knowing before you rely on this: many ISPs rotate the IPv6 prefix on every router
reboot, so the address you shared last week may be dead. Re-run the `Get-NetIPAddress` command each
session.

---

## Tier 2: forwarding the port by hand

This is the classic answer and it always works, provided the router has a real public IP on its WAN
side (see the CGNAT section under tier 3 for when it does not).

**Find the host PC's LAN address:**

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' } | Select-Object InterfaceAlias, IPAddress
```

You want the one that looks like `192.168.x.x` or `10.x.x.x`.

**On the router**, find the page called Port Forwarding, Virtual Server, or NAT. Add one rule:

- External port `7777`, internal port `7777`
- Internal address: the LAN address you just found
- Protocol: **both TCP and UDP**. If the router only lets you pick one, add two rules.

The "both" is not optional and is the single most common mistake. The mod moves fleet state over
UDP and everything else over TCP. Forward only TCP and the session connects, the clock syncs, and
then fleets stand still.

**Find the address to give the guest:** open <https://ifconfig.me> on the host PC, or run

```powershell
(Invoke-WebRequest -Uri https://ifconfig.me/ip -UseBasicParsing).Content
```

**Verify it worked** by pasting that address and port into <https://www.yougetsignal.com/tools/open-ports/>
while the host game is sitting in the lobby. That checker only tests TCP; the UDP half is confirmed
by the guest's connection doctor block saying `UDP path up`.

Also add the two firewall rules from tier 1. A forwarded port still dies at Windows Firewall.

---

## Tier 3: letting the game open the port

At host startup the mod runs `CoopPortMapper`, which asks the router to forward the port for it. It
tries UPnP IGD first, then NAT-PMP, and gives up after about five seconds either way. It never
blocks the session: a failure is a log line and a pointer back to this document.

The lease is 3600 seconds and gets renewed every 30 minutes while the game runs. On shutdown the
mapping is deleted. If the game crashes instead of exiting, the mapping expires on its own within an
hour, and the next run deletes any stale entry it finds on the same port anyway.

When it works, `starsector.log` contains:

```
Coop port mapper: UPnP gateway announced at http://192.168.1.1:5000/rootDesc.xml
Coop port mapper: gateway "ASUS RT-AX88U (RT-AX88U)" service urn:schemas-upnp-org:service:WANIPConnection:1 control http://192.168.1.1:5000/ctl/IPConn
Coop port mapper: mapped 203.0.113.7:7777 (TCP+UDP) via UPNP on gateway 192.168.1.1 "ASUS RT-AX88U", lease 3600 s
```

The address on that last line is what you send the guest.

When it does not:

```
Coop port mapper: no UPnP gateway answered in 3000 ms; trying NAT-PMP
Coop port mapper gave up: NAT-PMP gateway 192.168.1.1 did not answer -- host the session on a manual port forward, IPv6, or a VPN (see docs/CONNECTIVITY.md)
```

That usually means UPnP is switched off in the router's admin page. It is worth one look; the
setting is normally under Advanced, NAT, or WAN, and is called UPnP or "NAT traversal". If you would
rather the game did not try at all, launch with:

```
-Dcoop.portMapping=off
```

The only accepted values are `auto` (the default) and `off`. Anything else stops the game at startup
with an error rather than silently ignoring the typo.

### CGNAT, in plain words

Some ISPs have run out of IPv4 addresses and put an extra layer of NAT between your router and the
Internet. Your router thinks its public address is something like `100.71.4.9`, but that address is
shared with a few hundred other customers, and the box that owns it is in the ISP's building. You
cannot forward a port on a router you do not own.

The mod detects this. If the address your router reports is inside `10.0.0.0/8`, `172.16.0.0/12`,
`192.168.0.0/16`, `100.64.0.0/10` or `169.254.0.0/16`, the log says:

```
CGNAT/double NAT: direct IPv4 impossible; use IPv6 or a VPN (see docs/CONNECTIVITY.md). The router
mapped 100.71.4.9, which is not a public address -- something upstream is doing a second layer of NAT.
```

There is no clever fix. Tiers 2 and 3 are both dead for you. Use IPv6 if you have it, and a VPN if
you do not. Some ISPs will move you off CGNAT for free if you ask; it is worth a phone call if you
plan to host regularly.

---

## Reading the connection doctor

Both sides print a block to `starsector.log` that answers "why can't we connect" in one screen.
Search the log for `Coop connection doctor`.

### The host's block

```
Coop connection doctor:
  role              host, listening on port 7777 (TCP+UDP)
  local IPv4        192.168.1.5
  global IPv6       none (no IPv6, or only link-local/private addresses)
  port mapping      UPnP IGD via 192.168.1.1 "ASUS RT-AX88U" - external 203.0.113.7:7777
  CGNAT             no - 203.0.113.7 is a public address
  tier reached      3 - automatic port mapping (UPnP IGD)
  share with guest  203.0.113.7:7777
  next step         none - give the guest 203.0.113.7:7777 and start the session.
```

`tier reached` is the line that matters. It maps onto the four tiers above, with one honest
exception: **tier 0 cannot be detected**. A Tailscale address is a private address, and the mod has
no way to know the guest is on the same tailnet. So a working VPN session reports `0/unknown`, the
same as a broken direct one. If you set up a VPN on purpose, ignore that line.

A failed run looks like this:

```
Coop connection doctor:
  role              host, listening on port 7777 (TCP+UDP)
  local IPv4        192.168.1.5
  global IPv6       none (no IPv6, or only link-local/private addresses)
  port mapping      none (NAT-PMP gateway 192.168.1.1 did not answer)
  CGNAT             unknown (no external address was discovered)
  tier reached      0/unknown - only private addresses and no mapping. A VPN pseudo-LAN (tier 0)
                    looks identical from here, so if you are on Tailscale/ZeroTier this is expected
                    and fine.
  share with guest  nothing shareable yet - see docs/CONNECTIVITY.md
  next step         Automatic mapping did not work. Forward TCP+UDP 7777 manually on the router
                    (docs/CONNECTIVITY.md tier 2), or use a VPN pseudo-LAN (tier 0).
```

### The guest's block

```
Coop connection doctor:
  role              guest, connecting to 203.0.113.7:7777
  TCP               up
  UDP path          blocked - fleet state falls back to TCP; movement will be less smooth
  RTT               118 ms
  next step         Something between the two machines drops UDP. The session works over TCP; if
                    mirrors look choppy, both sides should try a VPN pseudo-LAN (tier 0).
```

`TCP up` with `UDP path blocked` is the signature of a port forward that only covers TCP. Go back to
tier 2 and add the UDP rule.

---

## Troubleshooting

| What you see | Where the problem is | What to do |
|---|---|---|
| Guest sits on "connecting" forever | The host is not reachable at all | Read the host's `tier reached` line. If it is 0, pick tier 0, 1 or 2. |
| Guest connects, then drops within seconds | Wrong port shared, or a second router in the chain | Confirm the guest's `coop.connectPort` matches the host's `coop.hostPort`. |
| Session works, fleets do not move | UDP is blocked, TCP is not | Add the UDP forwarding rule and the UDP firewall rule. Guest's doctor block will confirm. |
| `CGNAT/double NAT` in the host log | The ISP, not your router | Tier 1 if you have IPv6, tier 0 otherwise. |
| Mapper says "no UPnP gateway answered" | UPnP is off, or the router does not speak it | Turn UPnP on in the router, or use tier 2. |
| Mapper says `UPnPError 718` twice | Another device owns that external port | Pick a different `coop.hostPort`, for example 7778. |
| Mapper says `UPnPError 725` | Router refuses timed leases | Nothing to do. The mod retries with a permanent lease automatically and deletes it on exit. |
| Works on LAN, fails over the Internet | Almost always Windows Firewall on the host | Add both `New-NetFirewallRule` commands from tier 1. |
| Everything is fine but choppy | Latency, not reachability | Check RTT in the guest's doctor block. Above ~250 ms, try a VPN with a closer relay. |

---

## Spike results

Recorded 2026-09-02 on the development machine (Windows 11 Pro 26200, LAN `192.168.1.0/24`, host
`192.168.1.5`, gateway `192.168.1.1`).

### UPnP and NAT-PMP against the real router

`CoopPortMapperLiveSpikeTest` mapped port 27015 against whatever was on the LAN. Result: **nothing
answered**, with UPnP and NAT-PMP both switched on in the router's settings.

```
elapsed           5390 ms
tier              NONE
gateway address   192.168.1.1
external address
external port     0
failureText       NAT-PMP gateway 192.168.1.1 did not answer
```

Four independent probes agree that the gateway offers no services to this host, so this is not a
bug in the mapper:

- SSDP `M-SEARCH` to `239.255.255.250:1900`, sent from PowerShell rather than from the mod: zero
  responders in 4 seconds, for `ST: ssdp:all` (not just the gateway search target).
- Unicast `M-SEARCH` straight to `192.168.1.1:1900`: no reply.
- NAT-PMP opcode 0 to `192.168.1.1:5351`: no reply.
- TCP connect to ports 80, 443, 1900, 2869, 5000, 5431, 8080, 1780, 7547 and 49152 through 49154 on
  `192.168.1.1`: **every one closed**, including the two a router web interface would live on.

The gateway answers ICMP (`<1 ms`) and routes traffic (first WAN hop `91.77.160.1`, a public
address, so this connection is not behind CGNAT). A router that forwards packets but exposes no TCP
port at all is an ISP-managed CPE with LAN-side management disabled, not a UPnP failure.

**Consequence for the phase:** tier 3 could not be verified against real router firmware here. It
needs re-running on a second network before the WAN smoke test. What was verified instead is the
whole UPnP conversation against a stub IGD on loopback (`CoopPortMapperUpnpExchangeTest`): chunked
descriptor fetch, `GetExternalIPAddress`, `AddPortMapping` for TCP and UDP, the 718 delete-and-retry
path, the 725 permanent-lease retry, an outright refusal, a gateway with no WAN service, and
`DeletePortMapping` on shutdown. The live run did verify the failure path end to end: the mapper
degraded in 5.4 seconds, threw nothing, and produced a correct doctor block.

### Dual-stack IPv6 binds

`CoopDualStackBindSpikeTest`, same machine:

```
=== TCP ===
server local      /[0:0:0:0:0:0:0:0]:32771     (wildcard bind came up as [::], not 0.0.0.0)
accepted remote   /[0:0:0:0:0:0:0:1]:32772
remote class      Inet6Address

=== UDP ===
receiver local    /[0:0:0:0:0:0:0:0]:51635
datagram from     /[0:0:0:0:0:0:0:1]:51636
from class        Inet6Address

=== literal forms ===
"::1"           resolved=true  -> /0:0:0:0:0:0:0:1
"[::1]"         resolved=true  -> /0:0:0:0:0:0:0:1
"::1:27015"     resolved=false -> null
```

Three findings:

1. A wildcard `ServerSocketChannel` or `DatagramChannel` bind is dual-stack on Windows 11 with
   `java.net.preferIPv4Stack` unset. `CoopNetService`'s existing binds already accept IPv6, so tier 1
   is documentation, not code.
2. The peer address arrives as a real `Inet6Address`, not a v4-mapped `Inet4Address`. Anything that
   compares peer addresses (the UDP return-address pinning in `CoopNetService`) will compare v6 to v6
   and v4 to v4, with no mixed-family case to handle.
3. `coop.connectHost` accepts both `::1` and `[::1]`. It rejects `::1:27015`, which is what someone
   pastes when they think the host and port go in one property. That failure is loud, not silent:
   the address stays unresolved and the connect attempt fails immediately.

The machine has no global IPv6 (`IPv6Connectivity : NoTraffic`), so tier 1 was verified on loopback
only. A real two-household IPv6 session is still outstanding.
