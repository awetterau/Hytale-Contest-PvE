package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.rooter.RooterConfig;
import dev.hytalemodding.rooter.RooterManManager;
import dev.hytalemodding.loot.LifeEssenceContainerKey;
import dev.hytalemodding.loot.LootChestAccess;
import dev.hytalemodding.loot.LootChestRuntime;
import dev.hytalemodding.loot.QuestChestConfigManager;
import dev.hytalemodding.loot.QuestChestPositionManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.state.transition.CrimsonCoreConfigManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.PlayerSpawnSafety;
import dev.hytalemodding.state.transition.SpawnPointZoneConfigManager;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

public final class GameSessionManager {
    private static final String BLIGHT_BEAST_ROLE = "Blight_Beast";
    private static final String RUN_WORLD_PREFIX = "run-session-";
    private static final long CRIMSON_START_DELAY_MS = 10_000L;
    private static final long RUN_DURATION_MS = 5L * 60L * 1000L;
    private static final float DEFAULT_CRIMSON_SPREAD_SECONDS = RUN_DURATION_MS / 1000.0f;
    private static final int MAX_RUN_CRIMSON_RADIUS_BLOCKS = 24;
    private static final long PLAYER_TRANSFER_TIMEOUT_SECONDS = 15L;
    private static final long WORLD_COPY_TIMEOUT_SECONDS = 90L;
    private static final long WORLD_LOAD_TIMEOUT_SECONDS = 30L;
    private static final int WORLD_DELETE_MAX_ATTEMPTS = 20;
    private static final long WORLD_DELETE_RETRY_DELAY_MS = 100L;
    private static final GameSessionManager INSTANCE = new GameSessionManager();

    @Nullable
    private ActiveSession activeSession;
    private volatile long configuredRunDurationMs = RUN_DURATION_MS;
    @Nullable
    private volatile Long configuredRunSeed;

    private GameSessionManager() {
        this.configuredRunDurationMs = GameFlowConfigManager.get().getRunDurationSeconds() * 1000L;
        this.configuredRunSeed = GameFlowConfigManager.get().getRunSeed();
    }

    @Nonnull
    public static GameSessionManager get() {
        return INSTANCE;
    }

    public synchronized void setRunDurationSeconds(int seconds) {
        this.configuredRunDurationMs = Math.max(1, seconds) * 1000L;
        GameFlowConfigManager.get().setRunDurationSeconds((int) (this.configuredRunDurationMs / 1000L));
    }

    public synchronized int getRunDurationSeconds() {
        return (int) Math.max(1L, this.configuredRunDurationMs / 1000L);
    }

    public synchronized void setRunSeed(@Nullable Long seed) {
        this.configuredRunSeed = seed;
        GameFlowConfigManager.get().setRunSeed(seed);
    }

    @Nullable
    public synchronized Long getRunSeed() {
        if (this.configuredRunSeed == null) {
            this.configuredRunSeed = GameFlowConfigManager.get().getRunSeed();
        }
        return this.configuredRunSeed;
    }

    public void cleanupOrphanRunWorldsOnStartup() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        Map<String, World> loadedWorlds = universe.getWorlds();
        for (String worldName : loadedWorlds.keySet()) {
            if (!isRunSessionWorldName(worldName)) {
                continue;
            }
            try {
                universe.removeWorld(worldName);
            } catch (Exception ignored) {
            }
        }

