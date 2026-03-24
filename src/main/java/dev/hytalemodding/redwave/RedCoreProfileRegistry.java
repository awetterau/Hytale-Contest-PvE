package dev.hytalemodding.redwave;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedCoreProfileRegistry {
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, RedCoreProfile>> PROFILES_BY_WORLD = new ConcurrentHashMap<>();

    private RedCoreProfileRegistry() {
    }

    public static void setProfiles(@Nonnull UUID worldId, @Nonnull List<RedCoreProfile> profiles) {
        ConcurrentHashMap<String, RedCoreProfile> byKey = new ConcurrentHashMap<>();
        for (RedCoreProfile profile : profiles) {
            byKey.put(key(profile.corePos()), new RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }
        if (byKey.isEmpty()) {
            PROFILES_BY_WORLD.remove(worldId);
            return;
        }
        PROFILES_BY_WORLD.put(worldId, byKey);
    }

    @Nonnull
    public static List<RedCoreProfile> snapshot(@Nonnull UUID worldId) {
        ConcurrentHashMap<String, RedCoreProfile> byKey = PROFILES_BY_WORLD.get(worldId);
        if (byKey == null || byKey.isEmpty()) {
            return List.of();
        }

        ArrayList<RedCoreProfile> snapshot = new ArrayList<>(byKey.size());
        for (RedCoreProfile profile : byKey.values()) {
            snapshot.add(new RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }

        snapshot.sort((a, b) -> {
            Vector3i av = a.corePos();
            Vector3i bv = b.corePos();
            if (av.x != bv.x) {
                return Integer.compare(av.x, bv.x);
            }
            if (av.y != bv.y) {
                return Integer.compare(av.y, bv.y);
            }
            return Integer.compare(av.z, bv.z);
        });
        return snapshot;
    }

    @Nonnull
    public static HashMap<String, RedCoreProfile> snapshotByKey(@Nonnull UUID worldId) {
        List<RedCoreProfile> snapshot = snapshot(worldId);
        HashMap<String, RedCoreProfile> byKey = new HashMap<>();
        for (RedCoreProfile profile : snapshot) {
            byKey.put(key(profile.corePos()), profile);
        }
        return byKey;
    }

    public static void clear(@Nonnull UUID worldId) {
        PROFILES_BY_WORLD.remove(worldId);
    }

    @Nonnull
    private static String key(@Nonnull Vector3i corePos) {
        return corePos.x + ":" + corePos.y + ":" + corePos.z;
    }

    public record RedCoreProfile(
            @Nonnull Vector3i corePos,
            int radiusBlocks,
            float startSeconds
    ) {
    }
}
