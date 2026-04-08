package dev.hytalemodding.potion;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PotionBrewerWitchPoisonRuntime {
    private static final ConcurrentHashMap<UUID, List<Patch>> PATCHES_BY_WORLD = new ConcurrentHashMap<>();

    private PotionBrewerWitchPoisonRuntime() {
    }

    public static void addPatch(@Nonnull Patch patch) {
        PATCHES_BY_WORLD.compute(patch.worldId, (ignored, existing) -> {
            List<Patch> patches = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            patches.add(patch);
            return patches;
        });
    }

    @Nonnull
    public static List<Patch> getActivePatches(@Nonnull UUID worldId, long now) {
        List<Patch> existing = PATCHES_BY_WORLD.get(worldId);
        if (existing == null || existing.isEmpty()) {
            return List.of();
        }
        ArrayList<Patch> active = new ArrayList<>();
        boolean expiredFound = false;
        for (Patch patch : existing) {
            if (patch.expiresAtMillis > now) {
                active.add(patch);
            } else {
                expiredFound = true;
            }
        }
        if (expiredFound) {
            if (active.isEmpty()) {
                PATCHES_BY_WORLD.remove(worldId);
            } else {
                PATCHES_BY_WORLD.put(worldId, active);
            }
        }
        return active;
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        PATCHES_BY_WORLD.remove(worldId);
    }

    public record Patch(
            @Nonnull UUID id,
            @Nonnull UUID worldId,
            double x,
            double y,
            double z,
            double radius,
            long expiresAtMillis
    ) {
    }
}
