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
import dev.hytalemodding.quest.QuestDefinition;
import dev.hytalemodding.quest.QuestDefinitionRegistry;
import dev.hytalemodding.quest.QuestProgressManager;

import javax.annotation.Nonnull;
import java.util.List;

public class NpcQuestPage extends InteractiveCustomUIPage<NpcQuestPage.Data> {
    private final String npcKey;
    private int questIndex;
    private boolean internalNavigation;

    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcQuestPage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = npcKey;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        NpcDialogueManager.get().keepDialogueActive(this.playerRef);
        NpcDialogueManager.get().setTalkAnimation(this.playerRef, false);
        List<QuestDefinition> quests = QuestDefinitionRegistry.get().getBySource("npc", this.npcKey);
        if (this.questIndex < 0) {
            this.questIndex = 0;
        }
        if (!quests.isEmpty() && this.questIndex >= quests.size()) {
            this.questIndex = quests.size() - 1;
        }
        QuestDefinition selected = quests.isEmpty() ? null : quests.get(this.questIndex);
        ui.append("Pages/NpcQuest.ui");
        ui.set("#QuestHeader.Text", this.npcKey + " Quest Board");
        if (selected == null) {
            ui.set("#QuestTitle.Text", "No quests available");
            ui.set("#QuestSummary.Text", "This NPC currently has no quest definitions.");
            ui.set("#QuestStatus.Text", "Status: N/A");
            ui.set("#AcceptQuestBtn.Visible", false);
            ui.set("#CompleteQuestBtn.Visible", false);
            ui.set("#PrevQuestBtn.Visible", false);
            ui.set("#NextQuestBtn.Visible", false);
        } else {
            QuestProgressManager.QuestProgress progress = QuestProgressManager.get().getOrCreate(selected.questId);
            ui.set("#QuestTitle.Text", selected.title);
            ui.set("#QuestSummary.Text", selected.summary + "\nQuest ID: " + selected.questId);
            ui.set("#QuestStatus.Text", "Status: " + (progress.completed ? "Completed" : (progress.accepted ? "Accepted" : "Not accepted")));
            ui.set("#AcceptQuestBtn.Visible", !progress.completed);
            ui.set("#CompleteQuestBtn.Visible", progress.accepted && !progress.completed);
            ui.set("#PrevQuestBtn.Visible", quests.size() > 1);
            ui.set("#NextQuestBtn.Visible", quests.size() > 1);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevQuestBtn", EventData.of("Action", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextQuestBtn", EventData.of("Action", "next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AcceptQuestBtn", EventData.of("Action", "accept"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CompleteQuestBtn", EventData.of("Action", "complete"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn", EventData.of("Action", "back"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<QuestDefinition> quests = QuestDefinitionRegistry.get().getBySource("npc", this.npcKey);
        if ("prev".equals(action) && quests.size() > 1) {
            this.questIndex = this.questIndex <= 0 ? quests.size() - 1 : this.questIndex - 1;
            refresh(ref, store);
            return;
        }
        if ("next".equals(action) && quests.size() > 1) {
            this.questIndex = (this.questIndex + 1) % quests.size();
            refresh(ref, store);
            return;
        }
        QuestDefinition quest = quests.isEmpty() ? null : quests.get(Math.max(0, Math.min(this.questIndex, quests.size() - 1)));
        if ("accept".equals(action)) {
            if (quest == null) {
                this.playerRef.sendMessage(Message.raw("No quest definitions found for " + this.npcKey + "."));
                return;
            }
            boolean ok = QuestProgressManager.get().accept(quest.questId);
            this.playerRef.sendMessage(Message.raw(ok ? "Quest accepted: " + quest.title : "Quest already completed or unavailable."));
            refresh(ref, store);
            return;
        }
        if ("complete".equals(action)) {
            if (quest == null) {
                this.playerRef.sendMessage(Message.raw("No quest selected for " + this.npcKey + "."));
                return;
            }
            boolean ok = QuestProgressManager.get().complete(quest.questId, this.playerRef);
            this.playerRef.sendMessage(Message.raw(ok
                    ? "Quest completed: " + quest.title
                    : "Quest completion failed. Required objective items are missing."));
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
}



