package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class GameSessionManager {
    private static final String RUN_WORLD_PREFIX = "run-session-";
    private static final long RUN_DURATION_MS = 5L * 60L * 1000L;
    private static final long DEFAULT_CRIMSON_DELAY_MS = 0L;
    private static final float DEFAULT_CRIMSON_SPREAD_SECONDS = RUN_DURATION_MS / 1000.0f;
    private static final long PLAYER_TRANSFER_TIMEOUT_SECONDS = 15L;
    private static final long WORLD_COPY_TIMEOUT_SECONDS = 90L;
    private static final long WORLD_LOAD_TIMEOUT_SECONDS = 30L;
    private static final int WORLD_DELETE_MAX_ATTEMPTS = 20;
    private static final long WORLD_DELETE_RETRY_DELAY_MS = 100L;
    private static final GameSessionManager INSTANCE = new GameSessionManager();

    @Nullable
    private ActiveSession activeSession;

    private GameSessionManager() {
    }

    @Nonnull
    public static GameSessionManager get() {
        return INSTANCE;
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
                System.out.println("[GameDoorDebug] startup cleanup removing loaded orphan world: " + worldName);
                universe.removeWorld(worldName);
            } catch (Exception e) {
                System.out.println("[GameDoorDebug] startup cleanup failed removing loaded world " + worldName + ": " + e);
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
                            System.out.println("[GameDoorDebug] startup cleanup deleting orphan world directory: " + path);
                            deleteDirectoryIfPresent(path);
                        } catch (Exception e) {
                            System.out.println("[GameDoorDebug] startup cleanup failed deleting directory " + path + ": " + e);
                        }
                    });
        } catch (IOException e) {
            System.out.println("[GameDoorDebug] startup cleanup failed listing world directories: " + e.getMessage());
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
        System.out.println("[GameDoorDebug] startSession enter: player=" + starter.getUuid()
                + " template=" + templateWorld.getName()
                + " thread=" + Thread.currentThread().getName());

        final ActiveSession session;
        synchronized (this) {
            if (this.activeSession != null && this.activeSession.phase != RunPhase.IDLE) {
                System.out.println("[GameDoorDebug] startSession rejected: active session phase=" + this.activeSession.phase);
                return CompletableFuture.failedFuture(new IllegalStateException("A run is already active."));
            }

            String templateWorldName = templateWorld.getName();
            String runWorldName = buildRunWorldName(templateWorldName);
            Path runWorldPath = Universe.get().getPath().resolve("worlds").resolve(runWorldName);

            UUID templateWorldId = templateWorld.getWorldConfig().getUuid();
            List<RedCoreProfileRegistry.RedCoreProfile> configuredProfiles = RedCoreProfileRegistry.snapshot(templateWorldId);
            ArrayList<RedCoreProfileRegistry.RedCoreProfile> validProfiles = new ArrayList<>();
            for (RedCoreProfileRegistry.RedCoreProfile profile : configuredProfiles) {
                Vector3i corePos = profile.corePos();
                if (corePos == null) {
                    continue;
                }
                int effectiveRadius = normalizeRadius(profile.radiusBlocks());
                float effectiveStartSeconds = normalizeStartSeconds(profile.startSeconds());

                System.out.println("[GameDoorDebug] crimson core read start: world=" + templateWorld.getName() + " pos=" + corePos);
                String coreBlockId;
                try {
                    coreBlockId = resolveCoreBlockId(templateWorld, corePos);
                } catch (Exception e) {
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return CompletableFuture.failedFuture(new IllegalStateException("Unable to validate crimson core: " + reason, e));
                }
                System.out.println("[GameDoorDebug] crimson core read done: blockId=" + coreBlockId);
                if (!RedWaveConfig.CORE_BLOCK_ID.equals(coreBlockId)) {
                    continue;
                }
                validProfiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(corePos), effectiveRadius, effectiveStartSeconds));
            }
            System.out.println("[GameDoorDebug] crimson profile check: templateWorldId=" + templateWorldId
                    + " configured=" + configuredProfiles.size()
                    + " valid=" + validProfiles.size());
            if (validProfiles.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Configure crimson cores/radius/time in template world using /redui before /gamestart.")
                );
            }

            this.activeSession = new ActiveSession(
                    templateWorldName,
                    runWorldName,
                    runWorldPath,
                    starter.getUuid(),
                    validProfiles,
                    copyTransformOrNull(runSpawnTransform),
                    copyTransformOrNull(returnSpawnTransform)
            );
            this.activeSession.phase = RunPhase.PREPARING;
            session = this.activeSession;
        }

        Universe universe = Universe.get();
        Path templatePath = universe.getPath().resolve("worlds").resolve(session.templateWorldName);
        long startMs = System.currentTimeMillis();
        System.out.println("[GameDoorDebug] session preparing: template=" + session.templateWorldName
                + " runWorld=" + session.runWorldName
                + " templatePath=" + templatePath
                + " runPath=" + session.runWorldPath);

        CompletableFuture<Void> copyFuture = CompletableFuture.runAsync(() -> {
                    long copyStart = System.currentTimeMillis();
                    System.out.println("[GameDoorDebug] copy start: " + templatePath + " -> " + session.runWorldPath);
                    copyDirectory(templatePath, session.runWorldPath);
                    long copyMs = System.currentTimeMillis() - copyStart;
                    System.out.println("[GameDoorDebug] copy success in " + copyMs + "ms");
                })
                .orTimeout(WORLD_COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<World> loadFuture = copyFuture.thenCompose(ignored -> {
                    long loadStart = System.currentTimeMillis();
                    System.out.println("[GameDoorDebug] world load start: runWorld=" + session.runWorldName);
                    return loadAndInstantiateRunWorld(universe, session).whenComplete((world, err) -> {
                        long loadMs = System.currentTimeMillis() - loadStart;
                        if (err != null) {
                            System.out.println("[GameDoorDebug] world load failed after " + loadMs + "ms: " + err);
                            return;
                        }
                        System.out.println("[GameDoorDebug] world load success in " + loadMs + "ms: runWorld=" + world.getName());
                    });
                })
                .orTimeout(WORLD_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<World> transferFuture = loadFuture.thenCompose(runWorld -> {
            UUID playerWorldUuid = starter.getWorldUuid();
            World playerWorld = playerWorldUuid == null ? null : universe.getWorld(playerWorldUuid);
            if (playerWorld == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Starter player is not currently in a loaded world.")
                );
            }
            long transferStart = System.currentTimeMillis();
            System.out.println("[GameDoorDebug] transfer start: player=" + starter.getUuid()
                    + " from=" + playerWorld.getName() + " to=" + runWorld.getName());
            return movePlayerToWorld(starter, playerWorld, runWorld, session.runSpawnTransform)
                    .whenComplete((ignored, err) -> {
                        long transferMs = System.currentTimeMillis() - transferStart;
                        if (err != null) {
                            System.out.println("[GameDoorDebug] transfer failed after " + transferMs + "ms: " + err);
                            return;
                        }
                        System.out.println("[GameDoorDebug] transfer success in " + transferMs + "ms");
                    })
                    .thenApply(x -> runWorld);
        });

        return transferFuture
                .thenApply(runWorld -> {
                    synchronized (this) {
                        if (this.activeSession == session) {
                            session.phase = RunPhase.EXPLORATION;
                            session.startedAtEpochMillis = System.currentTimeMillis();
                            session.runEndsAtEpochMillis = session.startedAtEpochMillis + RUN_DURATION_MS;
                            session.crimsonStartAtEpochMillis = session.startedAtEpochMillis + DEFAULT_CRIMSON_DELAY_MS;
                            session.runWorldUuid = runWorld.getWorldConfig().getUuid();
                        }
                    }
                    GameSessionManager.ActiveSessionSnapshot snapshot = this.getActiveSession();
                    if (snapshot != null) {
                        RescueObjectiveManager.get().spawnRescueOnRunStart(runWorld, snapshot);
                    }
                    return new StartSessionResult(
                            session.templateWorldName,
                            session.runWorldName,
                            runWorld.getWorldConfig().getUuid(),
                            session.crimsonStartAtEpochMillis,
                            session.runEndsAtEpochMillis
                    );
                })
                .whenComplete((result, err) -> {
                    long totalMs = System.currentTimeMillis() - startMs;
                    if (err != null) {
                        System.out.println("[GameDoorDebug] session start failed after " + totalMs + "ms: " + err);
                        return;
                    }
                    System.out.println("[GameDoorDebug] session start success in " + totalMs + "ms: runWorld=" + result.runWorldName());
                })
                .exceptionallyCompose(throwable -> cleanupFailedStart(session, throwable));
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession() {
        return endSession(null);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession(@Nullable Transform returnSpawnOverride, @Nullable World ignoredFallbackWorld) {
        return endSessionInternal(returnSpawnOverride, ignoredFallbackWorld);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSessionAndWipeInventory(@Nullable Transform returnSpawnOverride, @Nullable World ignoredFallbackWorld) {
        return endSessionInternal(returnSpawnOverride, ignoredFallbackWorld);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession(@Nullable Transform returnSpawnOverride) {
        return endSessionInternal(returnSpawnOverride, null);
    }

    @Nonnull
    private CompletableFuture<EndSessionResult> endSessionInternal(
            @Nullable Transform returnSpawnOverride,
            @Nullable World fallbackWorldOverride
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

        CompletableFuture<Void> transferFuture;
        if (runWorld != null && fallbackWorld != null) {
            Transform returnSpawn = returnSpawnOverride != null
                    ? copyTransformOrNull(returnSpawnOverride)
                    : copyTransformOrNull(session.returnSpawnTransform);
            transferFuture = movePlayersFromWorld(runWorld, fallbackWorld, returnSpawn);
        } else {
            transferFuture = CompletableFuture.completedFuture(null);
        }

        return transferFuture
                .thenRun(() -> {
                    if (universe.getWorld(session.runWorldName) != null) {
                        universe.removeWorld(session.runWorldName);
                    }
                    deleteDirectoryIfPresent(session.runWorldPath);
                })
                .handle((ignored, throwable) -> {
                    synchronized (this) {
                        if (this.activeSession == session) {
                            this.activeSession = null;
                        }
                    }

                    if (throwable != null) {
                        String error = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                        return new EndSessionResult(false, session.runWorldName, session.templateWorldName, error);
                    }
                    return new EndSessionResult(true, session.runWorldName, session.templateWorldName, "Run ended and cleaned up.");
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
        if (this.activeSession == null || this.activeSession.phase == RunPhase.IDLE) {
            return 0L;
        }
        return Math.max(0L, this.activeSession.runEndsAtEpochMillis - System.currentTimeMillis());
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
        System.out.println("[GameDoorDebug] world config path: " + configPath + " exists=" + Files.exists(configPath));

        Path finalConfigPath = configPath;
        return WorldConfig.load(finalConfigPath)
                .thenCompose(config -> {
                    System.out.println("[GameDoorDebug] world config loaded: " + finalConfigPath);
                    config.setUuid(UUID.randomUUID());
                    config.setDisplayName("Run " + session.runWorldName);
                    config.setDeleteOnRemove(true);
                    config.markChanged();
                    System.out.println("[GameDoorDebug] makeWorld start: " + session.runWorldName);
                    return universe.makeWorld(session.runWorldName, session.runWorldPath, config);
                })
                .whenComplete((world, err) -> {
                    if (err != null) {
                        System.out.println("[GameDoorDebug] makeWorld failed: " + err);
                        return;
                    }
                    System.out.println("[GameDoorDebug] makeWorld success: " + world.getName());
                });
    }

    @Nonnull
    private CompletableFuture<StartSessionResult> cleanupFailedStart(@Nonnull ActiveSession session, @Nonnull Throwable throwable) {
        Universe universe = Universe.get();
        try {
            System.out.println("[GameDoorDebug] cleanup failed start: runWorld=" + session.runWorldName + " path=" + session.runWorldPath);
            if (universe.getWorld(session.runWorldName) != null) {
                universe.removeWorld(session.runWorldName);
            }
            deleteDirectoryIfPresent(session.runWorldPath);
        } catch (Exception ignored) {
            System.out.println("[GameDoorDebug] cleanup encountered exception: " + ignored);
        }

        synchronized (this) {
            if (this.activeSession == session) {
                this.activeSession = null;
            }
        }

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
        Transform destination = targetSpawn != null ? copyTransformOrNull(targetSpawn) : null;

        return CompletableFuture.runAsync(() -> {
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
                            : Teleport.createForPlayer(toWorld, playerRef.getTransform());
                    teleport.setOnComplete(teleportCompleted);
                    store.addComponent(playerRefHandle, Teleport.getComponentType(), teleport);
                }, fromWorld)
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

    @Nullable
    private static String resolveCoreBlockId(@Nonnull World templateWorld, @Nonnull Vector3i corePos) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        var blockType = templateWorld.getBlockType(corePos.x, corePos.y, corePos.z);
                        return blockType == null ? null : blockType.getId();
                    }, templateWorld)
                    .orTimeout(5L, TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(
                    "Timed out or failed reading core block in world '" + templateWorld.getName() + "' at " + corePos + ": "
                            + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()),
                    cause
            );
        }
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

    private static int normalizeRadius(int configuredRadius) {
        if (configuredRadius < RedWaveConfig.MIN_RADIUS_BLOCKS || configuredRadius > RedWaveConfig.MAX_RADIUS_BLOCKS) {
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

    public enum RunPhase {
        IDLE,
        PREPARING,
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
        private RunPhase phase = RunPhase.IDLE;
        private long startedAtEpochMillis;
        private long runEndsAtEpochMillis;
        private long crimsonStartAtEpochMillis;

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

