package dev.hytalemodding.commands.quest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.loot.QuestChestConfigManager;
import dev.hytalemodding.loot.QuestChestPositionManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SetQuestChestCommand extends AbstractPlayerCommand {
    private static final int SEARCH_RADIUS = 6;

    public SetQuestChestCommand() {
        super("setquestchest", "Set or clear a saved quest chest position.");
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
        if (args.isEmpty()) {
            context.sendMessage(Message.raw("Usage: /setquestchest <chestId> [x y z|clear]"));
            return;
        }

        String chestId = args.get(0);
        QuestChestConfigManager.QuestChestDefinition definition = QuestChestConfigManager.get().getByChestId(chestId);
        if (definition == null) {
            context.sendMessage(Message.raw("Unknown quest chest: " + chestId));
            return;
        }
        if (!definition.templateWorldName().equalsIgnoreCase(world.getName())) {
            context.sendMessage(Message.raw("Use this in template world '" + definition.templateWorldName() + "'."));
            return;
        }

        if (args.size() >= 2 && "clear".equalsIgnoreCase(args.get(1))) {
            QuestChestPositionManager.get().clearPosition(definition.chestId(), definition.templateWorldName());
            context.sendMessage(Message.raw("Cleared quest chest '" + definition.chestId() + "'."));
            return;
        }

        Vector3i pos;
        if (args.size() >= 4) {
            Integer x = parseInt(args.get(1));
            Integer y = parseInt(args.get(2));
            Integer z = parseInt(args.get(3));
            if (x == null || y == null || z == null) {
                context.sendMessage(Message.raw("Coordinates must be integers."));
                return;
            }
            pos = new Vector3i(x, y, z);
        } else {
            pos = findNearestChest(world, playerRef.getTransform().getPosition(), definition.blockId());
            if (pos == null) {
                context.sendMessage(Message.raw("No '" + definition.blockId() + "' chest found within " + SEARCH_RADIUS + " blocks."));
                return;
            }
        }

        BlockType blockType = world.getBlockType(pos);
        if (blockType == null || !definition.blockId().equalsIgnoreCase(blockType.getId())) {
            context.sendMessage(Message.raw("Target block at " + format(pos) + " is not '" + definition.blockId() + "'."));
            return;
        }

        QuestChestPositionManager.get().setPosition(definition.chestId(), definition.templateWorldName(), pos);
        context.sendMessage(Message.raw("Saved quest chest '" + definition.chestId() + "' at " + format(pos) + "."));
    }

    @Nullable
    private static Vector3i findNearestChest(@Nonnull World world, @Nonnull Vector3d center, @Nonnull String blockId) {
        Vector3i best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int cx = (int) Math.floor(center.getX());
        int cy = (int) Math.floor(center.getY());
        int cz = (int) Math.floor(center.getZ());

        for (int x = cx - SEARCH_RADIUS; x <= cx + SEARCH_RADIUS; x++) {
            for (int y = cy - SEARCH_RADIUS; y <= cy + SEARCH_RADIUS; y++) {
                for (int z = cz - SEARCH_RADIUS; z <= cz + SEARCH_RADIUS; z++) {
                    BlockType blockType = world.getBlockType(x, y, z);
                    if (blockType == null || !blockId.equalsIgnoreCase(blockType.getId())) {
                        continue;
                    }
                    double dx = x - center.getX();
                    double dy = y - center.getY();
                    double dz = z - center.getZ();
                    double distanceSq = dx * dx + dy * dy + dz * dz;
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = new Vector3i(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    private static Integer parseInt(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String format(@Nonnull Vector3i pos) {
        return "(" + pos.x + ", " + pos.y + ", " + pos.z + ")";
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
