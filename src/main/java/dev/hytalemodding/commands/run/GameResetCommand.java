package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.run.GameSessionManager;

import javax.annotation.Nonnull;

public class GameResetCommand extends AbstractPlayerCommand {
    @Nonnull
    private final OptionalArg<String> templateWorldArg = this.withOptionalArg("template", "Template world name", ArgTypes.STRING);

    public GameResetCommand() {
        super("gamereset", "End current run (if any) and start a new run from template world.");
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
        String templateWorldName = this.templateWorldArg.provided(context)
                ? this.templateWorldArg.get(context)
                : GameFlowConfigManager.get().getTemplateWorldName();
        World templateWorld = Universe.get().getWorld(templateWorldName);
        if (templateWorld == null) {
            context.sendMessage(Message.raw("Template world not loaded: " + templateWorldName));
            return;
        }

        GameSessionManager.get().resetSession(playerRef, templateWorld).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                context.sendMessage(Message.raw("Failed to reset run: " + reason));
                return;
            }

            context.sendMessage(
                    Message.raw(
                            "Run reset complete. Active world: '"
                                    + result.runWorldName()
                                    + "'. Crimson timer target: "
                                    + result.crimsonStartAtEpochMillis()
                    )
            );
        });
    }
}



