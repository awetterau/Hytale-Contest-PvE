package dev.hytalemodding.commands.hub;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SetRescueSpawnCommand extends AbstractPlayerCommand {
    public SetRescueSpawnCommand() {
        super("setrescuespawn", "Set fixed rescue NPC spawn position in the run world.");
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
        Transform transform = playerRef.getTransform();
        Transform spawn = new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
        List<String> args = getPositionalTokens(context);
        if (args.isEmpty()) {
            GameFlowConfigManager.get().setRescueRunSpawn(spawn);
            context.sendMessage(Message.raw("Default rescue run spawn set at: " + spawn.getPosition()));
            return;
        }

        String npcKey = args.get(0).toLowerCase(Locale.ROOT);
        GameFlowConfigManager.get().setRescueRunSpawn(npcKey, spawn);
        context.sendMessage(Message.raw("Rescue run spawn for " + npcKey + " set at: " + spawn.getPosition()));
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
        return List.copyOf(tokens);
    }
}



