package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameDoorTriggerRegistry {
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Vector3i>> DOORS_BY_WORLD = new ConcurrentHashMap<>();

    private GameDoorTriggerRegistry() {
    }

    public static void register(@Nonnull UUID worldId, @Nonnull Vector3i position) {
        DOORS_BY_WORLD
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(key(position), new Vector3i(position.x, position.y, position.z));
    }

    public static void unregister(@Nonnull UUID worldId, @Nonnull Vector3i position) {
        ConcurrentHashMap<String, Vector3i> doors = DOORS_BY_WORLD.get(worldId);
        if (doors == null) {
            return;
        }
        doors.remove(key(position));
        if (doors.isEmpty()) {
            DOORS_BY_WORLD.remove(worldId);
        }
    }

    @Nonnull
    public static List<Vector3i> snapshot(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, Vector3i> doors = DOORS_BY_WORLD.get(worldId);
        if (doors == null || doors.isEmpty()) {
            return List.of();
        }
        ArrayList<Vector3i> snapshot = new ArrayList<>(doors.values());
        snapshot.sort(Comparator.comparingInt((Vector3i v) -> v.x).thenComparingInt(v -> v.y).thenComparingInt(v -> v.z));
        return snapshot;
    }

    @Nonnull
    private static String key(@Nonnull Vector3i pos) {
        return pos.x + ":" + pos.y + ":" + pos.z;
    }
}
