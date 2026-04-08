package dev.hytalemodding.blob;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameSessionManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrangeBlobBlockManager {
    public static final String BLOCK_ID = "Orange_Blob_Block";
    public static final String ACTIVE_BLOCK_ID = "Orange_Blob_Block_Active";
    public static final String RUNE_BLOCK_ID = "Orange_Blob_Rune";
    public static final String ACTIVE_RUNE_BLOCK_ID = "Orange_Blob_Rune_Active";
    private static final String EMPTY_BLOCK_ID = "Empty";
    private static final int MOVE_DISTANCE_BLOCKS = 5;
    private static final long MOVE_DURATION_MS = 1520L;
    private static final long USE_COOLDOWN_MS = 650L;
    private static final Vector3i[] ADJACENT_OFFSETS = new Vector3i[]{
            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0),
            new Vector3i(0, 1, 0),
            new Vector3i(0, -1, 0),
            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1)
    };
    private static final ConcurrentHashMap<UUID, Long> LAST_USE_BY_PLAYER = new ConcurrentHashMap<>();

    private OrangeBlobBlockManager() {
    }

    public static boolean tryActivate(
            @Nullable PlayerRef playerRef,
            @Nullable World world,
            @Nullable Vector3i target
    ) {
        if (playerRef == null || world == null || target == null) {
            return false;
        }
        if (isOnCooldown(playerRef.getUuid())) {
            return false;
        }

        GameSessionManager.ActiveSessionSnapshot activeSession = GameSessionManager.get().getActiveSession();
        UUID worldId = world.getWorldConfig().getUuid();
        if (activeSession == null || activeSession.runWorldUuid() == null || !activeSession.runWorldUuid().equals(worldId)) {
            playerRef.sendMessage(Message.raw("The extraction rune only works during an active run."));
            return true;
        }

        OrangeBlobBlockRuntime.Session activeRuneSession = OrangeBlobBlockRuntime.findSessionByRunePosition(worldId, target);
        if (activeRuneSession != null) {
            return tryLaunchReadyExtraction(playerRef, activeRuneSession);
        }

        if (!isRuneBlock(world.getBlockType(target))) {
            return false;
        }

        OrangeBlobExtractionConfigManager.ExtractionConfigState config =
                OrangeBlobExtractionConfigManager.get().getState(world.getName());
        OrangeBlobBlockRuntime.ClusterBlock runeBlock = new OrangeBlobBlockRuntime.ClusterBlock(
                target.x,
                target.y,
                target.z,
                config.idleRuneBlockId(),
                readRotation(world, target.x, target.y, target.z)
        );

        Vector3i anchorBlob = findAnchorBlobNearRune(world, target);
        if (anchorBlob == null) {
            playerRef.sendMessage(Message.raw("This rune is not linked to an extraction island below it."));
            return true;
        }

        List<OrangeBlobBlockRuntime.ClusterBlock> cluster = findConnectedCluster(world, anchorBlob);
        if (cluster.isEmpty()) {
            playerRef.sendMessage(Message.raw("This extraction island has no connected blob terrain."));
            return true;
        }

        String failure = validateClusterCanMove(world, cluster, runeBlock);
        if (failure != null) {
            playerRef.sendMessage(Message.raw(failure));
            return true;
        }

        OrangeBlobBlockRuntime.ClusterBlock centerBlock = findCenterBlock(cluster);
        List<OrangeBlobBlockRuntime.SupportBlock> supportBlocks = buildSupportBlocks(world, cluster, centerBlock, runeBlock, config);

        long now = System.currentTimeMillis();
        OrangeBlobBlockRuntime.Session session = OrangeBlobBlockRuntime.createSession(
                worldId,
                playerRef.getUuid(),
                cluster,
                centerBlock,
                runeBlock,
                now,
                MOVE_DISTANCE_BLOCKS,
                MOVE_DURATION_MS,
                config,
                supportBlocks
        );
        OrangeBlobBlockRuntime.markClusterActive(session);
        placeSupportBlocks(world, supportBlocks);
        session.supportBlocksPlaced(!supportBlocks.isEmpty());
        clearClusterBlocks(world, cluster);
        world.setBlock(runeBlock.x(), runeBlock.y(), runeBlock.z(), EMPTY_BLOCK_ID);
        OrangeBlobBlockRuntime.addSession(session);
        playerRef.sendMessage(Message.raw("Extraction rune awakened. Hold the island and defend the rune."));
        return true;
    }

    public static boolean tryLaunchReadyExtraction(
            @Nullable PlayerRef playerRef,
            @Nullable OrangeBlobBlockRuntime.Session session
    ) {
        if (playerRef == null || session == null) {
            return false;
        }
        if (!session.worldId().equals(playerRef.getWorldUuid())) {
            return false;
        }
        if (session.phase() != OrangeBlobBlockRuntime.Phase.HOLDING_DOWN) {
            playerRef.sendMessage(Message.raw("The extraction rune is already returning."));
            return true;
        }
        if (!session.extractionReady()) {
            playerRef.sendMessage(Message.raw("The extraction rune is still stabilizing."));
            return true;
        }
        if (session.extractionDispatchStarted()) {
            playerRef.sendMessage(Message.raw("Extraction is already in progress."));
            return true;
        }
        session.launchRequested(true);
        playerRef.sendMessage(Message.raw("The extraction rune surges. Extraction is starting."));
        return true;
    }

    public static boolean tryLaunchReadyExtractionFromRuneProxy(
            @Nullable PlayerRef playerRef,
            @Nullable UUID worldId,
            @Nullable Ref<EntityStore> targetRef
    ) {
        if (playerRef == null || worldId == null || targetRef == null || !targetRef.isValid()) {
            return false;
        }
        return tryLaunchReadyExtraction(playerRef, OrangeBlobBlockRuntime.findSessionByRuneProxyRef(worldId, targetRef));
    }

    public static void clearRuntimeForWorld(@Nullable UUID worldId) {
        if (worldId == null) {
            return;
        }
        OrangeBlobBlockRuntime.clearWorld(worldId);
    }

    public static void createPrototypeAt(@Nonnull World world, @Nonnull Vector3i center) {
        int baseY = center.y;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                    continue;
                }
                world.setBlock(center.x + dx, baseY, center.z + dz, BLOCK_ID);
            }
        }

        world.setBlock(center.x + 3, baseY - 1, center.z, BLOCK_ID);
        world.setBlock(center.x + 4, baseY - 2, center.z, BLOCK_ID);
        world.setBlock(center.x - 3, baseY - 1, center.z, BLOCK_ID);
        world.setBlock(center.x - 4, baseY - 2, center.z, BLOCK_ID);
        world.setBlock(center.x, baseY - 1, center.z + 3, BLOCK_ID);
        world.setBlock(center.x, baseY - 2, center.z + 4, BLOCK_ID);
        world.setBlock(center.x, baseY - 1, center.z - 3, BLOCK_ID);
        world.setBlock(center.x, baseY - 2, center.z - 4, BLOCK_ID);

        world.setBlock(center.x, baseY + 1, center.z, RUNE_BLOCK_ID);
    }

    @Nullable
    private static Vector3i findAnchorBlobNearRune(@Nonnull World world, @Nonnull Vector3i runePos) {
        for (int dy = 1; dy <= 2; dy++) {
            Vector3i candidate = new Vector3i(runePos.x, runePos.y - dy, runePos.z);
            if (isBlobBlock(world.getBlockType(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    @Nonnull
    private static List<OrangeBlobBlockRuntime.SupportBlock> buildSupportBlocks(
            @Nonnull World world,
            @Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> cluster,
            @Nonnull OrangeBlobBlockRuntime.ClusterBlock centerBlock,
            @Nonnull OrangeBlobBlockRuntime.ClusterBlock runeBlock,
            @Nonnull OrangeBlobExtractionConfigManager.ExtractionConfigState config
    ) {
        HashSet<String> reserved = new HashSet<>();
        for (OrangeBlobBlockRuntime.ClusterBlock block : cluster) {
            reserved.add(posKey(block.x(), block.y(), block.z()));
            reserved.add(posKey(block.x(), block.y() - MOVE_DISTANCE_BLOCKS, block.z()));
        }
        reserved.add(posKey(runeBlock.x(), runeBlock.y(), runeBlock.z()));
        reserved.add(posKey(runeBlock.x(), runeBlock.y() - MOVE_DISTANCE_BLOCKS, runeBlock.z()));

        ArrayList<OrangeBlobBlockRuntime.SupportBlock> supportBlocks = new ArrayList<>();
        if (config.traversalHelperMode() == OrangeBlobExtractionConfigManager.TraversalHelperMode.CROSS) {
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x() + 2, centerBlock.y() - 1, centerBlock.z(), config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x() + 3, centerBlock.y() - 2, centerBlock.z(), config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x() - 2, centerBlock.y() - 1, centerBlock.z(), config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x() - 3, centerBlock.y() - 2, centerBlock.z(), config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x(), centerBlock.y() - 1, centerBlock.z() + 2, config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x(), centerBlock.y() - 2, centerBlock.z() + 3, config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x(), centerBlock.y() - 1, centerBlock.z() - 2, config.traversalHelperBlockId());
            addSupportIfValid(world, reserved, supportBlocks, centerBlock.x(), centerBlock.y() - 2, centerBlock.z() - 3, config.traversalHelperBlockId());
        }

        if (config.proceduralSupportEnabled()) {
            Random random = new Random((((long) runeBlock.x()) << 32) ^ (((long) runeBlock.y()) << 16) ^ runeBlock.z());
            int attempts = Math.max(config.proceduralSupportCount() * 3, config.proceduralSupportCount());
            while (supportBlocks.size() < config.proceduralSupportCount() + 8 && attempts-- > 0) {
                int offsetX = random.nextInt(7) - 3;
                int offsetZ = random.nextInt(7) - 3;
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                int y = centerBlock.y() - 1 - random.nextInt(3);
                addSupportIfValid(world, reserved, supportBlocks, centerBlock.x() + offsetX, y, centerBlock.z() + offsetZ, config.proceduralSupportBlockId());
            }
        }

        return List.copyOf(supportBlocks);
    }

    private static void addSupportIfValid(
            @Nonnull World world,
            @Nonnull Set<String> reserved,
            @Nonnull List<OrangeBlobBlockRuntime.SupportBlock> out,
            int x,
            int y,
            int z,
            @Nonnull String blockId
    ) {
        String key = posKey(x, y, z);
        if (reserved.contains(key)) {
            return;
        }
        BlockType existing = world.getBlockType(x, y, z);
        if (!isEmpty(existing)) {
            return;
        }
        reserved.add(key);
        out.add(new OrangeBlobBlockRuntime.SupportBlock(
                x,
                y,
                z,
                blockId,
                0,
                existing == null ? EMPTY_BLOCK_ID : existing.getId(),
                readRotation(world, x, y, z)
        ));
    }

    static void placeSupportBlocks(@Nonnull World world, @Nonnull List<OrangeBlobBlockRuntime.SupportBlock> supportBlocks) {
        for (OrangeBlobBlockRuntime.SupportBlock supportBlock : supportBlocks) {
            world.setBlock(supportBlock.x(), supportBlock.y(), supportBlock.z(), supportBlock.placedBlockId());
        }
    }

    static void restoreSupportBlocks(@Nonnull World world, @Nonnull List<OrangeBlobBlockRuntime.SupportBlock> supportBlocks) {
        for (OrangeBlobBlockRuntime.SupportBlock supportBlock : supportBlocks) {
            world.setBlock(supportBlock.x(), supportBlock.y(), supportBlock.z(), supportBlock.originalBlockId());
        }
    }

    @Nonnull
    private static List<OrangeBlobBlockRuntime.ClusterBlock> findConnectedCluster(
            @Nonnull World world,
            @Nonnull Vector3i origin
    ) {
        ArrayDeque<Vector3i> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<OrangeBlobBlockRuntime.ClusterBlock> blocks = new ArrayList<>();
        queue.add(new Vector3i(origin));

        while (!queue.isEmpty()) {
            Vector3i current = queue.removeFirst();
            String key = posKey(current.x, current.y, current.z);
            if (!visited.add(key)) {
                continue;
            }

            BlockType blockType = world.getBlockType(current);
            if (!isBlobBlock(blockType)) {
                continue;
            }

            blocks.add(new OrangeBlobBlockRuntime.ClusterBlock(
                    current.x,
                    current.y,
                    current.z,
                    BLOCK_ID,
                    readRotation(world, current.x, current.y, current.z)
            ));

            for (Vector3i offset : ADJACENT_OFFSETS) {
                queue.addLast(new Vector3i(current.x + offset.x, current.y + offset.y, current.z + offset.z));
            }
        }

        return blocks;
    }

    @Nonnull
    private static OrangeBlobBlockRuntime.ClusterBlock findCenterBlock(@Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> cluster) {
        double avgX = 0.0d;
        double avgY = 0.0d;
        double avgZ = 0.0d;
        for (OrangeBlobBlockRuntime.ClusterBlock block : cluster) {
            avgX += block.x();
            avgY += block.y();
            avgZ += block.z();
        }
        avgX /= cluster.size();
        avgY /= cluster.size();
        avgZ /= cluster.size();

        OrangeBlobBlockRuntime.ClusterBlock best = cluster.get(0);
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (OrangeBlobBlockRuntime.ClusterBlock block : cluster) {
            double dx = block.x() - avgX;
            double dy = block.y() - avgY;
            double dz = block.z() - avgZ;
            double distSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = block;
            }
        }
        return best;
    }

    @Nullable
    private static String validateClusterCanMove(
            @Nonnull World world,
            @Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> cluster,
            @Nonnull OrangeBlobBlockRuntime.ClusterBlock runeBlock
    ) {
        UUID worldId = world.getWorldConfig().getUuid();
        Set<String> clusterKeys = new HashSet<>();

        for (OrangeBlobBlockRuntime.ClusterBlock block : cluster) {
            String key = posKey(block.x(), block.y(), block.z());
            clusterKeys.add(key);
            if (OrangeBlobBlockRuntime.isPositionActive(worldId, key)) {
                return "That extraction island is already active.";
            }
        }

        String runeKey = posKey(runeBlock.x(), runeBlock.y(), runeBlock.z());
        if (OrangeBlobBlockRuntime.isPositionActive(worldId, runeKey)) {
            return "That extraction rune is already active.";
        }

        for (OrangeBlobBlockRuntime.ClusterBlock block : cluster) {
            int targetY = block.y() - MOVE_DISTANCE_BLOCKS;
            String destinationKey = posKey(block.x(), targetY, block.z());
            if (clusterKeys.contains(destinationKey)) {
                continue;
            }
            BlockType destinationType = world.getBlockType(block.x(), targetY, block.z());
            if (!isEmpty(destinationType)) {
                return "The extraction island needs 5 empty blocks below it.";
            }
        }

        String runeDestinationKey = posKey(runeBlock.x(), runeBlock.y() - MOVE_DISTANCE_BLOCKS, runeBlock.z());
        if (!clusterKeys.contains(runeDestinationKey)) {
            BlockType runeDestinationType = world.getBlockType(runeBlock.x(), runeBlock.y() - MOVE_DISTANCE_BLOCKS, runeBlock.z());
            if (!isEmpty(runeDestinationType)) {
                return "The rune needs empty space below it so it can descend with the island.";
            }
        }

        return null;
    }

    private static void clearClusterBlocks(@Nonnull World world, @Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> cluster) {
        for (OrangeBlobBlockRuntime.ClusterBlock block : cluster) {
            world.setBlock(block.x(), block.y(), block.z(), EMPTY_BLOCK_ID);
        }
    }

    private static boolean isOnCooldown(@Nonnull UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_USE_BY_PLAYER.put(playerId, now);
        return last != null && (now - last) < USE_COOLDOWN_MS;
    }

    static boolean isBlobBlock(@Nullable BlockType blockType) {
        return blockType != null && BLOCK_ID.equals(blockType.getId());
    }

    static boolean isRuneBlock(@Nullable BlockType blockType) {
        return blockType != null && RUNE_BLOCK_ID.equals(blockType.getId());
    }

    static boolean isEmpty(@Nullable BlockType blockType) {
        return blockType == null || blockType == BlockType.EMPTY || EMPTY_BLOCK_ID.equals(blockType.getId());
    }

    static int readRotation(@Nonnull World world, int x, int y, int z) {
        try {
            return world.getBlockRotationIndex(x, y, z);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Nonnull
    static String posKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
