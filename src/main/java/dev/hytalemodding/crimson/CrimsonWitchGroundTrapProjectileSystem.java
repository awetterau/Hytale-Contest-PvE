package dev.hytalemodding.crimson;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.projectile.component.Projectile;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CrimsonWitchGroundTrapProjectileSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, Projectile> PROJECTILE = Projectile.getComponentType();
    private static final ComponentType<EntityStore, StandardPhysicsProvider> STANDARD_PHYSICS = StandardPhysicsProvider.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, ModelComponent> MODEL = ModelComponent.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final String CRIMSON_WITCH_ROLE_PREFIX = "Crimson_Witch";
    private static final String TRAP_PROJECTILE_MODEL_ID = "Bomb_Potion_Poison";
    private static final long TRAP_DURATION_MS = 5000L;
    private static final Vector3i[][] TRAP_PATTERNS = new Vector3i[][]{
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

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        restoreExpiredPatches(world, worldId, now);

        List<Ref<EntityStore>> spentProjectiles = new ArrayList<>();
        store.forEachChunk(PROJECTILE, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                StandardPhysicsProvider physics = chunk.getComponent(i, STANDARD_PHYSICS);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                ModelComponent modelComponent = chunk.getComponent(i, MODEL);
                if (physics == null || transform == null || modelComponent == null) {
                    continue;
                }
                if (!isCrimsonTrapProjectile(store, physics, modelComponent)) {
                    continue;
                }
                if (physics.getState() != StandardPhysicsProvider.STATE.RESTING || !physics.isOnGround()) {
                    continue;
                }

                Ref<EntityStore> projectileRef = chunk.getReferenceTo(i);
                if (projectileRef == null || !projectileRef.isValid()) {
                    continue;
                }

                Vector3d impact = transform.getPosition().clone();
                placeTrapPatch(world, worldId, impact, now);
                spentProjectiles.add(projectileRef);
            }
        });

        for (Ref<EntityStore> projectileRef : spentProjectiles) {
            if (projectileRef != null && projectileRef.isValid()) {
                store.removeEntity(projectileRef, RemoveReason.REMOVE);
            }
        }
    }

    private static void restoreExpiredPatches(@Nonnull World world, @Nonnull UUID worldId, long now) {
        ArrayList<CrimsonWitchGroundTrapRuntime.TrapPatch> expired = CrimsonWitchGroundTrapRuntime.popExpiredPatches(worldId, now);
        if (expired.isEmpty()) {
            return;
        }
        for (CrimsonWitchGroundTrapRuntime.TrapPatch patch : expired) {
            for (CrimsonWitchGroundTrapRuntime.BlockRestore restore : patch.restores) {
                world.setBlock(restore.x, restore.y, restore.z, restore.originalBlockId);
            }
        }
    }

    private static void placeTrapPatch(@Nonnull World world, @Nonnull UUID worldId, @Nonnull Vector3d impact, long now) {
        int baseX = (int) Math.floor(impact.getX());
        int baseY = (int) Math.floor(impact.getY()) - 1;
        int baseZ = (int) Math.floor(impact.getZ());
        Vector3i[] pattern = selectTrapPattern(baseX, baseY, baseZ);

        ArrayList<CrimsonWitchGroundTrapRuntime.BlockRestore> restores = new ArrayList<>();
        for (Vector3i offset : pattern) {
            int x = baseX + offset.x;
            int y = baseY + offset.y;
            int z = baseZ + offset.z;
            if (CrimsonWitchGroundTrapRuntime.getActivePatchNear(worldId, x, y, z) != null) {
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

            restores.add(new CrimsonWitchGroundTrapRuntime.BlockRestore(
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
            CrimsonWitchGroundTrapRuntime.addPatch(new CrimsonWitchGroundTrapRuntime.TrapPatch(
                    UUID.randomUUID(),
                    worldId,
                    now + TRAP_DURATION_MS,
                    restores
            ));
        }
    }

    @Nonnull
    private static Vector3i[] selectTrapPattern(int x, int y, int z) {
        int hash = Math.abs((x * 7349) ^ (y * 9151) ^ (z * 1327));
        return TRAP_PATTERNS[hash % TRAP_PATTERNS.length];
    }

    private static boolean isCrimsonTrapProjectile(
            @Nonnull Store<EntityStore> store,
            @Nonnull StandardPhysicsProvider physics,
            @Nonnull ModelComponent modelComponent
    ) {
        Model model = modelComponent.getModel();
        if (model == null || !TRAP_PROJECTILE_MODEL_ID.equals(model.getModelAssetId())) {
            return false;
        }
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
        if (npc == null || npc.getRoleName() == null) {
            return false;
        }
        return npc.getRoleName().startsWith(CRIMSON_WITCH_ROLE_PREFIX);
    }
}
