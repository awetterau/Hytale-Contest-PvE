package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
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
    private static final String POTION_BREWER_WITCH_ROLE = "Potion_Brewer_Witch";
    private static final Vector3d WITCH_SPAWN_POS = new Vector3d(-2, 225, -90);
    private final ConcurrentHashMap<UUID, GameTimerHud> timerHuds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastShownSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<Integer>> executedAutomationByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Integer>> failedAutomationByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, CoreGrowthQueue>> pendingGrowthByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> witchSpawnedInWorld = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> currentFormattedTime = new ConcurrentHashMap<>();
    private static final long GROWTH_STAGE_INTERVAL_MS = 500L;
    private static final float GROWTH_EXPANSION_PROGRESS_THRESHOLD = 0.96f;
    private static Vector3d witchMarkerPos = null;

    public static String getFormattedTime(@Nonnull UUID playerId) {
        return currentFormattedTime.getOrDefault(playerId, "00:00");
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            hideAllTimerHuds();
            this.executedAutomationByWorld.clear();
            this.failedAutomationByWorld.clear();
            this.pendingGrowthByWorld.clear();
            this.witchSpawnedInWorld.clear();
            return;
        }
        if (snapshot.phase() != GameSessionManager.RunPhase.EXPLORATION
                && snapshot.phase() != GameSessionManager.RunPhase.CRIMSON_ACTIVE) {
            hideAllTimerHuds();
            this.executedAutomationByWorld.clear();
            this.failedAutomationByWorld.clear();
            this.pendingGrowthByWorld.clear();
            this.witchSpawnedInWorld.remove(snapshot.runWorldUuid());
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

    @Nonnull
    private static String growthKey(@Nonnull Vector3i corePos) {
        return corePos.x + ":" + corePos.y + ":" + corePos.z;
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
                    action.enabled()
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