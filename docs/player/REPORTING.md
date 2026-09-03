# Reporting a problem

Both players, every time. One side's report tells half the story: which side rejected, which side
went quiet, which side saw the mismatch. The two are matched up by the session id, which is identical
on both machines.

## The bug report

Press **Log**, then **Save a bug report**, in the launcher, on both PCs, and attach both files. It writes
`coop-report-host-<date>-<time>.zip` or `coop-report-guest-<date>-<time>.zip` to your Desktop and
opens the folder it landed in.

Press it before you relaunch the game. Starsector rewrites `starsector.log` at every launch, so the
run you want to report is gone the moment either of you starts the game again.

What is in the zip:

| Packed | Why it is there |
|---|---|
| `starsector.log` and `starsector.log.1` | The game's own log, plus the rolled-over half if the session ran long enough to fill one. |
| `coop-launcher.log` | What the launcher did: the install check, the port mapper, the probe, the settings it wrote. |
| `coop_options.json.data` | Your settings. `coop.password` is blanked. |
| `vmparams` | The classpath edit and any `-Dcoop.*` left on the line. A `-Dcoop.password=` entry is removed. |
| `mod_info.json` and `enabled_mods.json` | Your mod version and your mod list. A refused handshake is almost always one of those two. |
| Your newest save | It carries the campaign id, the seed and the co-op state. Untick **Include my newest save** to leave it out, and it is skipped by itself if the game is still writing it. |
| `report.txt` | Versions, which role you launched as, the last `[COOP-DOCTOR]` line, the last `Coop connection doctor:` block, and a list of what got packed. |

The doctor block names the host's public address, so the zip carries it. If you would rather that did
not sit in a public thread, send the file to whoever is helping instead of attaching it.

## What to paste alongside it

The `[COOP-DOCTOR]` line, from both sides, in the text of your post. It is the one line that says
what happened, and `report.txt` has it near the top of the zip if you would rather copy it from
there. It is written at WARN and starts with that literal, everything on one line by construction,
newlines and quotes inside a value escaped, so it selects in a single drag:

```text
[COOP-DOCTOR] code=COOP-SEED sessionId=b1f0c7 role=GUEST source=SEED_LOCK local="Ayo" remote="Keito" reason="seedString: host=MN-11 guest=MN-42" campaignIdMismatch=false hostSeed=MN-11 guestSeed=MN-42 hostFingerprint=9c3a... guestFingerprint=71ee... hostCampaignId=... guestCampaignId=<none>
```

`code` is the second field, one of `COOP-SEED`, `COOP-MODS` or `COOP-SESSION`, and it is what the
dialog tells you to search for: `[COOP-DOCTOR] code=` followed by that code. A bare `[COOP-DOCTOR]`
finds the line too.

`sessionId` is the third field and holds the identical value in both players' logs. That is what
lines two reports up without anyone having to say which is which. `role`, `local` and `remote` are
written from each machine's own side, so they differ between the two.

What comes after `reason` depends on the code: seeds, fingerprints and campaign ids for `COOP-SEED`;
`gameVersion`, `coopBuild`, `ironMode` and a semicolon-separated per-mod list for `COOP-MODS`;
`cause`, `graceSeconds` and `retryable` for `COOP-SESSION`. A value nobody supplied prints `<none>`.

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
- Whether the session id in the two reports matches. If it does not, the two of you were never in the
  same session.
- The full mod list on both PCs, if the mod refused to connect at all.

## Turning on more detail

Only when asked. These are diagnostics, they slow the game down, and the wiretap writes your
campaign's contents into the log:

| Property | What it adds |
|---|---|
| `-Dcoop.debug.diagnostics=true` | Extra state dumps at the points the mod already logs. |
| `-Dcoop.debug.wiretap=true` | Sampled message payloads in both directions, plus a size histogram every 60 seconds. |
| `-Dcoop.debug.wiretapSample=10` | With the wiretap on, log one message in every N. |

The launcher's Advanced card sets these too: **Diagnostics** and **Datagram wiretap** are checkboxes
in its Developer flags group, and **Wiretap sample (every Nth)** is a spinner next to Reconnect grace
in the same card. Ticking a box there writes the matching key into your settings file, and the mod
turns it back into the property above at the next launch; `INSTALL.md` section 7 covers the
mechanism, and shows where to set them as plain `-D` properties on the `vmparams` line instead, for a
launch without the launcher. Turn them back off, in whichever place you turned them on, when the run
is over. Left on in the settings file, the next session just runs with diagnostics on; left on as a
`-D` on `vmparams`, the launcher's install check will keep warning you about it besides.
