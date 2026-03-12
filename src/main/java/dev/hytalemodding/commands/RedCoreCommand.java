package dev.hytalemodding.commands;

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
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;

public class RedCoreCommand extends AbstractPlayerCommand {
    public RedCoreCommand() {
        super("redcore", "Set crimson core at the block under your feet. Usage: /redcore");
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
        Vector3i corePos = new Vector3i(
                MathUtil.floor(transform.getPosition().getX()),
                MathUtil.floor(transform.getPosition().getY()) - 1,
                MathUtil.floor(transform.getPosition().getZ())
        );

        world.setBlock(corePos.x, corePos.y, corePos.z, RedWaveConfig.CORE_BLOCK_ID);
        RedWaveManager.setCore(playerRef.getUuid(), world.getWorldConfig().getUuid(), corePos);
        context.sendMessage(Message.raw(
                "Crimson core set and cyan wool placed at: " + corePos.x + ", " + corePos.y + ", " + corePos.z
        ));
    }
}
