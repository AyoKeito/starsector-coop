# P26 M4/M5 MCP acceptance, October 31

P26 remains partial. Bounded M4 execution/owner-guard and M5 lifecycle/travel MCP checks pass. Computer use stayed paused; native visual acceptance remains open. The evidence limits below remain explicit.

## Runtime and setup

Both clients loaded the Mar 29, c207 co-op campaign, seed `MN-7083542166662285692`, with Conrad Triton on Host and Frost Pillai on Guest. Diagnostics and bridges 7801/7802 were enabled. Raw evidence is under `tmp_ff_analysis/p26/2026-10-31/m45-native/` and is ignored by Git.

Initial M5 cases use `coop.jar` build `95ddaf6591ad` and `coop-forks.jar` build `ecfb37a36368-dirty`. Post-commit `assemble` rebuilt only the main jar. `attestation-1793403732001.json` records both deployed jar hashes and JVM paths. An independent comparison against a clean `95ddaf6591ad` forks build verifies identical entry sets and every uncompressed entry byte except `META-INF/MANIFEST.MF` and generated `CoopForksBuildInfo.class`. Both clients match each other. Those results apply to this physical pair with verified source-code equivalence, not a falsely relabelled fork artifact.

Reload, course-driven storm and travel checks then use clean `0f3c27c7c772`. `attestation-1793405225786.json` verifies all four deployed jar hashes and stamps exactly. The final source changes only a misleading null-tile diagnostic reason: no selected cloud tile need not mean the point is outside the grid. Final source tests pass 4,510 Java cases with one existing skip across 288 suites, 106 MCP tests and eight SDK registration assertions.

Ghost creation uses fixed native creators/constructors, without RNG resets or synthetic terrain. Positions and fuel are explicit test setup. Brief clock advances run the real campaign; exact paired comparisons use recorded QA clock alignment, which does not prove production clock reconciliation.

## Recorded results

| Case | Receipt | Bounded result |
|---|---|---|
| Baseline | `baseline-1793403354137.json`, 8 checks | Native persistent Host manager; one owned transient Guest manager; paused query purity and matching weather baseline. The sampled cells were ABSENT. |
| Guest cosmetics | `ghosts-1793403377982.json`, 26 checks | Native NoGhost, Charger and Ziggurat each created once. AbyssalDrifter refused the non-abyss location. Complete Guest census contained only allowed types. |
| AbyssalDrifter | `drifter-1793403632229.json`, 13 checks | Native tagged Drifter created in the abyss and survived campaign advancement. Long lifetime and movement are not established. |
| Leviathan trail | `trail-1793403537004.json`, 22 checks | Native birth/growth, held Guest faders, retained terrain/point identities and exact paired checkpoint content. |
| Positive local discovery | `discovery-1793403617300.json`, 28 checks | Six positively discovered Guest points retained flags and identities through growth; Guest and Host discovery differed on matching points. |
| Calf trail | `trail-1793404118157.json`, 50 checks | Native proximity changed Dart to Run, attached the native generator, and grew a shared trail. Retained Guest discovery/identity and exact checkpoint content passed. No pulse or natural random generation claim. |
| Terminal fade/removal | `terminal-1793404551958.json`, 43 checks | A fresh native three-point trail entered its native 1.839-day fade after source collision. Paired faders matched, then the same Host/Guest trail and stream rows disappeared after about two game days. |
| Same-session reconnect | `reconnect-1793404932204.json`, 12 checks | A 25-second inbound discard produced grace on both clients and ordinary resume. Guest manager/cache and live trail terrain identities survived; complete paired TRAIL records and weather matched. |
| Native save and backup | `checkpoint-1793405020572.json`, 14 checks | Apr 5 pre-reload pair at timestamp `-55626759163462`; full native backups match source hashes. A live tagged Ziggurat was added by `save-fixture-1793405015204.json`. Save XML excludes the custom manager/probe classes, and the same Guest cache rebinds after save. |
| Actual cold reload | `reload-1793405562148.json`, 12 checks; early baseline `baseline-1793405292954.json` | New owned Guest manager, all four saved cosmetic IDs removed, three Host producers retained, all three saved Guest trail IDs/fade holds retained, exact paired current TRAIL records. Discovery and positions survive. Native width/fader advancement already occurred before the first capture; unchanged saved geometry is not claimed. All 15 Host/12 Guest ship IDs survive. |
| M4 execution/owner guard | `independent-storm-execution-1793405569925.json`, 29 checks over preserved `storm-owner-direct-1793405372177.json` | Host/Guest have 2/1 native boost installations, 30/11 returned velocity setters and positive CR/hull calls on actual owned ships. All 13/20 mirror attempts are blocked, with zero mirror effect calls. Independent raw-receipt recomputation confirms these bounds; see the invalid getter assertion below. |
| Native trail travel | `travel-1793405798046.json`, 20 checks | Both owners enter their native TRAIL mutex; burn rises 4 to 20 and along-flow speed about 80 to 391/394 versus the same-course outside control. No storm boost installation or setter count changes confound the sample. Host accepts 1,824 shared OFF controls; this does not isolate cloud mutation or attribute every control to that trail. |
| Final checkpoint | `checkpoint-1793405957055.json`, 14 checks | Apr 7 pair at `-55626655344462`, complete verified backups, unchanged actual ship IDs and post-save weather convergence. Native courses are cleared, both clients are parked and paused, and fuel is replenished. |

## Preserved failures and limits

The first trail comparison (`trail-1793403396325.json`) compared different clocks: Guest was 91,000 campaign milliseconds behind, with a newer checkpoint queued. Explicit QA pairing produced exact content without a runtime change. The first discovery fixture (`discovery-1793403587427.json`) attempted alignment before the pause became effective; its retry adds only a pause-settle wait.

The first reconnect receipt (`reconnect-1793404815078.json`) fails full stream-table equality on `committedTick` and `committedCampaignMillis` in five mature seasonal streams. All geometry/faders agree. The later pass is explicitly scoped to M5 TRAIL records; it does not erase that full-table result.

Earlier storm fixtures retain their failed positive-owner assertions. Fuel depletion, changing generations, temporary stream terrain immunity and native movement gates affect setup. The course-driven receipt reaches positive native effects, but its original harness fails an invalid immediate-getter-delta assertion. Pinned engine bytecode explains the result: `CampaignFleet.setVelocity` writes a movement-module vector; inherited `getVelocity` reads a separate entity vector copied later in `advance`. The bounded 29-check audit proves returned native execution and owner isolation, not requested impulse, immediate getter synchronization, causal boost magnitude or visuals. A subsequent retry during an existing boost is retained separately and is not a new-installation pass.

The first travel fixture (`travel-1793405682347.json`) selects an old bright point that fades out during the outside-control wait. The passing fixture selects a newer active point; no runtime behavior was changed to obtain it.

The 250 ms trail publication mechanism is implemented, but these paused samples do not measure network delivery cadence. Source collision covers interrupted-source terminal fading, not the full 15–20-day producer duration. Same-JVM campaign replacement, causal storm impulse magnitude and isolated trail-weather mutation are not established. Native visual agreement remains open.
