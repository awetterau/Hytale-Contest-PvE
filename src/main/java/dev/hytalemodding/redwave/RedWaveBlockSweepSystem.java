package dev.hytalemodding.redwave;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameRunDirectorSystem;
import dev.hytalemodding.state.run.RunEnvironmentPainter;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public class RedWaveBlockSweepSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();

        RedWaveManager.processUndoTick(worldId, world, dt);

        for (RedWaveManager.ActiveWave wave : RedWaveManager.getActiveWaves(worldId)) {
            wave.bootstrapAroundCore(world, worldId);

            int stepsThisTick = wave.takeBlocksForTick(dt);
            Vector3i corePos = wave.corePos();
            int convertedThisTick = 0;

            for (int i = 0; i < stepsThisTick && !wave.done(); i++) {
                Vector3i pos = wave.consumeGrowthStep();
                if (pos == null) {
                    continue;
                }

                BlockType existing = world.getBlockType(pos.x, pos.y, pos.z);
                if (!RedWaveManager.shouldConvertBlock(existing)) {
                    ReplacementDecision replacement = resolveSkippedReplacement(existing);
                    if (replacement != null) {
                        RedWaveManager.recordOriginalBlock(worldId, corePos, pos.x, pos.y, pos.z, existing.getId());
                        if (replacement.copyRotation()) {
                            int rotationIndex = world.getBlockRotationIndex(pos.x, pos.y, pos.z);
                            placeRotatedBlock(world, pos.x, pos.y, pos.z, replacement.blockId(), rotationIndex);
                        } else {
                            world.setBlock(pos.x, pos.y, pos.z, replacement.blockId());
                        }
                        RunEnvironmentPainter.paintColumnForRunBlock(world, pos.x, pos.y, pos.z);
                        wave.markConverted(pos);
                        wave.onConverted(pos);
                        convertedThisTick++;
                        continue;
                    }
                    wave.discardGrowth(pos);
                    continue;
                }
                if (!RedWaveManager.isExposedToAirOrEdge(world, pos)) {
                    wave.discardGrowth(pos);
                    continue;
                }
                if (!RedWaveManager.hasAdjacentWaveAnchor(world, pos)) {
                    wave.discardGrowth(pos);
                    continue;
                }
                if (!wave.shouldConvertByNoise(pos)) {
                    wave.onConverted(pos);
                    wave.discardGrowth(pos);
                    continue;
                }
                RedWaveManager.recordOriginalBlock(worldId, corePos, pos.x, pos.y, pos.z, existing.getId());
                world.setBlock(pos.x, pos.y, pos.z, RedWaveConfig.CRIMSON_LAYER_BLOCK_ID);
                RunEnvironmentPainter.paintColumnForRunBlock(world, pos.x, pos.y, pos.z);
                wave.markConverted(pos);
                tryPlaceCrimsonDecor(world, pos, corePos, wave.radiusBlocks());
                wave.onConverted(pos);
                convertedThisTick++;
            }

            if (wave.shouldEmitRadiusSample()) {
                if (GameRunDirectorSystem.isSeededGrowSeedCore(worldId, corePos)) {
                    continue;
                }
                String coreType = resolveCoreType(world, corePos);
                if ("weak".equalsIgnoreCase(coreType)) {
                    continue;
                }
                float speedBlocksPerSecond = dt <= 0.0001f ? convertedThisTick : (convertedThisTick / dt);
                if (RedWaveConfig.ENABLE_CONSOLE_LOGS) {
                    System.out.println(
                            "[RedWave] core="
                                    + corePos.x + "," + corePos.y + "," + corePos.z
                                    + " type=" + coreType
                                    + " avgRadius=" + String.format("%.2f", wave.averageConvertedRadius())
                                    + " areaRadius=" + String.format("%.2f", wave.areaEquivalentRadius())
                                    + " speed=" + String.format("%.2f", speedBlocksPerSecond) + " blocks/s"
                    );
                }
                sendRunWorldProgressMessage(
                        worldId,
                        "[InfectionProgress] Core " + coreType
                                + " | radius(avg)=" + String.format("%.2f", wave.averageConvertedRadius())
                                + " | radius(area)=" + String.format("%.2f", wave.areaEquivalentRadius())
                                + " | speed=" + String.format("%.2f", speedBlocksPerSecond) + " blocks/s"
                );
            }

            if (wave.done()) {
                GameRunDirectorSystem.clearSeededGrowSeedCore(worldId, corePos);
                RedWaveManager.clearWave(worldId, corePos);
            }
        }
    }

    private static ReplacementDecision resolveSkippedReplacement(BlockType blockType) {
        if (blockType == null || blockType.getId() == null) {
            return null;
        }
        String id = blockType.getId();
        String lowerId = id.toLowerCase();
        if ("empty".equals(lowerId)) {
            return null;
        }
        if (lowerId.contains("crimson")) {
            return null;
        }
        if (lowerId.contains("water")) {
            return ReplacementDecision.withoutRotation(RedWaveConfig.CRIMSON_FLUID_BLOCK_ID);
        }
        if (hasTag(blockType, "rubble")) {
            return ReplacementDecision.withoutRotation("Empty");
        }
        if (lowerId.contains("leaves")) {
            return ReplacementDecision.withRotation(RedWaveConfig.CRIMSON_LEAVES_BLOCK_ID);
        }
        for (String keyword : RedWaveConfig.NON_CONVERTIBLE_ID_KEYWORDS) {
            if (lowerId.contains(keyword)) {
                return ReplacementDecision.withoutRotation("Empty");
            }
        }
        if (lowerId.contains("half")) {
            return ReplacementDecision.withRotation(RedWaveConfig.CRIMSON_HALF_BLOCK_ID);
        }
        if (lowerId.contains("beam")) {
            return ReplacementDecision.withoutRotation(RedWaveConfig.CRIMSON_BEAM_BLOCK_ID);
        }
        if (lowerId.contains("pillar")) {
            return ReplacementDecision.withoutRotation(RedWaveConfig.CRIMSON_PILLAR_BLOCK_ID);
        }
        if (lowerId.contains("stair")) {
            return ReplacementDecision.withRotation(RedWaveConfig.CRIMSON_STAIRS_BLOCK_ID);
        }
        if (lowerId.contains("trunk")) {
            return ReplacementDecision.withoutRotation(RedWaveConfig.CRIMSON_TRUNK_FULL_BLOCK_ID);
        }
        return ReplacementDecision.withoutRotation(RedWaveConfig.CRIMSON_PROP_BLOCK_ID);
    }

    private static void placeRotatedBlock(
            @Nonnull World world,
            int x,
            int y,
            int z,
            @Nonnull String blockId,
            int rotationIndex
    ) {
        RotationTuple tuple = RotationTuple.get(rotationIndex);
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk worldChunk = world.getChunk(chunkIndex);
        if (worldChunk != null) {
            boolean placed = worldChunk.placeBlock(x, y, z, blockId, tuple, 0, true);
            if (placed) {
                return;
            }
        }
        world.setBlock(x, y, z, blockId, rotationIndex);
    }

    private static boolean hasTag(@Nonnull BlockType blockType, @Nonnull String tag) {
        Object tags = invokeOptionalZeroArg(blockType, "getTags");
        if (tags == null) {
            tags = invokeOptionalZeroArg(blockType, "tags");
        }
        return containsTag(tags, tag);
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

    private static boolean containsTag(@Nullable Object tags, @Nonnull String expectedTag) {
        if (tags == null) {
            return false;
        }
        if (tags instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry != null && expectedTag.equalsIgnoreCase(String.valueOf(entry))) {
                    return true;
                }
            }
            return false;
        }
        if (tags instanceof String tagText) {
            if (expectedTag.equalsIgnoreCase(tagText)) {
                return true;
            }
            for (String token : tagText.split("[,;|\\s]+")) {
                if (expectedTag.equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class ReplacementDecision {
        @Nonnull
        private final String blockId;
        private final boolean copyRotation;

        private ReplacementDecision(@Nonnull String blockId, boolean copyRotation) {
            this.blockId = blockId;
            this.copyRotation = copyRotation;
        }

        @Nonnull
        private String blockId() {
            return this.blockId;
        }

        private boolean copyRotation() {
            return this.copyRotation;
        }

        @Nonnull
        private static ReplacementDecision withRotation(@Nonnull String blockId) {
            return new ReplacementDecision(blockId, true);
        }

        @Nonnull
        private static ReplacementDecision withoutRotation(@Nonnull String blockId) {
            return new ReplacementDecision(blockId, false);
        }
    }

    private static void tryPlaceCrimsonDecor(@Nonnull World world, @Nonnull Vector3i origin, @Nonnull Vector3i corePos, int radiusBlocks) {
        if (!isInsideDecorRadius(origin, corePos, radiusBlocks)) {
            return;
        }
        if (!rollDecor(0.12f)) {
            return;
        }

        Vector3i top = new Vector3i(origin.x, origin.y + 1, origin.z);
        boolean canPlaceAbove = isEmpty(world, top)
                && RedWaveManager.isFullCubeBlock(world.getBlockType(origin.x, origin.y, origin.z));
        if (canPlaceAbove) {
            float roll = ThreadLocalRandom.current().nextFloat();
            if (roll < scaledDecorChance(0.030f)) {
                world.setBlock(top.x, top.y, top.z, "Crimson_Mushroom_Poison");
                RunEnvironmentPainter.paintColumnForRunBlock(world, top.x, top.y, top.z);
                return;
            }
            if (roll < scaledDecorChance(0.050f)) {
                world.setBlock(top.x, top.y, top.z, "Crimson_Mushroom_Fox");
                RunEnvironmentPainter.paintColumnForRunBlock(world, top.x, top.y, top.z);
                return;
            }
            if (roll < scaledDecorChance(0.065f)) {
                world.setBlock(top.x, top.y, top.z, "Plant_Crop_Mushroom_Glowing_Orange");
                RunEnvironmentPainter.paintColumnForRunBlock(world, top.x, top.y, top.z);
                return;
            }
        }

        maybePlaceCrimsonMushroomWall(world, origin);
        maybePlaceCrimsonRoots(world, origin);
    }

    private static void maybePlaceCrimsonMushroomWall(@Nonnull World world, @Nonnull Vector3i origin) {
        if (!rollDecor(1.0f)) {
            return;
        }

        Vector3i[] directions = new Vector3i[]{
                new Vector3i(1, 0, 0),
                new Vector3i(-1, 0, 0),
                new Vector3i(0, 0, 1),
                new Vector3i(0, 0, -1)
        };
        Vector3i front = directions[ThreadLocalRandom.current().nextInt(directions.length)];
        Vector3i placePos = new Vector3i(origin.x + front.x, origin.y, origin.z + front.z);
        if (!isEmpty(world, placePos)) {
            return;
        }

        Vector3i wallPos = new Vector3i(placePos.x - front.x, placePos.y, placePos.z - front.z);
        if (!RedWaveManager.isFullCubeBlock(world.getBlockType(wallPos.x, wallPos.y, wallPos.z))) {
            return;
        }

        Vector3i forward1 = new Vector3i(placePos.x + front.x, placePos.y, placePos.z + front.z);
        Vector3i forward2 = new Vector3i(placePos.x + (2 * front.x), placePos.y, placePos.z + (2 * front.z));
        if (!isEmpty(world, forward1) || !isEmpty(world, forward2)) {
            return;
        }

        Vector3i attachedSide = new Vector3i(-front.x, 0, -front.z);
        Vector3i facing = new Vector3i(-attachedSide.x, 0, -attachedSide.z);
        placeRotatedCrimsonMushroom(world, placePos, facing);
    }

    private static void maybePlaceCrimsonRoots(@Nonnull World world, @Nonnull Vector3i origin) {
        if (!rollDecor(2.0f)) {
            return;
        }

        Vector3i placePos = new Vector3i(origin.x, origin.y - 1, origin.z);
        if (!isEmpty(world, placePos)) {
            return;
        }
        if (!RedWaveManager.isFullCubeBlock(world.getBlockType(origin.x, origin.y, origin.z))) {
            return;
        }
        Vector3i below1 = new Vector3i(origin.x, origin.y - 1, origin.z);
        Vector3i below2 = new Vector3i(origin.x, origin.y - 2, origin.z);
        Vector3i below3 = new Vector3i(origin.x, origin.y - 3, origin.z);
        if (!isEmpty(world, below1) || !isEmpty(world, below2) || !isEmpty(world, below3)) {
            return;
        }

        world.setBlock(placePos.x, placePos.y, placePos.z, "Crimson_Roots");
        RunEnvironmentPainter.paintColumnForRunBlock(world, placePos.x, placePos.y, placePos.z);
    }

    private static boolean rollDecor(float baseChance) {
        return ThreadLocalRandom.current().nextFloat() < scaledDecorChance(baseChance);
    }

    private static float scaledDecorChance(float baseChance) {
        float multiplier = Math.max(0.0f, RedWaveConfig.DECORATION_PROBABILITY_MULTIPLIER);
        return Math.max(0.0f, Math.min(1.0f, baseChance * multiplier));
    }

    private static boolean isEmpty(@Nonnull World world, @Nonnull Vector3i pos) {
        BlockType blockType = world.getBlockType(pos.x, pos.y, pos.z);
        return blockType == null || blockType == BlockType.EMPTY;
    }

    private static int rotationIndexForFacing(@Nonnull Vector3i direction) {
        Rotation yaw = yawForFacing(direction);
        return RotationTuple.index(yaw, Rotation.None, Rotation.None);
    }

    private static void placeRotatedCrimsonMushroom(@Nonnull World world, @Nonnull Vector3i placePos, @Nonnull Vector3i facing) {
        Rotation yaw = yawForFacing(facing);
        RotationTuple rotation = RotationTuple.of(yaw, Rotation.None, Rotation.None);
        long chunkIndex = ChunkUtil.indexChunkFromBlock(placePos.x, placePos.z);
        WorldChunk worldChunk = world.getChunk(chunkIndex);
        if (worldChunk != null) {
            boolean placed = worldChunk.placeBlock(placePos.x, placePos.y, placePos.z, "Crimson_Mushroom", rotation, 0, true);
            if (placed) {
                RunEnvironmentPainter.paintColumnForRunBlock(world, placePos.x, placePos.y, placePos.z);
                return;
            }
        }
        world.setBlock(placePos.x, placePos.y, placePos.z, "Crimson_Mushroom", rotationIndexForFacing(facing));
        RunEnvironmentPainter.paintColumnForRunBlock(world, placePos.x, placePos.y, placePos.z);
    }

    @Nonnull
    private static Rotation yawForFacing(@Nonnull Vector3i direction) {
        if (direction.z > 0) {
            return Rotation.None;
        }
        if (direction.x < 0) {
            return Rotation.TwoSeventy;
        }
        if (direction.z < 0) {
            return Rotation.OneEighty;
        }
        if (direction.x > 0) {
            return Rotation.Ninety;
        }
        return Rotation.None;
    }

    private static boolean isInsideDecorRadius(@Nonnull Vector3i pos, @Nonnull Vector3i corePos, int radiusBlocks) {
        int clampedRadius = Math.max(1, radiusBlocks);
        float maxDecorRadius = clampedRadius * 0.75f;
        int dx = pos.x - corePos.x;
        int dz = pos.z - corePos.z;
        double distance = Math.sqrt((dx * dx) + (dz * dz));
        return distance <= maxDecorRadius;
    }

    @Nonnull
    private static String resolveCoreType(@Nonnull World world, @Nonnull Vector3i corePos) {
        BlockType coreType = world.getBlockType(corePos.x, corePos.y, corePos.z);
        if (coreType == null || coreType.getId() == null) {
            return "unknown";
        }
        if (RedWaveConfig.CORE_BLOCK_ID.equals(coreType.getId())) {
            return "core";
        }
        if ("Crimson_Core_Weak".equals(coreType.getId())) {
            return "weak";
        }
        return coreType.getId();
    }

    private static void sendRunWorldProgressMessage(@Nonnull UUID worldId, @Nonnull String message) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        if (!config.isStatusMessagesEnabled() || !config.isCoreRadiusChatMessagesEnabled()) {
            return;
        }
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getWorldUuid() == null || !worldId.equals(playerRef.getWorldUuid())) {
                continue;
            }
            playerRef.sendMessage(com.hypixel.hytale.server.core.Message.raw(message));
        }
    }

}