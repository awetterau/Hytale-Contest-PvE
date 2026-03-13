package dev.hytalemodding.commands;

import dev.hytalemodding.redwave.RedWaveManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class RedUndoCommand extends AbstractPlayerCommand {
    public RedUndoCommand() {
        super("redundo", "Undo the last crimson conversion in this world (chunk batches every 0.5s).");
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
        UUID worldId = world.getWorldConfig().getUuid();

        RedWaveManager.UndoProcessStatus running = RedWaveManager.getUndoProcessStatus(worldId);
        if (running != null && !running.done()) {
            context.sendMessage(Message.raw(
                    "Undo already running: " + running.restoredChunks() + "/" + running.totalChunks() + " chunks restored."
            ));
            return;
        }

        RedWaveManager.clearWave(worldId);
        RedWaveManager.UndoSession undo = RedWaveManager.takeUndoSession(worldId);
        if (undo == null || undo.size() == 0) {
            context.sendMessage(Message.raw("Nothing to undo in this world."));
            return;
        }

        boolean started = RedWaveManager.beginUndoProcess(worldId, undo);
        if (!started) {
            context.sendMessage(Message.raw("Nothing to undo in this world."));
            return;
        }

        context.sendMessage(Message.raw(
                "Chunk undo started: " + undo.chunkCount() + " chunks, " + undo.size()
                        + " blocks. Processing 1 chunk every 0.5s."
        ));
    }
}
