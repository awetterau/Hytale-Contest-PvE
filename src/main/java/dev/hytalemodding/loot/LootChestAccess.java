package dev.hytalemodding.loot;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public final class LootChestAccess {
    private LootChestAccess() {
    }

    @Nullable
    public static ResolvedChest resolveChest(@Nonnull World world, @Nonnull Vector3i pos) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
        WorldChunk worldChunk = world.getChunk(chunkIndex);
        if (worldChunk == null) {
            return null;
        }

        Holder<ChunkStore> holder = worldChunk.getBlockComponentHolder(pos.getX(), pos.getY(), pos.getZ());
        if (holder == null) {
            return null;
        }
        ItemContainerBlock containerBlock = holder.getComponent(ItemContainerBlock.getComponentType());
        if (containerBlock == null) {
            return null;
        }
        BlockType blockType = world.getBlockType(pos);
        String blockId = blockType == null ? null : normalizeBlockId(blockType.getId());
        return new ResolvedChest(worldChunk, holder, containerBlock, blockId);
    }

    public static boolean populate(@Nonnull ResolvedChest chest, @Nonnull List<ItemStack> items) {
        ItemContainer container = chest.containerBlock().getItemContainer();
        if (container == null || container.getCapacity() <= 0) {
            return false;
        }

        container.clear();
        short slot = 0;
        for (ItemStack item : items) {
            if (item == null || slot >= container.getCapacity()) {
                continue;
            }
            container.setItemStackForSlot(slot, item);
            slot++;
        }
        chest.chunk().markNeedsSaving();
        return true;
    }

    @Nullable
    private static String normalizeBlockId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        if (id.startsWith("*")) {
            id = id.substring(1);
        }
        int stateIndex = id.indexOf("_State_Definitions_");
        if (stateIndex >= 0) {
            id = id.substring(0, stateIndex);
        }
        return id.trim();
    }

    public record ResolvedChest(
            @Nonnull WorldChunk chunk,
            @Nonnull Holder<ChunkStore> holder,
            @Nonnull ItemContainerBlock containerBlock,
            @Nullable String blockId
    ) {
    }
}
