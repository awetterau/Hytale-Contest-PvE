package dev.hytalemodding.redwave;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3i;
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

}