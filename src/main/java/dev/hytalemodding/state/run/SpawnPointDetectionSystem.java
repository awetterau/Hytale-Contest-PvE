package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class SpawnPointDetectionSystem extends RefSystem<ChunkStore> {
    private static final ComponentType<ChunkStore, BlockModule.BlockStateInfo> BLOCK_STATE_INFO = BlockModule.BlockStateInfo.getComponentType();
    private static final String SPAWN_POINT_BLOCK_ID = "SpawnPoint_Block";

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        syncRegistry(ref, commandBuffer, true);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        syncRegistry(ref, commandBuffer, false);
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return Query.and(BLOCK_STATE_INFO);
    }

    private void syncRegistry(@Nonnull Ref<ChunkStore> ref, @Nonnull CommandBuffer<ChunkStore> commandBuffer, boolean adding) {
        BlockModule.BlockStateInfo blockStateInfo = commandBuffer.getComponent(ref, BLOCK_STATE_INFO);
        if (blockStateInfo == null) {
            return;
        }

        WorldChunk chunk = commandBuffer.getComponent(blockStateInfo.getChunkRef(), WorldChunk.getComponentType());
        if (chunk == null) {
            return;
        }

        int localX = ChunkUtil.xFromBlockInColumn(blockStateInfo.getIndex());
        int localY = ChunkUtil.yFromBlockInColumn(blockStateInfo.getIndex());
        int localZ = ChunkUtil.zFromBlockInColumn(blockStateInfo.getIndex());

        int worldX = (chunk.getX() * 32) + localX;
        int worldZ = (chunk.getZ() * 32) + localZ;
        Vector3i pos = new Vector3i(worldX, localY, worldZ);

        World world = chunk.getWorld();
        if (world == null || world.getWorldConfig() == null || world.getWorldConfig().getUuid() == null) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();

        if (!adding) {
            SpawnPointRegistry.unregister(worldId, pos);
            return;
        }

        if (chunk.getBlockComponentHolder(localX, localY, localZ) == null) {
            return;
        }

        BlockType type = world.getBlockType(worldX, localY, worldZ);
        if (type == null || !SPAWN_POINT_BLOCK_ID.equalsIgnoreCase(type.getId())) {
            return;
        }

        SpawnPointRegistry.register(worldId, pos);
    }
}