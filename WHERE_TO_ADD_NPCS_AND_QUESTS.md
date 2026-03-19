# Where To Add NPCs And Quests

Use this as the implementation map when adding/removing content.

## Add a new NPC
1. Register archetype
- File: `src/main/resources/Common/NpcData/npc-archetypes.properties`
- Add NPC key to `npcs=...`
- Add `npc.<key>.*` fields (`category`, `hubRole`, `plotType`, `services`, etc.)

2. Add NPC economy/content file
- File: `src/main/resources/Common/NpcData/npcs/<npcKey>.properties`
- Define:
  - `offers=...`
  - `offer.<id>.*` entries (cost/reward/type/requirements)
  - `upgrades=...`
  - `upgrade.<id>.*` entries (tier/cost/grants/flags)

3. Register run-rescue spawn (if this NPC is rescuable in runs)
- File: `src/main/resources/Common/NpcData/run-rescue-spawns.properties`
- Add key to `rescue.npcs=...`
- Add `rescue.<key>.enabled=true|false`
- Add `rescue.<key>.templateWorld=<templateWorldName>`
- Optional fixed point:
  - `rescue.<key>.x/y/z`
  - `rescue.<key>.yaw/pitch/roll`

4. Ensure role exists in assets
- NPC role JSONs under `src/main/resources/Server/NPC/Roles/...`
- `hubRole` / `runRescueRole` must match loaded role names.

5. Test in game
- `/npcdev rescue <npcKey> true`
- `/npcdev dump`
- If plot-based: `/baseplot settype <plotId> <plotType>` then purchase/assign flow

## Add a new quest (NPC or non-NPC)
1. Register global quest definition
- File: `src/main/resources/Common/QuestData/quest-definitions.properties`
- Add quest id to `quests=...`
- Add `quest.<id>.*` fields (`category`, `title`, `summary`, `sourceType`, `sourceId`, rewards)

2. If NPC-sourced quest
- `sourceType=npc`
- `sourceId=<npcKey>`
- Quest will appear in generic NPC quest UI for that NPC key.

3. If quest should unlock NPC functionality
- Use reward keys:
  - `quest.<id>.rewards.unlockCrafts=...`
  - `quest.<id>.rewards.unlockTrades=...`
  - `quest.<id>.rewards.setFlags=...`
  - `quest.<id>.rewards.rescueNpcs=...` (optional)
  - `quest.<id>.rewards.autoAcceptNext=true|false` (optional)

4. Test in game
- `/questdev accept <questId>`
- `/questdev complete <questId>`
- `/questdev flags`

## Core code touchpoints (if behavior changes are needed)
- NPC runtime: `src/main/java/dev/hytalemodding/npc/*`
- Quest runtime: `src/main/java/dev/hytalemodding/quest/*`
- Hub interactions: `src/main/java/dev/hytalemodding/state/hub/*`
- Run interactions/objective: `src/main/java/dev/hytalemodding/state/run/*`
- Transfer flow: `src/main/java/dev/hytalemodding/state/transition/*`
- Housing assignment/spawn: `src/main/java/dev/hytalemodding/domain/housing/BaseHousingManager.java`
