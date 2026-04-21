package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionSpawnMarkerRegistry {
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Marker>> MARKERS_BY_WORLD = new ConcurrentHashMap<>();

    private RegionSpawnMarkerRegistry() {
    }

    public static void register(@Nonnull UUID worldId, @Nonnull String regionId, @Nonnull Vector3i position) {
        MARKERS_BY_WORLD
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(key(position), new Marker(regionId, new Vector3i(position.x, position.y, position.z)));
    }

    public static void unregister(@Nonnull UUID worldId, @Nonnull Vector3i position) {
        ConcurrentHashMap<String, Marker> markers = MARKERS_BY_WORLD.get(worldId);
        if (markers == null) {
            return;
        }
        markers.remove(key(position));
        if (markers.isEmpty()) {
            MARKERS_BY_WORLD.remove(worldId);
        }
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        MARKERS_BY_WORLD.remove(worldId);
    }

    @Nonnull
    public static List<Marker> snapshot(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, Marker> markers = MARKERS_BY_WORLD.get(worldId);
        if (markers == null || markers.isEmpty()) {
            return List.of();
        }
        ArrayList<Marker> snapshot = new ArrayList<>(markers.values());
        snapshot.sort(Comparator
                .comparing(Marker::regionId)
                .thenComparingInt(marker -> marker.position().x)
                .thenComparingInt(marker -> marker.position().y)
                .thenComparingInt(marker -> marker.position().z));
        return List.copyOf(snapshot);
    }

    @Nullable
    public static String findRegionForBlockId(@Nullable String blockId) {
        return RegionSpawnConfig.get().findRegionForMarkerBlock(blockId);
    }

    @Nonnull
    private static String key(@Nonnull Vector3i pos) {
        return pos.x + ":" + pos.y + ":" + pos.z;
    }

    public record Marker(@Nonnull String regionId, @Nonnull Vector3i position) {
    }
}
