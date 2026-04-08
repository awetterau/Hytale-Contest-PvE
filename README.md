# Hytale Contest PvE - Developer/Tester Guide

This plugin currently has 4 main systems:
- Run session flow (start run, timer, extract/end run world)
- Rescue objective (escort a configured rescue NPC in run -> transfer to base on extraction)
- Crimson wave (area spread + undo)
- Base housing plots (profession-locked plots + auto-assignment + workshop state flow)
- NPC economy/progression (trade, upgrades, quest unlocks) driven by per-NPC properties files

## Code layout (current)
- `src/main/java/dev/hytalemodding/state/run`
  - Run-world state logic (session, run door flow, run rescue objective, run death handling).
- `src/main/java/dev/hytalemodding/state/hub`
  - Hub-world state logic (plot interaction handlers, hub watcher systems).
- `src/main/java/dev/hytalemodding/state/transition`
  - Run <-> hub transfer/config orchestration (shared flow state + transfer helpers).
- `src/main/java/dev/hytalemodding/domain/housing`
  - Shared housing domain logic (plots, assignment, workshop placement/spawn sync).
- `src/main/java/dev/hytalemodding/npc`
  - NPC domain (archetypes, role mapping, dialogue manager, progression unlocks).
- `src/main/java/dev/hytalemodding/npc/economy`
  - Data-driven NPC economy (offers, upgrades, inventory transaction execution).
- `src/main/java/dev/hytalemodding/quest`
  - Quest domain (definitions, progress, flags, reward application).
- `src/main/java/dev/hytalemodding/ui/hub`, `ui/dev`, `ui/npc`
  - Active UI pages grouped by use.
- `src/main/java/dev/hytalemodding/commands/run|hub|npc|quest|dev|redwave`
  - Command groups by subsystem.

## Quick smoke test (recommended)
1. Make sure your worlds exist and are loaded:
   - Hub world name (default): `hub`
   - Run template world name (default): `game`
2. In hub world, place a `Game_Start_Door` block.
3. Set required spawns:
   - `/spawnui` (run start location)
   - `/setbasespawn` (hub return location)
   - Optional: `/setrescuespawn` (fixed rescue NPC point in run)
4. Press `F` on `Game_Start_Door` in hub to start a run.
5. In run world, interact with the active rescue NPC so they follow.
6. Return to the run door and press `F` to extract.
7. Confirm you return to hub with your inventory preserved and that NPC is marked rescued.
8. Run `/npcadmin` if you need to inspect NPC state, validation, or reset an NPC to unified defaults during testing.

## System behavior details

### Spawn zone system + SpawnUI workflow
- Spawn zones are now organized as:
  - **Zone** -> top-level group used by the door selector.
  - **Location** -> sub-group inside a zone used to cluster multiple spawn points.
  - **SpawnPoint_Block entries** -> actual spawn blocks registered under one zone/location.
- Persistence file:
  - `SpawnPoint_Zones.properties`
  - Stores dynamic zone data by world, including zone count, per-zone location count, and ordered spawn entries (`x/y/z/dimension`).
- Editing permissions:
  - Spawn editing is currently allowed only in editable worlds.
  - Default editable world allowlist: `game`.
  - If you open the editor outside that allowlist, it shows a locked page and does **not** register or reconcile spawn data for that world.

#### How the run spawn selection works
1. In hub, run `/spawnui`.
2. In the **Door Run Zone** page, choose the zone you want the next `Game_Start_Door` run to use.
3. That door-zone selection is temporary and is consumed when the run starts.
4. When the door starts a run, the system:
   - uses the selected zone,
   - chooses a **location** inside that zone with weighted randomness,
   - gives lower probability to the same location that player used previously in that zone,
   - reserves a unique spawn point inside the chosen location,
   - and avoids handing the same exact spawn point to another player while reserved.
5. If run start fails, the reservation is released. After extraction, the reserved spawn is also released.

#### How to edit spawn data with `/spawnui`
1. Go to an editable world (currently `game`).
2. Run `/spawnui`.
3. In **Door Run Zone**, press **Edit Zones**.
4. In the spawn editor:
   - use the **zone buttons** to switch active zone,
   - use the small `+` / `-` buttons above zones to add/remove zones,
   - use the **location buttons** to switch active location inside the active zone,
   - use the small `+` / `-` buttons above locations to add/remove locations for the active zone only,
   - use **Set SpawnPoint** to place a `SpawnPoint_Block` under your player and register it into the active zone/location.
