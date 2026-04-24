package dev.hytalemodding.potion;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.projectile.component.Projectile;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PotionBrewerWitchProjectileSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, Projectile> PROJECTILE = Projectile.getComponentType();
    private static final ComponentType<EntityStore, StandardPhysicsProvider> STANDARD_PHYSICS = StandardPhysicsProvider.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, ModelComponent> MODEL = ModelComponent.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();

    private static final String ROLE_NAME = "Potion_Brewer_Witch";
    private static final String POISON_MODEL_ID = "Bomb_Potion_Poison";
    private static final String HEAL_POTION_MODEL_ID = "Bomb_Potion_Heal";
    private static final String BLOOD_POTION_MODEL_ID = "Bomb_Potion_Blood";
    private static final String BINDING_POTION_MODEL_ID = "Bomb_Potion_Binding";
    private static final String SHADOW_BOLT_MODEL_ID = "Skeleton_Mage_Corruption_Orb";
    private static final String HEAL_ZONE_BLOCK_ID = "Cloth_Block_Wool_Green_Light";
    private static final String HEAL_THROWN_ROOT_INTERACTION = "Potion_Brewer_Witch_Heal_Thrown_Attack";
    private static final double POISON_RADIUS = 2.25d;
    private static final double HEAL_ZONE_RADIUS = 6.0d;
    private static final long POISON_DURATION_MS = 4500L;
    private static final long HEAL_ZONE_DURATION_MS = 120000L;
    private static final long CRIMSON_PATCH_DURATION_MS = 5000L;
    private static final long RECENT_THROW_TIMEOUT_MS = 2000L;
    private static final long CLEANUP_INTERVAL_MS = 1000L;
    private static final Map<UUID, Map<UUID, Integer>> PENDING_THROWN_HEAL_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Set<UUID>> HEAL_PROJECTILES_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> RECENT_HEAL_THROWS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Set<Vector3i>> RECENT_HEAL_ZONE_POSITIONS = new HashMap<>();
    private static final long HEAL_ZONE_POSITION_TIMEOUT_MS = 3000L;
    private static final Vector3i[][] CRIMSON_PATCH_PATTERNS = new Vector3i[][]{
            new Vector3i[]{
                    new Vector3i(0, 0, 0),
                    new Vector3i(1, 0, 0),
                    new Vector3i(-1, 0, 0),
                    new Vector3i(0, 0, 1),
                    new Vector3i(0, 0, -1),
                    new Vector3i(1, 0, 1),
                    new Vector3i(1, 0, -1),
                    new Vector3i(-1, 0, 1),
                    new Vector3i(-1, 0, -1),
                    new Vector3i(2, 0, 0),
                    new Vector3i(-2, 0, 0),
                    new Vector3i(0, 0, 2),
                    new Vector3i(0, 0, -2),
                    new Vector3i(2, 0, 1),
                    new Vector3i(1, 0, 2),
                    new Vector3i(-2, 0, -1),
                    new Vector3i(-1, 0, -2),
                    new Vector3i(3, 0, 0),
                    new Vector3i(0, 0, -3)
            },
            new Vector3i[]{
                    new Vector3i(0, 0, 0),
                    new Vector3i(1, 0, 0),
                    new Vector3i(-1, 0, 0),
                    new Vector3i(0, 0, 1),
                    new Vector3i(0, 0, -1),
                    new Vector3i(1, 0, 1),
                    new Vector3i(1, 0, -1),
                    new Vector3i(-1, 0, 1),
                    new Vector3i(-1, 0, -1),
                    new Vector3i(2, 0, 0),
                    new Vector3i(-2, 0, 0),
                    new Vector3i(0, 0, 2),
                    new Vector3i(0, 0, -2),
                    new Vector3i(2, 0, -1),
                    new Vector3i(2, 0, -2),
                    new Vector3i(3, 0, -1),
                    new Vector3i(-2, 0, 1),
                    new Vector3i(-3, 0, 1),
                    new Vector3i(-1, 0, 2),
                    new Vector3i(-2, 0, 2),
                    new Vector3i(1, 0, -3),
                    new Vector3i(0, 0, 3)
            },
            new Vector3i[]{
                    new Vector3i(0, 0, 0),
                    new Vector3i(1, 0, 0),
                    new Vector3i(-1, 0, 0),
                    new Vector3i(0, 0, 1),
                    new Vector3i(0, 0, -1),
                    new Vector3i(1, 0, 1),
                    new Vector3i(1, 0, -1),
                    new Vector3i(-1, 0, 1),
                    new Vector3i(-1, 0, -1),
                    new Vector3i(2, 0, 0),
                    new Vector3i(-2, 0, 0),
                    new Vector3i(0, 0, 2),
                    new Vector3i(0, 0, -2),
                    new Vector3i(2, 0, 1),
                    new Vector3i(2, 0, -1),
                    new Vector3i(-2, 0, 1),
                    new Vector3i(-2, 0, -1),
                    new Vector3i(1, 0, 2),
                    new Vector3i(-1, 0, 2),
                    new Vector3i(1, 0, -2),
                    new Vector3i(-1, 0, -2),
                    new Vector3i(3, 0, 1),
                    new Vector3i(-3, 0, 0),
                    new Vector3i(0, 0, 3),
                    new Vector3i(-1, 0, -3)
            },
            new Vector3i[]{
                    new Vector3i(0, 0, 0),
                    new Vector3i(1, 0, 0),
                    new Vector3i(-1, 0, 0),
                    new Vector3i(0, 0, 1),
                    new Vector3i(0, 0, -1),
                    new Vector3i(1, 0, 1),
                    new Vector3i(-1, 0, 1),
                    new Vector3i(1, 0, -1),
                    new Vector3i(-1, 0, -1),
                    new Vector3i(2, 0, 0),
                    new Vector3i(-2, 0, 0),
                    new Vector3i(0, 0, 2),
                    new Vector3i(0, 0, -2),
                    new Vector3i(2, 0, 1),
                    new Vector3i(2, 0, 2),
                    new Vector3i(1, 0, 2),
                    new Vector3i(-2, 0, -1),
                    new Vector3i(-2, 0, -2),
                    new Vector3i(-1, 0, -2),
                    new Vector3i(3, 0, 2),
                    new Vector3i(2, 0, 3),
                    new Vector3i(-3, 0, -2),
                    new Vector3i(-2, 0, -3),
                    new Vector3i(0, 0, 3),
                    new Vector3i(1, 0, -3)
            }
    };

    private final Set<Ref<EntityStore>> announcedProjectiles = new HashSet<>();
    private long lastCleanupTime = 0L;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        restoreExpiredCrimsonPatches(world, worldId, now);
        restoreExpiredHealZones(world, worldId, now);
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupExpiredRecentThrows(worldId, now);
            cleanupExpiredHealZonePositions(worldId);
            lastCleanupTime = now;
        }
        List<Ref<EntityStore>> spentProjectiles = new ArrayList<>();

        store.forEachChunk(PROJECTILE, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                StandardPhysicsProvider physics = chunk.getComponent(i, STANDARD_PHYSICS);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                ModelComponent modelComponent = chunk.getComponent(i, MODEL);
                Ref<EntityStore> projectileRef = chunk.getReferenceTo(i);
                if (physics == null || transform == null || modelComponent == null || projectileRef == null || !projectileRef.isValid()) {
                    continue;
                }

                String modelId = modelComponent.getModel() == null ? null : modelComponent.getModel().getModelAssetId();
                if (modelId == null || !isCreatedByPotionWitch(store, physics)) {
                    continue;
                }

                UUID creatorUuid = getCreatorUuid(physics);
                UUID projectileId = getEntityUuid(store, projectileRef);
                boolean healProjectile = HEAL_POTION_MODEL_ID.equals(modelId);

                // Announce projectile only once
                if (this.announcedProjectiles.add(projectileRef)) {
                    if (projectileId != null && (PotionBrewerWitchBloodSystem.isBloodProjectile(worldId, projectileId) || PotionBrewerWitchBindingSystem.isBindingProjectile(worldId, projectileId))) {
                        continue;
                    }
                    if (healProjectile) {
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] projectileSpawn healingDraught");
                        }
                        broadcastToWorld(world, "Potion Brewer Witch throws a healing draught.");
                    } else if (POISON_MODEL_ID.equals(modelId)) {
                        if (creatorUuid != null
                                && PotionBrewerWitchSystem.consumePendingProjectileSuppression(worldId, creatorUuid, "poison potion")) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] suppressProjectile poison self-use");
                            spentProjectiles.add(projectileRef);
                            continue;
                        }
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] projectileSpawn poison");
                        }
                        broadcastToWorld(world, "Potion Brewer Witch throws a poison potion.");
                    } else if (SHADOW_BOLT_MODEL_ID.equals(modelId)) {
                        if (creatorUuid != null
                                && PotionBrewerWitchSystem.consumePendingProjectileSuppression(worldId, creatorUuid, "shadow bolt")) {
                            System.out.println("[PotionWitch][BOLT] Suppressing Shadow Bolt projectile for bossId=" + creatorUuid + " (Self-Use/Assassin Mode)");
                            spentProjectiles.add(projectileRef);
                            continue;
                        }
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][BOLT] Projectile SPAWNED for bossId=" + creatorUuid + ", projectileId=" + projectileId + ", pos=" + fmt(transform.getPosition()));
                        }
                        broadcastToWorld(world, "Potion Brewer Witch fires a shadow bolt.");
                    } else if (BLOOD_POTION_MODEL_ID.equals(modelId)) {
                        if (creatorUuid != null
                                && PotionBrewerWitchSystem.consumePendingProjectileSuppression(worldId, creatorUuid, "blood potion")) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] suppressProjectile blood self-use fallback");
                            spentProjectiles.add(projectileRef);
                            continue;
                        }
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] projectileSpawn blood fallbackWatch projectile=" + projectileId);
                        }
                    } else if (BINDING_POTION_MODEL_ID.equals(modelId)) {
                        if (creatorUuid != null
                                && PotionBrewerWitchSystem.consumePendingProjectileSuppression(worldId, creatorUuid, "binding potion")) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] suppressProjectile binding self-use fallback");
                            spentProjectiles.add(projectileRef);
                            continue;
                        }
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] projectileSpawn binding fallbackWatch projectile=" + projectileId);
                        }
                    }
                }

                if (physics.getState() == StandardPhysicsProvider.STATE.RESTING
                        && physics.isOnGround()) {
                    if (healProjectile) {
                        Vector3d impact = transform.getPosition();
                        if (shouldPlaceHealZone(worldId, impact, now)) {
                            placeHealZone(world, worldId, impact, now);
                            registerHealZonePosition(worldId, impact, now);
                            broadcastToWorld(world, "A healing zone forms where the potion shatters.");
                        }
                        spentProjectiles.add(projectileRef);
                    } else if (POISON_MODEL_ID.equals(modelId)
                            && (projectileId == null || (!PotionBrewerWitchBloodSystem.isBloodProjectile(worldId, projectileId) && !PotionBrewerWitchBindingSystem.isBindingProjectile(worldId, projectileId)))) {
                        Vector3d impact = transform.getPosition();
                        placeCrimsonPatch(world, worldId, impact, now);
                        PotionBrewerWitchPoisonRuntime.addPatch(new PotionBrewerWitchPoisonRuntime.Patch(
                                UUID.randomUUID(),
                                worldId,
                                impact.getX(),
                                impact.getY(),
                                impact.getZ(),
                                POISON_RADIUS,
                                now + POISON_DURATION_MS
                        ));
                        broadcastToWorld(world, "A toxic puddle spreads where the potion shatters.");
                        spentProjectiles.add(projectileRef);
                    } else if (BLOOD_POTION_MODEL_ID.equals(modelId)
                            && (projectileId == null || !PotionBrewerWitchBloodSystem.isBloodProjectile(worldId, projectileId))) {
                        PotionBrewerWitchBloodSystem.triggerThrownBloodImpact(
                                world,
                                creatorUuid,
                                new Vector3d(transform.getPosition())
                        );
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] projectileBreak blood fallback reason=ground projectile=" + projectileId + " impact=true");
                        }
                        spentProjectiles.add(projectileRef);
                    } else if (BINDING_POTION_MODEL_ID.equals(modelId)
                            && (projectileId == null || !PotionBrewerWitchBindingSystem.isBindingProjectile(worldId, projectileId))) {
                        PotionBrewerWitchBindingSystem.triggerThrownBindingImpact(
                                world,
                                creatorUuid,
                                new Vector3d(transform.getPosition())
                        );
                        if (creatorUuid != null) {
                            System.out.println("[PotionWitch][" + creatorUuid + "] projectileBreak binding fallback reason=ground projectile=" + projectileId + " impact=true");
                        }
                        spentProjectiles.add(projectileRef);
                    }
                }
            }
        });

        for (Ref<EntityStore> projectileRef : spentProjectiles) {
            if (projectileRef != null && projectileRef.isValid()) {
                store.removeEntity(projectileRef, RemoveReason.REMOVE);
            }
        }
    }

    public static void queueThrownHealProjectile(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        long now = System.currentTimeMillis();
        synchronized (PENDING_THROWN_HEAL_BY_WORLD) {
            PENDING_THROWN_HEAL_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new HashMap<>())
                    .merge(bossId, 1, Integer::sum);
        }
        synchronized (RECENT_HEAL_THROWS_BY_WORLD) {
            RECENT_HEAL_THROWS_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new HashMap<>())
                    .put(bossId, now);
        }
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        synchronized (PENDING_THROWN_HEAL_BY_WORLD) {
            PENDING_THROWN_HEAL_BY_WORLD.remove(worldId);
        }
        synchronized (HEAL_PROJECTILES_BY_WORLD) {
            HEAL_PROJECTILES_BY_WORLD.remove(worldId);
        }
        synchronized (RECENT_HEAL_THROWS_BY_WORLD) {
            RECENT_HEAL_THROWS_BY_WORLD.remove(worldId);
        }
        synchronized (RECENT_HEAL_ZONE_POSITIONS) {
            RECENT_HEAL_ZONE_POSITIONS.remove(worldId);
        }
    }

    private static void restoreExpiredCrimsonPatches(@Nonnull World world, @Nonnull UUID worldId, long now) {
        ArrayList<PotionBrewerWitchCrimsonPatchRuntime.Patch> expired = PotionBrewerWitchCrimsonPatchRuntime.popExpiredPatches(worldId, now);
        if (expired.isEmpty()) {
            return;
        }
        for (PotionBrewerWitchCrimsonPatchRuntime.Patch patch : expired) {
            for (PotionBrewerWitchCrimsonPatchRuntime.BlockRestore restore : patch.restores) {
                world.setBlock(restore.x, restore.y, restore.z, restore.originalBlockId);
            }
        }
    }

    private static void restoreExpiredHealZones(@Nonnull World world, @Nonnull UUID worldId, long now) {
        ArrayList<PotionBrewerWitchHealZoneRuntime.Zone> expired = PotionBrewerWitchHealZoneRuntime.popExpiredZones(worldId, now);
        if (expired.isEmpty()) {
            return;
        }
        for (PotionBrewerWitchHealZoneRuntime.Zone zone : expired) {
            for (PotionBrewerWitchHealZoneRuntime.BlockRestore restore : zone.restores()) {
                world.setBlock(restore.x(), restore.y(), restore.z(), restore.originalBlockId());
            }
        }
    }

    private static void placeHealZone(@Nonnull World world, @Nonnull UUID worldId, @Nonnull Vector3d impact, long now) {
        int baseX = (int) Math.floor(impact.getX());
        int baseY = (int) Math.floor(impact.getY()) - 1;
        int baseZ = (int) Math.floor(impact.getZ());
        int radius = (int) Math.ceil(HEAL_ZONE_RADIUS);

        ArrayList<PotionBrewerWitchHealZoneRuntime.BlockRestore> restores = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > HEAL_ZONE_RADIUS * HEAL_ZONE_RADIUS) {
                    continue;
                }
                int x = baseX + dx;
                int z = baseZ + dz;
                
                // Simple floor finding for the zone
                int y = baseY;
                BlockType current = world.getBlockType(x, y, z);
                if (current == null || "Empty".equals(current.getId())) {
                    // Try to find floor below
                    for (int i = 1; i < 3; i++) {
                        BlockType below = world.getBlockType(x, y - i, z);
                        if (below != null && !"Empty".equals(below.getId())) {
                            y -= i;
                            current = below;
                            break;
                        }
                    }
                } else {
                    // Try to find surface if buried
                    for (int i = 1; i < 3; i++) {
                        BlockType above = world.getBlockType(x, y + i, z);
                        if (above == null || "Empty".equals(above.getId())) {
                            break;
                        }
                        y += i;
                        current = above;
                    }
                }

                if (current == null || "Empty".equals(current.getId())) {
                    continue;
                }

                if (!RedWaveManager.shouldConvertBlock(current)) {
                    continue;
                }

                String existingId = current.getId();
                if (existingId == null || existingId.isEmpty()) {
                    continue;
                }

                restores.add(new PotionBrewerWitchHealZoneRuntime.BlockRestore(
                        x,
                        y,
                        z,
                        existingId
                ));
                world.setBlock(x, y, z, HEAL_ZONE_BLOCK_ID);
            }
        }

        if (!restores.isEmpty()) {
            PotionBrewerWitchHealZoneRuntime.addZone(new PotionBrewerWitchHealZoneRuntime.Zone(
                    UUID.randomUUID(),
                    worldId,
                    impact.getX(),
                    impact.getY(),
                    impact.getZ(),
                    HEAL_ZONE_RADIUS,
                    now + HEAL_ZONE_DURATION_MS,
                    restores
            ));
        }
    }

    private static boolean consumePendingThrownHealProjectile(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PENDING_THROWN_HEAL_BY_WORLD) {
            Map<UUID, Integer> pendingByBoss = PENDING_THROWN_HEAL_BY_WORLD.get(worldId);
            if (pendingByBoss == null) {
                return false;
            }
            Integer count = pendingByBoss.get(bossId);
            if (count == null || count <= 0) {
                return false;
            }
            if (count == 1) {
                pendingByBoss.remove(bossId);
            } else {
                pendingByBoss.put(bossId, count - 1);
            }
            if (pendingByBoss.isEmpty()) {
                PENDING_THROWN_HEAL_BY_WORLD.remove(worldId);
            }
            return true;
        }
    }

    private static boolean hasRecentHealThrow(@Nonnull UUID worldId, @Nonnull UUID bossId, long nowMillis) {
        synchronized (RECENT_HEAL_THROWS_BY_WORLD) {
            Map<UUID, Long> recentByBoss = RECENT_HEAL_THROWS_BY_WORLD.get(worldId);
            if (recentByBoss == null) {
                return false;
            }
            Long throwTime = recentByBoss.get(bossId);
            if (throwTime == null) {
                return false;
            }
            return (nowMillis - throwTime) < RECENT_THROW_TIMEOUT_MS;
        }
    }

    private static void cleanupExpiredRecentThrows(@Nonnull UUID worldId, long nowMillis) {
        synchronized (RECENT_HEAL_THROWS_BY_WORLD) {
            Map<UUID, Long> recentByBoss = RECENT_HEAL_THROWS_BY_WORLD.get(worldId);
            if (recentByBoss == null || recentByBoss.isEmpty()) {
                return;
            }
            recentByBoss.entrySet().removeIf(entry -> (nowMillis - entry.getValue()) >= RECENT_THROW_TIMEOUT_MS);
            if (recentByBoss.isEmpty()) {
                RECENT_HEAL_THROWS_BY_WORLD.remove(worldId);
            }
        }
    }

    private static void registerHealProjectile(@Nonnull UUID worldId, @Nonnull UUID projectileId) {
        synchronized (HEAL_PROJECTILES_BY_WORLD) {
            HEAL_PROJECTILES_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new HashSet<>())
                    .add(projectileId);
        }
    }

    private static void unregisterHealProjectile(@Nonnull UUID worldId, @Nonnull UUID projectileId) {
        synchronized (HEAL_PROJECTILES_BY_WORLD) {
            Set<UUID> projectiles = HEAL_PROJECTILES_BY_WORLD.get(worldId);
            if (projectiles == null) {
                return;
            }
            projectiles.remove(projectileId);
            if (projectiles.isEmpty()) {
                HEAL_PROJECTILES_BY_WORLD.remove(worldId);
            }
        }
    }

    private static boolean isHealProjectile(@Nonnull UUID worldId, @Nonnull UUID projectileId) {
        synchronized (HEAL_PROJECTILES_BY_WORLD) {
            Set<UUID> projectiles = HEAL_PROJECTILES_BY_WORLD.get(worldId);
            return projectiles != null && projectiles.contains(projectileId);
        }
    }

    private static boolean shouldPlaceHealZone(@Nonnull UUID worldId, @Nonnull Vector3d position, long now) {
        synchronized (RECENT_HEAL_ZONE_POSITIONS) {
            Set<Vector3i> positions = RECENT_HEAL_ZONE_POSITIONS.get(worldId);
            if (positions == null) {
                return true;
            }
            Vector3i blockPos = new Vector3i(
                    (int) Math.floor(position.getX()),
                    (int) Math.floor(position.getY()),
                    (int) Math.floor(position.getZ())
            );
            // Check if there's a recent heal zone within 2 blocks
            for (Vector3i recentPos : positions) {
                int dx = recentPos.x - blockPos.x;
                int dy = recentPos.y - blockPos.y;
                int dz = recentPos.z - blockPos.z;
                if (dx * dx + dy * dy + dz * dz <= 4) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void registerHealZonePosition(@Nonnull UUID worldId, @Nonnull Vector3d position, long now) {
        synchronized (RECENT_HEAL_ZONE_POSITIONS) {
            Set<Vector3i> positions = RECENT_HEAL_ZONE_POSITIONS.computeIfAbsent(worldId, k -> new HashSet<>());
            Vector3i blockPos = new Vector3i(
                    (int) Math.floor(position.getX()),
                    (int) Math.floor(position.getY()),
                    (int) Math.floor(position.getZ())
            );
            positions.add(blockPos);
        }
    }

    private static void cleanupExpiredHealZonePositions(@Nonnull UUID worldId) {
        synchronized (RECENT_HEAL_ZONE_POSITIONS) {
            RECENT_HEAL_ZONE_POSITIONS.remove(worldId);
        }
    }

    private static void placeCrimsonPatch(@Nonnull World world, @Nonnull UUID worldId, @Nonnull Vector3d impact, long now) {
        int baseX = (int) Math.floor(impact.getX());
        int baseY = (int) Math.floor(impact.getY()) - 1;
        int baseZ = (int) Math.floor(impact.getZ());
        Vector3i[] pattern = selectCrimsonPatchPattern(baseX, baseY, baseZ);

        ArrayList<PotionBrewerWitchCrimsonPatchRuntime.BlockRestore> restores = new ArrayList<>();
        for (Vector3i offset : pattern) {
            int x = baseX + offset.x;
            int y = baseY + offset.y;
            int z = baseZ + offset.z;
            if (PotionBrewerWitchCrimsonPatchRuntime.getActivePatchNear(worldId, x, y, z) != null) {
                continue;
            }

            BlockType existing = world.getBlockType(x, y, z);
            if (!RedWaveManager.shouldConvertBlock(existing)) {
                continue;
            }

            String existingId = existing.getId();
            if (existingId == null || existingId.isEmpty()) {
                continue;
            }

            restores.add(new PotionBrewerWitchCrimsonPatchRuntime.BlockRestore(
                    UUID.randomUUID(),
                    worldId,
                    x,
                    y,
                    z,
                    existingId
            ));
            world.setBlock(x, y, z, RedWaveConfig.CRIMSON_BLOCK_ID);
        }

        if (!restores.isEmpty()) {
            PotionBrewerWitchCrimsonPatchRuntime.addPatch(new PotionBrewerWitchCrimsonPatchRuntime.Patch(
                    UUID.randomUUID(),
                    worldId,
                    now + CRIMSON_PATCH_DURATION_MS,
                    restores
            ));
        }
    }

    @Nonnull
    private static Vector3i[] selectCrimsonPatchPattern(int x, int y, int z) {
        int hash = Math.abs((x * 7349) ^ (y * 9151) ^ (z * 1327));
        return CRIMSON_PATCH_PATTERNS[hash % CRIMSON_PATCH_PATTERNS.length];
    }

    private static boolean isCreatedByPotionWitch(
            @Nonnull Store<EntityStore> store,
            @Nonnull StandardPhysicsProvider physics
    ) {
        UUID creatorUuid = physics.getCreatorUuid();
        if (creatorUuid == null) {
            return false;
        }
        EntityStore entityStore = (EntityStore) store.getExternalData();
        Ref<EntityStore> creatorRef = entityStore.getRefFromUUID(creatorUuid);
        if (creatorRef == null || !creatorRef.isValid()) {
            return false;
        }
        NPCEntity npc = store.getComponent(creatorRef, NPC);
        return npc != null && ROLE_NAME.equals(npc.getRoleName());
    }

    private static boolean isCreatorRunningHealThrownInteraction(
            @Nonnull Store<EntityStore> store,
            @Nonnull StandardPhysicsProvider physics
    ) {
        UUID creatorUuid = physics.getCreatorUuid();
        if (creatorUuid == null) {
            return false;
        }
        EntityStore entityStore = (EntityStore) store.getExternalData();
        Ref<EntityStore> creatorRef = entityStore.getRefFromUUID(creatorUuid);
        if (creatorRef == null || !creatorRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, InteractionManager> interactionManagerType = InteractionModule.get().getInteractionManagerComponent();
        InteractionManager interactionManager = store.getComponent(creatorRef, interactionManagerType);
        if (interactionManager == null) {
            return false;
        }
        for (InteractionChain chain : interactionManager.getChains().values()) {
            if (chain == null) {
                continue;
            }
            RootInteraction rootInteraction = chain.getInitialRootInteraction();
            if (rootInteraction != null && HEAL_THROWN_ROOT_INTERACTION.equals(rootInteraction.getId())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static UUID getCreatorUuid(@Nonnull StandardPhysicsProvider physics) {
        return physics.getCreatorUuid();
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
    }

    @Nonnull
    private static String fmt(@Nonnull Vector3d vector) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", vector.getX(), vector.getY(), vector.getZ());
    }
}
