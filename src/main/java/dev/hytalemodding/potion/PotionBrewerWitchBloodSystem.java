package dev.hytalemodding.potion;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.projectile.component.Projectile;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedWaveConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PotionBrewerWitchBloodSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, Projectile> PROJECTILE = Projectile.getComponentType();
    private static final ComponentType<EntityStore, StandardPhysicsProvider> STANDARD_PHYSICS = StandardPhysicsProvider.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, ModelComponent> MODEL = ModelComponent.getComponentType();
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, EntityStatMap> STATS = EntityStatMap.getComponentType();

    private static final String ROLE_NAME = "Potion_Brewer_Witch";
    private static final String BLOOD_POTION = "blood potion";
    private static final String BLOOD_PROJECTILE_MODEL_ID = "Bomb_Potion_Blood";
    private static final String BLOOD_SPIKE_BLOCK_ID = RedWaveConfig.CRIMSON_BLOCK_ID;
    private static final int HEALTH_STAT_INDEX = DefaultEntityStatTypes.getHealth();

    private static final float BLOOD_THROW_DAMAGE = 30.0f;
    private static final float BLOOD_SPIKE_DAMAGE = 20.0f;
    private static final float BLOOD_SELF_COST = 36.0f;
    private static final float BLOOD_HEAL_RATIO = 0.5f;
    private static final double BLOOD_PROJECTILE_HIT_RADIUS = 1.35d;
    private static final double BLOOD_SPIKE_HIT_RADIUS = 1.05d;
    private static final double BLOOD_SPIKE_VERTICAL_TOLERANCE = 2.2d;
    private static final double BLOOD_SPIKE_SPEED = 8.0d;
    private static final double BLOOD_SPIKE_START_DISTANCE = 1.1d;
    private static final double BLOOD_SPIKE_MAX_DISTANCE = 16.0d;
    private static final double BLOOD_SPIKE_BODY_OFFSET = 0.52d;
    private static final float BLOOD_SPIKE_BASE_SCALE = 1.70f;
    private static final float BLOOD_SPIKE_TIP_SCALE = 0.68f;
    private static final int BLOOD_SPIKE_COUNT = 8;
    private static final long BLOOD_SPIKE_LIFETIME_MS = 2400L;
    private static final long BLOOD_SPIKE_HONING_DELAY_MS = 650L;
    private static final double BLOOD_SPIKE_HONING_STRENGTH = 0.14d;

    private static final Map<UUID, Map<UUID, Integer>> PENDING_THROWN_BLOOD_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, BloodProjectile>> BLOOD_PROJECTILES_BY_WORLD = new HashMap<>();
    private static final Map<UUID, List<BloodSpike>> BLOOD_SPIKES_BY_WORLD = new HashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        List<PlayerSnapshot> players = collectPlayers(store);
        claimPendingThrownBloodProjectiles(store, world, worldId);
        processThrownBloodProjectiles(store, world, worldId, players);
        processBloodSpikes(store, world, worldId, players, now, dt);
    }

    public static void queueThrownBloodProjectile(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PENDING_THROWN_BLOOD_BY_WORLD) {
            PENDING_THROWN_BLOOD_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new HashMap<>())
                    .merge(bossId, 1, Integer::sum);
        }
    }

    public static boolean isBloodProjectile(@Nonnull UUID worldId, @Nonnull UUID projectileId) {
        synchronized (BLOOD_PROJECTILES_BY_WORLD) {
            Map<UUID, BloodProjectile> worldProjectiles = BLOOD_PROJECTILES_BY_WORLD.get(worldId);
            return worldProjectiles != null && worldProjectiles.containsKey(projectileId);
        }
    }

    public static void triggerSelfBloodBurst(
            @Nonnull World world,
            @Nonnull UUID ownerBossId,
            @Nullable Ref<EntityStore> ownerRef,
            @Nonnull Vector3d origin,
            @Nullable Vector3d targetPosition
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (ownerRef != null && ownerRef.isValid()) {
            applyDamage(store, ownerRef, BLOOD_SELF_COST, true);
        }
        spawnBloodSpikes(world, ownerBossId, origin, targetPosition);
        System.out.println("[PotionWitch][" + ownerBossId + "] bloodImpact self spikes=" + BLOOD_SPIKE_COUNT + " origin=" + fmtVec(origin.getX(), origin.getY(), origin.getZ()));
        broadcastToWorld(world, "Potion Brewer Witch tears into its own blood and sends spikes racing across the ground.");
    }

    public static void spawnDebugBloodSpikes(@Nonnull World world, @Nonnull Vector3d origin) {
        spawnBloodSpikes(world, null, origin, null);
    }

    public static void triggerThrownBloodImpact(
            @Nonnull World world,
            @Nullable UUID ownerBossId,
            @Nonnull Vector3d impact
    ) {
        spawnBloodSpikes(world, ownerBossId, impact, null);
        System.out.println("[PotionWitch][" + ownerBossId + "] bloodImpact thrown spikes=" + BLOOD_SPIKE_COUNT + " impact=" + fmtVec(impact.getX(), impact.getY(), impact.getZ()));
        broadcastToWorld(world, "Potion Brewer Witch's blood potion bursts into racing spikes.");
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        synchronized (PENDING_THROWN_BLOOD_BY_WORLD) {
            PENDING_THROWN_BLOOD_BY_WORLD.remove(worldId);
        }
        synchronized (BLOOD_PROJECTILES_BY_WORLD) {
            BLOOD_PROJECTILES_BY_WORLD.remove(worldId);
        }
        synchronized (BLOOD_SPIKES_BY_WORLD) {
            BLOOD_SPIKES_BY_WORLD.remove(worldId);
        }
    }

    private static void spawnBloodSpikes(
            @Nonnull World world,
            @Nullable UUID ownerBossId,
            @Nonnull Vector3d origin,
            @Nullable Vector3d targetPosition
    ) {
        UUID worldId = world.getWorldConfig().getUuid();
        double baseY = Math.floor(origin.getY());
        long startAt = System.currentTimeMillis();
        ArrayList<BloodSpike> spikes = new ArrayList<>();
        double baseAngle = resolveBaseAngle(origin, targetPosition);
        for (int i = 0; i < BLOOD_SPIKE_COUNT; i++) {
            double angle = baseAngle + ((Math.PI * 2.0d * i) / BLOOD_SPIKE_COUNT);
            double dirX = Math.cos(angle);
            double dirZ = Math.sin(angle);
            double startX = origin.getX() + (dirX * BLOOD_SPIKE_START_DISTANCE);
            double startZ = origin.getZ() + (dirZ * BLOOD_SPIKE_START_DISTANCE);
            spikes.add(new BloodSpike(
                    ownerBossId,
                    startX,
                    baseY,
                    startZ,
                    dirX,
                    dirZ,
                    startAt,
                    startAt + BLOOD_SPIKE_LIFETIME_MS
            ));
        }
        synchronized (BLOOD_SPIKES_BY_WORLD) {
            BLOOD_SPIKES_BY_WORLD.computeIfAbsent(worldId, ignored -> new ArrayList<>()).addAll(spikes);
        }
    }

    private static double resolveBaseAngle(@Nonnull Vector3d origin, @Nullable Vector3d targetPosition) {
        if (targetPosition == null) {
            return 0.0d;
        }
        double dx = targetPosition.getX() - origin.getX();
        double dz = targetPosition.getZ() - origin.getZ();
        if ((dx * dx) + (dz * dz) < 0.0001d) {
            return 0.0d;
        }
        return Math.atan2(dz, dx);
    }

    @Nonnull
    private static List<PlayerSnapshot> collectPlayers(@Nonnull Store<EntityStore> store) {
        ArrayList<PlayerSnapshot> players = new ArrayList<>();
        store.forEachChunk(PLAYER, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                UUID playerId = getEntityUuid(store, playerRef);
                if (playerRef == null || !playerRef.isValid() || transform == null || playerId == null) {
                    continue;
                }
                players.add(new PlayerSnapshot(playerRef, playerId, new Vector3d(transform.getPosition())));
            }
        });
        return players;
    }

    private void claimPendingThrownBloodProjectiles(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId
    ) {
        ArrayList<Ref<EntityStore>> suppressedProjectiles = new ArrayList<>();
        store.forEachChunk(PROJECTILE, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                StandardPhysicsProvider physics = chunk.getComponent(i, STANDARD_PHYSICS);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                ModelComponent model = chunk.getComponent(i, MODEL);
                Ref<EntityStore> projectileRef = chunk.getReferenceTo(i);
                if (physics == null || transform == null || model == null || projectileRef == null || !projectileRef.isValid()) {
                    continue;
                }
                String modelId = model.getModel() == null ? null : model.getModel().getModelAssetId();
                if (!BLOOD_PROJECTILE_MODEL_ID.equals(modelId)) {
                    continue;
                }

                UUID projectileId = getEntityUuid(store, projectileRef);
                UUID creatorUuid = physics.getCreatorUuid();
                if (projectileId == null || creatorUuid == null || isBloodProjectile(worldId, projectileId)) {
                    continue;
                }
                if (!isCreatedByPotionWitch(store, creatorUuid)) {
                    continue;
                }

                if (PotionBrewerWitchSystem.consumePendingProjectileSuppression(worldId, creatorUuid, BLOOD_POTION)) {
                    suppressedProjectiles.add(projectileRef);
                    System.out.println("[PotionWitch][" + creatorUuid + "] suppressProjectile blood self-use projectile=" + projectileId);
                    continue;
                }

                if (consumePendingThrownBloodProjectile(worldId, creatorUuid)) {
                    synchronized (BLOOD_PROJECTILES_BY_WORLD) {
                        BLOOD_PROJECTILES_BY_WORLD
                                .computeIfAbsent(worldId, ignoredWorld -> new HashMap<>())
                                .put(projectileId, new BloodProjectile(projectileId, creatorUuid));
                    }
                    System.out.println("[PotionWitch][" + creatorUuid + "] projectileClaim blood projectile=" + projectileId);
                    broadcastToWorld(world, "Potion Brewer Witch hurls a blood potion.");
                }
            }
        });

        for (Ref<EntityStore> projectileRef : suppressedProjectiles) {
            if (projectileRef != null && projectileRef.isValid()) {
                store.removeEntity(projectileRef, RemoveReason.REMOVE);
            }
        }
    }

    private void processThrownBloodProjectiles(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull List<PlayerSnapshot> players
    ) {
        Map<UUID, BloodProjectile> claimedProjectiles;
        synchronized (BLOOD_PROJECTILES_BY_WORLD) {
            claimedProjectiles = BLOOD_PROJECTILES_BY_WORLD.get(worldId) == null
                    ? Map.of()
                    : new HashMap<>(BLOOD_PROJECTILES_BY_WORLD.get(worldId));
        }
        if (claimedProjectiles.isEmpty()) {
            return;
        }

        HashSet<UUID> seen = new HashSet<>();
        ArrayList<Ref<EntityStore>> toRemove = new ArrayList<>();
        ArrayList<UUID> consumedProjectiles = new ArrayList<>();

        store.forEachChunk(PROJECTILE, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> projectileRef = chunk.getReferenceTo(i);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                StandardPhysicsProvider physics = chunk.getComponent(i, STANDARD_PHYSICS);
                if (projectileRef == null || !projectileRef.isValid() || transform == null || physics == null) {
                    continue;
                }

                UUID projectileId = getEntityUuid(store, projectileRef);
                if (projectileId == null) {
                    continue;
                }
                BloodProjectile bloodProjectile = claimedProjectiles.get(projectileId);
                if (bloodProjectile == null) {
                    continue;
                }
                seen.add(projectileId);

                PlayerSnapshot hit = findHitPlayer(transform.getPosition(), players, BLOOD_PROJECTILE_HIT_RADIUS);
                if (hit != null) {
                    float dealt = applyDamage(store, hit.playerRef, BLOOD_THROW_DAMAGE, false);
                    if (dealt > 0.0f) {
                        healBoss(store, bloodProjectile.ownerBossId, dealt * BLOOD_HEAL_RATIO);
                    }
                    broadcastToWorld(world, "Potion Brewer Witch's blood potion bursts and siphons life back to the boss.");
                    System.out.println("[PotionWitch][" + bloodProjectile.ownerBossId + "] projectileBreak blood projectile=" + projectileId + " reason=hit");
                    toRemove.add(projectileRef);
                    consumedProjectiles.add(projectileId);
                    continue;
                }

                if (physics.getState() == StandardPhysicsProvider.STATE.RESTING && physics.isOnGround()) {
                    System.out.println("[PotionWitch][" + bloodProjectile.ownerBossId + "] projectileBreak blood projectile=" + projectileId + " reason=ground");
                    toRemove.add(projectileRef);
                    consumedProjectiles.add(projectileId);
                }
            }
        });

        for (Ref<EntityStore> projectileRef : toRemove) {
            if (projectileRef != null && projectileRef.isValid()) {
                store.removeEntity(projectileRef, RemoveReason.REMOVE);
            }
        }

        synchronized (BLOOD_PROJECTILES_BY_WORLD) {
            Map<UUID, BloodProjectile> worldProjectiles = BLOOD_PROJECTILES_BY_WORLD.get(worldId);
            if (worldProjectiles == null) {
                return;
            }
            for (UUID projectileId : consumedProjectiles) {
                worldProjectiles.remove(projectileId);
            }
            Iterator<UUID> iterator = worldProjectiles.keySet().iterator();
            while (iterator.hasNext()) {
                UUID projectileId = iterator.next();
                if (!seen.contains(projectileId)) {
                    iterator.remove();
                }
            }
            if (worldProjectiles.isEmpty()) {
                BLOOD_PROJECTILES_BY_WORLD.remove(worldId);
            }
        }
    }

    private void processBloodSpikes(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull List<PlayerSnapshot> players,
            long now,
            float dt
    ) {
        List<BloodSpike> spikes;
        synchronized (BLOOD_SPIKES_BY_WORLD) {
            spikes = BLOOD_SPIKES_BY_WORLD.get(worldId) == null
                    ? List.of()
                    : new ArrayList<>(BLOOD_SPIKES_BY_WORLD.get(worldId));
        }
        if (spikes.isEmpty()) {
            return;
        }

        ArrayList<BloodSpike> expired = new ArrayList<>();
        for (BloodSpike spike : spikes) {
            if (now >= spike.expiresAtMillis) {
                releaseSpikeVisuals(store, worldId, spike);
                expired.add(spike);
                continue;
            }
            ensureSpikeVisuals(store, worldId, spike);

            if (now - spike.startedAtMillis > BLOOD_SPIKE_HONING_DELAY_MS) {
                PlayerSnapshot target = findNearestPlayer(new Vector3d(spike.currentX, spike.baseY, spike.currentZ), players, 24.0d);
                if (target != null) {
                    double targetDx = target.position.getX() - spike.currentX;
                    double targetDz = target.position.getZ() - spike.currentZ;
                    double targetDist = Math.sqrt(targetDx * targetDx + targetDz * targetDz);
                    if (targetDist > 0.001d) {
                        targetDx /= targetDist;
                        targetDz /= targetDist;

                        spike.dirX += (targetDx - spike.dirX) * BLOOD_SPIKE_HONING_STRENGTH;
                        spike.dirZ += (targetDz - spike.dirZ) * BLOOD_SPIKE_HONING_STRENGTH;

                        double len = Math.sqrt(spike.dirX * spike.dirX + spike.dirZ * spike.dirZ);
                        if (len > 0.001d) {
                            spike.dirX /= len;
                            spike.dirZ /= len;
                        }
                    }
                }
            }

            spike.currentX += spike.dirX * BLOOD_SPIKE_SPEED * dt;
            spike.currentZ += spike.dirZ * BLOOD_SPIKE_SPEED * dt;

            double dx = spike.currentX - spike.originX;
            double dz = spike.currentZ - spike.originZ;
            double distSq = (dx * dx) + (dz * dz);
            if (distSq > BLOOD_SPIKE_MAX_DISTANCE * BLOOD_SPIKE_MAX_DISTANCE) {
                releaseSpikeVisuals(store, worldId, spike);
                expired.add(spike);
                continue;
            }

            boolean baseMoved = teleportSpike(spike.baseRef, store, spike.currentX, spike.baseY, spike.currentZ);
            boolean tipMoved = teleportSpike(spike.tipRef, store, spike.currentX, spike.baseY + BLOOD_SPIKE_BODY_OFFSET, spike.currentZ);
            if (!baseMoved || !tipMoved) {
                releaseSpikeVisuals(store, worldId, spike);
                expired.add(spike);
                continue;
            }

            boolean spikeRemoved = false;
            for (PlayerSnapshot player : players) {
                if (!spike.hitPlayers.add(player.playerId)) {
                    continue;
                }
                if (!isPlayerHitBySpike(player.position, spike.currentX, spike.baseY, spike.currentZ)) {
                    spike.hitPlayers.remove(player.playerId);
                    continue;
                }
                float dealt = applyDamage(store, player.playerRef, BLOOD_SPIKE_DAMAGE, false);
                if (dealt > 0.0f) {
                    healBoss(store, spike.ownerBossId, dealt * BLOOD_HEAL_RATIO);
                }

                releaseSpikeVisuals(store, worldId, spike);
                expired.add(spike);
                spikeRemoved = true;
                break;
            }

            if (spikeRemoved) {
                continue;
            }
        }

        if (expired.isEmpty()) {
            return;
        }
        synchronized (BLOOD_SPIKES_BY_WORLD) {
            List<BloodSpike> worldSpikes = BLOOD_SPIKES_BY_WORLD.get(worldId);
            if (worldSpikes == null) {
                return;
            }
            worldSpikes.removeAll(expired);
            if (worldSpikes.isEmpty()) {
                BLOOD_SPIKES_BY_WORLD.remove(worldId);
            }
        }
    }

    private static boolean isPlayerHitBySpike(
            @Nonnull Vector3d playerPos,
            double spikeX,
            double spikeY,
            double spikeZ
    ) {
        double dx = playerPos.getX() - spikeX;
        double dz = playerPos.getZ() - spikeZ;
        double distSq = (dx * dx) + (dz * dz);
        return distSq <= (BLOOD_SPIKE_HIT_RADIUS * BLOOD_SPIKE_HIT_RADIUS)
                && Math.abs(playerPos.getY() - spikeY) <= BLOOD_SPIKE_VERTICAL_TOLERANCE;
    }

    @Nullable
    private static PlayerSnapshot findHitPlayer(
            @Nonnull Vector3d projectilePos,
            @Nonnull List<PlayerSnapshot> players,
            double radius
    ) {
        double radiusSq = radius * radius;
        for (PlayerSnapshot player : players) {
            double dx = player.position.getX() - projectilePos.getX();
            double dz = player.position.getZ() - projectilePos.getZ();
            double distSq = (dx * dx) + (dz * dz);
            if (distSq <= radiusSq && Math.abs(player.position.getY() - projectilePos.getY()) <= 2.0d) {
                return player;
            }
        }
        return null;
    }

    private static void ensureSpikeVisuals(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID worldId,
            @Nonnull BloodSpike spike
    ) {
        if (spike.baseRef == null || !spike.baseRef.isValid()) {
            spike.baseRef = acquireSpikeBlock(store, worldId, spike.currentX, spike.baseY, spike.currentZ, BLOOD_SPIKE_BASE_SCALE);
        }
        if (spike.tipRef == null || !spike.tipRef.isValid()) {
            spike.tipRef = acquireSpikeBlock(store, worldId, spike.currentX, spike.baseY + BLOOD_SPIKE_BODY_OFFSET, spike.currentZ, BLOOD_SPIKE_TIP_SCALE);
        }
    }

    private static void releaseSpikeVisuals(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID worldId,
            @Nonnull BloodSpike spike
    ) {
        removeSpikeRef(store, spike.baseRef);
        removeSpikeRef(store, spike.tipRef);
        spike.baseRef = null;
        spike.tipRef = null;
    }

    private static boolean teleportSpike(
            @Nullable Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            double x,
            double y,
            double z
    ) {
        if (ref == null || !ref.isValid()) {
            return false;
        }
        World world = store.getExternalData().getWorld();
        if (!isChunkLoaded(world, x, z)) {
            System.out.println("[PotionWitch][bloodSpike] skipMove unloadedChunk pos=" + fmtVec(x, y, z));
            return false;
        }
        TransformComponent transform = store.getComponent(ref, TRANSFORM);
        if (transform == null) {
            return false;
        }
        TransformComponent updated = transform.clone();
        updated.teleportPosition(new Vector3d(x, y, z));
        updated.markChunkDirty(store);
        store.putComponent(ref, TRANSFORM, updated);
        return true;
    }

    @Nullable
    private static Ref<EntityStore> acquireSpikeBlock(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID worldId,
            double x,
            double y,
            double z,
            float scale
    ) {
        World world = store.getExternalData().getWorld();
        if (!isChunkLoaded(world, x, z)) {
            System.out.println("[PotionWitch][bloodSpike] skipSpawn unloadedChunk pos=" + fmtVec(x, y, z));
            return null;
        }
        return spawnSpikeBlock(store, x, y, z, scale);
    }

    private static void removeSpikeRef(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> spikeRef
    ) {
        if (spikeRef == null || !spikeRef.isValid()) {
            return;
        }
        store.putComponent(spikeRef, EntityScaleComponent.getComponentType(), new EntityScaleComponent(0.01f));
        store.putComponent(spikeRef, Velocity.getComponentType(), new Velocity());
    }

    @Nullable
    private static Ref<EntityStore> spawnSpikeBlock(
            @Nonnull Store<EntityStore> store,
            double x,
            double y,
            double z,
            float scale
    ) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(((EntityStore) store.getExternalData()).takeNextNetworkId()));
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(BLOOD_SPIKE_BLOCK_ID));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(x, y, z), Vector3f.FORWARD));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        holder.addComponent(Velocity.getComponentType(), new Velocity());
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

    private static boolean isChunkLoaded(@Nonnull World world, double x, double z) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock((int) Math.floor(x), (int) Math.floor(z));
        return world.getChunk(chunkIndex) != null;
    }

    @Nonnull
    private static String fmtVec(double x, double y, double z) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", x, y, z);
    }

    private static boolean consumePendingThrownBloodProjectile(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PENDING_THROWN_BLOOD_BY_WORLD) {
            Map<UUID, Integer> worldPending = PENDING_THROWN_BLOOD_BY_WORLD.get(worldId);
            if (worldPending == null) {
                return false;
            }
            Integer current = worldPending.get(bossId);
            if (current == null || current <= 0) {
                return false;
            }
            if (current == 1) {
                worldPending.remove(bossId);
            } else {
                worldPending.put(bossId, current - 1);
            }
            if (worldPending.isEmpty()) {
                PENDING_THROWN_BLOOD_BY_WORLD.remove(worldId);
            }
            return true;
        }
    }

    private static boolean isCreatedByPotionWitch(@Nonnull Store<EntityStore> store, @Nonnull UUID creatorUuid) {
        EntityStore entityStore = (EntityStore) store.getExternalData();
        Ref<EntityStore> creatorRef = entityStore.getRefFromUUID(creatorUuid);
        if (creatorRef == null || !creatorRef.isValid()) {
            return false;
        }
        NPCEntity npc = store.getComponent(creatorRef, NPC);
        return npc != null && ROLE_NAME.equals(npc.getRoleName());
    }

    @Nullable
    private static PlayerSnapshot findNearestPlayer(
            @Nonnull Vector3d origin,
            @Nonnull List<PlayerSnapshot> players,
            double maxDistance
    ) {
        PlayerSnapshot best = null;
        double bestDistance = maxDistance;
        for (PlayerSnapshot player : players) {
            double dx = player.position.getX() - origin.getX();
            double dy = player.position.getY() - origin.getY();
            double dz = player.position.getZ() - origin.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    @Nullable
    private static UUID getEntityUuid(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent component = store.getComponent(ref, UUIDComponent.getComponentType());
        return component == null ? null : component.getUuid();
    }

    private static float applyDamage(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> targetRef,
            float amount,
            boolean allowPotionWitchTarget
    ) {
        if (!allowPotionWitchTarget && isPotionBrewerWitch(store, targetRef)) {
            return 0.0f;
        }
        float currentHealth = readHealth(store, targetRef);
        if (currentHealth <= 0.0f || amount <= 0.0f) {
            return 0.0f;
        }
        DamageSystems.executeDamage(
                targetRef,
                store,
                new Damage(Damage.NULL_SOURCE, resolveDamageCauseIndex(), amount)
        );
        return Math.min(amount, currentHealth);
    }

    private static boolean isPotionBrewerWitch(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        NPCEntity npc = store.getComponent(ref, NPC);
        return npc != null && ROLE_NAME.equals(npc.getRoleName());
    }

    private static void healBoss(
            @Nonnull Store<EntityStore> store,
            @Nullable UUID ownerBossId,
            float amount
    ) {
        if (ownerBossId == null || amount <= 0.0f) {
            return;
        }
        EntityStore entityStore = (EntityStore) store.getExternalData();
        Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerBossId);
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }
        EntityStatMap stats = store.getComponent(ownerRef, STATS);
        if (stats == null) {
            return;
        }
        float currentHealth = readHealth(store, ownerRef);
        float healCeiling = PotionBrewerWitchSystem.getHealCeilingForCurrentHealth(currentHealth);
        if (currentHealth < 0.0f || currentHealth >= healCeiling) {
            return;
        }
        EntityStatMap updated = stats.clone();
        updated.addStatValue(HEALTH_STAT_INDEX, Math.min(amount, healCeiling - currentHealth));
        store.putComponent(ownerRef, STATS, updated);
    }

    private static float readHealth(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap stats = store.getComponent(ref, STATS);
        if (stats == null || HEALTH_STAT_INDEX < 0 || stats.get(HEALTH_STAT_INDEX) == null) {
            return -1.0f;
        }
        return stats.get(HEALTH_STAT_INDEX).get();
    }

    private static int resolveDamageCauseIndex() {
        int command = DamageCause.getAssetMap().getIndex("Command");
        if (command != Integer.MIN_VALUE) {
            return command;
        }
        int environment = DamageCause.getAssetMap().getIndex("Environment");
        return environment != Integer.MIN_VALUE ? environment : 0;
    }

    private static void broadcastToWorld(@Nonnull World world, @Nonnull String text) {
    }

    private static final class BloodProjectile {
        private final UUID projectileId;
        private final UUID ownerBossId;

        private BloodProjectile(@Nonnull UUID projectileId, @Nonnull UUID ownerBossId) {
            this.projectileId = projectileId;
            this.ownerBossId = ownerBossId;
        }
    }

    private static final class BloodSpike {
        private final UUID ownerBossId;
        private final double originX;
        private final double baseY;
        private final double originZ;
        private double currentX;
        private double currentZ;
        private double dirX;
        private double dirZ;
        private final long startedAtMillis;
        private final long expiresAtMillis;
        private final Set<UUID> hitPlayers = new HashSet<>();
        private Ref<EntityStore> baseRef;
        private Ref<EntityStore> tipRef;

        private BloodSpike(
                @Nullable UUID ownerBossId,
                double x,
                double baseY,
                double z,
                double dirX,
                double dirZ,
                long startedAtMillis,
                long expiresAtMillis
        ) {
            this.ownerBossId = ownerBossId;
            this.originX = x - (dirX * BLOOD_SPIKE_START_DISTANCE);
            this.currentX = x;
            this.baseY = baseY;
            this.originZ = z - (dirZ * BLOOD_SPIKE_START_DISTANCE);
            this.currentZ = z;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.startedAtMillis = startedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class PlayerSnapshot {
        private final Ref<EntityStore> playerRef;
        private final UUID playerId;
        private final Vector3d position;

        private PlayerSnapshot(
                @Nonnull Ref<EntityStore> playerRef,
                @Nonnull UUID playerId,
                @Nonnull Vector3d position
        ) {
            this.playerRef = playerRef;
            this.playerId = playerId;
            this.position = position;
        }
    }
}
