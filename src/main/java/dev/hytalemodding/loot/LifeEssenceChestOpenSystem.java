package dev.hytalemodding.loot;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class LifeEssenceChestOpenSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private static final String LIFE_ESSENCE_ITEM_ID = "Ingredient_Life_Essence";
    private static final String TARGET_BLOCK_ID = "Furniture_Village_Chest_Small";
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

        try {
            LootChestAccess.ResolvedChest chest = LootChestAccess.resolveChest(player.getWorld(), pos);
            if (chest == null || !TARGET_BLOCK_ID.equalsIgnoreCase(chest.blockId())) return;
            if (!LootChestAccess.populate(chest, java.util.List.of(new ItemStack(LIFE_ESSENCE_ITEM_ID, rand(3, 6))))) {
                processedChests.add(key);
                return;
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

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
