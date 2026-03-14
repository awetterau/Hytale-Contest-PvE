package dev.hytalemodding.commands.hub;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;

public class GameConfigCommand extends AbstractPlayerCommand {
    @Nonnull
    private final OptionalArg<String> actionArg = this.withOptionalArg("action", "list|template|hub|clear", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<String> valueArg = this.withOptionalArg("value", "Action value", ArgTypes.STRING);

    public GameConfigCommand() {
        super("gameconfig", "List and manage game flow config.");
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
        GameFlowConfigManager config = GameFlowConfigManager.get();
        String action = this.actionArg.provided(context) ? this.actionArg.get(context) : "list";
        if (action == null) {
            action = "list";
        }
        action = action.trim().toLowerCase();

        switch (action) {
            case "list" -> sendConfigList(context, config);
            case "template" -> {
                String worldName = this.valueArg.provided(context) ? this.valueArg.get(context) : null;
                if (worldName == null || worldName.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /gameconfig template <worldName>"));
                    return;
                }
                config.setTemplateWorldName(worldName);
                context.sendMessage(Message.raw("Template world set to '" + config.getTemplateWorldName() + "'."));
            }
            case "hub" -> {
                String worldName = this.valueArg.provided(context) ? this.valueArg.get(context) : null;
                if (worldName == null || worldName.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /gameconfig hub <worldName>"));
                    return;
                }
                config.setHubWorldName(worldName);
                context.sendMessage(Message.raw("Hub world set to '" + config.getHubWorldName() + "'."));
            }
            case "clear" -> {
                String target = this.valueArg.provided(context) ? this.valueArg.get(context) : null;
                if (target == null || target.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /gameconfig clear <run|base|rescue|all>"));
                    return;
                }
                clearTarget(context, config, target.trim().toLowerCase());
            }
            default -> context.sendMessage(Message.raw("Unknown action. Use: /gameconfig [list|template|hub|clear]"));
        }
    }

    private static void clearTarget(@Nonnull CommandContext context, @Nonnull GameFlowConfigManager config, @Nonnull String target) {
        switch (target) {
            case "run" -> {
                config.clearRunSpawn();
                context.sendMessage(Message.raw("Run spawn cleared. Re-run /setrunspawn to set it again."));
            }
            case "base" -> {
                config.clearBaseSpawn();
                context.sendMessage(Message.raw("Base spawn cleared. Re-run /setbasespawn to set it again."));
            }
            case "rescue" -> {
                config.clearRescueRunSpawn();
                context.sendMessage(Message.raw("Rescue spawn cleared. Re-run /setrescuespawn to set it again."));
            }
            case "all" -> {
                config.clearRunSpawn();
                config.clearBaseSpawn();
                config.clearRescueRunSpawn();
                context.sendMessage(Message.raw("All spawns cleared. Set again with /setrunspawn, /setbasespawn, /setrescuespawn."));
            }
            default -> context.sendMessage(Message.raw("Unknown clear target. Use run, base, rescue, or all."));
        }
    }

    private static void sendConfigList(@Nonnull CommandContext context, @Nonnull GameFlowConfigManager config) {
        context.sendMessage(Message.raw("Game flow config:"));
        for (String line : config.describe()) {
            context.sendMessage(Message.raw(" - " + line));
        }
    }
}