5. The central panel shows:
   - the active zone/location,
   - the number of registered blocks in that location,
   - and the ordered list of registered coordinates.
6. Use **Door Run Zone** to go back to the temporary door-zone selector.

### NPC economy config (current)
- Economy is defined per NPC under:
  - `src/main/resources/Common/NpcData/npcs/<npcKey>.properties`
- Blacksmith currently uses:
  - `src/main/resources/Common/NpcData/npcs/blacksmith.properties`
- Pack goat currently uses:
- Legacy split NPC economy files (`craft_sets`, `trade_sets`, `upgrade_trees`) are removed from active workflow.
- NPC dialogue action menu is currently:
  - `Trade`, `Upgrades`, `Quests`, `Close`
- Trade/Upgrades/Quests require the NPC to have a purchased workshop assignment.

### Run session + door flow
- Door block id: `Game_Start_Door`
- Press `F` on the door:
  - In hub world: starts a run (copies template world to a temporary run world).
  - In run world: extracts and ends the run world.
- Run duration is 5 minutes with a timer HUD in the run world.
- Crimson cores are loaded from persisted multi-core profiles and auto-start during the run after a delay.
- Current default delay before crimson starts: **15 seconds** after run start.
- Death behavior in run:
  - If starter dies during an active run, the run is ended.
  - Player is returned to hub/base spawn.
  - Inventory is wiped on death return.
- Extraction behavior in run:
  - If starter extracts via door, run ends and return keeps inventory.

### Rescue objective flow
- Run objective role: taken from `npc-archetypes.properties` (`runRescueRole`).
- Base resident role: taken from `npc-archetypes.properties` (`hubRole`).
- Rescue transfer queues on extraction when escort was confirmed (follow interaction) or NPC is in a follow state.
- Base rescued NPC interaction opens that NPC's dialogue/UI.
- If player dies in run, escorted NPC is not transferred to hub and runtime rescue state is reset.
- Rescue NPC registration/spawn is data-driven via:
  - `src/main/resources/Common/NpcData/npc-archetypes.properties` (roles/service metadata)
  - `src/main/resources/Common/NpcData/run-rescue-spawns.properties` (run spawn registration/points)

### Base housing plots
- Marker block id: `Base_Plot_Marker`
- `marker` = the block you click to open plot UI
- `home` = where the NPC stands/lives after assignment
- Interacting a marker:
  - If marker is not registered as a plot: opens plot terminal UI.
  - If marker matches a plot: opens plot purchase/management UI for that fixed plot.
- Plot data includes:
  - `plotType` (profession key this plot accepts, e.g. `blacksmith`)
  - `purchased`
  - `assignedNPC`
  - `buildingLevel`
- Purchase flow:
  - Purchasing a plot auto-assigns matching rescued NPC (if available).
  - NPC moves to workshop/home and enters working state.
- Rule:
  - One NPC per profession key.

## Command reference

### Core run commands
- `/gamestart [templateWorld]`
  - Manually starts a run from template world.
- `/gameend`
  - Ends current run and removes run world.
- `/gamereset [templateWorld]`
  - Ends active run (if any), then starts a new run.

### Config + spawn commands
- `/gameconfig list`
  - Shows template/hub world, spawns, door block, rescued state.
- `/gameconfig template <worldName>`
  - Sets template world name.
- `/gameconfig hub <worldName>`
  - Sets hub world name.
- `/gameconfig clear <run|base|rescue|all>`
  - Clears saved spawn points.
- `/setrunspawn`
  - Sets run spawn using your current transform.
- `/setbasespawn`
  - Sets hub return spawn using your current transform.
- `/setrescuespawn`
  - Sets fixed run rescue spawn using your current transform.

### Rescue + NPC commands
- `/npcspawn [role]`
  - Spawns any loaded spawnable NPC role (default `Blacksmith_Escort_Base`).
- `/npcroles [filter]`
  - Lists loaded roles (up to 50; optional contains filter).

### Base plot commands
- `/baseplot list`
  - Lists all plots.
- `/baseplot add <id> [x y z]`
  - Create/update a plot and set its marker block position. If `x y z` is not given, it uses the block under your feet.
- `/baseplot remove <id>`
  - Deletes a plot.
- `/baseplot sethome <id>`
  - Set NPC home for this plot to your current position and facing direction.
- `/baseplot settype <id> <plotType>`
  - Sets which profession this plot is reserved for (example: `blacksmith`).
- `/baseplot clearassign <id>`
  - Clears plot assignment.
