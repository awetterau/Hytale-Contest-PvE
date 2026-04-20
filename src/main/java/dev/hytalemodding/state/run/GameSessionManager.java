package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import dev.hytalemodding.blob.OrangeBlobBlockManager;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class GameSessionManager {
    private static final String BLIGHT_BEAST_ROLE = "Blight_Beast";
    private static final String RUN_WORLD_PREFIX = "run-session-";
    private static final long CRIMSON_START_DELAY_MS = 10_000L;
    private static final long RUN_START_PRE_TELEPORT_DELAY_MS = 1_000L;
    private static final int GAME_TIME_MINUTES = 20;
    private static final long RUN_DURATION_MS = GAME_TIME_MINUTES * 60L * 1000L;
    private static final ConcurrentHashMap<UUID, List<Ref<ChunkStore>>> PINNED_CHUNK_REFS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, LongSet> PINNED_CHUNK_INDICES_BY_RUN_WORLD = new ConcurrentHashMap<>();
    private static final float DEFAULT_CRIMSON_SPREAD_SECONDS = RUN_DURATION_MS / 1000.0f;
    private static final int MAX_RUN_CRIMSON_RADIUS_BLOCKS = 24;
    private static final long PLAYER_TRANSFER_TIMEOUT_SECONDS = 15L;
    private static final long WORLD_COPY_TIMEOUT_SECONDS = 90L;
    private static final long WORLD_LOAD_TIMEOUT_SECONDS = 30L;
    private static final int WORLD_DELETE_MAX_ATTEMPTS = 20;
    private static final long WORLD_DELETE_RETRY_DELAY_MS = 100L;
    private static final long EMPTY_RUN_AUTO_END_DELAY_MS = 20_000L;
    private static final GameSessionManager INSTANCE = new GameSessionManager();
    private static volatile boolean dynamicMapRefreshEnabled = true;
    private static final ConcurrentHashMap<UUID, Long> RUN_ELAPSED_MS_CACHE_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<UUID>> RUN_PARTICIPANTS_CACHE_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PlayerRunCategory> PLAYER_RUN_CATEGORY_CACHE = new ConcurrentHashMap<>();

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
        return startSession(starter, templateWorld, runSpawnTransform, returnSpawnTransform, List.of(starter.getUuid()), Map.of());
    }

    @Nonnull
    public CompletableFuture<StartSessionResult> startSession(
            @Nonnull PlayerRef starter,
            @Nonnull World templateWorld,
            @Nullable Transform runSpawnTransform,
            @Nullable Transform returnSpawnTransform,
            @Nonnull List<UUID> expectedPlayerIds,
            @Nonnull Map<UUID, Transform> spawnTransformByPlayer
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
                    copyTransformOrNull(returnSpawnTransform),
                    expectedPlayerIds,
                    spawnTransformByPlayer
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
            return movePlayersToRunWorld(starter, playerWorld, runWorld, session).thenApply(x -> runWorld);
        });

        return transferFuture
                .thenApply(runWorld -> {
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
                    for (UUID playerId : session.expectedPlayerIds) {
                        PlayerRunCategory category = session.participantCategoryByPlayer.getOrDefault(playerId, PlayerRunCategory.NONE);
                        if (category == PlayerRunCategory.ACTIVE) {
                            category = PlayerRunCategory.RETURNED_LOBBY;
                        } else if (category == PlayerRunCategory.NONE) {
                            category = PlayerRunCategory.DISCONNECTED;
                        }
                        PLAYER_RUN_CATEGORY_CACHE.put(playerId, category);
                        applyRunPlayerTag(playerId, category);
                    }
                    if (session.runWorldUuid != null) {
                        RUN_ELAPSED_MS_CACHE_BY_WORLD.remove(session.runWorldUuid);
                        RUN_PARTICIPANTS_CACHE_BY_WORLD.remove(session.runWorldUuid);
                    }
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
                    removeWorldOnOwningThread(universe, session.runWorldName);
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

    public synchronized void markPlayerCategory(@Nonnull UUID playerId, @Nonnull PlayerRunCategory category) {
        if (this.activeSession == null || !this.activeSession.expectedPlayerIds.contains(playerId)) {
            return;
        }
        this.activeSession.participantCategoryByPlayer.put(playerId, category);
        this.activeSession.participantsEverJoined.add(playerId);
        PLAYER_RUN_CATEGORY_CACHE.put(playerId, category);
        applyRunPlayerTag(playerId, category);
    }

    public synchronized void markPlayerDisconnected(@Nonnull UUID playerId) {
        if (this.activeSession == null || !this.activeSession.expectedPlayerIds.contains(playerId)) {
            return;
        }
        this.activeSession.participantCategoryByPlayer.put(playerId, PlayerRunCategory.DISCONNECTED);
        PLAYER_RUN_CATEGORY_CACHE.put(playerId, PlayerRunCategory.DISCONNECTED);
        applyRunPlayerTag(playerId, PlayerRunCategory.DISCONNECTED);
    }

    public synchronized boolean canRejoinActiveRun(@Nonnull UUID playerId) {
        if (this.activeSession == null
                || this.activeSession.runWorldUuid == null
                || (this.activeSession.phase != RunPhase.WAITING_FOR_PLAYERS_READY
                && this.activeSession.phase != RunPhase.EXPLORATION
                && this.activeSession.phase != RunPhase.CRIMSON_ACTIVE)) {
            return false;
        }
        return this.activeSession.expectedPlayerIds.contains(playerId);
    }

    public synchronized boolean shouldAutoEndForParticipants() {
        if (this.activeSession == null || this.activeSession.runWorldUuid == null) {
            return false;
        }
        if (this.activeSession.participantsEverJoined.isEmpty()) {
            return false;
        }
        for (UUID playerId : this.activeSession.participantsEverJoined) {
            PlayerRunCategory category = this.activeSession.participantCategoryByPlayer.getOrDefault(playerId, PlayerRunCategory.ACTIVE);
            if (category != PlayerRunCategory.EXTRACTED && category != PlayerRunCategory.DEAD) {
                return false;
            }
        }
        return true;
    }

    public synchronized boolean shouldAutoEndForEmptyRunWorld() {
        if (this.activeSession == null || this.activeSession.runWorldUuid == null) {
            return false;
        }
        List<PlayerRef> players = collectPlayersInWorld(this.activeSession.runWorldUuid);
        if (!players.isEmpty()) {
            this.activeSession.emptyWorldSinceEpochMs = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (this.activeSession.emptyWorldSinceEpochMs <= 0L) {
            this.activeSession.emptyWorldSinceEpochMs = now;
            return false;
        }
        return now - this.activeSession.emptyWorldSinceEpochMs >= EMPTY_RUN_AUTO_END_DELAY_MS;
    }

    public synchronized void refreshParticipantConnectionStates() {
        if (this.activeSession == null || this.activeSession.runWorldUuid == null) {
            return;
        }
        for (UUID playerId : this.activeSession.expectedPlayerIds) {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            PlayerRunCategory current = this.activeSession.participantCategoryByPlayer.getOrDefault(playerId, PlayerRunCategory.NONE);
            if (playerRef == null || !playerRef.isValid()) {
                if (current == PlayerRunCategory.ACTIVE || current == PlayerRunCategory.NONE) {
                    this.activeSession.participantCategoryByPlayer.put(playerId, PlayerRunCategory.DISCONNECTED);
                    PLAYER_RUN_CATEGORY_CACHE.put(playerId, PlayerRunCategory.DISCONNECTED);
                    applyRunPlayerTag(playerId, PlayerRunCategory.DISCONNECTED);
                }
                continue;
            }
            if (playerRef.getWorldUuid() != null && playerRef.getWorldUuid().equals(this.activeSession.runWorldUuid)) {
                this.activeSession.participantsEverJoined.add(playerId);
                if (current == PlayerRunCategory.DISCONNECTED || current == PlayerRunCategory.NONE) {
                    this.activeSession.participantCategoryByPlayer.put(playerId, PlayerRunCategory.ACTIVE);
                    PLAYER_RUN_CATEGORY_CACHE.put(playerId, PlayerRunCategory.ACTIVE);
                    applyRunPlayerTag(playerId, PlayerRunCategory.ACTIVE);
                }
            }
        }
    }

    public synchronized long getRunElapsedMillisCached(@Nullable UUID runWorldId) {
        if (runWorldId == null) {
            return 0L;
        }
        return RUN_ELAPSED_MS_CACHE_BY_WORLD.getOrDefault(runWorldId, 0L);
    }

    @Nonnull
    public synchronized List<UUID> getRunParticipantsCached(@Nullable UUID runWorldId) {
        if (runWorldId == null) {
            return List.of();
        }
        return RUN_PARTICIPANTS_CACHE_BY_WORLD.getOrDefault(runWorldId, List.of());
    }

    @Nonnull
    public synchronized PlayerRunCategory getCachedPlayerCategory(@Nonnull UUID playerId) {
        return PLAYER_RUN_CATEGORY_CACHE.getOrDefault(playerId, PlayerRunCategory.NONE);
    }

    private static void applyRunPlayerTag(@Nonnull UUID playerId, @Nonnull PlayerRunCategory category) {
        switch (category) {
            case EXTRACTED -> RunPlayerTagManager.addTag(playerId, RunPlayerTagManager.EXTRACTED_TAG);
            case DEAD -> RunPlayerTagManager.addTag(playerId, RunPlayerTagManager.DEAD_TAG);
            case DISCONNECTED -> RunPlayerTagManager.addTag(playerId, RunPlayerTagManager.DISCONNECTED_TAG);
            case SPECTATING -> RunPlayerTagManager.addTag(playerId, RunPlayerTagManager.SPECTATING_TAG);
            case ACTIVE, RETURNED_LOBBY, NONE -> RunPlayerTagManager.removeTag(playerId, RunPlayerTagManager.EXTRACTED_TAG);
        }
        if (category == PlayerRunCategory.ACTIVE || category == PlayerRunCategory.RETURNED_LOBBY || category == PlayerRunCategory.NONE) {
            RunPlayerTagManager.removeTag(playerId, RunPlayerTagManager.DEAD_TAG);
            RunPlayerTagManager.removeTag(playerId, RunPlayerTagManager.DISCONNECTED_TAG);
            RunPlayerTagManager.removeTag(playerId, RunPlayerTagManager.SPECTATING_TAG);
        }
    }

    public synchronized long getRemainingMillis() {
        if (this.activeSession == null
                || (this.activeSession.phase != RunPhase.EXPLORATION && this.activeSession.phase != RunPhase.CRIMSON_ACTIVE)) {
            return 0L;
        }
        long remaining = Math.max(0L, this.activeSession.runEndsAtEpochMillis - System.currentTimeMillis());
        if (this.activeSession.runWorldUuid != null && this.activeSession.startedAtEpochMillis > 0L) {
            RUN_ELAPSED_MS_CACHE_BY_WORLD.put(
                    this.activeSession.runWorldUuid,
                    Math.max(0L, System.currentTimeMillis() - this.activeSession.startedAtEpochMillis)
            );
            RUN_PARTICIPANTS_CACHE_BY_WORLD.put(this.activeSession.runWorldUuid, List.copyOf(this.activeSession.expectedPlayerIds));
        }
        return remaining;
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
        if (canRejoinActiveRun(playerId)) {
            handlePlayerReconnect(readyPlayerRef);
        }
        World runWorld;
        ActiveSessionSnapshot snapshot;
        List<UUID> playersToPlayIntro;

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
            session.participantsEverJoined.add(playerId);
            session.participantCategoryByPlayer.put(playerId, PlayerRunCategory.ACTIVE);
            PLAYER_RUN_CATEGORY_CACHE.put(playerId, PlayerRunCategory.ACTIVE);
            applyRunPlayerTag(playerId, PlayerRunCategory.ACTIVE);
            if (!session.readyPlayerIds.containsAll(session.expectedPlayerIds)) {
                return;
            }

            session.phase = RunPhase.EXPLORATION;
            session.startedAtEpochMillis = System.currentTimeMillis();
            session.runEndsAtEpochMillis = session.startedAtEpochMillis + this.configuredRunDurationMs;
            session.crimsonStartAtEpochMillis = session.startedAtEpochMillis + CRIMSON_START_DELAY_MS;
            RUN_PARTICIPANTS_CACHE_BY_WORLD.put(session.runWorldUuid, List.copyOf(session.expectedPlayerIds));
            playersToPlayIntro = List.copyOf(session.expectedPlayerIds);

            runWorld = Universe.get().getWorld(session.runWorldUuid);
            if (runWorld == null) {
                return;
            }
            snapshot = session.snapshot();
        }

        RescueObjectiveManager.get().spawnRescueOnRunStart(runWorld, snapshot);
        for (UUID introPlayerId : playersToPlayIntro) {
            PlayerRef introPlayer = Universe.get().getPlayer(introPlayerId);
            if (introPlayer != null) {
                RunStartCameraManager.get().playSpawnIntro(introPlayer);
            }
        }
    }

    public void onClientReadyPacket(@Nonnull UUID playerId, boolean readyForGameplay) {
        if (!readyForGameplay) {
            return;
        }
        if (canRejoinActiveRun(playerId)) {
            PlayerRef reconnecting = Universe.get().getPlayer(playerId);
            if (reconnecting != null) {
                handlePlayerReconnect(reconnecting);
            }
        }
        World runWorld;
        ActiveSessionSnapshot snapshot;
        List<UUID> playersToPlayIntro;

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
            session.participantsEverJoined.add(playerId);
            session.participantCategoryByPlayer.put(playerId, PlayerRunCategory.ACTIVE);
            PLAYER_RUN_CATEGORY_CACHE.put(playerId, PlayerRunCategory.ACTIVE);
            applyRunPlayerTag(playerId, PlayerRunCategory.ACTIVE);
            if (!session.readyPlayerIds.containsAll(session.expectedPlayerIds)) {
                return;
            }

            session.phase = RunPhase.EXPLORATION;
            session.startedAtEpochMillis = System.currentTimeMillis();
            session.runEndsAtEpochMillis = session.startedAtEpochMillis + this.configuredRunDurationMs;
            session.crimsonStartAtEpochMillis = session.startedAtEpochMillis + CRIMSON_START_DELAY_MS;
            RUN_PARTICIPANTS_CACHE_BY_WORLD.put(session.runWorldUuid, List.copyOf(session.expectedPlayerIds));
            playersToPlayIntro = List.copyOf(session.expectedPlayerIds);

            runWorld = Universe.get().getWorld(session.runWorldUuid);
            if (runWorld == null) {
                return;
            }
            snapshot = session.snapshot();
        }

        RescueObjectiveManager.get().spawnRescueOnRunStart(runWorld, snapshot);
        for (UUID introPlayerId : playersToPlayIntro) {
            PlayerRef introPlayer = Universe.get().getPlayer(introPlayerId);
            if (introPlayer != null) {
                RunStartCameraManager.get().playSpawnIntro(introPlayer);
            }
        }
    }

    public synchronized boolean isRunWorld(@Nonnull UUID worldUuid) {
        return this.activeSession != null
                && this.activeSession.runWorldUuid != null
                && this.activeSession.runWorldUuid.equals(worldUuid);
    }

    public synchronized boolean isActiveRunWorldCandidate(@Nonnull UUID worldUuid, @Nonnull String worldName) {
        if (this.activeSession == null) {
            return false;
        }
        if (this.activeSession.runWorldUuid != null && this.activeSession.runWorldUuid.equals(worldUuid)) {
            return true;
        }
        return this.activeSession.phase == RunPhase.PREPARING
                && this.activeSession.runWorldName.equals(worldName);
    }

    private void handlePlayerReconnect(@Nonnull PlayerRef playerRef) {
        final ActiveSession session;
        final UUID runWorldUuid;
        synchronized (this) {
            if (this.activeSession == null
                    || this.activeSession.runWorldUuid == null
                    || !this.activeSession.expectedPlayerIds.contains(playerRef.getUuid())) {
                return;
            }
            session = this.activeSession;
            runWorldUuid = session.runWorldUuid;
        }

        World runWorld = Universe.get().getWorld(runWorldUuid);
        UUID currentWorldId = playerRef.getWorldUuid();
        if (runWorld == null || currentWorldId == null) {
            return;
        }
        if (runWorldUuid.equals(currentWorldId)) {
            synchronized (this) {
                if (this.activeSession == session) {
                    session.participantCategoryByPlayer.put(playerRef.getUuid(), PlayerRunCategory.ACTIVE);
                    session.participantsEverJoined.add(playerRef.getUuid());
                    PLAYER_RUN_CATEGORY_CACHE.put(playerRef.getUuid(), PlayerRunCategory.ACTIVE);
                    applyRunPlayerTag(playerRef.getUuid(), PlayerRunCategory.ACTIVE);
                }
            }
            return;
        }

        World fromWorld = Universe.get().getWorld(currentWorldId);
        if (fromWorld == null) {
            return;
        }
        Transform personalSpawn;
        synchronized (this) {
            if (this.activeSession != session) {
                return;
            }
            personalSpawn = session.runSpawnTransformByPlayer.getOrDefault(playerRef.getUuid(), session.runSpawnTransform);
        }
        movePlayerToWorld(playerRef, fromWorld, runWorld, personalSpawn)
                .whenComplete((ignored, throwable) -> {
                    synchronized (this) {
                        if (this.activeSession != session) {
                            return;
                        }
                        if (throwable == null) {
                            session.participantCategoryByPlayer.put(playerRef.getUuid(), PlayerRunCategory.ACTIVE);
                            session.participantsEverJoined.add(playerRef.getUuid());
                            session.readyPlayerIds.add(playerRef.getUuid());
                            PLAYER_RUN_CATEGORY_CACHE.put(playerRef.getUuid(), PlayerRunCategory.ACTIVE);
                            applyRunPlayerTag(playerRef.getUuid(), PlayerRunCategory.ACTIVE);
                        } else {
                            session.participantCategoryByPlayer.put(playerRef.getUuid(), PlayerRunCategory.DISCONNECTED);
                            PLAYER_RUN_CATEGORY_CACHE.put(playerRef.getUuid(), PlayerRunCategory.DISCONNECTED);
                            applyRunPlayerTag(playerRef.getUuid(), PlayerRunCategory.DISCONNECTED);
                        }
                    }
                });
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
            removeWorldOnOwningThread(universe, session.runWorldName);
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
    private static CompletableFuture<Void> movePlayersToRunWorld(
            @Nonnull PlayerRef starter,
            @Nonnull World fromWorld,
            @Nonnull World toWorld,
            @Nonnull ActiveSession session
    ) {
        List<CompletableFuture<Void>> transfers = new ArrayList<>();
        for (UUID playerId : session.expectedPlayerIds) {
            PlayerRef partyPlayer = Universe.get().getPlayer(playerId);
            if (partyPlayer == null || !partyPlayer.isValid() || partyPlayer.getWorldUuid() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Player unavailable for run start: " + playerId));
            }
            if (!fromWorld.getWorldConfig().getUuid().equals(partyPlayer.getWorldUuid())) {
                return CompletableFuture.failedFuture(new IllegalStateException("All team members must be in hub before start."));
            }
            Transform personalSpawn = session.runSpawnTransformByPlayer.getOrDefault(playerId, session.runSpawnTransform);
            boolean applyPreTeleportDelay = playerId.equals(starter.getUuid());
            transfers.add(movePlayerToWorld(
                    partyPlayer,
                    fromWorld,
                    toWorld,
                    personalSpawn,
                    applyPreTeleportDelay,
                    session.templateWorldName
            ));
        }
        if (transfers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No players available to move to run world."));
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
        return movePlayerToWorld(playerRef, fromWorld, toWorld, targetSpawn, false, fromWorld.getName());
    }

    @Nonnull
    private static CompletableFuture<Void> movePlayerToWorld(
            @Nonnull PlayerRef playerRef,
            @Nonnull World fromWorld,
            @Nonnull World toWorld,
            @Nullable Transform targetSpawn,
            boolean applyRunStartPreTeleportDelay,
            @Nonnull String selectionWorldName
    ) {
        CompletableFuture<Void> teleportCompleted = new CompletableFuture<>();
        Transform destination = PlayerSpawnSafety.sanitizeTransform(
                targetSpawn != null ? copyTransformOrNull(targetSpawn) : playerRef.getTransform()
        );

        return prewarmSpawnChunks(toWorld, destination, playerRef, selectionWorldName).thenCompose(ignored -> CompletableFuture.runAsync(() -> {
                    if (applyRunStartPreTeleportDelay) {
                        try {
                            Thread.sleep(RUN_START_PRE_TELEPORT_DELAY_MS);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    }
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
    private static CompletableFuture<Void> prewarmSpawnChunks(
            @Nonnull World world,
            @Nullable Transform targetSpawn,
            @Nonnull PlayerRef playerRef,
            @Nonnull String selectionWorldName
    ) {
        if (targetSpawn == null) {
            return CompletableFuture.completedFuture(null);
        }

        Transform sanitized = PlayerSpawnSafety.sanitizeTransform(copyTransformOrNull(targetSpawn));
        Vector3d position = sanitized.getPosition();
        int blockX = (int) Math.floor(position.getX());
        int blockZ = (int) Math.floor(position.getZ());
        int centerChunkX = ChunkUtil.chunkCoordinate(blockX);
        int centerChunkZ = ChunkUtil.chunkCoordinate(blockZ);

        RunChunkSelectionManager selectionManager = RunChunkSelectionManager.get();
        Set<RunChunkSelectionManager.ChunkPosKey> selectedChunks = selectionManager.getSelectedChunks(selectionWorldName);
        LongSet pinnedChunkIndices = selectionManager.getPinnedChunkIndices(selectionWorldName);
        LongSet chunksToLoad = new LongOpenHashSet(Math.max(25, selectedChunks.size() + 25));

        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                long chunkIndex = ChunkUtil.indexChunk(centerChunkX + dx, centerChunkZ + dz);
                chunksToLoad.add(chunkIndex);
            }
        }
        for (RunChunkSelectionManager.ChunkPosKey selected : selectedChunks) {
            chunksToLoad.add(ChunkUtil.indexChunk(selected.x(), selected.z()));
        }

        boolean notifyLoadingProgress = GameFlowConfigManager.get().isChunkLoadingMessagesEnabled();
        int totalChunks = chunksToLoad.size();
        if (notifyLoadingProgress && totalChunks > 0) {
            playerRef.sendMessage(Message.raw("[Run] Loading chunks before TP: 0/" + totalChunks + " (pinned=" + pinnedChunkIndices.size() + ")"));
        }

        AtomicInteger loadedCount = new AtomicInteger(0);
        ArrayList<CompletableFuture<?>> futures = new ArrayList<>(totalChunks);
        UUID worldId = world.getWorldConfig().getUuid();
        if (!pinnedChunkIndices.isEmpty()) {
            PINNED_CHUNK_INDICES_BY_RUN_WORLD.put(worldId, new LongOpenHashSet(pinnedChunkIndices));
        } else {
            PINNED_CHUNK_INDICES_BY_RUN_WORLD.remove(worldId);
        }
        for (long chunkIndex : chunksToLoad) {
            CompletableFuture<Ref<ChunkStore>> chunkFuture = world.getChunkStore().getChunkReferenceAsync(chunkIndex);
            futures.add(chunkFuture.thenAcceptAsync(chunkRef -> {
                if (chunkRef != null && chunkRef.isValid()) {
                    RunEnvironmentPainter.paintRunDefaultEnvironmentOnChunk(world, chunkRef);
                }
                if (chunkRef != null && pinnedChunkIndices.contains(chunkIndex)) {
                    PINNED_CHUNK_REFS_BY_WORLD.computeIfAbsent(worldId, ignored -> Collections.synchronizedList(new ArrayList<>()))
                            .add(chunkRef);
                    WorldChunk pinnedChunk = world.getChunkStore().getChunkComponent(chunkIndex, WorldChunk.getComponentType());
                    if (pinnedChunk != null) {
                        try {
                            pinnedChunk.addKeepLoaded();
                            pinnedChunk.resetKeepAlive();
                            pinnedChunk.resetActiveTimer();
                            pinnedChunk.setFlag(ChunkFlag.TICKING, true);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (notifyLoadingProgress) {
                    int current = loadedCount.incrementAndGet();
                    if (current == totalChunks || current % 100 == 0) {
                        playerRef.sendMessage(Message.raw("[Run] Loading chunks before TP: " + current + "/" + totalChunks));
                    }
                }
            }, world));
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
        LongSet pinnedIndices = PINNED_CHUNK_INDICES_BY_RUN_WORLD.remove(runWorldUuid);
        World runWorld = Universe.get().getWorld(runWorldUuid);
        if (runWorld != null && pinnedIndices != null && !pinnedIndices.isEmpty()) {
            runWorld.execute(() -> {
                ChunkStore chunkStore = runWorld.getChunkStore();
                for (long chunkIndex : pinnedIndices) {
                    WorldChunk worldChunk = chunkStore.getChunkComponent(chunkIndex, WorldChunk.getComponentType());
                    if (worldChunk == null) {
                        continue;
                    }
                    try {
                        worldChunk.removeKeepLoaded();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        PINNED_CHUNK_REFS_BY_WORLD.remove(runWorldUuid);
        RedWaveManager.clearRuntime(runWorldUuid);
        RedCoreRegistry.clear(runWorldUuid);
        RedCoreProfileRegistry.clear(runWorldUuid);
        RooterManManager.get().clearRuntimeForWorld(runWorldUuid);
        LootChestRuntime.get().clearWorld(runWorldUuid);
        OrangeBlobBlockManager.clearRuntimeForWorld(runWorldUuid);
    }

    private static void removeWorldOnOwningThread(@Nonnull Universe universe, @Nonnull String worldName) {
        World existing = universe.getWorld(worldName);
        if (existing == null) {
            return;
        }
        CompletableFuture<Void> removalFuture = new CompletableFuture<>();
        existing.execute(() -> {
            try {
                universe.removeWorld(worldName);
                removalFuture.complete(null);
            } catch (Throwable throwable) {
                removalFuture.completeExceptionally(throwable);
            }
        });
        try {
            removalFuture.get(15L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public static void maintainPinnedRunChunks(@Nonnull World world) {
        UUID worldId = world.getWorldConfig().getUuid();
        if (!isPinnedKeepAliveActiveForWorld(worldId)) {
            return;
        }
        ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot != null) {
            RunChunkSelectionManager.get().reloadFromConfig(snapshot.templateWorldName());
            LongSet refreshedPinned = RunChunkSelectionManager.get().getPinnedChunkIndices(snapshot.templateWorldName());
            if (!refreshedPinned.isEmpty()) {
                PINNED_CHUNK_INDICES_BY_RUN_WORLD.put(worldId, refreshedPinned);
            }
        }
        LongSet pinned = PINNED_CHUNK_INDICES_BY_RUN_WORLD.get(worldId);
        if (pinned == null || pinned.isEmpty()) {
            return;
        }
        ChunkStore chunkStore = world.getChunkStore();
        for (long chunkIndex : pinned) {
            WorldChunk worldChunk = chunkStore.getChunkComponent(chunkIndex, WorldChunk.getComponentType());
            if (worldChunk == null) {
                chunkStore.getChunkReferenceAsync(chunkIndex).thenAcceptAsync(chunkRef -> {
                    if (!isPinnedKeepAliveActiveForWorld(worldId)) {
                        return;
                    }
                    LongSet activePinned = PINNED_CHUNK_INDICES_BY_RUN_WORLD.get(worldId);
                    if (activePinned == null || !activePinned.contains(chunkIndex)) {
                        return;
                    }
                    WorldChunk loadedChunk = chunkStore.getChunkComponent(chunkIndex, WorldChunk.getComponentType());
                    if (loadedChunk == null) {
                        return;
                    }
                    try {
                        loadedChunk.addKeepLoaded();
                        loadedChunk.resetKeepAlive();
                        loadedChunk.resetActiveTimer();
                        loadedChunk.setFlag(ChunkFlag.TICKING, true);
                    } catch (Throwable ignored) {
                    }
                }, world);
                continue;
            }
            try {
                worldChunk.addKeepLoaded();
                worldChunk.resetKeepAlive();
                worldChunk.resetActiveTimer();
                worldChunk.setFlag(ChunkFlag.TICKING, true);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void refreshActiveRunMap(@Nonnull World world) {
        if (!dynamicMapRefreshEnabled) {
            return;
        }
        ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        UUID worldId = world.getWorldConfig().getUuid();
        if (snapshot == null || snapshot.runWorldUuid() == null || !worldId.equals(snapshot.runWorldUuid())) {
            return;
        }
        LongSet chunksToRefresh = new LongOpenHashSet();
        RunChunkSelectionManager selectionManager = RunChunkSelectionManager.get();
        selectionManager.reloadFromConfig(snapshot.templateWorldName());
        Set<RunChunkSelectionManager.ChunkPosKey> selectedChunks = selectionManager.getSelectedChunks(snapshot.templateWorldName());
        for (RunChunkSelectionManager.ChunkPosKey selectedChunk : selectedChunks) {
            chunksToRefresh.add(ChunkUtil.indexChunk(selectedChunk.x(), selectedChunk.z()));
        }
        if (chunksToRefresh.isEmpty()) {
            return;
        }
        if (world.getWorldMapManager() != null) {
            world.getWorldMapManager().clearImagesInChunks(chunksToRefresh);
        }
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            Player player = world.getEntityStore().getStore().getComponent(playerRef.getReference(), Player.getComponentType());
            if (player != null && player.getWorldMapTracker() != null) {
                player.getWorldMapTracker().clearChunks(chunksToRefresh);
            }
        }
    }

    public static boolean isDynamicMapRefreshEnabled() {
        return dynamicMapRefreshEnabled;
    }

    public static void setDynamicMapRefreshEnabled(boolean enabled) {
        dynamicMapRefreshEnabled = enabled;
    }

    private static boolean isPinnedKeepAliveActiveForWorld(@Nonnull UUID worldId) {
        ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return false;
        }
        if (!worldId.equals(snapshot.runWorldUuid())) {
            return false;
        }
        return snapshot.phase() != RunPhase.IDLE && snapshot.phase() != RunPhase.ENDING;
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

        // FORCE SYNC: Ensure the chest items are pushed to ECS and synchronized with clients.
        Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> blockRef = chest.chunk().getBlockComponentEntity(pos.getX(), pos.getY(), pos.getZ());
        if (blockRef != null && blockRef.isValid()) {
            runWorld.getChunkStore().getStore().putComponent(blockRef, com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock.getComponentType(), chest.containerBlock());
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

    public enum PlayerRunCategory {
        NONE,
        ACTIVE,
        EXTRACTED,
        DEAD,
        SPECTATING,
        RETURNED_LOBBY,
        DISCONNECTED
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
        @Nonnull
        private final Map<UUID, Transform> runSpawnTransformByPlayer;
        @Nullable
        private UUID runWorldUuid;
        @Nonnull
        private final Set<UUID> expectedPlayerIds = new LinkedHashSet<>();
        @Nonnull
        private final Set<UUID> readyPlayerIds = new LinkedHashSet<>();
        @Nonnull
        private final Set<UUID> participantsEverJoined = new LinkedHashSet<>();
        @Nonnull
        private final Map<UUID, PlayerRunCategory> participantCategoryByPlayer = new LinkedHashMap<>();
        @Nonnull
        private RunPhase phase = RunPhase.IDLE;
        private long startedAtEpochMillis;
        private long runEndsAtEpochMillis;
        private long crimsonStartAtEpochMillis;
        private long emptyWorldSinceEpochMs;
        private int appliedRunHour = -1;

        private ActiveSession(
                @Nonnull String templateWorldName,
                @Nonnull String runWorldName,
                @Nonnull Path runWorldPath,
                @Nonnull UUID starterPlayerId,
                @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> crimsonProfiles,
                @Nullable Transform runSpawnTransform,
                @Nullable Transform returnSpawnTransform,
                @Nonnull List<UUID> expectedPlayerIds,
                @Nonnull Map<UUID, Transform> runSpawnTransformByPlayer
        ) {
            this.templateWorldName = templateWorldName;
            this.runWorldName = runWorldName;
            this.runWorldPath = runWorldPath;
            this.starterPlayerId = starterPlayerId;
            this.crimsonProfiles = copyProfiles(crimsonProfiles);
            this.runSpawnTransform = runSpawnTransform;
            this.returnSpawnTransform = returnSpawnTransform;
            this.expectedPlayerIds.addAll(expectedPlayerIds.isEmpty() ? List.of(starterPlayerId) : expectedPlayerIds);
            for (UUID playerId : this.expectedPlayerIds) {
                this.participantCategoryByPlayer.put(playerId, PlayerRunCategory.NONE);
                PLAYER_RUN_CATEGORY_CACHE.put(playerId, PlayerRunCategory.NONE);
            }
            this.runSpawnTransformByPlayer = new LinkedHashMap<>();
            for (Map.Entry<UUID, Transform> entry : runSpawnTransformByPlayer.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                this.runSpawnTransformByPlayer.put(entry.getKey(), copyTransformOrNull(entry.getValue()));
            }
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