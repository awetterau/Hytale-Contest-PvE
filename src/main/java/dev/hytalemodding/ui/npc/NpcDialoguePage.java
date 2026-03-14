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
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.NpcProgressManager;

import javax.annotation.Nonnull;

public class NpcDialoguePage extends InteractiveCustomUIPage<NpcDialoguePage.Data> {
    private final String npcKey;
    private boolean internalNavigation;

    public static class Data {
        public String action;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcDialoguePage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = NpcArchetype.normalizeKey(npcKey);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store
    ) {
        NpcDialogueManager.get().keepDialogueActive(this.playerRef);
        NpcDialogueManager.get().setTalkAnimation(this.playerRef, false);

        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(this.npcKey);
        String name = archetype == null ? this.npcKey : archetype.displayName;
        HubNpcManager.NpcData npcData = BaseHousingManager.get().getNpcData(this.npcKey);
        boolean hasAssignedPlot = npcData.assignedPlotId != null;
        boolean hasCraftUnlocks = !NpcProgressManager.get().getUnlockedCrafts(this.npcKey).isEmpty();
        boolean hasTradeUnlocks = !NpcProgressManager.get().getUnlockedTrades(this.npcKey).isEmpty();
        boolean canTalk = archetype == null || archetype.services.canTalk;
        boolean canCraft = archetype != null && archetype.services.canCraft && hasAssignedPlot && hasCraftUnlocks;
        boolean canTrade = archetype != null && archetype.services.canTrade && hasAssignedPlot && hasTradeUnlocks;
        boolean canQuest = archetype != null && archetype.services.canGiveQuests && hasAssignedPlot;
        boolean canUpgrade = archetype != null && archetype.services.canUpgrade && hasAssignedPlot;

        commandBuilder.append("Pages/NpcDialogue.ui");
        commandBuilder.set("#NpcName.Text", name);
        commandBuilder.set("#DialogueText.Text", hasAssignedPlot
                ? name + " is ready. What do you need?"
                : name + " needs setup before full services are available.");
        commandBuilder.set("#CraftButton.Visible", canCraft);
        commandBuilder.set("#TradeButton.Visible", canTrade);
        commandBuilder.set("#UpgradesButton.Visible", canUpgrade);
        commandBuilder.set("#QuestsButton.Visible", canQuest);
        commandBuilder.set("#TalkButton.Visible", canTalk);

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CraftButton", EventData.of("Action", "craft"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TradeButton", EventData.of("Action", "trade"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#UpgradesButton", EventData.of("Action", "upgrades"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#QuestsButton", EventData.of("Action", "quests"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TalkButton", EventData.of("Action", "talk"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        HubNpcManager.NpcData npcData = BaseHousingManager.get().getNpcData(this.npcKey);
        if (("craft".equals(action) || "upgrades".equals(action) || "quests".equals(action))
                && npcData.assignedPlotId == null) {
            this.playerRef.sendMessage(Message.raw("This NPC needs a purchased plot assignment first."));
            return;
        }
        if ("craft".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcWorkshopPage(this.playerRef, this.npcKey));
            return;
        }
        if ("trade".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcTradePage(this.playerRef, this.npcKey));
            return;
        }
        if ("upgrades".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcUpgradesPage(this.playerRef, this.npcKey));
            return;
        }
        if ("quests".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcQuestPage(this.playerRef, this.npcKey));
            return;
        }
        if ("talk".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcTalkPage(this.playerRef, this.npcKey, 1));
            return;
        }
        NpcDialogueManager.get().closeDialogue(this.playerRef);
        close();
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



