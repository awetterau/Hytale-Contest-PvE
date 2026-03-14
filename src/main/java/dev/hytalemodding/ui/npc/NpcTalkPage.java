package dev.hytalemodding.ui.npc;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;

import javax.annotation.Nonnull;

public class NpcTalkPage extends InteractiveCustomUIPage<NpcTalkPage.Data> {
    private final String npcKey;
    private final int step;
    private boolean internalNavigation;

    public static class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcTalkPage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey, int step) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = NpcArchetype.normalizeKey(npcKey);
        this.step = Math.max(1, Math.min(2, step));
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        NpcDialogueManager.get().keepDialogueActive(this.playerRef);
        NpcDialogueManager.get().setTalkAnimation(this.playerRef, true);
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(this.npcKey);
        String name = archetype == null ? this.npcKey : archetype.displayName;

        ui.append("Pages/NpcTalk.ui");
        ui.set("#NpcName.Text", name);
        if (this.step == 1) {
            ui.set("#DialogueText.Text", name + " says: We survive by preparing early and wasting nothing.");
            ui.set("#PromptText.Text", "Click continue.");
            ui.set("#NextBtn.Visible", true);
            ui.set("#ReturnBtn.Visible", false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#NextBtn", EventData.of("Action", "next"), false);
        } else {
            ui.set("#DialogueText.Text", name + " says: Bring resources and I can expand what this camp can do.");
            ui.set("#PromptText.Text", "Click to return.");
            ui.set("#NextBtn.Visible", false);
            ui.set("#ReturnBtn.Visible", true);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ReturnBtn", EventData.of("Action", "return"), false);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if (this.step == 1 && "next".equals(action)) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcTalkPage(this.playerRef, this.npcKey, 2));
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



