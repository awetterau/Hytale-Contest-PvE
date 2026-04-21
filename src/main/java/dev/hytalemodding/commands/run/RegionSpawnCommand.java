package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.RegionSpawnConfig;
import dev.hytalemodding.state.transition.RegionSpawnMarkerConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RegionSpawnCommand extends AbstractPlayerCommand {
    private static final int DEFAULT_SCAN_RADIUS_CHUNKS = 1;
    private static final int MIN_SCAN_Y = 0;
    private static final int MAX_SCAN_Y_EXCLUSIVE = 320;

    private final RequiredArg<String> actionArg = this.withRequiredArg(
            "action",
            "add|list|remove-nearest|scan-nearby",
            ArgTypes.STRING
    );

    public RegionSpawnCommand() {
        super("regionspawn", "Manage saved mob region spawn markers.");
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
        String action = this.actionArg.get(context).trim().toLowerCase(Locale.ROOT);
        List<String> args = getPositionalTokens(context);
        switch (action) {
            case "add" -> addMarker(context, playerRef, world, args);
            case "list" -> listMarkers(context, world);
            case "remove-nearest" -> removeNearest(context, playerRef, world);
            case "scan-nearby" -> scanNearby(context, playerRef, world, args);
            default -> sendUsage(context);
        }
    }

    private static void addMarker(
            @Nonnull CommandContext context,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world,
            @Nonnull List<String> args
    ) {
        if (args.size() < 2) {
            context.sendMessage(Message.raw("Usage: /regionspawn add <region> [x y z]"));
            return;
        }

        String regionId = normalize(args.get(1));
        RegionSpawnConfig.RegionDefinition region = RegionSpawnConfig.get().getRegion(regionId);
        if (region == null) {
            context.sendMessage(Message.raw("Unknown region: " + regionId));
            return;
        }

        Vector3i pos;
        if (args.size() >= 5) {
            pos = parsePos(args.get(2), args.get(3), args.get(4));
            if (pos == null) {
                context.sendMessage(Message.raw("Coordinates must be integers."));
                return;
            }
        } else {
            Transform transform = playerRef.getTransform();
            pos = new Vector3i(
                    MathUtil.floor(transform.getPosition().getX()),
                    MathUtil.floor(transform.getPosition().getY()) - 1,
                    MathUtil.floor(transform.getPosition().getZ())
            );
        }

        world.setBlock(pos.x, pos.y, pos.z, region.markerBlock());
        boolean added = RegionSpawnMarkerConfigManager.addMarker(
                world.getName(),
                new RegionSpawnMarkerConfigManager.RegionMarkerEntry(region.id(), new Vector3i(pos.x, pos.y, pos.z), world.getName())
        );
        context.sendMessage(Message.raw((added ? "Saved" : "Already saved")
                + " region spawn marker " + region.id()
                + " at " + format(pos)
                + " in world=" + world.getName()));
    }

    private static void listMarkers(@Nonnull CommandContext context, @Nonnull World world) {
        List<RegionSpawnMarkerConfigManager.RegionMarkerEntry> entries = RegionSpawnMarkerConfigManager.load(world.getName());
        if (entries.isEmpty()) {
            context.sendMessage(Message.raw("No region spawn markers saved for world=" + world.getName()));
            return;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RegionSpawnMarkerConfigManager.RegionMarkerEntry entry : entries) {
            counts.merge(entry.regionId(), 1, Integer::sum);
        }

        StringBuilder out = new StringBuilder("Region spawn markers for world=")
                .append(world.getName())
                .append(" total=")
                .append(entries.size());
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            out.append("\n").append(count.getKey()).append(": ").append(count.getValue());
        }
        context.sendMessage(Message.raw(out.toString()));
    }

    private static void removeNearest(
            @Nonnull CommandContext context,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        List<RegionSpawnMarkerConfigManager.RegionMarkerEntry> entries = RegionSpawnMarkerConfigManager.load(world.getName());
        if (entries.isEmpty()) {
            context.sendMessage(Message.raw("No region spawn markers saved for world=" + world.getName()));
            return;
        }

        Transform transform = playerRef.getTransform();
        double px = transform.getPosition().getX();
        double py = transform.getPosition().getY();
        double pz = transform.getPosition().getZ();
        int bestIndex = -1;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            Vector3i pos = entries.get(i).position();
            double dx = pos.x + 0.5d - px;
            double dy = pos.y + 0.5d - py;
            double dz = pos.z + 0.5d - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistanceSq) {
                bestDistanceSq = distSq;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            context.sendMessage(Message.raw("No region spawn markers found."));
            return;
        }

        ArrayList<RegionSpawnMarkerConfigManager.RegionMarkerEntry> next = new ArrayList<>(entries);
        RegionSpawnMarkerConfigManager.RegionMarkerEntry removed = next.remove(bestIndex);
        RegionSpawnMarkerConfigManager.save(world.getName(), next);
        Vector3i pos = removed.position();
        BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
        RegionSpawnConfig.RegionDefinition region = RegionSpawnConfig.get().getRegion(removed.regionId());
        if (type != null && region != null && region.markerBlock().equalsIgnoreCase(type.getId())) {
            world.setBlock(pos.x, pos.y, pos.z, "Empty");
        }
        context.sendMessage(Message.raw("Removed nearest region spawn marker "
                + removed.regionId() + " at " + format(pos) + "."));
    }

    private static void scanNearby(
            @Nonnull CommandContext context,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world,
            @Nonnull List<String> args
    ) {
        int radiusChunks = DEFAULT_SCAN_RADIUS_CHUNKS;
        if (args.size() >= 2) {
            Integer parsed = parseInt(args.get(1));
            if (parsed == null) {
                context.sendMessage(Message.raw("Radius must be an integer chunk radius."));
                return;
            }
            radiusChunks = Math.max(0, parsed);
        }

        Transform transform = playerRef.getTransform();
        int centerChunkX = Math.floorDiv(MathUtil.floor(transform.getPosition().getX()), 32);
        int centerChunkZ = Math.floorDiv(MathUtil.floor(transform.getPosition().getZ()), 32);
        int scanned = 0;
        int added = 0;

        for (int chunkX = centerChunkX - radiusChunks; chunkX <= centerChunkX + radiusChunks; chunkX++) {
            for (int chunkZ = centerChunkZ - radiusChunks; chunkZ <= centerChunkZ + radiusChunks; chunkZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    int worldX = (chunkX * 32) + localX;
                    for (int localZ = 0; localZ < 32; localZ++) {
                        int worldZ = (chunkZ * 32) + localZ;
                        for (int y = MIN_SCAN_Y; y < MAX_SCAN_Y_EXCLUSIVE; y++) {
                            BlockType type = world.getBlockType(worldX, y, worldZ);
                            String regionId = RegionSpawnConfig.get().findRegionForMarkerBlock(type == null ? null : type.getId());
                            if (regionId == null) {
                                continue;
                            }
                            scanned++;
                            boolean markerAdded = RegionSpawnMarkerConfigManager.addMarker(
                                    world.getName(),
                                    new RegionSpawnMarkerConfigManager.RegionMarkerEntry(
                                            regionId,
                                            new Vector3i(worldX, y, worldZ),
                                            world.getName()
                                    )
                            );
                            if (markerAdded) {
                                added++;
                            }
                        }
                    }
                }
            }
        }

        context.sendMessage(Message.raw("Scanned nearby region markers in chunk radius=" + radiusChunks
                + " found=" + scanned
                + " added=" + added
                + " world=" + world.getName()));
    }

    private static void sendUsage(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /regionspawn <add|list|remove-nearest|scan-nearby> ..."));
    }

    @Nullable
    private static Vector3i parsePos(@Nonnull String xRaw, @Nonnull String yRaw, @Nonnull String zRaw) {
        Integer x = parseInt(xRaw);
        Integer y = parseInt(yRaw);
        Integer z = parseInt(zRaw);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Vector3i(x, y, z);
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
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
