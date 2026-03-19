package dev.hytalemodding.loot;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class LifeEssenceChestOpenSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private static final String LIFE_ESSENCE_ITEM_ID = "Ingredient_Life_Essence";
    private static final String TARGET_BLOCK_ID = "Furniture_Village_Chest_Small";
    private static final Map<String, String> BLOCKID_TO_CONTAINERID = Map.of(TARGET_BLOCK_ID, "LifeEssenceChest");
    private static final Random RANDOM = new Random();

    private final Set<LifeEssenceContainerKey> processedChests;

    public LifeEssenceChestOpenSystem(@Nonnull Set<LifeEssenceContainerKey> processedChests) {
        super(UseBlockEvent.Pre.class);
        this.processedChests = processedChests;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event
    ) {
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player == null) return;

        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getWorldUuid() == null) return;
        UUID worldUuid = playerRef.getWorldUuid();

        Vector3i pos = event.getTargetBlock();
        LifeEssenceContainerKey key = LifeEssenceContainerKey.of(worldUuid, pos);
        if (processedChests.contains(key)) return;

        long chunkIndex = ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
        WorldChunk worldChunk = player.getWorld().getChunk(chunkIndex);
        if (worldChunk == null) return;

        try {
            Method getStateMethod = worldChunk.getClass().getMethod("getState", int.class, int.class, int.class);
            Object rawState = getStateMethod.invoke(worldChunk, pos.getX(), pos.getY(), pos.getZ());

            if (!(rawState instanceof ItemContainerBlockState containerBlock)) return;

            String rawBlockId = tryGetBlockId(rawState);
            String blockId = normalizeBlockId(rawBlockId);
            if (!BLOCKID_TO_CONTAINERID.containsKey(blockId)) return;

            if (processedChests.contains(key)) return;

            ItemContainer container = containerBlock.getItemContainer();
            if (container == null || container.getCapacity() <= 0) {
                processedChests.add(key);
                return;
            }

            int quantity = rand(3, 6);
            container.clear();
            container.setItemStackForSlot((short) 0, new ItemStack(LIFE_ESSENCE_ITEM_ID, quantity));

            try {
                Method saveMethod = rawState.getClass().getMethod("markNeedsSave");
                saveMethod.invoke(rawState);
            } catch (Exception ignored) {
                // Ignore if markNeedsSave is unavailable.
            }

            processedChests.add(key);
        } catch (Exception e) {
            System.out.println("[LifeEssenceChestOpenSystem] Failed to populate chest: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int rand(int min, int max) {
        return min + RANDOM.nextInt(max - min + 1);
    }

    private static String normalizeBlockId(@Nullable String id) {
        if (id == null) return null;
        if (id.startsWith("*")) id = id.substring(1);
        int stateIndex = id.indexOf("_State_Definitions_");
        if (stateIndex >= 0) id = id.substring(0, stateIndex);
        return id.trim();
    }

    private static String tryGetBlockId(@Nonnull Object rawState) {
        Object blockTypeObj = null;
        String[] candidates = new String[]{"getBlockType", "getType", "getBlock"};
        for (String name : candidates) {
            try {
                Method m = rawState.getClass().getMethod(name);
                blockTypeObj = m.invoke(rawState);
                if (blockTypeObj != null) break;
            } catch (Exception ignored) {
                // Keep trying fallback methods.
            }
        }

        String s = (blockTypeObj != null) ? String.valueOf(blockTypeObj) : String.valueOf(rawState);
        int idPos = s.indexOf("id=");
        if (idPos >= 0) {
            int start = idPos + 3;
            int end = s.indexOf(',', start);
            if (end < 0) end = s.indexOf('}', start);
            if (end < 0) end = s.length();
            return s.substring(start, end).trim();
        }

        return s;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
