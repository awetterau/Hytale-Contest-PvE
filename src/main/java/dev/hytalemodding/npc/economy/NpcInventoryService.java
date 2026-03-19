package dev.hytalemodding.npc.economy;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class NpcInventoryService {
    private static final int DEFAULT_MAX_STACK = 50;

    private NpcInventoryService() {
    }

    public static boolean canAfford(@Nonnull PlayerRef playerRef, @Nonnull List<NpcEconomyDefinition.ItemAmount> costs) {
        if (costs.isEmpty()) {
            return true;
        }
        Inventory inventory = getInventory(playerRef);
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        ItemContainer storage = inventory.getStorage();
        for (NpcEconomyDefinition.ItemAmount cost : costs) {
            int have = countInContainer(hotbar, cost.itemId) + countInContainer(storage, cost.itemId);
            if (have < cost.amount) {
                return false;
            }
        }
        return true;
    }

    public static boolean executeTransaction(
            @Nonnull PlayerRef playerRef,
            @Nonnull List<NpcEconomyDefinition.ItemAmount> costs,
            @Nonnull List<NpcEconomyDefinition.ItemAmount> rewards
    ) {
        Inventory inventory = getInventory(playerRef);
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        ItemContainer storage = inventory.getStorage();
        if (hotbar == null || storage == null) {
            return false;
        }

        // Validate affordability first.
        for (NpcEconomyDefinition.ItemAmount cost : costs) {
            int have = countInContainer(hotbar, cost.itemId) + countInContainer(storage, cost.itemId);
            if (have < cost.amount) {
                return false;
            }
        }

        // Simulate reward fit before consuming cost.
        ItemContainer hotbarShadow = cloneContainer(hotbar);
        ItemContainer storageShadow = cloneContainer(storage);
        if (hotbarShadow == null || storageShadow == null) {
            return false;
        }
        for (NpcEconomyDefinition.ItemAmount reward : rewards) {
            int remaining = reward.amount;
            remaining -= addToContainerWithStacking(storageShadow, reward.itemId, remaining);
            if (remaining > 0) {
                remaining -= addToContainerWithStacking(hotbarShadow, reward.itemId, remaining);
            }
            if (remaining > 0) {
                return false;
            }
        }

        // Apply costs.
        for (NpcEconomyDefinition.ItemAmount cost : costs) {
            int need = cost.amount;
            need = consumeFromContainer(storage, cost.itemId, need);
            if (need > 0) {
                need = consumeFromContainer(hotbar, cost.itemId, need);
            }
            if (need > 0) {
                return false;
            }
        }

        // Apply rewards.
        for (NpcEconomyDefinition.ItemAmount reward : rewards) {
            int remaining = reward.amount;
            remaining -= addToContainerWithStacking(storage, reward.itemId, remaining);
            if (remaining > 0) {
                remaining -= addToContainerWithStacking(hotbar, reward.itemId, remaining);
            }
            if (remaining > 0) {
                return false;
            }
        }

        return true;
    }

    private static int countInContainer(@Nullable ItemContainer container, @Nonnull String itemId) {
        if (container == null) {
            return 0;
        }
        int total = 0;
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null) {
                continue;
            }
            if (!itemId.equals(stack.getItemId())) {
                continue;
            }
            total += stack.getQuantity();
        }
        return total;
    }

    private static int consumeFromContainer(@Nullable ItemContainer container, @Nonnull String itemId, int need) {
        if (container == null || need <= 0) {
            return need;
        }
        for (short i = 0; i < container.getCapacity(); i++) {
            if (need <= 0) {
                break;
            }
            ItemStack stack = container.getItemStack(i);
            if (stack == null) {
                continue;
            }
            if (!itemId.equals(stack.getItemId())) {
                continue;
            }
            int available = stack.getQuantity();
            if (available <= 0) {
                continue;
            }
            int take = Math.min(available, need);
            container.removeItemStackFromSlot(i, take);
            need -= take;
        }
        return need;
    }

    private static int addToContainerWithStacking(@Nullable ItemContainer container, @Nonnull String itemId, int amount) {
        if (container == null || amount <= 0) {
            return 0;
        }
        int remaining = amount;

        // Fill existing stacks.
        for (short i = 0; i < container.getCapacity(); i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = container.getItemStack(i);
            if (stack == null) {
                continue;
            }
            if (!itemId.equals(stack.getItemId())) {
                continue;
            }
            int current = stack.getQuantity();
            if (current >= DEFAULT_MAX_STACK) {
                continue;
            }
            int canAdd = Math.min(DEFAULT_MAX_STACK - current, remaining);
            container.setItemStackForSlot(i, stack.withQuantity(current + canAdd));
            remaining -= canAdd;
        }

        // Fill empty slots.
        for (short i = 0; i < container.getCapacity(); i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = container.getItemStack(i);
            if (stack != null && stack.getQuantity() > 0) {
                continue;
            }
            int stackAmount = Math.min(DEFAULT_MAX_STACK, remaining);
            container.setItemStackForSlot(i, new ItemStack(itemId, stackAmount));
            remaining -= stackAmount;
        }

        return amount - remaining;
    }

    @Nullable
    private static Inventory getInventory(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return null;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return null;
        }
        return player.getInventory();
    }

    @Nullable
    private static ItemContainer cloneContainer(@Nullable ItemContainer source) {
        if (source == null) {
            return null;
        }
        com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer clone =
                new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer(source.getCapacity());
        for (short i = 0; i < source.getCapacity(); i++) {
            ItemStack stack = source.getItemStack(i);
            if (stack == null) {
                clone.setItemStackForSlot(i, null);
                continue;
            }
            clone.setItemStackForSlot(i, new ItemStack(stack.getItemId(), stack.getQuantity()));
        }
        return clone;
    }
}
