# P26 M3 native checks, 2026-10-20

Runtime build `5a395f410011`, branch `codex/p26-ambient-world`. Host is Conrad Triton; Guest is Frost Pillai. These checks extend the [October 18 MCP results](P26_M3_MCP_ACCEPTANCE_2026-10-18.md). They use native game UI and the existing local bridges. The user completed Guest's successful battle; automation handled setup, recovery, salvage and readback.

M3 and P26 remain PARTIAL. The user deferred Host's victory-and-loot check. No Host encounter was started for that check, and automatic approval review rejected its fleet-preparation command before execution after the deferral.

## Recorded results

Evidence below is retained under `tmp_ff_analysis/p26/2026-10-20/m3-ui/run-003/`. Original failures and inconclusive comparisons remain unchanged.

| Check | Result | Evidence |
| --- | --- | --- |
| Completed-consumption checkpoint reload | PASS: the matching Feb 14, c207 saves restore all 14 canonical/native light identities, resolved parameters and primitive consumption ledgers. Independent inspection reproduces the lifetime changes from 79 native frames. The final comparison matches 252 values after explicit QA epoch alignment. This does not test pending-consumption reload. | `01-reload-check.json`, `reload-independent-audit-001.json` |
| Host small Dweller dialog and Leave | PASS: the light survives, reward state stays unchanged, and accepted claim sequence 13271 releases at 15768 without claim churn when the dialog switches to the temporary fleet. | `03`–`05`, `host-leave-audit-001.json`, retained Host/Guest log spans |
| Guest small Dweller dialog and Leave | PASS: the light survives with unchanged primitive ledgers. Guest claim 1310, Host acceptance 33941 and Guest release 1359 appear in both logs, without target-swap claim churn. | `12`–`14`, `guest-leave-audit-001.json`, retained Host/Guest log spans |
| Host native defeat | PASS for the defeat consumption path: the native battle consumes the light, both clients finish its fade and the remaining world matches 234 values. Host result/applied ledgers stay unchanged on its native path; Guest adds one removal tombstone. This is not a victory or loot pass. | `06`–`11`, `host-defeat-audit-001.json` |
| Guest native victory consumption | PASS: the native UI reports complete victory. Exactly one accepted Host DWELLER result belongs to Guest, with zero light bonus and zero points. Host adds one result and tombstone; Guest adds one applied guard and tombstone. Neither has a pending or uncertain grant. Host cargo, roster identities and measured topography remain unchanged. | `20-guest-dweller-corrected-dialog.json`, `21-guest-corrected-before-battle.json`, `22-guest-corrected-after-victory.json`, `guest-corrected-dweller-audit-victory-001.json` |
| Guest native loot transfer | PASS for measured cargo: automation recovers five standard disabled ships, including Guest's flagship, then takes native battle salvage. The separate post-recovery pickup adds exactly 83 supplies and 72 fuel to Guest. Host's full cargo response stays identical. Credits, crew and marines stay unchanged during pickup; filtered topography stays Host 34 and Guest 73. Native UI also displays weapons and Dweller items, which the bridge cargo response does not enumerate. | `23-guest-loot-before.json`, `23b-guest-loot-after-recovery-before.json`, `24-guest-loot-after.json`, `guest-corrected-dweller-audit-loot-002.json` |
| Guest consumed-light removal | PASS: after 0.4 native campaign days, the consumed light is absent on both clients. Primitive ledgers remain unchanged since the victory capture. Both clients are paused on clear campaign screens; all 252 compared values match with no truncation or lookup failures. | `25-guest-after-loot-campaign.json`, `26-guest-victory-settled.json`, `guest-corrected-dweller-audit-settled-001.json` |

The final comparison uses explicit QA calendar alignment. It does not establish production clock reconciliation. Earlier same-row comparisons with different raw timestamps retain INCONCLUSIVE verdicts. Paused native fade rows are not proof of completed removal; the removal pass comes from unpaused settlement in capture 26.

