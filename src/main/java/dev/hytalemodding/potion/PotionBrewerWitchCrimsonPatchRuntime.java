package dev.hytalemodding.potion;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PotionBrewerWitchCrimsonPatchRuntime {
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

    static final class Patch {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final long expireMillis;
        @Nonnull
        final ArrayList<BlockRestore> restores;

        Patch(
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

    private static final ConcurrentHashMap<UUID, ArrayList<Patch>> PATCHES_BY_WORLD = new ConcurrentHashMap<>();

    private PotionBrewerWitchCrimsonPatchRuntime() {
    }

    static void addPatch(@Nonnull Patch patch) {
        PATCHES_BY_WORLD.computeIfAbsent(patch.worldId, ignored -> new ArrayList<>()).add(patch);
    }

    @Nonnull
    static ArrayList<Patch> popExpiredPatches(@Nonnull UUID worldId, long nowMillis) {
        ArrayList<Patch> list = PATCHES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayList<Patch> expired = new ArrayList<>();
        Iterator<Patch> iterator = list.iterator();
        while (iterator.hasNext()) {
            Patch patch = iterator.next();
            if (patch.expireMillis <= nowMillis) {
                expired.add(patch);
                iterator.remove();
            }
        }
        return expired;
    }

    @Nullable
    static Patch getActivePatchNear(@Nonnull UUID worldId, int x, int y, int z) {
        ArrayList<Patch> list = PATCHES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (Patch patch : list) {
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
