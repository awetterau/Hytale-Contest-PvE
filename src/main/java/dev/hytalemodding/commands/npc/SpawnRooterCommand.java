package dev.hytalemodding.commands.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.rooter.RooterManManager;

import javax.annotation.Nonnull;

public class SpawnRooterCommand extends AbstractPlayerCommand {
    public SpawnRooterCommand() {
        super("spawnrooter", "Spawn a Rooter Man prototype boss at your position.");
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
        boolean spawned = RooterManManager.get().spawnBossAtPlayer(store, playerRef);
        if (!spawned) {
            context.sendMessage(Message.raw("Failed to spawn Rooter Man. Check that role assets loaded."));
            return;
        }
        context.sendMessage(Message.raw("Spawned Rooter Man prototype."));
    }
}
