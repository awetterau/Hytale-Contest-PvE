package dev.hytalemodding.ui.hub;

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
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.HubNpcManager;

import javax.annotation.Nonnull;

public class BasePlotAssignPage extends InteractiveCustomUIPage<BasePlotAssignPage.PageData> {
    @Nonnull
    private final String plotId;

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

        ui.append("Pages/BasePlotAssign.ui");
        ui.set("#PlotTitle.Text", "Plot: " + this.plotId);

        if (plot == null) {
            ui.set("#StatusText.Text", "Plot not found.");
            ui.set("#BlacksmithBtn.Visible", false);
            ui.set("#PurchaseBtn.Visible", false);
            ui.set("#AssignBtn.Visible", false);
            ui.set("#CloseBtn.Visible", true);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
            return;
        }

        ui.set("#BlacksmithBtn.Visible", false);
        ui.set("#BlacksmithBtn.Disabled", true);
        ui.set("#PurchaseBtn.Visible", !plot.purchased);
        ui.set("#PurchaseBtn.Disabled", false);
        ui.set("#AssignBtn.Visible", false);

        if (!plot.purchased) {
            ui.set("#StatusText.Text", "Purchase this " + plot.plotType + " plot to build its workshop.");
        } else if (plot.assignedNpcKey != null) {
            HubNpcManager.HubNpcState state = manager.getNpcState(plot.assignedNpcKey);
            ui.set("#StatusText.Text", "Assigned: " + plot.assignedNpcKey + " (" + state.name() + ").");
        } else {
            ui.set("#StatusText.Text", "Waiting for rescued " + plot.plotType + " to auto-assign.");
        }

        ui.set("#CloseBtn.Visible", true);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PurchaseBtn", EventData.of("Action", "purchase"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if ("purchase".equals(action)) {
            BaseHousingManager.AssignmentResult result = BaseHousingManager.get().purchasePlot(this.plotId);
            this.playerRef.sendMessage(Message.raw(result.message));
            refresh(ref, store);
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


