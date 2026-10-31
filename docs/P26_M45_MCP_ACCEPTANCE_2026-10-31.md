# P26 M4/M5 MCP acceptance, October 31

P26 remains partial. These are bounded MCP observations; computer use was paused during this run. Native rendering, save reload, M5 travel/weather observations and the remaining M4 owner boost/mirror checks are still open.

## Runtime and setup

Both clients loaded the Mar 29, c207 co-op campaign, seed `MN-7083542166662285692`, with Conrad Triton on Host and Frost Pillai on Guest. Diagnostics and bridges 7801/7802 were enabled. Raw evidence is under `tmp_ff_analysis/p26/2026-10-31/m45-native/` and is ignored by Git.

The physical runtime pair is `coop.jar` build `95ddaf6591ad` and `coop-forks.jar` build `ecfb37a36368-dirty`. Post-commit `assemble` rebuilt only the main jar. `attestation-1793403732001.json` records both deployed jar hashes and JVM paths. An independent comparison against a clean `95ddaf6591ad` forks build verifies identical entry sets and every uncompressed entry byte except `META-INF/MANIFEST.MF` and generated `CoopForksBuildInfo.class`. Both clients match each other. Results therefore apply to this physical pair with verified source-code equivalence, not a falsely relabelled fork artifact.

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
| Native save and backup | `checkpoint-1793405020572.json`, 14 checks | Latest Apr 5 pair at timestamp `-55626759163462`; full native backups match source hashes. A live tagged Ziggurat was added by `save-fixture-1793405015204.json`. Save XML excludes the custom manager/probe classes, and the same Guest cache rebinds after save. Reload remains open. |

## Preserved failures and limits

The first trail comparison (`trail-1793403396325.json`) compared different clocks: Guest was 91,000 campaign milliseconds behind, with a newer checkpoint queued. Explicit QA pairing produced exact content without a runtime change. The first discovery fixture (`discovery-1793403587427.json`) attempted alignment before the pause became effective; its retry adds only a pause-settle wait.

The first reconnect receipt (`reconnect-1793404815078.json`) fails full stream-table equality on `committedTick` and `committedCampaignMillis` in five mature seasonal streams. All geometry/faders agree. The later pass is explicitly scoped to M5 TRAIL records; it does not erase that full-table result.

Storm execution fixtures retain their failed positive-owner assertions. Fuel depletion, changing generations, temporary stream terrain immunity and native movement gates affect setup. They do not establish owner boost execution or mirror immunity. New pure actor-gate diagnostics and native course setup support the next run; zero execution counters are not a pass.

The 250 ms trail publication mechanism is implemented, but these paused samples do not measure network delivery cadence. Source collision covers interrupted-source terminal fading, not the full 15–20-day producer duration. Native visual agreement, full reload cleanup/reconciliation, M5 travel/weather observations and remaining M4 execution observations still require evidence.
