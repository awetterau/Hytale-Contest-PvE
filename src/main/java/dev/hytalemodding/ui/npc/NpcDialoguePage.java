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
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;
import dev.hytalemodding.npc.runtime.NpcAvailabilityService;
import dev.hytalemodding.quest.QuestProgressManager;

import javax.annotation.Nonnull;
import java.util.Locale;

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

        NpcAvailabilityService.AvailabilitySnapshot availability = NpcAvailabilityService.get().getAvailability(this.npcKey);
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(this.npcKey);
        String name = availability.displayName();
        boolean canTrade = availability.canTrade();
        boolean canQuest = availability.canQuest();
        boolean canUpgrade = availability.canUpgrade();

        // Hide quests tab for blacksmith after retrieving quest is completed
        if ("blacksmith".equals(this.npcKey) && QuestProgressManager.get().isCompleted("retrieve_blacksmith_tools")) {
            canQuest = false;
        }
        // Hide quests tab for farmer until farmer has a workshop
        if ("farmer".equals(this.npcKey) && !BaseHousingManager.get().isWorkshopBuilt("farmer")) {
            canQuest = false;
        }
        NpcEconomyDefinition economy = NpcEconomyRegistry.get().getNpc(this.npcKey);
        String readyText = economy == null ? availability.readyText() : economy.readyDialogueText;
        String lockedText = economy == null ? availability.lockedText() : economy.noWorkshopDialogueText;

        commandBuilder.append("Pages/NpcDialogue.ui");
        commandBuilder.set("#NpcName.Text", name);
        commandBuilder.set("#DialogueText.Text", availability.servicesUnlocked()
                ? readyText
                : lockedText);
        commandBuilder.set("#TradeButton.Visible", canTrade);
        commandBuilder.set("#UpgradesButton.Visible", canUpgrade);
        commandBuilder.set("#QuestsButton.Visible", canQuest);

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TradeButton", EventData.of("Action", "trade"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#UpgradesButton", EventData.of("Action", "upgrades"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#QuestsButton", EventData.of("Action", "quests"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        NpcAvailabilityService.AvailabilitySnapshot availability = NpcAvailabilityService.get().getAvailability(this.npcKey);
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(this.npcKey);
        boolean requiresWorkshop = availability.requiresWorkstation();
        boolean preWorkshopQuestUnlocked = availability.preWorkshopQuestPending();
        boolean preWorkshopUpgradeOnly = availability.preWorkshopUpgradeOnly();
        String npcName = availability.displayName();
        String npcNameLower = npcName == null || npcName.isBlank()
                ? "npc"
                : npcName.toLowerCase(Locale.ROOT);
        if ("trade".equals(action)
                && requiresWorkshop
                && !availability.servicesUnlocked()) {
            this.playerRef.sendMessage(Message.raw("The " + npcNameLower + " needs a workshop before those services unlock."));
            return;
        }
        if ("quests".equals(action)
                && requiresWorkshop
                && !availability.servicesUnlocked()
                && !preWorkshopQuestUnlocked) {
            this.playerRef.sendMessage(Message.raw("The " + npcNameLower + " needs a workshop before those services unlock."));
            return;
        }
        if ("upgrades".equals(action) && !preWorkshopUpgradeOnly && requiresWorkshop && !availability.servicesUnlocked()) {
            if (archetype != null && archetype.prePlotQuestId != null && !archetype.prePlotQuestId.isBlank()) {
                this.playerRef.sendMessage(Message.raw("Complete the " + npcNameLower + "'s prerequisite quest before workshop upgrades unlock."));
            } else {
                this.playerRef.sendMessage(Message.raw("The " + npcNameLower + " needs a workshop before those services unlock."));
            }
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



