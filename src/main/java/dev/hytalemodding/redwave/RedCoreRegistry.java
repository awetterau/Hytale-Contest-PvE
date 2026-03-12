package dev.hytalemodding.redwave;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedCoreRegistry {
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Vector3i>> CORES_BY_WORLD = new ConcurrentHashMap<>();

    public enum CoreSortOrder {
        BY_XYZ_ASC,
        BY_ZYX_ASC
    }

    private RedCoreRegistry() {
    }

    public static void register(@Nonnull UUID worldId, @Nonnull Vector3i position) {
        CORES_BY_WORLD
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(key(position), position);
    }

    public static void unregister(@Nonnull UUID worldId, @Nonnull Vector3i position) {
        ConcurrentHashMap<String, Vector3i> cores = CORES_BY_WORLD.get(worldId);
        if (cores == null) {
            return;
        }
        cores.remove(key(position));
        if (cores.isEmpty()) {
            CORES_BY_WORLD.remove(worldId);
        }
    }

    @Nonnull
    public static List<Vector3i> snapshot(@Nonnull UUID worldId) {
        return snapshot(worldId, CoreSortOrder.BY_XYZ_ASC);
    }

    @Nonnull
    public static List<Vector3i> snapshot(@Nonnull UUID worldId, @Nonnull CoreSortOrder sortOrder) {
        ConcurrentHashMap<String, Vector3i> cores = CORES_BY_WORLD.get(worldId);
        if (cores == null || cores.isEmpty()) {
            return List.of();
        }

        ArrayList<Vector3i> snapshot = new ArrayList<>(cores.values());
        Comparator<Vector3i> comparator = sortOrder == CoreSortOrder.BY_ZYX_ASC
                ? Comparator.comparingInt((Vector3i v) -> v.z).thenComparingInt(v -> v.y).thenComparingInt(v -> v.x)
                : Comparator.comparingInt((Vector3i v) -> v.x).thenComparingInt(v -> v.y).thenComparingInt(v -> v.z);
        snapshot.sort(comparator);
        return snapshot;
    }

    @Nonnull
    private static String key(@Nonnull Vector3i pos) {
        return pos.x + ":" + pos.y + ":" + pos.z;
    }
}