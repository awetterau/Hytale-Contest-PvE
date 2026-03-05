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

        RedWaveManager.ActiveWave wave = RedWaveManager.getActiveWave(worldId);
        if (wave == null) {
            return;
        }

        int stepsThisTick = wave.takeBlocksForTick();
        for (int i = 0; i < stepsThisTick && !wave.done(); i++) {
            Vector3i pos = wave.consumeGrowthStep();
            if (pos == null) {
                break;
            }
            BlockType existing = world.getBlockType(pos.x, pos.y, pos.z);
            if (!RedWaveManager.shouldConvertBlock(existing)) {
                continue;
            }
            RedWaveManager.recordOriginalBlock(worldId, pos.x, pos.y, pos.z, existing.getId());
            world.setBlock(pos.x, pos.y, pos.z, RedWaveManager.TARGET_BLOCK_ID);
        }

        if (wave.done()) {
            RedWaveManager.clearWave(worldId);
        }
    }
}
