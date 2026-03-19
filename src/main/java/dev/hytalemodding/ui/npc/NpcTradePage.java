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
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;
import dev.hytalemodding.npc.economy.NpcOfferService;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NpcTradePage extends InteractiveCustomUIPage<NpcTradePage.Data> {
    private static final int SLOT_COUNT = 7;
    private final String npcKey;
    private int index;
    private boolean internalNavigation;

    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcTradePage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = npcKey;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        NpcDialogueManager.get().keepDialogueActive(this.playerRef);
        NpcDialogueManager.get().setTalkAnimation(this.playerRef, false);
        List<NpcEconomyDefinition.OfferDefinition> trades = getTradeOffers();
        normalizeSelection(trades);
        NpcEconomyDefinition.OfferDefinition selected = getSelected(trades);
        Player player = store.getComponent(ref, Player.getComponentType());

        String name = this.npcKey;
        if (NpcDefinitionRegistry.get().getArchetype(this.npcKey) != null) {
            name = NpcDefinitionRegistry.get().getArchetype(this.npcKey).displayName;
        }

        ui.append("Pages/WorkshopTab.ui");
        ui.set("#WorkshopContent.Visible", !trades.isEmpty());
        ui.set("#WorkshopEmpty.Visible", trades.isEmpty());
        ui.set("#WorkshopUpgradeView.Visible", false);

        ui.set("#QtyDec.Visible", false);
        ui.set("#QtyValBox.Visible", false);
        ui.set("#QtyInc.Visible", false);
        ui.set("#QtyVal.Text", "1");
        ui.set("#CraftBtn.Visible", selected != null);

        String rarity = "COMMON";
        if (selected != null) {
            rarity = rarityLabel(selected.requiredTier);
        }

        ui.set("#RarityLabel.Text", rarity);
        ui.set("#ItemName.Text", selected == null ? (name + " Trades") : selected.title);
        ui.set("#ItemDesc.Text", selected == null
                ? "No trade unlocks available yet."
                : "Cost: " + formatItems(selected.cost) + "\nReward: " + formatItems(selected.reward));

        for (int i = 0; i < SLOT_COUNT; i++) {
            boolean visible = i < trades.size();
            NpcEconomyDefinition.OfferDefinition offer = visible ? trades.get(i) : null;
            String raritySlot = visible ? raritySlot(offer.requiredTier) : "common";
            boolean unlocked = visible && NpcOfferService.get().canUseOffer(this.npcKey, offer);
            boolean locked = visible && !unlocked;
            String iconItem = visible ? firstRewardItemId(offer) : "";
            boolean selectedSlot = visible && i == this.index && unlocked;

            ui.set("#Bp" + i + "Cell.Visible", visible);

            ui.set("#Bp" + i + "CommonUnlocked.Visible", visible && "common".equals(raritySlot) && unlocked);
            ui.set("#Bp" + i + "UncommonUnlocked.Visible", visible && "uncommon".equals(raritySlot) && unlocked);
            ui.set("#Bp" + i + "RareUnlocked.Visible", visible && "rare".equals(raritySlot) && unlocked);

            ui.set("#Bp" + i + "CommonLocked.Visible", visible && "common".equals(raritySlot) && locked);
            ui.set("#Bp" + i + "UncommonLocked.Visible", visible && "uncommon".equals(raritySlot) && locked);
            ui.set("#Bp" + i + "RareLocked.Visible", visible && "rare".equals(raritySlot) && locked);

            ui.set("#Bp" + i + "CommonUnlockedIcon.ItemId", visible && "common".equals(raritySlot) && unlocked ? iconItem : "");
            ui.set("#Bp" + i + "UncommonUnlockedIcon.ItemId", visible && "uncommon".equals(raritySlot) && unlocked ? iconItem : "");
            ui.set("#Bp" + i + "RareUnlockedIcon.ItemId", visible && "rare".equals(raritySlot) && unlocked ? iconItem : "");

            ui.set("#Bp" + i + "CommonLockedIcon.ItemId", visible && "common".equals(raritySlot) && locked ? iconItem : "");
            ui.set("#Bp" + i + "UncommonLockedIcon.ItemId", visible && "uncommon".equals(raritySlot) && locked ? iconItem : "");
            ui.set("#Bp" + i + "RareLockedIcon.ItemId", visible && "rare".equals(raritySlot) && locked ? iconItem : "");

            ui.set("#Bp" + i + "CommonUnlocked #Selected.Visible", "common".equals(raritySlot) && selectedSlot);
            ui.set("#Bp" + i + "UncommonUnlocked #Selected.Visible", "uncommon".equals(raritySlot) && selectedSlot);
            ui.set("#Bp" + i + "RareUnlocked #Selected.Visible", "rare".equals(raritySlot) && selectedSlot);
        }

        for (int i = 0; i < 4; i++) {
            boolean show = selected != null && i < selected.cost.size();
            ui.set("#ReqRow" + i + ".Visible", show);
            if (!show) {
                ui.set("#ReqIcon" + i + ".ItemId", "");
                ui.set("#ReqName" + i + ".Text", "");
                ui.set("#ReqCount" + i + ".Text", "");
                if (i < 3) {
                    ui.set("#Divider" + i + ".Visible", false);
                }
                continue;
            }
            NpcEconomyDefinition.ItemAmount req = selected.cost.get(i);
            int have = countOwned(player, req.itemId);
            ui.set("#ReqIcon" + i + ".ItemId", req.itemId);
            ui.set("#ReqName" + i + ".Text", req.itemId);
            ui.set("#ReqCount" + i + ".Text", have + "/" + req.amount);
            ui.set("#Divider" + i + ".Visible", i < 3 && i < selected.cost.size() - 1);
        }

        for (int i = 0; i < SLOT_COUNT; i++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Bp" + i + "CommonUnlocked", EventData.of("Action", "select:" + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Bp" + i + "UncommonUnlocked", EventData.of("Action", "select:" + i), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Bp" + i + "RareUnlocked", EventData.of("Action", "select:" + i), false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CraftBtn", EventData.of("Action", "buy"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<NpcEconomyDefinition.OfferDefinition> trades = getTradeOffers();
        normalizeSelection(trades);

        if (action.startsWith("select:")) {
            try {
                int newIndex = Integer.parseInt(action.substring("select:".length()));
                if (newIndex >= 0 && newIndex < trades.size() && newIndex < SLOT_COUNT) {
                    this.index = newIndex;
                }
            } catch (NumberFormatException ignored) {
            }
            refresh(ref, store);
            return;
        }

        if ("buy".equals(action) && !trades.isEmpty()) {
            int safeIndex = Math.max(0, Math.min(this.index, trades.size() - 1));
            NpcEconomyDefinition.OfferDefinition selected = trades.get(safeIndex);
            if (!NpcOfferService.get().canUseOffer(this.npcKey, selected)) {
                this.playerRef.sendMessage(Message.raw("That trade is currently locked."));
                refresh(ref, store);
                return;
            }
            NpcOfferService.Result result = NpcOfferService.get().executeOffer(this.playerRef, this.npcKey, selected.offerId);
            this.playerRef.sendMessage(Message.raw(result.message()));
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

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        build(ref, commandBuilder, eventBuilder, store);
        sendUpdate(commandBuilder, eventBuilder, true);
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
    private List<NpcEconomyDefinition.OfferDefinition> getTradeOffers() {
        NpcEconomyDefinition npc = NpcEconomyRegistry.get().getNpc(this.npcKey);
        if (npc == null) {
            return List.of();
        }
        ArrayList<NpcEconomyDefinition.OfferDefinition> out = new ArrayList<>();
        for (NpcEconomyDefinition.OfferDefinition offer : npc.getOffersByKind(NpcEconomyDefinition.OfferKind.TRADE)) {
            out.add(offer);
        }
        out.sort(Comparator
                .comparingInt((NpcEconomyDefinition.OfferDefinition o) -> o.requiredTier)
                .thenComparing(o -> o.title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private void normalizeSelection(@Nonnull List<NpcEconomyDefinition.OfferDefinition> trades) {
        if (trades.isEmpty()) {
            this.index = -1;
            return;
        }
        if (this.index >= 0 && this.index < trades.size()) {
            return;
        }
        for (int i = 0; i < trades.size(); i++) {
            if (NpcOfferService.get().canUseOffer(this.npcKey, trades.get(i))) {
                this.index = i;
                return;
            }
        }
        this.index = 0;
    }

    private NpcEconomyDefinition.OfferDefinition getSelected(@Nonnull List<NpcEconomyDefinition.OfferDefinition> trades) {
        if (trades.isEmpty() || this.index < 0 || this.index >= trades.size()) {
            return null;
        }
        return trades.get(this.index);
    }

    @Nonnull
    private static String raritySlot(int requiredTier) {
        if (requiredTier <= 0) {
            return "common";
        }
        if (requiredTier == 1) {
            return "uncommon";
        }
        return "rare";
    }

    @Nonnull
    private static String rarityLabel(int requiredTier) {
        if (requiredTier <= 0) {
            return "COMMON";
        }
        if (requiredTier == 1) {
            return "UNCOMMON";
        }
        return "RARE";
    }

    @Nonnull
    private static String firstRewardItemId(@Nonnull NpcEconomyDefinition.OfferDefinition offer) {
        if (offer.reward.isEmpty()) {
            return "";
        }
        return offer.reward.get(0).itemId;
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
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return 0;
        }
        return countInContainer(inventory.getHotbar(), itemId) + countInContainer(inventory.getStorage(), itemId);
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