- `/baseplot resetall`
  - Clears all plots/assignments and rescue runtime (rescued flag is preserved).

### Dev/testing commands
- `/devpanel`
  - Opens the in-game Dev Admin Panel for step-by-step flow testing.
- `/npcadmin`
  - Opens the unified NPC admin flow for browsing NPCs, inspecting state, validating setup, and resetting one NPC to unified defaults.
- `/questdev list`
  - Print all registered quests with accept/complete state.
- `/questdev accept <questId>`
  - Mark a quest as accepted.
- `/questdev complete <questId>`
  - Mark a quest complete and apply reward effects (flags/unlocks/next quest).
- `/questdev reset <questId>`
  - Reset one quest progress entry.
- `/questdev reload`
  - Reload quest definitions (current implementation note: restart may still be required for full live refresh).
- `/questdev flags`
  - Print active quest flags.
- `/plotdev purchase <plotId>`
  - Mark plot purchased (dev shortcut).
- `/plotdev unpurchase <plotId>`
  - Mark plot unpurchased.
- `/plotdev settype <plotId> <plotType>`
  - Set plot profession type.
- `/plotdev setlevel <plotId> <level>`
  - Force building level.
- `/plotdev dump`
  - Print current plot data.

## Crimson infection commands
- `/redui`  
  Opens/closes the Red control page.

- `/redcore`  
  Places a `Crimson_Core` under the player and saves it as a crimson candidate location.
- `/redradius <blocks>`  
  Sets expansion radius for the current selected core.
- `/redstart <seconds>`  
  Starts crimson spread from the selected core over total duration.
- `/redundo`  
  Reverts previous crimson conversions (chunk batches).

## Crimson multi-core workflow (UI)
1. Run `/redui` to open the panel.
2. Press **Set Core** at each location where you want a candidate `Crimson_Core`.
3. Use left/right side buttons to switch the selected candidate.
4. Radius and spread seconds in the panel apply to the currently selected candidate only.
5. Use the active-core-count controls to choose how many saved candidates should activate in each run.
6. On run start, the system randomly picks that many saved candidates from the template world and only those selected cores spread crimson in the run.
7. **Start Wave** starts spread only for the selected candidate.
8. **Undo** reverts only the selected candidate history.
9. **Global Undo** reverts crimson conversions for all active cores and blocks new wave starts until it finishes.

### Misc command
- `/example`
  - Test command that prints a hello message.

## Tester scenarios

### Test base plot assignment
1. Place `Base_Plot_Marker` blocks where desired.
2. Register one with `/baseplot add smithPlot`.
3. Reserve the plot for blacksmith with `/baseplot settype smithPlot blacksmith`.
4. Ensure blacksmith is configured/rescued through the unified admin panel (`/npcadmin`) for fast test setup.
5. Interact marker and purchase the plot.
6. Confirm house is built and blacksmith appears at plot home/workshop.

### Fast dev flow (recommended)
1. Run `/devpanel`.
2. Use panel buttons in order:
   - Reset Flow
   - Setup Plot Here
   - Rescued TRUE
   - Purchase Plot
3. Confirm NPC state transitions to `WORKING`.
4. Interact blacksmith and verify workshop/quest pages only appear when plot is assigned.

### Quest + NPC unlock smoke test
1. Open `/npcadmin` and inspect/reset blacksmith as needed before quest testing.
2. Run `/questdev accept ember_core_hunt`.
3. Run `/questdev complete ember_core_hunt`.
4. Interact blacksmith and confirm new trade/upgrades unlock effects are available in NPC UI.
5. Run `/questdev flags` and confirm `blacksmith_tempered_unlocked` is present.

### Test run death behavior
1. Start a run from hub door.
2. Die in the run world.
3. Confirm:
   - You are returned to hub/base spawn.
   - Active run world is ended/cleaned up.
   - Inventory is wiped.
   - Escorted rescue NPC does not transfer and returns to hub wandering behavior.

## Persistence notes
- Run sessions are temporary and cleaned up when ended.
- Some config/state is persisted under universe plugin config:
  - `game-flow.properties` (world names, spawns, rescued NPC keys)
  - `base-housing.properties` (plot metadata, type, purchase, assignment, level)
  - `npc-state-v2.properties` (unified NPC runtime state used by the new admin/runtime layer)
  - `hub-npcs.properties` (hub NPC state/data)
  - `npc-progress.properties` (rescued/progression + unlocks by NPC key)
  - `quest-progress.properties` (quest accepted/completed state)
  - `quest-flags.properties` (global quest flags)
