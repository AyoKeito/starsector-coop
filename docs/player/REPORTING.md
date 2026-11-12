# Reporting a problem

For a co-op session problem, send reports from both players. One side's report tells half the story:
which side rejected, which side went quiet, which side saw the mismatch. An established session has
the same session id on both machines; a startup failure may have no session id yet.

## The bug report

Press **Logs**, then **Save a bug report**, in the launcher, on both PCs, and attach both files. It writes
`coop-report-host-<date>-<time>.zip` or `coop-report-guest-<date>-<time>.zip` to your Desktop and
opens the folder it landed in. If there is no Desktop folder, it uses the Starsector install folder.

Press it before you relaunch the game. Starsector rewrites `starsector.log` at every launch, so the
run you want to report is gone the moment either of you starts the game again.

What is in the zip:

| Packed | Why it is there |
|---|---|
| `starsector.log` and `starsector.log.1` | The game's own log, plus the rolled-over half if the session ran long enough to fill one. |
| `coop-launcher.log` | What the launcher did: the install check, the port mapper, the probe, the settings it wrote. |
| `coop_options.json.data` | Your settings, with `coop.password` blanked. Packed only when the blanked copy is verified; if the file cannot be parsed or the password cannot be removed, the file is left out of the zip and `report.txt` says so. |
| `vmparams` | The classpath edit and any `-Dcoop.*` left on the line, with any `-Dcoop.password=` entry removed (quoted values included). Packed only when the result provably carries neither `coop.password` nor your password; otherwise it is left out and `report.txt` says so. |
| `mod_info.json` and `enabled_mods.json` | Your mod version and your mod list. A refused handshake is almost always one of those two. |
| Your newest save | It carries the campaign id, the seed and the co-op state. Untick **Include my newest save** to leave it out, and it is skipped by itself if the game is still writing it. |
| `report.txt` | Versions, which role you launched as, the last `[COOP-DOCTOR]` line, the last `Coop connection doctor:` block, and a list of what got packed. |

The doctor block can name the host's public address, so the zip can carry it. Game logs and saves are
copied without general redaction; the password scrubbing described above applies to the settings and
launch line. Send the report privately if you do not want those files in a public thread.

## What to paste alongside it

If a refusal or session-ended dialog appeared, paste the `[COOP-DOCTOR]` line from both sides.
`report.txt` has it near the top of the zip if one was logged. Visual mismatches during a running
session may have no doctor line; use an F11 marker for those. The doctor line is written at WARN and
starts with that literal, everything on one line by construction,
newlines and quotes inside a value escaped, so it selects in a single drag:

```text
[COOP-DOCTOR] code=COOP-SEED sessionId=b1f0c7 role=GUEST source=SEED_LOCK local="Ayo" remote="Keito" reason="seedString: host=MN-11 guest=MN-42" campaignIdMismatch=false hostSeed=MN-11 guestSeed=MN-42 hostFingerprint=9c3a... guestFingerprint=71ee... hostCampaignId=... guestCampaignId=<none>
```

`code` is the second field, one of `COOP-SEED`, `COOP-MODS`, `COOP-GAME` or `COOP-SESSION`, and it is what the
dialog tells you to search for: `[COOP-DOCTOR] code=` followed by that code. A bare `[COOP-DOCTOR]`
finds the line too.

`sessionId` is the third field. It matches after the session is established; early refusals can have
`<none>`, and different connection attempts can leave different last doctor lines. Include both
reports anyway. `role`, `local` and `remote` are written from each machine's own side, so they differ
between the two.

What comes after `reason` depends on the code: seeds, fingerprints and campaign ids for `COOP-SEED`;
`gameVersion`, `coopBuild`, `ironMode` and a semicolon-separated per-mod list for `COOP-MODS`;
`modGameVersion` and `gameVersion` for `COOP-GAME`; `cause`, `graceSeconds` and `retryable` for
`COOP-SESSION`. A value nobody supplied prints `<none>`.

