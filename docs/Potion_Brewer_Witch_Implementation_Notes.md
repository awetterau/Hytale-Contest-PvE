# Potion Brewer Witch Implementation Notes

This document is for future AI/code agents working on the Potion Brewer Witch in this repo.

Goal:
- explain how the witch currently works
- list the files that actually matter
- capture the runtime ownership split
- record the main engine/runtime failures already hit
- give a future agent enough context to modify the boss without re-reading the whole implementation

Treat this as the current working architecture, not as design brainstorming.

## Current High-Level Design

The Potion Brewer Witch is a ranged boss with an ammo-style brew loop.

Current combat loop:
- fight with only the currently brewed charges
- when charges hit zero, stop and enter `Combat.Brewing`
- place a temporary cauldron at the witch's current ground position
- trigger a circular ground shockwave when the cauldron is placed
- hold in place and brew for 3 seconds
- remove the temporary cauldron
- leave brewing and resume combat with a fresh 3-charge brewed loadout

Important:
- she does not return to a fixed cauldron anymore
- the cauldron is now a temporary world block placed where she starts brewing
- the visible `Brewing` state is real gameplay state, not flavor only
- brewed charges are the source of truth for what CAE may use

## Potion Set

Current potion pool:
- poison potion
- shadow bolt
- healing draught
- blood potion
- holy potion
- binding potion

Current brew count:
- always 3 charges per reload

Current brew rules:
- healing may appear only if the boss is below max health
- healing is capped at 1 slot per reload
- other slots are random across poison, shadow, blood, holy, and binding

Mode rules:
- poison: random self-use or thrown
- shadow: random self-use or thrown
- blood: random self-use or thrown
- holy: random self-use or thrown
- binding: random self-use or thrown
- healing: self-use only

Range rule:
- poison, blood, and binding force self-use at close range instead of trying to throw past a nearby player

Cycle self-buff rule:
- only one persistent self-buff may be armed per brew cycle
- this applies to poison self, shadow self, and holy self
- blood self and binding self are not treated as persistent self-buffs
- cycle self-buffs are cleared when brewing starts again

## Current Ability Behavior

### Poison

Thrown poison:
- normal projectile
- poison puddle on impact
- temporary crimson floor patch on impact

Self poison:
- projectile is suppressed
- witch gains a short reactive retaliation window
- nearby players who hit her during that window are flagged for poison DOT
- retaliation window lasts long enough to matter now instead of being nearly instant

### Shadow

Thrown shadow:
- normal projectile
- if a shadow self-buff is armed, the next later thrown offensive use consumes it and stuns the target

Self shadow:
- projectile is suppressed
- witch gains a real temporary stealth window and speed boost
- stealth is applied with the engine hidden-player component, not just tinting
- self-use also forces a meaningful reposition away from the player's current angle
- self-use still arms the later thrown shadow stun payoff
- self-use must not immediately trigger the stun payoff
- shadow duration is short, but long enough to create a real vanish/reposition moment
- while shadow stealth is active, the witch should not advance into the next attack or brewing transition

### Healing

Self only:
- no projectile
- healing draught is now a fixed `25%` max-health heal
- old generic regen effect was removed so the heal amount is explicit and bounded
- if the witch is already at full HP, the healing charge is discarded so the cycle cannot stall forever

### Blood

Thrown blood:
- uses a thrown projectile path
- on hit:
  - damages the player
  - heals the boss for a portion of damage dealt

Self blood:
- boss intentionally loses a small amount of HP
- spawns outward-moving blood spikes from the boss position
- spikes now hone in on the nearest player after an initial outward burst (approx 650ms)
- spikes last longer and travel further (up to 16 blocks)
- if a spike hits a player:
  - damage player
  - heal boss
  - the spike immediately disappears

Important:
- blood may not damage the witch except for the explicit self-use HP cost
- blood spikes are pooled block entities, not particles

### Holy

Thrown holy:
- spawns a tracking ground marker on the target player
- marker follows the target on `x/z` only
- marker stays near the ground even if the player jumps
- after tracking duration ends, the marker locks in place briefly
- then a damaging area explosion occurs at the final location

Self holy:
- applies a temporary absorb-style shield based on max HP
- clears the witch's active effect controller as a simple cleanse
- shield value is not passive regen
- instead, when the witch is hit during the shield window, prevented damage is refunded back as health

### Binding

