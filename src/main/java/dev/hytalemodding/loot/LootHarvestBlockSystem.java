package dev.hytalemodding.loot;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcInventoryService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class LootHarvestBlockSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private static final Random RANDOM = new Random();
    private static final String EMPTY_BLOCK_ID = "Empty";

    public LootHarvestBlockSystem() {
        super(UseBlockEvent.Pre.class);
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
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }

        Vector3i pos = event.getTargetBlock();
        World world = player.getWorld();
        if (world == null || pos == null) {
            return;
        }

        BlockType blockType = world.getBlockType(pos);
        String blockId = normalizeBlockId(blockType == null ? null : blockType.getId());
        LootTableRegistry registry = LootTableRegistry.get();
        if (!registry.isConfiguredHarvestBlock(blockId)) {
            return;
        }

        List<ItemStack> rewards = registry.rollBlock(blockId, LootTier.forActiveRun(), RANDOM);
        if (rewards.isEmpty()) {
            playerRef.sendMessage(Message.raw("This growth is not ready to harvest yet."));
            return;
        }
        if (!NpcInventoryService.executeTransaction(playerRef, List.of(), toItemAmounts(rewards))) {
            playerRef.sendMessage(Message.raw("Your inventory is full."));
            return;
        }

        world.setBlock(pos.x, pos.y, pos.z, EMPTY_BLOCK_ID);
        playerRef.sendMessage(Message.raw("Harvested crimson growth."));
    }

    @Nonnull
    private static List<NpcEconomyDefinition.ItemAmount> toItemAmounts(@Nonnull List<ItemStack> stacks) {
        ArrayList<NpcEconomyDefinition.ItemAmount> out = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getItemId() == null || stack.getItemId().isBlank() || stack.getQuantity() <= 0) {
                continue;
            }
            out.add(new NpcEconomyDefinition.ItemAmount(stack.getItemId(), stack.getQuantity()));
        }
        return List.copyOf(out);
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

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
