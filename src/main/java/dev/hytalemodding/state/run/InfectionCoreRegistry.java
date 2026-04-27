package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InfectionCoreRegistry {
    public static final String WEAK_CORE_BLOCK_ID = "Spawn_Crimson_Core_Weak_Block";
    public static final String CORE_BLOCK_ID = "Spawn_Crimson_Core_Block";
    public static final String WEAK_CORE_BLOCK_ENTITY_STATE_ID = "Spawn_Crimson_Core_Weak_State";
    public static final String CORE_BLOCK_ENTITY_STATE_ID = "Spawn_Crimson_Core_State";
    public static final String CRIMSON_MUSHROOM_POISON_BLOCK_ID = "Crimson_Mushroom_Poison";
    public static final String CRIMSON_MUSHROOM_POISON_BLOCK_ENTITY_STATE_ID = "Crimson_Mushroom_Poison_State";
    public static final String CRIMSON_MUSHROOM_FOX_BLOCK_ID = "Crimson_Mushroom_Fox";
    public static final String CRIMSON_MUSHROOM_FOX_BLOCK_ENTITY_STATE_ID = "Crimson_Mushroom_Fox_State";
    public static final String ARENA_ACTIVATION_BLOCK_ID = "Arena_activation";
    public static final String ARENA_ACTIVATION_BLOCK_ENTITY_STATE_ID = "Arena_activation_State";

    private static final ConcurrentHashMap<UUID, Set<Long>> WEAK_CORE_POSITIONS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<Long>> CORE_POSITIONS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<Long>> CRIMSON_MUSHROOM_POISON_POSITIONS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<Long>> ARENA_ACTIVATION_POSITIONS_BY_WORLD = new ConcurrentHashMap<>();

    private InfectionCoreRegistry() {
    }

    public static void registerWeakCore(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        WEAK_CORE_POSITIONS_BY_WORLD.computeIfAbsent(worldId, ignored -> ConcurrentHashMap.newKeySet())
                .add(pack(pos));
    }

    public static void unregisterWeakCore(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        Set<Long> entries = WEAK_CORE_POSITIONS_BY_WORLD.get(worldId);
        if (entries == null) {
            return;
        }
        entries.remove(pack(pos));
        if (entries.isEmpty()) {
            WEAK_CORE_POSITIONS_BY_WORLD.remove(worldId, entries);
        }
    }

    public static int getWeakCoreCount(@Nonnull UUID worldId) {
        Set<Long> entries = WEAK_CORE_POSITIONS_BY_WORLD.get(worldId);
        return entries == null ? 0 : entries.size();
    }

    public static void registerCore(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        CORE_POSITIONS_BY_WORLD.computeIfAbsent(worldId, ignored -> ConcurrentHashMap.newKeySet())
                .add(pack(pos));
    }

    public static void unregisterCore(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        Set<Long> entries = CORE_POSITIONS_BY_WORLD.get(worldId);
        if (entries == null) {
            return;
        }
        entries.remove(pack(pos));
        if (entries.isEmpty()) {
            CORE_POSITIONS_BY_WORLD.remove(worldId, entries);
        }
    }

    public static int getCoreCount(@Nonnull UUID worldId) {
        Set<Long> entries = CORE_POSITIONS_BY_WORLD.get(worldId);
        return entries == null ? 0 : entries.size();
    }

    public static void registerCrimsonMushroomPoison(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        CRIMSON_MUSHROOM_POISON_POSITIONS_BY_WORLD.computeIfAbsent(worldId, ignored -> ConcurrentHashMap.newKeySet())
                .add(pack(pos));
    }

    public static void unregisterCrimsonMushroomPoison(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        Set<Long> entries = CRIMSON_MUSHROOM_POISON_POSITIONS_BY_WORLD.get(worldId);
        if (entries == null) {
            return;
        }
        entries.remove(pack(pos));
        if (entries.isEmpty()) {
            CRIMSON_MUSHROOM_POISON_POSITIONS_BY_WORLD.remove(worldId, entries);
        }
    }

    public static int getCrimsonMushroomPoisonCount(@Nonnull UUID worldId) {
        Set<Long> entries = CRIMSON_MUSHROOM_POISON_POSITIONS_BY_WORLD.get(worldId);
        return entries == null ? 0 : entries.size();
    }

    public static void registerArenaActivation(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        ARENA_ACTIVATION_POSITIONS_BY_WORLD.computeIfAbsent(worldId, ignored -> ConcurrentHashMap.newKeySet())
                .add(pack(pos));
    }

    public static void unregisterArenaActivation(@Nonnull UUID worldId, @Nonnull Vector3i pos) {
        Set<Long> entries = ARENA_ACTIVATION_POSITIONS_BY_WORLD.get(worldId);
        if (entries == null) {
            return;
        }
        entries.remove(pack(pos));
        if (entries.isEmpty()) {
            ARENA_ACTIVATION_POSITIONS_BY_WORLD.remove(worldId, entries);
        }
    }

    @Nonnull
    public static List<Vector3i> snapshotWeakPositions(@Nonnull UUID worldId) {
        return unpackSnapshot(WEAK_CORE_POSITIONS_BY_WORLD.get(worldId));
    }

    @Nonnull
    public static List<Vector3i> snapshotCorePositions(@Nonnull UUID worldId) {
        return unpackSnapshot(CORE_POSITIONS_BY_WORLD.get(worldId));
    }

    @Nonnull
    public static List<Vector3i> snapshotCrimsonMushroomPoisonPositions(@Nonnull UUID worldId) {
        return unpackSnapshot(CRIMSON_MUSHROOM_POISON_POSITIONS_BY_WORLD.get(worldId));
    }

    @Nonnull
    public static List<Vector3i> snapshotArenaActivationPositions(@Nonnull UUID worldId) {
        return unpackSnapshot(ARENA_ACTIVATION_POSITIONS_BY_WORLD.get(worldId));
    }

    @Nonnull
    private static List<Vector3i> unpackSnapshot(Set<Long> packed) {
        if (packed == null || packed.isEmpty()) {
            return List.of();
        }
        ArrayList<Vector3i> result = new ArrayList<>(packed.size());
        for (long entry : packed) {
            result.add(unpack(entry));
        }
        result.sort(Comparator.comparingInt((Vector3i v) -> v.x)
                .thenComparingInt(v -> v.y)
                .thenComparingInt(v -> v.z));
        return result;
    }

    private static long pack(@Nonnull Vector3i pos) {
        long x = ((long) pos.x & 0x3FFFFFFL) << 38;
        long y = ((long) pos.y & 0xFFFL) << 26;
        long z = (long) pos.z & 0x3FFFFFFL;
        return x | y | z;
    }

    @Nonnull
    private static Vector3i unpack(long packed) {
        int x = (int) (packed >> 38);
        int y = (int) ((packed >> 26) & 0xFFFL);
        int z = (int) (packed << 38 >> 38);
        if (x >= 0x2000000) {
            x -= 0x4000000;
        }
        if (y >= 0x800) {
            y -= 0x1000;
        }
        if (z >= 0x2000000) {
            z -= 0x4000000;
        }
        return new Vector3i(x, y, z);
    }
}