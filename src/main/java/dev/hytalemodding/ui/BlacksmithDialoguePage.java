package dev.hytalemodding.ui;

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
import dev.hytalemodding.game.BaseHousingManager;
import dev.hytalemodding.game.BlacksmithDialogueManager;
import dev.hytalemodding.game.HubNpcManager;

import javax.annotation.Nonnull;

public class BlacksmithDialoguePage extends InteractiveCustomUIPage<BlacksmithDialoguePage.Data> {
    private boolean internalNavigation;

    public static class Data {
        public String action;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public BlacksmithDialoguePage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store
    ) {
        BlacksmithDialogueManager.get().keepDialogueActive(this.playerRef);
        BlacksmithDialogueManager.get().setTalkAnimation(this.playerRef, false);

        HubNpcManager.NpcData npcData = BaseHousingManager.get().getNpcData("blacksmith");
        boolean hasAssignedPlot = npcData.assignedPlotId != null;
        commandBuilder.append("Pages/BlacksmithDialogue.ui");
        commandBuilder.set("#DialogueText.Text", hasAssignedPlot
                ? "Workshop is running. What do you need?"
                : "I need a workshop plot before I can offer services.");
        commandBuilder.set("#CraftButton.Visible", hasAssignedPlot);
        commandBuilder.set("#UpgradesButton.Visible", hasAssignedPlot);
        commandBuilder.set("#QuestsButton.Visible", hasAssignedPlot);
        commandBuilder.set("#TalkButton.Visible", true);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CraftButton", EventData.of("Action", "craft"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#UpgradesButton", EventData.of("Action", "upgrades"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#QuestsButton", EventData.of("Action", "quests"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TalkButton", EventData.of("Action", "talk"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        HubNpcManager.NpcData npcData = BaseHousingManager.get().getNpcData("blacksmith");
        if (("craft".equals(action) || "upgrades".equals(action) || "quests".equals(action))
                && npcData.assignedPlotId == null) {
            this.playerRef.sendMessage(Message.raw("Blacksmith needs a purchased plot assignment first."));
            return;
        }
        if ("craft".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new BlacksmithWorkshopPage(this.playerRef));
            return;
        }
        if ("upgrades".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new BlacksmithUpgradesPage(this.playerRef));
            return;
        }
        if ("quests".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new BlacksmithQuestPage(this.playerRef));
            return;
        }
        if ("talk".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new BlacksmithTalkPage(this.playerRef, 1));
            return;
        }
        BlacksmithDialogueManager.get().closeDialogue(this.playerRef);
        close();
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!this.internalNavigation) {
            BlacksmithDialogueManager.get().closeDialogue(this.playerRef);
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
