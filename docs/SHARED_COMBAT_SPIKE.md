# Shared combat spike (Suite frame-sync route) — Phase A–D

## Goal

Prove the campaign can run **dual real CombatEngines** with Suite/BO-style frame sync over coop transport, instead of Phase 22 tactical-map orders or puppet spectating.

## Acceptance (Phase A)

| Check | How |
|---|---|
| Partner enters COMBAT on `BATTLE_BEGIN` / Accept | `CoopSharedCombatEntry.enterAgainstShadowEnemy` → `CampaignUIAPI.startBattle` |
| Both sides have engines | Logs: `shared combat partner ENTERED` + engager already in combat |
| Partner has controllable ship | Player fleet is side 0 of the shadow battle |

**Failure mode:** if `startBattle` cannot open a parallel campaign battle on the spectator client, dual-engine coop is blocked for vanilla campaign fights; fall back to spectator pause / BO rooms only.

## Phase B — transport

- Message: `CoopMessages.Type.SHARED_COMBAT` (`OFFER` / `READY` / `FRAME` / `CLAIM` / `CLAIM_ACK` / `LEAVE`)
- Codec: `CoopSharedCombatFrameCodec` (~10 Hz ship kinematics)
- Mid-combat drain: `CoopNetPump.drainCombatInboundWhitelist` installed on `CoopBattleBridge` — whitelist `SHARED_COMBAT`, keepalives, `BATTLE_END`/`STATUS`; other types re-queued on `deferredInbound`

## Phase C — join

- `PROXIMITY` → auto partner entry after `BATTLE_BEGIN`
- `REMOTE_SUPPORT` → Accept → entry + claim probe; Decline / `NO_MARKED_SHIP` → stay paused spectator
- Claim ack prefers Suite full variant tree when Suite is on the classpath

## Phase D — reconcile

- Engager `BATTLE_END` / local end → `sharedCombat.onBattleEnded` + existing `CoopBattleResult*` path
- Partner `BATTLE_END` / silence timeout → same cleanup via join coordinator
- Disconnect → `DISCONNECT` end (no LEAVE wire); engager finishes locally; host-authoritative reconcile; claim ships roll back with that reconcile

## Live two-instance checklist

1. Host engages NPC; guest within proximity → both logs show combat engine + `SHARED_COMBAT` FRAME traffic
2. Guest far + marked ship → Accept → claim ACK → guest pilots claimed hull
3. Guest Decline → pause only, no entry
4. Kill host TCP mid-fight → guest released; host battle completes locally
