package dev.hytalemodding.rooter;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RooterShockwaveEmitterSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final String ROOTER_ROLE_PREFIX = "Rooter_Man_Boss";
    private static final String ROOT_WAVE_STATE = "BurrowLong";
    private static final String ROOT_MELEE_STATE_A = "BurrowShort";
    private static final String ROOT_MELEE_STATE_B = "BurrowedShort";
    private static final long MIN_WAVE_TRIGGER_INTERVAL_MS = 700L;
    private static final long MIN_MELEE_TRIGGER_INTERVAL_MS = 420L;
    private static final int WAVE_STEPS = 12;
    private static final double STEP_DISTANCE = 1.0;
    private static final long WAVE_STEP_DELAY_MS = 72L;
    private static final long WAVE_ASCEND_DURATION_MS = 145L;
    private static final long WAVE_DESCEND_DURATION_MS = 145L;
    private static final double WAVE_MOVER_SPEED_BLOCKS_PER_SECOND = 6.9;
    private static final String EMPTY_BLOCK = "Empty";

    private final ConcurrentHashMap<Ref<EntityStore>, Tracker> trackers = new ConcurrentHashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();

        processWaveMovers(store, worldId, now);
        scanRooterStatesAndEmit(store, world, worldId, now);
        processWaveMovers(store, worldId, now);
    }

    private void scanRooterStatesAndEmit(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            long now
    ) {
        store.forEachChunk(NPC, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPC);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                if (npc == null || transform == null) {
                    continue;
                }
                String roleName = npc.getRoleName();
                if (roleName == null || !roleName.startsWith(ROOTER_ROLE_PREFIX)) {
                    continue;
                }

                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }

                String state = getNpcStateName(npc);
                Tracker tracker = this.trackers.computeIfAbsent(npcRef, ignoredRef -> new Tracker());
                boolean enteredRootWave = isState(state, ROOT_WAVE_STATE) && !isState(tracker.lastStateName, ROOT_WAVE_STATE);
                if (enteredRootWave && (now - tracker.lastWaveTriggerMs) >= MIN_WAVE_TRIGGER_INTERVAL_MS) {
                    tracker.pendingWaveFireMs = now + 500L;
                    tracker.lastWaveTriggerMs = now;
                }
                if (tracker.pendingWaveFireMs > 0 && now >= tracker.pendingWaveFireMs) {
                    Direction direction = resolveAttackDirection(store, transform);
                    setFacing(transform, direction);
                    triggerShockwave(world, worldId, transform, direction, now);
                    tracker.pendingWaveFireMs = 0L;
                }

                boolean meleeNow = isState(state, ROOT_MELEE_STATE_A) || isState(state, ROOT_MELEE_STATE_B);
                boolean meleeBefore = isState(tracker.lastStateName, ROOT_MELEE_STATE_A) || isState(tracker.lastStateName, ROOT_MELEE_STATE_B);
                boolean enteredMelee = meleeNow && !meleeBefore;
                if (enteredMelee && (now - tracker.lastMeleeTriggerMs) >= MIN_MELEE_TRIGGER_INTERVAL_MS) {
                    Direction direction = resolveAttackDirection(store, transform);
                    setFacing(transform, direction);
                    triggerMelee(worldId, transform, direction, now);
                    tracker.lastMeleeTriggerMs = now;
                }
                tracker.lastStateName = state;
            }
        });

        this.trackers.entrySet().removeIf(entry -> entry.getKey() == null || !entry.getKey().isValid());
    }

    private static void triggerMelee(
            @Nonnull UUID worldId,
            @Nonnull TransformComponent transform,
            @Nonnull Direction direction,
            long now
    ) {
        Vector3d pos = transform.getPosition();
        RooterShockwaveRuntime.addMelee(new RooterShockwaveRuntime.MeleeEvent(
                UUID.randomUUID(),
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                direction.dirX,
                direction.dirZ,
                now + 500L,
                now + 500L + 480L,
                6.6,
                2.05
        ));
    }

    private static void triggerShockwave(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull TransformComponent transform,
            @Nonnull Direction direction,
            long now
    ) {
        Vector3d pos = transform.getPosition();
        RooterShockwaveRuntime.WaveEvent wave = new RooterShockwaveRuntime.WaveEvent(
                UUID.randomUUID(),
                worldId,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                direction.dirX,
                direction.dirZ,
                now,
                now + 2600L,
                7.6,
                WAVE_STEPS * STEP_DISTANCE
        );
        RooterShockwaveRuntime.addWave(wave);
        scheduleWaveBlocks(world, worldId, wave, now);
    }

    private static void scheduleWaveBlocks(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull RooterShockwaveRuntime.WaveEvent wave,
            long now
    ) {
        double sideX = -wave.dirZ;
        double sideZ = wave.dirX;
        Set<String> scheduled = new HashSet<>();

        for (int step = 1; step <= WAVE_STEPS; step++) {
            double distance = step * STEP_DISTANCE;
            int width = Math.min(1 + (step / 2), 5);
            long baseLiftAt = now + (step * WAVE_STEP_DELAY_MS);
            for (int lateral = -width; lateral <= width; lateral++) {
                double lateralOffset = lateral * 0.86;
                double sampleX = wave.originX + (wave.dirX * distance) + (sideX * lateralOffset);
                double sampleZ = wave.originZ + (wave.dirZ * distance) + (sideZ * lateralOffset);
                int x = (int) Math.floor(sampleX);
                int z = (int) Math.floor(sampleZ);
                int sourceY = (int) Math.floor(wave.originY) - 1;
                String blockKey = x + ":" + sourceY + ":" + z;
                if (!scheduled.add(blockKey)) {
                    continue;
                }

                BlockType source = world.getBlockType(x, sourceY, z);
                String sourceId = normalizeBlockId(source == null ? null : source.getId());
                if (sourceId == null || EMPTY_BLOCK.equals(sourceId)) {
                    continue;
                }
                int rotation = readRotation(world, x, sourceY, z);
                RooterShockwaveRuntime.addWaveMover(new RooterShockwaveRuntime.WaveMover(
                        UUID.randomUUID(),
                        worldId,
                        x,
                        sourceY,
                        z,
                        sourceId
                        ,
                        rotation,
                        baseLiftAt,
                        baseLiftAt,
                        baseLiftAt + WAVE_ASCEND_DURATION_MS,
                        baseLiftAt + WAVE_ASCEND_DURATION_MS + WAVE_DESCEND_DURATION_MS
                ));
            }
        }
    }

    private static void processWaveMovers(@Nonnull Store<EntityStore> store, @Nonnull UUID worldId, long now) {
        List<RooterShockwaveRuntime.WaveMover> movers = RooterShockwaveRuntime.getWaveMovers(worldId);
        if (movers.isEmpty()) {
            return;
        }
        List<UUID> completed = new ArrayList<>();
        for (RooterShockwaveRuntime.WaveMover mover : movers) {
            if (mover == null) {
                continue;
            }
            if (now < mover.spawnAtMillis) {
                continue;
            }

            if (mover.moverRef == null || !mover.moverRef.isValid()) {
                if (mover.moverRef == null) {
                    mover.moverRef = spawnBlockMover(store, mover);
                }
                if (mover.moverRef == null || !mover.moverRef.isValid()) {
                    if (now > mover.removeAtMillis) {
                        completed.add(mover.id);
                    }
                    continue;
                }
            }

            Ref<EntityStore> moverRef = mover.moverRef;
            BlockEntity blockEntity = store.getComponent(moverRef, BlockEntity.getComponentType());
            Velocity velocity = store.getComponent(moverRef, Velocity.getComponentType());
            TransformComponent transform = store.getComponent(moverRef, TRANSFORM);
            if (blockEntity == null || velocity == null || transform == null) {
                if (now > mover.removeAtMillis) {
                    store.removeEntity(moverRef, RemoveReason.REMOVE);
                    completed.add(mover.id);
                }
                continue;
            }

            if (!mover.riseStarted && now >= mover.riseAtMillis) {
                blockEntity.getSimplePhysicsProvider().setResting(false);
                Vector3d up = new Vector3d(0.0d, WAVE_MOVER_SPEED_BLOCKS_PER_SECOND, 0.0d);
                blockEntity.getSimplePhysicsProvider().setVelocity(up);
                velocity.set(up);
                mover.riseStarted = true;
            }

            if (!mover.fallStarted && now >= mover.fallAtMillis) {
                // Keep the crest at exactly one block above source before descending.
                transform.teleportPosition(new Vector3d(mover.blockX + 0.5d, mover.blockY + 1.0d, mover.blockZ + 0.5d));
                transform.markChunkDirty(store);
                Vector3d down = new Vector3d(0.0d, -WAVE_MOVER_SPEED_BLOCKS_PER_SECOND, 0.0d);
                blockEntity.getSimplePhysicsProvider().setVelocity(down);
                velocity.set(down);
                mover.fallStarted = true;
            }

            if (now >= mover.removeAtMillis) {
                blockEntity.getSimplePhysicsProvider().setVelocity(Vector3d.ZERO);
                blockEntity.getSimplePhysicsProvider().setResting(true);
                velocity.set(Vector3d.ZERO);
                store.removeEntity(moverRef, RemoveReason.REMOVE);
                completed.add(mover.id);
            }
        }

        RooterShockwaveRuntime.removeWaveMovers(worldId, completed);
    }

    @Nullable
    private static Ref<EntityStore> spawnBlockMover(
            @Nonnull Store<EntityStore> store,
            @Nonnull RooterShockwaveRuntime.WaveMover mover
    ) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(((EntityStore) store.getExternalData()).takeNextNetworkId()));
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(mover.blockTypeId));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(
                new Vector3d(mover.blockX + 0.5d, mover.blockY, mover.blockZ + 0.5d),
                Vector3f.FORWARD
        ));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(2.0f));
        holder.addComponent(Velocity.getComponentType(), new Velocity());
        holder.addComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);

        try {
            HitboxCollisionConfig cfg = HitboxCollisionConfig.getAssetMap().getAsset("HardCollision");
            if (cfg != null) {
                holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(cfg));
            }
        } catch (Exception ignored) {
        }

        try {
            RotationTuple tuple = RotationTuple.get(mover.blockRotation);
            Rotation yaw = tuple.yaw();
            Rotation pitch = tuple.pitch();
            Rotation roll = tuple.roll();
            Vector3f rot = new Vector3f(0.0f, 0.0f, 0.0f);
            rot.setYaw((float) (-yaw.getRadians()));
            rot.setPitch((float) (-pitch.getRadians()));
            rot.setRoll((float) (-roll.getRadians()));
            holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rot));
        } catch (Exception ignored) {
        }

        holder.ensureComponent(UUIDComponent.getComponentType());
        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
        if (ref == null || !ref.isValid()) {
            return null;
        }

        BlockEntity blockEntity = store.getComponent(ref, BlockEntity.getComponentType());
        if (blockEntity != null) {
            blockEntity.initPhysics(new BoundingBox(
                    com.hypixel.hytale.math.shape.Box.centeredCube(new Vector3d(0.0d, 0.5d, 0.0d), 0.5d)
            ));
            blockEntity.getSimplePhysicsProvider().setResting(true);
        }
        return ref;
    }

    private static int readRotation(@Nonnull World world, int x, int y, int z) {
        try {
            return world.getBlockRotationIndex(x, y, z);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Nonnull
    private static Direction resolveAttackDirection(@Nonnull Store<EntityStore> store, @Nonnull TransformComponent npcTransform) {
        Vector3d npcPos = npcTransform.getPosition();
        ClosestTarget closest = new ClosestTarget();
        store.forEachChunk(PLAYER, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                TransformComponent playerTransform = chunk.getComponent(i, TRANSFORM);
                if (playerTransform == null) {
                    continue;
                }
                Vector3d playerPos = playerTransform.getPosition();
                double dx = playerPos.getX() - npcPos.getX();
                double dz = playerPos.getZ() - npcPos.getZ();
                double distSq = (dx * dx) + (dz * dz);
                if (distSq < 0.0001 || distSq > (18.0 * 18.0)) {
                    continue;
                }
                if (distSq < closest.distSq) {
                    closest.distSq = distSq;
                    closest.dx = dx;
                    closest.dz = dz;
                }
            }
        });

        if (closest.distSq < Double.POSITIVE_INFINITY) {
            double mag = Math.sqrt(closest.distSq);
            return new Direction(closest.dx / mag, closest.dz / mag);
        }

        Vector3f rot = npcTransform.getRotation();
        double yawRadians = Math.toRadians(rot.getY());
        double dirX = -Math.sin(yawRadians);
        double dirZ = Math.cos(yawRadians);
        double mag = Math.sqrt((dirX * dirX) + (dirZ * dirZ));
        if (mag < 0.0001) {
            return new Direction(0, 1);
        }
        return new Direction(dirX / mag, dirZ / mag);
    }

    private static void setFacing(
            @Nonnull TransformComponent transform,
            @Nonnull Direction direction
    ) {
        Vector3f nextRot = new Vector3f(transform.getRotation());
        float yaw = (float) Math.atan2(-direction.dirX, -direction.dirZ);
        nextRot.setYaw(yaw);
        transform.teleportRotation(nextRot);
    }

    @Nullable
    private static String getNpcStateName(@Nonnull NPCEntity npc) {
        if (npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        return npc.getRole().getStateSupport().getStateName();
    }

    @Nullable
    private static String normalizeBlockId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.startsWith("*") ? id.substring(1) : id;
        int stateIndex = normalized.indexOf("_State_Definitions_");
        if (stateIndex >= 0) {
            normalized = normalized.substring(0, stateIndex);
        }
        return normalized.trim();
    }

    private static boolean isState(@Nullable String stateName, @Nonnull String suffix) {
        if (stateName == null || stateName.isEmpty()) {
            return false;
        }
        if (stateName.equals(suffix) || stateName.equals("." + suffix)) {
            return true;
        }
        return stateName.endsWith("." + suffix) || stateName.endsWith(suffix);
    }

    private static final class Tracker {
        @Nullable
        private String lastStateName;
        private long lastWaveTriggerMs;
        private long lastMeleeTriggerMs;
        private long pendingWaveFireMs;
    }

    private static final class ClosestTarget {
        private double distSq = Double.POSITIVE_INFINITY;
        private double dx;
        private double dz;
    }

    private static final class Direction {
        private final double dirX;
        private final double dirZ;

        private Direction(double dirX, double dirZ) {
            this.dirX = dirX;
            this.dirZ = dirZ;
        }
    }
}
