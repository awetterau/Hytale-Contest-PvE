package dev.hytalemodding.commands;

import dev.hytalemodding.redwave.RedWaveManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
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
        super("redstart", "Start red-wave sweep for selected area.");
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
            context.sendMessage(Message.raw("Select area first with /redpos1 and /redpos2."));
            return;
        }

        UUID worldId = world.getWorldConfig().getUuid();
        if (!worldId.equals(selection.worldId())) {
            context.sendMessage(Message.raw("Selection was made in a different world. Re-select here with /redpos1 and /redpos2."));
            return;
        }

        Vector3i pos1 = selection.pos1();
        Vector3i pos2 = selection.pos2();
        if (pos1 == null || pos2 == null) {
            context.sendMessage(Message.raw("Selection is incomplete. Use /redpos1 and /redpos2."));
            return;
        }

        RedWaveManager.beginUndoSession(worldId);
        RedWaveManager.ActiveWave wave = RedWaveManager.startWave(worldId, pos1, pos2, seconds);
        context.sendMessage(
                Message.raw(
                        "Red wave started: "
                                + wave.totalBlocks()
                                + " blocks over ~"
                                + String.format("%.2f", seconds)
                                + "s."
                )
        );
    }
}
