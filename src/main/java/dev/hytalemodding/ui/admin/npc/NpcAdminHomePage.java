package dev.hytalemodding.ui.admin.npc;

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
import dev.hytalemodding.npc.admin.NpcAdminService;
import dev.hytalemodding.npc.config.NpcUnifiedRegistry;
import dev.hytalemodding.npc.core.NpcDefinition;
import dev.hytalemodding.npc.state.NpcRuntimeState;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class NpcAdminHomePage extends InteractiveCustomUIPage<NpcAdminHomePage.Data> {
    private int index;
    private boolean internalNavigation;

    public static final class Data {
        public String action;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public NpcAdminHomePage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        List<NpcDefinition> definitions = getDefinitions();
        normalizeSelection(definitions);
        NpcDefinition selected = definitions.isEmpty() ? null : definitions.get(this.index);
        NpcRuntimeState selectedState = selected == null ? null : dev.hytalemodding.npc.state.NpcStateManager.get().getState(selected.npcKey);

        ui.append("Pages/NpcAdminHome.ui");
        ui.set("#Title.Text", "NPC Admin");
        ui.set("#Summary.Text", String.join("\n", buildSummaryLines(definitions)));
        ui.set("#SelectedLabel.Text", selected == null ? "No NPC selected" : selected.displayName);
        ui.set("#SelectedMeta.Text", selected == null
                ? "Select an NPC to inspect and change its state."
                : selected.npcKey + "  |  " + selected.category.name() + "  |  " + (this.index + 1) + "/" + definitions.size());
        ui.set("#SelectedState.Text", selectedState == null
                ? ""
                : "Rescued: " + boolLabel(selectedState.rescued)
                + "   Presence: " + pretty(selectedState.presenceMode.name())
                + "   Behavior: " + pretty(selectedState.hubBehavior.name()));
        ui.set("#OpenBtn.Visible", selected != null);
        ui.set("#PrevBtn.Visible", definitions.size() > 1);
        ui.set("#NextBtn.Visible", definitions.size() > 1);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevBtn", EventData.of("Action", "prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextBtn", EventData.of("Action", "next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#OpenBtn", EventData.of("Action", "open"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshBtn", EventData.of("Action", "refresh"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<NpcDefinition> definitions = getDefinitions();
        normalizeSelection(definitions);
        if ("prev".equals(action) && definitions.size() > 1) {
            this.index = this.index <= 0 ? definitions.size() - 1 : this.index - 1;
            refresh(ref, store);
            return;
        }
        if ("next".equals(action) && definitions.size() > 1) {
            this.index = (this.index + 1) % definitions.size();
            refresh(ref, store);
            return;
        }
        if ("open".equals(action) && !definitions.isEmpty()) {
            this.internalNavigation = true;
            openPage(ref, store, new NpcAdminDetailPage(this.playerRef, definitions.get(this.index).npcKey));
            return;
        }
        if ("close".equals(action)) {
            close();
            return;
        }
        refresh(ref, store);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (this.internalNavigation) {
            return;
        }
    }

    @Nonnull
    private List<NpcDefinition> getDefinitions() {
        return new ArrayList<>(NpcUnifiedRegistry.get().getAll());
    }

    private void normalizeSelection(@Nonnull List<NpcDefinition> definitions) {
        if (definitions.isEmpty()) {
            this.index = 0;
            return;
        }
        if (this.index < 0 || this.index >= definitions.size()) {
            this.index = 0;
        }
    }

    @Nonnull
    private List<String> buildSummaryLines(@Nonnull List<NpcDefinition> definitions) {
        ArrayList<String> lines = new ArrayList<>();
        for (NpcDefinition definition : definitions) {
            NpcRuntimeState state = dev.hytalemodding.npc.state.NpcStateManager.get().getState(definition.npcKey);
            int issues = dev.hytalemodding.npc.admin.NpcValidationService.get().validate(definition).size();
            lines.add(
                    definition.displayName
                            + "  |  "
                            + (state.rescued ? "Rescued" : "Not Rescued")
                            + "  |  "
                            + pretty(state.presenceMode.name())
                            + "  |  "
                            + (issues == 0 ? "Ready" : (issues + " issue" + (issues == 1 ? "" : "s")))
            );
        }
        if (lines.isEmpty()) {
            lines.add("No NPCs registered.");
        }
        return List.copyOf(lines);
    }

    @Nonnull
    private static String boolLabel(boolean value) {
        return value ? "Yes" : "No";
    }

    @Nonnull
    private static String pretty(@Nonnull String raw) {
        return raw.toLowerCase().replace('_', ' ');
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
