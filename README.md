# Hytale Contest PvE - Developer/Tester Guide

This plugin currently has 4 main systems:
- Run session flow (start run, timer, extract/end run world)
- Rescue objective (escort blacksmith in run -> transfer to base on extraction)
- Crimson wave (area spread + undo)
- Base housing plots (assign rescued NPCs to plot markers)

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
7. Confirm you return to hub and blacksmith is marked rescued (and spawned in base).
8. Run `/blacksmith status` to verify state.

## System behavior details

### Run session + door flow
- Door block id: `Game_Start_Door`
- Press `F` on the door:
  - In hub world: starts a run (copies template world to a temporary run world).
  - In run world: extracts and ends the run world.
- Run duration is 5 minutes with a timer HUD in the run world.
- If crimson selection exists for the starter in the template world, crimson auto-starts during the run.

### Rescue objective flow
- Run objective role: `Blacksmith_Escort_Objective`
- Base resident role: `Blacksmith_Escort_Base`
- Rescue transfer only queues on extraction if the objective NPC is actively following.
- Base blacksmith interaction opens blacksmith dialogue UI.

### Base housing plots
- Marker block id: `Base_Plot_Marker`
- `marker` = the block you click to open plot UI
- `home` = where the NPC stands/lives after assignment
- Interacting a marker:
  - If marker is not registered as a plot: opens plot terminal UI.
  - If marker matches a plot: opens assignment UI for that plot.
- Assigning blacksmith to a plot builds a house at the marker and ensures blacksmith is placed at plot home.

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
- `/baseplot clearassign <id>`
  - Clears plot assignment.
- `/baseplot resetall`
  - Clears all plots/assignments and rescue runtime (rescued flag is preserved).

### Crimson commands
- `/redpos1 [x y z]`
  - Sets crimson selection corner 1 (current feet block if omitted).
- `/redpos2 [x y z]`
  - Sets crimson selection corner 2 (current feet block if omitted).
- `/redstart <seconds>`
  - Starts crimson spread over selected region in current world.
- `/redundo`
  - Restores blocks from last crimson conversion in current world.

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
3. Ensure blacksmith is rescued (`/blacksmith rescued true` for fast test).
4. Interact marker and assign blacksmith in UI.
5. Confirm house is built and blacksmith appears at plot home.

## Persistence notes
- Run sessions are temporary and cleaned up when ended.
- Some config/state is persisted under universe plugin config:
  - `game-flow.properties` (world names, spawns, rescued flag)
  - `base-housing.properties` (plot metadata/assignments)