Thrown binding:
- uses a thrown projectile path
- creates a short-lived stationary binding zone where the potion lands
- thrown zone radius is about `3.25` blocks
- if a player enters the zone:
  - apply stuck state
  - spawn a binding entity at the player's feet

Self binding:
- creates a short-lived binding zone centered on the boss
- the zone follows the boss while active
- self zone lasts `5` seconds
- self zone radius is about `5.25` blocks
- self zone shows the ring visuals; thrown binding does not
- if a player enters the zone:
  - apply stuck state
  - spawn a binding entity at the player's feet

Important:
- thrown binding and self binding share the same zone-entry logic, but differ in placement and radius
- a trapped player is no longer released by generic click input
- each trapped player now gets:
  - a visible small binding block entity
  - a separate invisible hurtbox entity scaled down to match
- breaking the hurtbox removes both entities and releases the player

## Other Combat Behavior

### Brewing shockwave

- when the cauldron is placed, the outward shockwave now briefly stuns players
- stun duration is about `1` second
- this reuses the same movement-lock path as binding, but does not spawn a breakable binding visual or hurtbox

### Recovery timing

- the boss no longer snaps instantly from one cast into the next state
- most actions wait for the interaction chain to clear, then apply a short recovery
- normal recovery is about `300ms`
- after the last charge is used, brewing waits slightly longer than a normal follow-up so the prior cast is not visibly cut off
- healing is the exception: it does not wait for the full interaction clear path the same way offensive casts do

### Movement / spacing

- loaded combat movement is tuned to reposition more often
- the witch now strafes in longer arcs and adjusts spacing sooner instead of planting in place
- the intent is not full laps, but more circling pressure so the player keeps moving

### Boss HUD

- there is now a top-left custom HUD for nearby/active players
- it shows:
  - witch health as a colored progress bar
  - current brewed charges
  - potion names with color-coded labels
- the HUD uses a custom `.ui` file and Java-side HUD updater code
- no separate texture assets were added for the HUD

## Important Files

### Main witch runtime

[PotionBrewerWitchSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchSystem.java)

This is the main source of truth for the boss.

What it owns:
- runtime bookkeeping per witch
- brewed loadout generation
- charge tracking
- loaded-state selection keys
- detecting actual ability execution from root interactions
- spending charges when the ability actually starts
- self/thrown mode selection at use time
- brewing start/end timing
- temporary cauldron placement and cleanup
- brewing pause duration
- brewing look-down pose
- cleanup of per-world witch runtime

Important constants/behavior:
- `BREW_DURATION_MS = 3000L`
- blood symbol is `B`
- holy symbol is `L`
- binding symbol is `N`
- role substates are still `Combat.Loaded_*`
- healing draught heal amount is `27.5f` (`25%` of the current `110` max HP target)
- self poison duration is `9000ms`
- shadow self duration is `6500ms`
- shadow reposition target distance is about `10` blocks

### Projectile handling

[PotionBrewerWitchProjectileSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchProjectileSystem.java)

What it owns:
- projectile spawn detection
- self-use projectile suppression for poison and shadow
- poison puddle/crimson impact path

Important:
- this system does not own charge truth

### Poison runtimes

[PotionBrewerWitchPoisonRuntime.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchPoisonRuntime.java)
- active poison puddle records

[PotionBrewerWitchPoisonDamageSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchPoisonDamageSystem.java)
- poison puddle DOT
- reactive poison DOT
- player-only damage path, so the witch does not get hit by her poison floor

[PotionBrewerWitchReactivePoisonRuntime.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchReactivePoisonRuntime.java)
- self-poison retaliation player records

[PotionBrewerWitchCrimsonPatchRuntime.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchCrimsonPatchRuntime.java)
- poison crimson-floor restore data

### Blood runtime

[PotionBrewerWitchBloodSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchBloodSystem.java)

What it owns:
- claimed thrown blood projectiles
- blood projectile hit detection
- blood self-cost
- blood healing on hit
- blood spike spawning, movement, hit detection
- blood spike block pooling and hide/reuse lifecycle

Important behavior:
- blood spike visuals use pooled block entities parked in-world, not removed
- blood projectile hit and spike hit both refuse to damage `Potion_Brewer_Witch`

### Holy runtime

[PotionBrewerWitchHolySystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchHolySystem.java)

What it owns:
- pending thrown holy casts
- target tracking marker runtime
- lock-and-detonate holy explosion runtime
- temporary absorb shield runtime
- cleanse behavior
- particle recipient collection for holy visuals

