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

public class RedLimitCommand extends AbstractPlayerCommand {
    @Nonnull
    private final RequiredArg<Integer> limitArg = this.withRequiredArg("limit", "Frontier limit for corruption wave", ArgTypes.INTEGER);

    public RedLimitCommand() {
        super("redlimit", "Set crimson frontier limit.");
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
        Integer limit = this.limitArg.get(context);
        if (limit == null || limit < 512 || limit > 5000000) {
            context.sendMessage(Message.raw("Limit must be between 512 and 5000000."));
            return;
        }

        RedWaveManager.setWorldFrontierLimit(world.getWorldConfig().getUuid(), limit);
        context.sendMessage(Message.raw("Crimson frontier limit set to " + limit + "."));
    }
}