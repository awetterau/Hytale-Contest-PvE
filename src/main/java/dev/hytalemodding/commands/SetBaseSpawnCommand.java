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

public class SetBaseSpawnCommand extends AbstractPlayerCommand {
    public SetBaseSpawnCommand() {
        super("setbasespawn", "Set spawn position used when extracting back to base.");
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
        GameFlowConfigManager.get().setBaseSpawn(world.getWorldConfig().getUuid(), spawn);
        context.sendMessage(Message.raw("Base spawn set at: " + spawn.getPosition()));
    }
}