Important behavior:
- holy tracking now follows player `x/z` only after initial placement
- holy marker stays near floor and should not rise when player jumps
- marker placement now scans downward for ground instead of trusting airborne player Y
- holy visuals are particles, but damage/shield logic stays server-side in Java
- holy shield is currently a damage-refund style absorb:
  - on hit, absorbed damage is added back as health
  - it is not a passive HOT/regen effect

### Binding runtime

[PotionBrewerWitchBindingSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchBindingSystem.java)

What it owns:
- claimed thrown binding projectiles
- stationary thrown binding zones
- boss-attached self binding zones
- player stuck runtime and release
- per-player visible binding block entity spawn/update/cleanup
- per-player invisible hurtbox spawn/update/cleanup
- temporary movement lock reused by brewing shockwave stun

### Brewing shockwave runtime

[PotionBrewerWitchShockwaveRuntime.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchShockwaveRuntime.java)
- world-level ripple bookkeeping
- pooled ripple block refs

[PotionBrewerWitchShockwaveSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchShockwaveSystem.java)
- visual ripple propagation
- pooled ripple mover reuse
- scripted rise/fall animation

[PotionBrewerWitchShockwaveDamageSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchShockwaveDamageSystem.java)
- player-only shockwave damage
- one-hit-per-ripple protection

Important behavior:
- ripple is a single outward ring, one layer at a time
- ripple blocks are pooled/hidden, not removed

### Debug commands

[BloodSpikesCommand.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/commands/dev/BloodSpikesCommand.java)
- `/bloodspikes`
- spawns blood spike pattern for testing without the witch

[HolyTrackCommand.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/commands/dev/HolyTrackCommand.java)
- `/holytrack`
- spawns the holy tracking marker/explosion on the player for testing

[BindingTestCommand.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/commands/dev/BindingTestCommand.java)
- `/bindingtest self`
- `/bindingtest thrown`
- useful for testing live binding zones without waiting for the brew cycle

### Plugin registration

[ExamplePlugin.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/ExamplePlugin.java)

This registers:
- potion witch systems
- blood system
- holy system
- binding system
- shockwave systems
- witch HUD system
- debug commands

### Role asset

[Potion_Brewer_Witch.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/NPC/Roles/Potion/Potion_Brewer_Witch.json)

What it owns:
- visible state tree
- `Combat.Brewing`
- all `Combat.Loaded_*` states
- combat movement shell

Important:
- loaded substates still matter because CAE actions are keyed by them
- old “return to fixed cauldron” design should be considered obsolete

### CAE config

[CAE_Potion_Brewer_Witch.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/NPC/Balancing/Potion/CAE_Potion_Brewer_Witch.json)

What it owns:
- brewed-action action sets keyed to loaded substates

Important:
- this file was expanded to cover the symbol space for:
  - `H`
  - `P`
  - `S`
  - `B`
  - `L`
  - `N`

### Root interactions

Current root interactions:
- [Potion_Brewer_Witch_Poison_Potion_Attack.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/RootInteractions/Potion/Potion_Brewer_Witch_Poison_Potion_Attack.json)
- [Potion_Brewer_Witch_Shadow_Bolt_Attack.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/RootInteractions/Potion/Potion_Brewer_Witch_Shadow_Bolt_Attack.json)
- [Potion_Brewer_Witch_Heal_Self_Attack.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/RootInteractions/Potion/Potion_Brewer_Witch_Heal_Self_Attack.json)
- [Potion_Brewer_Witch_Blood_Potion_Attack.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/RootInteractions/Potion/Potion_Brewer_Witch_Blood_Potion_Attack.json)
- [Potion_Brewer_Witch_Holy_Potion_Attack.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/RootInteractions/Potion/Potion_Brewer_Witch_Holy_Potion_Attack.json)
- [Potion_Brewer_Witch_Binding_Potion_Attack.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/RootInteractions/Potion/Potion_Brewer_Witch_Binding_Potion_Attack.json)

These are what `PotionBrewerWitchSystem` watches to detect real ability execution.

### Interaction / projectile assets

