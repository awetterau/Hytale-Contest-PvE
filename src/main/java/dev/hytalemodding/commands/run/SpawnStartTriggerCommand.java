package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameDoorTriggerSpawner;

import javax.annotation.Nonnull;

public final class SpawnStartTriggerCommand extends AbstractPlayerCommand {
    public SpawnStartTriggerCommand() {
        super("spawnstarttrigger", "Spawn a static Start Run trigger entity at your position.");
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
        Transform transform = playerRef.getTransform();
        GameDoorTriggerSpawner.SpawnAttempt attempt = GameDoorTriggerSpawner.spawn(world, transform);
        Ref<EntityStore> spawned = attempt.getEntityRef();
        if (!attempt.isSuccess() || spawned == null || !spawned.isValid()) {
            String error = attempt.getError();
            context.sendMessage(Message.raw("Failed to spawn Start Run trigger" + (error == null ? "." : ": " + error)));
            return;
        }
        context.sendMessage(Message.raw("Spawned Start Run trigger at your position."));
    }
}
