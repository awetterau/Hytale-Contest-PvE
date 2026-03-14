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
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.NpcProgressManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class NpcTradePage extends InteractiveCustomUIPage<NpcTradePage.Data> {
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
        List<String> trades = new ArrayList<>(NpcProgressManager.get().getUnlockedTrades(this.npcKey));
        trades.sort(String::compareToIgnoreCase);
        if (this.index < 0) {
            this.index = 0;
        }
        if (!trades.isEmpty() && this.index >= trades.size()) {
            this.index = trades.size() - 1;
        }
        String tradeId = trades.isEmpty() ? null : trades.get(this.index);

        String name = this.npcKey;
        if (NpcDefinitionRegistry.get().getArchetype(this.npcKey) != null) {
            name = NpcDefinitionRegistry.get().getArchetype(this.npcKey).displayName;
        }

        ui.append("Pages/NpcTrade.ui");
        ui.set("#TradeHeader.Text", name + " Trades");
        ui.set("#TradeIdText.Text", tradeId == null
                ? "No trade unlocks yet."
                : "Trade ID: " + tradeId + " (" + (this.index + 1) + "/" + trades.size() + ")");
        ui.set("#PrevTradeBtn.Visible", trades.size() > 1);
        ui.set("#NextTradeBtn.Visible", trades.size() > 1);
        ui.set("#BuyBtn.Visible", tradeId != null);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevTradeBtn", EventData.of("Action", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextTradeBtn", EventData.of("Action", "next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BuyBtn", EventData.of("Action", "buy"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<String> trades = new ArrayList<>(NpcProgressManager.get().getUnlockedTrades(this.npcKey));
        trades.sort(String::compareToIgnoreCase);
        if ("prev".equals(action) && trades.size() > 1) {
            this.index = this.index <= 0 ? trades.size() - 1 : this.index - 1;
            refresh(ref, store);
            return;
        }
        if ("next".equals(action) && trades.size() > 1) {
            this.index = (this.index + 1) % trades.size();
            refresh(ref, store);
            return;
        }
        if ("buy".equals(action) && !trades.isEmpty()) {
            int safeIndex = Math.max(0, Math.min(this.index, trades.size() - 1));
            this.playerRef.sendMessage(Message.raw("Trade executed: " + trades.get(safeIndex) + " (transaction hook)."));
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
}