## Setup corrections and limits

- Native small Dweller dialogs require a recent Pulse or Sensor Burst as well as proximity. Capture 02 preserves the failed proximity-only harness assumption; no runtime fix was needed.
- The Host bridge stopped answering during native Host combat. Capture 08 retains that timeout; later campaign captures establish the defeat result.
- The first Guest battle fleet entered at 22% readiness because new ships were readied before crew was filled. It was disabled, and the attempt was discarded without a save. Both original Feb 14 saves were reloaded, restoring the earlier Host loss and removing that test fleet. Artifacts 15–17c retain the failed setup separately from the successful battle.
- Corrected setup 18 added ships and cargo once, then failed on an unsupported `ownfleet` wrapper query. Readiness-only helper 19 used the existing direct native protocol and verified all 12 Guest ships at their native maximum of 0.7 before fresh encounter 20. It did not add ships again. The helper now validates maximum readiness and member identity before combat; source review and tests using the real action whitelist cover the earlier routing mistake.
- The successful Guest battle also disabled ships. Native recovery restored the five standard recoverable ships; this disposable test state is not a clean replacement for the backed-up Feb 14 saves. No new coordinated checkpoint was saved during this run. Both current campaigns remain paused after capture 26.
- Guest's unfiltered prebattle intel query was truncated. Guest's exact prebattle topography delta is therefore not independently measured. The accepted DWELLER result records zero points; the later filtered 73-to-73 comparison covers loot pickup only. Host's prebattle topography was measurable at 34.
- Native temporary Dweller fleets can be absent from the location fleet census even while their dialog is open. Entity-memory lookup also cannot resolve these dialog-only targets. Closed dialogs, accepted/released claim envelopes and shared light removal are observed; direct temporary-fleet cleanup remains unproven by the live diagnostics. The native leave code removes the temporary entity, but source inspection is not a census-based runtime pass.

## Prepared fixtures, not native passes

Fresh labeled save copies for Sensor Burst timeout readback and legacy-light migration are installed in the test profiles. Their campaign/descriptor hashes match independently audited workspace copies. Existing save folders were not overwritten.

- The timeout pair adds one positive 1,000-day marker to each active EncounterManager timeout tracker. The verifier resolves the active manager rather than scanning unrelated serialized managers. Tool tests cover positive baseline, Guest-only removal and both removed; the games have not loaded or exercised these fixtures.
- The legacy-format pair keeps Host's campaign unchanged and removes only two ownership entries, totaling 137 bytes, from one live Guest native light. Its native plugin, parameters, graph IDs, 13 canonical light controls and two Warning Beacon controls remain unchanged. Restoring those bytes reproduces the original. This is a constructed legacy-format save, not a historical pre-M3 save. Native migration and open-dialog deferral remain untested.

Receipts: `fixture-install-receipt.json`, `sensor-timeout-fixtures-labeled-001/fixture-receipt.json`, `legacy-light-fixtures-001/fixture-receipt.json`. Synthetic verifier tests remain separate from native acceptance evidence.

## Remaining gates

- Host native victory and loot, deferred by the user; direct temporary-fleet cleanup evidence on both actors.
- Dweller automatic/manual claim races, delayed acceptance and cancellation, including reconnect with a claim or consumption pending.
- Sensor Burst's native timeout reset using positive saved markers and native checkpoint readback.
- Local sensor/render visibility and the non-consuming client's cosmetic fade. Lights are not persistent discover-once entities; their native `discoverable` flag is false.
- Legacy-format Guest migration after a complete valid Host set, deferral while a native legacy-light interaction is open, and preservation of unrelated plugins.
- Pending-consumption reload. Completed-consumption/live-light reload passed separately above.

M4 storms, M5 ghosts/Leviathan trails and Guest-proximity encounter triggering remain unbuilt. Co-op-only pause stays; Host-solo acceptance was removed by the user's earlier policy decision, not passed.
