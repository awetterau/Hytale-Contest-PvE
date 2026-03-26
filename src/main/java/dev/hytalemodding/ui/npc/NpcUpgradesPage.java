package dev.hytalemodding.ui.npc;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;
import dev.hytalemodding.npc.economy.NpcUpgradeService;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class NpcUpgradesPage extends InteractiveCustomUIPage<NpcUpgradesPage.Data> {
    private final String npcKey;
    private int index;
    private boolean internalNavigation;

    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcUpgradesPage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = npcKey;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        NpcDialogueManager.get().keepDialogueActive(this.playerRef);
        NpcDialogueManager.get().setTalkAnimation(this.playerRef, false);
        List<NpcEconomyDefinition.UpgradeDefinition> upgrades = getVisibleUpgrades();
        if (this.index < 0) {
            this.index = 0;
        }
        if (!upgrades.isEmpty() && this.index >= upgrades.size()) {
            this.index = upgrades.size() - 1;
        }
        NpcEconomyDefinition.UpgradeDefinition selected = upgrades.isEmpty() ? null : upgrades.get(this.index);

        ui.append("Pages/NpcUpgrades.ui");
        ui.set("#UpgradeTitle.Text", selected == null ? "NO UPGRADES" : selected.title.toUpperCase());

        Player player = store.getComponent(ref, Player.getComponentType());
        for (int i = 0; i < 4; i++) {
            boolean visible = selected != null && i < selected.cost.size();
            ui.set("#UpgradeReqRow" + i + ".Visible", visible);
            if (!visible) {
                ui.set("#UpgradeReqIcon" + i + ".ItemId", "");
                ui.set("#UpgradeReqName" + i + ".Text", "");
                ui.set("#UpgradeReqCount" + i + ".Text", "0/0");
                continue;
            }
            NpcEconomyDefinition.ItemAmount req = selected.cost.get(i);
            int have = countOwned(player, req.itemId);
            ui.set("#UpgradeReqIcon" + i + ".ItemId", req.itemId);
            ui.set("#UpgradeReqName" + i + ".Text", req.itemId);
            ui.set("#UpgradeReqCount" + i + ".Text", have + "/" + req.amount);
        }

        ui.set("#PrevUpgradeBtn.Visible", upgrades.size() > 1);
        ui.set("#NextUpgradeBtn.Visible", upgrades.size() > 1);
        ui.set("#UpgradeBtn.Visible", selected != null);
        ui.set("#UpgradeBtn.Disabled", selected == null);
        ui.set("#UpgradeBtnLabel.Text", selected == null ? "UPGRADE" : "UPGRADE TO TIER " + selected.targetTier);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevUpgradeBtn", EventData.of("Action", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextUpgradeBtn", EventData.of("Action", "next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#UpgradeBtn", EventData.of("Action", "upgrade"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<NpcEconomyDefinition.UpgradeDefinition> upgrades = getVisibleUpgrades();
        if ("prev".equals(action) && upgrades.size() > 1) {
            this.index = this.index <= 0 ? upgrades.size() - 1 : this.index - 1;
            refresh(ref, store);
            return;
        }
        if ("next".equals(action) && upgrades.size() > 1) {
            this.index = (this.index + 1) % upgrades.size();
            refresh(ref, store);
            return;
        }
        if ("upgrade".equals(action)) {
            if (upgrades.isEmpty()) {
                this.playerRef.sendMessage(Message.raw("No upgrades available."));
                return;
            }
            int safeIndex = Math.max(0, Math.min(this.index, upgrades.size() - 1));
            NpcEconomyDefinition.UpgradeDefinition selected = upgrades.get(safeIndex);
            NpcUpgradeService.Result result = NpcUpgradeService.get().executeUpgrade(this.playerRef, this.npcKey, selected.upgradeId);
            this.playerRef.sendMessage(Message.raw(result.message()));
            if (result.success() && result.closeUiOnSuccess()) {
                NpcDialogueManager.get().closeDialogue(this.playerRef);
                close();
                return;
            }
            refresh(ref, store);
            return;
        }
        this.internalNavigation = true;
        openPage(ref, store, new NpcDialoguePage(this.playerRef, this.npcKey));
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!this.internalNavigation) {
            NpcDialogueManager.get().closeDialogue(this.playerRef);
        }
    }

    private static void openPage(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull InteractiveCustomUIPage<?> nextPage
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, nextPage);
    }

    @Nonnull
    private List<NpcEconomyDefinition.UpgradeDefinition> getVisibleUpgrades() {
        NpcEconomyDefinition npc = NpcEconomyRegistry.get().getNpc(this.npcKey);
        if (npc == null) {
            return List.of();
        }
        ArrayList<NpcEconomyDefinition.UpgradeDefinition> out = new ArrayList<>();
        for (NpcEconomyDefinition.UpgradeDefinition upgrade : npc.upgrades) {
            if (!NpcUpgradeService.get().canUpgrade(this.npcKey, upgrade)) {
                continue;
            }
            out.add(upgrade);
        }
        return List.copyOf(out);
    }

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        build(ref, commandBuilder, eventBuilder, store);
        sendUpdate(commandBuilder, eventBuilder, true);
    }

    @Nonnull
    private static String formatItems(@Nonnull List<NpcEconomyDefinition.ItemAmount> items) {
        if (items.isEmpty()) {
            return "None";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (NpcEconomyDefinition.ItemAmount item : items) {
            parts.add(item.itemId + " x" + item.amount);
        }
        return String.join(", ", parts);
    }

    private static int countOwned(Player player, @Nonnull String itemId) {
        if (player == null || itemId.isBlank()) {
            return 0;
        }
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return 0;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return 0;
        }
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storage = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        return countInContainer(hotbar == null ? null : hotbar.getInventory(), itemId)
                + countInContainer(storage == null ? null : storage.getInventory(), itemId);
    }

    private static int countInContainer(ItemContainer container, @Nonnull String itemId) {
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
}



