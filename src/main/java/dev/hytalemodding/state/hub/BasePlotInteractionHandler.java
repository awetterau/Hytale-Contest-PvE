package dev.hytalemodding.state.hub;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.ui.hub.BasePlotAssignPage;
import dev.hytalemodding.ui.hub.BasePlotTerminalPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BasePlotInteractionHandler {
    private static final String TERMINAL_BLOCK_ID = "Base_Plot_Marker";

    private BasePlotInteractionHandler() {
    }

    public static void onPlayerInteract(@Nonnull PlayerInteractEvent event) {
        if (event.getActionType() != InteractionType.Use || event.getTargetBlock() == null) {
            return;
        }
        if (event.getPlayerRef() == null || !event.getPlayerRef().isValid()) {
            return;
        }
        PlayerRef playerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        Player player = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), Player.getComponentType());
        if (playerRef == null || player == null || playerRef.getWorldUuid() == null) {
            return;
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            return;
        }
        handlePlotUse(playerRef, player, world, event.getTargetBlock());
    }

    public static boolean handlePlotUse(
            @Nullable PlayerRef playerRef,
            @Nullable Player player,
            @Nullable World world,
            @Nullable Vector3i target
    ) {
        if (playerRef == null || player == null || world == null || target == null) {
            return false;
        }
        BaseHousingManager.PlotData plot = BaseHousingManager.get().findPlotByMarker(world.getName(), target);
        if (plot == null) {
            BlockType blockType = world.getBlockType(target);
            if (blockType != null && TERMINAL_BLOCK_ID.equals(blockType.getId())) {
                player.getPageManager().openCustomPage(player.getReference(), player.getReference().getStore(), new BasePlotTerminalPage(playerRef, world.getName()));
                return true;
            }
            return false;
        }
        player.getPageManager().openCustomPage(player.getReference(), player.getReference().getStore(), new BasePlotAssignPage(playerRef, plot.id));
        playerRef.sendMessage(Message.raw("Opened plot assignment: " + plot.id));
        return true;
    }
}