Current direct potion interaction files:
- [Potion_Brewer_Witch_Poison_Potion.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Poison_Potion.json)
- [Potion_Brewer_Witch_Shadow_Bolt.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Shadow_Bolt.json)
- [Potion_Brewer_Witch_Heal_Self.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Heal_Self.json)
- [Potion_Brewer_Witch_Blood_Potion.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Blood_Potion.json)
- [Potion_Brewer_Witch_Holy_Potion.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Holy_Potion.json)
- [Potion_Brewer_Witch_Binding_Potion.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Binding_Potion.json)
- [Potion_Brewer_Witch_Heal_Self.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Item/Interactions/Potion/Potion_Brewer_Witch_Heal_Self.json)

Important:
- healing no longer relies on a generic regen effect asset to determine final healing amount
- the actual heal amount is applied in Java when the healing draught ability is consumed

### HUD files

- [PotionBrewerWitchHud.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/hud/PotionBrewerWitchHud.java)
- [PotionBrewerWitchHudSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/potion/PotionBrewerWitchHudSystem.java)
- [PotionBrewerWitchHud.ui](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Common/UI/Custom/PotionBrewerWitchHud.ui)

Important:
- the first HUD draft used a detached `@...` attachment pattern in the `.ui` file that was not consistent with the rest of this repo
- that pattern was removed and replaced with inline child definitions only

### Predictive Aiming and Visual Arcs

All thrown potions (Poison, Blood, Holy, Binding) now use a **Predictive Aiming System**:

1.  **Visual Arc (Parabola):**
    - JSON-configured gravity (approx 25) and launch force (approx 18) create a realistic parabolic trajectory.
    - Throws are no longer straight lines; they "lob" toward the target.

2.  **Predictive Leading:**
    - The witch calculates the player's current velocity.
    - It estimates flight time: `t = distance / projectile_speed`.
    - It aims at a "wanted" point: `P' = P + (Velocity * t * 0.6)`.
    - The `0.6` multiplier ensures the witch leads the target but remains dodgeable if the player changes direction.

3.  **Aim Smoothing (Drift):**
    - The witch's aim does not snap instantly to the predicted point.
    - It "drifts" toward the wanted point with a `0.4` smoothing factor every tick.
    - Rapid direction changes by the player will cause the witch's aim to trail behind or overshoot the empty space where the player used to be.

4.  **Implementation Details:**
    - Uses Hytale's native `LaunchProjectile` interaction type.
    - Predictive math and aim smoothing are handled in `PotionBrewerWitchSystem.java` via `TargetTracker`.
    - Player velocities are tracked across ticks to allow leading calculation.
    - Interaction chains for thrown abilities have their target position overridden by the calculated predictive point at start-time.

Projectile config worth knowing:
- [Projectile_Config_Potion_Brewer_Witch_Blood_Potion.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/ProjectileConfigs/Potion/Projectile_Config_Potion_Brewer_Witch_Blood_Potion.json)
- [Projectile_Config_Potion_Brewer_Witch_Binding_Potion.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/ProjectileConfigs/Potion/Projectile_Config_Potion_Brewer_Witch_Binding_Potion.json)

### Holy particle assets

Main holy particle systems:
- [Potion_Brewer_Witch_Holy_Track.particlesystem](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Potion_Brewer_Witch_Holy_Track.particlesystem)
- [Potion_Brewer_Witch_Holy_Explosion.particlesystem](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Potion_Brewer_Witch_Holy_Explosion.particlesystem)

Key holy spawners:
- [Potion_Brewer_Witch_Holy_Track_Circle.particlespawner](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Spawners/Potion/Potion_Brewer_Witch_Holy_Track_Circle.particlespawner)
- [Potion_Brewer_Witch_Holy_Track_Wave.particlespawner](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Spawners/Potion/Potion_Brewer_Witch_Holy_Track_Wave.particlespawner)
- [Potion_Brewer_Witch_Holy_Explosion_Flash.particlespawner](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Spawners/Potion/Potion_Brewer_Witch_Holy_Explosion_Flash.particlespawner)
- [Potion_Brewer_Witch_Holy_Explosion_Wave.particlespawner](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Spawners/Potion/Potion_Brewer_Witch_Holy_Explosion_Wave.particlespawner)
- [Potion_Brewer_Witch_Holy_Explosion_Sparks.particlespawner](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/Particles/Spell/Holy/Spawners/Potion/Potion_Brewer_Witch_Holy_Explosion_Sparks.particlespawner)

## Runtime Ownership Split

This split is intentional and should be preserved unless there is a strong reason to change it.

### Java owns

