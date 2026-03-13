package dev.hytalemodding.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.game.GameFlowConfigManager;

import javax.annotation.Nonnull;

public class SetRescueSpawnCommand extends AbstractPlayerCommand {
    public SetRescueSpawnCommand() {
        super("setrescuespawn", "Set fixed rescue NPC spawn position in the run world.");
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
        Transform spawn = new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
        GameFlowConfigManager.get().setRescueRunSpawn(spawn);
        context.sendMessage(Message.raw("Rescue run spawn set at: " + spawn.getPosition()));
    }
}
