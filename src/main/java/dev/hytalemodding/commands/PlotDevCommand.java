package dev.hytalemodding.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.game.BaseHousingManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlotDevCommand extends AbstractPlayerCommand {
    public PlotDevCommand() {
        super("plotdev", "Plot development tools");
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
        String action = args.isEmpty() ? "dump" : args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "purchase" -> setPurchased(context, args, true);
            case "unpurchase" -> setPurchased(context, args, false);
            case "setlevel" -> setLevel(context, args);
            case "settype" -> setType(context, args);
            case "dump" -> {
                context.sendMessage(Message.raw("Plots:"));
                for (String line : BaseHousingManager.get().describePlots()) {
                    context.sendMessage(Message.raw(" - " + line));
                }
            }
            default -> context.sendMessage(Message.raw("Usage: /plotdev <purchase|unpurchase|setlevel|settype|dump> ..."));
        }
    }

    private static void setPurchased(@Nonnull CommandContext context, @Nonnull List<String> args, boolean purchased) {
        if (args.size() < 2) {
            context.sendMessage(Message.raw("Usage: /plotdev " + (purchased ? "purchase" : "unpurchase") + " <plotId>"));
            return;
        }
        String plotId = args.get(1);
        BaseHousingManager.AssignmentResult result = BaseHousingManager.get().devSetPlotPurchased(plotId, purchased);
        context.sendMessage(Message.raw(result.message));
    }

    private static void setLevel(@Nonnull CommandContext context, @Nonnull List<String> args) {
        if (args.size() < 3) {
            context.sendMessage(Message.raw("Usage: /plotdev setlevel <plotId> <level>"));
            return;
        }
        String plotId = args.get(1);
        int level;
        try {
            level = Integer.parseInt(args.get(2));
        } catch (NumberFormatException ex) {
            context.sendMessage(Message.raw("Level must be a number."));
            return;
        }
        BaseHousingManager.AssignmentResult result = BaseHousingManager.get().devSetPlotBuildingLevel(plotId, level);
        context.sendMessage(Message.raw(result.message));
    }

    private static void setType(@Nonnull CommandContext context, @Nonnull List<String> args) {
        if (args.size() < 3) {
            context.sendMessage(Message.raw("Usage: /plotdev settype <plotId> <plotType>"));
            return;
        }
        String plotId = args.get(1);
        String plotType = args.get(2);
        BaseHousingManager.AssignmentResult result = BaseHousingManager.get().devSetPlotType(plotId, plotType);
        context.sendMessage(Message.raw(result.message));
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
