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

**`[COOP-DOCTOR]`** if the session ended with a dialog explaining why. One line, written at WARN,
starting with that literal. It leads with the session id (the same on both sides) and then carries
the classified cause, both players' names, and whatever detail fits the cause: the two seeds and
fingerprints for a seed mismatch, the differing mods and versions for an install mismatch, the
retry-ability and grace window for a dropped session. The dialog names the exact string to search
for; a bare `[COOP-DOCTOR]` finds it too.

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
