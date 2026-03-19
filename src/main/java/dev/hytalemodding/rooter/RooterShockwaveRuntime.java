package dev.hytalemodding.rooter;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RooterShockwaveRuntime {
    static final class WaveEvent {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final double originX;
        final double originY;
        final double originZ;
        final double dirX;
        final double dirZ;
        final long startMillis;
        final long expireMillis;
        final double speedBlocksPerSecond;
        final double maxDistance;
        final ConcurrentHashMap<Ref<EntityStore>, Boolean> hitPlayers = new ConcurrentHashMap<>();

        WaveEvent(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                double originX,
                double originY,
                double originZ,
                double dirX,
                double dirZ,
                long startMillis,
                long expireMillis,
                double speedBlocksPerSecond,
                double maxDistance
        ) {
            this.id = id;
            this.worldId = worldId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.startMillis = startMillis;
            this.expireMillis = expireMillis;
            this.speedBlocksPerSecond = speedBlocksPerSecond;
            this.maxDistance = maxDistance;
        }
    }

    static final class BlockRestore {
        @Nonnull
        final UUID worldId;
        final long restoreAtMillis;
        final int sourceX;
        final int sourceY;
        final int sourceZ;
        @Nonnull
        final String sourceBlockId;
        final int liftedX;
        final int liftedY;
        final int liftedZ;
        @Nonnull
        final String liftedBlockId;

        BlockRestore(
                @Nonnull UUID worldId,
                long restoreAtMillis,
                int sourceX,
                int sourceY,
                int sourceZ,
                @Nonnull String sourceBlockId,
                int liftedX,
                int liftedY,
                int liftedZ,
                @Nonnull String liftedBlockId
        ) {
            this.worldId = worldId;
            this.restoreAtMillis = restoreAtMillis;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.sourceZ = sourceZ;
            this.sourceBlockId = sourceBlockId;
            this.liftedX = liftedX;
            this.liftedY = liftedY;
            this.liftedZ = liftedZ;
            this.liftedBlockId = liftedBlockId;
        }
    }

    static final class BlockShift {
        @Nonnull
        final UUID worldId;
        final long shiftAtMillis;
        final int fromX;
        final int fromY;
        final int fromZ;
        final int toX;
        final int toY;
        final int toZ;
        @Nonnull
        final String blockId;

        BlockShift(
                @Nonnull UUID worldId,
                long shiftAtMillis,
                int fromX,
                int fromY,
                int fromZ,
                int toX,
                int toY,
                int toZ,
                @Nonnull String blockId
        ) {
            this.worldId = worldId;
            this.shiftAtMillis = shiftAtMillis;
            this.fromX = fromX;
            this.fromY = fromY;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toY = toY;
            this.toZ = toZ;
            this.blockId = blockId;
        }
    }

    static final class MeleeEvent {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final double originX;
        final double originY;
        final double originZ;
        final double dirX;
        final double dirZ;
        final long startMillis;
        final long expireMillis;
        final double range;
        final double lateralWidth;
        final ConcurrentHashMap<Ref<EntityStore>, Boolean> hitPlayers = new ConcurrentHashMap<>();

        MeleeEvent(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                double originX,
                double originY,
                double originZ,
                double dirX,
                double dirZ,
                long startMillis,
                long expireMillis,
                double range,
                double lateralWidth
        ) {
            this.id = id;
            this.worldId = worldId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.startMillis = startMillis;
            this.expireMillis = expireMillis;
            this.range = range;
            this.lateralWidth = lateralWidth;
        }
    }

    static final class WaveMover {
        @Nonnull
        final UUID id;
        @Nonnull
        final UUID worldId;
        final int blockX;
        final int blockY;
        final int blockZ;
        @Nonnull
        final String blockTypeId;
        final int blockRotation;
        final long spawnAtMillis;
        final long riseAtMillis;
        final long fallAtMillis;
        final long removeAtMillis;
        @Nullable
        Ref<EntityStore> moverRef;
        boolean riseStarted;
        boolean fallStarted;

        WaveMover(
                @Nonnull UUID id,
                @Nonnull UUID worldId,
                int blockX,
                int blockY,
                int blockZ,
                @Nonnull String blockTypeId,
                int blockRotation,
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
            this.blockRotation = blockRotation;
            this.spawnAtMillis = spawnAtMillis;
            this.riseAtMillis = riseAtMillis;
            this.fallAtMillis = fallAtMillis;
            this.removeAtMillis = removeAtMillis;
        }
    }

    private static final ConcurrentHashMap<UUID, List<WaveEvent>> WAVES_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<BlockRestore>> RESTORES_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<BlockShift>> SHIFTS_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<MeleeEvent>> MELEE_BY_WORLD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<WaveMover>> WAVE_MOVERS_BY_WORLD = new ConcurrentHashMap<>();

    private RooterShockwaveRuntime() {
    }

    static void addWave(@Nonnull WaveEvent waveEvent) {
        WAVES_BY_WORLD.computeIfAbsent(waveEvent.worldId, ignored -> new ArrayList<>()).add(waveEvent);
    }

    @Nonnull
    static List<WaveEvent> getActiveWaves(@Nonnull UUID worldId, long nowMillis) {
        List<WaveEvent> list = WAVES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        list.removeIf(wave -> nowMillis > wave.expireMillis);
        return list;
    }

    static void addRestore(@Nonnull BlockRestore restore) {
        RESTORES_BY_WORLD.computeIfAbsent(restore.worldId, ignored -> new ArrayList<>()).add(restore);
    }

    static void addShift(@Nonnull BlockShift shift) {
        SHIFTS_BY_WORLD.computeIfAbsent(shift.worldId, ignored -> new ArrayList<>()).add(shift);
    }

    @Nonnull
    static List<BlockRestore> popDueRestores(@Nonnull UUID worldId, long nowMillis) {
        List<BlockRestore> list = RESTORES_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<BlockRestore> due = new ArrayList<>();
        Iterator<BlockRestore> it = list.iterator();
        while (it.hasNext()) {
            BlockRestore entry = it.next();
            if (entry.restoreAtMillis <= nowMillis) {
                due.add(entry);
                it.remove();
            }
        }
        return due;
    }

    @Nonnull
    static List<BlockShift> popDueShifts(@Nonnull UUID worldId, long nowMillis) {
        List<BlockShift> list = SHIFTS_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<BlockShift> due = new ArrayList<>();
        Iterator<BlockShift> it = list.iterator();
        while (it.hasNext()) {
            BlockShift entry = it.next();
            if (entry.shiftAtMillis <= nowMillis) {
                due.add(entry);
                it.remove();
            }
        }
        return due;
    }

    static void addMelee(@Nonnull MeleeEvent event) {
        MELEE_BY_WORLD.computeIfAbsent(event.worldId, ignored -> new ArrayList<>()).add(event);
    }

    @Nonnull
    static List<MeleeEvent> getActiveMelee(@Nonnull UUID worldId, long nowMillis) {
        List<MeleeEvent> list = MELEE_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        list.removeIf(event -> nowMillis > event.expireMillis);
        return list;
    }

    static void addWaveMover(@Nonnull WaveMover mover) {
        WAVE_MOVERS_BY_WORLD.computeIfAbsent(mover.worldId, ignored -> new ArrayList<>()).add(mover);
    }

    @Nonnull
    static List<WaveMover> getWaveMovers(@Nonnull UUID worldId) {
        List<WaveMover> list = WAVE_MOVERS_BY_WORLD.get(worldId);
        return list == null ? List.of() : list;
    }

    static void removeWaveMovers(@Nonnull UUID worldId, @Nonnull List<UUID> moverIds) {
        if (moverIds.isEmpty()) {
            return;
        }
        List<WaveMover> list = WAVE_MOVERS_BY_WORLD.get(worldId);
        if (list == null || list.isEmpty()) {
            return;
        }
        list.removeIf(mover -> moverIds.contains(mover.id));
    }
}