- brewed loadout data
- charge consumption
- self/thrown mode choice for poison, shadow, blood, and holy
- close-range self-forcing for poison, blood, and binding
- self-use projectile suppression for poison/shadow
- blood projectile/spike runtime
- holy tracker, explosion, shield, and cleanse runtime
- binding zone runtime, player movement lock, and foot binding visuals
- binding release now depends on the bound hurtbox dying, not generic mouse input
- poison retaliation runtime
- one-shot healing draught HP restore
- shadow stealth application and shadow reposition command queueing
- cauldron placement/removal
- brewing timer
- brewing downward pose
- brewing shockwave runtime
- witch HUD snapshot state

### Role JSON owns

- visible state tree
- `Combat.Brewing`
- `Combat.Loaded_*`
- movement shell

### CAE owns

- selection among only the currently legal brewed actions

### Particles/assets own

- holy target marker visuals
- holy detonation visuals
- binding self ring visuals

## Current Known Good Behavior

When working correctly:
- witch can brew poison, shadow, blood, holy, and conditional heal
- witch can also brew binding
- witch places a cauldron at her current position when starting to brew
- witch pauses in `Combat.Brewing` for 3 seconds
- witch looks downward while brewing
- witch creates a single expanding ripple shockwave when cauldron is placed
- cauldron is removed when brewing ends
- witch resumes combat in the correct `Combat.Loaded_*` state
- every real ability start consumes exactly one brewed charge
- empty charges lead back into brewing, not a stuck loaded state
- poison/shadow self-use suppresses projectile
- blood self-use harms only the witch via explicit self-cost, then launches spikes
- blood/poison/shockwave do not damage the witch
- brewing shockwave briefly stuns players in place
- holy thrown tracks, locks, and detonates
- holy self-use grants shield and cleanse
- holy marker stays grounded even if the player jumps
- binding thrown creates a short stationary snare zone
- binding self-use creates a short boss-following snare zone
- self binding uses a larger radius than thrown binding
- trapped players break free by destroying the paired invisible hurtbox
- healing draught heals a fixed `25%` max-health chunk
- shadow self-use hides the witch and repositions her before the next attack pattern
- poison, holy, and shadow self-buffs do not stack across a single brew cycle

## Important Problems Already Hit And How They Were Solved

These are the main failures that already cost time. Future agents should not rediscover them.

### 1. Charge spending on projectile spawn was wrong

Problem:
- healing and self-use branches do not map cleanly to projectile spawn
- charge truth became unreliable

Fix:
- consume charge when the root interaction actually starts in `PotionBrewerWitchSystem`

Rule:
- do not move charge truth back into projectile systems

### 2. Witch got stuck in `Combat.Loaded_*` with zero charges

Observed symptom:
- repeated `blockedUnbrewedAbility ... remaining=[]`
- no re-brew after last blood use

Cause:
- `returnQueued` could remain stuck true across role transitions, so the empty-charge transition was skipped

Fix:
- clear `returnQueued` on brewing entry and on the relevant phase transitions in `PotionBrewerWitchSystem`

### 3. Brewing ended immediately instead of pausing

Observed symptom:
- entered `Combat.Brewing`
- immediately left brewing and resumed attacking

Cause:
- same-tick race between requested brewing phase and visible role state

Fix:
- gate brewing completion on real brewing initialization, not just phase enum

### 4. Brewing look-down crashed the world

Observed symptom:
- `Store is currently processing! Ensure you aren't calling a store method from a system`

Cause:
- direct transform writes were happening during system tick

Fix:
- move brewing pose updates to deferred world command execution

Rule:
- do not directly mutate store-owned components from that path during system processing

### 5. Blood projectile made the witch get deleted

Observed symptom:
- witch removed when using blood projectile
- projectile spawn exception around empty interaction map

Cause:
- blood projectile asset did not have a valid projectile hit interaction map

Fix:
- add a non-empty `ProjectileHit` interaction config to the blood projectile asset

### 6. Blood spikes crashed chunk save / removed world

Observed symptom:
- `Invalid entity reference!`
- `Removing world exceptionally`

Cause:
- spike block entities were being removed while the world/chunk serializer still had live references

Fix:
- stop removing spike entities
- hide and pool them instead
- park them at a valid in-world Y (`-16`, not far below world floor)

Rule:
- pooled visual block entities are safer than removing them during active world runtime

### 7. Shockwave ripple crashed chunk save / removed world

Observed symptom:
- same invalid ref failure as blood spikes

