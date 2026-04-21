package dev.hytalemodding.loot;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class LifeEssenceChestOpenSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
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
        if (pos == null) return;
        if (LootChestRuntime.get().getQuestChest(worldUuid, pos) != null) {
            return;
        }

        LifeEssenceContainerKey key = LifeEssenceContainerKey.of(worldUuid, pos);
        if (processedChests.contains(key)) return;

        try {
            LootChestAccess.ResolvedChest chest = LootChestAccess.resolveChest(player.getWorld(), pos);
            if (chest == null) return;

            java.util.List<ItemStack> items = LootTableRegistry.get().rollChest(
                    chest.blockId(),
                    LootTier.forActiveRun(),
                    RANDOM
            );
            if (items.isEmpty()) {
                return;
            }

            if (!LootChestAccess.populate(chest, items)) {
                processedChests.add(key);
                return;
            }

            Ref<ChunkStore> blockRef = chest.chunk().getBlockComponentEntity(pos.getX(), pos.getY(), pos.getZ());
            if (blockRef != null && blockRef.isValid()) {
                Store<ChunkStore> chunkComponentStore = player.getWorld().getChunkStore().getStore();
                chunkComponentStore.putComponent(blockRef, ItemContainerBlock.getComponentType(), chest.containerBlock());
            }

            processedChests.add(key);
        } catch (Exception e) {
            System.out.println("[LootChestOpenSystem] Exception populating chest: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
