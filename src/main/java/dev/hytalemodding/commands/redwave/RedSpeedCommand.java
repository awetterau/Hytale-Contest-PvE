package dev.hytalemodding.commands.redwave;

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
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;

public class RedSpeedCommand extends AbstractPlayerCommand {
    @Nonnull
    private final RequiredArg<Integer> speedArg = this.withRequiredArg("blocksPerTick", "Spread speed (blocks per tick)", ArgTypes.INTEGER);

    public RedSpeedCommand() {
        super("redspeed", "Set crimson spread speed (blocks per tick).");
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
        Integer speed = this.speedArg.get(context);
        if (speed == null || speed < 1 || speed > 5000) {
            context.sendMessage(Message.raw("Speed must be between 1 and 5000 blocks per tick."));
            return;
        }

        RedWaveManager.setWorldSpreadSpeed(world.getWorldConfig().getUuid(), speed);
        context.sendMessage(Message.raw("Crimson speed set to " + speed + " blocks/tick."));
    }
}