Cause:
- ripple movers were being removed during or near serialization-sensitive timing

Fix:
- pool/hide ripple mover entities instead of deleting them

### 8. Shockwave initially only went up or shot too high

Observed symptom:
- blocks rose several blocks
- sometimes never fell back down

Cause:
- physics-style vertical behavior was too uncontrolled for the desired effect

Fix:
- switch to scripted rise/fall arc based on effect timing instead of velocity-style motion

### 9. Shockwave hit alignment was wrong

Observed symptom:
- visible ripple could pass through player without damage

Fix:
- sync damage timing to the visual ring pacing
- allow only one hit per player per ripple through `hitPlayers`

### 10. Holy particle marker initially spawned as stacked trails or not at all

Observed symptom A:
- marker left piles/trails instead of feeling like one moving ground marker

Cause:
- long-lived particle systems were being respawned each update

Fix:
- shorten marker lifespan and respawn interval so refreshes overwrite the feel of the old position

Observed symptom B:
- damage happened but no particles were visible

Cause:
- rotation-aware particle spawn overload was called with an empty recipient list

Fix:
- gather player refs in-world and use them as particle recipients

### 11. Holy particle marker was vertical/sideways

Observed symptom:
- marker projected like a wall/arch instead of a ground circle

Cause:
- trying to force orientation in Java was the wrong pattern for these assets

Fix:
- remove Java-side forced rotation
- move orientation into the particle spawners, following built-in ground AoE asset patterns

### 12. Holy marker was oval and clipped into the floor

Cause:
- non-uniform X/Y scaling in the particle spawners
- marker origin slightly too low
- following player Y made jumps lift the marker

Fix:
- use one-to-one scaling for the track visuals
- raise marker height slightly
- update only `x/z` while tracking

### 13. Binding feet visual could not be hit reliably

Observed symptom:
- hits appeared to pass through the bind object
- releasing on direct hits at the player's feet was unreliable or impossible

Cause:
- the original bind visual was only a block-entity style visual, not a proper damage target
- when the visual lived inside the trapped player's own space, targeting it from first-person was unreliable even when it looked centered correctly

Fix path:
- first separate the problem into visual vs hurtbox testing with `/bindingtest`
- confirm a separate invisible NPC hurtbox can be hit consistently
- pair the visible small binding block entity with that invisible hurtbox
- move the real release condition to hurtbox death/health depletion
- remove the temporary generic mouse-click release fallback once the hurtbox path works

Rule:
- if a future trap needs to be "hit to break," give it a real damageable hurtbox and let visuals be visuals

### 14. Binding hurtbox size did not match the visual

Observed symptom:
- the invisible target felt bigger than the small binding entity

Cause:
- the spawned hurtbox role inherited a larger default target volume than the visual implied

Fix:
- scale the spawned invisible hurtbox entity down in Java to match the small binding visual more closely

Rule:
- when pairing a visual with an invisible hurtbox, tune the hurtbox size to the visual instead of assuming defaults will line up

### 15. Binding cleanup could still crash world save if one missing witch cleared global runtime

Observed symptom:
- `Invalid entity reference!`
- world/chunk save crashes after earlier binding activity

Cause:
- per-boss missing cleanup in `PotionBrewerWitchSystem.cleanupIfMissing(...)` was calling world-wide `clearWorld(...)` methods for binding and the other potion subsystems
- one missing boss could therefore invalidate runtime-owned refs for the entire world

Fix:
- stop doing world-wide subsystem clears from the per-boss missing path
- keep cleanup scoped to the missing witch runtime itself

Rule:
- world-wide subsystem teardown should not be triggered just because one boss instance disappeared

### 16. Shadow stealth crashed the world when invisibility was first added

Observed symptom:
- `Store is currently processing! Ensure you aren't calling a store method from a system`

Cause:
- the first shadow stealth implementation wrote hidden-component and teleport changes directly during chunk iteration

Fix:
- queue stealth, teleport, and other store mutations into deferred `world.execute(...)` commands
- when shadow is cleared on brewing entry or other cycle cleanup, queue stealth removal instead of removing the hidden component inline
- also hold pending recovery transitions until the shadow window has actually ended

Rule:
- if shadow stealth or reposition changes again later, keep component writes deferred and do not mutate the ECS store inline from the tick loop

### 17. Healing draught stalled for too long before firing

Observed symptom:
- healing draught sometimes sat for many seconds before finally executing, especially as the last brewed charge

