package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blob.OrangeBlobBlockManager;

import javax.annotation.Nonnull;

public final class SpawnExtractionPrototypeCommand extends AbstractPlayerCommand {
    public SpawnExtractionPrototypeCommand() {
        super("spawnextractionprototype", "Create a working extraction island prototype at your position.");
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
        Vector3i center = new Vector3i(
                (int) Math.floor(playerRef.getTransform().getPosition().getX()),
                (int) Math.floor(playerRef.getTransform().getPosition().getY()),
                (int) Math.floor(playerRef.getTransform().getPosition().getZ())
        );
        OrangeBlobBlockManager.createPrototypeAt(world, center);
        context.sendMessage(Message.raw("Spawned extraction prototype centered at " + center + ". Rune placed one block above the island center."));
    }
}
