package dev.hytalemodding.blob;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class OrangeBlobBlockRuntime {
    private static final int ROCK_MOVE_DISTANCE_REDUCTION = 3;
    private static final ConcurrentHashMap<UUID, List<Session>> SESSIONS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, UUID>> ACTIVE_POSITIONS_BY_WORLD = new ConcurrentHashMap<>();

    private OrangeBlobBlockRuntime() {
    }

    static Session createSession(
            @Nonnull UUID worldId,
            @Nonnull UUID activatingPlayerId,
            @Nonnull List<ClusterBlock> sourceBlocks,
            @Nonnull List<ClusterBlock> sourceDetachedRocks,
            @Nonnull ClusterBlock sourceCenterBlock,
            @Nonnull ClusterBlock sourceRuneBlock,
            long now,
            int moveDistance,
            long moveDurationMs,
            @Nonnull OrangeBlobExtractionConfigManager.ExtractionConfigState config,
            @Nonnull List<SupportBlock> supportBlocks,
            double extractionRadiusBlocks,
            double extractionMinHeightOffset,
            double extractionMaxHeightOffset,
            double enemySpawnMinRadiusBlocks,
            double enemySpawnMaxRadiusBlocks,
            double enemySpawnMinHeightOffset,
            double enemySpawnMaxHeightOffset,
            long extractionWindowDurationMs,
            long moverTailDelayMs
    ) {
        long downEndAt = now + moveDurationMs;
        long holdEndAt = downEndAt + config.defenseDurationMs();
        long upEndAt = holdEndAt + moveDurationMs;

        ArrayList<ClusterBlock> loweredBlocks = new ArrayList<>(sourceBlocks.size());
        for (ClusterBlock block : sourceBlocks) {
            loweredBlocks.add(new ClusterBlock(
                    block.x(),
                    block.y() - moveDistance,
                    block.z(),
                    toActiveBlobBlockId(block.blockId()),
                    block.rotation()
            ));
        }

        int detachedRockMoveDistance = Math.max(0, moveDistance - ROCK_MOVE_DISTANCE_REDUCTION);
        ArrayList<ClusterBlock> loweredDetachedRocks = new ArrayList<>(sourceDetachedRocks.size());
        for (ClusterBlock block : sourceDetachedRocks) {
            loweredDetachedRocks.add(new ClusterBlock(
                    block.x(),
                    block.y() - detachedRockMoveDistance,
                    block.z(),
                    toActiveBlobBlockId(block.blockId()),
                    block.rotation()
            ));
        }

        ClusterBlock loweredCenterBlock = new ClusterBlock(
                sourceCenterBlock.x(),
                sourceCenterBlock.y() - moveDistance,
                sourceCenterBlock.z(),
                config.loweredIslandBlockId(),
                sourceCenterBlock.rotation()
        );

        ClusterBlock loweredRuneBlock = new ClusterBlock(
                sourceRuneBlock.x(),
                sourceRuneBlock.y() - moveDistance,
                sourceRuneBlock.z(),
                config.activeRuneBlockId(),
                sourceRuneBlock.rotation()
        );

        return new Session(
                UUID.randomUUID(),
                worldId,
                activatingPlayerId,
                sourceBlocks,
                loweredBlocks,
                sourceDetachedRocks,
                loweredDetachedRocks,
                sourceCenterBlock,
                loweredCenterBlock,
                sourceRuneBlock,
                loweredRuneBlock,
                sourceRuneBlock.rotation(),
                now,
                downEndAt,
                holdEndAt,
                upEndAt,
                config,
                supportBlocks,
                extractionRadiusBlocks,
                extractionMinHeightOffset,
                extractionMaxHeightOffset,
                enemySpawnMinRadiusBlocks,
                enemySpawnMaxRadiusBlocks,
                enemySpawnMinHeightOffset,
                enemySpawnMaxHeightOffset,
                extractionWindowDurationMs,
                Math.max(0L, moverTailDelayMs)
        );
    }

    static void addSession(@Nonnull Session session) {
        SESSIONS_BY_WORLD.computeIfAbsent(session.worldId(), ignored -> new ArrayList<>()).add(session);
    }

    @Nonnull
    private static String toActiveBlobBlockId(@Nonnull String sourceBlockId) {
        if (OrangeBlobBlockManager.BLOCK_ID.equals(sourceBlockId)) {
            return OrangeBlobBlockManager.ACTIVE_BLOCK_ID;
        }
        if (OrangeBlobBlockManager.CORNER_BLOCK_ID.equals(sourceBlockId)) {
            return OrangeBlobBlockManager.ACTIVE_CORNER_BLOCK_ID;
        }
        if (OrangeBlobBlockManager.ROCKS_BLOCK_ID.equals(sourceBlockId)) {
            return OrangeBlobBlockManager.ACTIVE_ROCKS_BLOCK_ID;
        }
        return sourceBlockId;
    }

    static void markClusterActive(@Nonnull Session session) {
        ConcurrentHashMap<String, UUID> active = ACTIVE_POSITIONS_BY_WORLD.computeIfAbsent(session.worldId(), ignored -> new ConcurrentHashMap<>());
        for (ClusterBlock block : session.sourceBlocks()) {
            active.put(OrangeBlobBlockManager.posKey(block.x(), block.y(), block.z()), session.id());
        }
        active.put(OrangeBlobBlockManager.posKey(session.sourceRuneBlock().x(), session.sourceRuneBlock().y(), session.sourceRuneBlock().z()), session.id());
        for (ClusterBlock block : session.loweredBlocks()) {
            active.put(OrangeBlobBlockManager.posKey(block.x(), block.y(), block.z()), session.id());
        }
        for (ClusterBlock block : session.sourceDetachedRocks()) {
            active.put(OrangeBlobBlockManager.posKey(block.x(), block.y(), block.z()), session.id());
        }
        for (ClusterBlock block : session.loweredDetachedRocks()) {
            active.put(OrangeBlobBlockManager.posKey(block.x(), block.y(), block.z()), session.id());
        }
        active.put(OrangeBlobBlockManager.posKey(session.loweredRuneBlock().x(), session.loweredRuneBlock().y(), session.loweredRuneBlock().z()), session.id());
        for (SupportBlock supportBlock : session.supportBlocks()) {
            active.put(OrangeBlobBlockManager.posKey(supportBlock.x(), supportBlock.y(), supportBlock.z()), session.id());
        }
    }

    static void clearClusterActive(@Nonnull Session session) {
        ConcurrentHashMap<String, UUID> active = ACTIVE_POSITIONS_BY_WORLD.get(session.worldId());
        if (active == null) {
            return;
        }
        active.entrySet().removeIf(entry -> session.id().equals(entry.getValue()));
        if (active.isEmpty()) {
            ACTIVE_POSITIONS_BY_WORLD.remove(session.worldId());
        }
    }

    static boolean isPositionActive(@Nonnull UUID worldId, @Nonnull String positionKey) {
        ConcurrentHashMap<String, UUID> active = ACTIVE_POSITIONS_BY_WORLD.get(worldId);
        return active != null && active.containsKey(positionKey);
    }

    @Nonnull
    static List<Session> getSessions(@Nonnull UUID worldId) {
        List<Session> sessions = SESSIONS_BY_WORLD.get(worldId);
        return sessions == null ? List.of() : sessions;
    }

    static void removeCompleted(@Nonnull UUID worldId) {
        List<Session> sessions = SESSIONS_BY_WORLD.get(worldId);
        if (sessions == null) {
            return;
        }
        Iterator<Session> iterator = sessions.iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (!session.complete()) {
                continue;
            }
            clearClusterActive(session);
            iterator.remove();
        }
        if (sessions.isEmpty()) {
            SESSIONS_BY_WORLD.remove(worldId);
        }
    }

    static void clearWorld(@Nonnull UUID worldId) {
        SESSIONS_BY_WORLD.remove(worldId);
        ACTIVE_POSITIONS_BY_WORLD.remove(worldId);
    }

    @Nullable
    static Session findSessionByRunePosition(@Nonnull UUID worldId, @Nonnull Vector3i position) {
        List<Session> sessions = SESSIONS_BY_WORLD.get(worldId);
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        for (Session session : sessions) {
            if (matchesRunePosition(session.sourceRuneBlock(), position) || matchesRunePosition(session.loweredRuneBlock(), position)) {
                return session;
            }
        }
        return null;
    }

    @Nullable
    static Session findSessionByRuneProxyRef(@Nonnull UUID worldId, @Nonnull Ref<EntityStore> targetRef) {
        List<Session> sessions = SESSIONS_BY_WORLD.get(worldId);
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        for (Session session : sessions) {
            Ref<EntityStore> runeProxyRef = session.runeAggroProxyRef();
            Ref<EntityStore> runeBodyRef = session.runeBodyRef();
            if (runeProxyRef != null && runeProxyRef.isValid() && runeProxyRef.equals(targetRef)) {
                return session;
            }
            if (runeBodyRef != null && runeBodyRef.isValid() && runeBodyRef.equals(targetRef)) {
                return session;
            }
        }
        return null;
    }

    private static boolean matchesRunePosition(@Nonnull ClusterBlock runeBlock, @Nonnull Vector3i position) {
        return runeBlock.x() == position.x && runeBlock.y() == position.y && runeBlock.z() == position.z;
    }

    enum Phase {
        MOVING_DOWN,
        HOLDING_DOWN,
        RETURNING_FOR_EXTRACTION,
        MOVING_UP,
        COMPLETE
    }

    enum RockPhase {
        IDLE_UP,
        MOVING_DOWN,
        IDLE_DOWN,
        MOVING_UP
    }

    record ClusterBlock(
            int x,
            int y,
            int z,
            @Nonnull String blockId,
            int rotation
    ) {
    }

    record SupportBlock(
            int x,
            int y,
            int z,
            @Nonnull String placedBlockId,
            int placedRotation,
            @Nonnull String originalBlockId,
            int originalRotation
    ) {
    }

    static final class Session {
        @Nonnull
        private final UUID id;
        @Nonnull
        private final UUID worldId;
        @Nonnull
        private final UUID activatingPlayerId;
        @Nonnull
        private final List<ClusterBlock> sourceBlocks;
        @Nonnull
        private final List<ClusterBlock> loweredBlocks;
        @Nonnull
        private final List<ClusterBlock> sourceDetachedRocks;
        @Nonnull
        private final List<ClusterBlock> loweredDetachedRocks;
        @Nonnull
        private final ClusterBlock sourceCenterBlock;
        @Nonnull
        private final ClusterBlock loweredCenterBlock;
        @Nonnull
        private final ClusterBlock sourceRuneBlock;
        @Nonnull
        private final ClusterBlock loweredRuneBlock;
        private final int sourceRuneInitialRotation;
        private final long downStartAt;
        private final long downEndAt;
        private long holdEndAt;
        private final long moveDurationMs;
        private long upStartAt;
        private long upEndAt;
        @Nonnull
        private final OrangeBlobExtractionConfigManager.ExtractionConfigState config;
        @Nonnull
        private final List<SupportBlock> supportBlocks;
        private final double extractionRadiusBlocks;
        private final double extractionMinHeightOffset;
        private final double extractionMaxHeightOffset;
        private final double enemySpawnMinRadiusBlocks;
        private final double enemySpawnMaxRadiusBlocks;
        private final double enemySpawnMinHeightOffset;
        private final double enemySpawnMaxHeightOffset;
        private final long extractionWindowDurationMs;
        private final long rockTailDelayMs;
        @Nonnull
        private Phase phase = Phase.MOVING_DOWN;
        @Nonnull
        private RockPhase rockPhase = RockPhase.IDLE_UP;
        @Nonnull
        private final List<Mover> movers = new ArrayList<>();
        @Nonnull
        private final List<Mover> rockMovers = new ArrayList<>();
        @Nonnull
        private final List<Ref<EntityStore>> spawnedMobRefs = new ArrayList<>();
        @Nonnull
        private final List<com.hypixel.hytale.math.vector.Vector3d> recentMobSpawnPositions = new ArrayList<>();
        private Ref<EntityStore> runeBodyRef;
        private Ref<EntityStore> runeAggroProxyRef;
        private long nextMobSpawnAt;
        private long nextDebugAt;
        private long nextRuneDamageAt;
        private long lastNearbyPlayerAt;
        private long nextStandReminderAt;
        private boolean loweredPlaced;
        private boolean sourcePlaced;
        private boolean loweredRocksPlaced;
        private boolean sourceRocksPlaced;
        private boolean supportBlocksPlaced;
        private float runeHealth;
        private int wavesSpawned;
        private boolean extractionDispatchStarted;
        private boolean extractionDispatchSucceeded;
        private String extractionDispatchFailure;
        private boolean extractionReady;
        private long extractionAutoLaunchAt;
        private boolean launchRequested;
        private int lastAnnouncedHealthTier = 4;
        private boolean extractionWindowActive;
        private long extractionWindowEndsAt;
        @Nonnull
        private final Set<UUID> extractionWindowPlayerIds = new LinkedHashSet<>();
        private boolean rockDownSignalPending;
        private long rockDownSignalAt;
        private boolean rockUpSignalPending;
        private long rockUpSignalAt;
        private long rockDownEndAt;
        private long rockUpEndAt;

        private Session(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                @Nonnull UUID activatingPlayerId,
                @Nonnull List<ClusterBlock> sourceBlocks,
                @Nonnull List<ClusterBlock> loweredBlocks,
                @Nonnull List<ClusterBlock> sourceDetachedRocks,
                @Nonnull List<ClusterBlock> loweredDetachedRocks,
                @Nonnull ClusterBlock sourceCenterBlock,
                @Nonnull ClusterBlock loweredCenterBlock,
                @Nonnull ClusterBlock sourceRuneBlock,
                @Nonnull ClusterBlock loweredRuneBlock,
                int sourceRuneInitialRotation,
                long downStartAt,
                long downEndAt,
                long holdEndAt,
                long upEndAt,
                @Nonnull OrangeBlobExtractionConfigManager.ExtractionConfigState config,
                @Nonnull List<SupportBlock> supportBlocks,
                double extractionRadiusBlocks,
                double extractionMinHeightOffset,
                double extractionMaxHeightOffset,
                double enemySpawnMinRadiusBlocks,
                double enemySpawnMaxRadiusBlocks,
                double enemySpawnMinHeightOffset,
                double enemySpawnMaxHeightOffset,
                long extractionWindowDurationMs,
                long rockTailDelayMs
        ) {
            this.id = id;
            this.worldId = worldId;
            this.activatingPlayerId = activatingPlayerId;
            this.sourceBlocks = List.copyOf(sourceBlocks);
            this.loweredBlocks = List.copyOf(loweredBlocks);
            this.sourceDetachedRocks = List.copyOf(sourceDetachedRocks);
            this.loweredDetachedRocks = List.copyOf(loweredDetachedRocks);
            this.sourceCenterBlock = sourceCenterBlock;
            this.loweredCenterBlock = loweredCenterBlock;
            this.sourceRuneBlock = sourceRuneBlock;
            this.loweredRuneBlock = loweredRuneBlock;
            this.sourceRuneInitialRotation = sourceRuneInitialRotation;
            this.downStartAt = downStartAt;
            this.downEndAt = downEndAt;
            this.holdEndAt = holdEndAt;
            this.moveDurationMs = Math.max(1L, downEndAt - downStartAt);
            this.upStartAt = holdEndAt;
            this.upEndAt = upEndAt;
            this.config = config;
            this.supportBlocks = List.copyOf(supportBlocks);
            this.extractionRadiusBlocks = extractionRadiusBlocks;
            this.extractionMinHeightOffset = Math.min(extractionMinHeightOffset, extractionMaxHeightOffset);
            this.extractionMaxHeightOffset = Math.max(extractionMinHeightOffset, extractionMaxHeightOffset);
            this.enemySpawnMinRadiusBlocks = Math.min(enemySpawnMinRadiusBlocks, enemySpawnMaxRadiusBlocks);
            this.enemySpawnMaxRadiusBlocks = Math.max(enemySpawnMinRadiusBlocks, enemySpawnMaxRadiusBlocks);
            this.enemySpawnMinHeightOffset = Math.min(enemySpawnMinHeightOffset, enemySpawnMaxHeightOffset);
            this.enemySpawnMaxHeightOffset = Math.max(enemySpawnMinHeightOffset, enemySpawnMaxHeightOffset);
            this.extractionWindowDurationMs = Math.max(100L, extractionWindowDurationMs);
            this.rockTailDelayMs = Math.max(0L, rockTailDelayMs);
            this.nextMobSpawnAt = downEndAt;
            this.nextDebugAt = downEndAt;
            this.nextRuneDamageAt = downEndAt + 1000L;
            this.lastNearbyPlayerAt = downEndAt;
            this.nextStandReminderAt = downEndAt;
            this.runeHealth = Math.max(1.0f, config.runeMaxHealth());
        }

        @Nonnull UUID id() { return this.id; }
        @Nonnull UUID worldId() { return this.worldId; }
        @Nonnull UUID activatingPlayerId() { return this.activatingPlayerId; }
        @Nonnull List<ClusterBlock> sourceBlocks() { return this.sourceBlocks; }
        @Nonnull List<ClusterBlock> loweredBlocks() { return this.loweredBlocks; }
        @Nonnull List<ClusterBlock> sourceDetachedRocks() { return this.sourceDetachedRocks; }
        @Nonnull List<ClusterBlock> loweredDetachedRocks() { return this.loweredDetachedRocks; }
        @Nonnull ClusterBlock sourceCenterBlock() { return this.sourceCenterBlock; }
        @Nonnull ClusterBlock loweredCenterBlock() { return this.loweredCenterBlock; }
        @Nonnull ClusterBlock sourceRuneBlock() { return this.sourceRuneBlock; }
        @Nonnull ClusterBlock loweredRuneBlock() { return this.loweredRuneBlock; }
        int sourceRuneInitialRotation() { return this.sourceRuneInitialRotation; }
        long downStartAt() { return this.downStartAt; }
        long downEndAt() { return this.downEndAt; }
        long moveDurationMs() { return this.moveDurationMs; }
        long holdEndAt() { return this.holdEndAt; }
        void holdEndAt(long holdEndAt) { this.holdEndAt = holdEndAt; }
        long upEndAt() { return this.upEndAt; }
        long upStartAt() { return this.upStartAt; }
        @Nonnull OrangeBlobExtractionConfigManager.ExtractionConfigState config() { return this.config; }
        @Nonnull List<SupportBlock> supportBlocks() { return this.supportBlocks; }
        double extractionRadiusBlocks() { return this.extractionRadiusBlocks; }
        double extractionMinHeightOffset() { return this.extractionMinHeightOffset; }
        double extractionMaxHeightOffset() { return this.extractionMaxHeightOffset; }
        double enemySpawnMinRadiusBlocks() { return this.enemySpawnMinRadiusBlocks; }
        double enemySpawnMaxRadiusBlocks() { return this.enemySpawnMaxRadiusBlocks; }
        double enemySpawnMinHeightOffset() { return this.enemySpawnMinHeightOffset; }
        double enemySpawnMaxHeightOffset() { return this.enemySpawnMaxHeightOffset; }
        long extractionWindowDurationMs() { return this.extractionWindowDurationMs; }
        long rockTailDelayMs() { return this.rockTailDelayMs; }
        @Nonnull Phase phase() { return this.phase; }
        void phase(@Nonnull Phase phase) { this.phase = phase; }
        @Nonnull RockPhase rockPhase() { return this.rockPhase; }
        void rockPhase(@Nonnull RockPhase rockPhase) { this.rockPhase = rockPhase; }
        @Nonnull List<Mover> movers() { return this.movers; }
        @Nonnull List<Mover> rockMovers() { return this.rockMovers; }
        @Nonnull List<Ref<EntityStore>> spawnedMobRefs() { return this.spawnedMobRefs; }
        @Nonnull List<com.hypixel.hytale.math.vector.Vector3d> recentMobSpawnPositions() { return this.recentMobSpawnPositions; }
        Ref<EntityStore> runeBodyRef() { return this.runeBodyRef; }
        void runeBodyRef(Ref<EntityStore> runeBodyRef) { this.runeBodyRef = runeBodyRef; }
        Ref<EntityStore> runeAggroProxyRef() { return this.runeAggroProxyRef; }
        void runeAggroProxyRef(Ref<EntityStore> runeAggroProxyRef) { this.runeAggroProxyRef = runeAggroProxyRef; }
        long nextMobSpawnAt() { return this.nextMobSpawnAt; }
        void nextMobSpawnAt(long nextMobSpawnAt) { this.nextMobSpawnAt = nextMobSpawnAt; }
        long nextDebugAt() { return this.nextDebugAt; }
        void nextDebugAt(long nextDebugAt) { this.nextDebugAt = nextDebugAt; }
        long nextRuneDamageAt() { return this.nextRuneDamageAt; }
        void nextRuneDamageAt(long nextRuneDamageAt) { this.nextRuneDamageAt = nextRuneDamageAt; }
        long lastNearbyPlayerAt() { return this.lastNearbyPlayerAt; }
        void lastNearbyPlayerAt(long lastNearbyPlayerAt) { this.lastNearbyPlayerAt = lastNearbyPlayerAt; }
        long nextStandReminderAt() { return this.nextStandReminderAt; }
        void nextStandReminderAt(long nextStandReminderAt) { this.nextStandReminderAt = nextStandReminderAt; }
        boolean loweredPlaced() { return this.loweredPlaced; }
        void loweredPlaced(boolean loweredPlaced) { this.loweredPlaced = loweredPlaced; }
        boolean sourcePlaced() { return this.sourcePlaced; }
        void sourcePlaced(boolean sourcePlaced) { this.sourcePlaced = sourcePlaced; }
        boolean loweredRocksPlaced() { return this.loweredRocksPlaced; }
        void loweredRocksPlaced(boolean loweredRocksPlaced) { this.loweredRocksPlaced = loweredRocksPlaced; }
        boolean sourceRocksPlaced() { return this.sourceRocksPlaced; }
        void sourceRocksPlaced(boolean sourceRocksPlaced) { this.sourceRocksPlaced = sourceRocksPlaced; }
        boolean supportBlocksPlaced() { return this.supportBlocksPlaced; }
        void supportBlocksPlaced(boolean supportBlocksPlaced) { this.supportBlocksPlaced = supportBlocksPlaced; }
        float runeHealth() { return this.runeHealth; }
        void runeHealth(float runeHealth) { this.runeHealth = runeHealth; }
        int wavesSpawned() { return this.wavesSpawned; }
        void wavesSpawned(int wavesSpawned) { this.wavesSpawned = wavesSpawned; }
        boolean extractionDispatchStarted() { return this.extractionDispatchStarted; }
        void extractionDispatchStarted(boolean extractionDispatchStarted) { this.extractionDispatchStarted = extractionDispatchStarted; }
        boolean extractionDispatchSucceeded() { return this.extractionDispatchSucceeded; }
        void extractionDispatchSucceeded(boolean extractionDispatchSucceeded) { this.extractionDispatchSucceeded = extractionDispatchSucceeded; }
        String extractionDispatchFailure() { return this.extractionDispatchFailure; }
        void extractionDispatchFailure(String extractionDispatchFailure) { this.extractionDispatchFailure = extractionDispatchFailure; }
        boolean extractionReady() { return this.extractionReady; }
        void extractionReady(boolean extractionReady) { this.extractionReady = extractionReady; }
        long extractionAutoLaunchAt() { return this.extractionAutoLaunchAt; }
        void extractionAutoLaunchAt(long extractionAutoLaunchAt) { this.extractionAutoLaunchAt = extractionAutoLaunchAt; }
        boolean launchRequested() { return this.launchRequested; }
        void launchRequested(boolean launchRequested) { this.launchRequested = launchRequested; }
        int lastAnnouncedHealthTier() { return this.lastAnnouncedHealthTier; }
        void lastAnnouncedHealthTier(int lastAnnouncedHealthTier) { this.lastAnnouncedHealthTier = lastAnnouncedHealthTier; }
        boolean extractionWindowActive() { return this.extractionWindowActive; }
        void extractionWindowActive(boolean extractionWindowActive) { this.extractionWindowActive = extractionWindowActive; }
        long extractionWindowEndsAt() { return this.extractionWindowEndsAt; }
        void extractionWindowEndsAt(long extractionWindowEndsAt) { this.extractionWindowEndsAt = extractionWindowEndsAt; }
        @Nonnull Set<UUID> extractionWindowPlayerIds() { return this.extractionWindowPlayerIds; }
        boolean rockDownSignalPending() { return this.rockDownSignalPending; }
        void rockDownSignalPending(boolean rockDownSignalPending) { this.rockDownSignalPending = rockDownSignalPending; }
        long rockDownSignalAt() { return this.rockDownSignalAt; }
        void rockDownSignalAt(long rockDownSignalAt) { this.rockDownSignalAt = rockDownSignalAt; }
        boolean rockUpSignalPending() { return this.rockUpSignalPending; }
        void rockUpSignalPending(boolean rockUpSignalPending) { this.rockUpSignalPending = rockUpSignalPending; }
        long rockUpSignalAt() { return this.rockUpSignalAt; }
        void rockUpSignalAt(long rockUpSignalAt) { this.rockUpSignalAt = rockUpSignalAt; }
        long rockDownEndAt() { return this.rockDownEndAt; }
        void rockDownEndAt(long rockDownEndAt) { this.rockDownEndAt = rockDownEndAt; }
        long rockUpEndAt() { return this.rockUpEndAt; }
        void rockUpEndAt(long rockUpEndAt) { this.rockUpEndAt = rockUpEndAt; }

        void signalRockMoveDown(long now) {
            this.rockDownSignalPending = true;
            this.rockDownSignalAt = now;
            this.rockUpSignalPending = false;
        }

        void signalRockMoveUp(long now) {
            this.rockUpSignalPending = true;
            this.rockUpSignalAt = now;
            this.rockDownSignalPending = false;
        }

        void beginMoveUp(long now) {
            this.upStartAt = now;
            this.upEndAt = now + this.moveDurationMs;
            this.launchRequested = false;
            this.extractionWindowActive = false;
            this.extractionWindowEndsAt = 0L;
            this.extractionWindowPlayerIds.clear();
        }

        boolean complete() {
            return this.phase == Phase.COMPLETE;
        }
    }

    static final class Mover {
        @Nonnull private final String blockId;
        private final int rotation;
        private final double startX;
        private final double startY;
        private final double startZ;
        private final double endX;
        private final double endY;
        private final double endZ;
        private final long startAt;
        private final long endAt;
        private Ref<EntityStore> moverRef;

        Mover(
                @Nonnull String blockId,
                int rotation,
                double startX,
                double startY,
                double startZ,
                double endX,
                double endY,
                double endZ,
                long startAt,
                long endAt
        ) {
            this.blockId = blockId;
            this.rotation = rotation;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.endX = endX;
            this.endY = endY;
            this.endZ = endZ;
            this.startAt = startAt;
            this.endAt = endAt;
        }

        @Nonnull String blockId() { return this.blockId; }
        int rotation() { return this.rotation; }
        double startX() { return this.startX; }
        double startY() { return this.startY; }
        double startZ() { return this.startZ; }
        double endX() { return this.endX; }
        double endY() { return this.endY; }
        double endZ() { return this.endZ; }
        long startAt() { return this.startAt; }
        long endAt() { return this.endAt; }
        Ref<EntityStore> moverRef() { return this.moverRef; }
        void moverRef(Ref<EntityStore> moverRef) { this.moverRef = moverRef; }
    }
}