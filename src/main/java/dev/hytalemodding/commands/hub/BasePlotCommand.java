package dev.hytalemodding.commands.hub;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.state.run.RescueObjectiveManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class BasePlotCommand extends AbstractPlayerCommand {
    @Nonnull
    private final OptionalArg<String> actionArg = this.withOptionalArg("action", "list|add|remove|sethome|settype|clearassign|resetall", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<String> idArg = this.withOptionalArg("id", "Plot id", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<Integer> xArg = this.withOptionalArg("x", "X", ArgTypes.INTEGER);
    @Nonnull
    private final OptionalArg<Integer> yArg = this.withOptionalArg("y", "Y", ArgTypes.INTEGER);
    @Nonnull
    private final OptionalArg<Integer> zArg = this.withOptionalArg("z", "Z", ArgTypes.INTEGER);

    public BasePlotCommand() {
        super("baseplot", "Manage base housing plots.");
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
        BaseHousingManager manager = BaseHousingManager.get();
        List<String> positional = getPositionalTokens(context);
        String action = this.actionArg.provided(context) ? this.actionArg.get(context) : (positional.isEmpty() ? "list" : positional.get(0));
        if (action == null) {
            action = "list";
        }
        action = action.trim().toLowerCase();

        switch (action) {
            case "list" -> {
                context.sendMessage(Message.raw("Base plots:"));
                for (String line : manager.describePlots()) {
                    context.sendMessage(Message.raw(" - " + line));
                }
            }
            case "add" -> {
                String id = this.idArg.provided(context) ? this.idArg.get(context) : (positional.size() > 1 ? positional.get(1) : null);
                if (id == null || id.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /baseplot add <id> [x y z]"));
                    return;
                }
                Vector3i marker = resolveMarker(playerRef, context, positional);
                Transform playerTransform = playerRef.getTransform();
                Transform home = new Transform(
                        new Vector3d(marker.x + 0.5, marker.y + 1.0, marker.z + 0.5),
                        new Vector3f(playerTransform.getRotation())
                );
                boolean created = manager.addOrUpdatePlot(id.trim(), world.getName(), marker, home);
                context.sendMessage(Message.raw((created ? "Created" : "Updated") + " plot '" + id + "' at "
                        + marker.x + ", " + marker.y + ", " + marker.z));
            }
            case "remove" -> {
                String id = this.idArg.provided(context) ? this.idArg.get(context) : (positional.size() > 1 ? positional.get(1) : null);
                if (id == null || id.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /baseplot remove <id>"));
                    return;
                }
                boolean ok = manager.removePlot(id.trim());
                context.sendMessage(Message.raw(ok ? "Removed plot '" + id + "'." : "Plot not found: " + id));
            }
            case "sethome" -> {
                String id = this.idArg.provided(context) ? this.idArg.get(context) : (positional.size() > 1 ? positional.get(1) : null);
                if (id == null || id.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /baseplot sethome <id>"));
                    return;
                }
                Transform transform = playerRef.getTransform();
                Transform home = new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
                boolean ok = manager.setPlotHome(id.trim(), home);
                context.sendMessage(Message.raw(ok ? "Home set for plot '" + id + "'." : "Plot not found: " + id));
            }
            case "settype" -> {
                String id = this.idArg.provided(context) ? this.idArg.get(context) : (positional.size() > 1 ? positional.get(1) : null);
                String plotType = positional.size() > 2 ? positional.get(2) : null;
                if (id == null || id.isBlank() || plotType == null || plotType.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /baseplot settype <id> <plotType>"));
                    return;
                }
                BaseHousingManager.AssignmentResult result = manager.setPlotType(id.trim(), plotType.trim());
                context.sendMessage(Message.raw(result.message));
            }
            case "clearassign" -> {
                String id = this.idArg.provided(context) ? this.idArg.get(context) : (positional.size() > 1 ? positional.get(1) : null);
                if (id == null || id.isBlank()) {
                    context.sendMessage(Message.raw("Usage: /baseplot clearassign <id>"));
                    return;
                }
                boolean ok = manager.clearAssignment(id.trim());
                context.sendMessage(Message.raw(ok ? "Assignment cleared for plot '" + id + "'." : "Plot not found: " + id));
            }
            case "resetall" -> {
                manager.resetAll();
                RescueObjectiveManager.get().resetRuntimeStatePreserveRescued();
                context.sendMessage(Message.raw("Reset complete: cleared plots/assignments and transient rescue runtime state. Rescued status was preserved."));
            }
            default -> context.sendMessage(Message.raw("Usage: /baseplot <list|add|remove|sethome|settype|clearassign|resetall> ..."));
        }
    }

    @Nonnull
    private Vector3i resolveMarker(@Nonnull PlayerRef playerRef, @Nonnull CommandContext context, @Nonnull List<String> positional) {
        if (this.xArg.provided(context) && this.yArg.provided(context) && this.zArg.provided(context)) {
            return new Vector3i(this.xArg.get(context), this.yArg.get(context), this.zArg.get(context));
        }
        if (positional.size() >= 5) {
            try {
                return new Vector3i(
                        Integer.parseInt(positional.get(2)),
                        Integer.parseInt(positional.get(3)),
                        Integer.parseInt(positional.get(4))
                );
            } catch (NumberFormatException ignored) {
            }
        }
        Transform transform = playerRef.getTransform();
        return new Vector3i(
                MathUtil.floor(transform.getPosition().getX()),
                MathUtil.floor(transform.getPosition().getY()) - 1,
                MathUtil.floor(transform.getPosition().getZ())
        );
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



