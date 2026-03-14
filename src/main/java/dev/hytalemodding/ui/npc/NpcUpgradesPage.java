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
import dev.hytalemodding.npc.NpcDialogueManager;

import javax.annotation.Nonnull;

public class NpcUpgradesPage extends InteractiveCustomUIPage<NpcUpgradesPage.Data> {
    private final String npcKey;
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
        ui.append("Pages/NpcUpgrades.ui");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#UpgradeBtn", EventData.of("Action", "upgrade"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if ("upgrade".equals(action)) {
            this.playerRef.sendMessage(Message.raw("Upgrade requested for " + this.npcKey + " (upgrade tree hook)."));
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
}



