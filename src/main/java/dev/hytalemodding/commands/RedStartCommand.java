package dev.hytalemodding.commands;

import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class RedStartCommand extends AbstractPlayerCommand {
    @Nonnull
    private final RequiredArg<Float> secondsArg = this.withRequiredArg("seconds", "How long the sweep should take", ArgTypes.FLOAT);

    public RedStartCommand() {
        super("redstart", "Start red-wave sweep around core block.");
        this.setPermissionGroup(null);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        Float seconds = this.secondsArg.get(context);
        if (seconds == null || seconds <= 0.0f) {
            context.sendMessage(Message.raw("Seconds must be greater than 0."));
            return;
        }

        UUID playerId = playerRef.getUuid();
        RedWaveManager.Selection selection = RedWaveManager.getSelection(playerId);
        if (selection == null || !selection.isComplete()) {
            context.sendMessage(Message.raw("Configure crimson with /redcore and /redradius first."));
            return;
        }

        UUID worldId = world.getWorldConfig().getUuid();
        if (!RedWaveManager.isWorldReady(worldId)) {
            RedWaveManager.UndoProcessStatus status = RedWaveManager.getUndoProcessStatus(worldId);
            if (status != null) {
                context.sendMessage(Message.raw(
                        "Wait for chunk undo to finish: " + status.restoredChunks() + "/" + status.totalChunks() + " chunks restored."
                ));
            } else {
                context.sendMessage(Message.raw("World is not ready for a new redstart yet."));
            }
            return;
        }

        if (!worldId.equals(selection.worldId())) {
            context.sendMessage(Message.raw("Configuration was made in another world. Configure again here with /redcore and /redradius."));
            return;
        }

        Vector3i corePos = selection.corePos();
        Integer radius = selection.radiusBlocks();
        if (corePos == null || radius == null) {
            context.sendMessage(Message.raw("Crimson core/radius is incomplete. Use /redcore and /redradius."));
            return;
        }

        BlockType coreType = world.getBlockType(corePos.x, corePos.y, corePos.z);
        if (coreType == null || !RedWaveConfig.CORE_BLOCK_ID.equals(coreType.getId())) {
            context.sendMessage(Message.raw(
                    "Core block mismatch at configured position. Expected " + RedWaveConfig.CORE_BLOCK_ID + "."
            ));
            return;
        }

        RedWaveManager.beginUndoSession(worldId);
        RedWaveManager.ActiveWave wave = RedWaveManager.startWave(worldId, corePos, radius, seconds);
        context.sendMessage(
                Message.raw(
                        "Red wave started from core "
                                + corePos.x + "," + corePos.y + "," + corePos.z
                                + " radius=" + radius
                                + " blocks over ~" + String.format("%.2f", seconds) + "s"
                                + " (" + wave.totalBlocks() + " positions)."
                )
        );
    }
}
