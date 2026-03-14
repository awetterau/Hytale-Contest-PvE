package dev.hytalemodding.ui.hub;

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
import dev.hytalemodding.domain.housing.BaseHousingManager;

import javax.annotation.Nonnull;
import java.util.List;

public class BasePlotTerminalPage extends InteractiveCustomUIPage<BasePlotTerminalPage.PageData> {
    private static final int MAX_BUTTONS = 8;

    @Nonnull
    private final String worldName;
    @Nonnull
    private List<String> currentPlotIds = List.of();

    public static class PageData {
        public String action;
        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((PageData) d).action = (String) v, d -> ((PageData) d).action)
                .build();
    }

    public BasePlotTerminalPage(@Nonnull PlayerRef playerRef, @Nonnull String worldName) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.worldName = worldName;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        this.currentPlotIds = BaseHousingManager.get().getPlotIdsForWorld(this.worldName);
        ui.append("Pages/BasePlotTerminal.ui");
        ui.set("#Title.Text", "Plot Terminal");

        if (this.currentPlotIds.isEmpty()) {
            ui.set("#Status.Text", "No plots in world '" + this.worldName + "'. Use /baseplot add <id>.");
        } else {
            ui.set("#Status.Text", "Select plot:");
        }

        for (int i = 0; i < MAX_BUTTONS; i++) {
            String btn = "#PlotBtn" + (i + 1);
            if (i < this.currentPlotIds.size()) {
                String plotId = this.currentPlotIds.get(i);
                BaseHousingManager.PlotData plot = BaseHousingManager.get().getPlot(plotId);
                String label = plotId;
                if (plot != null) {
                    label = plotId + (plot.purchased ? " [Purchased]" : " [For Sale]");
                }
                ui.set(btn + ".Visible", true);
                ui.set(btn + ".Disabled", false);
                ui.set("#PlotBtn" + (i + 1) + "Text.Text", label);
                events.addEventBinding(CustomUIEventBindingType.Activating, btn, EventData.of("Action", "plot:" + i), false);
            } else {
                ui.set(btn + ".Visible", false);
            }
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        if (action.startsWith("plot:")) {
            int index;
            try {
                index = Integer.parseInt(action.substring("plot:".length()));
            } catch (NumberFormatException ignored) {
                close();
                return;
            }
            if (index < 0 || index >= this.currentPlotIds.size()) {
                close();
                return;
            }
            String plotId = this.currentPlotIds.get(index);
            Ref<EntityStore> playerRefEntity = this.playerRef.getReference();
            Store<EntityStore> playerStore = playerRefEntity == null ? null : playerRefEntity.getStore();
            Player player = playerStore == null ? null : playerStore.getComponent(playerRefEntity, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(playerRefEntity, playerStore, new BasePlotAssignPage(this.playerRef, plotId));
            }
            return;
        }
        close();
    }
}


