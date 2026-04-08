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

import javax.annotation.Nonnull;

public class GameStartCommand extends AbstractPlayerCommand {
    public GameStartCommand() {
        super("gamestart", "Open door-style zone selection and start a run.");
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
        if (dev.hytalemodding.state.run.GameSessionManager.get().hasActiveSession()) {
            context.sendMessage(Message.raw("A run is already active. Use /gameend before starting another run."));
            return;
        }
        GameDoorInteractionHandler.openDoorZoneSelection(playerRef);
        context.sendMessage(Message.raw("Select a door zone to start the run."));
    }
}