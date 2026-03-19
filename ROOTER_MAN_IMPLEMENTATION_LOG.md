# Rooter Man Implementation Log

Date: 2026-03-17

## Current Architecture
- Rooter is now phase-driven and JSON-first.
- Java runtime only handles encounter lifecycle:
  - spawn in run world near infected core
  - discover/track active Rooter boss entities
  - phase-change feedback messages for role swaps

## Combat/Behavior
- Combat selection is fully `CombatActionEvaluator`-driven.
- Attack kit retained:
  - melee basic attack
  - wave attack (`RootWave`)
  - projectile attack (`RootProjectile`)
- Added non-damage tempo action:
  - `ResetTempo` -> `.BurrowedLong` recovery/reposition

## Phase Model
- `Rooter_Man_Boss_P1` -> `Rooter_Man_Boss_P2` at HP ratio <= 0.66
- `Rooter_Man_Boss_P2` -> `Rooter_Man_Boss_P3` at HP ratio <= 0.33
- Spawn role defaults to `Rooter_Man_Boss_P1`.
- Manager recognizes all `Rooter_Man_Boss*` roles and announces phase shift.

## Cleanup Completed
- Removed dead crystal runtime path.
- Removed unused Rooter custom damage systems and crystal death system.
- Reduced `RooterConfig` to active keys only.
- Trimmed `rooter-man.properties` to runtime keys only.

## Tuning Entry Points
- CAE weights/cooldowns/range:
  - `Server/NPC/Balancing/Intelligent/CAE_Rooter_Man_P1.json`
  - `Server/NPC/Balancing/Intelligent/CAE_Rooter_Man_P2.json`
  - `Server/NPC/Balancing/Intelligent/CAE_Rooter_Man_P3.json`
- Motion/telegraph pacing:
  - `Server/NPC/Roles/Rooter/Rooter_Man_Boss_P1.json`
  - `Server/NPC/Roles/Rooter/Rooter_Man_Boss_P2.json`
  - `Server/NPC/Roles/Rooter/Rooter_Man_Boss_P3.json`
