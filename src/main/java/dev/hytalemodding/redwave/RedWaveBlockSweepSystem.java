package dev.hytalemodding.redwave;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class RedWaveBlockSweepSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();

        RedWaveManager.processUndoTick(worldId, world, dt);

        for (RedWaveManager.ActiveWave wave : RedWaveManager.getActiveWaves(worldId)) {
            wave.bootstrapAroundCore(world, worldId);

            int stepsThisTick = wave.takeBlocksForTick();
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

                boolean isExtraLowerLayer = pos.y >= (corePos.y - 1);
                if (!isExtraLowerLayer && pos.y < corePos.y && !isLikelyVisible(world, pos)) {
                    wave.discardGrowth(pos);
                    continue;
                }
                if (!wave.isInInnerUniformCore(pos) && !RedWaveManager.hasAdjacentWaveAnchor(world, pos)) {
                    wave.discardGrowth(pos);
                    continue;
                }

                RedWaveManager.recordOriginalBlock(worldId, corePos, pos.x, pos.y, pos.z, existing.getId());
                world.setBlock(pos.x, pos.y, pos.z, RedWaveConfig.CRIMSON_BLOCK_ID);
                wave.markConverted(pos);
            }

            if (wave.done()) {
                RedWaveManager.clearWave(worldId, corePos);
            }
        }
    }

    private static boolean isLikelyVisible(@Nonnull World world, @Nonnull Vector3i pos) {
        return isOpenOrNonFull(world.getBlockType(pos.x + 1, pos.y, pos.z))
                || isOpenOrNonFull(world.getBlockType(pos.x - 1, pos.y, pos.z))
                || isOpenOrNonFull(world.getBlockType(pos.x, pos.y + 1, pos.z))
                || isOpenOrNonFull(world.getBlockType(pos.x, pos.y - 1, pos.z))
                || isOpenOrNonFull(world.getBlockType(pos.x, pos.y, pos.z + 1))
                || isOpenOrNonFull(world.getBlockType(pos.x, pos.y, pos.z - 1));
    }

    private static boolean isOpenOrNonFull(BlockType blockType) {
        if (blockType == null || blockType == BlockType.EMPTY || blockType.getMaterial() != BlockMaterial.Solid) {
            return true;
        }
        return blockType.getOpacity() != Opacity.Solid
                || (blockType.getDrawType() != DrawType.Cube && blockType.getDrawType() != DrawType.GizmoCube);
    }
}