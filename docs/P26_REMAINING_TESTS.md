# P26 remaining native tests

Use the same build from `main` on both test clients with diagnostics and the MCP bridge enabled. Start from backed-up paired co-op saves, such as Apr 17, c207 from the preview smoke. Record the build, campaign clock, world coordinates or entity IDs, and screenshots from both clients. Match camera zoom and compare active storm cells after Guest receives the current Host weather revision. Record any QA positioning or clock alignment used for setup.

## M4 storms

- [ ] **Shared weather.** Put both fleets in view of the same active global cell. Watch its warning, storm and waning phases, then check a shared slipstream or Guest slipsurge clearing a cloudy footprint. Pass when both views show the same weather transitions and cleared footprint after the Host update arrives. Local flicker may differ. Use `storms` to identify the cell and revision if the views disagree.
- [ ] **Owner-only effects.** Take each real fleet through an active storm in turn. Keep the observing fleet outside the storm with the affected fleet's mirror in view. Look for the native strike and boost on the owner. Pass when both owners show the effects on their turns, the observing fleet stays unaffected, and `storms.execution` shows blocked mirror attempts with zero new mirror effect calls.

## M5 ghosts and Leviathan trails

- [ ] **Guest-local ghosts.** Observe a visible Guest cosmetic ghost under its native conditions. Match it to the `ghosts` census and confirm that the same ghost is absent from Host. Guest's local manager should contain only Charger, NoGhost, Ziggurat or AbyssalDrifter. Host keeps its native ghost behavior.
- [ ] **Shared trails.** Observe a native Leviathan or Calf trail on Host and the corresponding trail on Guest. Compare placement and width where both fleets have discovered it. Watch it grow, carry each fleet along its flow, then fade and disappear on both clients. Guest discovery is local, and shape and fade changes may lag by the 250 ms publication interval plus delivery time.

## Evidence already recorded

The [M4 native report](P26_M4_NATIVE_ACCEPTANCE_2026-10-26.md) and [M4/M5 MCP report](P26_M45_MCP_ACCEPTANCE_2026-10-31.md) cover bounded native storm controls and owner isolation, plus ghost and trail lifecycle, reconnect, cold reload and travel. The [0.2.0 preview smoke](RELEASE_0_2_0_SMOKE_2026-11-02.md) records a minor visual mismatch accepted for that preview.

Other evidence gaps include causal storm impulse magnitude, trail-only weather mutation, the full natural trail lifetime, delivery cadence and M5 same-JVM campaign replacement. One earlier reconnect also left timing fields different for five seasonal streams while their geometry and fades matched. The [P26 plan](COOP_MP_IMPLEMENTATION_PLAN_V1.md#phase-26-hyperspace-ambient-world-replication-post-v1--slipstreams--abyssal-encounters) retains deferred M3 cases and unbuilt Guest-proximity encounter generation.
