package dev.hytalemodding.redwave;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
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

            for (int i = 0; i < stepsThisTick && !wave.done(); i++) {
                Vector3i pos = wave.consumeGrowthStep();
                if (pos == null) {
                    continue;
                }

                BlockType existing = world.getBlockType(pos.x, pos.y, pos.z);
                if (!RedWaveManager.shouldConvertBlock(existing)) {
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
                wave.markConverted(pos);
                tryPlaceCrimsonDecor(world, pos, corePos, wave.radiusBlocks());
                wave.onConverted(pos);
            }

            if (wave.shouldEmitRadiusSample()) {
                System.out.println(
                        "[RedWave] core="
                                + corePos.x + "," + corePos.y + "," + corePos.z
                                + " avgRadius=" + String.format("%.2f", wave.averageConvertedRadius())
                                + " areaRadius=" + String.format("%.2f", wave.areaEquivalentRadius())
                );
            }

            if (wave.done()) {
                RedWaveManager.clearWave(worldId, corePos);
            }
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
                return;
            }
            if (roll < scaledDecorChance(0.050f)) {
                world.setBlock(top.x, top.y, top.z, "Crimson_Mushroom_Fox");
                return;
            }
            if (roll < scaledDecorChance(0.065f)) {
                world.setBlock(top.x, top.y, top.z, "Plant_Crop_Mushroom_Glowing_Orange");
                return;
            }
        }

        maybePlaceCrimsonMushroomWall(world, origin);
        maybePlaceCrimsonRoots(world, origin);
    }

    private static void maybePlaceCrimsonMushroomWall(@Nonnull World world, @Nonnull Vector3i origin) {
        if (!rollDecor(1.3f)) {
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
                return;
            }
        }
        world.setBlock(placePos.x, placePos.y, placePos.z, "Crimson_Mushroom", rotationIndexForFacing(facing));
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

}