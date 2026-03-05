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
        super("redundo", "Undo the last crimson conversion in this world.");
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

        RedWaveManager.clearWave(worldId);
        RedWaveManager.UndoSession undo = RedWaveManager.takeUndoSession(worldId);
        if (undo == null || undo.size() == 0) {
            context.sendMessage(Message.raw("Nothing to undo in this world."));
            return;
        }

        int restored = 0;
        for (RedWaveManager.UndoEntry entry : undo.entries()) {
            world.setBlock(entry.position().x, entry.position().y, entry.position().z, entry.blockId());
            restored++;
        }

        context.sendMessage(Message.raw("Restored " + restored + " blocks from the last crimson spread."));
    }
}
