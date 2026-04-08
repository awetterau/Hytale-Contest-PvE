package dev.hytalemodding.potion;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedWoolDamageSystem;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PotionBrewerWitchPoisonDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float DAMAGE_INTERVAL_SECONDS = 0.5f;
    private static final float DAMAGE_PER_TICK = 4.0f;
    private static final float RETALIATION_DAMAGE_PER_TICK = 2.0f;

    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, UUIDComponent> UUID_COMPONENT = UUIDComponent.getComponentType();

    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM, UUID_COMPONENT);
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedInPoison = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedInReactivePoison = new ConcurrentHashMap<>();

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
        TransformComponent transform = archetypeChunk.getComponent(index, TRANSFORM);
        if (transform == null) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUID_COMPONENT);
        if (uuidComponent == null) {
            return;
        }

        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        List<PotionBrewerWitchPoisonRuntime.Patch> patches = PotionBrewerWitchPoisonRuntime.getActivePatches(
                worldId,
                now
        );
        boolean inPuddle = !patches.isEmpty() && isInPatch(transform.getPosition(), patches);
        boolean reactivePoisoned = PotionBrewerWitchReactivePoisonRuntime.isPoisoned(worldId, uuidComponent.getUuid(), now);

        if (!inPuddle) {
            this.elapsedInPoison.remove(playerRef);
        }
        if (!reactivePoisoned) {
            this.elapsedInReactivePoison.remove(playerRef);
        }

        if (!inPuddle && !reactivePoisoned) {
            return;
        }

        if (inPuddle) {
            float elapsed = this.elapsedInPoison.getOrDefault(playerRef, 0.0f) + dt;
            if (elapsed >= DAMAGE_INTERVAL_SECONDS) {
                int applications = (int) (elapsed / DAMAGE_INTERVAL_SECONDS);
                Damage damage = new Damage(
                        Damage.NULL_SOURCE,
                        RedWoolDamageSystem.resolveHazardCauseIndexSafe(),
                        applications * DAMAGE_PER_TICK
                );
                DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
                elapsed -= applications * DAMAGE_INTERVAL_SECONDS;
            }
            this.elapsedInPoison.put(playerRef, elapsed);
        }

        if (reactivePoisoned) {
            float elapsed = this.elapsedInReactivePoison.getOrDefault(playerRef, 0.0f) + dt;
            if (elapsed >= DAMAGE_INTERVAL_SECONDS) {
                int applications = (int) (elapsed / DAMAGE_INTERVAL_SECONDS);
                Damage damage = new Damage(
                        Damage.NULL_SOURCE,
                        RedWoolDamageSystem.resolveHazardCauseIndexSafe(),
                        applications * RETALIATION_DAMAGE_PER_TICK
                );
                DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
                elapsed -= applications * DAMAGE_INTERVAL_SECONDS;
            }
            this.elapsedInReactivePoison.put(playerRef, elapsed);
        }
    }

    private static boolean isInPatch(
            @Nonnull Vector3d position,
            @Nonnull List<PotionBrewerWitchPoisonRuntime.Patch> patches
    ) {
        for (PotionBrewerWitchPoisonRuntime.Patch patch : patches) {
            double dx = position.getX() - patch.x();
            double dz = position.getZ() - patch.z();
            double distanceSq = (dx * dx) + (dz * dz);
            if (distanceSq <= patch.radius() * patch.radius() && Math.abs(position.getY() - patch.y()) <= 2.0d) {
                return true;
            }
        }
        return false;
    }
}
