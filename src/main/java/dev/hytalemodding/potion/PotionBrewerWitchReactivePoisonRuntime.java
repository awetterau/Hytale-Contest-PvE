package dev.hytalemodding.potion;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PotionBrewerWitchReactivePoisonRuntime {
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> POISONED_PLAYERS_BY_WORLD = new ConcurrentHashMap<>();

    private PotionBrewerWitchReactivePoisonRuntime() {
    }

    static boolean markPoisoned(@Nonnull UUID worldId, @Nonnull UUID playerId, long expireAt) {
        ConcurrentHashMap<UUID, Long> poisoned = POISONED_PLAYERS_BY_WORLD.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        Long previous = poisoned.put(playerId, expireAt);
        return previous == null || previous < expireAt;
    }

    static boolean isPoisoned(@Nonnull UUID worldId, @Nonnull UUID playerId, long now) {
        Map<UUID, Long> poisoned = POISONED_PLAYERS_BY_WORLD.get(worldId);
        if (poisoned == null) {
            return false;
        }
        Long expireAt = poisoned.get(playerId);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= now) {
            poisoned.remove(playerId);
            if (poisoned.isEmpty()) {
                POISONED_PLAYERS_BY_WORLD.remove(worldId);
            }
            return false;
        }
        return true;
    }

    static void clearWorld(@Nonnull UUID worldId) {
        POISONED_PLAYERS_BY_WORLD.remove(worldId);
    }
}