`COOP-GAME` is the only one of the four that is decided before anything connects: your Starsector is
not the version the mod was built for, so no port was opened and no connection was attempted. Both
players get it independently, and each log names that machine's own pair.

Loading a Survival save also blocks co-op startup. Search the log for `This save uses Survival mode`
if the campaign loads and the co-op lobby stays closed.

## Marking the moment while you play

Press **F11** when you see something wrong. One line goes into your log and the same line into your
partner's:

```text
COOP-MARK host#3 | day 74418.38 | Corvus (hyperspace) | seq 48120 | ""
```

`host#3` is the third marker the host made this session; the guest's are `guest#1` and up. The day,
the system and the sequence number are the presser's, copied verbatim into the other file, so
searching both logs for `COOP-MARK host#3` finds the same moment in each. On the open campaign map
the key also opens a note box; what you type is written under the same marker as a second line:

```text
COOP-MARK host#3 note | "guest fleet snapped back after the jump"
```

A note is cut at 120 characters, and anything outside printable ASCII is written as `\uXXXX`, so a
Cyrillic or CJK note reads as escapes and both logs carry the identical characters.

There is no box in combat, inside a market or in any other dialog. The marker still lands, so say
the number out loud or write it down and put the words in your post. Both players see
`marked host#3` or `partner marked host#3` on the link readout for five seconds. Another key can be
set in the launcher's Settings window under **Log marker key** (an LWJGL key name, `F10` for
example); the bug report zip carries the markers with the rest of the log.

## When the launcher cannot run

Do it by hand. The log is here, and the game rewrites it on every launch, so copy it before
relaunching:

```text
<Starsector>\starsector-core\starsector.log
```

Search it for these and paste what you find, from both PCs.

**`[COOP-DOCTOR]`** if a dialog told you the session ended. Same line as above.

**`Coop connection doctor:`** for anything about connecting. It is a block of about ten indented
lines; paste the whole block, not just the `next step` line. The host's block and the guest's block
say different things and both are needed.

**Every `Coop` line logged at WARN.** Those are the mod complaining, and they are usually the answer.

**Two specific lines if the complaint is about movement or smoothness:**

```text
Coop UDP return address validated ...
Coop state stream switching to TCP fallback ...
```

The first says the UDP path came up. The second says it went away again, with the reason in
brackets.

## What to say alongside it

- What each of you was doing when it happened, and which of you saw it.
- Whether the session ids match, and which connection attempt each report covers if they differ.
- The full mod list on both PCs, if the mod refused to connect at all.

For a slipstream, wreck, abyssal encounter, light or storm mismatch, press F11, pause and capture both
views with the status line visible. Name the location and the object, which player interacted with
it or used an ability, and whether this followed a load or reconnect. Say whether the difference
persists after both clients have let time pass and then paused again. The ambient preview still has
open visual checks, so matching diagnostics alone do not settle what appeared on screen.

## Turning on more detail

Only when asked. These are diagnostics, they slow the game down, and the wiretap writes your
campaign's contents into the log:

| Property | What it adds |
|---|---|
| `-Dcoop.debug.diagnostics=true` | Extra state dumps at the points the mod already logs. |
| `-Dcoop.debug.wiretap=true` | Sampled message payloads in both directions, plus a size histogram every 60 seconds. |
| `-Dcoop.debug.wiretapSample=10` | With the wiretap on, log one message in every N. |

The launcher's **Settings → Developer** tab sets these too: **Diagnostics** and **Datagram wiretap**
are checkboxes, and **Wiretap sample (every Nth)** is a spinner. Launching with these options writes
the matching keys into your settings file, and the mod
turns it back into the property above at the next launch; `INSTALL.md` section 9 covers the
mechanism, and shows where to set them as plain `-D` properties on the `vmparams` line instead, for a
launch without the launcher. Turn them back off, in whichever place you turned them on, when the run
is over. Left on in the settings file, the next session just runs with diagnostics on; left on as a
`-D` on `vmparams`, the launcher's install check will keep warning you about it besides.
