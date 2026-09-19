# Phase 20 networking spike results

Moved verbatim from the retired `docs/CONNECTIVITY.md` on 2026-09-19.

## Spike results

Recorded 2026-09-02 on the development machine (Windows 11 Pro 26200, LAN `192.168.1.0/24`, host
`192.168.1.5`, gateway `192.168.1.1` = MikroTik hAP on RouterOS 7.23.3).

### UPnP and NAT-PMP against the real router

**Tier 3 is verified against real router firmware.** It took two runs and a router fix in between,
and the failure in the middle is the more instructive half, so both are recorded.

**Run 1: silence.** `CoopPortMapperLiveSpikeTest` asked for port 27015 and nothing answered, even
with UPnP switched on in the router's settings.

```
elapsed           5390 ms
tier              NONE
gateway address   192.168.1.1
external address
external port     0
failureText       NAT-PMP gateway 192.168.1.1 did not answer
```

Four probes outside the mod agreed, which ruled out a bug in the mapper: SSDP `M-SEARCH` to
`239.255.255.250:1900` from PowerShell drew zero responders in 4 seconds even for `ST: ssdp:all`;
a unicast `M-SEARCH` straight to `192.168.1.1:1900` got nothing; NAT-PMP opcode 0 to
`192.168.1.1:5351` got nothing; and TCP connects to 80, 443, 1900, 2869, 5000, 5431, 8080, 1780,
7547 and 49152 through 49154 all came back closed. The gateway answered ICMP in under 1 ms and
routed traffic normally.

The conclusion drawn from that evidence was wrong. It looked like an ISP-managed box with LAN-side
management disabled. It was a MikroTik hAP on RouterOS 7.23.3, fully under the user's control, with
one setting pointed at the wrong interface.

**The cause.** `/ip upnp interfaces` listed `bridge` as the internal interface. The LAN subnet
`192.168.1.0/24` does not live on the bridge; it lives on `vlan10-LAN` in a bridge-VLAN setup, so
the bridge holds no IP address and the SSDP responder was bound somewhere the M-SEARCH queries never
arrived. The firewall was never involved: the input chain accepts LAN traffic by fall-through. One
command fixed it:

```
/ip upnp interfaces set 0 interface=vlan10-LAN
```

**Run 2: mapped in 428 milliseconds.**

```
elapsed           428 ms
tier              UPNP
gateway address   192.168.1.1
gateway name      MikroTik Router (Router OS)
  friendlyName    MikroTik Router
  modelName       Router OS
external address  91.77.x.x
external port     27015
cgnat             false
mapped            true
failureText

Coop connection doctor:
  port mapping      UPnP IGD via 192.168.1.1 "MikroTik Router (Router OS)" - external 91.77.x.x:27015
  CGNAT             no - 91.77.x.x is a public address
  tier reached      3 - automatic port mapping (UPnP IGD)
```

428 ms end to end covers SSDP discovery, the chunked descriptor fetch, `GetExternalIPAddress`, and
both `AddPortMapping` calls. The mapping was deleted again on shutdown, and the external address is
public, so this connection is not behind CGNAT.

Since 2026-09-04 the 718 path asks first: a conflict is answered with one
`GetSpecificPortMappingEntry`, and the entry is deleted and re-added only when the router says it
belongs to this machine. When it belongs to someone else, or when the router does not answer that
call at all, the mapper reports the conflict and leaves the mapping alone rather than evicting a
neighbour on every renewal. A router that speaks UPnP but not `GetSpecificPortMappingEntry` therefore
will not clear even its own stale entry after a crash; pick another `coop.hostPort`.

Four error paths this run did not exercise, all covered by `CoopPortMapperUpnpExchangeTest` against
a stub IGD on loopback instead: the 718 delete-and-retry path, the 725 permanent-lease retry, an
outright refusal, and a gateway advertising no WAN connection service. Run 1 covered the give-up
path end to end: the mapper degraded in 5.4 seconds, threw nothing, and printed a correct doctor
block naming tier 0/unknown.

NAT-PMP remains unverified against any hardware. RouterOS does not implement it, so this LAN cannot
test it at all.

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
   pastes when they think the host and port go in one property. A name that does not resolve makes no
   connect attempt at all: the guest logs the failure once, then re-tries the lookup on a backoff that
   grows 0.5 s, 1 s, 2 s up to 30 s, so a resolver that comes back still joins without a relaunch and
   a name that never resolves costs one frame-long stall every 30 s rather than every retry.

The machine has no global IPv6 (`IPv6Connectivity : NoTraffic`), so tier 1 was verified on loopback
only. A real two-household IPv6 session is still outstanding.

### A session across the real Internet

Run 2026-09-02, build `3d3f41b`. The two clients ran on the same PC, but only one of them reached the
host through the household LAN. The guest's JVM was launched as `jre\bin\coopguest-java.exe` (the
guest launch script's `-ProxiedJvm` switch), and a per-application proxy on the machine routes that
executable name through an AmneziaWG tunnel to a server in another network. Its packets left through
the tunnel, came back in from the public Internet to the router's WAN side, and hit the UPnP mapping
the host had opened. The host saw the guest from the tunnel's exit address, not from `192.168.1.x`.

What the logs showed:

- Host doctor block: `port mapping UPnP IGD via 192.168.1.1 "MikroTik Router (Router OS)"`, tier 3,
  `share with guest 91.77.x.x:7777`.
- Wrong password first: `LOBBY_CHALLENGE`, then `LOBBY_REJECT` "password rejected" and a drop, three
  times; then `refusing connections from <tunnel exit> for 30000 ms after 3 failed lobby password
  proofs`. A fourth failure doubled it to 60 s. Connections inside the cooldown were closed with no
  reply. The guest log said `Coop lobby rejected: password rejected`.
- Right password: `LOBBY_ACCEPT`, handshake, then `Coop UDP return address validated /<tunnel
  exit>:39195`. Fleet state streamed over UDP through the tunnel; the TCP fallback never engaged.
- Ten minutes of play: no fallback, degraded, link-death or grace transitions on either side, no coop
  WARN. Host-side p95 RTT in the handoff-margin lines ran 52 to 181 ms across 17 samples, eleven of
  them at 53 ms or 181 ms; the tunnel added most of that. Wiretap maxima 289 B (`FLEET_SNAPSHOT`) and
  883 B (`NPC_FLEET_MOTION`). Clocks were within 0.5 game-seconds at the end.

Two guest-side defects came out of the wrong-password half, filed in the plan as Phase 20 findings
F4 and F5: the guest never backs off or shows the reason after a password reject (it reconnects every
500 ms through the host's whole cooldown), and the reconnect right after a reject sends no hello, so
it sits on the host's slot until the 15 s handshake deadline drops it. Neither weakens the host side,
which rejected and throttled as designed. One practical consequence for testers: relaunching a guest
while the old process is still knocking extends the cooldown, so the corrected guest looks unable to
connect until it expires.

Both were fixed the same night in `080eaaf`. A password reject now ends the retry loop for that
launch, the HUD line reads `rejected: password rejected, relaunch with the host's password`, and the
campaign feed says the same; any other reject retries after 5 s instead of 500 ms; and a fresh
connection always opens a lobby round, so nothing sits mute on the host's slot.
