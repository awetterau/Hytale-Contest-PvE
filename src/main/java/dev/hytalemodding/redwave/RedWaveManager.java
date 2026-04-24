package dev.hytalemodding.redwave;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.state.run.RunEnvironmentPainter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedWaveManager {
    private static final float UNDO_CHUNK_INTERVAL_SECONDS = 0.5f;
    private static final int CHUNK_SIZE = 16;

    private static final ConcurrentHashMap<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ActiveWave>> ACTIVE_WAVES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, UndoSession>> ACTIVE_UNDO_SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ArrayList<UndoSession>>> UNDO_HISTORY_BY_CORE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, UndoProcess>> UNDO_PROCESSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> WORLD_READY_FLAGS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> WORLD_SPREAD_SPEED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> WORLD_FRONTIER_LIMIT = new ConcurrentHashMap<>();
    private static final Vector3i[] ADJACENT_OFFSETS = new Vector3i[]{
            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0),
            new Vector3i(0, 1, 0),
            new Vector3i(0, -1, 0),
            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1)
    };
    private static final Vector3i[] SIDE_ADJACENT_OFFSETS = new Vector3i[]{
            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1)
    };
    private static final Vector3i[] ALL_ADJACENT_OFFSETS;

    static {
        List<Vector3i> offsets = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    offsets.add(new Vector3i(x, y, z));
                }
            }
        }
        ALL_ADJACENT_OFFSETS = offsets.toArray(new Vector3i[0]);
    }

    private RedWaveManager() {
    }

    public static void setCore(@Nonnull UUID playerId, @Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        Selection selection = SELECTIONS.computeIfAbsent(playerId, id -> new Selection(worldId));
        selection.worldId = worldId;
        selection.corePos = new Vector3i(corePos);
    }

    public static void setRadius(@Nonnull UUID playerId, @Nonnull UUID worldId, int radius) {
        Selection selection = SELECTIONS.computeIfAbsent(playerId, id -> new Selection(worldId));
        selection.worldId = worldId;
        selection.radiusBlocks = radius;
    }

    @Nullable
    public static Selection getSelection(@Nonnull UUID playerId) {
        return SELECTIONS.get(playerId);
    }

    @Nullable
    public static ActiveWave getActiveWave(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, ActiveWave> waves = ACTIVE_WAVES.get(worldId);
        if (waves == null || waves.isEmpty()) {
            return null;
        }
        return waves.values().iterator().next();
    }

    @Nullable
    public static ActiveWave getActiveWave(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        ConcurrentHashMap<String, ActiveWave> waves = ACTIVE_WAVES.get(worldId);
        if (waves == null) {
            return null;
        }
        return waves.get(coreKey(corePos));
    }

    @Nonnull
    public static List<ActiveWave> getActiveWaves(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, ActiveWave> waves = ACTIVE_WAVES.get(worldId);
        if (waves == null || waves.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(waves.values());
    }

    @Nonnull
    public static ActiveWave startWave(@Nonnull UUID worldId, @Nonnull Vector3i corePos, int radiusBlocks, float seconds) {
        return startWave(worldId, corePos, radiusBlocks, seconds, false);
    }

    @Nonnull
    public static ActiveWave startWave(
            @Nonnull UUID worldId,
            @Nonnull Vector3i corePos,
            int radiusBlocks,
            float seconds,
            boolean ignoreFrontierLimit
    ) {
        ActiveWave wave = new ActiveWave(worldId, corePos, radiusBlocks, seconds, ignoreFrontierLimit);
        ACTIVE_WAVES
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(coreKey(corePos), wave);
        WORLD_READY_FLAGS.put(worldId, Boolean.TRUE);
        return wave;
    }

    public static void clearWave(@Nonnull UUID worldId) {
        ACTIVE_WAVES.remove(worldId);
    }

    public static void clearRuntime(@Nonnull UUID worldId) {
        ACTIVE_WAVES.remove(worldId);
        ACTIVE_UNDO_SESSIONS.remove(worldId);
        UNDO_HISTORY_BY_CORE.remove(worldId);
        UNDO_PROCESSES.remove(worldId);
        WORLD_READY_FLAGS.remove(worldId);
        WORLD_SPREAD_SPEED.remove(worldId);
        WORLD_FRONTIER_LIMIT.remove(worldId);
    }

    public static boolean isUndoRecordingEnabled() {
        return RedWaveConfig.ENABLE_UNDO_RECORDING;
    }

    public static void setWorldSpreadSpeed(@Nonnull UUID worldId, int speed) {
        WORLD_SPREAD_SPEED.put(worldId, Math.max(1, speed));
    }

    public static int getWorldSpreadSpeed(@Nonnull UUID worldId) {
        return Math.max(1, WORLD_SPREAD_SPEED.getOrDefault(worldId, RedWaveConfig.DEFAULT_SPREAD_SPEED_BLOCKS_PER_TICK));
    }

    public static void setWorldFrontierLimit(@Nonnull UUID worldId, int limit) {
        WORLD_FRONTIER_LIMIT.put(worldId, Math.max(512, limit));
    }

    public static int getWorldFrontierLimit(@Nonnull UUID worldId) {
        return Math.max(512, WORLD_FRONTIER_LIMIT.getOrDefault(worldId, RedWaveConfig.DEFAULT_FRONTIER_LIMIT));
    }

    public static void clearWave(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        ConcurrentHashMap<String, ActiveWave> waves = ACTIVE_WAVES.get(worldId);
        if (waves == null) {
            return;
        }
        waves.remove(coreKey(corePos));
        if (waves.isEmpty()) {
            ACTIVE_WAVES.remove(worldId);
        }
    }

    public static void beginUndoSession(@Nonnull UUID worldId) {
        beginUndoSession(worldId, new Vector3i(0, 0, 0));
    }

    public static void beginUndoSession(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        if (!RedWaveConfig.ENABLE_UNDO_RECORDING) {
            return;
        }
        UndoSession session = new UndoSession();
        String coreKey = coreKey(corePos);
        ACTIVE_UNDO_SESSIONS
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(coreKey, session);
        UNDO_HISTORY_BY_CORE
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(coreKey, ignored -> new ArrayList<>())
                .add(session);
    }

    public static void recordOriginalBlock(@Nonnull UUID worldId, int x, int y, int z, @Nonnull String blockId) {
        if (!RedWaveConfig.ENABLE_UNDO_RECORDING) {
            return;
        }
        ConcurrentHashMap<String, UndoSession> sessions = ACTIVE_UNDO_SESSIONS.get(worldId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        sessions.values().iterator().next().record(x, y, z, blockId);
    }

    public static void recordOriginalBlock(@Nonnull UUID worldId, @Nonnull Vector3i corePos, int x, int y, int z, @Nonnull String blockId) {
        if (!RedWaveConfig.ENABLE_UNDO_RECORDING) {
            return;
        }
        ConcurrentHashMap<String, UndoSession> sessions = ACTIVE_UNDO_SESSIONS.get(worldId);
        if (sessions == null) {
            return;
        }
        UndoSession session = sessions.get(coreKey(corePos));
        if (session == null) {
            return;
        }
        session.record(x, y, z, blockId);
    }

    @Nullable
    public static UndoSession takeUndoSession(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, ArrayList<UndoSession>> byCore = UNDO_HISTORY_BY_CORE.remove(worldId);
        ACTIVE_UNDO_SESSIONS.remove(worldId);
        if (byCore == null || byCore.isEmpty()) {
            return null;
        }

        UndoSession merged = new UndoSession();
        for (ArrayList<UndoSession> sessions : byCore.values()) {
            for (UndoSession session : sessions) {
                merged.append(session);
            }
        }
        return merged.size() == 0 ? null : merged;
    }

    @Nullable
    public static UndoSession takeUndoSessionsForCore(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        String targetCoreKey = coreKey(corePos);
        ConcurrentHashMap<String, ArrayList<UndoSession>> byCore = UNDO_HISTORY_BY_CORE.get(worldId);
        if (byCore == null) {
            return null;
        }

        ArrayList<UndoSession> sessions = byCore.remove(targetCoreKey);
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }

        ConcurrentHashMap<String, UndoSession> activeByCore = ACTIVE_UNDO_SESSIONS.get(worldId);
        if (activeByCore != null) {
            activeByCore.remove(targetCoreKey);
            if (activeByCore.isEmpty()) {
                ACTIVE_UNDO_SESSIONS.remove(worldId);
            }
        }

        if (byCore.isEmpty()) {
            UNDO_HISTORY_BY_CORE.remove(worldId);
        }

        UndoSession merged = new UndoSession();
        for (UndoSession session : sessions) {
            merged.append(session);
        }
        return merged.size() == 0 ? null : merged;
    }

    public static boolean beginUndoProcess(@Nonnull UUID worldId, @Nullable UndoSession undoSession) {
        if (undoSession == null || undoSession.size() == 0) {
            WORLD_READY_FLAGS.put(worldId, Boolean.TRUE);
            return false;
        }

        UNDO_PROCESSES
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put("__global__", new UndoProcess(undoSession));
        WORLD_READY_FLAGS.put(worldId, Boolean.FALSE);
        return true;
    }

    public static boolean beginUndoProcess(@Nonnull UUID worldId, @Nonnull Vector3i corePos, @Nullable UndoSession undoSession) {
        if (undoSession == null || undoSession.size() == 0) {
            WORLD_READY_FLAGS.put(worldId, Boolean.TRUE);
            return false;
        }

        UNDO_PROCESSES
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(coreKey(corePos), new UndoProcess(undoSession));
        WORLD_READY_FLAGS.put(worldId, Boolean.FALSE);
        return true;
    }

    public static boolean processUndoTick(@Nonnull UUID worldId, @Nonnull World world, float dt) {
        ConcurrentHashMap<String, UndoProcess> processes = UNDO_PROCESSES.get(worldId);
        if (processes == null || processes.isEmpty()) {
            return false;
        }

        boolean any = false;
        ArrayList<String> done = new ArrayList<>();
        for (var entry : processes.entrySet()) {
            UndoProcess process = entry.getValue();
            process.tick(world, dt);
            any = true;
            if (process.done()) {
                done.add(entry.getKey());
            }
        }
        for (String key : done) {
            processes.remove(key);
        }
        if (processes.isEmpty()) {
            UNDO_PROCESSES.remove(worldId);
            WORLD_READY_FLAGS.put(worldId, Boolean.TRUE);
        }
        return any;
    }

    @Nullable
    public static UndoProcessStatus getUndoProcessStatus(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, UndoProcess> processes = UNDO_PROCESSES.get(worldId);
        if (processes == null || processes.isEmpty()) {
            return null;
        }
        return processes.values().iterator().next().status();
    }

    @Nullable
    public static UndoProcessStatus getUndoProcessStatus(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        ConcurrentHashMap<String, UndoProcess> processes = UNDO_PROCESSES.get(worldId);
        if (processes == null) {
            return null;
        }
        UndoProcess process = processes.get(coreKey(corePos));
        if (process == null) {
            return null;
        }
        return process.status();
    }

    public static boolean isWorldReady(@Nonnull UUID worldId) {
        return WORLD_READY_FLAGS.getOrDefault(worldId, Boolean.TRUE);
    }


    public static boolean isGlobalUndoRunning(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, UndoProcess> processes = UNDO_PROCESSES.get(worldId);
        return processes != null && processes.containsKey("__global__");
    }

    public static boolean isCoreReady(@Nonnull UUID worldId, @Nonnull Vector3i corePos) {
        ConcurrentHashMap<String, UndoProcess> processes = UNDO_PROCESSES.get(worldId);
        if (processes == null) {
            return true;
        }
        if (processes.containsKey("__global__")) {
            return false;
        }
        return !processes.containsKey(coreKey(corePos));
    }

    @Nonnull
    private static String coreKey(@Nonnull Vector3i corePos) {
        return corePos.x + ":" + corePos.y + ":" + corePos.z;
    }
    public static boolean shouldConvertBlock(@Nullable BlockType blockType) {
        if (blockType == null || blockType == BlockType.EMPTY) {
            return false;
        }
        if (blockType.getMaterial() != BlockMaterial.Solid) {
            return false;
        }
        if (!isFullCubeBlock(blockType)) {
            return false;
        }

        String id = blockType.getId();
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (isCrimsonFamilyId(id) || isCrimsonFamilyBlock(blockType) || RedWaveConfig.isCoreBlockId(id)) {
            return false;
        }

        String lowered = id.toLowerCase(Locale.ROOT);
        for (String keyword : RedWaveConfig.NON_CONVERTIBLE_ID_KEYWORDS) {
            if (lowered.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isFullCubeBlock(@Nullable BlockType blockType) {
        if (blockType == null || blockType == BlockType.EMPTY) {
            return false;
        }
        if (blockType.getMaterial() != BlockMaterial.Solid || blockType.getOpacity() != Opacity.Solid) {
            return false;
        }
        DrawType drawType = blockType.getDrawType();
        return drawType == DrawType.Cube || drawType == DrawType.GizmoCube;
    }

    public static boolean hasAdjacentSolidBlock(@Nonnull World world, @Nonnull Vector3i pos) {
        return countFullCubeNeighbors(world, pos, ADJACENT_OFFSETS, 1) > 0;
    }

    public static boolean hasSideAdjacentSolidBlock(@Nonnull World world, @Nonnull Vector3i pos) {
        return countFullCubeNeighbors(world, pos, SIDE_ADJACENT_OFFSETS, 1) > 0;
    }

    public static boolean isHiddenBySurroundingBlocks(@Nonnull World world, @Nonnull Vector3i pos) {
        return countFullCubeNeighbors(world, pos, ADJACENT_OFFSETS, ADJACENT_OFFSETS.length) == ADJACENT_OFFSETS.length;
    }

    public static boolean isExposedToAirOrEdge(@Nonnull World world, @Nonnull Vector3i pos) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    BlockType neighbor = world.getBlockType(pos.x + x, pos.y + y, pos.z + z);
                    if (!isFullCubeBlock(neighbor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasAdjacentWaveAnchor(@Nonnull World world, @Nonnull Vector3i pos) {
        for (Vector3i offset : ALL_ADJACENT_OFFSETS) {
            BlockType neighbor = world.getBlockType(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            if (neighbor == null || neighbor == BlockType.EMPTY) {
                continue;
            }
            String id = neighbor.getId();
            if (isCrimsonFamilyId(id) || isCrimsonFamilyBlock(neighbor) || RedWaveConfig.isCoreBlockId(id)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCrimsonFamilyId(@Nullable String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        return RedWaveConfig.CRIMSON_LAYER_BLOCK_ID.equals(blockId)
                || RedWaveConfig.CRIMSON_PLATE_BLOCK_ID.equals(blockId);
    }

    public static boolean isCrimsonFamilyBlock(@Nullable BlockType blockType) {
        if (blockType == null || blockType == BlockType.EMPTY) {
            return false;
        }
        Object family = invokeOptionalZeroArg(blockType, "getFamily");
        if (family == null) {
            family = invokeOptionalZeroArg(blockType, "family");
        }
        if (family instanceof String familyName && "crimson".equalsIgnoreCase(familyName)) {
            return true;
        }

        Object tags = invokeOptionalZeroArg(blockType, "getTags");
        if (tags == null) {
            tags = invokeOptionalZeroArg(blockType, "tags");
        }
        return containsCrimsonTag(tags);
    }

    @Nullable
    private static Object invokeOptionalZeroArg(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean containsCrimsonTag(@Nullable Object tags) {
        if (tags == null) {
            return false;
        }
        if (tags instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry != null && "crimson".equalsIgnoreCase(String.valueOf(entry))) {
                    return true;
                }
            }
            return false;
        }
        if (tags instanceof String tagText) {
            if ("crimson".equalsIgnoreCase(tagText)) {
                return true;
            }
            for (String token : tagText.split("[,;|\\s]+")) {
                if ("crimson".equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countFullCubeNeighbors(
            @Nonnull World world,
            @Nonnull Vector3i pos,
            @Nonnull Vector3i[] offsets,
            int stopAfter
    ) {
        int count = 0;
        for (Vector3i offset : offsets) {
            BlockType neighbor = world.getBlockType(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            if (!isFullCubeBlock(neighbor)) {
                continue;
            }
            count++;
            if (count >= stopAfter) {
                return count;
            }
        }
        return count;
    }

    public static final class Selection {
        @Nonnull
        private UUID worldId;
        @Nullable
        private Vector3i corePos;
        @Nullable
        private Integer radiusBlocks;

        private Selection(@Nonnull UUID worldId) {
            this.worldId = worldId;
            this.radiusBlocks = RedWaveConfig.DEFAULT_RADIUS_BLOCKS;
        }

        @Nonnull
        public UUID worldId() {
            return this.worldId;
        }

        @Nullable
        public Vector3i corePos() {
            return this.corePos;
        }

        @Nullable
        public Integer radiusBlocks() {
            return this.radiusBlocks;
        }

        public boolean isComplete() {
            return this.corePos != null && this.radiusBlocks != null && this.radiusBlocks > 0;
        }
    }

    public static final class ActiveWave {
        private static final int[][] LOCAL_SPREAD_OFFSETS = new int[][]{
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
                {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
                {0, 1, 0}, {0, -1, 0}
        };
        private static final int FINAL_MASK_SIDES = 24;
        private static final float INITIAL_DYNAMIC_RADIUS = 26.0f;
        private static final float INITIAL_DYNAMIC_HALF_HEIGHT = 12.0f;
        private static final float DYNAMIC_RADIUS_GROWTH_PER_SECOND = 3.8f;
        private static final float DYNAMIC_HEIGHT_GROWTH_PER_SECOND = 2.0f;
        private static final float MIN_RADIUS_BEFORE_CONVERSION = 30.0f;
        @Nonnull
        private final UUID worldId;
        @Nonnull
        private final Vector3i corePos;
        private final int radiusBlocks;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int width;
        private final long totalBlocks;
        private final double targetBlocksPerSecond;
        private final float targetSeconds;
        private final int conversionLimit;
        private final int frontierLimit;
        private final boolean ignoreFrontierLimit;
        private final long noiseSeed;
        @Nonnull
        private final double[] lobeLengthByIndex;
        @Nonnull
        private final double[] lobeSharpnessByIndex;
        @Nonnull
        private final Random random;
        @Nonnull
        private final boolean[] inRadius;
        @Nonnull
        private final boolean[] expanded;
        @Nonnull
        private final boolean[] converted;
        @Nonnull
        private final boolean[] queued;
        @Nonnull
        private final byte[] maskState;
        @Nonnull
        private final ArrayDeque<Integer> frontier = new ArrayDeque<>();
        private final float innerUniformRadius;
        private final float innerUniformHeight;
        private final int innerCenter;
        private boolean bootstrapped;
        private long convertedCount;
        private long convertedActualCount;
        private long horizontalFootprintBlocks;
        private double convertedRingSum;
        private double tickBudget;
        private int tickCounter;
        private float dynamicHorizontalRadius;
        private float dynamicHalfHeight;
        private boolean fullyExpandedMask;
        private boolean meshReadyForConversion;

        private ActiveWave(@Nonnull UUID worldId, @Nonnull Vector3i corePos, int radiusBlocks, float seconds, boolean ignoreFrontierLimit) {
            this.worldId = worldId;
            this.corePos = new Vector3i(corePos);
            this.radiusBlocks = radiusBlocks;
            this.ignoreFrontierLimit = ignoreFrontierLimit;

            int padding = 3;
            this.minX = corePos.x - radiusBlocks - padding;
            this.minY = corePos.y - radiusBlocks - padding;
            this.minZ = corePos.z - radiusBlocks - padding;
            this.width = (radiusBlocks * 2) + (padding * 2) + 1;

            int totalCells = this.width * this.width * this.width;
            this.inRadius = new boolean[totalCells];
            this.expanded = new boolean[totalCells];
            this.converted = new boolean[totalCells];
            this.queued = new boolean[totalCells];
            this.maskState = new byte[totalCells];
            long seed = worldId.getLeastSignificantBits() ^ worldId.getMostSignificantBits() ^ corePos.hashCode() ^ System.nanoTime();
            this.random = new Random(seed);
            this.innerUniformRadius = Math.max(1.0f, this.radiusBlocks);
            this.innerUniformHeight = Math.max(1.0f, this.radiusBlocks);
            this.innerCenter = this.radiusBlocks + padding;
            this.dynamicHorizontalRadius = Math.max(2.0f, Math.min(this.innerUniformRadius, INITIAL_DYNAMIC_RADIUS));
            this.dynamicHalfHeight = Math.max(1.0f, Math.min(this.innerUniformHeight, INITIAL_DYNAMIC_HALF_HEIGHT));
            this.fullyExpandedMask = this.dynamicHorizontalRadius >= this.innerUniformRadius
                    && this.dynamicHalfHeight >= this.innerUniformHeight;
            this.meshReadyForConversion = false;
            this.lobeLengthByIndex = new double[FINAL_MASK_SIDES];
            this.lobeSharpnessByIndex = new double[FINAL_MASK_SIDES];
            this.prepareLobeJitter(seed);
            this.horizontalFootprintBlocks = estimateHorizontalFootprintBlocks(this.innerUniformRadius);
            this.totalBlocks = estimateTotalBlocks(this.innerUniformRadius, this.innerUniformHeight);

            this.frontierLimit = getWorldFrontierLimit(worldId);
            this.conversionLimit = this.ignoreFrontierLimit
                    ? (int) this.totalBlocks
                    : (int) Math.min(this.totalBlocks, this.frontierLimit);
            this.targetSeconds = Math.max(0.1f, seconds);
            double targetWork = Math.max(1.0d, this.horizontalFootprintBlocks * RedWaveConfig.FINAL_SHAPE_WORK_SCALE);
            this.targetBlocksPerSecond = Math.max(0.0001d, targetWork / this.targetSeconds);
            this.noiseSeed = seed ^ 0x9E3779B97F4A7C15L;
            this.seedAtCore(padding);
        }

        @Nonnull
        public UUID worldId() {
            return this.worldId;
        }

        @Nonnull
        public Vector3i corePos() {
            return new Vector3i(this.corePos);
        }

        public int radiusBlocks() {
            return this.radiusBlocks;
        }

        public double spreadSpeedPerTick() {
            return this.targetBlocksPerSecond / 20.0d;
        }

        public long totalBlocks() {
            return this.totalBlocks;
        }

        public float progress() {
            if (this.totalBlocks <= 0L) {
                return 0.0f;
            }
            return Math.max(0.0f, Math.min(1.0f, this.convertedCount / (float) this.totalBlocks));
        }

        public boolean done() {
            if (!this.bootstrapped) {
                return false;
            }
            return this.convertedActualCount >= this.conversionLimit || this.frontier.isEmpty();
        }

        public int takeBlocksForTick(float dt) {
            if (this.done()) {
                return 0;
            }
            this.tickCounter++;
            this.advanceMaskGrowth(dt);
            if (!this.meshReadyForConversion) {
                return 0;
            }
            if (!this.fullyExpandedMask && this.dynamicHorizontalRadius < Math.min(this.innerUniformRadius, MIN_RADIUS_BEFORE_CONVERSION)) {
                return 0;
            }
            double dynamicBlocksPerSecond = this.targetBlocksPerSecond;
            if (RedWaveConfig.EXPONENTIAL_SPEED_SCALING_ENABLED) {
                dynamicBlocksPerSecond *= this.computeExponentialSpeedMultiplier();
            }
            this.tickBudget += dynamicBlocksPerSecond * Math.max(0.0f, dt);
            int amount = (int) Math.floor(this.tickBudget);
            if (amount <= 0) {
                return 0;
            }
            this.tickBudget -= amount;
            return amount;
        }

        private double computeExponentialSpeedMultiplier() {
            double maxMultiplier = Math.max(1.0d, RedWaveConfig.EXPONENTIAL_SPEED_MAX_MULTIPLIER);
            double curve = Math.max(0.01d, RedWaveConfig.EXPONENTIAL_SPEED_CURVE);
            double radiusRatio = Math.max(0.0d, Math.min(1.0d, this.areaEquivalentRadius() / Math.max(1.0d, this.radiusBlocks)));
            double expAtProgress = Math.exp(curve * radiusRatio);
            double expAtEnd = Math.exp(curve);
            double normalized = (expAtProgress - 1.0d) / Math.max(1.0e-6d, (expAtEnd - 1.0d));
            return 1.0d + ((maxMultiplier - 1.0d) * normalized);
        }

        public boolean shouldEmitRadiusSample() {
            return this.tickCounter > 0 && (this.tickCounter % 20) == 0;
        }

        public float averageConvertedRadius() {
            if (this.convertedActualCount <= 0L) {
                return 0.0f;
            }
            return (float) (this.convertedRingSum / this.convertedActualCount);
        }

        public float areaEquivalentRadius() {
            if (this.totalBlocks <= 0L) {
                return 0.0f;
            }
            float fillRatio = Math.max(0.0f, Math.min(1.0f, this.convertedActualCount / (float) this.totalBlocks));
            return this.radiusBlocks * (float) Math.sqrt(fillRatio);
        }

        @Nullable
        public Vector3i consumeGrowthStep() {
            while (!this.frontier.isEmpty()) {
                int index = this.popPriorityFrontierIndex();
                if (index < 0) {
                    continue;
                }
                int localX = index % this.width;
                int yz = index / this.width;
                int localY = yz % this.width;
                int localZ = yz / this.width;
                if (!this.ensureMaskEvaluated(index, localX, localY, localZ) || this.expanded[index]) {
                    continue;
                }

                this.expanded[index] = true;
                return this.toWorldPosition(index);
            }
            return null;
        }

        public void markConverted(@Nonnull Vector3i pos) {
            int index = this.toIndex(pos);
            if (index >= 0 && !this.converted[index]) {
                this.converted[index] = true;
                int localX = index % this.width;
                int yz = index / this.width;
                int localZ = yz / this.width;
                int dx = localX - this.innerCenter;
                int dz = localZ - this.innerCenter;
                this.convertedRingSum += Math.sqrt((dx * dx) + (dz * dz));
                this.convertedActualCount++;
                this.convertedCount = this.convertedActualCount;
            }
        }

        public void discardGrowth(@Nonnull Vector3i pos) {
            int index = this.toIndex(pos);
            if (index < 0 || !this.expanded[index]) {
                return;
            }
            this.convertedCount = Math.max(0L, this.convertedCount - 1L);
        }

        public void deferGrowth(@Nonnull Vector3i pos) {
            int index = this.toIndex(pos);
            if (index < 0 || !this.expanded[index]) {
                return;
            }
            this.expanded[index] = false;
            this.convertedCount = Math.max(0L, this.convertedCount - 1L);
            this.enqueue(index);
        }

        public void bootstrapAroundCore(@Nonnull World world, @Nonnull UUID worldId) {
            if (this.bootstrapped) {
                return;
            }
            this.bootstrapped = true;

            int frontierSeeds = this.seedFrontierFromExistingCrimson(world);
            if (frontierSeeds > 0) {
                this.meshReadyForConversion = true;
                if (RedWaveConfig.ENABLE_CONSOLE_LOGS) {
                    System.out.println(
                            "[RedWave] core="
                                    + this.corePos.x + "," + this.corePos.y + "," + this.corePos.z
                                    + " seeded frontier from existing crimson=" + frontierSeeds
                    );
                }
                return;
            }

            int placed = this.tryBootstrapTarget(world, worldId, new Vector3i(this.corePos.x, this.corePos.y - 1, this.corePos.z), 0);

            List<Vector3i> oppositeCandidates = List.of(
                    new Vector3i(this.corePos.x + this.radiusBlocks, this.corePos.y, this.corePos.z),
                    new Vector3i(this.corePos.x - this.radiusBlocks, this.corePos.y, this.corePos.z),
                    new Vector3i(this.corePos.x, this.corePos.y, this.corePos.z + this.radiusBlocks),
                    new Vector3i(this.corePos.x, this.corePos.y, this.corePos.z - this.radiusBlocks)
            );
            for (Vector3i opposite : oppositeCandidates) {
                if (placed >= 2) {
                    break;
                }
                Vector3i fallbackTarget = this.findNearbyBootstrapTarget(world, opposite, 3, 2);
                placed = this.tryBootstrapTarget(world, worldId, fallbackTarget != null ? fallbackTarget : opposite, placed);
            }

            List<Vector3i> offsets = new ArrayList<>(ALL_ADJACENT_OFFSETS.length);
            for (Vector3i offset : ALL_ADJACENT_OFFSETS) {
                offsets.add(offset);
            }
            java.util.Collections.shuffle(offsets, this.random);

            for (Vector3i offset : offsets) {
                if (placed >= 5) {
                    break;
                }
                Vector3i target = new Vector3i(this.corePos.x + offset.x, this.corePos.y + offset.y, this.corePos.z + offset.z);
                placed = this.tryBootstrapTarget(world, worldId, target, placed);
            }
            this.meshReadyForConversion = !this.frontier.isEmpty() || this.convertedActualCount > 0L;
        }

        private int tryBootstrapTarget(@Nonnull World world, @Nonnull UUID worldId, @Nonnull Vector3i target, int placed) {
            if (placed >= 5) {
                return placed;
            }
            BlockType existing = world.getBlockType(target.x, target.y, target.z);
            if (!shouldConvertBlock(existing)) {
                return placed;
            }
            if (!isExposedToAirOrEdge(world, target)) {
                return placed;
            }

            recordOriginalBlock(worldId, this.corePos, target.x, target.y, target.z, existing.getId());
            world.setBlock(target.x, target.y, target.z, RedWaveConfig.CRIMSON_LAYER_BLOCK_ID);
            RunEnvironmentPainter.paintColumnForRunBlock(world, target.x, target.y, target.z);
            this.markConverted(target);
            int targetIndex = this.toIndex(target);
            if (targetIndex >= 0) {
                this.onConverted(target);
            }
            return placed + 1;
        }

        @Nullable
        private Vector3i findNearbyBootstrapTarget(@Nonnull World world, @Nonnull Vector3i center, int horizontalRange, int verticalRange) {
            Vector3i best = null;
            int bestDistanceSquared = Integer.MAX_VALUE;
            int bestNeighborCount = -1;

            for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                for (int dx = -horizontalRange; dx <= horizontalRange; dx++) {
                    for (int dz = -horizontalRange; dz <= horizontalRange; dz++) {
                        Vector3i candidate = new Vector3i(center.x + dx, center.y + dy, center.z + dz);
                        BlockType candidateBlock = world.getBlockType(candidate.x, candidate.y, candidate.z);
                        if (!shouldConvertBlock(candidateBlock) || !isExposedToAirOrEdge(world, candidate)) {
                            continue;
                        }
                        int neighborCount = this.countConvertibleNeighbors(world, candidate);
                        if (neighborCount < RedWaveConfig.FINAL_SHAPE_BOOTSTRAP_MIN_NEIGHBORS) {
                            continue;
                        }
                        int distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
                        if (distanceSquared < bestDistanceSquared || (distanceSquared == bestDistanceSquared && neighborCount > bestNeighborCount)) {
                            bestDistanceSquared = distanceSquared;
                            bestNeighborCount = neighborCount;
                            best = candidate;
                            if (distanceSquared == 0) {
                                return best;
                            }
                        }
                    }
                }
            }
            return best;
        }

        private int seedFrontierFromExistingCrimson(@Nonnull World world) {
            int seeded = 0;
            int scanRadius = Math.max(4, Math.min(this.radiusBlocks, Math.round(this.dynamicHorizontalRadius) + 4));
            int scanHalfHeight = Math.max(3, Math.min(this.radiusBlocks, Math.round(this.dynamicHalfHeight) + 3));

            int minScanX = this.corePos.x - scanRadius;
            int maxScanX = this.corePos.x + scanRadius;
            int minScanY = this.corePos.y - scanHalfHeight;
            int maxScanY = this.corePos.y + scanHalfHeight;
            int minScanZ = this.corePos.z - scanRadius;
            int maxScanZ = this.corePos.z + scanRadius;

            for (int x = minScanX; x <= maxScanX; x++) {
                for (int y = minScanY; y <= maxScanY; y++) {
                    for (int z = minScanZ; z <= maxScanZ; z++) {
                        BlockType existing = world.getBlockType(x, y, z);
                        if (existing == null || !RedWaveConfig.CRIMSON_LAYER_BLOCK_ID.equals(existing.getId())) {
                            continue;
                        }

                        Vector3i crimsonPos = new Vector3i(x, y, z);
                        int crimsonIndex = this.toIndex(crimsonPos);
                        if (crimsonIndex < 0) {
                            continue;
                        }
                        if (!this.ensureMaskEvaluated(crimsonIndex, x - this.minX, y - this.minY, z - this.minZ)) {
                            continue;
                        }
                        this.markConverted(crimsonPos);

                        for (Vector3i offset : ADJACENT_OFFSETS) {
                            Vector3i candidate = new Vector3i(x + offset.x, y + offset.y, z + offset.z);
                            int candidateIndex = this.toIndex(candidate);
                            if (candidateIndex < 0) {
                                continue;
                            }
                            if (!this.ensureMaskEvaluated(candidateIndex, candidate.x - this.minX, candidate.y - this.minY, candidate.z - this.minZ)) {
                                continue;
                            }
                            if (this.expanded[candidateIndex] || this.queued[candidateIndex]) {
                                continue;
                            }
                            BlockType candidateType = world.getBlockType(candidate.x, candidate.y, candidate.z);
                            if (!shouldConvertBlock(candidateType)) {
                                continue;
                            }
                            if (!isExposedToAirOrEdge(world, candidate)) {
                                continue;
                            }
                            if (!hasAdjacentWaveAnchor(world, candidate)) {
                                continue;
                            }
                            int before = this.frontier.size();
                            this.enqueue(candidateIndex);
                            if (this.frontier.size() > before) {
                                seeded++;
                            }
                        }
                    }
                }
            }
            return seeded;
        }

        private int countConvertibleNeighbors(@Nonnull World world, @Nonnull Vector3i pos) {
            int count = 0;
            for (Vector3i offset : ADJACENT_OFFSETS) {
                Vector3i neighbor = new Vector3i(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
                BlockType neighborType = world.getBlockType(neighbor.x, neighbor.y, neighbor.z);
                if (shouldConvertBlock(neighborType)) {
                    count++;
                }
            }
            return count;
        }

        private int popPriorityFrontierIndex() {
            Integer value;
            if (this.frontier.size() > 1 && this.random.nextFloat() < 0.35f) {
                value = this.frontier.pollLast();
            } else {
                value = this.frontier.pollFirst();
            }
            if (value == null) {
                return -1;
            }
            this.queued[value] = false;
            return value;
        }

        private void prepareLobeJitter(long seed) {
            Random lobeRandom = new Random(seed ^ 0xC2B2AE3D27D4EB4FL);
            for (int i = 0; i < this.lobeLengthByIndex.length; i++) {
                if (!RedWaveConfig.FINAL_SHAPE_RANDOM_TIPS_ENABLED) {
                    this.lobeLengthByIndex[i] = 1.0d;
                    this.lobeSharpnessByIndex[i] = 1.0d;
                    continue;
                }
                double baseLength = RedWaveConfig.FINAL_SHAPE_LOBE_LENGTH_MIN
                        + (lobeRandom.nextDouble() * (RedWaveConfig.FINAL_SHAPE_LOBE_LENGTH_MAX - RedWaveConfig.FINAL_SHAPE_LOBE_LENGTH_MIN));
                double lengthJitter = (lobeRandom.nextDouble() * 2.0d) - 1.0d;
                double sharpnessJitter = (lobeRandom.nextDouble() * 2.0d) - 1.0d;
                this.lobeLengthByIndex[i] = Math.max(0.45d, baseLength + (lengthJitter * RedWaveConfig.FINAL_SHAPE_LOBE_LENGTH_JITTER));
                this.lobeSharpnessByIndex[i] = Math.max(0.85d, 1.00d + (sharpnessJitter * RedWaveConfig.FINAL_SHAPE_LOBE_SHARPNESS_JITTER));
            }
        }

        private static double lerp(double a, double b, double t) {
            return a + ((b - a) * t);
        }

        private void seedAtCore(int padding) {
            int center = this.innerCenter;
            this.enqueue(this.indexOf(center, center, center));
        }

        private void enqueueNeighbors(int index) {
            int localX = index % this.width;
            int yz = index / this.width;
            int localY = yz % this.width;
            int localZ = yz / this.width;

            int start = this.random.nextInt(LOCAL_SPREAD_OFFSETS.length);
            int direction = this.random.nextBoolean() ? 1 : -1;
            for (int i = 0; i < LOCAL_SPREAD_OFFSETS.length; i++) {
                int offsetIndex = Math.floorMod(start + (i * direction), LOCAL_SPREAD_OFFSETS.length);
                int[] offset = LOCAL_SPREAD_OFFSETS[offsetIndex];
                this.tryEnqueue(localX + offset[0], localY + offset[1], localZ + offset[2]);
            }
        }

        private void tryEnqueue(int localX, int localY, int localZ) {
            if (localX < 0 || localY < 0 || localZ < 0 || localX >= this.width || localY >= this.width || localZ >= this.width) {
                return;
            }
            int index = this.indexOf(localX, localY, localZ);
            if (this.ensureMaskEvaluated(index, localX, localY, localZ)) {
                this.enqueue(index);
            }
        }

        private void enqueue(int index) {
            if (index < 0 || this.expanded[index] || this.queued[index]) {
                return;
            }
            int localX = index % this.width;
            int yz = index / this.width;
            int localY = yz % this.width;
            int localZ = yz / this.width;
            if (!this.ensureMaskEvaluated(index, localX, localY, localZ)) {
                return;
            }
            if (!this.ignoreFrontierLimit && this.frontier.size() >= this.frontierLimit) {
                return;
            }
            this.queued[index] = true;
            this.frontier.addLast(index);
        }

        private int indexOf(int localX, int localY, int localZ) {
            return localX + (localY * this.width) + (localZ * this.width * this.width);
        }

        private int toIndex(@Nonnull Vector3i pos) {
            int localX = pos.x - this.minX;
            int localY = pos.y - this.minY;
            int localZ = pos.z - this.minZ;
            if (localX < 0 || localY < 0 || localZ < 0 || localX >= this.width || localY >= this.width || localZ >= this.width) {
                return -1;
            }
            return this.indexOf(localX, localY, localZ);
        }

        public void onConverted(@Nonnull Vector3i pos) {
            int index = this.toIndex(pos);
            if (index < 0) {
                return;
            }
            this.enqueueNeighbors(index);
        }

        private void advanceMaskGrowth(float dt) {
            if (this.fullyExpandedMask) {
                return;
            }
            float safeDt = Math.max(0.0f, dt);
            this.dynamicHorizontalRadius = Math.min(this.innerUniformRadius, this.dynamicHorizontalRadius + (DYNAMIC_RADIUS_GROWTH_PER_SECOND * safeDt));
            this.dynamicHalfHeight = Math.min(this.innerUniformHeight, this.dynamicHalfHeight + (DYNAMIC_HEIGHT_GROWTH_PER_SECOND * safeDt));
            this.fullyExpandedMask = this.dynamicHorizontalRadius >= this.innerUniformRadius
                    && this.dynamicHalfHeight >= this.innerUniformHeight;
        }

        private boolean ensureMaskEvaluated(int index, int localX, int localY, int localZ) {
            byte state = this.maskState[index];
            if (state == 1) {
                return true;
            }
            if (state == 2 && this.fullyExpandedMask) {
                return false;
            }

            int dx = localX - this.innerCenter;
            int dy = localY - this.innerCenter;
            int dz = localZ - this.innerCenter;
            float horizontalDistance = (float) Math.sqrt((dx * dx) + (dz * dz));
            if (horizontalDistance > this.dynamicHorizontalRadius || Math.abs(dy) > this.dynamicHalfHeight) {
                if (this.fullyExpandedMask) {
                    this.inRadius[index] = false;
                    this.maskState[index] = 2;
                }
                return false;
            }

            boolean inside = this.computeFinalInside(dx, dy, dz);
            this.inRadius[index] = inside;
            if (inside || this.fullyExpandedMask) {
                this.maskState[index] = inside ? (byte) 1 : (byte) 2;
            } else {
                this.maskState[index] = 0;
            }
            return inside;
        }

        private boolean computeFinalInside(int dx, int dy, int dz) {
            int sidesCount = this.lobeLengthByIndex.length;
            double sides = sidesCount;
            double tau = Math.PI * 2.0d;
            float horizontalDistance = (float) Math.sqrt((dx * dx) + (dz * dz));
            double angle = Math.atan2(dz, dx);
            double normalizedAngle = angle < 0.0d ? angle + tau : angle;
            double sectorFloat = (normalizedAngle / tau) * sides;
            int sectorA = Math.floorMod((int) Math.floor(sectorFloat), sidesCount);
            int sectorB = (sectorA + 1) % sidesCount;
            double localT = sectorFloat - Math.floor(sectorFloat);
            double lerpT = localT * localT * (3.0d - (2.0d * localT));
            double lobeLength = lerp(this.lobeLengthByIndex[sectorA], this.lobeLengthByIndex[sectorB], lerpT);
            double lobeSharpness = lerp(this.lobeSharpnessByIndex[sectorA], this.lobeSharpnessByIndex[sectorB], lerpT);
            double wave = (Math.cos(angle * sides) + 1.0d) * 0.5d;
            double lobeStrength = Math.max(0.20d, Math.min(0.55d, (1.0d - RedWaveConfig.FINAL_SHAPE_TIP_SCALE) * 0.55d));
            double radiusScale = (1.0d - lobeStrength) + (lobeStrength * Math.pow(wave, lobeSharpness));
            double shapeRadius = this.innerUniformRadius * radiusScale * lobeLength;
            return horizontalDistance <= shapeRadius && Math.abs(dy) <= this.innerUniformHeight;
        }

        private static long estimateHorizontalFootprintBlocks(float radius) {
            double footprint = Math.PI * radius * radius;
            return Math.max(1L, Math.round(footprint));
        }

        private static long estimateTotalBlocks(float radius, float halfHeight) {
            double height = Math.max(1.0d, (halfHeight * 2.0d) + 1.0d);
            double volume = Math.PI * radius * radius * height;
            return Math.max(1L, Math.round(volume * 0.85d));
        }

        public boolean shouldConvertByNoise(@Nonnull Vector3i pos) {
            long h = this.noiseSeed;
            h ^= (long) pos.x * 341873128712L;
            h ^= (long) pos.y * 132897987541L;
            h ^= (long) pos.z * 42317861L;
            h ^= (h >>> 33);
            h *= 0xff51afd7ed558ccdL;
            h ^= (h >>> 33);
            double noise = ((h & 0x7fffffffffffffffL) / (double) Long.MAX_VALUE);

            int dx = pos.x - this.corePos.x;
            int dz = pos.z - this.corePos.z;
            double distNorm = Math.min(1.0d, Math.sqrt((dx * dx) + (dz * dz)) / Math.max(1.0d, this.radiusBlocks));
            double solidRadius = Math.max(0.20d, Math.min(0.90d, RedWaveConfig.FINAL_SHAPE_NOISE_SOLID_RADIUS));
            if (distNorm <= solidRadius) {
                return true;
            }
            if (this.isInInnerStarLine(distNorm, dx, dz, solidRadius)) {
                return true;
            }
            double elapsedSeconds = this.tickCounter * 0.05d;
            double progress = Math.min(1.0d, elapsedSeconds / Math.max(0.1d, this.targetSeconds));
            double edgeSpan = Math.max(0.05d, 1.0d - solidRadius);
            double edgeNorm = Math.max(0.0d, (distNorm - solidRadius) / edgeSpan);
            double edgeNormCurve = edgeNorm * edgeNorm;
            double rawThreshold = (0.44d + (edgeNormCurve * 0.44d)) - (progress * 0.06d);

            double hardenStart = Math.max(solidRadius + 0.05d, RedWaveConfig.FINAL_SHAPE_NOISE_EDGE_HARDEN_START);
            if (distNorm > hardenStart) {
                rawThreshold += 0.08d;
            }
            double gradientEnd = Math.max(solidRadius + 0.02d, RedWaveConfig.FINAL_SHAPE_NOISE_GRADIENT_END);
            double gradientSpan = Math.max(0.02d, gradientEnd - solidRadius);
            double gradientRaw = Math.max(0.0d, Math.min(1.0d, (distNorm - solidRadius) / gradientSpan));
            double strongPortion = Math.max(0.10d, Math.min(0.90d, RedWaveConfig.FINAL_SHAPE_NOISE_GRADIENT_STRONG_PORTION));
            double strongDrop = Math.max(0.0d, RedWaveConfig.FINAL_SHAPE_NOISE_GRADIENT_STRONG_DROP);
            double baseDrop = Math.max(0.0d, RedWaveConfig.FINAL_SHAPE_NOISE_GRADIENT_BASE_DROP);

            double startDrop;
            if (gradientRaw <= strongPortion) {
                double local = gradientRaw / strongPortion;
                double strongCurve = Math.pow(local, Math.max(0.25d, RedWaveConfig.FINAL_SHAPE_NOISE_GRADIENT_CHARGE));
                startDrop = lerp(strongDrop, baseDrop, strongCurve);
            } else {
                double local = (gradientRaw - strongPortion) / Math.max(0.01d, 1.0d - strongPortion);
                startDrop = lerp(baseDrop, 0.0d, local);
            }

            double threshold = rawThreshold - startDrop;

            threshold = Math.max(0.10d, Math.min(0.95d, threshold));
            return noise >= threshold;
        }

        private boolean isInInnerStarLine(double distNorm, int dx, int dz, double solidRadius) {
            if (!RedWaveConfig.FINAL_SHAPE_INNER_STAR_ENABLED) {
                return false;
            }
            double innerStarRadius = Math.max(solidRadius + 0.05d, RedWaveConfig.FINAL_SHAPE_INNER_STAR_RADIUS);
            if (distNorm > innerStarRadius) {
                return false;
            }
            double angle = Math.atan2(dz, dx);
            double tipWave = (Math.cos(angle * this.lobeLengthByIndex.length) + 1.0d) * 0.5d;
            double lineWidth = Math.max(0.01d, Math.min(0.30d, RedWaveConfig.FINAL_SHAPE_INNER_STAR_LINE_WIDTH));
            double threshold = 1.0d - lineWidth;
            return tipWave >= threshold;
        }

        @Nonnull
        private Vector3i toWorldPosition(int index) {
            int localX = index % this.width;
            int yz = index / this.width;
            int localY = yz % this.width;
            int localZ = yz / this.width;
            return new Vector3i(this.minX + localX, this.minY + localY, this.minZ + localZ);
        }
    }

    public static final class UndoSession {
        @Nonnull
        private final LinkedHashMap<ChunkPosKey, ChunkUndoBatch> chunks = new LinkedHashMap<>();
        private int size;

        private void record(int x, int y, int z, @Nonnull String blockId) {
            int chunkX = Math.floorDiv(x, CHUNK_SIZE);
            int chunkZ = Math.floorDiv(z, CHUNK_SIZE);
            ChunkPosKey chunkKey = new ChunkPosKey(chunkX, chunkZ);
            ChunkUndoBatch chunk = this.chunks.computeIfAbsent(chunkKey, ignored -> new ChunkUndoBatch(chunkKey));
            if (chunk.record(x, y, z, blockId)) {
                this.size++;
            }
        }

        public int size() {
            return this.size;
        }

        public int chunkCount() {
            return this.chunks.size();
        }

        private void append(@Nonnull UndoSession session) {
            for (ChunkUndoBatch chunk : session.chunkBatches()) {
                for (UndoEntry entry : chunk.entries()) {
                    this.record(entry.position().x, entry.position().y, entry.position().z, entry.blockId());
                }
            }
        }

        @Nonnull
        public Iterable<ChunkUndoBatch> chunkBatches() {
            return this.chunks.values();
        }
    }

    public static final class ChunkUndoBatch {
        @Nonnull
        private final ChunkPosKey chunk;
        @Nonnull
        private final LinkedHashMap<BlockPosKey, UndoEntry> entries = new LinkedHashMap<>();

        private ChunkUndoBatch(@Nonnull ChunkPosKey chunk) {
            this.chunk = chunk;
        }

        private boolean record(int x, int y, int z, @Nonnull String blockId) {
            BlockPosKey posKey = new BlockPosKey(x, y, z);
            return this.entries.putIfAbsent(posKey, new UndoEntry(new Vector3i(x, y, z), blockId)) == null;
        }

        @Nonnull
        public ChunkPosKey chunk() {
            return this.chunk;
        }

        public int size() {
            return this.entries.size();
        }

        @Nonnull
        public Iterable<UndoEntry> entries() {
            return this.entries.values();
        }
    }

    private static final class UndoProcess {
        @Nonnull
        private final Iterator<ChunkUndoBatch> chunkIterator;
        private final int totalChunks;
        private final int totalBlocks;
        private int restoredChunks;
        private int restoredBlocks;
        private float accumulated;
        private boolean done;

        private UndoProcess(@Nonnull UndoSession session) {
            List<ChunkUndoBatch> batches = new ArrayList<>();
            for (ChunkUndoBatch batch : session.chunkBatches()) {
                batches.add(batch);
            }
            this.chunkIterator = batches.iterator();
            this.totalChunks = batches.size();
            this.totalBlocks = session.size();
            this.done = this.totalChunks == 0;
        }

        private void tick(@Nonnull World world, float dt) {
            if (this.done) {
                return;
            }

            this.accumulated += dt;
            while (this.accumulated >= UNDO_CHUNK_INTERVAL_SECONDS && !this.done) {
                this.accumulated -= UNDO_CHUNK_INTERVAL_SECONDS;
                if (!this.chunkIterator.hasNext()) {
                    this.done = true;
                    break;
                }

                ChunkUndoBatch batch = this.chunkIterator.next();
                for (UndoEntry entry : batch.entries()) {
                    world.setBlock(entry.position().x, entry.position().y, entry.position().z, entry.blockId());
                    RunEnvironmentPainter.paintColumnForRunBlock(world, entry.position().x, entry.position().y, entry.position().z);
                    this.restoredBlocks++;
                }
                this.restoredChunks++;
                if (!this.chunkIterator.hasNext()) {
                    this.done = true;
                }
            }
        }

        private boolean done() {
            return this.done;
        }

        @Nonnull
        private UndoProcessStatus status() {
            return new UndoProcessStatus(this.totalChunks, this.restoredChunks, this.totalBlocks, this.restoredBlocks, this.done);
        }
    }

    public record UndoProcessStatus(
            int totalChunks,
            int restoredChunks,
            int totalBlocks,
            int restoredBlocks,
            boolean done
    ) {
    }

    public record UndoEntry(Vector3i position, String blockId) {
    }

    public record ChunkPosKey(int x, int z) {
    }

    private record BlockPosKey(int x, int y, int z) {
    }
}
