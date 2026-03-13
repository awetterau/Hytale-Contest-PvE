package dev.hytalemodding.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;

public class RedRadiusCommand extends AbstractPlayerCommand {
    @Nonnull
    private final RequiredArg<Integer> radiusArg = this.withRequiredArg("blocks", "Radius in blocks around the core", ArgTypes.INTEGER);

    public RedRadiusCommand() {
        super("redradius", "Set crimson expansion radius around the core block.");
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
        Integer radius = this.radiusArg.get(context);
        if (radius == null || radius < RedWaveConfig.MIN_RADIUS_BLOCKS || radius > RedWaveConfig.MAX_RADIUS_BLOCKS) {
            context.sendMessage(Message.raw(
                    "Radius must be between " + RedWaveConfig.MIN_RADIUS_BLOCKS + " and " + RedWaveConfig.MAX_RADIUS_BLOCKS + "."
            ));
            return;
        }

        RedWaveManager.setRadius(playerRef.getUuid(), world.getWorldConfig().getUuid(), radius);
        context.sendMessage(Message.raw("Crimson radius set to " + radius + " blocks around core."));
    }
}
