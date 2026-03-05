package dev.hytalemodding.redwave;

import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedWaveManager {
    public static final String TARGET_BLOCK_ID = "Cloth_Block_Wool_Red";
    private static final ConcurrentHashMap<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ActiveWave> ACTIVE_WAVES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, UndoSession> UNDO_SESSIONS = new ConcurrentHashMap<>();

    private RedWaveManager() {
    }

    public static void setPos1(@Nonnull UUID playerId, @Nonnull UUID worldId, @Nonnull Vector3i pos) {
        Selection selection = SELECTIONS.computeIfAbsent(playerId, id -> new Selection(worldId));
        selection.worldId = worldId;
        selection.pos1 = new Vector3i(pos);
    }

    public static void setPos2(@Nonnull UUID playerId, @Nonnull UUID worldId, @Nonnull Vector3i pos) {
        Selection selection = SELECTIONS.computeIfAbsent(playerId, id -> new Selection(worldId));
        selection.worldId = worldId;
        selection.pos2 = new Vector3i(pos);
    }

    @Nullable
    public static Selection getSelection(@Nonnull UUID playerId) {
        return SELECTIONS.get(playerId);
    }

    @Nullable
    public static ActiveWave getActiveWave(@Nonnull UUID worldId) {
        return ACTIVE_WAVES.get(worldId);
    }

    @Nonnull
    public static ActiveWave startWave(@Nonnull UUID worldId, @Nonnull Vector3i pos1, @Nonnull Vector3i pos2, float seconds) {
        int minX = Math.min(pos1.x, pos2.x);
        int minY = Math.min(pos1.y, pos2.y);
        int minZ = Math.min(pos1.z, pos2.z);
        int maxX = Math.max(pos1.x, pos2.x);
        int maxY = Math.max(pos1.y, pos2.y);
        int maxZ = Math.max(pos1.z, pos2.z);

        int widthX = maxX - minX + 1;
        int widthY = maxY - minY + 1;
        int widthZ = maxZ - minZ + 1;
        long totalBlocks = (long) widthX * widthY * widthZ;

        int totalTicks = Math.max(1, Math.round(seconds * 20.0f));
        float blocksPerTick = Math.max(0.01f, (float) totalBlocks / (float) totalTicks);

        ActiveWave wave = new ActiveWave(worldId, minX, minY, minZ, maxX, maxY, maxZ, widthX, widthY, widthZ, totalBlocks, blocksPerTick);
        ACTIVE_WAVES.put(worldId, wave);
        return wave;
    }

    public static void clearWave(@Nonnull UUID worldId) {
        ACTIVE_WAVES.remove(worldId);
    }

    public static void beginUndoSession(@Nonnull UUID worldId) {
        UNDO_SESSIONS.put(worldId, new UndoSession());
    }

    public static void recordOriginalBlock(@Nonnull UUID worldId, int x, int y, int z, @Nonnull String blockId) {
        UndoSession undoSession = UNDO_SESSIONS.get(worldId);
        if (undoSession == null) {
            return;
        }
        undoSession.record(x, y, z, blockId);
    }

    @Nullable
    public static UndoSession takeUndoSession(@Nonnull UUID worldId) {
        return UNDO_SESSIONS.remove(worldId);
    }

    public static boolean shouldConvertBlock(@Nullable BlockType blockType) {
        if (blockType == null || blockType == BlockType.EMPTY) {
            return false;
        }
        if (blockType.getMaterial() != BlockMaterial.Solid) {
            return false;
        }

        String id = blockType.getId();
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (TARGET_BLOCK_ID.equals(id)) {
            return false;
        }

        String lowered = id.toLowerCase(Locale.ROOT);
        return !lowered.contains("leaf")
                && !lowered.contains("leaves")
                && !lowered.contains("foliage")
                && !lowered.contains("bush")
                && !lowered.contains("plant")
                && !lowered.contains("flower")
                && !lowered.contains("crop")
                && !lowered.contains("vine")
                && !lowered.contains("mushroom")
                && !lowered.contains("seaweed")
                && !lowered.contains("sapling")
                && !lowered.contains("fern")
                && !lowered.contains("reed");
    }

    @Nullable
    public static Vector3i findLookedBlock(@Nonnull World world, @Nonnull PlayerRef playerRef, double maxDistance) {
        Transform transform = playerRef.getTransform();
        Vector3d origin = transform.getPosition();

        Vector3d direction = transform.getDirection();
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();

        for (double distance = 0.0; distance <= maxDistance; distance += 0.2) {
            int x = MathUtil.floor(origin.getX() + dx * distance);
            int y = MathUtil.floor(origin.getY() + dy * distance);
            int z = MathUtil.floor(origin.getZ() + dz * distance);

            BlockType blockType = world.getBlockType(x, y, z);
            if (blockType != null && blockType != BlockType.EMPTY) {
                return new Vector3i(x, y, z);
            }
        }

        return null;
    }

    public static final class Selection {
        @Nonnull
        private UUID worldId;
        @Nullable
        private Vector3i pos1;
        @Nullable
        private Vector3i pos2;

        private Selection(@Nonnull UUID worldId) {
            this.worldId = worldId;
        }

        @Nonnull
        public UUID worldId() {
            return this.worldId;
        }

        @Nullable
        public Vector3i pos1() {
            return this.pos1;
        }

        @Nullable
        public Vector3i pos2() {
            return this.pos2;
        }

        public boolean isComplete() {
            return this.pos1 != null && this.pos2 != null;
        }
    }

    public static final class ActiveWave {
        @Nonnull
        private final UUID worldId;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final int widthX;
        private final int widthY;
        private final int widthZ;
        private final long totalBlocks;
        private final float blocksPerTick;
        @Nonnull
        private final Random random;
        @Nonnull
        private final boolean[] converted;
        @Nonnull
        private final boolean[] queued;
        @Nonnull
        private final List<Integer> frontier = new ArrayList<>();
        private long convertedCount;
        private float carry;

        private ActiveWave(
                @Nonnull UUID worldId,
                int minX,
                int minY,
                int minZ,
                int maxX,
                int maxY,
                int maxZ,
                int widthX,
                int widthY,
                int widthZ,
                long totalBlocks,
                float blocksPerTick
        ) {
            this.worldId = worldId;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.widthX = widthX;
            this.widthY = widthY;
            this.widthZ = widthZ;
            this.totalBlocks = totalBlocks;
            this.blocksPerTick = blocksPerTick;
            this.random = new Random(worldId.getLeastSignificantBits() ^ worldId.getMostSignificantBits() ^ totalBlocks);
            this.converted = new boolean[(int) totalBlocks];
            this.queued = new boolean[(int) totalBlocks];
            this.seedFromOneSide();
        }

        @Nonnull
        public UUID worldId() {
            return this.worldId;
        }

        public int minX() {
            return this.minX;
        }

        public int minY() {
            return this.minY;
        }

        public int minZ() {
            return this.minZ;
        }

        public int maxX() {
            return this.maxX;
        }

        public int maxY() {
            return this.maxY;
        }

        public int maxZ() {
            return this.maxZ;
        }

        public long totalBlocks() {
            return this.totalBlocks;
        }

        public boolean done() {
            return this.convertedCount >= this.totalBlocks;
        }

        public int takeBlocksForTick() {
            if (this.done()) {
                return 0;
            }
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
                int pick = this.random.nextInt(this.frontier.size());
                int index = this.popFrontierAt(pick);
                if (this.converted[index]) {
                    continue;
                }

                this.converted[index] = true;
                this.convertedCount++;
                this.enqueueNeighbors(index);
                return this.toWorldPosition(index);
            }
            return null;
        }

        private void seedFromOneSide() {
            for (int localY = 0; localY < this.widthY; localY++) {
                for (int localZ = 0; localZ < this.widthZ; localZ++) {
                    int index = this.indexOf(0, localY, localZ);
                    this.enqueue(index);
                }
            }
        }

        private void enqueueNeighbors(int index) {
            int localX = index % this.widthX;
            int yz = index / this.widthX;
            int localY = yz % this.widthY;
            int localZ = yz / this.widthY;

            this.tryEnqueue(localX + 1, localY, localZ);
            this.tryEnqueue(localX - 1, localY, localZ);
            this.tryEnqueue(localX, localY + 1, localZ);
            this.tryEnqueue(localX, localY - 1, localZ);
            this.tryEnqueue(localX, localY, localZ + 1);
            this.tryEnqueue(localX, localY, localZ - 1);
        }

        private void tryEnqueue(int localX, int localY, int localZ) {
            if (localX < 0 || localY < 0 || localZ < 0 || localX >= this.widthX || localY >= this.widthY || localZ >= this.widthZ) {
                return;
            }
            this.enqueue(this.indexOf(localX, localY, localZ));
        }

        private void enqueue(int index) {
            if (this.converted[index] || this.queued[index]) {
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
            return value;
        }

        private int indexOf(int localX, int localY, int localZ) {
            return localX + (localY * this.widthX) + (localZ * this.widthX * this.widthY);
        }

        @Nonnull
        private Vector3i toWorldPosition(int index) {
            int localX = index % this.widthX;
            int yz = index / this.widthX;
            int localY = yz % this.widthY;
            int localZ = yz / this.widthY;
            return new Vector3i(this.minX + localX, this.minY + localY, this.minZ + localZ);
        }
    }

    public static final class UndoSession {
        @Nonnull
        private final LinkedHashMap<String, UndoEntry> entries = new LinkedHashMap<>();

        private void record(int x, int y, int z, @Nonnull String blockId) {
            String key = x + ":" + y + ":" + z;
            this.entries.putIfAbsent(key, new UndoEntry(new Vector3i(x, y, z), blockId));
        }

        public int size() {
            return this.entries.size();
        }

        @Nonnull
        public Iterable<UndoEntry> entries() {
            return this.entries.values();
        }
    }

    public record UndoEntry(Vector3i position, String blockId) {
    }
}
