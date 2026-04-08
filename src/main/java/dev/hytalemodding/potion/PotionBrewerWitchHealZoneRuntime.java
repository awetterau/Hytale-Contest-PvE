package dev.hytalemodding.potion;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PotionBrewerWitchHealZoneRuntime {
    public static record Zone(
            UUID id,
            UUID worldId,
            double x,
            double y,
            double z,
            double radius,
            long expireMillis,
            ArrayList<BlockRestore> restores
    ) {}

    public static record BlockRestore(
            int x,
            int y,
            int z,
            String originalBlockId
    ) {}

    private static final ConcurrentHashMap<UUID, ArrayList<Zone>> ZONES_BY_WORLD = new ConcurrentHashMap<>();

    private PotionBrewerWitchHealZoneRuntime() {}

    public static void addZone(@Nonnull Zone zone) {
        ZONES_BY_WORLD.computeIfAbsent(zone.worldId(), ignored -> new ArrayList<>()).add(zone);
    }

    @Nonnull
    public static List<Zone> getActiveZones(@Nonnull UUID worldId, long nowMillis) {
        ArrayList<Zone> list = ZONES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(z -> z.expireMillis() > nowMillis).toList();
    }

    @Nonnull
    public static ArrayList<Zone> popExpiredZones(@Nonnull UUID worldId, long nowMillis) {
        ArrayList<Zone> list = ZONES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayList<Zone> expired = new ArrayList<>();
        Iterator<Zone> iterator = list.iterator();
        while (iterator.hasNext()) {
            Zone zone = iterator.next();
            if (zone.expireMillis() <= nowMillis) {
                expired.add(zone);
                iterator.remove();
            }
        }
        return expired;
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        ZONES_BY_WORLD.remove(worldId);
    }
}
