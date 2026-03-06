# Hytale Contest PvE - Dev Run Guide

## Run the server
- Join the default world
- Build/edit your map in the default world

## How to run a game (door flow)
1. Place the custom door block `Game_Start_Door` where you want start/extract interaction
2. Stand at your desired run (raid) start point and run `/setrunspawn`
3. Stand at your desired base return point and run `/setbasespawn`
4. Stand where the rescue NPC should appear during a run and run `/setrescuespawn`
5. If crimson is enabled for this run, set the area with:
   - `/redpos1` to set at your feet (or `/redpos1 x y z`)
   - `/redpos2` to set at your feet (or `/redpos2 x y z`)
6. Use `F` on the door block:
   - In base world: starts a run (copies world into a run session).
   - In run world: extracts back to base.

## Core game commands
- `/gamestart [templateWorld]`  
  Starts a run manually from the template world (defaults to current world).
- `/gameend`  
  Ends the active run and cleans up the run world.
- `/gamereset [templateWorld]`  
  Ends active run (if any) and starts a new one.

## Spawn setup commands
- `/setrunspawn`  
  Sets player spawn used when run starts.
- `/setbasespawn`  
  Sets player spawn used when extracting back to base.
- `/setrescuespawn`  
  Sets rescue NPC spawn position in the run world.

## Rescue/NPC commands
- `/spawnblacksmith`  
  Spawns the base blacksmith role (`Blacksmith_Escort_Base`) for testing.
- `/npcspawn [role]`  
  Spawns any role (default is `Blacksmith_Escort_Base`).
- `/npcroles [filter]`  
  Lists loaded roles (optional substring filter).

## Crimson infection commands
- `/redpos1 [x y z]`  
  Sets infection area corner 1 (uses your feet position if args omitted).
- `/redpos2 [x y z]`  
  Sets infection area corner 2 (uses your feet position if args omitted).
- `/redstart <seconds>`  
  Starts spread in selected area over total duration.
- `/redundo`  
  Reverts the last crimson conversion in this world.

## Notes
- Rescue uses two roles:
  - Run objective NPC: `Blacksmith_Escort_Objective` (interactive/follow behavior).
  - Base NPC: `Blacksmith_Escort_Base` (non-interactive base resident).
- Nothing is persisted by design for this MVP (server restart resets session state).
