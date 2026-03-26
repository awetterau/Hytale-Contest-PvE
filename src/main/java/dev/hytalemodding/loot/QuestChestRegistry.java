package dev.hytalemodding.loot;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestChestRegistry {
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Vector3i>> POSITIONS_BY_WORLD = new ConcurrentHashMap<>();

    private QuestChestRegistry() {
    }

    public static void register(@Nonnull UUID worldUuid, @Nonnull Vector3i pos) {
        POSITIONS_BY_WORLD.computeIfAbsent(worldUuid, ignored -> new ConcurrentHashMap<>())
                .put(buildKey(pos), new Vector3i(pos));
    }

    public static void unregister(@Nonnull UUID worldUuid, @Nonnull Vector3i pos) {
        ConcurrentHashMap<String, Vector3i> positions = POSITIONS_BY_WORLD.get(worldUuid);
        if (positions == null) {
            return;
        }
        positions.remove(buildKey(pos));
        if (positions.isEmpty()) {
            POSITIONS_BY_WORLD.remove(worldUuid);
        }
    }

    @Nonnull
    public static List<Vector3i> getPositions(@Nonnull UUID worldUuid) {
        ConcurrentHashMap<String, Vector3i> positions = POSITIONS_BY_WORLD.get(worldUuid);
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        ArrayList<Vector3i> out = new ArrayList<>(positions.size());
        for (Vector3i pos : positions.values()) {
            out.add(new Vector3i(pos));
        }
        return List.copyOf(out);
    }

    public static void clear(@Nonnull UUID worldUuid) {
        POSITIONS_BY_WORLD.remove(worldUuid);
    }

    @Nonnull
    private static String buildKey(@Nonnull Vector3i pos) {
        return pos.x + ":" + pos.y + ":" + pos.z;
    }
}
