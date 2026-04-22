package dev.hytalemodding.blob;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.RunExtractionConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlareExtractionManager {
    public static final String FLARE_BLOCK_ID = "Flare_Block";
    public static final String ACTIVE_FLARE_BLOCK_ID = "Flare_Block_Active";
    public static final String LARGE_ROPE_BLOCK_ID = "Large_Rope";
    public static final String LARGE_ROPE_UP_BLOCK_ID = "Large_Rope_UP";
    public static final String EMPTY_BLOCK_ID = "Empty";
    private static final long ROPE_UP_ANIM_MS = 2200L;
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Session>> SESSIONS_BY_WORLD = new ConcurrentHashMap<>();

    private FlareExtractionManager() {
    }

    public static boolean tryActivate(
            @Nullable PlayerRef playerRef,
            @Nullable World world,
            @Nullable com.hypixel.hytale.math.vector.Vector3i flarePosition
    ) {
        if (playerRef == null || world == null || flarePosition == null) {
            return false;
        }
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        UUID worldId = world.getWorldConfig().getUuid();
        if (snapshot == null || snapshot.runWorldUuid() == null || !snapshot.runWorldUuid().equals(worldId)) {
            playerRef.sendMessage(Message.raw("Flare extraction only works during an active run."));
            return true;
        }

        RunExtractionConfigManager.VariantState variantState =
                RunExtractionConfigManager.get().getVariantState(RunExtractionConfigManager.VariantKey.ESCAPE_ROPE);
        String availabilityFailure = validateRunWindowAvailability(snapshot, variantState);
        if (availabilityFailure != null) {
            playerRef.sendMessage(Message.raw(availabilityFailure));
            return true;
        }

        BlockType blockType = world.getBlockType(flarePosition);
        if (blockType == null || !FLARE_BLOCK_ID.equals(blockType.getId())) {
            return false;
        }

        String key = posKey(flarePosition.x, flarePosition.y, flarePosition.z);
        ConcurrentHashMap<String, Session> byPosition = SESSIONS_BY_WORLD.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        if (byPosition.containsKey(key)) {
            playerRef.sendMessage(Message.raw("This flare extraction is already active."));
            return true;
        }

        long now = System.currentTimeMillis();
        long waitMs = Math.max(1000L, variantState.extractionWaitSeconds() * 1000L);
        long windowMs = Math.max(1000L, variantState.extractionWindowSeconds() * 1000L);
        int rotationBeforeInteraction = captureFlareRotationForSession(world, flarePosition.x, flarePosition.y, flarePosition.z);
        Session session = new Session(
                UUID.randomUUID(),
                worldId,
                flarePosition.x,
                flarePosition.y,
                flarePosition.z,
                rotationBeforeInteraction,
                variantState.extractionRadiusBlocks(),
                variantState.extractionMinHeightOffset(),
                variantState.extractionMaxHeightOffset(),
                now + waitMs,
                now + waitMs + windowMs
        );
        byPosition.put(key, session);

        world.setBlock(flarePosition.x, flarePosition.y, flarePosition.z, ACTIVE_FLARE_BLOCK_ID, rotationBeforeInteraction);
        playerRef.sendMessage(Message.raw("Flare activated. Rope extraction is charging."));
        return true;
    }

    @Nullable
    private static String validateRunWindowAvailability(
            @Nonnull GameSessionManager.ActiveSessionSnapshot snapshot,
            @Nonnull RunExtractionConfigManager.VariantState state
    ) {
        if (snapshot.startedAtEpochMillis() <= 0L) {
            return "Run timer not started yet.";
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - snapshot.startedAtEpochMillis()) / 1000L);
        if (elapsedSeconds < state.runEnableFromSecond()) {
            return "Flare extraction is not available yet.";
        }
        if (elapsedSeconds > state.runEnableUntilSecond()) {
            return "Flare extraction window has closed for this run.";
        }
        return null;
    }

    @Nonnull
    static Map<String, Session> getSessions(@Nonnull UUID worldId) {
        Map<String, Session> sessions = SESSIONS_BY_WORLD.get(worldId);
        return sessions == null ? Map.of() : sessions;
    }

    public static long getExtractionCountdownMillis(@Nullable UUID worldId) {
        if (worldId == null) {
            return 0L;
        }
        Map<String, Session> sessions = SESSIONS_BY_WORLD.get(worldId);
        if (sessions == null || sessions.isEmpty()) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        for (Session session : sessions.values()) {
            if (session.phase() != Phase.WAITING) {
                continue;
            }
            long targetAt = session.waitEndAt();
            if (targetAt <= 0L || targetAt <= now) {
                continue;
            }
            best = Math.min(best, targetAt - now);
        }
        return best == Long.MAX_VALUE ? 0L : best;
    }

    public static void clearRuntimeForWorld(@Nullable UUID worldId) {
        if (worldId == null) {
            return;
        }
        SESSIONS_BY_WORLD.remove(worldId);
    }

    static void removeSession(@Nonnull Session session) {
        ConcurrentHashMap<String, Session> byPosition = SESSIONS_BY_WORLD.get(session.worldId());
        if (byPosition == null) {
            return;
        }
        byPosition.remove(posKey(session.flareX(), session.flareY(), session.flareZ()));
        if (byPosition.isEmpty()) {
            SESSIONS_BY_WORLD.remove(session.worldId());
        }
    }

    @Nonnull
    static String posKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    static int captureFlareRotationForSession(@Nonnull World world, int x, int y, int z) {
        return OrangeBlobBlockManager.readRotation(world, x, y, z);
    }

    enum Phase {
        WAITING,
        WINDOW_OPEN,
        ROPE_RETRACTING,
        COMPLETE
    }

    static final class Session {
        private final UUID id;
        private final UUID worldId;
        private final int flareX;
        private final int flareY;
        private final int flareZ;
        private final int flareInitialRotation;
        private final double extractionRadiusBlocks;
        private final double extractionMinHeightOffset;
        private final double extractionMaxHeightOffset;
        private final long waitEndAt;
        private final long windowEndAt;
        private int ropeX;
        private int ropeY;
        private int ropeZ;
        private boolean ropePlaced;
        private long ropeUpEndAt;
        private Phase phase = Phase.WAITING;

        private Session(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                int flareX,
                int flareY,
                int flareZ,
                int flareInitialRotation,
                double extractionRadiusBlocks,
                double extractionMinHeightOffset,
                double extractionMaxHeightOffset,
                long waitEndAt,
                long windowEndAt
        ) {
            this.id = id;
            this.worldId = worldId;
            this.flareX = flareX;
            this.flareY = flareY;
            this.flareZ = flareZ;
            this.flareInitialRotation = flareInitialRotation;
            this.extractionRadiusBlocks = extractionRadiusBlocks;
            this.extractionMinHeightOffset = Math.min(extractionMinHeightOffset, extractionMaxHeightOffset);
            this.extractionMaxHeightOffset = Math.max(extractionMinHeightOffset, extractionMaxHeightOffset);
            this.waitEndAt = waitEndAt;
            this.windowEndAt = windowEndAt;
        }

        @Nonnull UUID id() { return this.id; }
        @Nonnull UUID worldId() { return this.worldId; }
        int flareX() { return this.flareX; }
        int flareY() { return this.flareY; }
        int flareZ() { return this.flareZ; }
        int flareInitialRotation() { return this.flareInitialRotation; }
        double extractionRadiusBlocks() { return this.extractionRadiusBlocks; }
        double extractionMinHeightOffset() { return this.extractionMinHeightOffset; }
        double extractionMaxHeightOffset() { return this.extractionMaxHeightOffset; }
        long waitEndAt() { return this.waitEndAt; }
        long windowEndAt() { return this.windowEndAt; }
        int ropeX() { return this.ropeX; }
        int ropeY() { return this.ropeY; }
        int ropeZ() { return this.ropeZ; }
        void ropePosition(int ropeX, int ropeY, int ropeZ) { this.ropeX = ropeX; this.ropeY = ropeY; this.ropeZ = ropeZ; }
        boolean ropePlaced() { return this.ropePlaced; }
        void ropePlaced(boolean ropePlaced) { this.ropePlaced = ropePlaced; }
        long ropeUpEndAt() { return this.ropeUpEndAt; }
        void ropeUpEndAt(long ropeUpEndAt) { this.ropeUpEndAt = ropeUpEndAt; }
        @Nonnull Phase phase() { return this.phase; }
        void phase(@Nonnull Phase phase) { this.phase = phase; }
    }

    static long ropeUpAnimMs() {
        return ROPE_UP_ANIM_MS;
    }
}