package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.GameSessionManager;

import javax.annotation.Nonnull;

public class GameResetCommand extends AbstractPlayerCommand {
    public GameResetCommand() {
        super("gamereset", "Reset run using the player's selected door zone.");
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
            context.sendMessage(Message.raw("No active run to reset. Use /gamestart to begin one."));
            return;
        }
        GameSessionManager.get().endSession().whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                context.sendMessage(Message.raw("Failed to reset run: " + reason));
                return;
            }
            if (!result.success()) {
                context.sendMessage(Message.raw("Reset cancelled: " + result.message()));
                return;
            }
            context.sendMessage(Message.raw("Run ended. Starting a new run using your selected door zone..."));
            GameDoorInteractionHandler.tryStartFromDoorSelection(playerRef);
        });
    }
}