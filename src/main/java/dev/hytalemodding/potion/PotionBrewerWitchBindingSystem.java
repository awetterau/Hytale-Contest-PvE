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
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedWaveConfig;
import it.unimi.dsi.fastutil.Pair;

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

public final class PotionBrewerWitchBindingSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, Projectile> PROJECTILE = Projectile.getComponentType();
    private static final ComponentType<EntityStore, StandardPhysicsProvider> STANDARD_PHYSICS = StandardPhysicsProvider.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, ModelComponent> MODEL = ModelComponent.getComponentType();
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, EntityStatMap> STATS = EntityStatMap.getComponentType();

    private static final String ROLE_NAME = "Potion_Brewer_Witch";
    private static final String BINDING_PROJECTILE_MODEL_ID = "Bomb_Potion_Poison";
    private static final String BINDING_VISUAL_BLOCK_ID = RedWaveConfig.CRIMSON_BLOCK_ID;
    private static final String BINDING_HURTBOX_ROLE = "Binding_Hurtbox_Target";
    private static final float MIN_LOCKED_MOVEMENT_VALUE = 0.01f;
    private static final long THROWN_ZONE_DURATION_MS = 2400L;
    private static final long SELF_ZONE_DURATION_MS = 5000L;
    private static final double THROWN_BINDING_ZONE_RADIUS = 3.25d;
    private static final double SELF_BINDING_ZONE_RADIUS = 5.25d;
    private static final double BINDING_ZONE_VERTICAL_TOLERANCE = 2.5d;
    private static final double BINDING_PROJECTILE_HIT_RADIUS = 1.3d;
    private static final double LOCK_POSITION_EPSILON = 0.01d;
    private static final float BINDING_VISUAL_SCALE = 0.9f;
    private static final float BINDING_HURTBOX_SCALE = 0.45f;
    private static final double BINDING_VISUAL_Y_OFFSET = 0.65d;
    private static final int ZONE_VISUAL_COUNT = 8;
    private static final float ZONE_VISUAL_SCALE = 0.55f;
    private static final double ZONE_VISUAL_HEIGHT = 0.05d;
    private static final int HEALTH_STAT_INDEX = DefaultEntityStatTypes.getHealth();
    private static final Vector3d HIDDEN_ZONE_VISUAL_POSITION = new Vector3d(0.0d, -16.0d, 0.0d);
    private static final float HIDDEN_ZONE_VISUAL_SCALE = 0.01f;

    private static final Map<UUID, Map<UUID, Integer>> PENDING_THROWN_BINDING_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, BindingProjectile>> BINDING_PROJECTILES_BY_WORLD = new HashMap<>();
    private static final Map<UUID, List<BindingZone>> BINDING_ZONES_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, BoundPlayer>> BOUND_PLAYERS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, List<DebugHurtboxPair>> DEBUG_HURTBOX_PAIRS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, List<Ref<EntityStore>>> ZONE_VISUAL_POOL_BY_WORLD = new HashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        List<PlayerSnapshot> players = collectPlayers(store);

        claimPendingThrownBindingProjectiles(store, world, worldId);
        processThrownBindingProjectiles(store, world, worldId, players, now);
        processBindingZones(store, world, worldId, players, now);
        processBoundPlayers(store, world, worldId);
        processDebugHurtboxPairs(store, worldId);
    }

    public static void queueThrownBindingProjectile(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PENDING_THROWN_BINDING_BY_WORLD) {
            PENDING_THROWN_BINDING_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new HashMap<>())
                    .merge(bossId, 1, Integer::sum);
        }
    }

    public static void triggerSelfBindingZone(
            @Nonnull World world,
            @Nonnull UUID ownerBossId,
            @Nullable Ref<EntityStore> ownerRef,
            @Nonnull Vector3d origin
    ) {
        long now = System.currentTimeMillis();
        addZone(
                world.getWorldConfig().getUuid(),
                new BindingZone(
                        ownerBossId,
                        ownerRef,
                        true,
                        origin.getX(),
                        Math.floor(origin.getY()),
                        origin.getZ(),
                        SELF_BINDING_ZONE_RADIUS,
                        now + SELF_ZONE_DURATION_MS
                )
        );
        broadcastToWorld(world, "Potion Brewer Witch uncorks a binding brew and a snaring zone clings to her feet.");
    }

    public static void spawnDebugThrownBindingZone(
            @Nonnull World world,
            @Nonnull Vector3d origin
    ) {
        long now = System.currentTimeMillis();
        addZone(
                world.getWorldConfig().getUuid(),
                new BindingZone(
                        UUID.randomUUID(),
                        null,
                        false,
                        origin.getX(),
                        Math.floor(origin.getY()),
                        origin.getZ(),
                        THROWN_BINDING_ZONE_RADIUS,
                        now + THROWN_ZONE_DURATION_MS
                )
        );
    }

    public static void spawnDebugSelfBindingZone(
            @Nonnull World world,
            @Nonnull UUID ownerId,
            @Nullable Ref<EntityStore> ownerRef,
            @Nonnull Vector3d origin
    ) {
        long now = System.currentTimeMillis();
        addZone(
                world.getWorldConfig().getUuid(),
                new BindingZone(
                        ownerId,
                        ownerRef,
                        true,
                        origin.getX(),
                        Math.floor(origin.getY()),
                        origin.getZ(),
                        SELF_BINDING_ZONE_RADIUS,
                        now + SELF_ZONE_DURATION_MS
                )
        );
    }

    @Nullable
    public static Ref<EntityStore> spawnDebugBindingVisual(
            @Nonnull World world,
            @Nonnull Vector3d position
    ) {
        return spawnBlockVisual(world.getEntityStore().getStore(), floorToFeet(position), 0.55f);
    }

    public static void registerDebugHurtboxPair(
            @Nonnull UUID worldId,
            @Nonnull Ref<EntityStore> hurtboxRef,
            @Nonnull Ref<EntityStore> visualRef
    ) {
        synchronized (DEBUG_HURTBOX_PAIRS_BY_WORLD) {
            DEBUG_HURTBOX_PAIRS_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new ArrayList<>())
                    .add(new DebugHurtboxPair(hurtboxRef, visualRef));
        }
    }

    public static void applyTemporaryMovementLock(
            @Nonnull UUID worldId,
            @Nonnull UUID playerId,
            @Nonnull Vector3d lockPosition,
            long expiresAtMillis
    ) {
        synchronized (BOUND_PLAYERS_BY_WORLD) {
            Map<UUID, BoundPlayer> worldBound = BOUND_PLAYERS_BY_WORLD.computeIfAbsent(worldId, ignored -> new HashMap<>());
            BoundPlayer existing = worldBound.get(playerId);
            if (existing != null) {
                existing.lockedPosition = floorToFeet(lockPosition);
                existing.temporaryLockExpiresAtMillis = Math.max(existing.temporaryLockExpiresAtMillis, expiresAtMillis);
                return;
            }
            BoundPlayer bound = new BoundPlayer(floorToFeet(lockPosition), null, null);
            bound.temporaryLockExpiresAtMillis = expiresAtMillis;
            worldBound.put(playerId, bound);
        }
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        synchronized (PENDING_THROWN_BINDING_BY_WORLD) {
            PENDING_THROWN_BINDING_BY_WORLD.remove(worldId);
        }
        synchronized (BINDING_PROJECTILES_BY_WORLD) {
            BINDING_PROJECTILES_BY_WORLD.remove(worldId);
        }
        synchronized (BINDING_ZONES_BY_WORLD) {
            List<BindingZone> zones = BINDING_ZONES_BY_WORLD.remove(worldId);
            if (zones != null) {
                for (BindingZone zone : zones) {
                    for (Ref<EntityStore> visualRef : zone.visualRefs) {
                        if (visualRef != null && visualRef.isValid()) {
                            Store<EntityStore> store = visualRef.getStore();
                            if (store != null) {
                                store.removeEntity(visualRef, RemoveReason.REMOVE);
                            }
                        }
                    }
                }
            }
        }
        synchronized (BOUND_PLAYERS_BY_WORLD) {
            Map<UUID, BoundPlayer> worldBoundPlayers = BOUND_PLAYERS_BY_WORLD.remove(worldId);
            if (worldBoundPlayers != null) {
                for (BoundPlayer bound : worldBoundPlayers.values()) {
                    removeEntityIfValid(bound.hurtboxRef);
                    removeEntityIfValid(bound.visualRef);
                }
            }
        }
        synchronized (DEBUG_HURTBOX_PAIRS_BY_WORLD) {
            List<DebugHurtboxPair> pairs = DEBUG_HURTBOX_PAIRS_BY_WORLD.remove(worldId);
            if (pairs != null) {
                for (DebugHurtboxPair pair : pairs) {
                    removeEntityIfValid(pair.hurtboxRef);
                    removeEntityIfValid(pair.visualRef);
                }
            }
        }
        synchronized (ZONE_VISUAL_POOL_BY_WORLD) {
            List<Ref<EntityStore>> pooled = ZONE_VISUAL_POOL_BY_WORLD.remove(worldId);
            if (pooled != null) {
                for (Ref<EntityStore> visualRef : pooled) {
                    removeEntityIfValid(visualRef);
                }
            }
        }
    }

    public static void clearAllBindingZones(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (BINDING_ZONES_BY_WORLD) {
            List<BindingZone> zones = BINDING_ZONES_BY_WORLD.get(worldId);
            if (zones != null) {
                zones.removeIf(zone -> bossId.equals(zone.ownerBossId));
            }
        }
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

    private void claimPendingThrownBindingProjectiles(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId
    ) {
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
                if (!BINDING_PROJECTILE_MODEL_ID.equals(modelId)) {
                    continue;
                }

                UUID projectileId = getEntityUuid(store, projectileRef);
                UUID creatorUuid = physics.getCreatorUuid();
                if (projectileId == null || creatorUuid == null || isBindingProjectile(worldId, projectileId)) {
                    continue;
                }
                if (!isCreatedByPotionWitch(store, creatorUuid)) {
                    continue;
                }
                if (!consumePendingThrownBindingProjectile(worldId, creatorUuid)) {
                    continue;
                }

                synchronized (BINDING_PROJECTILES_BY_WORLD) {
                    BINDING_PROJECTILES_BY_WORLD
                            .computeIfAbsent(worldId, ignoredWorld -> new HashMap<>())
                            .put(projectileId, new BindingProjectile(projectileId, creatorUuid));
                }
                broadcastToWorld(world, "Potion Brewer Witch hurls a binding potion.");
            }
        });
    }

    private void processThrownBindingProjectiles(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull List<PlayerSnapshot> players,
            long now
    ) {
        Map<UUID, BindingProjectile> claimedProjectiles;
        synchronized (BINDING_PROJECTILES_BY_WORLD) {
            claimedProjectiles = BINDING_PROJECTILES_BY_WORLD.get(worldId) == null
                    ? Map.of()
                    : new HashMap<>(BINDING_PROJECTILES_BY_WORLD.get(worldId));
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
                BindingProjectile bindingProjectile = claimedProjectiles.get(projectileId);
                if (bindingProjectile == null) {
                    continue;
                }
                seen.add(projectileId);

                PlayerSnapshot hit = findHitPlayer(transform.getPosition(), players, BINDING_PROJECTILE_HIT_RADIUS);
                if (hit != null || (physics.getState() == StandardPhysicsProvider.STATE.RESTING && physics.isOnGround())) {
                    Vector3d impact = new Vector3d(transform.getPosition());
                    addZone(
                            worldId,
                            new BindingZone(
                                    bindingProjectile.ownerBossId,
                                    null,
                                    false,
                                    impact.getX(),
                                    Math.floor(impact.getY()),
                                    impact.getZ(),
                                    THROWN_BINDING_ZONE_RADIUS,
                                    now + THROWN_ZONE_DURATION_MS
                            )
                    );
                    broadcastToWorld(world, "Potion Brewer Witch's binding potion shatters into a snaring field.");
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

        synchronized (BINDING_PROJECTILES_BY_WORLD) {
            Map<UUID, BindingProjectile> worldProjectiles = BINDING_PROJECTILES_BY_WORLD.get(worldId);
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
                BINDING_PROJECTILES_BY_WORLD.remove(worldId);
            }
        }
    }

    private void processBindingZones(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull List<PlayerSnapshot> players,
            long now
    ) {
        List<BindingZone> zones;
        synchronized (BINDING_ZONES_BY_WORLD) {
            zones = BINDING_ZONES_BY_WORLD.get(worldId) == null
                    ? List.of()
                    : new ArrayList<>(BINDING_ZONES_BY_WORLD.get(worldId));
        }
        if (zones.isEmpty()) {
            return;
        }

        ArrayList<BindingZone> expired = new ArrayList<>();
        for (BindingZone zone : zones) {
            if (now >= zone.expiresAtMillis) {
                releaseZoneVisuals(store, zone);
                expired.add(zone);
                continue;
            }

            if (zone.attachedToBoss && zone.ownerBossId != null) {
                Ref<EntityStore> bossRef = ((EntityStore) store.getExternalData()).getRefFromUUID(zone.ownerBossId);
                if (bossRef != null && bossRef.isValid()) {
                    TransformComponent bossTransform = store.getComponent(bossRef, TRANSFORM);
                    if (bossTransform != null) {
                        zone.x = bossTransform.getPosition().getX();
                        zone.y = Math.floor(bossTransform.getPosition().getY());
                        zone.z = bossTransform.getPosition().getZ();
                        zone.ownerBossRef = bossRef;
                    }
                }
            }

            if (zone.attachedToBoss) {
                ensureZoneVisuals(store, zone);
                updateZoneVisuals(store, zone);
            } else if (!zone.visualRefs.isEmpty()) {
                releaseZoneVisuals(store, zone);
            }

            HashSet<UUID> stillInside = new HashSet<>();
            for (PlayerSnapshot player : players) {
                if (!isInsideZone(zone, player.position)) {
                    continue;
                }
                stillInside.add(player.playerId);
                if (zone.playersInside.contains(player.playerId)) {
                    continue;
                }
                applyBindingToPlayer(store, world, worldId, player, now);
            }
            zone.playersInside.clear();
            zone.playersInside.addAll(stillInside);
        }

        synchronized (BINDING_ZONES_BY_WORLD) {
            List<BindingZone> worldZones = BINDING_ZONES_BY_WORLD.get(worldId);
            if (worldZones != null) {
                worldZones.removeAll(expired);
                if (worldZones.isEmpty()) {
                    BINDING_ZONES_BY_WORLD.remove(worldId);
                }
            }
        }
    }

    private void processBoundPlayers(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId
    ) {
        Map<UUID, BoundPlayer> boundPlayers;
        synchronized (BOUND_PLAYERS_BY_WORLD) {
            boundPlayers = BOUND_PLAYERS_BY_WORLD.get(worldId) == null
                    ? Map.of()
                    : new HashMap<>(BOUND_PLAYERS_BY_WORLD.get(worldId));
        }
        if (boundPlayers.isEmpty()) {
            return;
        }

        ArrayList<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, BoundPlayer> entry : boundPlayers.entrySet()) {
            UUID playerId = entry.getKey();
            BoundPlayer bound = entry.getValue();
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || !playerRef.isValid() || !worldId.equals(playerRef.getWorldUuid())) {
                cleanupBoundPlayer(store, playerRef, bound);
                expired.add(playerId);
                continue;
            }

            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                cleanupBoundPlayer(store, playerRef, bound);
                expired.add(playerId);
                continue;
            }

            TransformComponent transform = store.getComponent(playerEntityRef, TRANSFORM);
            if (transform == null) {
                cleanupBoundPlayer(store, playerRef, bound);
                expired.add(playerId);
                continue;
            }

            boolean hasBreakableBinding = bound.hurtboxRef != null || bound.visualRef != null;
            if (bound.temporaryLockExpiresAtMillis > 0L && System.currentTimeMillis() >= bound.temporaryLockExpiresAtMillis) {
                cleanupBoundPlayer(store, playerRef, bound);
                expired.add(playerId);
                continue;
            }

            boolean hurtboxAlive = isLivingEntityAlive(store, bound.hurtboxRef);
            float hurtboxHealth = readCurrentHealth(store, bound.hurtboxRef);
            if (hasBreakableBinding && (!hurtboxAlive || (hurtboxHealth >= 0.0f && hurtboxHealth <= 0.0f))) {
                cleanupBoundPlayer(store, playerRef, bound);
                playerRef.sendMessage(Message.raw("You break free of the binding snare."));
                expired.add(playerId);
                continue;
            }

            if (!bound.movementSettingsApplied) {
                applyZeroMovementSettings(playerRef, playerEntityRef, store);
                bound.movementSettingsApplied = true;
            }

            Vector3d currentPosition = transform.getPosition();
            double dx = currentPosition.getX() - bound.lockedPosition.getX();
            double dy = currentPosition.getY() - bound.lockedPosition.getY();
            double dz = currentPosition.getZ() - bound.lockedPosition.getZ();
            boolean drifted = (dx * dx) + (dy * dy) + (dz * dz) > LOCK_POSITION_EPSILON;

            if (drifted) {
                TransformComponent updated = transform.clone();
                updated.teleportPosition(new Vector3d(bound.lockedPosition));
                store.putComponent(playerEntityRef, TRANSFORM, updated);
            }

            Velocity velocity = store.getComponent(playerEntityRef, Velocity.getComponentType());
            if (velocity != null) {
                velocity.set(Vector3d.ZERO);
                store.putComponent(playerEntityRef, Velocity.getComponentType(), velocity);
            }

            if (bound.visualRef != null) {
                updateBindingVisual(store, bound.visualRef, bound.lockedPosition);
            }
            if (bound.hurtboxRef != null) {
                updateBindingHurtbox(store, bound.hurtboxRef, bound.lockedPosition);
            }
        }

        synchronized (BOUND_PLAYERS_BY_WORLD) {
            Map<UUID, BoundPlayer> worldBoundPlayers = BOUND_PLAYERS_BY_WORLD.get(worldId);
            if (worldBoundPlayers != null) {
                for (UUID playerId : expired) {
                    worldBoundPlayers.remove(playerId);
                }
                if (worldBoundPlayers.isEmpty()) {
                    BOUND_PLAYERS_BY_WORLD.remove(worldId);
                }
            }
        }
    }

    private void processDebugHurtboxPairs(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID worldId
    ) {
        synchronized (DEBUG_HURTBOX_PAIRS_BY_WORLD) {
            List<DebugHurtboxPair> pairs = DEBUG_HURTBOX_PAIRS_BY_WORLD.get(worldId);
            if (pairs == null || pairs.isEmpty()) {
                return;
            }

            Iterator<DebugHurtboxPair> iterator = pairs.iterator();
            while (iterator.hasNext()) {
                DebugHurtboxPair pair = iterator.next();
                boolean hurtboxAlive = isLivingEntityAlive(store, pair.hurtboxRef);
                float currentHealth = readCurrentHealth(store, pair.hurtboxRef);
                boolean consumed = !hurtboxAlive || (currentHealth >= 0.0f && currentHealth <= 0.0f);
                if (!consumed) {
                    continue;
                }

                removeEntityIfValid(pair.hurtboxRef);
                removeEntityIfValid(pair.visualRef);
                iterator.remove();
            }

            if (pairs.isEmpty()) {
                DEBUG_HURTBOX_PAIRS_BY_WORLD.remove(worldId);
            }
        }
    }

    private static void applyBindingToPlayer(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull PlayerSnapshot player,
            long now
    ) {
        synchronized (BOUND_PLAYERS_BY_WORLD) {
            Map<UUID, BoundPlayer> worldBound = BOUND_PLAYERS_BY_WORLD.computeIfAbsent(worldId, ignored -> new HashMap<>());
            BoundPlayer existing = worldBound.get(player.playerId);
            if (existing != null) {
                existing.lockedPosition = floorToFeet(player.position);
                existing.temporaryLockExpiresAtMillis = 0L;
                if (existing.visualRef == null || !existing.visualRef.isValid()) {
                    existing.visualRef = spawnBindingVisual(store, existing.lockedPosition);
                } else {
                    updateBindingVisual(store, existing.visualRef, existing.lockedPosition);
                }
                if (existing.hurtboxRef == null || !existing.hurtboxRef.isValid()) {
                    existing.hurtboxRef = spawnBindingHurtbox(store, existing.lockedPosition);
                } else {
                    updateBindingHurtbox(store, existing.hurtboxRef, existing.lockedPosition);
                }
                return;
            }

            Vector3d lockedPosition = floorToFeet(player.position);
            Ref<EntityStore> visualRef = spawnBindingVisual(store, lockedPosition);
            Ref<EntityStore> hurtboxRef = spawnBindingHurtbox(store, lockedPosition);
            worldBound.put(player.playerId, new BoundPlayer(lockedPosition, visualRef, hurtboxRef));
        }
        broadcastToWorld(world, "A binding snare clamps around a player's feet.");
    }

    private static boolean isInsideZone(@Nonnull BindingZone zone, @Nonnull Vector3d position) {
        double dx = position.getX() - zone.x;
        double dz = position.getZ() - zone.z;
        double horizontalDistanceSquared = (dx * dx) + (dz * dz);
        if (horizontalDistanceSquared > zone.radius * zone.radius) {
            return false;
        }
        return Math.abs(position.getY() - zone.y) <= BINDING_ZONE_VERTICAL_TOLERANCE;
    }

    private static void addZone(@Nonnull UUID worldId, @Nonnull BindingZone zone) {
        synchronized (BINDING_ZONES_BY_WORLD) {
            BINDING_ZONES_BY_WORLD.computeIfAbsent(worldId, ignored -> new ArrayList<>()).add(zone);
        }
    }

    private static void ensureZoneVisuals(
            @Nonnull Store<EntityStore> store,
            @Nonnull BindingZone zone
    ) {
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        while (zone.visualRefs.size() < ZONE_VISUAL_COUNT) {
            Ref<EntityStore> visualRef = claimZoneVisual(
                    worldId,
                    store,
                    new Vector3d(zone.x, zone.y + ZONE_VISUAL_HEIGHT, zone.z)
            );
            if (visualRef == null || !visualRef.isValid()) {
                return;
            }
            zone.visualRefs.add(visualRef);
        }
    }

    private static void updateZoneVisuals(
            @Nonnull Store<EntityStore> store,
            @Nonnull BindingZone zone
    ) {
        for (int i = 0; i < zone.visualRefs.size(); i++) {
            double angle = (Math.PI * 2.0d * i) / Math.max(1, zone.visualRefs.size());
            double x = zone.x + (Math.cos(angle) * zone.radius);
            double z = zone.z + (Math.sin(angle) * zone.radius);
            updateBlockVisual(
                    store,
                    zone.visualRefs.get(i),
                    new Vector3d(x, zone.y + ZONE_VISUAL_HEIGHT, z),
                    ZONE_VISUAL_SCALE
            );
        }
    }

    private static void releaseZoneVisuals(
            @Nonnull Store<EntityStore> store,
            @Nonnull BindingZone zone
    ) {
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        for (Ref<EntityStore> visualRef : zone.visualRefs) {
            recycleZoneVisual(worldId, store, visualRef);
        }
        zone.visualRefs.clear();
    }

    private static boolean isBindingProjectile(@Nonnull UUID worldId, @Nonnull UUID projectileId) {
        synchronized (BINDING_PROJECTILES_BY_WORLD) {
            Map<UUID, BindingProjectile> worldProjectiles = BINDING_PROJECTILES_BY_WORLD.get(worldId);
            return worldProjectiles != null && worldProjectiles.containsKey(projectileId);
        }
    }

    private static boolean consumePendingThrownBindingProjectile(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PENDING_THROWN_BINDING_BY_WORLD) {
            Map<UUID, Integer> worldPending = PENDING_THROWN_BINDING_BY_WORLD.get(worldId);
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
                PENDING_THROWN_BINDING_BY_WORLD.remove(worldId);
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
    private static Ref<EntityStore> spawnBindingVisual(
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d feetPosition
    ) {
        return spawnBlockVisual(store, liftedBindingPosition(feetPosition), BINDING_VISUAL_SCALE);
    }

    @Nullable
    private static Ref<EntityStore> spawnBindingHurtbox(
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d feetPosition
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(BINDING_HURTBOX_ROLE);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return null;
        }

        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                store,
                roleIndex,
                liftedBindingPosition(feetPosition),
                Vector3f.FORWARD,
                null,
                null
        );
        if (npcPair == null || npcPair.first() == null || !npcPair.first().isValid()) {
            return null;
        }

        store.putComponent(
                npcPair.first(),
                EntityScaleComponent.getComponentType(),
                new EntityScaleComponent(BINDING_HURTBOX_SCALE)
        );
        return npcPair.first();
    }

    @Nullable
    private static Ref<EntityStore> spawnBlockVisual(
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d position,
            float scale
    ) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(((EntityStore) store.getExternalData()).takeNextNetworkId()));
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(BINDING_VISUAL_BLOCK_ID));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(position), Vector3f.FORWARD));
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

    private static void updateBindingVisual(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> visualRef,
            @Nonnull Vector3d feetPosition
    ) {
        updateBlockVisual(store, visualRef, liftedBindingPosition(feetPosition), BINDING_VISUAL_SCALE);
    }

    private static void updateBindingHurtbox(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> hurtboxRef,
            @Nonnull Vector3d feetPosition
    ) {
        if (hurtboxRef == null || !hurtboxRef.isValid()) {
            return;
        }
        TransformComponent transform = store.getComponent(hurtboxRef, TRANSFORM);
        if (transform == null) {
            return;
        }
        TransformComponent updated = transform.clone();
        updated.teleportPosition(liftedBindingPosition(feetPosition));
        updated.markChunkDirty(store);
        store.putComponent(hurtboxRef, TRANSFORM, updated);
        store.putComponent(hurtboxRef, EntityScaleComponent.getComponentType(), new EntityScaleComponent(BINDING_HURTBOX_SCALE));
        Velocity velocity = store.getComponent(hurtboxRef, Velocity.getComponentType());
        if (velocity != null) {
            velocity.set(Vector3d.ZERO);
            store.putComponent(hurtboxRef, Velocity.getComponentType(), velocity);
        }
    }

    private static void updateBlockVisual(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> visualRef,
            @Nonnull Vector3d position,
            float scale
    ) {
        if (visualRef == null || !visualRef.isValid()) {
            return;
        }
        TransformComponent transform = store.getComponent(visualRef, TRANSFORM);
        if (transform == null) {
            return;
        }
        TransformComponent updated = transform.clone();
        updated.teleportPosition(new Vector3d(position));
        updated.markChunkDirty(store);
        store.putComponent(visualRef, TRANSFORM, updated);
        store.putComponent(visualRef, EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        store.putComponent(visualRef, Velocity.getComponentType(), new Velocity());
    }

    @Nullable
    private static Ref<EntityStore> claimZoneVisual(
            @Nonnull UUID worldId,
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d position
    ) {
        synchronized (ZONE_VISUAL_POOL_BY_WORLD) {
            List<Ref<EntityStore>> pooled = ZONE_VISUAL_POOL_BY_WORLD.get(worldId);
            if (pooled != null) {
                while (!pooled.isEmpty()) {
                    Ref<EntityStore> visualRef = pooled.remove(pooled.size() - 1);
                    if (visualRef != null && visualRef.isValid()) {
                        updateBlockVisual(store, visualRef, position, ZONE_VISUAL_SCALE);
                        return visualRef;
                    }
                }
                if (pooled.isEmpty()) {
                    ZONE_VISUAL_POOL_BY_WORLD.remove(worldId);
                }
            }
        }
        return spawnBlockVisual(store, position, ZONE_VISUAL_SCALE);
    }

    private static void recycleZoneVisual(
            @Nonnull UUID worldId,
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> visualRef
    ) {
        if (visualRef == null || !visualRef.isValid()) {
            return;
        }
        updateBlockVisual(store, visualRef, HIDDEN_ZONE_VISUAL_POSITION, HIDDEN_ZONE_VISUAL_SCALE);
        synchronized (ZONE_VISUAL_POOL_BY_WORLD) {
            ZONE_VISUAL_POOL_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new ArrayList<>())
                    .add(visualRef);
        }
    }

    private static void removeEntityIfValid(@Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }
        store.removeEntity(ref, RemoveReason.REMOVE);
    }

    private static boolean isLivingEntityAlive(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref
    ) {
        return ref != null
                && ref.isValid()
                && ref.getStore() == store
                && store.getComponent(ref, DeathComponent.getComponentType()) == null;
    }

    private static float readCurrentHealth(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref
    ) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return -1.0f;
        }
        EntityStatMap stats = store.getComponent(ref, STATS);
        if (stats == null || HEALTH_STAT_INDEX < 0 || stats.get(HEALTH_STAT_INDEX) == null) {
            return -1.0f;
        }
        return stats.get(HEALTH_STAT_INDEX).get();
    }

    private static void cleanupBoundPlayer(
            @Nonnull Store<EntityStore> store,
            @Nullable PlayerRef playerRef,
            @Nonnull BoundPlayer bound
    ) {
        if (playerRef != null && playerRef.isValid()) {
            restoreMovementDefaults(playerRef);
        }
        removeEntityIfValid(bound.hurtboxRef);
        if (bound.visualRef != null && bound.visualRef.isValid()) {
            store.removeEntity(bound.visualRef, RemoveReason.REMOVE);
        }
    }

    private static void releaseBoundPlayer(@Nonnull UUID worldId, @Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }

        BoundPlayer removed;
        synchronized (BOUND_PLAYERS_BY_WORLD) {
            Map<UUID, BoundPlayer> worldBoundPlayers = BOUND_PLAYERS_BY_WORLD.get(worldId);
            if (worldBoundPlayers == null) {
                return;
            }
            removed = worldBoundPlayers.remove(playerRef.getUuid());
            if (worldBoundPlayers.isEmpty()) {
                BOUND_PLAYERS_BY_WORLD.remove(worldId);
            }
        }
        if (removed != null) {
            cleanupBoundPlayer(store, playerRef, removed);
        }
    }

    private static void applyZeroMovementSettings(
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null || movementManager.getSettings() == null) {
            return;
        }

        MovementSettings settings = movementManager.getSettings();
        settings.baseSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.acceleration = MIN_LOCKED_MOVEMENT_VALUE;
        settings.jumpForce = MIN_LOCKED_MOVEMENT_VALUE;
        settings.swimJumpForce = MIN_LOCKED_MOVEMENT_VALUE;
        settings.jumpBufferDuration = 0.0f;
        settings.jumpBufferMaxYVelocity = 0.0f;
        settings.climbSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.climbSpeedLateral = MIN_LOCKED_MOVEMENT_VALUE;
        settings.climbUpSprintSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.climbDownSprintSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.horizontalFlySpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.verticalFlySpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.maxSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.minSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardWalkSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.backwardWalkSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.strafeWalkSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardRunSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.backwardRunSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.strafeRunSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardCrouchSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.backwardCrouchSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.strafeCrouchSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardSprintSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        movementManager.update(playerRef.getPacketHandler());
    }

    private static void restoreMovementDefaults(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }
        movementManager.resetDefaultsAndUpdate(ref, store);
    }

    @Nullable
    private static PlayerSnapshot findHitPlayer(
            @Nonnull Vector3d projectilePosition,
            @Nonnull List<PlayerSnapshot> players,
            double radius
    ) {
        for (PlayerSnapshot player : players) {
            if (distance(projectilePosition, player.position) <= radius) {
                return player;
            }
        }
        return null;
    }

    private static double distance(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    @Nonnull
    private static Vector3d floorToFeet(@Nonnull Vector3d position) {
        return new Vector3d(position.getX(), Math.floor(position.getY()), position.getZ());
    }

    @Nonnull
    private static Vector3d liftedBindingPosition(@Nonnull Vector3d feetPosition) {
        return new Vector3d(feetPosition.getX(), feetPosition.getY() + BINDING_VISUAL_Y_OFFSET, feetPosition.getZ());
    }

    @Nullable
    private static UUID getEntityUuid(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent component = store.getComponent(ref, UUIDComponent.getComponentType());
        return component == null ? null : component.getUuid();
    }

    private static void broadcastToWorld(@Nonnull World world, @Nonnull String text) {
        Message message = Message.raw(text);
        UUID worldId = world.getWorldConfig().getUuid();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorldId = playerRef.getWorldUuid();
            if (playerWorldId != null && playerWorldId.equals(worldId)) {
                playerRef.sendMessage(message);
            }
        }
    }

    private static final class BindingProjectile {
        private final UUID projectileId;
        private final UUID ownerBossId;

        private BindingProjectile(@Nonnull UUID projectileId, @Nonnull UUID ownerBossId) {
            this.projectileId = projectileId;
            this.ownerBossId = ownerBossId;
        }
    }

    private static final class BindingZone {
        @Nullable
        private final UUID ownerBossId;
        @Nullable
        private Ref<EntityStore> ownerBossRef;
        private final boolean attachedToBoss;
        private final double radius;
        private final long expiresAtMillis;
        private double x;
        private double y;
        private double z;
        private final Set<UUID> playersInside = new HashSet<>();
        private final List<Ref<EntityStore>> visualRefs = new ArrayList<>();

        private BindingZone(
                @Nullable UUID ownerBossId,
                @Nullable Ref<EntityStore> ownerBossRef,
                boolean attachedToBoss,
                double x,
                double y,
                double z,
                double radius,
                long expiresAtMillis
        ) {
            this.ownerBossId = ownerBossId;
            this.ownerBossRef = ownerBossRef;
            this.attachedToBoss = attachedToBoss;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class BoundPlayer {
        private Vector3d lockedPosition;
        @Nullable
        private Ref<EntityStore> visualRef;
        @Nullable
        private Ref<EntityStore> hurtboxRef;
        private boolean movementSettingsApplied;
        private long temporaryLockExpiresAtMillis;

        private BoundPlayer(
                @Nonnull Vector3d lockedPosition,
                @Nullable Ref<EntityStore> visualRef,
                @Nullable Ref<EntityStore> hurtboxRef
        ) {
            this.lockedPosition = lockedPosition;
            this.visualRef = visualRef;
            this.hurtboxRef = hurtboxRef;
        }
    }

    private static final class DebugHurtboxPair {
        private final Ref<EntityStore> hurtboxRef;
        private final Ref<EntityStore> visualRef;

        private DebugHurtboxPair(
                @Nonnull Ref<EntityStore> hurtboxRef,
                @Nonnull Ref<EntityStore> visualRef
        ) {
            this.hurtboxRef = hurtboxRef;
            this.visualRef = visualRef;
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
