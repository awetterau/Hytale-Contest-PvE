package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.state.run.RescueObjectiveManager;
import dev.hytalemodding.ui.hub.BasePlotTerminalPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public class DevAdminPanelPage extends InteractiveCustomUIPage<DevAdminPanelPage.Data> {
    private static final String PLOT_ID = "blacksmith_a";
    private static final String NPC_KEY = "blacksmith";

    public static class Data {
        public String action;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .build();
    }

    public DevAdminPanelPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("Pages/DevAdminPanel.ui");
        ui.set("#Status.Text", buildStatus());
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetFlowBtn", EventData.of("Action", "reset_flow"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetupPlotBtn", EventData.of("Action", "setup_plot"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RescuedTrueBtn", EventData.of("Action", "rescued_true"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PurchaseBtn", EventData.of("Action", "purchase_plot"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RescuedFalseBtn", EventData.of("Action", "rescued_false"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetWorkingBtn", EventData.of("Action", "set_working"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#OpenTerminalBtn", EventData.of("Action", "open_terminal"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshBtn", EventData.of("Action", "refresh"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        switch (action) {
            case "reset_flow" -> runResetFlow();
            case "setup_plot" -> setupPlotAtPlayer();
            case "rescued_true" -> RescueObjectiveManager.get().setNpcRescued(NPC_KEY, true);
            case "rescued_false" -> RescueObjectiveManager.get().setNpcRescued(NPC_KEY, false);
            case "purchase_plot" -> this.playerRef.sendMessage(Message.raw(BaseHousingManager.get().purchasePlot(PLOT_ID).message));
            case "set_working" -> HubNpcManager.get().devSetState("blacksmith", HubNpcManager.HubNpcState.WORKING);
            case "open_terminal" -> openPlotTerminal(ref, store);
            case "close" -> {
                close();
                return;
            }
            default -> {
            }
        }
        refresh(ref, store);
    }

    private void runResetFlow() {
        UUID worldId = this.playerRef.getWorldUuid();
        World world = worldId == null ? null : Universe.get().getWorld(worldId);
        if (world != null) {
            BaseHousingManager.get().removeAllBaseBlacksmithsInWorld(world);
        }
        BaseHousingManager.get().resetAll();
        HubNpcManager.get().resetAll();
        RescueObjectiveManager.get().resetRuntimeStatePreserveRescued();
        RescueObjectiveManager.get().setNpcRescued(NPC_KEY, false);
    }

    private void setupPlotAtPlayer() {
        UUID worldId = this.playerRef.getWorldUuid();
        World world = worldId == null ? null : Universe.get().getWorld(worldId);
        if (world == null) {
            return;
        }
        Transform transform = this.playerRef.getTransform();
        Vector3i marker = new Vector3i(
                MathUtil.floor(transform.getPosition().getX()),
                MathUtil.floor(transform.getPosition().getY()) - 1,
                MathUtil.floor(transform.getPosition().getZ())
        );
        Transform home = new Transform(
                new Vector3d(marker.x + 0.5, marker.y + 1.0, marker.z + 0.5),
                new Vector3f(transform.getRotation())
        );
        BaseHousingManager.get().addOrUpdatePlot(PLOT_ID, world.getName(), marker, home);
        BaseHousingManager.get().setPlotType(PLOT_ID, "blacksmith");
    }

    private void openPlotTerminal(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        UUID worldId = this.playerRef.getWorldUuid();
        World world = worldId == null ? null : Universe.get().getWorld(worldId);
        if (player == null || world == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new BasePlotTerminalPage(this.playerRef, world.getName()));
    }

    @Nonnull
    private String buildStatus() {
        UUID worldId = this.playerRef.getWorldUuid();
        World world = worldId == null ? null : Universe.get().getWorld(worldId);
        String worldName = world == null ? "<none>" : world.getName();
        BaseHousingManager.PlotData plot = BaseHousingManager.get().getPlot(PLOT_ID);
        HubNpcManager.NpcData npc = HubNpcManager.get().getOrCreate("blacksmith");
        String plotLine = plot == null
                ? "Plot " + PLOT_ID + ": <missing>"
                : "Plot " + plot.id + ": type=" + plot.plotType + ", purchased=" + plot.purchased + ", assigned=" + (plot.assignedNpcKey == null ? "<none>" : plot.assignedNpcKey);
        return "Quick Test Steps:\n"
                + "1) Reset Flow  2) Setup Plot Here  3) Rescued TRUE  4) Purchase Plot\n"
                + "If needed, click Force WORKING then interact with blacksmith.\n\n"
                + "World: " + worldName + " (hub: " + GameFlowConfigManager.get().getHubWorldName() + ")\n"
                + "Blacksmith: rescued=" + RescueObjectiveManager.get().isNpcRescued(NPC_KEY)
                + ", state=" + npc.state
                + ", assignedPlot=" + (npc.assignedPlotId == null ? "<none>" : npc.assignedPlotId) + "\n"
                + plotLine + "\n"
                + "Tip: /npcdev hud on for live overlay.";
    }

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        build(ref, commandBuilder, eventBuilder, store);
        sendUpdate(commandBuilder, eventBuilder, true);
    }
}


