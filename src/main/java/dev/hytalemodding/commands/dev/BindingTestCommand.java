package dev.hytalemodding.commands.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.potion.PotionBrewerWitchBindingSystem;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class BindingTestCommand extends AbstractPlayerCommand {
    public BindingTestCommand() {
        super("bindingtest", "Spawn a Binding Potion test effect: self or thrown.");
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
        String mode = args.isEmpty() ? "self" : args.get(0).toLowerCase(Locale.ROOT);
        Vector3d origin = new Vector3d(playerRef.getTransform().getPosition());

        if ("thrown".equals(mode)) {
            PotionBrewerWitchBindingSystem.spawnDebugThrownBindingZone(world, origin);
            context.sendMessage(Message.raw("Spawned thrown-style binding zone at " + origin + "."));
            return;
        }

        if ("self".equals(mode)) {
            UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
            UUID ownerId = uuidComponent == null ? null : uuidComponent.getUuid();
            if (ownerId == null) {
                context.sendMessage(Message.raw("Could not resolve your player UUID for binding self-test."));
                return;
            }
            PotionBrewerWitchBindingSystem.spawnDebugSelfBindingZone(world, ownerId, ref, origin);
            context.sendMessage(Message.raw("Spawned self-style binding zone that follows you briefly."));
            return;
        }

        context.sendMessage(Message.raw("Usage: /bindingtest <self|thrown>"));
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