        Path worldsPath = universe.getPath().resolve("worlds");
        if (!Files.isDirectory(worldsPath)) {
            return;
        }
        try (var stream = Files.list(worldsPath)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> isRunSessionWorldName(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            deleteDirectoryIfPresent(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    public synchronized boolean hasActiveSession() {
        return this.activeSession != null && this.activeSession.phase != RunPhase.IDLE;
    }

    @Nullable
    public synchronized ActiveSessionSnapshot getActiveSession() {
        if (this.activeSession == null) {
            return null;
        }
        return this.activeSession.snapshot();
    }

    @Nonnull
    public CompletableFuture<StartSessionResult> startSession(@Nonnull PlayerRef starter, @Nonnull World templateWorld) {
        return startSession(starter, templateWorld, null, null);
    }

    @Nonnull
    public CompletableFuture<StartSessionResult> startSession(
            @Nonnull PlayerRef starter,
            @Nonnull World templateWorld,
            @Nullable Transform runSpawnTransform,
            @Nullable Transform returnSpawnTransform
    ) {
        final ActiveSession session;
        synchronized (this) {
            if (this.activeSession != null && this.activeSession.phase != RunPhase.IDLE) {
                return CompletableFuture.failedFuture(new IllegalStateException("A run is already active."));
            }

            String templateWorldName = templateWorld.getName();
            String runWorldName = buildRunWorldName(templateWorldName);
            Path runWorldPath = Universe.get().getPath().resolve("worlds").resolve(runWorldName);

            UUID templateWorldId = templateWorld.getWorldConfig().getUuid();
            CrimsonCoreConfigManager.CrimsonCoreConfigState configuredState = CrimsonCoreConfigManager.get().getState(templateWorld.getName());
            List<RedCoreProfileRegistry.RedCoreProfile> configuredProfiles = RedCoreProfileRegistry.snapshot(templateWorldId);
            if (configuredProfiles.isEmpty()) {
                configuredProfiles = configuredState.profiles();
                if (!configuredProfiles.isEmpty()) {
                    RedCoreProfileRegistry.setProfiles(templateWorldId, configuredProfiles);
                }
            }
            ArrayList<RedCoreProfileRegistry.RedCoreProfile> validProfiles = new ArrayList<>();
            for (RedCoreProfileRegistry.RedCoreProfile profile : configuredProfiles) {
                Vector3i corePos = profile.corePos();
                if (corePos == null) {
                    continue;
                }
                int effectiveRadius = normalizeRunRadius(profile.radiusBlocks());
                float effectiveStartSeconds = normalizeStartSeconds(profile.startSeconds());
                validProfiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(corePos), effectiveRadius, effectiveStartSeconds));
            }
            if (validProfiles.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Configure crimson cores/radius/time in template world using /redui before /gamestart.")
                );
            }

            Long configuredSeed = this.configuredRunSeed;
            long effectiveRunSeed = configuredSeed != null ? configuredSeed.longValue() : ThreadLocalRandom.current().nextLong();
            List<RedCoreProfileRegistry.RedCoreProfile> selectedProfiles = selectRunProfiles(validProfiles, configuredState.chooseCount(), effectiveRunSeed);

            this.activeSession = new ActiveSession(
                    templateWorldName,
                    runWorldName,
                    runWorldPath,
                    starter.getUuid(),
                    selectedProfiles,
                    copyTransformOrNull(runSpawnTransform),
                    copyTransformOrNull(returnSpawnTransform)
            );
            this.activeSession.phase = RunPhase.PREPARING;
            session = this.activeSession;
        }

        Universe universe = Universe.get();
        Path templatePath = universe.getPath().resolve("worlds").resolve(session.templateWorldName);

        CompletableFuture<Void> copyFuture = CompletableFuture.runAsync(() -> copyDirectory(templatePath, session.runWorldPath))
                .orTimeout(WORLD_COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<World> loadFuture = copyFuture.thenCompose(ignored -> loadAndInstantiateRunWorld(universe, session))
                .orTimeout(WORLD_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<World> transferFuture = loadFuture.thenCompose(runWorld -> {
            return CompletableFuture.runAsync(() -> scrubRunWorldMarkersAndUnusedCores(runWorld, session), runWorld)
                    .thenApply(ignored -> runWorld);
        }).thenCompose(runWorld -> {
            return CompletableFuture.runAsync(() -> customizeRunWorld(runWorld, session), runWorld)
                    .thenApply(ignored -> runWorld);
        }).thenCompose(runWorld -> {
            UUID playerWorldUuid = starter.getWorldUuid();
            World playerWorld = playerWorldUuid == null ? null : universe.getWorld(playerWorldUuid);
            if (playerWorld == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Starter player is not currently in a loaded world.")
                );
            }
            return movePlayerToWorld(starter, playerWorld, runWorld, session.runSpawnTransform)
                    .thenApply(x -> runWorld);
        });

        return transferFuture
                .thenApply(runWorld -> {
                    RunStartMovementLockManager.get().lockPlayerForIntro(starter);
                    if (GameFlowConfigManager.get().isStatusMessagesEnabled() && session.appliedRunHour >= 0) {
                        starter.sendMessage(Message.raw("Run world time applied: " + session.appliedRunHour + ":00"));
                    }
                    synchronized (this) {
                        if (this.activeSession == session) {
                            session.runWorldUuid = runWorld.getWorldConfig().getUuid();
                            session.phase = RunPhase.WAITING_FOR_PLAYERS_READY;
                        }
                    }
                    return new StartSessionResult(
                            session.templateWorldName,
                            session.runWorldName,
                            runWorld.getWorldConfig().getUuid(),
                            session.crimsonStartAtEpochMillis,
                            session.runEndsAtEpochMillis
                    );
                })
                .exceptionallyCompose(throwable -> cleanupFailedStart(session, throwable));
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession() {
        return endSession(null);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession(@Nullable Transform returnSpawnOverride, @Nullable World ignoredFallbackWorld) {
        return endSessionInternal(returnSpawnOverride, ignoredFallbackWorld, false);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSessionAndWipeInventory(@Nullable Transform returnSpawnOverride, @Nullable World ignoredFallbackWorld) {
        return endSessionInternal(returnSpawnOverride, ignoredFallbackWorld, true);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession(@Nullable Transform returnSpawnOverride) {
        return endSessionInternal(returnSpawnOverride, null, false);
    }

    @Nonnull
    private CompletableFuture<EndSessionResult> endSessionInternal(
            @Nullable Transform returnSpawnOverride,
            @Nullable World fallbackWorldOverride,
            boolean wipeInventory
    ) {
        final ActiveSession session;
        synchronized (this) {
            if (this.activeSession == null || this.activeSession.phase == RunPhase.IDLE) {
                return CompletableFuture.completedFuture(new EndSessionResult(false, null, null, "No active run."));
            }
            session = this.activeSession;
            session.phase = RunPhase.ENDING;
        }

        Universe universe = Universe.get();
        World runWorld = universe.getWorld(session.runWorldName);
        World fallbackWorld = fallbackWorldOverride != null
                ? fallbackWorldOverride
                : universe.getWorld(session.templateWorldName);
        if (fallbackWorld == null) {
            fallbackWorld = universe.getDefaultWorld();
        }
        List<PlayerRef> affectedPlayers = runWorld == null ? List.of() : collectPlayersInWorld(runWorld.getWorldConfig().getUuid());

        CompletableFuture<Void> transferFuture;
        if (runWorld != null && fallbackWorld != null) {
            Transform returnSpawn = returnSpawnOverride != null
                    ? copyTransformOrNull(returnSpawnOverride)
                    : copyTransformOrNull(session.returnSpawnTransform);
            transferFuture = movePlayersFromWorld(runWorld, fallbackWorld, returnSpawn);
        } else {
            transferFuture = CompletableFuture.completedFuture(null);
        }

        return transferFuture.handle((ignored, throwable) -> {
            synchronized (this) {
                if (this.activeSession == session) {
                    this.activeSession = null;
                }
            }

            cleanupRunRuntime(session.runWorldUuid);

            if (throwable != null) {
                String error = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                return new EndSessionResult(false, session.runWorldName, session.templateWorldName, error);
            }

            if (wipeInventory) {
                wipeInventories(affectedPlayers);
            }

            CompletableFuture.runAsync(() -> {
                try {
                    if (universe.getWorld(session.runWorldName) != null) {
                        universe.removeWorld(session.runWorldName);
                    }
                    deleteDirectoryIfPresent(session.runWorldPath);
                } catch (Exception cleanupIgnored) {
                }
            });

            return new EndSessionResult(true, session.runWorldName, session.templateWorldName, "Run ended.");
        });
    }

    @Nonnull
    public CompletableFuture<StartSessionResult> resetSession(@Nonnull PlayerRef requester, @Nonnull World templateWorld) {
        CompletableFuture<EndSessionResult> endFuture = this.hasActiveSession()
                ? this.endSession()
                : CompletableFuture.completedFuture(new EndSessionResult(true, null, templateWorld.getName(), "No active run."));
        return endFuture.thenCompose(ignored -> this.startSession(requester, templateWorld, null, null));
    }

    public synchronized boolean shouldActivateCrimson() {
        if (this.activeSession == null || this.activeSession.phase != RunPhase.EXPLORATION) {
            return false;
        }
        return System.currentTimeMillis() >= this.activeSession.crimsonStartAtEpochMillis;
    }

    public synchronized void markCrimsonActive() {
        if (this.activeSession != null && this.activeSession.phase == RunPhase.EXPLORATION) {
            this.activeSession.phase = RunPhase.CRIMSON_ACTIVE;
        }
    }

    public synchronized long getRemainingMillis() {
        if (this.activeSession == null
                || (this.activeSession.phase != RunPhase.EXPLORATION && this.activeSession.phase != RunPhase.CRIMSON_ACTIVE)) {
            return 0L;
        }
        return Math.max(0L, this.activeSession.runEndsAtEpochMillis - System.currentTimeMillis());
    }

    public void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        if (event.getPlayerRef() == null || !event.getPlayerRef().isValid()) {
            return;
        }
        PlayerRef readyPlayerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        if (readyPlayerRef == null) {
            return;
        }
        UUID playerId = readyPlayerRef.getUuid();
        World runWorld;
        ActiveSessionSnapshot snapshot;

        synchronized (this) {
            if (this.activeSession == null || this.activeSession.phase != RunPhase.WAITING_FOR_PLAYERS_READY) {
                return;
            }
            ActiveSession session = this.activeSession;
            if (!session.expectedPlayerIds.contains(playerId)) {
                return;
            }

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || playerRef.getWorldUuid() == null || session.runWorldUuid == null) {
                return;
            }
            if (!session.runWorldUuid.equals(playerRef.getWorldUuid())) {
                return;
            }

            session.readyPlayerIds.add(playerId);
            if (!session.readyPlayerIds.containsAll(session.expectedPlayerIds)) {
                return;
            }

            RunStartMovementLockManager.get().lockPlayerForIntro(playerRef);
            session.phase = RunPhase.EXPLORATION;
            session.startedAtEpochMillis = System.currentTimeMillis();
            session.runEndsAtEpochMillis = session.startedAtEpochMillis + this.configuredRunDurationMs;
            session.crimsonStartAtEpochMillis = session.startedAtEpochMillis + CRIMSON_START_DELAY_MS;

            runWorld = Universe.get().getWorld(session.runWorldUuid);
            if (runWorld == null) {
                return;
            }
            snapshot = session.snapshot();
        }

        RescueObjectiveManager.get().spawnRescueOnRunStart(runWorld, snapshot);
        PlayerRef readyPlayer = Universe.get().getPlayer(playerId);
        if (readyPlayer != null) {
            RunStartCameraManager.get().playSpawnIntro(readyPlayer);
        }
    }

    public void onClientReadyPacket(@Nonnull UUID playerId, boolean readyForGameplay) {
        if (!readyForGameplay) {
            return;
        }
        World runWorld;
        ActiveSessionSnapshot snapshot;

        synchronized (this) {
            if (this.activeSession == null || this.activeSession.phase != RunPhase.WAITING_FOR_PLAYERS_READY) {
                return;
            }
            ActiveSession session = this.activeSession;
            if (!session.expectedPlayerIds.contains(playerId)) {
                return;
            }

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || playerRef.getWorldUuid() == null || session.runWorldUuid == null) {
                return;
            }
            if (!session.runWorldUuid.equals(playerRef.getWorldUuid())) {
                return;
            }

            session.readyPlayerIds.add(playerId);
            if (!session.readyPlayerIds.containsAll(session.expectedPlayerIds)) {
                return;
            }

            RunStartMovementLockManager.get().lockPlayerForIntro(playerRef);
            session.phase = RunPhase.EXPLORATION;
            session.startedAtEpochMillis = System.currentTimeMillis();
            session.runEndsAtEpochMillis = session.startedAtEpochMillis + this.configuredRunDurationMs;
            session.crimsonStartAtEpochMillis = session.startedAtEpochMillis + CRIMSON_START_DELAY_MS;

            runWorld = Universe.get().getWorld(session.runWorldUuid);
            if (runWorld == null) {
                return;
            }
            snapshot = session.snapshot();
        }

        RescueObjectiveManager.get().spawnRescueOnRunStart(runWorld, snapshot);
        PlayerRef readyPlayer = Universe.get().getPlayer(playerId);
        if (readyPlayer != null) {
            RunStartCameraManager.get().playSpawnIntro(readyPlayer);
        }
    }

    public synchronized boolean isRunWorld(@Nonnull UUID worldUuid) {
        return this.activeSession != null
                && this.activeSession.runWorldUuid != null
                && this.activeSession.runWorldUuid.equals(worldUuid);
    }

    @Nonnull
    private CompletableFuture<World> loadAndInstantiateRunWorld(@Nonnull Universe universe, @Nonnull ActiveSession session) {
        Path configPath = session.runWorldPath.resolve("config.bson");
        if (!Files.exists(configPath)) {
            configPath = session.runWorldPath.resolve("config.json");
        }

        Path finalConfigPath = configPath;
        return WorldConfig.load(finalConfigPath)
                .thenCompose(config -> {
                    config.setUuid(UUID.randomUUID());
                    if (GameFlowConfigManager.get().isStatusMessagesEnabled()) {
                        config.setDisplayName("Run " + session.runWorldName);
                    } else {
                        config.setDisplayName("");
                    }
                    config.setDeleteOnRemove(true);
                    config.markChanged();
                    return universe.makeWorld(session.runWorldName, session.runWorldPath, config);
                });
    }

    @Nonnull
    private CompletableFuture<StartSessionResult> cleanupFailedStart(@Nonnull ActiveSession session, @Nonnull Throwable throwable) {
        Universe universe = Universe.get();
        try {
            if (universe.getWorld(session.runWorldName) != null) {
                universe.removeWorld(session.runWorldName);
            }
            deleteDirectoryIfPresent(session.runWorldPath);
        } catch (Exception ignored) {
        }

        synchronized (this) {
            if (this.activeSession == session) {
                this.activeSession = null;
            }
        }

        cleanupRunRuntime(session.runWorldUuid);

        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        return CompletableFuture.failedFuture(cause);
    }


    @Nonnull
    private static CompletableFuture<Void> movePlayersFromWorld(@Nonnull World fromWorld, @Nonnull World toWorld, @Nullable Transform targetSpawn) {
        UUID fromWorldId = fromWorld.getWorldConfig().getUuid();
        List<CompletableFuture<Void>> transfers = new ArrayList<>();

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorld = playerRef.getWorldUuid();
            if (playerWorld != null && playerWorld.equals(fromWorldId)) {
                transfers.add(movePlayerToWorld(playerRef, fromWorld, toWorld, targetSpawn));
            }
        }

        if (transfers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(transfers.toArray(new CompletableFuture[0]));
    }

    @Nonnull
    private static CompletableFuture<Void> movePlayerToWorld(
            @Nonnull PlayerRef playerRef,
            @Nonnull World fromWorld,
            @Nonnull World toWorld,
            @Nullable Transform targetSpawn
    ) {
        CompletableFuture<Void> teleportCompleted = new CompletableFuture<>();
        Transform destination = PlayerSpawnSafety.sanitizeTransform(
                targetSpawn != null ? copyTransformOrNull(targetSpawn) : playerRef.getTransform()
        );

        return prewarmSpawnChunks(toWorld, destination).thenCompose(ignored -> CompletableFuture.runAsync(() -> {
                    UUID playerWorldUuid = playerRef.getWorldUuid();
                    if (playerWorldUuid == null) {
                        throw new IllegalStateException("Player has no current world before transfer.");
                    }
                    UUID fromWorldUuid = fromWorld.getWorldConfig().getUuid();
                    if (!fromWorldUuid.equals(playerWorldUuid)) {
                        throw new IllegalStateException("Player world mismatch before transfer. expected="
                                + fromWorld.getName() + " actual=" + playerWorldUuid);
                    }

                    Ref<EntityStore> playerRefHandle = playerRef.getReference();
                    if (playerRefHandle == null || !playerRefHandle.isValid()) {
                        throw new IllegalStateException("Player reference is not valid for teleport.");
                    }
                    Store<EntityStore> store = playerRefHandle.getStore();
                    if (store == null) {
                        throw new IllegalStateException("Player store is unavailable for teleport.");
                    }

                    Teleport teleport = destination != null
                            ? Teleport.createForPlayer(toWorld, destination)
                            : Teleport.createForPlayer(toWorld, PlayerSpawnSafety.sanitizeTransform(playerRef.getTransform()));
                    teleport.setOnComplete(teleportCompleted);
                    store.addComponent(playerRefHandle, Teleport.getComponentType(), teleport);
                }, fromWorld))
                .thenCompose(ignored -> teleportCompleted)
                .orTimeout(PLAYER_TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(ignored -> null)
                .handle((ignored, throwable) -> {
                    if (throwable == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                            ? throwable.getCause()
                            : throwable;
                    String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    String detail = "Player transfer failed from '" + fromWorld.getName() + "' to '" + toWorld.getName()
                            + "' for " + playerRef.getUuid() + ": " + reason;
                    return CompletableFuture.<Void>failedFuture(new IllegalStateException(detail, cause));
                })
                .thenCompose(future -> future);
    }

    @Nonnull
    private static CompletableFuture<Void> prewarmSpawnChunks(@Nonnull World world, @Nullable Transform targetSpawn) {
        if (targetSpawn == null) {
            return CompletableFuture.completedFuture(null);
        }

        Transform sanitized = PlayerSpawnSafety.sanitizeTransform(copyTransformOrNull(targetSpawn));
        Vector3d position = sanitized.getPosition();
        int blockX = (int) Math.floor(position.getX());
        int blockZ = (int) Math.floor(position.getZ());
        int centerChunkX = ChunkUtil.chunkCoordinate(blockX);
        int centerChunkZ = ChunkUtil.chunkCoordinate(blockZ);

        ArrayList<CompletableFuture<?>> futures = new ArrayList<>(25);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                long chunkIndex = ChunkUtil.indexChunk(centerChunkX + dx, centerChunkZ + dz);
                futures.add(world.getChunkStore().getChunkReferenceAsync(chunkIndex));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private static void copyDirectory(@Nonnull Path source, @Nonnull Path target) {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Template world path does not exist: " + source);
        }

        try {
            if (Files.exists(target)) {
                deleteDirectoryIfPresent(target);
            }
            try (var stream = Files.walk(source)) {
                stream.forEach(path -> {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString());
                    try {
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else if (Files.isRegularFile(path)) {
                            Path parent = destination.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                });
            }
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static void deleteDirectoryIfPresent(@Nonnull Path path) {
        if (!Files.exists(path)) {
            return;
        }

        CompletionException lastFailure = null;
        for (int attempt = 1; attempt <= WORLD_DELETE_MAX_ATTEMPTS; attempt++) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                });
                return;
            } catch (IOException e) {
                lastFailure = new CompletionException(e);
            } catch (CompletionException e) {
                lastFailure = e;
            }

            if (attempt < WORLD_DELETE_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(WORLD_DELETE_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(interrupted);
                }
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    @Nonnull
    private static String buildRunWorldName(@Nonnull String templateWorldName) {
        String safeTemplate = templateWorldName.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return RUN_WORLD_PREFIX + safeTemplate + "-" + System.currentTimeMillis();
    }

    private static boolean isRunSessionWorldName(@Nullable String worldName) {
        return worldName != null && worldName.startsWith(RUN_WORLD_PREFIX);
    }

    @Nullable
    private static Transform copyTransformOrNull(@Nullable Transform transform) {
        if (transform == null) {
            return null;
        }
        return new Transform(transform.getPosition().clone(), transform.getRotation().clone());
    }

    private static int normalizeRunRadius(int configuredRadius) {
        if (configuredRadius < RedWaveConfig.MIN_RADIUS_BLOCKS || configuredRadius > MAX_RUN_CRIMSON_RADIUS_BLOCKS) {
            return RedWaveConfig.DEFAULT_UI_RADIUS_BLOCKS;
        }
        return configuredRadius;
    }

    private static float normalizeStartSeconds(float configuredSeconds) {
        if (configuredSeconds <= 0.0f || Float.isNaN(configuredSeconds) || Float.isInfinite(configuredSeconds)) {
            return RedWaveConfig.DEFAULT_UI_START_SECONDS;
        }
        return configuredSeconds;
    }

    private static List<PlayerRef> collectPlayersInWorld(@Nonnull UUID worldUuid) {
        ArrayList<PlayerRef> players = new ArrayList<>();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorldUuid = playerRef.getWorldUuid();
            if (playerWorldUuid != null && playerWorldUuid.equals(worldUuid)) {
                players.add(playerRef);
            }
        }
        return List.copyOf(players);
    }

    private static void wipeInventories(@Nonnull List<PlayerRef> playerRefs) {
        for (PlayerRef playerRef : playerRefs) {
            dev.hytalemodding.npc.economy.NpcInventoryService.clearAll(playerRef);
        }
    }

    private static void cleanupRunRuntime(@Nullable UUID runWorldUuid) {
        if (runWorldUuid == null) {
            return;
        }
        RedWaveManager.clearRuntime(runWorldUuid);
        RedCoreRegistry.clear(runWorldUuid);
        RedCoreProfileRegistry.clear(runWorldUuid);
        RooterManManager.get().clearRuntimeForWorld(runWorldUuid);
        LootChestRuntime.get().clearWorld(runWorldUuid);
    }

    private static void scrubRunWorldMarkersAndUnusedCores(@Nonnull World runWorld, @Nonnull ActiveSession session) {
        scrubSpawnPointBlocks(runWorld, session.templateWorldName);
        scrubUnusedCrimsonCoreBlocks(runWorld, session.templateWorldName, session.crimsonProfiles);
    }

    private static void customizeRunWorld(@Nonnull World runWorld, @Nonnull ActiveSession session) {
        runWorld.getWorldConfig().setIsAllNPCFrozen(false);
        runWorld.getWorldConfig().markChanged();
        int selectedHour = chooseRunHour();
        session.appliedRunHour = selectedHour;
        applyRunWorldTimeSet(runWorld, selectedHour);
        configureQuestChest(runWorld, session.templateWorldName);
        replaceRandomBlightBeastWithRooter(runWorld);
    }

    private static void configureQuestChest(@Nonnull World runWorld, @Nonnull String templateWorldName) {
        QuestChestConfigManager.QuestChestDefinition definition = QuestChestConfigManager.get().getForTemplateWorld(templateWorldName);
        if (definition == null || runWorld.getWorldConfig() == null || runWorld.getWorldConfig().getUuid() == null) {
            return;
        }
        Vector3i pos = QuestChestPositionManager.get().getPosition(definition.chestId(), templateWorldName);
        if (pos == null) {
            return;
        }
        UUID worldUuid = runWorld.getWorldConfig().getUuid();
        QuestProgressManager.QuestProgress questProgress = QuestProgressManager.get().getOrCreate(definition.questId());
        boolean shouldPlaceHammer = questProgress.accepted && !questProgress.completed;
        LootChestRuntime runtime = LootChestRuntime.get();
        LootChestAccess.ResolvedChest chest = LootChestAccess.resolveChest(runWorld, pos);
        if (chest == null || chest.blockId() == null || !definition.blockId().equalsIgnoreCase(chest.blockId())) {
            return;
        }

        ItemStack contents = shouldPlaceHammer
                ? new ItemStack(definition.rewardItemId(), definition.rewardAmount())
                : new ItemStack(definition.fallbackItemId(), randomInRange(definition.fallbackMinAmount(), definition.fallbackMaxAmount()));
        if (!LootChestAccess.populate(chest, List.of(contents))) {
            return;
        }
        runtime.processedChests().add(LifeEssenceContainerKey.of(worldUuid, pos));
        if (shouldPlaceHammer) {
            runtime.registerQuestChest(new LootChestRuntime.QuestChestState(
                    worldUuid,
                    pos,
                    definition.markerId(),
                    definition.markerName(),
                    definition.markerIcon(),
                    definition.markerColor(),
                    true
            ));
        }
    }

    private static int randomInRange(int min, int max) {
        if (max <= min) {
            return Math.max(1, min);
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static int chooseRunHour() {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        int minHour = Math.max(0, Math.min(23, config.getRunTimeHourMin()));
        int maxHour = Math.max(0, Math.min(23, config.getRunTimeHourMax()));
        if (maxHour < minHour) {
            int tmp = minHour;
            minHour = maxHour;
            maxHour = tmp;
        }
        if (maxHour == minHour) {
            return minHour;
        }
        return ThreadLocalRandom.current().nextInt(minHour, maxHour + 1);
    }

    private static void applyRunWorldTimeSet(@Nonnull World runWorld, int selectedHour) {
        int clampedHour = Math.max(0, Math.min(23, selectedHour));
        Store<EntityStore> store = runWorld.getEntityStore().getStore();
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        if (worldTime == null) {
            return;
        }
        double dayFraction = clampedHour / (double) WorldTimeResource.HOURS_PER_DAY;
        worldTime.setDayTime(dayFraction, runWorld, store);
    }

    private static void replaceRandomBlightBeastWithRooter(@Nonnull World runWorld) {
        Store<EntityStore> store = runWorld.getEntityStore().getStore();
        ArrayList<Ref<EntityStore>> candidates = new ArrayList<>();
        ArrayList<Transform> candidateTransforms = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !BLIGHT_BEAST_ROLE.equalsIgnoreCase(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) {
                    continue;
                }
                candidates.add(npcRef);
                candidateTransforms.add(new Transform(
                        new Vector3d(transform.getPosition()),
                        new Vector3f(transform.getRotation())
                ));
            }
        });

        if (candidates.isEmpty()) {
            return;
        }

        String rooterRole = RooterConfig.get().getBossRole();
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(rooterRole);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            System.out.println("[GameSessionManager] Rooter replacement skipped; role unavailable: " + rooterRole);
            return;
        }

        int selectedIndex = ThreadLocalRandom.current().nextInt(candidates.size());
        Ref<EntityStore> beastRef = candidates.get(selectedIndex);
        Transform spawnTransform = candidateTransforms.get(selectedIndex);

        store.removeEntity(beastRef, RemoveReason.REMOVE);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                store,
                roleIndex,
                new Vector3d(spawnTransform.getPosition()),
                new Vector3f(spawnTransform.getRotation()),
                null,
                null
        );
        if (spawned == null || spawned.first() == null || !spawned.first().isValid()) {
            System.out.println("[GameSessionManager] Failed to replace Blight Beast with Rooter Man.");
        }
    }

    private static void scrubSpawnPointBlocks(@Nonnull World runWorld, @Nonnull String templateWorldName) {
        SpawnPointZoneConfigManager.SpawnZoneState spawnState = SpawnPointZoneConfigManager.load(templateWorldName);
        for (LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap : spawnState.zones().values()) {
            for (ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> entries : locationMap.values()) {
                for (SpawnPointZoneConfigManager.SpawnPointEntry entry : entries) {
                    if (!entry.dimension().equalsIgnoreCase(templateWorldName)) {
                        continue;
                    }
                    runWorld.setBlock(entry.position().x, entry.position().y, entry.position().z, "Empty");
                }
            }
        }
    }

    private static void scrubUnusedCrimsonCoreBlocks(
            @Nonnull World runWorld,
            @Nonnull String templateWorldName,
            @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> selectedProfiles
    ) {
        List<RedCoreProfileRegistry.RedCoreProfile> savedProfiles = CrimsonCoreConfigManager.get().getState(templateWorldName).profiles();
        for (RedCoreProfileRegistry.RedCoreProfile savedProfile : savedProfiles) {
            if (containsProfile(selectedProfiles, savedProfile.corePos())) {
                continue;
            }
            Vector3i pos = savedProfile.corePos();
            var type = runWorld.getBlockType(pos.x, pos.y, pos.z);
            if (type != null && RedWaveConfig.isCoreBlockId(type.getId())) {
                runWorld.setBlock(pos.x, pos.y, pos.z, "Empty");
            }
        }
    }

    private static boolean containsProfile(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles, @Nonnull Vector3i pos) {
        for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
            if (profile.corePos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static List<RedCoreProfileRegistry.RedCoreProfile> selectRunProfiles(
            @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> validProfiles,
            int configuredChooseCount,
            long runSeed
    ) {
        if (validProfiles.isEmpty()) {
            return List.of();
        }

        int chooseCount = configuredChooseCount;
        if (chooseCount < 1) {
            chooseCount = 1;
        }
        chooseCount = Math.min(chooseCount, validProfiles.size());
        if (chooseCount >= validProfiles.size()) {
            return copyProfiles(validProfiles);
        }

        ArrayList<RedCoreProfileRegistry.RedCoreProfile> shuffled = new ArrayList<>(validProfiles.size());
        for (RedCoreProfileRegistry.RedCoreProfile profile : validProfiles) {
            shuffled.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }
        Collections.shuffle(shuffled, new java.util.Random(runSeed));

        ArrayList<RedCoreProfileRegistry.RedCoreProfile> selected = new ArrayList<>(shuffled.subList(0, chooseCount));
        selected.sort(Comparator
                .comparingInt((RedCoreProfileRegistry.RedCoreProfile p) -> p.corePos().x)
                .thenComparingInt(p -> p.corePos().y)
                .thenComparingInt(p -> p.corePos().z));
        return selected;
    }

    public enum RunPhase {
        IDLE,
        PREPARING,
        WAITING_FOR_PLAYERS_READY,
        EXPLORATION,
        CRIMSON_ACTIVE,
        ENDING
    }

    private static final class ActiveSession {
        @Nonnull
        private final String templateWorldName;
        @Nonnull
        private final String runWorldName;
        @Nonnull
        private final Path runWorldPath;
        @Nonnull
        private final UUID starterPlayerId;
        @Nonnull
        private final List<RedCoreProfileRegistry.RedCoreProfile> crimsonProfiles;
        @Nullable
        private final Transform runSpawnTransform;
        @Nullable
        private final Transform returnSpawnTransform;
        @Nullable
        private UUID runWorldUuid;
        @Nonnull
        private final Set<UUID> expectedPlayerIds = new LinkedHashSet<>();
        @Nonnull
        private final Set<UUID> readyPlayerIds = new LinkedHashSet<>();
        @Nonnull
        private RunPhase phase = RunPhase.IDLE;
        private long startedAtEpochMillis;
        private long runEndsAtEpochMillis;
        private long crimsonStartAtEpochMillis;
        private int appliedRunHour = -1;

        private ActiveSession(
                @Nonnull String templateWorldName,
                @Nonnull String runWorldName,
                @Nonnull Path runWorldPath,
                @Nonnull UUID starterPlayerId,
                @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> crimsonProfiles,
                @Nullable Transform runSpawnTransform,
                @Nullable Transform returnSpawnTransform
        ) {
            this.templateWorldName = templateWorldName;
            this.runWorldName = runWorldName;
            this.runWorldPath = runWorldPath;
            this.starterPlayerId = starterPlayerId;
            this.crimsonProfiles = copyProfiles(crimsonProfiles);
            this.runSpawnTransform = runSpawnTransform;
            this.returnSpawnTransform = returnSpawnTransform;
            this.expectedPlayerIds.add(starterPlayerId);
        }

        @Nonnull
        private ActiveSessionSnapshot snapshot() {
            return new ActiveSessionSnapshot(
                    this.templateWorldName,
                    this.runWorldName,
                    this.starterPlayerId,
                    this.runWorldUuid,
                    this.phase,
                    this.startedAtEpochMillis,
                    this.runEndsAtEpochMillis,
                    this.crimsonStartAtEpochMillis,
                    copyProfiles(this.crimsonProfiles)
            );
        }
    }

    public record StartSessionResult(
            @Nonnull String templateWorldName,
            @Nonnull String runWorldName,
            @Nonnull UUID runWorldUuid,
            long crimsonStartAtEpochMillis,
            long runEndsAtEpochMillis
    ) {
    }

    public record EndSessionResult(
            boolean success,
            @Nullable String runWorldName,
            @Nullable String templateWorldName,
            @Nonnull String message
    ) {
    }

    public record ActiveSessionSnapshot(
            @Nonnull String templateWorldName,
            @Nonnull String runWorldName,
            @Nonnull UUID starterPlayerId,
            @Nullable UUID runWorldUuid,
            @Nonnull RunPhase phase,
            long startedAtEpochMillis,
            long runEndsAtEpochMillis,
            long crimsonStartAtEpochMillis,
            @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> crimsonProfiles
    ) {
        public boolean crimsonEnabled() {
            return !this.crimsonProfiles.isEmpty();
        }
    }

    @Nonnull
    private static List<RedCoreProfileRegistry.RedCoreProfile> copyProfiles(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> copy = new ArrayList<>(profiles.size());
        for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
            copy.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }
        return copy;
    }
}