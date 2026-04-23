package dev.hytalemodding.ui.stash;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.stash.PlayerStashManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class SimpleStashPage extends InteractiveCustomUIPage<SimpleStashPage.Data> {
    private static final String ITEM_SLOT_TEMPLATE = "Pages/SimpleStashItemSlot.ui";

    private final ItemContainer stash;
    private List<ItemGroup> stashGroups = List.of();
    private List<ItemGroup> inventoryGroups = List.of();
    private boolean fullStackMode = true;
    private int customAmount = 1;

    public static class Data {
        @Nullable
        public String action;
        @Nullable
        public Integer index;
        @Nullable
        public Integer slotIndex;
        @Nullable
        public String search;
        @Nullable
        public Integer button;
        @Nullable
        public Integer mouseButton;
        @Nullable
        public String amount;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .addField(new KeyedCodec("Index", Codec.INTEGER), (d, v) -> ((Data) d).index = (Integer) v, d -> ((Data) d).index)
                .addField(new KeyedCodec("SlotIndex", Codec.INTEGER), (d, v) -> ((Data) d).slotIndex = (Integer) v, d -> ((Data) d).slotIndex)
                .addField(new KeyedCodec("@Search", Codec.STRING), (d, v) -> ((Data) d).search = (String) v, d -> ((Data) d).search)
                .addField(new KeyedCodec("Button", Codec.INTEGER), (d, v) -> ((Data) d).button = (Integer) v, d -> ((Data) d).button)
                .addField(new KeyedCodec("MouseButton", Codec.INTEGER), (d, v) -> ((Data) d).mouseButton = (Integer) v, d -> ((Data) d).mouseButton)
                .addField(new KeyedCodec("@Amount", Codec.STRING), (d, v) -> ((Data) d).amount = (String) v, d -> ((Data) d).amount)
                .build();

        @Nullable
        public Integer effectiveIndex() {
            return this.slotIndex != null ? this.slotIndex : this.index;
        }

        @Nullable
        public Integer effectiveButton() {
            return this.button != null ? this.button : this.mouseButton;
        }
    }

    public SimpleStashPage(@Nonnull PlayerRef playerRef, @Nonnull ItemContainer stash) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.stash = stash;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());

        ui.append("Pages/SimpleStashPage.ui");

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
        bindAmountControls(events);

        if (player == null) {
            ui.set("#StatusLabel.Text", "Unable to read inventory.");
            return;
        }

        refreshGrids(ui, events, player.getInventory());
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            String rawData
    ) {
        try {
            super.handleDataEvent(ref, store, rawData);
        } catch (RuntimeException ignored) {
            // Unknown UI payloads are ignored; only explicit stash actions are supported.
        }
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull Data data
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || data.action == null) {
            return;
        }

        String action = data.action.trim().toLowerCase();
        boolean moved = false;
        switch (actionName(action)) {
            case "close" -> {
                player.getPageManager().setPage(ref, store, Page.None);
                return;
            }
            case "stash_click" -> {
                Integer idx = actionIndex(action, data.effectiveIndex());
                if (idx != null) {
                    moved = transferGroup(player, GroupSide.STASH, idx, requestedAmountFor(GroupSide.STASH, idx));
                }
            }
            case "inventory_click" -> {
                Integer idx = actionIndex(action, data.effectiveIndex());
                if (idx != null) {
                    moved = transferGroup(player, GroupSide.INVENTORY, idx, requestedAmountFor(GroupSide.INVENTORY, idx));
                }
            }
            case "toggle_full_stack" -> {
                this.fullStackMode = !this.fullStackMode;
            }
            case "amount_changed" -> {
                this.fullStackMode = false;
                this.customAmount = parsePositiveAmount(data.amount, this.customAmount);
            }
            case "amount_decrease" -> {
                this.fullStackMode = false;
                this.customAmount = Math.max(1, this.customAmount - 1);
            }
            case "amount_increase" -> {
                this.fullStackMode = false;
                this.customAmount = Math.min(9999, this.customAmount + 1);
            }
            default -> {
            }
        }

        if (moved) {
            PlayerStashManager.get().save(this.playerRef.getUuid());
        }

        refresh(ref, player);
    }

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        refreshGrids(commands, events, player.getInventory());
        sendUpdate(commands, events, false);
    }

    private void refreshGrids(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Inventory inventory) {
        this.stashGroups = aggregate(List.of(new ContainerView(this.stash)), "");
        this.inventoryGroups = mapToGroups(List.of(
                new ContainerView(inventory.getStorage()),
                new ContainerView(inventory.getHotbar())
        ));

        rebuildGroupGrid(ui, events, "#StashGrid", this.stashGroups, GroupSide.STASH);
        rebuildGroupGrid(ui, events, "#InventoryGrid", this.inventoryGroups, GroupSide.INVENTORY);
        ui.set("#StashCountLabel.Text", countItems(this.stashGroups) + " items");
        ui.set("#InventoryCountLabel.Text", countItems(this.inventoryGroups) + " items");
        ui.set("#StatusLabel.Text", this.fullStackMode
                ? "Click items to transfer one full stack"
                : "Click items to transfer " + this.customAmount);
        ui.set("#FullStackLabel.Text", this.fullStackMode ? "Full Stack: ON" : "Full Stack: OFF");
        ui.set("#AmountInput.Visible", !this.fullStackMode);
        ui.set("#AmountInput.Value", Integer.toString(this.customAmount));
        ui.set("#DecreaseButton.Visible", !this.fullStackMode);
        ui.set("#IncreaseButton.Visible", !this.fullStackMode);
    }

    private void bindAmountControls(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FullStackButton", EventData.of("Action", "toggle_full_stack"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DecreaseButton", EventData.of("Action", "amount_decrease"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#IncreaseButton", EventData.of("Action", "amount_increase"), false);
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#AmountInput",
                new EventData().append("Action", "amount_changed").append("@Amount", "#AmountInput.Value"),
                false
        );
    }

    private void rebuildGroupGrid(
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull String gridSelector,
            @Nonnull List<ItemGroup> groups,
            @Nonnull GroupSide side
    ) {
        ui.clear(gridSelector);
        for (int i = 0; i < groups.size(); i++) {
            ItemGroup group = groups.get(i);
            ItemStack stack = group.stack;
            ui.append(gridSelector, ITEM_SLOT_TEMPLATE);
            String slotSelector = gridSelector + "[" + i + "]";
            if (stack == null || stack.isEmpty()) {
                ui.set(slotSelector + " #SlotItem.ItemId", "");
                ui.set(slotSelector + " #QuantityLabel.Text", "");
                ui.set(slotSelector + ".TooltipText", "");
                continue;
            }

            ui.set(slotSelector + " #SlotItem.ItemId", stack.getItemId());
            ui.set(slotSelector + " #QuantityLabel.Text", group.quantity > 1 ? Integer.toString(group.quantity) : "");
            ui.set(slotSelector + ".TooltipText", displayItemName(stack) + " x" + group.quantity);
            String action = side == GroupSide.STASH ? "stash_click" : "inventory_click";
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    slotSelector,
                    EventData.of("Action", action + ":" + i),
                    false
            );
        }
    }

    @Nonnull
    private String actionName(@Nonnull String action) {
        int separator = action.indexOf(':');
        return separator >= 0 ? action.substring(0, separator) : action;
    }

    @Nullable
    private Integer actionIndex(@Nonnull String action, @Nullable Integer fallback) {
        int separator = action.indexOf(':');
        if (separator < 0 || separator + 1 >= action.length()) {
            return fallback;
        }
        try {
            return Integer.parseInt(action.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String displayItemName(@Nonnull ItemStack stack) {
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return "Unknown Item";
        }
        int slash = itemId.lastIndexOf('/');
        int colon = itemId.lastIndexOf(':');
        int start = Math.max(slash, colon) + 1;
        return itemId.substring(Math.max(0, start));
    }

    private List<ItemGroup> aggregate(@Nonnull List<ContainerView> containers, @Nonnull String filter) {
        List<ItemGroup> groups = new ArrayList<>();
        String query = filter.toLowerCase().trim();

        for (ContainerView view : containers) {
            for (short slot = 0; slot < view.container.getCapacity(); slot++) {
                ItemStack stack = view.container.getItemStack(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                if (!query.isEmpty()) {
                    String id = stack.getItemId().toLowerCase();
                    if (!id.contains(query)) {
                        continue;
                    }
                }

                ItemGroup group = findMergeGroup(groups, stack);
                if (group == null) {
                    group = new ItemGroup(stack.withQuantity(stack.getQuantity()), 0, new ArrayList<>());
                    groups.add(group);
                }
                group.quantity += stack.getQuantity();
                group.sources.add(new SlotSource(view.container, slot));
            }
        }
        return groups;
    }

    private List<ItemGroup> mapToGroups(@Nonnull List<ContainerView> containers) {
        List<ItemGroup> groups = new ArrayList<>();
        for (ContainerView view : containers) {
            for (short slot = 0; slot < view.container.getCapacity(); slot++) {
                ItemStack stack = view.container.getItemStack(slot);
                if (stack == null || stack.isEmpty()) {
                    groups.add(new ItemGroup(null, 0, List.of(new SlotSource(view.container, slot))));
                } else {
                    groups.add(new ItemGroup(stack, stack.getQuantity(), List.of(new SlotSource(view.container, slot))));
                }
            }
        }
        return groups;
    }

    @Nullable
    private ItemGroup findMergeGroup(@Nonnull List<ItemGroup> groups, @Nonnull ItemStack stack) {
        if (stack.getItem().getMaxStack() <= 1) {
            return null;
        }
        for (ItemGroup group : groups) {
            if (group.stack != null && group.stack.isStackableWith(stack)) {
                return group;
            }
        }
        return null;
    }

    private boolean transferGroup(@Nonnull Player player, @Nonnull GroupSide side, @Nullable Integer groupIndex, int requestedAmount) {
        List<ItemGroup> groups = side == GroupSide.STASH ? this.stashGroups : this.inventoryGroups;
        if (groupIndex == null || groupIndex < 0 || groupIndex >= groups.size()) {
            return false;
        }
        return transferGroup(player, side, groups.get(groupIndex), requestedAmount);
    }

    private boolean transferGroup(@Nonnull Player player, @Nonnull GroupSide sourceSide, @Nonnull ItemGroup group, int requestedAmount) {
        int amount = Math.min(group.quantity, requestedAmount);
        if (amount <= 0) {
            return false;
        }

        ItemStack moving = group.stack.withQuantity(amount);
        if (moving == null) {
            return false;
        }

        ItemContainer target = sourceSide == GroupSide.STASH ? player.getInventory().getCombinedHotbarFirst() : this.stash;
        if (target == null || !target.canAddItemStack(moving, false, true)) {
            return false;
        }

        int remaining = amount;
        for (SlotSource source : group.sources) {
            if (remaining <= 0) {
                break;
            }
            ItemStack current = source.container.getItemStack(source.slot);
            if (current == null || current.isEmpty() || !group.stack.isStackableWith(current)) {
                continue;
            }
            int take = Math.min(remaining, current.getQuantity());
            source.container.removeItemStackFromSlot(source.slot, take);
            remaining -= take;
        }

        if (remaining != 0) {
            return false;
        }

        target.addItemStack(moving);
        return true;
    }

    private int requestedAmountFor(@Nonnull GroupSide side, int groupIndex) {
        List<ItemGroup> groups = side == GroupSide.STASH ? this.stashGroups : this.inventoryGroups;
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            return 0;
        }
        ItemGroup group = groups.get(groupIndex);
        if (group.stack == null || group.stack.isEmpty()) {
            return 0;
        }
        if (!this.fullStackMode) {
            return Math.max(1, this.customAmount);
        }
        return Math.max(1, group.stack.getItem().getMaxStack());
    }

    private int parsePositiveAmount(@Nullable String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(9999, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int countItems(@Nonnull List<ItemGroup> groups) {
        int count = 0;
        for (ItemGroup group : groups) {
            count += group.quantity;
        }
        return count;
    }

    private enum GroupSide {
        STASH,
        INVENTORY
    }

    private record ContainerView(@Nonnull ItemContainer container) {
    }

    private record SlotSource(@Nonnull ItemContainer container, short slot) {
    }

    private static final class ItemGroup {
        @Nonnull
        private final ItemStack stack;
        private int quantity;
        @Nonnull
        private final List<SlotSource> sources;

        private ItemGroup(@Nonnull ItemStack stack, int quantity, @Nonnull List<SlotSource> sources) {
            this.stack = stack;
            this.quantity = quantity;
            this.sources = sources;
        }
    }
}
