package dev.hytalemodding.commands.quest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.quest.QuestDefinitionRegistry;
import dev.hytalemodding.quest.QuestFlagManager;
import dev.hytalemodding.quest.QuestProgressManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class QuestDevCommand extends AbstractPlayerCommand {
    public QuestDevCommand() {
        super("questdev", "Quest development tools");
        this.setPermissionGroup(null);
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        List<String> args = getPositionalTokens(context);
        String action = args.isEmpty() ? "list" : args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                context.sendMessage(Message.raw("Quest definitions:"));
                for (String line : QuestProgressManager.get().describeAll()) {
                    context.sendMessage(Message.raw(" - " + line));
                }
            }
            case "accept" -> {
                if (args.size() < 2) {
                    context.sendMessage(Message.raw("Usage: /questdev accept <questId>"));
                    return;
                }
                boolean ok = QuestProgressManager.get().accept(args.get(1));
                context.sendMessage(Message.raw(ok ? "Quest accepted." : "Quest not found or already completed."));
            }
            case "complete" -> {
                if (args.size() < 2) {
                    context.sendMessage(Message.raw("Usage: /questdev complete <questId>"));
                    return;
                }
                boolean ok = QuestProgressManager.get().complete(args.get(1));
                context.sendMessage(Message.raw(ok ? "Quest completed." : "Quest not found."));
            }
            case "reset" -> {
                if (args.size() < 2) {
                    context.sendMessage(Message.raw("Usage: /questdev reset <questId>"));
                    return;
                }
                QuestProgressManager.get().reset(args.get(1));
                context.sendMessage(Message.raw("Quest reset."));
            }
            case "reload" -> {
                QuestDefinitionRegistry.get().initialize();
                context.sendMessage(Message.raw("Quest definitions are loaded (restart required to fully reload in this build)."));
            }
            case "flags" -> context.sendMessage(Message.raw("Quest flags: " + String.join(",", QuestFlagManager.get().getFlags())));
            default -> context.sendMessage(Message.raw("Usage: /questdev <list|accept|complete|reset|reload|flags> ..."));
        }
    }

    @Nonnull
    private static List<String> getPositionalTokens(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] split = input.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < split.length; i++) {
            String token = split[i];
            if (token == null || token.isBlank() || token.startsWith("--")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}




