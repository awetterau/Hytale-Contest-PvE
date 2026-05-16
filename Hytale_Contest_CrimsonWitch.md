# Crimson Witch — NPC Showcase

## Overview

**Crimson Witch** is a final boss NPC with a crimson themed design, built around cooldown based area and ranged attack systems. Her skill set combines direct ranged magic, area control, and progressive environmental hazards.
Rather than functioning as a simple creature that only launches projectiles, she gradually modifies the battlefield by corrupting the terrain over time, forcing the player to reposition and adapt during the fight.

The NPC uses Java functions and JSON configuration:

- **JSON driven NPC role/combat configuration:** for appearance, stats, targeting, attack selection, and projectile definitions.
- **Java ticking systems:** for custom runtime behavior
- **Shared trap runtime state:** that tracks temporary crimson Hazard_Spawn and restores the original blocks after expiration.
- **Environment:** The combat area contains poison zones designed to obstruct player movement.

---
<img width="960" height="540" alt="image" src="https://imgur.com/iZifufJ.png" />
## NPC Modify

| Category | Value |
|---       |---    |
| NPC Role             | `Crimson_Witch`                                       |
| Role Family Prefix   | `Crimson_Witch`                                       |
| Base Reference       | `Template_Trork_Mage`                                 |
| Main Weapon Visual   | `Weapon_Spellbook_Grimoire_Purple`                    |
| Primary Combat Range | Medium range caster / area denial                     |
| Main Design Goal     | Pressure the player with traps, and corruption zones. |

---

## Core Gameplay Loop

1. The Crimson Witch detects hostile players through her configured sight range (18), hearing range (8), alertness (18), and memory systems.
2. At medium range, she uses projectile based magic and trap abilities.
3. When one of her projectiles hits the ground, a Java based trap system transforms the affected area into crimson blocks for a defined duration.
4. These corruption zones are automatically registered and managed through the combat state system.
5. Block shaped projectiles are summoned and launched to strike the player.
6. Attack cooldowns are displayed in the HUD at the top of the screen.

---

## Combat Design Summary

| Layer | Implementation | Player_facing result |
|---    |---             |---                   |
| Ground trap                | Trap JSON + `CrimsonWitchGroundTrapProjectileSystem` | Potion lands, then corrupts nearby terrain temporarily.          |
| Helper control             | `CrimsonWitchHelperSummonSystem`                     | Witch gains support enemies and retargets them onto the player.  |
| Arena pressure             | `CrimsonWitchEnvironmentalPressureSystem`            | Crimson hazards appear around the player and escalate over time. |
| Temporary terrain mutation | `CrimsonWitchGroundTrapRuntime`                      | Blocks are restored after hazard expiration.                     |
| Low health variation       | Attack sequence + CAE                                | Witch can become more dangerous as health drops.                 |

---
<img width="960" height="540" alt="image" src="https://imgur.com/8osJk3G.png" />

## System Registration

The three custom Crimson Witch systems are registered during plugin startup in `ExamplePlugin.start()`:

```java
this.getEntityStoreRegistry().registerSystem(new CrimsonWitchGroundTrapProjectileSystem());
this.getEntityStoreRegistry().registerSystem(new CrimsonWitchHelperSummonSystem());
this.getEntityStoreRegistry().registerSystem(new CrimsonWitchEnvironmentalPressureSystem());

ArrayList<CrimsonWitchGroundTrapRuntime.BlockRestore> restores = new ArrayList<>();
```

---

## Java Systems Showcase

### `CrimsonWitchGroundTrapProjectileSystem`
**File:** `src/main/java/dev/hytalemodding/crimson/CrimsonWitchGroundTrapProjectileSystem.java`

This system turns the Witch's trap projectile into a temporary terrain corruption mechanic.

#### Key constants
A system of attack variations is used to add variety to the combat. A potion, like projectile is launched to the ground, where it triggers a variable attack pattern upon impact.

| Constant | Value | Purpose |
|---       |---:   |---      |
| `CRIMSON_WITCH_ROLE_PREFIX` | `Crimson_Witch`      | Limits the system to Crimson Witch projectiles.                  |
| `TRAP_PROJECTILE_MODEL_ID`  | `Bomb_Potion_Poison` | Identifies the trap projectile visually/configurationally.       |
| `TRAP_DURATION_MS`          | `5000`               | Hazard_Spawn lifetime before block restore.                        |
| `TRAP_PATTERNS`             | 4 variants           | Creates irregular corruption shapes instead of a perfect square. |

#### Features

- Scans active projectile entities.
- Runs as an entity-store ticking system.
- Filters only projectiles that:
  - use the model `Bomb_Potion_Poison`, and
  - were created by an NPC whose role starts with `Crimson_Witch`.
- Waits until the projectile is resting and on the ground.
- Converts nearby valid blocks into `RedWaveConfig.CRIMSON_BLOCK_ID`.
- Stores original block IDs before conversion.
- Removes the spent projectile entity after the trap activates.
- Restores expired trap Hazard_Spawn after `TRAP_DURATION_MS`.

