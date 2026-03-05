package dev.hytalemodding.commands;

import dev.hytalemodding.redwave.RedWaveManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class RedPos2Command extends AbstractPlayerCommand {
    public RedPos2Command() {
        super("redpos2", "Set red-wave position 2 from the block you are looking at.");
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
        Vector3i looked = new Vector3i(
                MathUtil.floor(transform.getPosition().getX()),
                MathUtil.floor(transform.getPosition().getY()) - 1,
                MathUtil.floor(transform.getPosition().getZ())
        );

        RedWaveManager.setPos2(playerRef.getUuid(), world.getWorldConfig().getUuid(), looked);
        context.sendMessage(Message.raw("Red area pos2 set: " + looked.x + ", " + looked.y + ", " + looked.z));
    }
}
