package dev.hytalemodding.game;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class GameSessionManager {
    private static final String RUN_WORLD_PREFIX = "run-session-";
    private static final long RUN_DURATION_MS = 5L * 60L * 1000L;
    private static final long DEFAULT_CRIMSON_DELAY_MS = 0L;
    private static final float DEFAULT_CRIMSON_SPREAD_SECONDS = RUN_DURATION_MS / 1000.0f;
    private static final GameSessionManager INSTANCE = new GameSessionManager();

    @Nullable
    private ActiveSession activeSession;

    private GameSessionManager() {
    }

    @Nonnull
    public static GameSessionManager get() {
        return INSTANCE;
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

            RedWaveManager.Selection selection = RedWaveManager.getSelection(starter.getUuid());
            boolean crimsonEnabled = selection != null
                    && selection.isComplete()
                    && templateWorld.getWorldConfig().getUuid().equals(selection.worldId());
            Vector3i pos1;
            Vector3i pos2;
            if (crimsonEnabled) {
                pos1 = selection.pos1();
                pos2 = selection.pos2();
                if (pos1 == null || pos2 == null) {
                    crimsonEnabled = false;
                    pos1 = new Vector3i(0, 0, 0);
                    pos2 = new Vector3i(0, 0, 0);
                }
            } else {
                pos1 = new Vector3i(0, 0, 0);
                pos2 = new Vector3i(0, 0, 0);
            }

            this.activeSession = new ActiveSession(
                    templateWorldName,
                    runWorldName,
                    runWorldPath,
                    starter.getUuid(),
                    new Vector3i(pos1),
                    new Vector3i(pos2),
                    DEFAULT_CRIMSON_SPREAD_SECONDS,
                    crimsonEnabled,
                    copyTransformOrNull(runSpawnTransform),
                    copyTransformOrNull(returnSpawnTransform)
            );
            this.activeSession.phase = RunPhase.PREPARING;
            session = this.activeSession;
        }

        Universe universe = Universe.get();
        Path templatePath = universe.getPath().resolve("worlds").resolve(session.templateWorldName);

        return CompletableFuture.runAsync(() -> copyDirectory(templatePath, session.runWorldPath))
                .thenCompose(ignored -> loadAndInstantiateRunWorld(universe, session))
                .thenCompose(runWorld -> {
                    World sourceWorld = resolvePlayerWorld(starter, universe, templateWorld);
                    return movePlayerToWorld(starter, sourceWorld, runWorld, session.runSpawnTransform, false).thenApply(x -> runWorld);
                })
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
                .exceptionallyCompose(throwable -> cleanupFailedStart(session, throwable));
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession() {
        return endSession(null, null, false);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession(@Nullable Transform returnSpawnOverride) {
        return endSession(returnSpawnOverride, null, false);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSession(
            @Nullable Transform returnSpawnOverride,
            @Nullable World returnWorldOverride
    ) {
        return endSession(returnSpawnOverride, returnWorldOverride, false);
    }

    @Nonnull
    public CompletableFuture<EndSessionResult> endSessionAndWipeInventory(
            @Nullable Transform returnSpawnOverride,
            @Nullable World returnWorldOverride
    ) {
        return endSession(returnSpawnOverride, returnWorldOverride, true);
    }

    @Nonnull
    private CompletableFuture<EndSessionResult> endSession(
            @Nullable Transform returnSpawnOverride,
            @Nullable World returnWorldOverride,
            boolean wipeInventoryOnReturn
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
        World fallbackWorld = returnWorldOverride != null ? returnWorldOverride : universe.getWorld(session.templateWorldName);
        if (fallbackWorld == null) {
            fallbackWorld = universe.getDefaultWorld();
        }

        CompletableFuture<Void> transferFuture;
        if (runWorld != null && fallbackWorld != null) {
            Transform returnSpawn = returnSpawnOverride != null
                    ? copyTransformOrNull(returnSpawnOverride)
                    : copyTransformOrNull(session.returnSpawnTransform);
            transferFuture = movePlayersFromWorld(runWorld, fallbackWorld, returnSpawn, wipeInventoryOnReturn);
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
        if (!this.activeSession.crimsonEnabled) {
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

        Path finalConfigPath = configPath;
        return WorldConfig.load(finalConfigPath)
                .thenCompose(config -> {
                    config.setUuid(UUID.randomUUID());
                    config.setDisplayName("Run " + session.runWorldName);
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

        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        return CompletableFuture.failedFuture(cause);
    }

    @Nonnull
    private static CompletableFuture<Void> movePlayersFromWorld(
            @Nonnull World fromWorld,
            @Nonnull World toWorld,
            @Nullable Transform targetSpawn,
            boolean wipeInventoryAfterMove
    ) {
        UUID fromWorldId = fromWorld.getWorldConfig().getUuid();
        List<CompletableFuture<Void>> transfers = new ArrayList<>();

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorld = playerRef.getWorldUuid();
            if (playerWorld != null && playerWorld.equals(fromWorldId)) {
                transfers.add(movePlayerToWorld(playerRef, fromWorld, toWorld, targetSpawn, wipeInventoryAfterMove));
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
            @Nullable Transform targetSpawn,
            boolean wipeInventoryAfterMove
    ) {
        return CompletableFuture.runAsync(playerRef::removeFromStore, fromWorld)
                .thenCompose(ignored -> {
                    CompletableFuture<PlayerRef> addFuture = toWorld.addPlayer(
                            playerRef,
                            targetSpawn != null ? copyTransformOrNull(targetSpawn) : null,
                            Boolean.TRUE,
                            Boolean.FALSE
                    );
                    if (addFuture == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Player add returned null (inactive connection?)."));
                    }
                    return addFuture.thenApply(added -> {
                        restoreHealth(added);
                        clearEffects(added);
                        if (wipeInventoryAfterMove) {
                            clearInventory(added);
                        }
                        return null;
                    });
                });
    }

    private static void clearEffects(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effects == null) {
            return;
        }
        effects.clearEffects(ref, store);
        store.putComponent(ref, EffectControllerComponent.getComponentType(), effects);
    }

    private static void clearInventory(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getInventory() == null) {
            return;
        }
        player.getInventory().clear();
        player.sendInventory();
    }

    private static void restoreHealth(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
        store.putComponent(ref, EntityStatMap.getComponentType(), stats);
    }

    @Nonnull
    private static World resolvePlayerWorld(@Nonnull PlayerRef playerRef, @Nonnull Universe universe, @Nonnull World fallback) {
        UUID playerWorldUuid = playerRef.getWorldUuid();
        if (playerWorldUuid != null) {
            World actual = universe.getWorld(playerWorldUuid);
            if (actual != null) {
                return actual;
            }
        }
        return fallback;
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

        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    @Nonnull
    private static String buildRunWorldName(@Nonnull String templateWorldName) {
        String safeTemplate = templateWorldName.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return RUN_WORLD_PREFIX + safeTemplate + "-" + System.currentTimeMillis();
    }

    @Nullable
    private static Transform copyTransformOrNull(@Nullable Transform transform) {
        if (transform == null) {
            return null;
        }
        return new Transform(transform.getPosition().clone(), transform.getRotation().clone());
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
        private final Vector3i crimsonPos1;
        @Nonnull
        private final Vector3i crimsonPos2;
        private final float crimsonSpreadSeconds;
        private final boolean crimsonEnabled;
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
                @Nonnull Vector3i crimsonPos1,
                @Nonnull Vector3i crimsonPos2,
                float crimsonSpreadSeconds,
                boolean crimsonEnabled,
                @Nullable Transform runSpawnTransform,
                @Nullable Transform returnSpawnTransform
        ) {
            this.templateWorldName = templateWorldName;
            this.runWorldName = runWorldName;
            this.runWorldPath = runWorldPath;
            this.starterPlayerId = starterPlayerId;
            this.crimsonPos1 = crimsonPos1;
            this.crimsonPos2 = crimsonPos2;
            this.crimsonSpreadSeconds = crimsonSpreadSeconds;
            this.crimsonEnabled = crimsonEnabled;
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
                    new Vector3i(this.crimsonPos1),
                    new Vector3i(this.crimsonPos2),
                    this.crimsonSpreadSeconds,
                    this.crimsonEnabled
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
            @Nonnull Vector3i crimsonPos1,
            @Nonnull Vector3i crimsonPos2,
            float crimsonSpreadSeconds,
            boolean crimsonEnabled
    ) {
    }
}
