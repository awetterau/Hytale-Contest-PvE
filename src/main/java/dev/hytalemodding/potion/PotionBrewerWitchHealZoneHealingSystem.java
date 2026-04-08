package dev.hytalemodding.potion;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public final class PotionBrewerWitchHealZoneHealingSystem extends EntityTickingSystem<EntityStore> {
    private static final float HEAL_INTERVAL_SECONDS = 0.5f;
    private static final float HEAL_PER_SECOND = 2.5f;
    private static final int HEALTH_STAT_INDEX = DefaultEntityStatTypes.getHealth();
    private static final float MAX_HEALTH = 110.0f;

    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, EntityStatMap> STATS = EntityStatMap.getComponentType();

    private final Query<EntityStore> query = Query.and(NPC, TRANSFORM, STATS);
    private final java.util.concurrent.ConcurrentHashMap<Ref<EntityStore>, Float> elapsedInZone = new java.util.concurrent.ConcurrentHashMap<>();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        NPCEntity npc = archetypeChunk.getComponent(index, NPC);
        if (npc == null || !"Potion_Brewer_Witch".equals(npc.getRoleName())) {
            return;
        }

        TransformComponent transform = archetypeChunk.getComponent(index, TRANSFORM);
        if (transform == null) {
            return;
        }

        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        if (npcRef == null || !npcRef.isValid()) {
            return;
        }

        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        List<PotionBrewerWitchHealZoneRuntime.Zone> zones = PotionBrewerWitchHealZoneRuntime.getActiveZones(worldId, now);
        
        boolean inZone = isInZone(transform.getPosition(), zones);
        if (!inZone) {
            this.elapsedInZone.remove(npcRef);
            return;
        }

        float elapsed = this.elapsedInZone.getOrDefault(npcRef, 0.0f) + dt;
        if (elapsed >= HEAL_INTERVAL_SECONDS) {
            int applications = (int) (elapsed / HEAL_INTERVAL_SECONDS);
            float healAmount = applications * (HEAL_PER_SECOND * HEAL_INTERVAL_SECONDS);
            
            EntityStatMap stats = archetypeChunk.getComponent(index, STATS);
            if (stats != null) {
                float currentHealth = readHealth(stats);
                if (currentHealth >= 0 && currentHealth < MAX_HEALTH) {
                    EntityStatMap updated = stats.clone();
                    updated.addStatValue(HEALTH_STAT_INDEX, Math.min(healAmount, MAX_HEALTH - currentHealth));
                    commandBuffer.putComponent(npcRef, STATS, updated);
                }
            }
            elapsed -= applications * HEAL_INTERVAL_SECONDS;
        }
        this.elapsedInZone.put(npcRef, elapsed);
    }

    private static float readHealth(@Nullable EntityStatMap stats) {
        if (stats == null || HEALTH_STAT_INDEX < 0 || stats.get(HEALTH_STAT_INDEX) == null) {
            return -1.0f;
        }
        return stats.get(HEALTH_STAT_INDEX).get();
    }

    private static boolean isInZone(
            @Nonnull Vector3d position,
            @Nonnull List<PotionBrewerWitchHealZoneRuntime.Zone> zones
    ) {
        for (PotionBrewerWitchHealZoneRuntime.Zone zone : zones) {
            double dx = position.getX() - zone.x();
            double dz = position.getZ() - zone.z();
            double distanceSq = (dx * dx) + (dz * dz);
            if (distanceSq <= zone.radius() * zone.radius() && Math.abs(position.getY() - zone.y()) <= 3.0d) {
                return true;
            }
        }
        return false;
    }
}
