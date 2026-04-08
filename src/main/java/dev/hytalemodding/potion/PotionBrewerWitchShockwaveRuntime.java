package dev.hytalemodding.potion;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PotionBrewerWitchShockwaveRuntime {
    static final class RippleEvent {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        @Nullable
        final UUID ownerBossId;
        final double originX;
        final double originY;
        final double originZ;
        final long startMillis;
        final long expireMillis;
        final double speedBlocksPerSecond;
        final double maxDistance;
        final ConcurrentHashMap<Ref<EntityStore>, Boolean> hitPlayers = new ConcurrentHashMap<>();

        RippleEvent(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                @Nullable UUID ownerBossId,
                double originX,
                double originY,
                double originZ,
                long startMillis,
                long expireMillis,
                double speedBlocksPerSecond,
                double maxDistance
        ) {
            this.id = id;
            this.worldId = worldId;
            this.ownerBossId = ownerBossId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.startMillis = startMillis;
            this.expireMillis = expireMillis;
            this.speedBlocksPerSecond = speedBlocksPerSecond;
            this.maxDistance = maxDistance;
        }
    }

    static final class RippleMover {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final int blockX;
        final int blockY;
        final int blockZ;
        @Nonnull
        final String blockTypeId;
        final long spawnAtMillis;
        final long riseAtMillis;
        final long fallAtMillis;
        final long removeAtMillis;
        @Nullable
        Ref<EntityStore> moverRef;
        boolean riseStarted;
        boolean fallStarted;

        RippleMover(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                int blockX,
                int blockY,
                int blockZ,
                @Nonnull String blockTypeId,
                long spawnAtMillis,
                long riseAtMillis,
                long fallAtMillis,
                long removeAtMillis
        ) {
            this.id = id;
            this.worldId = worldId;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.blockTypeId = blockTypeId;
            this.spawnAtMillis = spawnAtMillis;
            this.riseAtMillis = riseAtMillis;
            this.fallAtMillis = fallAtMillis;
            this.removeAtMillis = removeAtMillis;
        }
    }

    private static final ConcurrentHashMap<UUID, List<RippleEvent>> RIPPLES_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<RippleMover>> RIPPLE_MOVERS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, LinkedList<Ref<EntityStore>>>> RIPPLE_POOLS_BY_WORLD = new ConcurrentHashMap<>();

    private PotionBrewerWitchShockwaveRuntime() {
    }

    static void addRipple(@Nonnull RippleEvent rippleEvent) {
        RIPPLES_BY_WORLD.computeIfAbsent(rippleEvent.worldId, ignored -> new ArrayList<>()).add(rippleEvent);
    }

    @Nonnull
    static List<RippleEvent> getActiveRipples(@Nonnull UUID worldId, long nowMillis) {
        List<RippleEvent> list = RIPPLES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        list.removeIf(event -> nowMillis > event.expireMillis);
        return list;
    }

    static void addRippleMover(@Nonnull RippleMover mover) {
        RIPPLE_MOVERS_BY_WORLD.computeIfAbsent(mover.worldId, ignored -> new ArrayList<>()).add(mover);
    }

    @Nonnull
    static List<RippleMover> getRippleMovers(@Nonnull UUID worldId) {
        List<RippleMover> list = RIPPLE_MOVERS_BY_WORLD.get(worldId);
        return list == null ? List.of() : list;
    }

    static void removeRippleMovers(@Nonnull UUID worldId, @Nonnull List<UUID> moverIds) {
        if (moverIds.isEmpty()) {
            return;
        }
        List<RippleMover> list = RIPPLE_MOVERS_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return;
        }
        list.removeIf(mover -> moverIds.contains(mover.id));
        if (list.isEmpty()) {
            RIPPLE_MOVERS_BY_WORLD.remove(worldId);
        }
    }

    static void clearWorld(@Nonnull UUID worldId) {
        RIPPLES_BY_WORLD.remove(worldId);
        RIPPLE_MOVERS_BY_WORLD.remove(worldId);
        RIPPLE_POOLS_BY_WORLD.remove(worldId);
    }

    static void releasePooledMover(
            @Nonnull UUID worldId,
            @Nonnull String blockTypeId,
            @Nonnull Ref<EntityStore> moverRef
    ) {
        RIPPLE_POOLS_BY_WORLD
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(blockTypeId, ignored -> new LinkedList<>())
                .addLast(moverRef);
    }

    @Nullable
    static Ref<EntityStore> takePooledMover(
            @Nonnull UUID worldId,
            @Nonnull String blockTypeId
    ) {
        ConcurrentHashMap<String, LinkedList<Ref<EntityStore>>> pools = RIPPLE_POOLS_BY_WORLD.get(worldId);
        if (pools == null) {
            return null;
        }
        LinkedList<Ref<EntityStore>> pool = pools.get(blockTypeId);
        if (pool == null) {
            return null;
        }
        while (!pool.isEmpty()) {
            Ref<EntityStore> ref = pool.removeFirst();
            if (ref != null && ref.isValid()) {
                if (pool.isEmpty()) {
                    pools.remove(blockTypeId);
                    if (pools.isEmpty()) {
                        RIPPLE_POOLS_BY_WORLD.remove(worldId);
                    }
                }
                return ref;
            }
        }
        pools.remove(blockTypeId);
        if (pools.isEmpty()) {
            RIPPLE_POOLS_BY_WORLD.remove(worldId);
        }
        return null;
    }
}
