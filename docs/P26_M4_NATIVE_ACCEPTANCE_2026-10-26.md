# P26 M4 native checks, 2026-10-26

M4 remains partial. The initial native cases below ran on `4072ba0512c0`. The surviving Guest surge-tail recovery fix passes offline verification and awaits native retesting. Passing offline tests do not close the native gate.

The local evidence directory is `tmp_ff_analysis/p26/2026-10-26/m4-storms/`. Its raw campaigns, backups and captures are ignored by Git. Both clients use the seed `MN-7083542166662285692`, with Conrad Triton on Host and Frost Pillai on Guest.

| Case | Result and scope |
|---|---|
| Fresh offline verification | 4,445 Java tests pass, one existing skip, 282 suites; MCP 103/103. |
| Native old-save adoption | Both Mar 22, c207 campaigns loaded through the real UI. Host published the initial native grid; Guest received it without a private generation. |
| Baseline and pure queries | `baseline-1792982370655.json`: 10 checks; 465 shared trackers. Repeated paused probes retain their revision and values. The first immediate fleet-neighborhood samples were ABSENT; later scans establish active-phase coverage. |
| Native evolution | `evolution-1792982424944.json`: 10 checks through three ordinary one-day advances; generations 0 to 1 to 2, reaching 1,413 trackers. |
| Separated coverage | `separated-1792982493035.json`: seven checks; fleets separated by about 62,965 su, 1,790 shared trackers. `selected-cells-1792982502230.json` selects 11 active Host-window cells and seven active Guest-window cells. Preserved epochs show WAIT to SIGNAL and SIGNAL to WANE transitions. |
| Guest surge expansion and clear | `surge-1792983149789.json`: nine checks. Six selected cells have shared rate 5 and matching native phases, including STORM. After native expansion ends they are removed from the extra coverage. Guest alone has the new 16-segment stream `60ef7`; Host retains its five seasonal streams. |
| Same-session reconnect | `reconnect-1792983536416.json`: 10 checks. A 25-second inbound discard produces native grace on both clients, ordinary resume of the same session and a fresh Guest STORM_SET. Selected cells converge after explicit QA clock pairing; canonical grid/generation persist. |
| Coordinated native save | `checkpoint-1792983691910.json`: 14 checks; both Mar 28 saves and complete backups verify. Independent parsing compares all 405,000 current grid cells and 2,273 native tracker rows exactly. |
| Reload and replacement | The Host reloads the Mar 28 M4 save in the same JVM and creates a new session. All original 2,273 cell identities and epochs remain. Guest's native reload and the recovery fix are still pending. |

## Clock and sampling limits

Strict phase comparisons use equal raw campaign timestamps and the same complete revision/frame anchor. Natural paused offsets in the phase cases range from 432,000 to 936,000 campaign milliseconds. The harness explicitly aligns the lower clock while paused and waits for a current complete checkpoint. These cases do not establish production clock reconciliation or frame lockstep.

Live phase equality is sampled; whole-grid equality uses its hash. The native saved-state comparison is exhaustive across all 2,273 saved tracker rows. The row SHA-256 is `b21002741a48ec199162a65372df9b81633c0bec7d991275b897913fc7866d19`; decoded current-grid SHA-256 is `3ffa14740b4c99fc663c0dd9e32dd0dfff3efa6d172bacfb0aac8e7da7cab056`. The saved union contains 1,194 Host-only and 1,079 Guest-only rows, with none outside the two native windows. Callback, transport and DTO-cache state is absent from both XMLs. Native `savedNext` differs as expected.

## Preserved failures and qualifications

The first surge attempt has insufficient fuel. After replenishing Guest from 56 to 1,000 fuel, the native instantaneous ability consumes 710 fuel and records its readiness costs. The harness initially misclassifies its immediate inactive flag as a refusal; `surge-1792982932397.json` retains that failure. The corrected case continues that same activation rather than activating again. A later attempt retains a pause-settling failure before explicit clock pairing. Surge activation costs do not establish hyperstorm strike damage.

The first reconnect comparison is INCONCLUSIVE because Guest's raw clock is 69,000 ms ahead; `reconnect-1792983422647.json` retains the failed assertion. The later case records natural clock offsets separately, then pairs a paused checkpoint explicitly. The first save verification retains a harness URL-to-path conversion error after both native saves completed; the corrected verification reuses those saves without another save request.

`reload-readback-1792984063297.json` retains a failed exact-phase assertion. On Host, 2,272 original rows remain unchanged. Cell `(260,278)`, epoch `100841`, changes only STORM to STORM_WANE and its wane/maxWane durations. Its saved grid value is 2; the native cell loop starts waning before advancing its timer, including a zero-day step. Separately, the saved cloud mask/current grid predict exactly 65 missing live-cloud cells within the union, matching the increase to 2,338 trackers. These are native pending transitions/materialization, not evidence of broad readResolve randomization. Guest's additional projected time is recorded separately.

The actual reload defect is distinct: Host logs 15 rejected STORM_CONTROL messages from the surviving Guest-private surge tail after the new handshake. Resetting transient source authorization loses the tail after its 10-second expansion has ended. Recovery must re-advertise the actual live native private source without re-enabling the accelerated window or creating Host surge geometry.

The fix adds a neutral `SURGE_SOURCE` intent from the pinned live native OFF branch. Untagged, non-dynamic native surge geometry re-advertises its source before clearing; shared replicas and other profiles are excluded. Host checks the authenticated actor, revision and current position, then renews only its transient recent-source allowance. The clean full suite passes 4,450 tests with one existing skip across 282 suites; 66 storm tests include 24 native-hook regressions. Native retesting remains open.

## Still open

Verify the surge-tail recovery fix on the real paired M4 save, finish Guest native reload and new-session convergence, and record ordinary slipstream clearing and owner-only strike effects within explicit observable bounds. Direct mirror strike/boost execution counters are not exposed by the current bridge. M4 and P26 remain partial until their remaining acceptance requirements are resolved.
