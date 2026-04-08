package dev.hytalemodding.potion;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public final class PotionBrewerWitchShockwaveDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float SHOCKWAVE_DAMAGE = 12.0f;
    private static final double SHOCKWAVE_KNOCKBACK_XZ = 0.7d;
    private static final double SHOCKWAVE_KNOCKBACK_Y = 0.28d;
    private static final long RIPPLE_STEP_DELAY_MS = 140L;
    private static final long SHOCKWAVE_STUN_MS = 1000L;
    private static final double RIPPLE_VISUAL_SPEED_BLOCKS_PER_SECOND = 1000.0d / RIPPLE_STEP_DELAY_MS;
    private static final double RIPPLE_HIT_BAND = 1.3d;
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM);

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

        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        List<PotionBrewerWitchShockwaveRuntime.RippleEvent> ripples = PotionBrewerWitchShockwaveRuntime.getActiveRipples(worldId, now);
        if (ripples.isEmpty()) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        UUID playerId = uuidComponent == null ? null : uuidComponent.getUuid();
        double px = transform.getPosition().getX();
        double pz = transform.getPosition().getZ();
        for (PotionBrewerWitchShockwaveRuntime.RippleEvent ripple : ripples) {
            if (ripple.hitPlayers.containsKey(playerRef)) {
                continue;
            }

            double relX = px - ripple.originX;
            double relZ = pz - ripple.originZ;
            double distance = Math.sqrt((relX * relX) + (relZ * relZ));
            if (distance > ripple.maxDistance + 1.25d) {
                continue;
            }
            double elapsedSeconds = (now - ripple.startMillis) / 1000.0d;
            double ringDistance = elapsedSeconds * RIPPLE_VISUAL_SPEED_BLOCKS_PER_SECOND;
            if (Math.abs(distance - ringDistance) > RIPPLE_HIT_BAND) {
                continue;
            }
            if (Math.abs(transform.getPosition().getY() - ripple.originY) > 2.2d) {
                continue;
            }

            Damage damage = new Damage(Damage.NULL_SOURCE, resolveDamageCauseIndex(), SHOCKWAVE_DAMAGE);
            DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
            if (distance > 0.001d) {
                applyKnockback(commandBuffer, playerRef, relX / distance, relZ / distance);
            }
            if (playerId != null) {
                PotionBrewerWitchBindingSystem.applyTemporaryMovementLock(
                        worldId,
                        playerId,
                        transform.getPosition(),
                        now + SHOCKWAVE_STUN_MS
                );
            }
            ripple.hitPlayers.put(playerRef, true);
        }
    }

    private static void applyKnockback(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> playerRef,
            double dirX,
            double dirZ
    ) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        Velocity velocity = commandBuffer.getComponent(playerRef, Velocity.getComponentType());
        if (velocity == null) {
            velocity = new Velocity();
        }
        velocity.addForce(new Vector3d(dirX * SHOCKWAVE_KNOCKBACK_XZ, SHOCKWAVE_KNOCKBACK_Y, dirZ * SHOCKWAVE_KNOCKBACK_XZ));
        commandBuffer.putComponent(playerRef, Velocity.getComponentType(), velocity);
    }

    private static int resolveDamageCauseIndex() {
        int command = DamageCause.getAssetMap().getIndex("Command");
        if (command != Integer.MIN_VALUE) {
            return command;
        }
        int environment = DamageCause.getAssetMap().getIndex("Environment");
        return environment != Integer.MIN_VALUE ? environment : 0;
    }
}
