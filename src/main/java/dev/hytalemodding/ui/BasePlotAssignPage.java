package dev.hytalemodding.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.game.BaseHousingManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class BasePlotAssignPage extends InteractiveCustomUIPage<BasePlotAssignPage.PageData> {
    @Nonnull
    private final String plotId;
    @Nullable
    private String selectedNpc;

    public static class PageData {
        public String action;
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((PageData) d).action = (String) v, d -> ((PageData) d).action)
                .build();
    }

    public BasePlotAssignPage(@Nonnull PlayerRef playerRef, @Nonnull String plotId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.plotId = plotId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        BaseHousingManager manager = BaseHousingManager.get();
        BaseHousingManager.PlotData plot = manager.getPlot(this.plotId);
        List<String> eligible = manager.getEligibleNpcKeysForPlot(this.plotId);

        ui.append("Pages/BasePlotAssign.ui");
        ui.set("#PlotTitle.Text", "Plot: " + this.plotId);

        if (plot == null) {
            ui.set("#StatusText.Text", "Plot not found.");
            ui.set("#BlacksmithBtn.Visible", false);
            ui.set("#ConfirmBtn.Visible", false);
            ui.set("#CloseBtn.Visible", true);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
            return;
        }
        if (plot.assignedNpcKey != null) {
            ui.set("#StatusText.Text", "This plot is occupied by " + plot.assignedNpcKey + ".");
            ui.set("#BlacksmithBtn.Visible", false);
            ui.set("#ConfirmBtn.Visible", false);
            ui.set("#CloseBtn.Visible", true);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
            return;
        }

        boolean blacksmithEligible = eligible.contains("blacksmith");
        ui.set("#BlacksmithBtn.Visible", blacksmithEligible);
        ui.set("#BlacksmithBtn.Disabled", !blacksmithEligible);

        if (this.selectedNpc == null) {
            if (blacksmithEligible) {
                ui.set("#StatusText.Text", "Select a rescued NPC with no home.");
            } else {
                ui.set("#StatusText.Text", "No rescued unassigned NPCs available.");
            }
            ui.set("#ConfirmBtn.Visible", false);
        } else {
            ui.set("#StatusText.Text", "Build blacksmith home on this plot?");
            ui.set("#ConfirmBtn.Visible", true);
            ui.set("#ConfirmBtn.Disabled", false);
        }

        ui.set("#CloseBtn.Visible", true);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BlacksmithBtn", EventData.of("Action", "select:blacksmith"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmBtn", EventData.of("Action", "confirm"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if (action.startsWith("select:")) {
            this.selectedNpc = action.substring("select:".length());
            refresh(ref, store);
            return;
        }
        if ("confirm".equals(action)) {
            if (this.selectedNpc == null || this.selectedNpc.isBlank()) {
                this.playerRef.sendMessage(Message.raw("No NPC selected."));
                refresh(ref, store);
                return;
            }
            BaseHousingManager.AssignmentResult result = BaseHousingManager.get().assignNpcToPlot(this.plotId, this.selectedNpc);
            this.playerRef.sendMessage(Message.raw(result.message));
            close();
            return;
        }
        close();
    }

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        build(ref, commandBuilder, eventBuilder, store);
        sendUpdate(commandBuilder, eventBuilder, true);
    }
}
