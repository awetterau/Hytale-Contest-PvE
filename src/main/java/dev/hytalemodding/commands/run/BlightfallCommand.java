package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.ui.dev.BlightfallMainPage;

import javax.annotation.Nonnull;

public class BlightfallCommand extends AbstractPlayerCommand {
    @Nonnull
    private final OptionalArg<String> arg1 = this.withOptionalArg("arg1", "Primary action", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<String> arg2 = this.withOptionalArg("arg2", "Secondary action", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<String> arg3 = this.withOptionalArg("arg3", "Optional template world", ArgTypes.STRING);

    public BlightfallCommand() {
        super("blightfall", "Main Blightfall command and control panel.");
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
        String first = this.arg1.provided(context) ? this.arg1.get(context).trim().toLowerCase() : "";
        String second = this.arg2.provided(context) ? this.arg2.get(context).trim().toLowerCase() : "";
        String third = this.arg3.provided(context) ? this.arg3.get(context).trim() : "";

        if (first.isBlank()) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                context.sendMessage(Message.raw("Unable to open Blightfall panel right now."));
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new BlightfallMainPage(playerRef));
            return;
        }

        if ("stop".equals(first) && "run".equals(second)) {
            if (!GameDoorInteractionHandler.handleDoorEntityTrigger(playerRef)) {
                context.sendMessage(Message.raw("Unable to stop run here. Use while in-run (same extraction flow as game door)."));
            }
            return;
        }

        if ("run".equals(first) && "start".equals(second)) {
            startRun(context, playerRef, third);
            return;
        }

        if ("run".equals(first) && "reset".equals(second)) {
            resetRun(context, playerRef, third);
            return;
        }

        context.sendMessage(Message.raw("Usage: /blightfall | /blightfall stop run | /blightfall run start [templateWorld] | /blightfall run reset [templateWorld]"));
    }

    private void startRun(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nonnull String requestedTemplate) {
        GameDoorInteractionHandler.openDoorZoneSelection(playerRef);
        context.sendMessage(Message.raw("Select a door zone to start the run."));
    }

    private void resetRun(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nonnull String requestedTemplate) {
        GameSessionManager.get().endSession().whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                context.sendMessage(Message.raw("Failed to reset run: " + reason));
                return;
            }
            context.sendMessage(Message.raw("Run ended. Starting a new run using your selected door zone..."));
            GameDoorInteractionHandler.tryStartFromDoorSelection(playerRef);
        });
    }
}