---
<img width="960" height="540" alt="image" src="https://imgur.com/LJFNYav.png" />

### `CrimsonWitchEnvironmentalPressureSystem`
**File:** `src/main/java/dev/hytalemodding/crimson/CrimsonWitchEnvironmentalPressureSystem.java`

A system designed to manage the NPC’s combat mechanics and special attack abilities.

#### Key constants
As the fight progresses, the difficulty increases and additional attacks are introduced, creating a pressure system that challenges the player while still providing opportunities to react and adapt.

| Constant | Value | Purpose |
|---       |---:   |---      |
| `INITIAL_DELAY_MS`               | `6000` | Gives the player a short opening before environmental pressure begins. |
| `MAX_PRESSURE_INTERVAL_MS`       | `8000` | Starting interval between pressure events.                             |
| `MIN_PRESSURE_INTERVAL_MS`       | `3000` | Fastest pressure interval.                                             |
| `INTERVAL_REDUCTION_PER_STEP_MS` | `500`  | Escalation rate.                                                       |
| `MAX_HAZARDS_PER_TRIGGER`        | `3`    | Max Hazard_Spawn per pressure event.                                 |
| `TRAP_DURATION_MS`               | `7000` | Environmental hazard lifetime.                                         |
| `HAZARD_SPAWN_RADIUS`            | `10.0` | Randomized hazard spread around the target location.                   |
| Player search range              | `24.0` | Range used to find the nearest player target.                          |


#### Features

- Tracks each active Crimson Witch through its UUID.
- Starts the pressure system after a short delay.
- Periodically triggers Hazard_Spawn around the nearest player within range.
- Finds for valid ground before placing hazards.
- Converts valid terrain into crimson blocks using a compact pressure pattern.
- Stores the original blocks and restores them through the shared trap runtime.
- Reduces the interval between pressure events over time.
- Increases the Hazard count every few triggers, capped at a maximum value.

---

## Shared Runtime: `CrimsonWitchGroundTrapRuntime`

**File:** `src/main/java/dev/hytalemodding/crimson/CrimsonWitchGroundTrapRuntime.java`

A helper function used to manage the execution timing and duration of the attack zones.

### Features

- Stores temporary trap Hazard_Spawn by world UUID.
- Records each replaced block with: `World UUID` | `Block coordinates` | `ID - Original block` 
- Prevents overlapping Hazard_Spawn on blocks that are already part of active traps.
- Returns expired Hazard_Spawn so they can be restored.
- Supports clearing all runtime Hazard_Spawn for a world.

---
<img width="960" height="540" alt="image" src="https://imgur.com/ZQS0aYi.png" />

### Attack Sequence Component

**File:** `src/main/resources/Server/NPC/Roles/Crimson/Components/Component_Instruction_Attack_Sequence_Crimson_Witch.json`

Defines how the Witch chooses attacks through a tree mode attack sequence.

#### Behavior highlights

- Uses `TreeMode: true`.
- At close range with line of sight, can execute `Crimson_Witch_Fire_Trap_Attack`.
- At low health, uses trap behavior at a wider range.
- Uses `Skeleton_Sand_Mage_Spellbook_Corruption_Orb` as the standard projectile attack.
- Aims head motion toward the target using `CombatRelativeTurnSpeed`.

This file is one of the key bridges between JSON combat logic and the Java trap system: it calls the fire trap attack, which launches the trap projectile consumed by `CrimsonWitchGroundTrapProjectileSystem`.

---

### Combat Action Evaluator

**File:** `src/main/resources/Server/NPC/Balancing/Crimson/CAE_Crimson_Witch.json`

Defines utility based action selection.

#### Available actions

| Action | Ability | Range | Purpose |
|---     |---      |---:   |---      |
| `GroundTrap`      | `Crimson_Witch_Fire_Trap_Attack`              | `0–12` | Area denial and trap creation. |
| `Projectile`      | `Skeleton_Sand_Mage_Spellbook_Corruption_Orb` | `0–20` | Standard ranged pressure.      |
| `CorruptionBurst` | `Crimson_Witch_Corruption_Burst_Attack`       | `5–18` | Health based burst pressure.   |

The evaluator makes the Witch more dynamic by allowing ability choice to depend on target distance and Witch health.

---
## JSON File
**File:** `Server/NPC/Roles/Crimson/Crimson_Witch.json`

#### Important settings
Defines the Witch as a variant of `Template_Trork_Mage`.

