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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;
import dev.hytalemodding.npc.economy.NpcOfferService;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class NpcWorkshopPage extends InteractiveCustomUIPage<NpcWorkshopPage.Data> {
    private final String npcKey;
    private int index;
    private boolean internalNavigation;

    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcWorkshopPage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = npcKey;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        NpcDialogueManager.get().keepDialogueActive(this.playerRef);
        NpcDialogueManager.get().setTalkAnimation(this.playerRef, false);
        List<NpcEconomyDefinition.OfferDefinition> offers = getVisibleCraftOffers();
        if (this.index < 0) {
            this.index = 0;
        }
        if (!offers.isEmpty() && this.index >= offers.size()) {
            this.index = offers.size() - 1;
        }
        NpcEconomyDefinition.OfferDefinition selected = offers.isEmpty() ? null : offers.get(this.index);
        ui.append("Pages/NpcWorkshop.ui");
        ui.set("#RecipeTitleText.Text", selected == null
                ? "No craft unlocks yet."
                : selected.title + " (" + (this.index + 1) + "/" + offers.size() + ")");
        ui.set("#RecipeCostText.Text", selected == null
                ? ""
                : "Cost: " + formatItems(selected.cost));
        ui.set("#RecipeRewardText.Text", selected == null
                ? ""
                : "Reward: " + formatItems(selected.reward));
        ui.set("#PrevCraftBtn.Visible", offers.size() > 1);
        ui.set("#NextCraftBtn.Visible", offers.size() > 1);
        ui.set("#CraftSwordBtn.Visible", selected != null);
        ui.set("#CraftSwordBtn.Disabled", selected == null);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevCraftBtn", EventData.of("Action", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextCraftBtn", EventData.of("Action", "next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CraftSwordBtn", EventData.of("Action", "craft"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<NpcEconomyDefinition.OfferDefinition> offers = getVisibleCraftOffers();
        if ("prev".equals(action) && offers.size() > 1) {
            this.index = this.index <= 0 ? offers.size() - 1 : this.index - 1;
            refresh(ref, store);
            return;
        }
        if ("next".equals(action) && offers.size() > 1) {
            this.index = (this.index + 1) % offers.size();
            refresh(ref, store);
            return;
        }
        if ("craft".equals(action)) {
            if (offers.isEmpty()) {
                this.playerRef.sendMessage(Message.raw("No craft unlocks available yet for " + this.npcKey + "."));
                return;
            }
            int safeIndex = Math.max(0, Math.min(this.index, offers.size() - 1));
            NpcEconomyDefinition.OfferDefinition selected = offers.get(safeIndex);
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
    private List<NpcEconomyDefinition.OfferDefinition> getVisibleCraftOffers() {
        NpcEconomyDefinition npc = NpcEconomyRegistry.get().getNpc(this.npcKey);
        if (npc == null) {
            return List.of();
        }
        Set<String> unlocked = NpcProgressManager.get().getUnlockedCrafts(this.npcKey);
        ArrayList<NpcEconomyDefinition.OfferDefinition> out = new ArrayList<>();
        for (NpcEconomyDefinition.OfferDefinition offer : npc.getOffersByKind(NpcEconomyDefinition.OfferKind.CRAFT)) {
            if (!unlocked.contains(offer.offerId)) {
                continue;
            }
            if (!NpcOfferService.get().canUseOffer(this.npcKey, offer)) {
                continue;
            }
            out.add(offer);
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
}



