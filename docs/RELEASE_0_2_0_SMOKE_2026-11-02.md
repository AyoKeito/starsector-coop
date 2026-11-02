# 0.2.0 preview release smoke

Runtime and archive checked: `3e1c11c3228946f51bff5cc9ae4804f83dedc58a`, Starsector `0.98a-RC8`.
The later smoke-record commit changes repository documentation only. The release tag and ZIP use
this checked runtime commit.

`coop-0.2.0.zip`: 3,225,200 bytes, 21 entries, SHA-256
`febf0a23e6775e6b75b9e39bed8f058e1430f1c2ffc63bdd3ea5e119d7ff53ca`.

## Automated and archive checks

- Clean offline Java `test build`: 4,538 passes, one skip, zero failures/errors across 289 suites.
- MCP suite: 107 passes, including actual SDK stdio initialization, tool discovery, error response
  and child shutdown. SDK is pinned to 1.32.2; compatible transitive dependencies were refreshed.
- `npm audit --json`: zero vulnerabilities in the updated developer tool. The original 30 GitHub
  alerts concern this npm tree; GitHub still evaluates the unchanged default branch until a backport
  or merge reaches it. No alerts were dismissed.
- Archive root is exactly `coop/`; required player docs, launcher, jars, data and licenses exist.
  No developer tools, node_modules, sources, Git metadata or saves are included.
- All three own jar manifests agree on version `0.2.0` and commit `3e1c11c32289`; classloader split
  checks pass. Eleven deployed runtime files per test client match the ZIP byte-for-byte.

## Native checks

Both clients loaded the existing Apr 7, c207 campaign pair: Host Conrad Triton and Guest Frost
Pillai. They accepted session `3f1fab0c-de27-4c7d-9f3a-4b83d1a32203`. Host connection doctor reported
tier 3 with automatic UPnP mapping; Guest reported TCP/UDP up, 17 ms RTT and zero measured loss.
Storm grid hash, generation, revision and tracker count agreed at sampled checkpoints. Stream,
light and wreck counts also agreed. Raw campaign clocks were not identical; no exact world-clock
or full visual parity is claimed.

A 22-second Guest inbound discard exercised the ordinary link-death path. Guest logged TCP silence
15,374 ms against a 15,000 ms threshold, with combat, recent-save and local-stall exemptions false.
Its first reconnect reply was discarded while the fault remained active; ordinary retry then
resumed the same session on both sides. Host and Guest logged explicit resume at elapsed
359,286/354,360 ms respectively. The fault expired, reliable queues settled to zero and storm state
matched again. The bridge's `sessionActive` flag stayed true through grace because it describes
the retained session, not transport connectivity.

For one fresh light consumption, QA positioning moved Guest to an existing isolated normal light,
then the ordinary native Sensor Burst ability ran while time advanced. No synthetic light or
direct reward-handler call was used. Guest was returned to its original position afterward.
Paused setup attempts did not exercise consumption and are excluded; an evidence decoder was
corrected to read the existing length-prefixed outcome format.

| Evidence | Result |
|---|---|
| Light | `light:6ecc5f4e-2f31-4465-83c3-0cc89c9d1660` |
| Request | `d9d358b9-eec3-4b8b-9e05-cf9b594db4a7` |
| Host result | One accepted `SENSOR_BURST`, bonus 2.5446525, 13-point outcome |
| Host ledger | Results 21→22; consumed guards 19→20; local applied count stays 5 |
| Guest ledger | Applied guards 15→16; consumed guards 37→38 |
| Native bonus readback | Guest sensor reserve positive; Host has no bonus |
| Settlement | Pending and uncertain counts zero; light absent on both |

The original on-disk save pair was copied to
`tmp_ff_analysis/release-0.2.0/pre-smoke-save-3e1c11c/{host,guest}` before coordinated saving.
All four files matched their original SHA-256 hashes. These backups are local and untracked.

A coordinated native autosave created Apr 17, c207 saves on both clients. The user loaded both
through the native Load Game interface. New session `633dc8ca-e1ce-481f-bfe8-15d4fe82e117` became
active on both sides. Host retained exactly one result for the request; Guest retained its applied
guard; both retained the consumed guard. The light stayed absent, ledger totals stayed unchanged,
and pending/uncertain counts remained zero. Storm grid/generation/revision/tracker counts matched
again. This establishes persistence for this settled Sensor Burst case, not arbitrary interrupted
grants, combat outcomes or deliberately rewound saves.

The user observed a minor visual mismatch and explicitly accepted it for this preview: “There's
a minor mismatch but it's fine, let's keep it this way”. Its subtype was not characterized. This
does not close full M4/M5 native visual acceptance.

## Log qualifications and remaining scope

Twelve stale `STORM_CONTROL` revision-window refusals occurred together during initial startup.
They did not recur in the subsequent observed window or after reconnect. No continuing Fatal,
light-hook, uncertainty, apply, sync or capture failure was observed during the checked path.
Separate warnings identify an old missing-market storage baseline and ordinary Guest clock-ahead
correction. Two startup hull-spec errors also appear in October 31 logs and predate this build.

Release decision: publish a manual-download GitHub prerelease, leaving 0.1.5 stable. Full
storm/boost/mirror and ghost/trail visual acceptance, Guest-proximity generation and recorded
deferred M3 edges remain open. No native wreck consumption, Dweller combat, partial-grant failure
or exhaustive long-soak acceptance is added by this smoke.
