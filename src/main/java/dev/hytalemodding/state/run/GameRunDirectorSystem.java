package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blob.OrangeBlobBlockManager;
import dev.hytalemodding.blob.FlareExtractionManager;
import dev.hytalemodding.hud.GameTimerHud;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.ui.dev.BlightfallMainPage;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import com.hypixel.hytale.math.vector.Vector3i;
import java.util.List;
import java.util.Set;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class GameRunDirectorSystem extends TickingSystem<EntityStore> {
    private static final String WEAK_RUNTIME_CORE_BLOCK_ID = "Crimson_Core_Weak";
    private static final int CHUNK_SIZE_BLOCKS = 32;
    private static final String POTION_BREWER_WITCH_ROLE = "Potion_Brewer_Witch";
    private static final Vector3d WITCH_SPAWN_POS = new Vector3d(-2, 225, -90);
    private final ConcurrentHashMap<UUID, GameTimerHud> timerHuds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastShownSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<Integer>> executedAutomationByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Integer>> failedAutomationByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, CoreGrowthQueue>> pendingGrowthByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, java.util.Map<String, SeededGrowEvent>> activeSeededGrowByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, java.util.Set<String>> seededGrowMainCoreHistoryByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<ChunkReachCacheKey, java.util.Set<RunChunkSelectionManager.ChunkPosKey>>> seededGrowChunkCacheByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> witchSpawnedInWorld = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> currentFormattedTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, java.util.Set<String>> SEEDED_GROW_SEED_KEYS_BY_WORLD = new ConcurrentHashMap<>();
    private static final long GROWTH_STAGE_INTERVAL_MS = 500L;
    private static final float GROWTH_EXPANSION_PROGRESS_THRESHOLD = 0.96f;
    private static Vector3d witchMarkerPos = null;

    public static String getFormattedTime(@Nonnull UUID playerId) {
        return currentFormattedTime.getOrDefault(playerId, "00:00");
    }

    public static boolean isSeededGrowSeedCore(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        java.util.Set<String> keys = SEEDED_GROW_SEED_KEYS_BY_WORLD.get(worldId);
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        return keys.contains(growthKey(corePos));
    }

    public static void clearSeededGrowSeedCore(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        java.util.Set<String> keys = SEEDED_GROW_SEED_KEYS_BY_WORLD.get(worldId);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        keys.remove(growthKey(corePos));
        if (keys.isEmpty()) {
            SEEDED_GROW_SEED_KEYS_BY_WORLD.remove(worldId);
        }
    }

    private static void markSeededGrowSeedCore(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        SEEDED_GROW_SEED_KEYS_BY_WORLD
                .computeIfAbsent(worldId, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(growthKey(corePos));
    }

    private static void clearSeededGrowSeedCores(@Nonnull UUID worldId) {
        SEEDED_GROW_SEED_KEYS_BY_WORLD.remove(worldId);
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            hideAllTimerHuds();
            this.executedAutomationByWorld.clear();
            this.failedAutomationByWorld.clear();
            this.pendingGrowthByWorld.clear();
            this.activeSeededGrowByWorld.clear();
            this.seededGrowMainCoreHistoryByWorld.clear();
            this.seededGrowChunkCacheByWorld.clear();
            this.witchSpawnedInWorld.clear();
            SEEDED_GROW_SEED_KEYS_BY_WORLD.clear();
            return;
        }
        if (snapshot.phase() != GameSessionManager.RunPhase.EXPLORATION
                && snapshot.phase() != GameSessionManager.RunPhase.CRIMSON_ACTIVE) {
            hideAllTimerHuds();
            this.executedAutomationByWorld.clear();
            this.failedAutomationByWorld.clear();
            this.pendingGrowthByWorld.clear();
            this.activeSeededGrowByWorld.clear();
            this.seededGrowMainCoreHistoryByWorld.clear();
            this.seededGrowChunkCacheByWorld.clear();
            this.witchSpawnedInWorld.remove(snapshot.runWorldUuid());
            clearSeededGrowSeedCores(snapshot.runWorldUuid());
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            return;
        }

        GameSessionManager.get().refreshParticipantConnectionStates();
        long remainingMs = Math.max(0L, snapshot.runEndsAtEpochMillis() - System.currentTimeMillis());
        updateRunWorldTimerHud(worldId, remainingMs);
        processAutomatedActions(world, snapshot);
        processPendingGrowth(world);
        processSeededGrowEvents(world);

        // End run when time expires, wiping inventory like death
        if (remainingMs <= 0) {
            sendRunWorldMessage(worldId, "Time's up! Returning to hub.");
            GameSessionManager.get().endSessionAndWipeInventory(null, null);
            return;
        }

        if (GameSessionManager.get().shouldAutoEndForParticipants()) {
            sendRunWorldMessage(worldId, "All run participants are marked as extracted or dead. Ending run.");
            GameSessionManager.get().endSession(null, null);
            return;
        }

        if (GameSessionManager.get().shouldAutoEndForEmptyRunWorld()) {
            sendRunWorldMessage(worldId, "Run world has been empty for 20 seconds. Ending run.");
            GameSessionManager.get().endSession(null, null);
            return;
        }

        // Spawn Potion Brewer Witch when save_the_farm_animals quest is completed
        if (!this.witchSpawnedInWorld.getOrDefault(worldId, false) &&
                QuestProgressManager.get().isCompleted("save_the_farm_animals")) {
            trySpawnWitch(world, worldId);
        }

        if (snapshot.crimsonEnabled() && snapshot.phase() == GameSessionManager.RunPhase.EXPLORATION && GameSessionManager.get().shouldActivateCrimson()) {
            if (RedWaveManager.getActiveWave(worldId) == null) {
                int started = 0;
                for (RedCoreProfileRegistry.RedCoreProfile profile : snapshot.crimsonProfiles()) {
                    var coreType = world.getBlockType(profile.corePos().x, profile.corePos().y, profile.corePos().z);
                    if (coreType == null || !RedWaveConfig.isCoreBlockId(coreType.getId())) {
                        continue;
                    }
                    if (RedWaveManager.isUndoRecordingEnabled()) {
                        RedWaveManager.beginUndoSession(worldId, profile.corePos());
                    }
                    RedWaveManager.startWave(worldId, profile.corePos(), profile.radiusBlocks(), profile.startSeconds());
                    started++;
                }
                if (started > 0) {
                    sendRunWorldMessage(worldId, "Crimson infection is spreading. Return to base.");
                } else {
                    sendRunWorldMessage(worldId, "No valid crimson core blocks found in run world.");
                }
            }
            GameSessionManager.get().markCrimsonActive();
        }
    }


    private void processAutomatedActions(@Nonnull World world, @Nonnull GameSessionManager.ActiveSessionSnapshot snapshot) {
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - snapshot.startedAtEpochMillis()) / 1000L);
        List<BlightfallMainPage.RuntimeAction> actions = resolveRuntimeActions(world, snapshot);
        if (actions.isEmpty()) {
            return;
        }

        UUID worldId = world.getWorldConfig().getUuid();
        Set<Integer> executed = this.executedAutomationByWorld.computeIfAbsent(worldId, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        ConcurrentHashMap<Integer, Integer> failedCounts = this.failedAutomationByWorld.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());

        for (int i = 0; i < actions.size(); i++) {
            BlightfallMainPage.RuntimeAction action = actions.get(i);
            if (!action.enabled() || action.triggerSecond() > elapsedSeconds || executed.contains(i)) {
                continue;
            }
            int roll = ThreadLocalRandom.current().nextInt(100);
            if (roll >= Math.max(0, Math.min(100, action.probabilityPercent()))) {
                sendStatusIfEnabled(worldId, "[InfectionAction] Skipped by probability for action #" + i + ".");
                executed.add(i);
                continue;
            }

            boolean performed = switch (action.actionType().toLowerCase()) {
                case "spawn" -> executeSpawnAction(worldId, world, action);
                case "grow" -> executeGrowAction(worldId, world, action);
                case "seeded_grow" -> executeSeededGrowAction(worldId, world, action);
                default -> false;
            };
            if (performed) {
                sendStatusIfEnabled(worldId, "[InfectionAction] Executed " + action.actionType() + " (" + action.coreTier() + ") at t=" + elapsedSeconds + "s");
                executed.add(i);
                failedCounts.remove(i);
            } else {
                int failures = failedCounts.merge(i, 1, Integer::sum);
                if (failures >= 5) {
                    sendStatusIfEnabled(worldId, "[InfectionAction] Failed " + action.actionType() + " (" + action.coreTier() + ") 5 times. Disabling this action for current run.");
                    executed.add(i);
                } else {
                    sendStatusIfEnabled(worldId, "[InfectionAction] Failed " + action.actionType() + " (" + action.coreTier() + ") - no valid targets (" + failures + "/5).");
                }
            }
        }
    }

    private boolean executeSpawnAction(@Nonnull UUID worldId, @Nonnull World world, @Nonnull BlightfallMainPage.RuntimeAction action) {
        List<Vector3i> anchors = "weak".equalsIgnoreCase(action.coreTier())
                ? InfectionCoreRegistry.snapshotWeakPositions(worldId)
                : InfectionCoreRegistry.snapshotCorePositions(worldId);
        if (anchors.isEmpty()) {
            return false;
        }
        Vector3i target = anchors.get(ThreadLocalRandom.current().nextInt(anchors.size()));
        String blockId = "weak".equalsIgnoreCase(action.coreTier()) ? WEAK_RUNTIME_CORE_BLOCK_ID : RedWaveConfig.CORE_BLOCK_ID;
        world.setBlock(target.x, target.y, target.z, blockId);
        RunEnvironmentPainter.paintColumnForRunBlock(world, target.x, target.y, target.z);
        RedCoreRegistry.register(worldId, target);
        if (RedWaveManager.isUndoRecordingEnabled()) {
            RedWaveManager.beginUndoSession(worldId, target);
        }
        int targetRadius = Math.max(1, action.radius());
        float targetSeconds = Math.max(0.1f, action.ticksPerBlock() / 20.0f);
        RedWaveManager.startWave(worldId, target, targetRadius, targetSeconds);
        return true;
    }

    private boolean executeGrowAction(@Nonnull UUID worldId, @Nonnull World world, @Nonnull BlightfallMainPage.RuntimeAction action) {
        List<Vector3i> cores = RedCoreRegistry.snapshot(worldId).stream()
                .filter(pos -> {
                    var bt = world.getBlockType(pos.x, pos.y, pos.z);
                    if (bt == null) {
                        return false;
                    }
                    if ("weak".equalsIgnoreCase(action.coreTier())) {
                        return WEAK_RUNTIME_CORE_BLOCK_ID.equals(bt.getId());
                    }
                    return RedWaveConfig.CORE_BLOCK_ID.equals(bt.getId());
                })
                .toList();
        Vector3i target;
        if (cores.isEmpty()) {
            List<Vector3i> anchors = "weak".equalsIgnoreCase(action.coreTier())
                    ? InfectionCoreRegistry.snapshotWeakPositions(worldId)
                    : InfectionCoreRegistry.snapshotCorePositions(worldId);
            if (anchors.isEmpty()) {
                return false;
            }
            target = anchors.get(ThreadLocalRandom.current().nextInt(anchors.size()));
            String coreBlockId = "weak".equalsIgnoreCase(action.coreTier()) ? WEAK_RUNTIME_CORE_BLOCK_ID : RedWaveConfig.CORE_BLOCK_ID;
            world.setBlock(target.x, target.y, target.z, coreBlockId);
            RunEnvironmentPainter.paintColumnForRunBlock(world, target.x, target.y, target.z);
            RedCoreRegistry.register(worldId, target);
        } else {
            target = cores.get(ThreadLocalRandom.current().nextInt(cores.size()));
        }

        RedWaveManager.ActiveWave activeBeforeReset = RedWaveManager.getActiveWave(worldId, target);
        int activeRadiusBeforeReset = activeBeforeReset == null ? 0 : activeBeforeReset.radiusBlocks();

        // Restart grow from zero by replacing the core and stopping any active spread for this core.
        String coreBlockId = "weak".equalsIgnoreCase(action.coreTier()) ? WEAK_RUNTIME_CORE_BLOCK_ID : RedWaveConfig.CORE_BLOCK_ID;
        RedWaveManager.clearWave(worldId, target);
        world.setBlock(target.x, target.y, target.z, coreBlockId);
        RunEnvironmentPainter.paintColumnForRunBlock(world, target.x, target.y, target.z);
        RedCoreRegistry.register(worldId, target);
        ConcurrentHashMap<String, CoreGrowthQueue> pendingByCore = this.pendingGrowthByWorld.get(worldId);
        if (pendingByCore != null) {
            pendingByCore.remove(growthKey(target));
        }

        int growthRadius = Math.max(1, action.radius());
        float targetSeconds = Math.max(0.1f, action.ticksPerBlock() / 20.0f);
        sendRunWorldMessage(
                worldId,
                "[GrowDebug] tier=" + action.coreTier()
                        + " selectedCore=" + target.x + "," + target.y + "," + target.z
                        + " activeBeforeReset=" + (activeBeforeReset != null)
                        + " activeRadiusBeforeReset=" + activeRadiusBeforeReset
                        + " requestedRadius=" + action.radius()
                        + " appliedRadius=" + growthRadius
                        + " ticksPerBlock=" + action.ticksPerBlock()
                        + " appliedSeconds=" + targetSeconds
        );
        sendStatusIfEnabled(worldId, "[InfectionAction] Grow restarted core at " + target.x + "," + target.y + "," + target.z + " targetRadius=" + growthRadius + ".");
        if (RedWaveManager.isUndoRecordingEnabled()) {
            RedWaveManager.beginUndoSession(worldId, target);
        }
        RedWaveManager.startWave(worldId, target, growthRadius, targetSeconds, true);
        return true;
    }

    private boolean executeSeededGrowAction(@Nonnull UUID worldId, @Nonnull World world, @Nonnull BlightfallMainPage.RuntimeAction action) {
        List<Vector3i> cores = RedCoreRegistry.snapshot(worldId).stream()
                .filter(pos -> {
                    var bt = world.getBlockType(pos.x, pos.y, pos.z);
                    return bt != null && RedWaveConfig.CORE_BLOCK_ID.equals(bt.getId());
                })
                .toList();
        if (cores.isEmpty()) {
            return false;
        }
        java.util.Set<String> usedMainCores = this.seededGrowMainCoreHistoryByWorld
                .computeIfAbsent(worldId, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        List<Vector3i> availableCores = new java.util.ArrayList<>();
        for (Vector3i core : cores) {
            if (!usedMainCores.contains(growthKey(core))) {
                availableCores.add(core);
            }
        }
        if (availableCores.isEmpty()) {
            usedMainCores.clear();
            availableCores.addAll(cores);
        }
        Vector3i target = availableCores.get(ThreadLocalRandom.current().nextInt(availableCores.size()));
        usedMainCores.add(growthKey(target));

        RedWaveManager.clearWave(worldId, target);
        ConcurrentHashMap<String, CoreGrowthQueue> pendingByCore = this.pendingGrowthByWorld.get(worldId);
        if (pendingByCore != null) {
            pendingByCore.remove(growthKey(target));
        }

        int targetRadius = Math.max(1, action.radius());
        float targetSeconds = Math.max(0.1f, action.ticksPerBlock() / 20.0f);
        RedWaveManager.startWave(worldId, target, targetRadius, targetSeconds, true);

        double mainTriggerPct = randomFromRange(action.mainTriggerPctRange(), 0.70d, 0.70d, 0.10d, 0.95d);
        double seedRadiusAvgTriggerPct = randomFromRange(action.seedRadiusAvgTriggerPctRange(), 0.90d, 0.90d, 0.10d, 0.95d);
        double seedTargetRadius = randomFromRange(action.seedTargetRadiusRange(), 120.0d, 120.0d, 8.0d, 600.0d);

        SeededGrowEvent event = new SeededGrowEvent(
                new Vector3i(target),
                targetRadius,
                targetSeconds,
                mainTriggerPct,
                action.seedSpawnDelaySecRange(),
                seedRadiusAvgTriggerPct,
                (int) Math.round(seedTargetRadius),
                Math.max(0, action.chunkRangePerCore()),
                Math.max(1, action.maxActiveSeeds())
        );
        this.activeSeededGrowByWorld
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(growthKey(target), event);
        return true;
    }

    private void processPendingGrowth(@Nonnull World world) {
        UUID worldId = world.getWorldConfig().getUuid();
        ConcurrentHashMap<String, CoreGrowthQueue> pendingByCore = this.pendingGrowthByWorld.get(worldId);
        if (pendingByCore == null || pendingByCore.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (var entry : pendingByCore.entrySet()) {
            CoreGrowthQueue queue = entry.getValue();
            if (queue == null) {
                continue;
            }
            GrowthEventJob running = queue.running;
            if (running == null) {
                running = queue.queue.pollFirst();
                queue.running = running;
            }
            if (running == null || running.status != GrowthEventStatus.RUNNING || now < running.nextApplyAtMs) {
                if (running == null && queue.queue.isEmpty()) {
                    pendingByCore.remove(entry.getKey());
                }
                continue;
            }
            if (applyGrowthStep(worldId, running, now)) {
                queue.running = null;
                GrowthEventJob next = queue.queue.pollFirst();
                if (next != null) {
                    next.status = GrowthEventStatus.RUNNING;
                    next.nextApplyAtMs = 0L;
                    queue.running = next;
                } else {
                    pendingByCore.remove(entry.getKey());
                }
            }
        }
        if (pendingByCore.isEmpty()) {
            this.pendingGrowthByWorld.remove(worldId);
        }
    }

    private void scheduleGrowth(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull Vector3i corePos,
            int targetRadius,
            float targetSeconds,
            int sourceActionIndex
    ) {
        int normalizedTarget = Math.max(1, targetRadius);
        float normalizedTargetSeconds = Math.max(0.1f, targetSeconds);

        String key = growthKey(corePos);
        ConcurrentHashMap<String, CoreGrowthQueue> pendingByCore = this.pendingGrowthByWorld.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        CoreGrowthQueue queue = pendingByCore.computeIfAbsent(key, ignored -> new CoreGrowthQueue());
        GrowthEventJob event = new GrowthEventJob(
                UUID.randomUUID(),
                sourceActionIndex,
                new Vector3i(corePos),
                normalizedTarget,
                normalizedTargetSeconds,
                System.currentTimeMillis()
        );

        // Preempt any current wave for this core (spawn or grow) and replace queued grow work.
        RedWaveManager.ActiveWave activeWave = RedWaveManager.getActiveWave(worldId, corePos);
        if (activeWave != null) {
            RedWaveManager.clearWave(worldId, corePos);
        }

        // Preempt tracked grow pipeline work for this core.
        if (queue.running != null) {
            queue.running.status = GrowthEventStatus.CANCELED;
        }
        while (!queue.queue.isEmpty()) {
            GrowthEventJob queued = queue.queue.pollFirst();
            if (queued != null && queued.status == GrowthEventStatus.QUEUED) {
                queued.status = GrowthEventStatus.CANCELED;
            }
        }
        event.status = GrowthEventStatus.RUNNING;
        queue.running = event;
        if (applyGrowthStep(worldId, event, System.currentTimeMillis())) {
            queue.running = null;
            if (queue.queue.isEmpty()) {
                pendingByCore.remove(key);
            }
        }
    }

    private boolean applyGrowthStep(@Nonnull UUID worldId, @Nonnull GrowthEventJob pending, long now) {
        if (pending.status != GrowthEventStatus.RUNNING) {
            return true;
        }
        RedWaveManager.ActiveWave currentWave = RedWaveManager.getActiveWave(worldId, pending.corePos);
        int currentRadius = currentWave == null ? 0 : Math.max(0, currentWave.radiusBlocks());
        if (currentRadius <= 0) {
            RedWaveManager.startWave(worldId, pending.corePos, pending.targetRadius, pending.targetSeconds, true);
            pending.nextApplyAtMs = now + GROWTH_STAGE_INTERVAL_MS;
            return true;
        }
        if (currentRadius >= pending.targetRadius) {
            pending.status = GrowthEventStatus.DONE;
            return true;
        }
        float waveProgress = currentWave == null ? 1.0f : currentWave.progress();
        if (waveProgress < GROWTH_EXPANSION_PROGRESS_THRESHOLD) {
            pending.nextApplyAtMs = now + GROWTH_STAGE_INTERVAL_MS;
            return false;
        }
        RedWaveManager.startWave(worldId, pending.corePos, pending.targetRadius, pending.targetSeconds, true);
        pending.nextApplyAtMs = now + GROWTH_STAGE_INTERVAL_MS;
        pending.status = GrowthEventStatus.DONE;
        return true;
    }

    private void processSeededGrowEvents(@Nonnull World world) {
        UUID worldId = world.getWorldConfig().getUuid();
        java.util.Map<String, SeededGrowEvent> events = this.activeSeededGrowByWorld.get(worldId);
        if (events == null || events.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        RunChunkSelectionManager runChunkSelectionManager = RunChunkSelectionManager.get();
        java.util.LinkedHashSet<RunChunkSelectionManager.ChunkPosKey> selectedRunChunks = runChunkSelectionManager.getSelectedChunks(world.getName());
        it.unimi.dsi.fastutil.longs.LongSet markerChunkIndices = runChunkSelectionManager.getPinnedChunkIndices(world.getName());
        java.util.LinkedHashSet<RunChunkSelectionManager.ChunkPosKey> selectableRunChunks = new java.util.LinkedHashSet<>();
        for (RunChunkSelectionManager.ChunkPosKey selected : selectedRunChunks) {
            long chunkIndex = ChunkUtil.indexChunk(selected.x(), selected.z());
            if (markerChunkIndices.contains(chunkIndex)) {
                continue;
            }
            selectableRunChunks.add(selected);
        }
        if (selectableRunChunks.isEmpty() && !selectedRunChunks.isEmpty()) {
            selectableRunChunks.addAll(selectedRunChunks);
        }
        for (var entry : events.entrySet()) {
            SeededGrowEvent event = entry.getValue();
            if (event == null || event.done) {
                continue;
            }
            RedWaveManager.ActiveWave mainWave = RedWaveManager.getActiveWave(worldId, event.mainCorePos);
            float simulatedMainRadiusAvg = mainWave == null
                    ? 0.0f
                    : (mainWave.averageConvertedRadius() * 0.5f);
            float mainTriggerRadius = event.mainTargetRadius * event.mainTriggerPct;
            if (!event.mainTriggered && simulatedMainRadiusAvg >= mainTriggerRadius) {
                event.mainTriggered = true;
                event.nextSpawnAtMs = now;
                sendStatusIfEnabled(worldId, "[SeededGrow] Main core reached trigger "
                        + String.format("%.0f", event.mainTriggerPct * 100f)
                        + "% at " + event.mainCorePos.x + "," + event.mainCorePos.y + "," + event.mainCorePos.z
                        + " simulatedRadius(avg)=" + String.format("%.2f", simulatedMainRadiusAvg));
            }

            event.reachedChunks.clear();
            event.frameChunks.clear();
            addReachedChunksForCore(worldId, event, event.mainCorePos, selectableRunChunks);
            for (Vector3i seedPos : event.seedCores) {
                addReachedChunksForCore(worldId, event, seedPos, selectableRunChunks);
            }
            rebuildFrameChunks(event, selectableRunChunks);
            refreshManagedSeedQueue(worldId, event);

            boolean spawnChained = event.mainTriggered && !event.frameChunks.isEmpty();
            int activeSeedWaves = 0;
            for (Vector3i seedPos : event.seedCores) {
                RedWaveManager.ActiveWave seedWave = RedWaveManager.getActiveWave(worldId, seedPos);
                if (seedWave != null) {
                    activeSeedWaves++;
                }
                float simulatedSeedRadiusAvg = seedWave == null
                        ? event.seedTargetRadius
                        : seedWave.averageConvertedRadius();
                if (simulatedSeedRadiusAvg >= (event.seedTargetRadius * event.seedRadiusAvgTriggerPct)) {
                    spawnChained = true;
                    break;
                }
            }
            if (event.mainTriggered && event.seedCores.isEmpty()) {
                spawnChained = true;
            } else if (event.mainTriggered && event.spawnedSeeds >= event.maxInitialSeeds && activeSeedWaves < event.minActiveSeedsAfterInitial) {
                spawnChained = true;
            }
            if (activeSeedWaves >= event.maxActiveSeeds) {
                spawnChained = false;
            }
            if (spawnChained && !event.frameChunks.isEmpty()) {
                while (event.seedSpawnQueue.size() + event.managedSeedCores.size() < event.maxActiveSeeds) {
                    event.seedSpawnQueue.addLast(SeedSpawnRequest.chain());
                }
            }
            if (now >= event.nextSpawnAtMs
                    && !event.seedSpawnQueue.isEmpty()
                    && event.managedSeedCores.size() < event.maxActiveSeeds) {
                SeedSpawnRequest next = event.seedSpawnQueue.pollFirst();
                boolean spawned = false;
                if (next != null) {
                    spawned = spawnSeedFromFrameChunk(world, worldId, event, "chain-frame");
                }
                if (spawned) {
                    event.spawnedSeeds++;
                }
                event.nextSpawnAtMs = now + nextSeedDelayMs(event);
            }

            if (event.frameChunks.isEmpty()
                    && event.seedSpawnQueue.isEmpty()
                    && event.managedSeedCores.isEmpty()
                    && activeSeedWaves <= 0) {
                event.done = true;
            }
        }
        events.entrySet().removeIf(e -> e.getValue() == null || e.getValue().done);
        if (events.isEmpty()) {
            this.activeSeededGrowByWorld.remove(worldId);
        }
    }

    private boolean spawnSeedFromFrameChunk(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull SeededGrowEvent event,
            @Nonnull String phase
    ) {
        if (event.frameChunks.isEmpty()) {
            return false;
        }
        ArrayList<RunChunkSelectionManager.ChunkPosKey> candidates = new ArrayList<>();
        for (RunChunkSelectionManager.ChunkPosKey frame : event.frameChunks) {
            if (event.consumedFrameChunks.contains(frame)) {
                continue;
            }
            if (event.rejectedFrameChunks.contains(frame)) {
                continue;
            }
            if (event.reservedFrameChunks.contains(frame)) {
                continue;
            }
            if (event.seededChunks.contains(frame)) {
                continue;
            }
            candidates.add(frame);
        }
        if (candidates.isEmpty()) {
            return false;
        }
        RunChunkSelectionManager.ChunkPosKey chosen = candidates.get(0);
        event.reservedFrameChunks.add(chosen);
        Vector3i spawnPos = resolveEdgeSpawnFromFrame(event, chosen);
        if (spawnPos == null) {
            registerFrameSpawnFailure(event, chosen);
            event.reservedFrameChunks.remove(chosen);
            return false;
        }
        RunChunkSelectionManager.ChunkPosKey spawnChunk = toChunkPosKey(spawnPos.x, spawnPos.z);
        if (event.seededChunks.contains(spawnChunk)) {
            registerFrameSpawnFailure(event, chosen);
            event.reservedFrameChunks.remove(chosen);
            return false;
        }
        boolean spawned = trySpawnSeedAt(world, worldId, event, spawnPos.x, spawnPos.y, spawnPos.z, phase + " " + chosen.x() + "," + chosen.z());
        if (spawned) {
            event.consumedFrameChunks.add(chosen);
            event.frameChunks.remove(chosen);
            event.seededChunks.add(spawnChunk);
            event.frameFailedAttempts.remove(chosen);
        } else {
            registerFrameSpawnFailure(event, chosen);
        }
        event.reservedFrameChunks.remove(chosen);
        return spawned;
    }

    private void registerFrameSpawnFailure(@Nonnull SeededGrowEvent event, @Nonnull RunChunkSelectionManager.ChunkPosKey frameChunk) {
        int failures = event.frameFailedAttempts.getOrDefault(frameChunk, 0) + 1;
        if (failures >= 3) {
            event.rejectedFrameChunks.add(frameChunk);
            event.frameChunks.remove(frameChunk);
            event.frameFailedAttempts.remove(frameChunk);
            return;
        }
        event.frameFailedAttempts.put(frameChunk, failures);
    }

    @Nonnull
    private static RunChunkSelectionManager.ChunkPosKey toChunkPosKey(int blockX, int blockZ) {
        return new RunChunkSelectionManager.ChunkPosKey(
                Math.floorDiv(blockX, CHUNK_SIZE_BLOCKS),
                Math.floorDiv(blockZ, CHUNK_SIZE_BLOCKS)
        );
    }

    private void addReachedChunksForCore(
            @Nonnull UUID worldId,
            @Nonnull SeededGrowEvent event,
            @Nonnull Vector3i origin,
            @Nonnull java.util.Set<RunChunkSelectionManager.ChunkPosKey> selectedRunChunks
    ) {
        int originChunkX = Math.floorDiv(origin.x, CHUNK_SIZE_BLOCKS);
        int originChunkZ = Math.floorDiv(origin.z, CHUNK_SIZE_BLOCKS);
        for (RunChunkSelectionManager.ChunkPosKey reached : getSharedReachedChunks(worldId, originChunkX, originChunkZ, event.chunkRangePerCore)) {
            if (selectedRunChunks.isEmpty() || selectedRunChunks.contains(reached)) {
                event.reachedChunks.add(reached);
            }
        }
    }

    @Nonnull
    private java.util.Set<RunChunkSelectionManager.ChunkPosKey> getSharedReachedChunks(
            @Nonnull UUID worldId,
            int originChunkX,
            int originChunkZ,
            int chunkRange
    ) {
        ConcurrentHashMap<ChunkReachCacheKey, java.util.Set<RunChunkSelectionManager.ChunkPosKey>> worldCache =
                this.seededGrowChunkCacheByWorld.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        ChunkReachCacheKey cacheKey = new ChunkReachCacheKey(originChunkX, originChunkZ, Math.max(0, chunkRange));
        return worldCache.computeIfAbsent(cacheKey, ignored -> {
            java.util.HashSet<RunChunkSelectionManager.ChunkPosKey> chunks = new java.util.HashSet<>();
            for (int dx = -cacheKey.chunkRange; dx <= cacheKey.chunkRange; dx++) {
                for (int dz = -cacheKey.chunkRange; dz <= cacheKey.chunkRange; dz++) {
                    chunks.add(new RunChunkSelectionManager.ChunkPosKey(cacheKey.originChunkX + dx, cacheKey.originChunkZ + dz));
                }
            }
            return java.util.Set.copyOf(chunks);
        });
    }

    private void rebuildFrameChunks(
            @Nonnull SeededGrowEvent event,
            @Nonnull java.util.Set<RunChunkSelectionManager.ChunkPosKey> selectedRunChunks
    ) {
        java.util.HashSet<RunChunkSelectionManager.ChunkPosKey> nextFrameSet = new java.util.HashSet<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (RunChunkSelectionManager.ChunkPosKey reached : event.reachedChunks) {
            for (int[] dir : dirs) {
                RunChunkSelectionManager.ChunkPosKey neighbor = new RunChunkSelectionManager.ChunkPosKey(reached.x() + dir[0], reached.z() + dir[1]);
                if (event.reachedChunks.contains(neighbor)) {
                    continue;
                }
                if (!selectedRunChunks.isEmpty() && !selectedRunChunks.contains(neighbor)) {
                    continue;
                }
                if (event.consumedFrameChunks.contains(neighbor)) {
                    continue;
                }
                nextFrameSet.add(neighbor);
            }
        }
        ArrayList<RunChunkSelectionManager.ChunkPosKey> nextFrame = new ArrayList<>(nextFrameSet);
        int mainChunkX = Math.floorDiv(event.mainCorePos.x, CHUNK_SIZE_BLOCKS);
        int mainChunkZ = Math.floorDiv(event.mainCorePos.z, CHUNK_SIZE_BLOCKS);
        nextFrame.sort(java.util.Comparator.comparingInt(chunk -> {
            int dx = chunk.x() - mainChunkX;
            int dz = chunk.z() - mainChunkZ;
            return (dx * dx) + (dz * dz);
        }));
        event.frameChunks.clear();
        event.frameChunks.addAll(nextFrame);
    }

    private Vector3i resolveEdgeSpawnFromFrame(
            @Nonnull SeededGrowEvent event,
            @Nonnull RunChunkSelectionManager.ChunkPosKey frame
    ) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        ArrayList<RunChunkSelectionManager.ChunkPosKey> inwardNeighbors = new ArrayList<>(4);
        for (int[] dir : dirs) {
            RunChunkSelectionManager.ChunkPosKey neighbor = new RunChunkSelectionManager.ChunkPosKey(frame.x() + dir[0], frame.z() + dir[1]);
            if (event.frameChunks.contains(neighbor) || event.reachedChunks.contains(neighbor)) {
                inwardNeighbors.add(neighbor);
            }
        }
        if (inwardNeighbors.isEmpty()) {
            return null;
        }
        java.util.Collections.shuffle(inwardNeighbors);
        RunChunkSelectionManager.ChunkPosKey neighborTarget = inwardNeighbors.get(0);
        int frameMinX = frame.x() * CHUNK_SIZE_BLOCKS;
        int frameMinZ = frame.z() * CHUNK_SIZE_BLOCKS;
        int frameMaxX = frameMinX + CHUNK_SIZE_BLOCKS - 1;
        int frameMaxZ = frameMinZ + CHUNK_SIZE_BLOCKS - 1;
        int y = event.mainCorePos.y;
        int x = ThreadLocalRandom.current().nextInt(frameMinX + 4, frameMaxX - 3);
        int z = ThreadLocalRandom.current().nextInt(frameMinZ + 4, frameMaxZ - 3);
        int dx = neighborTarget.x() - frame.x();
        int dz = neighborTarget.z() - frame.z();
        if (dx > 0) {
            x = frameMaxX;
        } else if (dx < 0) {
            x = frameMinX;
        } else if (dz > 0) {
            z = frameMaxZ;
        } else if (dz < 0) {
            z = frameMinZ;
        }
        return new Vector3i(x, y, z);
    }

    private int countActiveSeedWaves(@Nonnull UUID worldId, @Nonnull SeededGrowEvent event) {
        int active = 0;
        for (Vector3i seedPos : event.seedCores) {
            if (RedWaveManager.getActiveWave(worldId, seedPos) != null) {
                active++;
            }
        }
        return active;
    }

    private void refreshManagedSeedQueue(@Nonnull UUID worldId, @Nonnull SeededGrowEvent event) {
        float releaseRadius = event.seedTargetRadius * event.seedRadiusAvgTriggerPct;
        java.util.Iterator<Vector3i> it = event.managedSeedCores.iterator();
        while (it.hasNext()) {
            Vector3i seedPos = it.next();
            RedWaveManager.ActiveWave seedWave = RedWaveManager.getActiveWave(worldId, seedPos);
            if (seedWave == null) {
                it.remove();
                continue;
            }
            float radiusAvg = seedWave.averageConvertedRadius();
            if (radiusAvg >= releaseRadius) {
                it.remove();
            }
        }
    }

    private long nextSeedDelayMs(@Nonnull SeededGrowEvent event) {
        double delaySec = randomFromRange(event.seedSpawnDelaySecRange, 2.0d, 2.0d, 0.0d, 30.0d);
        return (long) (Math.max(0.0d, delaySec) * 1000d);
    }

    private boolean trySpawnSeedAt(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull SeededGrowEvent event,
            int x,
            int y,
            int z,
            @Nonnull String phase
    ) {
        int spawnY = resolveSeedSpawnY(world, x, y, z);
        if (spawnY < 0) {
            return false;
        }
        Vector3i seedPos = new Vector3i(x, spawnY, z);
        world.setBlock(x, spawnY, z, RedWaveConfig.CORE_BLOCK_ID);
        RunEnvironmentPainter.paintColumnForRunBlock(world, x, spawnY, z);
        RedCoreRegistry.register(worldId, seedPos);
        markSeededGrowSeedCore(worldId, seedPos);
        RedWaveManager.startWave(worldId, seedPos, event.seedTargetRadius, event.mainTargetSeconds);
        event.seedCores.add(seedPos);
        event.managedSeedCores.add(seedPos);
        sendStatusIfEnabled(worldId, "[SeededGrow] Seed spawned (" + phase + ") at "
                + x + "," + spawnY + "," + z
                + " targetRadius=" + event.seedTargetRadius
                + " delay=dynamic");
        return true;
    }

    private int resolveSeedSpawnY(@Nonnull World world, int x, int y, int z) {
        int maxOffset = 24;
        for (int offset = 0; offset <= maxOffset; offset++) {
            int upY = y + offset;
            if (isSupportedEmpty(world, x, upY, z)) {
                return upY;
            }
            if (offset == 0) {
                continue;
            }
            int downY = y - offset;
            if (isSupportedEmpty(world, x, downY, z)) {
                return downY;
            }
        }
        return -1;
    }

    private boolean isSupportedEmpty(@Nonnull World world, int x, int y, int z) {
        BlockType target = world.getBlockType(x, y, z);
        if (target != null && target != BlockType.EMPTY) {
            return false;
        }
        BlockType below = world.getBlockType(x, y - 1, z);
        return below != null && below != BlockType.EMPTY;
    }

    private static float simulateRadiusAvg(int targetRadius, float progress) {
        float clampedProgress = Math.max(0.0f, Math.min(1.0f, progress));
        float targetDiameter = Math.max(1.0f, targetRadius * 2.0f);
        return (targetDiameter / 2.0f) * clampedProgress;
    }

    private static double randomFromRange(
            @Nonnull String rangeText,
            double fallbackMin,
            double fallbackMax,
            double clampMin,
            double clampMax
    ) {
        String normalized = rangeText == null ? "" : rangeText.trim();
        double min = fallbackMin;
        double max = fallbackMax;
        if (!normalized.isEmpty()) {
            String[] split = normalized.split("[-:,]");
            if (split.length >= 2) {
                try {
                    min = Double.parseDouble(split[0].trim());
                    max = Double.parseDouble(split[1].trim());
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    min = Double.parseDouble(normalized);
                    max = min;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        double low = Math.max(clampMin, Math.min(min, max));
        double high = Math.min(clampMax, Math.max(min, max));
        if (high <= low) {
            return low;
        }
        return ThreadLocalRandom.current().nextDouble(low, high);
    }

    @Nonnull
    private static String growthKey(@Nonnull Vector3i corePos) {
        return corePos.x + ":" + corePos.y + ":" + corePos.z;
    }

    private static final class SeededGrowEvent {
        private final Vector3i mainCorePos;
        private final int mainTargetRadius;
        private final float mainTargetSeconds;
        private final float mainTriggerPct;
        private final float seedRadiusAvgTriggerPct;
        private final String seedSpawnDelaySecRange;
        private final int seedTargetRadius;
        private final int maxInitialSeeds;
        private final int minActiveSeedsAfterInitial;
        private final int maxActiveSeeds;
        private final int chunkRangePerCore;
        private final List<Vector3i> seedCores = new ArrayList<>();
        private final java.util.Set<RunChunkSelectionManager.ChunkPosKey> reachedChunks = new java.util.HashSet<>();
        private final java.util.List<RunChunkSelectionManager.ChunkPosKey> frameChunks = new ArrayList<>();
        private final java.util.Set<RunChunkSelectionManager.ChunkPosKey> consumedFrameChunks = new java.util.HashSet<>();
        private final java.util.Set<RunChunkSelectionManager.ChunkPosKey> rejectedFrameChunks = new java.util.HashSet<>();
        private final java.util.Set<RunChunkSelectionManager.ChunkPosKey> reservedFrameChunks = new java.util.HashSet<>();
        private final java.util.Set<RunChunkSelectionManager.ChunkPosKey> seededChunks = new java.util.HashSet<>();
        private final java.util.Map<RunChunkSelectionManager.ChunkPosKey, Integer> frameFailedAttempts = new java.util.HashMap<>();
        private final java.util.ArrayDeque<SeedSpawnRequest> seedSpawnQueue = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<Vector3i> managedSeedCores = new java.util.ArrayDeque<>();
        private boolean mainTriggered;
        private int spawnedSeeds;
        private long nextSpawnAtMs;
        private boolean done;

        private SeededGrowEvent(
                @Nonnull Vector3i mainCorePos,
                int mainTargetRadius,
                float mainTargetSeconds,
                double mainTriggerPct,
                @Nonnull String seedSpawnDelaySecRange,
                double seedRadiusAvgTriggerPct,
                int seedTargetRadius,
                int chunkRangePerCore,
                int maxActiveSeeds
        ) {
            this.mainCorePos = new Vector3i(mainCorePos);
            this.mainTargetRadius = Math.max(1, mainTargetRadius);
            this.mainTargetSeconds = Math.max(0.1f, mainTargetSeconds);
            this.mainTriggerPct = (float) Math.max(0.1d, Math.min(0.95d, mainTriggerPct));
            this.seedRadiusAvgTriggerPct = (float) Math.max(0.1d, Math.min(0.95d, seedRadiusAvgTriggerPct));
            this.seedSpawnDelaySecRange = seedSpawnDelaySecRange == null ? "2.0-2.0" : seedSpawnDelaySecRange;
            this.seedTargetRadius = Math.max(8, seedTargetRadius);
            this.maxInitialSeeds = 0;
            this.minActiveSeedsAfterInitial = 0;
            this.maxActiveSeeds = Math.max(1, maxActiveSeeds);
            this.chunkRangePerCore = Math.max(0, chunkRangePerCore);
            this.mainTriggered = false;
            this.spawnedSeeds = 0;
            this.nextSpawnAtMs = 0L;
            this.done = false;
        }
    }

    private static final class ChunkReachCacheKey {
        private final int originChunkX;
        private final int originChunkZ;
        private final int chunkRange;

        private ChunkReachCacheKey(int originChunkX, int originChunkZ, int chunkRange) {
            this.originChunkX = originChunkX;
            this.originChunkZ = originChunkZ;
            this.chunkRange = chunkRange;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ChunkReachCacheKey)) {
                return false;
            }
            ChunkReachCacheKey other = (ChunkReachCacheKey) obj;
            return this.originChunkX == other.originChunkX
                    && this.originChunkZ == other.originChunkZ
                    && this.chunkRange == other.chunkRange;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(this.originChunkX);
            result = (31 * result) + Integer.hashCode(this.originChunkZ);
            result = (31 * result) + Integer.hashCode(this.chunkRange);
            return result;
        }
    }

    private enum SeedSpawnPhase {
        CHAIN
    }

    private static final class SeedSpawnRequest {
        private final SeedSpawnPhase phase;

        private SeedSpawnRequest(@Nonnull SeedSpawnPhase phase) {
            this.phase = phase;
        }

        @Nonnull
        private static SeedSpawnRequest chain() {
            return new SeedSpawnRequest(SeedSpawnPhase.CHAIN);
        }
    }

    private enum GrowthEventStatus {
        QUEUED,
        RUNNING,
        CANCELED,
        DONE,
        FAILED
    }

    private static final class GrowthEventJob {
        private final UUID eventId;
        private final int sourceActionIndex;
        private final Vector3i corePos;
        private final int targetRadius;
        private final float targetSeconds;
        private final long createdAtMs;
        private GrowthEventStatus status;
        private long nextApplyAtMs;

        private GrowthEventJob(
                @Nonnull UUID eventId,
                int sourceActionIndex,
                @Nonnull Vector3i corePos,
                int targetRadius,
                float targetSeconds,
                long createdAtMs
        ) {
            this.eventId = eventId;
            this.sourceActionIndex = sourceActionIndex;
            this.corePos = corePos;
            this.targetRadius = targetRadius;
            this.targetSeconds = Math.max(0.1f, targetSeconds);
            this.createdAtMs = createdAtMs;
            this.status = GrowthEventStatus.QUEUED;
            this.nextApplyAtMs = 0L;
        }
    }

    private static final class CoreGrowthQueue {
        private GrowthEventJob running;
        private final Deque<GrowthEventJob> queue = new ArrayDeque<>();
    }

    @Nonnull
    private List<BlightfallMainPage.RuntimeAction> resolveRuntimeActions(
            @Nonnull World world,
            @Nonnull GameSessionManager.ActiveSessionSnapshot snapshot
    ) {
        List<BlightfallMainPage.RuntimeAction> uiActions = BlightfallMainPage.snapshotRuntimeActions(snapshot.starterPlayerId());
        if (!uiActions.isEmpty()) {
            return uiActions;
        }

        List<InfectionActionConfigManager.ActionEntry> actions = InfectionActionConfigManager.loadActions(world.getName());
        if (actions.isEmpty()) {
            actions = InfectionActionConfigManager.loadActions(snapshot.templateWorldName());
        }
        if (actions.isEmpty()) {
            return List.of();
        }

        List<BlightfallMainPage.RuntimeAction> mapped = new java.util.ArrayList<>(actions.size());
        for (InfectionActionConfigManager.ActionEntry action : actions) {
            mapped.add(new BlightfallMainPage.RuntimeAction(
                    action.actionType(),
                    action.coreTier(),
                    action.triggerSecond(),
                    action.radius(),
                    action.ticksPerBlock(),
                    action.probabilityPercent(),
                    action.enabled(),
                    action.mainTriggerPctRange(),
                    action.seedSpawnDelaySecRange(),
                    action.seedRadiusAvgTriggerPctRange(),
                    action.seedTargetRadiusRange(),
                    action.chunkRangePerCore(),
                    action.maxActiveSeeds()
            ));
        }
        return mapped;
    }

    private void sendStatusIfEnabled(@Nonnull UUID worldId, @Nonnull String text) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        if (!config.isStatusMessagesEnabled()) {
            return;
        }
        if (!config.isCoreRadiusChatMessagesEnabled() && isCoreRadiusStatusMessage(text)) {
            return;
        }
        sendRunWorldMessage(worldId, text);
    }

    private static boolean isCoreRadiusStatusMessage(@Nonnull String text) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.contains("[infectionaction]")) {
            return false;
        }
        return normalized.contains("core")
                || normalized.contains("radius")
                || normalized.contains("growth")
                || normalized.contains("requestedradius")
                || normalized.contains("activeradiusbeforereset");
    }

    private void updateRunWorldTimerHud(@Nonnull UUID runWorldId, long remainingMs) {
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        long secondsLeft = remainingMs / 1000L;
        String formatted = formatTime(secondsLeft);
        long runeCountdownMs = OrangeBlobBlockManager.getExtractionCountdownMillis(runWorldId);
        long flareCountdownMs = FlareExtractionManager.getExtractionCountdownMillis(runWorldId);
        long extractionCountdownMs;
        if (runeCountdownMs > 0L && flareCountdownMs > 0L) {
            extractionCountdownMs = Math.min(runeCountdownMs, flareCountdownMs);
        } else {
            extractionCountdownMs = Math.max(runeCountdownMs, flareCountdownMs);
        }
        boolean extractionVisible = extractionCountdownMs > 0L;
        String extractionFormatted = extractionVisible ? formatTime(extractionCountdownMs / 1000L) : "";
        boolean witchSpawned = this.witchSpawnedInWorld.getOrDefault(runWorldId, false);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorld = playerRef.getWorldUuid();
            UUID playerId = playerRef.getUuid();
            if (playerWorld == null || !playerWorld.equals(runWorldId)) {
                hideTimerHud(playerRef);
                this.lastShownSecond.remove(playerId);
                currentFormattedTime.remove(playerId);
                continue;
            }

            currentFormattedTime.put(playerId, formatted);

            if (isRunIntroHudSuppressed(snapshot)) {
                hideTimerHud(playerRef);
                this.lastShownSecond.remove(playerId);
                continue;
            }

            // Skip if Potion Brewer Witch HUD is active to avoid flashing
            if (dev.hytalemodding.potion.PotionBrewerWitchHudSystem.isPlayerSeeingWitchHud(playerId)) {
                if (this.lastShownSecond.containsKey(playerId)) {
                    hideTimerHud(playerRef);
                    this.lastShownSecond.remove(playerId);
                }
                continue;
            }

            Long last = this.lastShownSecond.get(playerId);
            if (last != null && last == secondsLeft) {
                continue;
            }
            this.lastShownSecond.put(playerId, secondsLeft);
            showTimerHud(playerRef, formatted, witchSpawned, extractionFormatted, extractionVisible);
        }
    }

    private static boolean isRunIntroHudSuppressed(GameSessionManager.ActiveSessionSnapshot snapshot) {
        if (snapshot == null || snapshot.startedAtEpochMillis() <= 0L) {
            return false;
        }
        long introEndsAt = snapshot.startedAtEpochMillis() + RunStartCameraManager.getIntroEndFromRunStartMs();
        return System.currentTimeMillis() < introEndsAt;
    }

    private void showTimerHud(
            @Nonnull PlayerRef playerRef,
            @Nonnull String timeString,
            boolean witchSpawned,
            @Nonnull String extractionTimeString,
            boolean extractionVisible
    ) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        entityStore.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = entityStore.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            GameTimerHud hud = this.timerHuds.computeIfAbsent(playerRef.getUuid(), ignored -> new GameTimerHud(playerRef));
            hud.setTime(timeString);
            hud.setExtractionTimer(extractionTimeString, extractionVisible);
            hud.setVisible(true);
            player.getHudManager().setCustomHud(playerRef, hud);
            hud.show();
        });
    }

    private void hideTimerHud(@Nonnull PlayerRef playerRef) {
        GameTimerHud hud = this.timerHuds.get(playerRef.getUuid());
        if (hud == null) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        entityStore.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = entityStore.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            hud.setVisible(false);
            CustomUIHud current = player.getHudManager().getCustomHud();
            if (current == hud) {
                player.getHudManager().setCustomHud(playerRef, null);
            }
        });
    }

    private void hideAllTimerHuds() {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            hideTimerHud(playerRef);
        }
        this.lastShownSecond.clear();
    }

    @Nonnull
    private static String formatTime(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static void sendRunWorldMessage(@Nonnull UUID runWorldId, @Nonnull String text) {
        Message message = Message.raw(text);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid != null && worldUuid.equals(runWorldId)) {
                playerRef.sendMessage(message);
            }
        }
    }

    private void trySpawnWitch(@Nonnull World world, @Nonnull UUID worldId) {
        try {
            int existingWitches = countExistingWitches(world);
            if (existingWitches > 0) {
                this.witchSpawnedInWorld.put(worldId, true);
                if (existingWitches > 1) {
                    System.out.println("[GameRunDirector] Found " + existingWitches + " Potion Brewer Witches in world " + world.getName() + "; suppressing additional spawn.");
                }
                return;
            }

            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null) {
                return;
            }
            int roleIndex = npcPlugin.getIndex(POTION_BREWER_WITCH_ROLE);
            BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
            if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
                return;
            }
            Vector3f spawnRot = new Vector3f(0, 0, 0);
            var npcPair = npcPlugin.spawnEntity(
                    world.getEntityStore().getStore(),
                    roleIndex,
                    WITCH_SPAWN_POS,
                    spawnRot,
                    null,
                    null
            );

            if (npcPair != null && npcPair.first() != null && npcPair.first().isValid()) {
                this.witchSpawnedInWorld.put(worldId, true);
                sendRunWorldMessage(worldId, "A Potion Brewer Witch appeared!");
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to spawn witch: " + e.getMessage());
        }
    }

    private static int countExistingWitches(@Nonnull World world) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        final int[] count = {0};
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (npc != null
                        && POTION_BREWER_WITCH_ROLE.equals(npc.getRoleName())
                        && ref != null
                        && ref.isValid()
                        && !npc.isDespawning()) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

}