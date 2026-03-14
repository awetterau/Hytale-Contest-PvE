package dev.hytalemodding.redwave;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;

public class RedWoolNpcDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float DAMAGE_INTERVAL_SECONDS = 0.5f;
    private static final float DAMAGE_PER_TICK = 5.0f;

    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();

    private final Query<EntityStore> query = Query.and(NPC, TRANSFORM);
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedOnHazard = new ConcurrentHashMap<>();

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
        TransformComponent transform = archetypeChunk.getComponent(index, TRANSFORM);
        if (npc == null || transform == null) {
            return;
        }

        Ref<EntityStore> entityId = archetypeChunk.getReferenceTo(index);
        if (!RedWoolDamageSystem.isStandingOnHazardBlockSafe(transform, store.getExternalData().getWorld())) {
            this.elapsedOnHazard.remove(entityId);
            return;
        }

        float elapsed = this.elapsedOnHazard.getOrDefault(entityId, 0.0f) + dt;
        if (elapsed >= DAMAGE_INTERVAL_SECONDS) {
            int applications = (int) (elapsed / DAMAGE_INTERVAL_SECONDS);
            Damage damage = new Damage(Damage.NULL_SOURCE, RedWoolDamageSystem.resolveHazardCauseIndexSafe(), applications * DAMAGE_PER_TICK);
            DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
            elapsed -= applications * DAMAGE_INTERVAL_SECONDS;
        }
        this.elapsedOnHazard.put(entityId, elapsed);
    }
}


