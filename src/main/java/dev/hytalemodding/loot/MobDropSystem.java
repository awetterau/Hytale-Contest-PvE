package dev.hytalemodding.loot;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcInventoryService;
import dev.hytalemodding.state.run.GameSessionManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public final class MobDropSystem extends DeathSystems.OnDeathSystem {
    private static final Random RANDOM = new Random();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent deathComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || npc.getRoleName() == null) {
            return;
        }

        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return;
        }

        List<ItemStack> drops = LootTableRegistry.get().rollMob(npc.getRoleName(), LootTier.forActiveRun(), RANDOM);
        if (drops.isEmpty()) {
            return;
        }

        List<NpcEconomyDefinition.ItemAmount> rewards = toItemAmounts(drops);
        UUID runWorldUuid = snapshot.runWorldUuid();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (runWorldUuid.equals(playerRef.getWorldUuid())) {
                NpcInventoryService.executeTransaction(playerRef, List.of(), rewards);
            }
        }
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
}