Cause:
- root interaction / CAE timing gates were still much longer than the new combat pacing expected

Fix:
- lower the timing gates in the healing CAE/root interaction setup
- also stop tying the heal amount to a generic regen effect

Rule:
- when a cast feels "stuck," check both Java timing and the content-side cooldown/timing gates

### 18. Holy marker spawned in the air when the target was airborne

Observed symptom:
- holy warning marker appeared suspended or half-embedded when the target was jumping/falling

Cause:
- marker placement originally trusted the player's current Y too much

Fix:
- scan downward for solid ground and anchor the marker there
- keep updating grounded `y` during tracking

Rule:
- target-following ground markers should resolve their own floor height instead of inheriting airborne target height

### 19. First HUD `.ui` draft used an unsupported or at least unproven pattern

Observed symptom:
- HUD load/parsing issue from the first custom `.ui` draft

Cause:
- detached `@WidgetName` attachment style was introduced even though existing repo UI files mostly define children inline

Fix:
- rewrite the HUD `.ui` to only use patterns already present in this repo and the comparison project

Rule:
- for Hytale `.ui` work in this repo, prefer copied local patterns over inventing attachment/layout syntax

## Assets / Engine References Used

Read-only Hytale asset root:
`C:\Users\cryst\AppData\Roaming\Hytale\install\release\package\game\latest\Assets`

Useful references:

### Role / combat movement references

- [Template_Trork_Mage.json](C:/Users/cryst/AppData/Roaming/Hytale/install/release/package/game/latest/Assets/Server/NPC/Roles/Intelligent/Aggressive/Trork/Templates/Template_Trork_Mage.json)
- [Component_Instruction_Target_Adjusted_Attack.json](C:/Users/cryst/AppData/Roaming/Hytale/install/release/package/game/latest/Assets/Server/NPC/Roles/_Core/Components/Steps/Component_Instruction_Target_Adjusted_Attack.json)
- [Component_Instruction_Combat_Retreat.json](C:/Users/cryst/AppData/Roaming/Hytale/install/release/package/game/latest/Assets/Server/NPC/Roles/_Core/Components/Steps/Component_Instruction_Combat_Retreat.json)

### Crimson references used earlier

- [CrimsonWitchGroundTrapProjectileSystem.java](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/java/dev/hytalemodding/crimson/CrimsonWitchGroundTrapProjectileSystem.java)
- [Crimson_Witch.json](C:/Users/cryst/OneDrive/Desktop/Hytale-Contest-PvE/src/main/resources/Server/NPC/Roles/Crimson/Crimson_Witch.json)

### Extraction/block-entity movement reference for blood spikes

Future agents should inspect the repo extraction systems if they need another moving block-entity pattern. Blood spikes were built from that style of approach, then hardened with pooling.

### Rooterman shockwave reference

The brewing shockwave was conceptually based on the Rooterman shockwave idea, but changed to:
- circular ripple
- one ring layer at a time
- pooled movers
- scripted rise/fall

### Particle references for holy

Built-in portal references used while prototyping:
- `Server/Particles/Spell/Portal/...`

Built-in ground AoE references that were more correct for orientation:
- `Server/Particles/Deployables/Slowness_Totem/Totem_Slow_AoE.particlesystem`
- `Server/Particles/Deployables/Slowness_Totem/Totem_Slow_AGroundSpawn2.particlespawner`

Important Java API reference:
- `com.hypixel.hytale.server.core.universe.world.ParticleUtil`

Relevant finding:
- `ParticleUtil` can spawn particle systems by asset id string
- when using overloads with explicit recipients, do not pass an empty list unless the intent is to show the effect to nobody

Relevant engine/API finding from later work:
- `HiddenFromAdventurePlayers` is a real component and is the correct engine-side way used here to hide the witch during shadow self-use

## Useful Log Strings

Main witch logs:
- `brewLoadout`
- `phaseChange`
- `applyState`
- `forceLeaveBrewing`
- `consumeAbilityCharge`
- `queueLoadedState`
- `selfUse poison`
- `selfUse shadow`
- `selfUse blood`
- `selfUse holy`
- `selfUse binding`
- `shadowEmpowerTriggered`
- `blockedUnbrewedAbility`
- `placeBrewingCauldron`
- `removeBrewingCauldron`

