# Hytale Contest PvE - Developer/Tester Guide

This plugin currently has 4 main systems:
- Run session flow (start run, timer, extract/end run world)
- Rescue objective (escort blacksmith in run -> transfer to base on extraction)
- Crimson wave (area spread + undo)
- Base housing plots (profession-locked plots + auto-assignment + workshop state flow)

## Quick smoke test (recommended)
1. Make sure your worlds exist and are loaded:
   - Hub world name (default): `hub`
   - Run template world name (default): `game`
2. In hub world, place a `Game_Start_Door` block.
3. Set required spawns:
   - `/setrunspawn` (run start location)
   - `/setbasespawn` (hub return location)
   - Optional: `/setrescuespawn` (fixed rescue NPC point in run)
4. Press `F` on `Game_Start_Door` in hub to start a run.
5. In run world, interact with the rescue blacksmith NPC so they follow.
6. Return to the run door and press `F` to extract.
7. Confirm you return to hub with your inventory preserved and blacksmith is marked rescued.
8. Run `/blacksmith status` to verify state.

## System behavior details

### Run session + door flow
- Door block id: `Game_Start_Door`
- Press `F` on the door:
  - In hub world: starts a run (copies template world to a temporary run world).
  - In run world: extracts and ends the run world.
- Run duration is 5 minutes with a timer HUD in the run world.
- If crimson selection exists for the starter in the template world, crimson auto-starts during the run.
- Death behavior in run:
  - If starter dies during an active run, the run is ended.
  - Player is returned to hub/base spawn.
  - Inventory is wiped on death return.
- Extraction behavior in run:
  - If starter extracts via door, run ends and return keeps inventory.

### Rescue objective flow
- Run objective role: `Blacksmith_Escort_Objective`
- Base resident role: `Blacksmith_Escort_Base`
- Rescue transfer only queues on extraction if the objective NPC is actively following.
- Base blacksmith interaction opens blacksmith dialogue UI.
- If player dies in run, escorted NPC is not transferred to hub and runtime rescue state is reset.

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
  - One NPC per profession. The blacksmith profession has one working blacksmith.

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
- `/blacksmith status`
  - Dumps rescue/runtime/config status.
- `/blacksmith setspawn <run|base|rescue>`
  - Shortcut to set run/base/rescue spawn from your current location.
- `/blacksmith spawn <run|base>`
  - Forces spawn of rescue objective (run) or base blacksmith (base).
- `/blacksmith rescued <true|false>`
  - Manually sets rescued progression flag.
- `/blacksmith reset`
  - Clears runtime rescue state and sets rescued=false.
- `/blacksmith resetall`
  - Reset rescue state and remove base blacksmith entities in current world.
- `/spawnblacksmith`
  - Spawns `Blacksmith_Escort_Base` at your location.
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
- `/npcdev hud [on|off]`
  - Toggle or set live NPC debug HUD.
- `/npcdev state <profession> <wandering|moving|working>`
  - Force an NPC state for testing.
- `/npcdev assign <profession> <plotId>`
  - Force-assign NPC to plot.
- `/npcdev unassign <profession|plotId>`
  - Clear assignment.
- `/npcdev dump`
  - Print current NPC data.
- `/npcdev reset`
  - Reset NPC dev state.
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
  Places a `Crimson_Core` under the player and sets it as current selection.
- `/redradius <blocks>`  
  Sets expansion radius for the current selected core.
- `/redstart <seconds>`  
  Starts crimson spread from the selected core over total duration.
- `/redundo`  
  Reverts previous crimson conversions (chunk batches).

## Crimson multi-core workflow (UI)
1. Run `/redui` to open the panel.
2. Press **Set Core** at each location where you want a new `Crimson_Core`.
3. Use left/right side buttons to switch active core.
4. Radius and start seconds in the panel apply to the currently selected core only.
5. **Start Wave** starts spread only for the selected core.
6. **Undo** reverts only the selected core history.
7. **Global Undo** reverts crimson conversions for all cores and blocks new wave starts until it finishes.

### Misc command
- `/example`
  - Test command that prints a hello message.

## Tester scenarios

### Test crimson manually
1. In the target world, run `/redpos1` and `/redpos2`.
2. Run `/redstart 30`.
3. Confirm block conversion progresses.
4. Run `/redundo` and confirm blocks are restored.

### Test base plot assignment
1. Place `Base_Plot_Marker` blocks where desired.
2. Register one with `/baseplot add smithPlot`.
3. Reserve the plot for blacksmith with `/baseplot settype smithPlot blacksmith`.
4. Ensure blacksmith is rescued (`/blacksmith rescued true` for fast test).
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
  - `game-flow.properties` (world names, spawns, rescued flag)
  - `base-housing.properties` (plot metadata, type, purchase, assignment, level)
  - `hub-npcs.properties` (hub NPC state/data)
