package dev.hytalemodding.crimson;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CrimsonWitchGroundTrapRuntime {
    static final class BlockRestore {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final int x;
        final int y;
        final int z;
        @Nonnull
        final String originalBlockId;

        BlockRestore(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                int x,
                int y,
                int z,
                @Nonnull String originalBlockId
        ) {
            this.id = id;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.originalBlockId = originalBlockId;
        }
    }

    static final class TrapPatch {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final long expireMillis;
        @Nonnull
        final ArrayList<BlockRestore> restores;

        TrapPatch(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                long expireMillis,
                @Nonnull ArrayList<BlockRestore> restores
        ) {
            this.id = id;
            this.worldId = worldId;
            this.expireMillis = expireMillis;
            this.restores = restores;
        }
    }

    private static final ConcurrentHashMap<UUID, ArrayList<TrapPatch>> PATCHES_BY_WORLD = new ConcurrentHashMap<>();

    private CrimsonWitchGroundTrapRuntime() {
    }

    static void addPatch(@Nonnull TrapPatch trapPatch) {
        PATCHES_BY_WORLD.computeIfAbsent(trapPatch.worldId, ignored -> new ArrayList<>()).add(trapPatch);
    }

    @Nonnull
    static ArrayList<TrapPatch> popExpiredPatches(@Nonnull UUID worldId, long nowMillis) {
        ArrayList<TrapPatch> list = PATCHES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayList<TrapPatch> expired = new ArrayList<>();
        Iterator<TrapPatch> iterator = list.iterator();
        while (iterator.hasNext()) {
            TrapPatch patch = iterator.next();
            if (patch.expireMillis <= nowMillis) {
                expired.add(patch);
                iterator.remove();
            }
        }
        return expired;
    }

    @Nullable
    static TrapPatch getActivePatchNear(@Nonnull UUID worldId, int x, int y, int z) {
        ArrayList<TrapPatch> list = PATCHES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (TrapPatch patch : list) {
            for (BlockRestore restore : patch.restores) {
                if (restore.x == x && restore.y == y && restore.z == z) {
                    return patch;
                }
            }
        }
        return null;
    }

    static void clearWorld(@Nonnull UUID worldId) {
        PATCHES_BY_WORLD.remove(worldId);
    }
}