| Field | Value / Meaning |
|---    |---              |
| `Appearance`                       | `Crimson_Witch`                                       |
| `Weapons`                          | Dual `Weapon_Spellbook_Grimoire_Purple` visuals       |
| `ViewRange`                        | `18`                                                  |
| `ViewSector`                       | `270`                                                 |
| `HearingRange`                     | `8`                                                   |
| `AlertedRange`                     | `18`                                                  |
| `TooCloseDistance`                 | `4.5`                                                 |
| `CloseRange`                       | `8`                                                   |
| `RangedAttack`                     | `Skeleton_Sand_Mage_Spellbook_Corruption_Orb`         |
| `RangedAttackSequence`             | `Component_Instruction_Attack_Sequence_Crimson_Witch` |
| `SummonKind`                       | `Scarak_Louse`                                        |
| `DesiredRangedAttackDistanceRange` | `[8, 13]`                                             |
| `MaxSpeed`                         | `8`                                                   |
| `LeashDistance`                    | `45`                                                  |
| `TargetGroups`                     | `Outlander`                                           |
| `DisableDamageGroups`              | `Self`                                                |
| `MaxHealth`                        | `74`                                                  |
| `IsMemory`                         | `true`                                                |

### Appearance Model

The NPC is spawned through the GameRunDirectorSystem, using the Potion_Brewer_Witch role, which assigns the corresponding model index to the NPC entity.
Provides default animation set overrides for states: `idle`, `walk`, `Rally`, `shockwave`, `throwing_potions` and `brewing`.


---

## Fire Trap Root Interaction
**File:** `Server/Item/RootInteractions/Crimson_Witch_Fire_Trap_Attack.json`

This is the attack entry referenced by the interaction attack sequence for the projectile attack, allowing the Java system to identify which projectile to create.

| Field | Value |
|---    |---    |
| Attack Tag  | `Ranged`                  |
| Cooldown    | `20.0`                    |
| Interaction | `Crimson_Witch_Fire_Trap` |

---

### Fire Trap Interaction
**File:** `Server/Item/Interactions/Crimson/Crimson_Witch_Fire_Trap.json`

Defines the actual projectile interaction.

| Field | Value |
|---    |---    |
| Type    | `Projectile`                                |
| Config  | `Projectile_Config_Crimson_Witch_Fire_Trap` |
| Runtime | `0.2`                                       |
| Tags    | `AimingReference`                           |

---

**File:** `Server/ProjectileConfigs/Projectile_Config_Crimson_Witch_Fire_Trap.json`

Defines the physical projectile used by the trap attack.

| Field | Value |
|---    |---    |
| Model         | `Bomb_Potion_Poison` |
| Physics Type  | `Standard`           |
| Gravity       | `20`                 |
| LaunchForce   | `20`                 |
| BounceLimit   | `0.2`                |
| Bounciness    | `0`                  |
| BounceCount   | `0`                  |
| AllowRolling  | `false`              |
| ProjectileHit | `RemoveEntity`       |

The `CrimsonWitchGroundTrapProjectileSystem` checks for `Bomb_Potion_Poison` before converting an idle projectile into a crimson Hazard_Spawn.

---

## Corruption Burst Root Interaction
**File:** `Server/Item/RootInteractions/Crimson_Witch_Corruption_Burst_Attack.json`

Defines the root wrapper for the burst attack.

| Field | Value |
|---    |---    |
| Attack Tag  | `Ranged`                         |
| Cooldown    | `15.0`                           |
| Interaction | `Crimson_Witch_Corruption_Burst` |

---

### Corruption Burst Interaction
**File:** `Server/Item/Interactions/Crimson/Crimson_Witch_Corruption_Burst.json`

Defines a chained projectile attack that launches multiple extra `Skeleton_Mage_Corruption_Orb` projectiles after the spellbook charge animation.

- Uses `Skeleton_Mage_Corruption_Orb` as the projectile.
- Starts with a `Simple` interaction.
- Uses `Skeleton_Spellbook` animation effects.
- Plays charge and impact sound events.
- Chains three `LaunchProjectile` steps.

## Crimson Poison
**File:** `Server/Entity/Effects/Status/Crimson_Poison.json`

A custom poison status effect Crimson_Poison is used to emphasize the player's corrupted crimson state. It applies new textures and visual effects within designated areas of the combat zone.

| Field | Value |
|---    |---    |
| Debuff          | `true`     |
| Duration        | `3.0`      |
| OverlapBehavior | `Extend`   |
| RemovalBehavior | `Duration` |

<img width="512" height="512" alt="image" src="https://imgur.com/6HlT61C.png" />

**File:** `Server/Particles/Status_Effect/Poison/Spawners/Crimson_Poison_Face.particlespawner.json`

### Effects System
**File:** `Server/Particles/Status_Effect/Poison/Effect_Crimson_Poison.particlesystem.json`

The effect is refreshed every second, with a maximum duration of 3 seconds.

| Field | Value |
|---    |---    |
| DamageCalculatorCooldown | `1.0` |
| BaseDamage               | `4.0` |

When applied, the effect is also displayed as particles in first person view.

| Field | Value |
|---    |---    |
| Particles            | `Effect_Crimson_Poison`   |
| FirstPersonParticles | `Effect_Crimson_Poison.0` |
