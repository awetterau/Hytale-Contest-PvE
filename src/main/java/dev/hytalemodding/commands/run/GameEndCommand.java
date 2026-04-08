package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;

public class GameEndCommand extends AbstractPlayerCommand {
    public GameEndCommand() {
        super("gameend", "Extract/finish run using the same game door extraction flow.");
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
        if (!GameSessionManager.get().hasActiveSession()) {
            context.sendMessage(Message.raw("No active run to end."));
            return;
        }
        GameFlowConfigManager config = GameFlowConfigManager.get();
        World hubWorld = resolveHubWorldOrDefault(config.getHubWorldName(), playerRef);
        Transform returnSpawn = config.getBaseSpawn();
        GameSessionManager.get().endSession(returnSpawn, hubWorld).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                context.sendMessage(Message.raw("Failed to end run: " + reason));
                return;
            }
            context.sendMessage(Message.raw(result.message()));
        });
    }

    private static World resolveHubWorldOrDefault(String hubWorldName, @Nonnull PlayerRef playerRef) {
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        if (hubWorldName != null && !hubWorldName.isBlank()) {
            World configuredHub = universe.getWorld(hubWorldName);
            if (configuredHub != null) {
                return configuredHub;
            }
        }
        if (playerRef.getWorldUuid() != null) {
            World playerWorld = universe.getWorld(playerRef.getWorldUuid());
            if (playerWorld != null) {
                return playerWorld;
            }
        }
        return universe.getDefaultWorld();
    }
}