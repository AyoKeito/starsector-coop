# Reporting a problem

Both players' logs, every time. One log tells half the story: which side rejected, which side went
quiet, which side saw the mismatch. The two are matched up by the session id, which is identical on
both machines.

## The file

```text
<Starsector>\starsector-core\starsector.log
```

The game rewrites it on every launch. Copy it before relaunching, or the run you want to report is
gone.

## What to paste

Search the log for these and paste what you find, from both PCs.

**`[COOP-DOCTOR]`** if a dialog told you the session ended. One line, written at WARN, starting with
that literal. Everything is on the one line by construction, newlines and quotes inside a value
escaped, so it can be selected in a single drag:

```text
[COOP-DOCTOR] code=COOP-SEED sessionId=b1f0c7 role=GUEST source=SEED_LOCK local="Ayo" remote="Keito" reason="seedString: host=MN-11 guest=MN-42" campaignIdMismatch=false hostSeed=MN-11 guestSeed=MN-42 hostFingerprint=9c3a... guestFingerprint=71ee... hostCampaignId=... guestCampaignId=<none>
```

`code` is the second field, one of `COOP-SEED`, `COOP-MODS` or `COOP-SESSION`, and it is what the
dialog tells you to search for: `[COOP-DOCTOR] code=` followed by that code. A bare `[COOP-DOCTOR]`
finds the line too.

`sessionId` is the third field and holds the identical value in both players' logs. That is what
lines two pastes up without anyone having to say which log is which. `role`, `local` and `remote` are
written from each machine's own side, so they differ between the two.

What comes after `reason` depends on the code: seeds, fingerprints and campaign ids for `COOP-SEED`;
`gameVersion`, `coopBuild`, `ironMode` and a semicolon-separated per-mod list for `COOP-MODS`;
`cause`, `graceSeconds` and `retryable` for `COOP-SESSION`. A value nobody supplied prints `<none>`.

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
- Whether the session id in the two logs matches. If it does not, the two of you were never in the
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

They go on the `vmparams` line like every other co-op setting; `INSTALL.md` section 6 shows where.
Remove them when the run is over.
