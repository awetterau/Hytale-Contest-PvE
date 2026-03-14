package dev.hytalemodding.redwave;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
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
        ActiveWave wave = new ActiveWave(worldId, corePos, radiusBlocks, seconds);
        ACTIVE_WAVES
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(coreKey(corePos), wave);
        WORLD_READY_FLAGS.put(worldId, Boolean.TRUE);
        return wave;
    }

    public static void clearWave(@Nonnull UUID worldId) {
        ACTIVE_WAVES.remove(worldId);
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
        ConcurrentHashMap<String, UndoSession> sessions = ACTIVE_UNDO_SESSIONS.get(worldId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        sessions.values().iterator().next().record(x, y, z, blockId);
    }

    public static void recordOriginalBlock(@Nonnull UUID worldId, @Nonnull Vector3i corePos, int x, int y, int z, @Nonnull String blockId) {
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
        if (RedWaveConfig.CRIMSON_BLOCK_ID.equals(id) || RedWaveConfig.CORE_BLOCK_ID.equals(id)) {
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
        for (Vector3i offset : ADJACENT_OFFSETS) {
            BlockType neighbor = world.getBlockType(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            if (isFullCubeBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSideAdjacentSolidBlock(@Nonnull World world, @Nonnull Vector3i pos) {
        for (Vector3i offset : SIDE_ADJACENT_OFFSETS) {
            BlockType neighbor = world.getBlockType(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            if (isFullCubeBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHiddenBySurroundingBlocks(@Nonnull World world, @Nonnull Vector3i pos) {
        for (Vector3i offset : ADJACENT_OFFSETS) {
            BlockType neighbor = world.getBlockType(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            if (!isFullCubeBlock(neighbor)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasAdjacentWaveAnchor(@Nonnull World world, @Nonnull Vector3i pos) {
        for (Vector3i offset : ALL_ADJACENT_OFFSETS) {
            BlockType neighbor = world.getBlockType(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            if (neighbor == null || neighbor == BlockType.EMPTY) {
                continue;
            }
            String id = neighbor.getId();
            if (RedWaveConfig.CRIMSON_BLOCK_ID.equals(id) || RedWaveConfig.CORE_BLOCK_ID.equals(id)) {
                return true;
            }
        }
        return false;
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
        private final float blocksPerTick;
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
        private final List<Integer> frontier = new ArrayList<>();
        private final long noiseSalt;
        private final float noiseScale;
        private final float waveScale;
        private final float fringeChance;
        private final float phaseX;
        private final float phaseZ;
        private final float lobeAmplitude;
        private final float lobeFrequency;
        private final float lobePhase;
        private final float innerUniformRadius;
        private final float innerUniformHeight;
        private final int innerCenter;
        @Nonnull
        private final int[] nextEligibleTick;
        private boolean bootstrapped;
        private long convertedCount;
        private float carry;
        private int tickCounter;

        private ActiveWave(@Nonnull UUID worldId, @Nonnull Vector3i corePos, int radiusBlocks, float seconds) {
            this.worldId = worldId;
            this.corePos = new Vector3i(corePos);
            this.radiusBlocks = radiusBlocks;

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
            this.nextEligibleTick = new int[totalCells];
            long seed = worldId.getLeastSignificantBits() ^ worldId.getMostSignificantBits() ^ corePos.hashCode() ^ System.nanoTime();
            this.random = new Random(seed);
            this.noiseSalt = this.random.nextLong();
            this.noiseScale = 1.6f + (this.random.nextFloat() * 1.8f);
            this.waveScale = 0.45f + (this.random.nextFloat() * 0.75f);
            this.fringeChance = 0.05f + (this.random.nextFloat() * 0.17f);
            this.phaseX = this.random.nextFloat() * 6.2831855f;
            this.phaseZ = this.random.nextFloat() * 6.2831855f;
            this.lobeAmplitude = 0.4f + (this.random.nextFloat() * 1.8f);
            this.lobeFrequency = 1.2f + (this.random.nextFloat() * 4.3f);
            this.lobePhase = this.random.nextFloat() * 6.2831855f;
            this.innerUniformRadius = Math.max(1.0f, this.radiusBlocks * (2.0f / 3.0f));
            this.innerUniformHeight = Math.max(2.0f, this.radiusBlocks * 0.35f);
            this.innerCenter = this.radiusBlocks + padding;
            this.totalBlocks = this.prepareRadiusMask(padding);

            int totalTicks = Math.max(1, Math.round(seconds * 20.0f));
            this.blocksPerTick = Math.max(0.01f, (float) this.totalBlocks / (float) totalTicks);
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

        public long totalBlocks() {
            return this.totalBlocks;
        }

        public boolean done() {
            return this.convertedCount >= this.totalBlocks || this.frontier.isEmpty();
        }

        public int takeBlocksForTick() {
            if (this.done()) {
                return 0;
            }
            this.tickCounter++;
            this.carry += this.blocksPerTick;
            int amount = (int) this.carry;
            if (amount <= 0) {
                return 0;
            }
            this.carry -= amount;
            return amount;
        }

        @Nullable
        public Vector3i consumeGrowthStep() {
            while (!this.frontier.isEmpty()) {
                int index = this.popPriorityFrontierIndex();
                if (this.expanded[index] || !this.inRadius[index]) {
                    continue;
                }

                this.expanded[index] = true;
                this.convertedCount++;
                this.enqueueNeighbors(index);
                return this.toWorldPosition(index);
            }
            return null;
        }

        public void markConverted(@Nonnull Vector3i pos) {
            int index = this.toIndex(pos);
            if (index >= 0) {
                this.converted[index] = true;
            }
        }

        public boolean isInInnerUniformCore(@Nonnull Vector3i pos) {
            int localX = pos.x - this.minX;
            int localY = pos.y - this.minY;
            int localZ = pos.z - this.minZ;
            if (localX < 0 || localY < 0 || localZ < 0 || localX >= this.width || localY >= this.width || localZ >= this.width) {
                return false;
            }

            int dx = localX - this.innerCenter;
            int dy = localY - this.innerCenter;
            int dz = localZ - this.innerCenter;
            float horizontalDistance = (float) Math.sqrt((dx * dx) + (dz * dz));
            return horizontalDistance <= this.innerUniformRadius && Math.abs(dy) <= this.innerUniformHeight;
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

        public boolean shouldDelayCrowdedGrowth(@Nonnull Vector3i pos) {
            int index = this.toIndex(pos);
            if (index < 0) {
                return false;
            }
            if (this.tickCounter < this.nextEligibleTick[index]) {
                return true;
            }

            int crowdedNeighbors = this.countConvertedNeighbors(index);
            if (crowdedNeighbors < 3) {
                return false;
            }

            float delayChance;
            if (crowdedNeighbors == 3) {
                delayChance = 0.78f;
            } else if (crowdedNeighbors == 4) {
                delayChance = 0.62f;
            } else if (crowdedNeighbors == 5) {
                delayChance = 0.48f;
            } else {
                delayChance = 0.35f;
            }

            if (this.random.nextFloat() < delayChance) {
                this.nextEligibleTick[index] = this.tickCounter + 2 + this.random.nextInt(7);
                return true;
            }
            return false;
        }

        public void bootstrapAroundCore(@Nonnull World world, @Nonnull UUID worldId) {
            if (this.bootstrapped) {
                return;
            }
            this.bootstrapped = true;

            int placed = this.tryBootstrapTarget(world, worldId, new Vector3i(this.corePos.x, this.corePos.y - 1, this.corePos.z), 0);

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
        }

        private int tryBootstrapTarget(@Nonnull World world, @Nonnull UUID worldId, @Nonnull Vector3i target, int placed) {
            if (placed >= 5) {
                return placed;
            }
            BlockType existing = world.getBlockType(target.x, target.y, target.z);
            if (!shouldConvertBlock(existing)) {
                return placed;
            }

            recordOriginalBlock(worldId, target.x, target.y, target.z, existing.getId());
            world.setBlock(target.x, target.y, target.z, RedWaveConfig.CRIMSON_BLOCK_ID);
            this.markConverted(target);
            int targetIndex = this.toIndex(target);
            if (targetIndex >= 0) {
                this.enqueueNeighbors(targetIndex);
            }
            return placed + 1;
        }

        private int popPriorityFrontierIndex() {
            if (this.frontier.size() == 1) {
                return this.popFrontierAt(0);
            }

            int preferredPick = -1;
            int preferredNeighborCount = 3;
            for (int i = 0; i < this.frontier.size(); i++) {
                int index = this.frontier.get(i);
                int neighborCount = this.countConvertedNeighbors(index);
                if (neighborCount >= preferredNeighborCount) {
                    preferredNeighborCount = neighborCount;
                    preferredPick = i;
                }
            }

            if (preferredPick >= 0) {
                return this.popFrontierAt(preferredPick);
            }

            return this.popFrontierAt(this.random.nextInt(this.frontier.size()));
        }

        private long prepareRadiusMask(int padding) {
            long count = 0L;
            int center = this.innerCenter;
            for (int x = 0; x < this.width; x++) {
                int dx = x - center;
                for (int y = 0; y < this.width; y++) {
                    int dy = y - center;
                    for (int z = 0; z < this.width; z++) {
                        int dz = z - center;
                        int index = this.indexOf(x, y, z);

                        float horizontalDistance = (float) Math.sqrt((dx * dx) + (dz * dz));
                        float distance = (float) Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
                        boolean inside = horizontalDistance <= this.innerUniformRadius && Math.abs(dy) <= this.innerUniformHeight;
                        if (!inside) {
                            float radialNoise = this.computeRadialNoise(dx, dy, dz);
                            float lobeNoise = this.computeLobeNoise(dx, dz);
                            float effectiveRadius = this.radiusBlocks + radialNoise + lobeNoise;
                            inside = distance <= effectiveRadius;
                            if (!inside && distance <= (this.radiusBlocks + 1.8f)) {
                                inside = this.random.nextFloat() < this.fringeChance;
                            }
                        }

                        this.inRadius[index] = inside;
                        if (inside) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        private float computeRadialNoise(int dx, int dy, int dz) {
            long hash = 1469598103934665603L;
            hash ^= (dx * 73856093L);
            hash *= 1099511628211L;
            hash ^= (dy * 19349663L);
            hash *= 1099511628211L;
            hash ^= (dz * 83492791L);
            hash *= 1099511628211L;
            hash ^= this.worldId.getLeastSignificantBits();
            hash ^= this.noiseSalt;

            float randomPart = ((hash & 0x3FFL) / 1023.0f) - 0.5f;
            float waveA = (float) Math.sin((dx * 0.31f) + (dz * 0.17f) + this.phaseX);
            float waveB = (float) Math.cos((dx * 0.11f) - (dz * 0.27f) + this.phaseZ);
            float wavePart = (waveA + waveB) * this.waveScale;
            return (randomPart * this.noiseScale) + wavePart;
        }

        private float computeLobeNoise(int dx, int dz) {
            if (dx == 0 && dz == 0) {
                return 0.0f;
            }
            float angle = (float) Math.atan2(dz, dx);
            return (float) Math.sin((angle * this.lobeFrequency) + this.lobePhase) * this.lobeAmplitude;
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

            this.tryEnqueue(localX + 1, localY, localZ);
            this.tryEnqueue(localX - 1, localY, localZ);
            this.tryEnqueue(localX, localY + 1, localZ);
            this.tryEnqueue(localX, localY - 1, localZ);
            this.tryEnqueue(localX, localY, localZ + 1);
            this.tryEnqueue(localX, localY, localZ - 1);
        }

        private void tryEnqueue(int localX, int localY, int localZ) {
            if (localX < 0 || localY < 0 || localZ < 0 || localX >= this.width || localY >= this.width || localZ >= this.width) {
                return;
            }
            this.enqueue(this.indexOf(localX, localY, localZ));
        }

        private void enqueue(int index) {
            if (!this.inRadius[index] || this.expanded[index] || this.queued[index]) {
                return;
            }
            this.queued[index] = true;
            this.frontier.add(index);
        }

        private int popFrontierAt(int pick) {
            int lastIdx = this.frontier.size() - 1;
            int value = this.frontier.get(pick);
            int last = this.frontier.get(lastIdx);
            this.frontier.set(pick, last);
            this.frontier.remove(lastIdx);
            this.queued[value] = false;
            return value;
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

        private int countConvertedNeighbors(int index) {
            int localX = index % this.width;
            int yz = index / this.width;
            int localY = yz % this.width;
            int localZ = yz / this.width;

            int neighbors = 0;
            neighbors += this.isConverted(localX + 1, localY, localZ) ? 1 : 0;
            neighbors += this.isConverted(localX - 1, localY, localZ) ? 1 : 0;
            neighbors += this.isConverted(localX, localY + 1, localZ) ? 1 : 0;
            neighbors += this.isConverted(localX, localY - 1, localZ) ? 1 : 0;
            neighbors += this.isConverted(localX, localY, localZ + 1) ? 1 : 0;
            neighbors += this.isConverted(localX, localY, localZ - 1) ? 1 : 0;
            return neighbors;
        }

        private boolean isConverted(int localX, int localY, int localZ) {
            if (localX < 0 || localY < 0 || localZ < 0 || localX >= this.width || localY >= this.width || localZ >= this.width) {
                return false;
            }
            return this.converted[this.indexOf(localX, localY, localZ)];
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

