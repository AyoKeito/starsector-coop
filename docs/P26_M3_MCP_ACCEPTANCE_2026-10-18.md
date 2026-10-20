# P26 M3 campaign checks, 2026-10-18

Build `5a395f410011`, branch `codex/p26-ambient-world`. Host is Conrad Triton; Guest is Frost Pillai. Both loaded the Jan 21, c207 checkpoint with the legacy launcher setting. This run uses the existing local agent bridges and MCP implementation library. No computer-use actions were performed.

M3 remains partial. The checks below cover native light creation, ability rewards, shared consumption, cleanup and network recovery. Their original scope leaves the Dweller, migration, visual and reload gates open. [October 20 native checks](P26_M3_NATIVE_ACCEPTANCE_2026-10-20.md) subsequently verify completed-consumption reload, both small Dweller Leave paths, Host defeat, both actors' victory/loot, native Sensor Burst timeout clearing, local near/far rendering and the known native fade difference. Separate battle, timeout and render checkpoints, remaining gates and limitations are recorded there.

## Recorded results

| Check | Result | Evidence |
| --- | --- | --- |
| Native spawn branches | PASS: NORMAL 1, PAIR 2, CLUSTER 9, LARGE 1, DWELLER 1. All 14 Host identities correlate to Guest replicas; 252 values match without truncation or lookup failures. | `03-native-shape-spawns.json`, `05-post-refresh-diff.json` |
| Host Pulse | PASS: corrected native burn grant matches committed bonus `0.9137434`; Host gains 7 points, Guest gains none. | `14-host-exact-pulse.json` |
| Host Sensor Burst | PASS: corrected native sensor grant matches `1.1628785`; Host gains 8 points, Guest gains none. | `16-host-exact-sensor.json` |
| Guest Pulse | PASS: corrected native burn grant matches `0.894737`; Guest gains 7 points, Host gains none. | `19-guest-exact-pulse.json` |
| Guest Sensor Burst | PASS: corrected native sensor grant matches `0.7893769`; Guest gains 6 points, Host gains none. | `20-guest-exact-sensor.json` |
| Ordinary-light race | PASS for one committed winner: Host receives 6 points and bonus `0.77910906`. Both Guest requests are rejected with zero reward. This race alone does not prove replay of the same request id. | `22-pulse-race.json`, corrected offline audit `24-historical-offline-audit.json` |
| Shallow cleanup | PASS: the positive-lifetime light at `(0,0)` disappears on both clients after native advancement. The deep LARGE control survives; no reward is granted. | `23-native-depth-cleanup.json`, corrected offline audit `24-historical-offline-audit.json` |
| Pending consumption and reconnect | PASS: real TCP link loss and same-session resume occur with one pending Guest request. Twenty request envelopes and sixteen result envelopes leave one accepted cached result, one Guest guard and one 7-point award after native settlement. Strict comparison passes after the consumed light's fade finishes. | `25-pending-reconnect.json`, `25b-reconnect-settled.json`, `27-reconnect-fades.json`, independent `28-reconnect-audit.json` |
| Natural lifetime exhaustion | PASS: the original ordinary fixture expires after 10.3967 further campaign days in four bounded steps. Both clients remove it; PAIR and LARGE controls survive. Every strict comparison passes and reward guards remain unchanged. | `26-native-natural-expiry.json` |
| Coordinated save and backup | PASS for checkpoint creation: both Feb 14, c207 autosaves have the same timestamp. Each complete backup matches its own source file hashes. Four XML files parse; primitive reward ledgers and all 14 saved lights per client match the captured native state. Reload is a separate open gate. | `30-coordinated-checkpoint.json`, independent `31-save-and-expiry-audit.json` |
| Final campaign state | PASS: both clients are paused on clear campaign screens; 14 lights each, 252 compared values, zero differences, no truncation or lookup failures. Both have zero pending and uncertain grants. No clock write is needed for this final comparison. | `32-final-paused.json`, `33-final-strict-diff.json` |

The four exact ability checks compare full Host/Guest tables at equal raw campaign timestamps: 270 values each, zero differences, zero lookup failures and no truncation. Each has one acting-player outcome, no outcome on the other actor, and zero pending or uncertain grants. The bonus assertion uses the other positive reserve channel to subtract native travel drain, with an explicit absolute tolerance of `0.0001`. Sensor Burst's encounter-timeout reset has no independent native readback in this run; the separate October 20 positive-marker saved-XML chain closes that gate.

All raw captures, ignored test helpers and backups are under `tmp_ff_analysis/p26/2026-10-18/m3-mcp-live/run-001/`. Original failed captures remain unchanged. The historical audit records input hashes.

## Test-harness corrections

- Native duration abilities report zero progress immediately after activation. The initial Host pulse did start; capture `07-observe-pulse-without-retry.json` observes its result without another activation.
- Pause commands need time to reach both clients before clock alignment. Capture `08` retains that guard failure; `08c` records the settled Guest outcome.
- The `applied` ledger counts handled responses, including rejection guards. It is not a count of granted rewards. In the race, the two Guest guards match the two rejected Host results.
- Guest adds a tombstone when a complete Host manifest removes a light. Host native depth cleanup does not add the same consumption tombstone. The original depth harness incorrectly required identical counter changes.
- QA teleports can clear reserves through normal distance drain. Capture `15` cannot support the exact-bonus assertion because its reference reserve is zero. Fresh checks `19` and `20` establish the required positive channels and pass.
- `sessionActive` remains true during reconnect grace. Transport logs, including link-death and both resume records, establish the real interruption.
- Consumed, fading entities remain visible to the diagnostic query. Their local fade-start lifetimes can differ while paused because they have left the authoritative live manifest. Captures `25` and `25b` retain that strict mismatch. After native fades and deferred reward settlement, `27` passes the unchanged strict comparison. Lifetime equality is still required for live lights.

Test helpers explicitly use QA calendar alignment for same-epoch comparisons. These results do not establish production clock reconciliation. Lifetime refreshes require the full-set cadence, and fade completion requires native unpaused frames.

## Remaining checks

This retains the historical October 18 list, except for timeout readback and scoped rendering/fade checks completed on October 20. See the [October 20 follow-up](P26_M3_NATIVE_ACCEPTANCE_2026-10-20.md#remaining-gates) for current status.

- Exercise genuine legacy Guest lights, including an open legacy interaction and unrelated custom plugins. The Jan 21 baseline has no light candidates, so it cannot prove migration.
- Reload the saved pair and verify live identities, lifetimes, consumption tombstones, cached results and actor reward guards. Pending-consumption reload remains separate from the completed-consumption checkpoint.
- On each actor, open the small native Dweller dialog and Leave; verify the light survives and its claim releases.
- Fight a native Dweller on each actor; verify claim continuity, native loot and temporary-fleet cleanup.
- Race, delay and cancel Dweller claims, including reconnect while a claim or consumption is pending.

M4 storms, M5 ghosts/Leviathan trails and Guest-proximity encounter triggering remain unbuilt. P26 remains PARTIAL.
