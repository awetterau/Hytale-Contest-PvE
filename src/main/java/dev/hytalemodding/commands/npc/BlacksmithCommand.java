package dev.hytalemodding.commands.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.RescueObjectiveManager;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public class BlacksmithCommand extends AbstractPlayerCommand {
    @Nonnull
    private final OptionalArg<String> actionArg = this.withOptionalArg("action", "status|setspawn|spawn|rescued|reset|resetall", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<String> valueArg = this.withOptionalArg("value", "Action value", ArgTypes.STRING);

    public BlacksmithCommand() {
        super("blacksmith", "Blacksmith rescue dev tools.");
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
        List<String> positional = getPositionalTokens(context);
        String action = this.actionArg.provided(context) ? this.actionArg.get(context) : (positional.isEmpty() ? "status" : positional.get(0));
        if (action == null) {
            action = "status";
        }
        action = action.trim().toLowerCase();
        String rawValue = this.valueArg.provided(context)
                ? this.valueArg.get(context)
                : (positional.size() > 1 ? positional.get(1) : "");

        switch (action) {
            case "status" -> status(context);
            case "setspawn" -> setSpawn(context, playerRef, rawValue);
            case "spawn" -> spawn(context, playerRef, rawValue);
            case "rescued" -> setRescued(context, rawValue);
            case "reset" -> reset(context);
            case "resetall" -> resetAll(context, world);
            default -> context.sendMessage(Message.raw("Usage: /blacksmith <status|setspawn|spawn|rescued|reset|resetall> [value]"));
        }
    }

    private static void status(@Nonnull CommandContext context) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        RescueObjectiveManager rescue = RescueObjectiveManager.get();
        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();

        context.sendMessage(Message.raw("Blacksmith status:"));
        context.sendMessage(Message.raw(" - rescued: " + rescue.isBlacksmithRescued()));
        context.sendMessage(Message.raw(" - pendingBaseSpawn: " + rescue.isPendingBaseSpawn()));
        context.sendMessage(Message.raw(" - baseSpawnInProgress: " + rescue.isBaseSpawnInProgress()));
        context.sendMessage(Message.raw(" - templateWorld: " + config.getTemplateWorldName()));
        context.sendMessage(Message.raw(" - hubWorld: " + config.getHubWorldName()));
        context.sendMessage(Message.raw(" - runSpawnSet: " + (config.getRunSpawn() != null)));
        context.sendMessage(Message.raw(" - baseSpawnSet: " + (config.getBaseSpawn() != null)));
        context.sendMessage(Message.raw(" - rescueRunSpawnSet: " + (config.getRescueRunSpawn() != null)));

        if (session == null || session.runWorldUuid() == null) {
            context.sendMessage(Message.raw(" - activeRun: none"));
            return;
        }
        UUID runWorldUuid = session.runWorldUuid();
        context.sendMessage(Message.raw(" - activeRunWorld: " + session.runWorldName() + " (" + runWorldUuid + ")"));
        context.sendMessage(Message.raw(" - objectiveState: " + rescue.getState(runWorldUuid)));
        context.sendMessage(Message.raw(" - objectiveNpcPresent: " + rescue.hasObjectiveNpc(runWorldUuid)));
    }

    private static void setSpawn(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nonnull String targetRaw) {
        String target = targetRaw.trim().toLowerCase();
        if (target.isEmpty()) {
            context.sendMessage(Message.raw("Usage: /blacksmith setspawn <run|base|rescue>"));
            return;
        }
        Transform transform = playerRef.getTransform();
        Transform copy = new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
        GameFlowConfigManager config = GameFlowConfigManager.get();
        switch (target) {
            case "run" -> {
                config.setRunSpawn(copy);
                context.sendMessage(Message.raw("Blacksmith run spawn set: " + copy.getPosition()));
            }
            case "base" -> {
                config.setBaseSpawn(copy);
                context.sendMessage(Message.raw("Blacksmith base spawn set: " + copy.getPosition()));
            }
            case "rescue" -> {
                config.setRescueRunSpawn(copy);
                context.sendMessage(Message.raw("Blacksmith rescue-run spawn set: " + copy.getPosition()));
            }
            default -> context.sendMessage(Message.raw("Unknown target. Use run, base, or rescue."));
        }
    }

    private static void spawn(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nonnull String targetRaw) {
        String target = targetRaw.trim().toLowerCase();
        if (target.isEmpty()) {
            context.sendMessage(Message.raw("Usage: /blacksmith spawn <run|base>"));
            return;
        }
        RescueObjectiveManager rescue = RescueObjectiveManager.get();
        if ("run".equals(target)) {
            GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
            if (session == null || session.runWorldUuid() == null) {
                context.sendMessage(Message.raw("No active run world."));
                return;
            }
            World runWorld = Universe.get().getWorld(session.runWorldUuid());
            if (runWorld == null) {
                context.sendMessage(Message.raw("Run world not loaded."));
                return;
            }
            rescue.spawnRescueOnRunStart(runWorld, session);
            context.sendMessage(Message.raw("Requested rescue objective spawn in run world."));
            return;
        }
        if ("base".equals(target)) {
            GameFlowConfigManager config = GameFlowConfigManager.get();
            World hubWorld = Universe.get().getWorld(config.getHubWorldName());
            if (hubWorld == null) {
                context.sendMessage(Message.raw("Hub world not loaded: " + config.getHubWorldName()));
                return;
            }
            Transform baseSpawn = config.getBaseSpawn();
            if (baseSpawn == null) {
                Transform playerTransform = playerRef.getTransform();
                baseSpawn = new Transform(new Vector3d(playerTransform.getPosition()), new Vector3f(playerTransform.getRotation()));
            }
            boolean success = rescue.spawnBaseBlacksmithNow(hubWorld, baseSpawn);
            context.sendMessage(Message.raw(success
                    ? "Spawned base blacksmith and marked rescued."
                    : "Could not spawn base blacksmith (already rescued or spawn failed)."));
            return;
        }
        context.sendMessage(Message.raw("Unknown target. Use run or base."));
    }

    private static void setRescued(@Nonnull CommandContext context, @Nonnull String valueRaw) {
        String value = valueRaw.trim().toLowerCase();
        if (!"true".equals(value) && !"false".equals(value)) {
            context.sendMessage(Message.raw("Usage: /blacksmith rescued <true|false>"));
            return;
        }
        boolean rescued = Boolean.parseBoolean(value);
        RescueObjectiveManager.get().setBlacksmithRescued(rescued);
        context.sendMessage(Message.raw("Blacksmith rescued set to " + rescued + "."));
    }

    private static void reset(@Nonnull CommandContext context) {
        RescueObjectiveManager.get().resetBlacksmithProgress();
        context.sendMessage(Message.raw("Blacksmith rescue progress reset."));
    }

    private static void resetAll(@Nonnull CommandContext context, @Nonnull World world) {
        RescueObjectiveManager.get().resetBlacksmithProgress();
        int removed = BaseHousingManager.get().removeAllBaseBlacksmithsInWorld(world);
        context.sendMessage(Message.raw("Blacksmith full reset complete. Rescued=false, runtime cleared, base blacksmith removed in this world: " + removed));
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



