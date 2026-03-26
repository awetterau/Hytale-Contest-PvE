package dev.hytalemodding.loot;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
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

        try {
            Method getStateMethod = worldChunk.getClass().getMethod("getState", int.class, int.class, int.class);
            Object rawState = getStateMethod.invoke(worldChunk, pos.getX(), pos.getY(), pos.getZ());
            if (!(rawState instanceof ItemContainerBlockState containerBlock)) {
                return null;
            }
            return new ResolvedChest(rawState, containerBlock, normalizeBlockId(tryGetBlockId(rawState)));
        } catch (Exception ignored) {
            return null;
        }
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
        markNeedsSave(chest.rawState());
        return true;
    }

    public static void markNeedsSave(@Nonnull Object rawState) {
        try {
            Method saveMethod = rawState.getClass().getMethod("markNeedsSave");
            saveMethod.invoke(rawState);
        } catch (Exception ignored) {
            // Ignore if markNeedsSave is unavailable.
        }
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

    @Nonnull
    private static String tryGetBlockId(@Nonnull Object rawState) {
        Object blockTypeObj = null;
        String[] candidates = new String[]{"getBlockType", "getType", "getBlock"};
        for (String name : candidates) {
            try {
                Method m = rawState.getClass().getMethod(name);
                blockTypeObj = m.invoke(rawState);
                if (blockTypeObj != null) {
                    break;
                }
            } catch (Exception ignored) {
                // Try next fallback.
            }
        }

        String raw = blockTypeObj != null ? String.valueOf(blockTypeObj) : String.valueOf(rawState);
        int idPos = raw.indexOf("id=");
        if (idPos >= 0) {
            int start = idPos + 3;
            int end = raw.indexOf(',', start);
            if (end < 0) {
                end = raw.indexOf('}', start);
            }
            if (end < 0) {
                end = raw.length();
            }
            return raw.substring(start, end).trim();
        }
        return raw;
    }

    public record ResolvedChest(
            @Nonnull Object rawState,
            @Nonnull ItemContainerBlockState containerBlock,
            @Nullable String blockId
    ) {
    }
}
