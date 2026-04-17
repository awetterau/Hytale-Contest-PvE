package dev.hytalemodding.potion;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PotionBrewerWitchShockwaveSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final String EMPTY_BLOCK = "Empty";
    private static final int RIPPLE_RINGS = 8;
    private static final double RIPPLE_RING_STEP = 1.0d;
    private static final long RIPPLE_STEP_DELAY_MS = 140L;
    private static final long RIPPLE_ASCEND_DURATION_MS = 260L;
    private static final long RIPPLE_DESCEND_DURATION_MS = 260L;
    private static final double RIPPLE_CREST_HEIGHT = 0.7d;
    private static final float RIPPLE_BLOCK_SCALE = 2.0f;
    private static final float RIPPLE_HIDDEN_SCALE = 0.05f;
    private static final double RIPPLE_POOL_HIDE_OFFSET_Y = -3.0d;
    private static final double RIPPLE_EVENT_SPEED_BLOCKS_PER_SECOND = 13.85d;
    private static final boolean ENABLE_RIPPLE_MOVERS = true;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!ENABLE_RIPPLE_MOVERS) {
            PotionBrewerWitchShockwaveRuntime.clearWorld(worldId);
            return;
        }
        processRippleMovers(store, world, worldId, System.currentTimeMillis());
    }

    public static void triggerRipple(
            @Nonnull World world,
            @Nullable UUID ownerBossId,
            @Nonnull Vector3d origin
    ) {
        if (!ENABLE_RIPPLE_MOVERS) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        double maxDistance = RIPPLE_RINGS * RIPPLE_RING_STEP;
        PotionBrewerWitchShockwaveRuntime.addRipple(new PotionBrewerWitchShockwaveRuntime.RippleEvent(
                UUID.randomUUID(),
                worldId,
                ownerBossId,
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                now,
                now + (long) ((maxDistance / RIPPLE_EVENT_SPEED_BLOCKS_PER_SECOND) * 1000.0d) + 350L,
                RIPPLE_EVENT_SPEED_BLOCKS_PER_SECOND,
                maxDistance
        ));
        scheduleRippleBlocks(world, worldId, origin, now);
    }

    private static void scheduleRippleBlocks(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull Vector3d origin,
            long now
    ) {
        int sourceY = (int) Math.floor(origin.getY()) - 1;
        Set<String> scheduled = new HashSet<>();
        for (int ring = 1; ring <= RIPPLE_RINGS; ring++) {
            double radius = ring * RIPPLE_RING_STEP;
            int samples = Math.max(8, (int) Math.ceil(radius * 10.0d));
            long baseLiftAt = now + (ring * RIPPLE_STEP_DELAY_MS);
            for (int sample = 0; sample < samples; sample++) {
                double angle = (Math.PI * 2.0d * sample) / samples;
                double sampleX = origin.getX() + (Math.cos(angle) * radius);
                double sampleZ = origin.getZ() + (Math.sin(angle) * radius);
                int x = (int) Math.floor(sampleX);
                int z = (int) Math.floor(sampleZ);
                String blockKey = x + ":" + sourceY + ":" + z;
                if (!scheduled.add(blockKey)) {
                    continue;
                }
                BlockType source = world.getBlockType(x, sourceY, z);
                String sourceId = normalizeBlockId(source == null ? null : source.getId());
                if (sourceId == null || EMPTY_BLOCK.equals(sourceId)) {
                    continue;
                }
                PotionBrewerWitchShockwaveRuntime.addRippleMover(new PotionBrewerWitchShockwaveRuntime.RippleMover(
                        UUID.randomUUID(),
                        worldId,
                        x,
                        sourceY,
                        z,
                        sourceId,
                        baseLiftAt,
                        baseLiftAt,
                        baseLiftAt + RIPPLE_ASCEND_DURATION_MS,
                        baseLiftAt + RIPPLE_ASCEND_DURATION_MS + RIPPLE_DESCEND_DURATION_MS
                ));
            }
        }
    }

    private static void processRippleMovers(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            long now
    ) {
        List<PotionBrewerWitchShockwaveRuntime.RippleMover> movers = PotionBrewerWitchShockwaveRuntime.getRippleMovers(worldId);
        if (movers.isEmpty()) {
            return;
        }
        List<UUID> completed = new ArrayList<>();
        List<MoveCmd> moves = new ArrayList<>();
        List<ReleaseCmd> releases = new ArrayList<>();
        for (PotionBrewerWitchShockwaveRuntime.RippleMover mover : movers) {
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
            TransformComponent transform = store.getComponent(moverRef, TRANSFORM);
            if (transform == null) {
                if (now > mover.removeAtMillis) {
                    releases.add(new ReleaseCmd(moverRef, mover.blockTypeId, mover.blockX + 0.5d, mover.blockY + RIPPLE_POOL_HIDE_OFFSET_Y, mover.blockZ + 0.5d));
                    completed.add(mover.id);
                }
                continue;
            }

            double y = mover.blockY;
            if (now >= mover.fallAtMillis) {
                double fallProgress = Math.min(1.0d, (double) (now - mover.fallAtMillis) / (double) RIPPLE_DESCEND_DURATION_MS);
                y += RIPPLE_CREST_HEIGHT * (1.0d - fallProgress);
            } else if (now >= mover.riseAtMillis) {
                double riseProgress = Math.min(1.0d, (double) (now - mover.riseAtMillis) / (double) RIPPLE_ASCEND_DURATION_MS);
                y += RIPPLE_CREST_HEIGHT * riseProgress;
            }
            moves.add(new MoveCmd(moverRef, mover.blockX + 0.5d, y, mover.blockZ + 0.5d));

            if (now >= mover.removeAtMillis) {
                releases.add(new ReleaseCmd(moverRef, mover.blockTypeId, mover.blockX + 0.5d, mover.blockY + RIPPLE_POOL_HIDE_OFFSET_Y, mover.blockZ + 0.5d));
                completed.add(mover.id);
            }
        }

        for (MoveCmd move : moves) {
            world.execute(() -> applyMoverPosition(world, move));
        }
        for (ReleaseCmd release : releases) {
            world.execute(() -> releaseMover(world, worldId, release));
        }
        PotionBrewerWitchShockwaveRuntime.removeRippleMovers(worldId, completed);
    }

    @Nullable
    private static Ref<EntityStore> spawnBlockMover(
            @Nonnull Store<EntityStore> store,
            @Nonnull PotionBrewerWitchShockwaveRuntime.RippleMover mover
    ) {
        Ref<EntityStore> pooled = PotionBrewerWitchShockwaveRuntime.takePooledMover(mover.worldId, mover.blockTypeId);
        if (pooled != null && pooled.isValid()) {
            configureMover(store, pooled, mover.blockX + 0.5d, mover.blockY, mover.blockZ + 0.5d, RIPPLE_BLOCK_SCALE);
            return pooled;
        }

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(((EntityStore) store.getExternalData()).takeNextNetworkId()));
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(mover.blockTypeId));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(
                new Vector3d(mover.blockX + 0.5d, mover.blockY, mover.blockZ + 0.5d),
                Vector3f.FORWARD
        ));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(RIPPLE_BLOCK_SCALE));
        holder.addComponent(Velocity.getComponentType(), new Velocity());
        holder.addComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        holder.ensureComponent(UUIDComponent.getComponentType());

        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
        if (ref == null || !ref.isValid()) {
            return null;
        }

        BlockEntity blockEntity = store.getComponent(ref, BlockEntity.getComponentType());
        if (blockEntity != null) {
            blockEntity.initPhysics(new BoundingBox(Box.centeredCube(new Vector3d(0.0d, 0.5d, 0.0d), 0.5d)));
            blockEntity.getSimplePhysicsProvider().setResting(true);
        }
        return ref;
    }

    private static void applyMoverPosition(
            @Nonnull World world,
            @Nonnull MoveCmd move
    ) {
        if (move.moverRef == null || !move.moverRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(move.moverRef, TRANSFORM);
        if (transform == null) {
            return;
        }
        TransformComponent updated = transform.clone();
        updated.teleportPosition(new Vector3d(move.x, move.y, move.z));
        updated.markChunkDirty(store);
        store.putComponent(move.moverRef, TRANSFORM, updated);
    }

    private static void releaseMover(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull ReleaseCmd release
    ) {
        if (release.moverRef == null || !release.moverRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        configureMover(store, release.moverRef, release.x, release.y, release.z, RIPPLE_HIDDEN_SCALE);
        PotionBrewerWitchShockwaveRuntime.releasePooledMover(worldId, release.blockTypeId, release.moverRef);
    }

    private static void configureMover(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> moverRef,
            double x,
            double y,
            double z,
            float scale
    ) {
        TransformComponent transform = store.getComponent(moverRef, TRANSFORM);
        if (transform == null) {
            return;
        }
        TransformComponent updated = transform.clone();
        updated.teleportPosition(new Vector3d(x, y, z));
        updated.markChunkDirty(store);
        store.putComponent(moverRef, TRANSFORM, updated);
        store.putComponent(moverRef, EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        Velocity velocity = new Velocity();
        velocity.set(Vector3d.ZERO);
        store.putComponent(moverRef, Velocity.getComponentType(), velocity);
        BlockEntity blockEntity = store.getComponent(moverRef, BlockEntity.getComponentType());
        if (blockEntity != null) {
            blockEntity.getSimplePhysicsProvider().setVelocity(Vector3d.ZERO);
            blockEntity.getSimplePhysicsProvider().setResting(true);
        }
    }

    @Nullable
    private static String normalizeBlockId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.startsWith("*") ? id.substring(1) : id;
        int stateIndex = normalized.indexOf('[');
        if (stateIndex >= 0) {
            normalized = normalized.substring(0, stateIndex);
        }
        return normalized;
    }

    private static final class MoveCmd {
        private final Ref<EntityStore> moverRef;
        private final double x;
        private final double y;
        private final double z;

        private MoveCmd(@Nonnull Ref<EntityStore> moverRef, double x, double y, double z) {
            this.moverRef = moverRef;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class ReleaseCmd {
        private final Ref<EntityStore> moverRef;
        private final String blockTypeId;
        private final double x;
        private final double y;
        private final double z;

        private ReleaseCmd(@Nonnull Ref<EntityStore> moverRef, @Nonnull String blockTypeId, double x, double y, double z) {
            this.moverRef = moverRef;
            this.blockTypeId = blockTypeId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