Projectile logs:
- `projectileSpawn poison`
- `projectileSpawn shadowBolt`
- `suppressProjectile poison self-use`
- `suppressProjectile shadow self-use`

## Current Weak Spots / First Places To Check

If behavior regresses, inspect these first.

### Wrong ability still being used

Check:
- current `Combat.Loaded_*` state
- current CAE action set for that state
- `queueLoadedState`

### Brewing ends too early or not at all

Check:
- `BREW_DURATION_MS`
- brewing initialization flags in `PotionBrewerWitchSystem`
- logs around `applyState`, `placeBrewingCauldron`, and `forceLeaveBrewing`

### Witch damages itself from a spell

Check:
- blood damage filters in `PotionBrewerWitchBloodSystem`
- poison damage query in `PotionBrewerWitchPoisonDamageSystem`
- player-only damage logic in `PotionBrewerWitchShockwaveDamageSystem`

### Holy visuals look wrong

Check:
- particle spawner orientation in the holy spawner assets
- marker height in `markerPosition(...)`
- scale ratio constraints in the holy track spawners
- whether marker tracking is updating only `x/z`

### World crashes around pooled visuals

Check:
- whether a future change reintroduced entity removal for blood spikes or ripple movers
- whether binding cleanup or per-boss missing cleanup is again touching world-wide runtime refs

### Shadow invisibility or reposition regresses

Check:
- `syncShadowStealth(...)`
- deferred `applyStealth(...)` / `applyTeleport(...)` path
- whether hidden-component writes were accidentally moved back into live system iteration

### Healing amount or cadence feels wrong

Check:
- `HEALING_DRAUGHT_HEAL_AMOUNT`
- `Potion_Brewer_Witch_Heal_Self.json`
- healing root interaction timing / CAE gates
- full-health healing discard path in `PotionBrewerWitchSystem`

## Short Summary

The correct mental model is:
- `PotionBrewerWitchSystem` owns the boss's actual gameplay logic
- role JSON and CAE expose only what the current brewed loadout allows
- brewing is now a local pause-and-place-cauldron loop, not a return-home loop
- blood and shockwave visuals are pooled block entities
- holy visuals are particle systems
- self-use poison/shadow works by suppressing the projectile and applying runtime buffs
- shadow stealth/hide and reposition are deferred world commands, not inline ECS writes
- healing draught is a bounded Java-side heal, not free-form regen
- most serious crashes already came from bad entity lifecycle handling or bad store mutation timing, not from the high-level design itself

If changing this boss, preserve that split unless there is a strong reason not to.

## Proposed Healing Changes (In Progress)

### Intended Behavior

1. **Max Health Scale**: The boss is tuned for 110 Max HP.
2. **Dynamic Healing Choice**:
   - If HP < 60% (66 HP): Witch chooses to **Drink** (Self-Heal).
   - If HP >= 60%: Witch chooses to **Throw** (Heal Zone).
3. **Healing Formula (Decay)**:
   - Base Heal: 22 HP (20% of Max HP).
   - Decay: Each subsequent drink is weaker: `Heal = 22 / (1.5 ^ heal_count)`.
   - Floor: Minimum heal is always 1 HP.
4. **Heal Zone (Thrown)**:
   - Impact creates a 7-block radius "Heal Zone" for 2 minutes.
   - Ground blocks convert to `Cloth_Block_Wool_Green_Light`.
   - Witch regenerates 2.5 HP/sec while standing in the zone.
   - Players do NOT receive healing from the zone.
   - Blocks are restored when the zone expires.

### Current Implementation State & Errors

- **Java Logic**: Implemented in `PotionBrewerWitchSystem.java`, `PotionBrewerWitchProjectileSystem.java`, and a new `PotionBrewerWitchHealZoneHealingSystem.java`.
- **Assets**: New interactions and projectile configs added to `src/main/resources/Server/`.
- **Current Blocker**: The server fails to start/allows join with a `java.lang.IllegalStateException`.
  - Error: `ProjectileInteraction 'Potion_Brewer_Witch_Heal_Thrown' has no valid ProjectileConfig: Projectile_Config_Potion_Brewer_Witch_Heal_Thrown`.
  - Symptom: Despite the file existing in `src/main/resources/Server/ProjectileConfigs/`, the internal Hytale asset loader is failing to resolve the link between the Interaction JSON and the ProjectileConfig JSON.
  - Attempted Fixes: Moved configs from subfolders to the root `ProjectileConfigs` directory, but the resolution error persists.
