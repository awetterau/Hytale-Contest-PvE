package dev.hytalemodding.rooter;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
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

public final class RooterMeleeDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float MELEE_DAMAGE = 22.0f;
    private static final double MELEE_KNOCKBACK_XZ = 0.35;
    private static final double MELEE_KNOCKBACK_Y = 0.12;
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
        List<RooterShockwaveRuntime.MeleeEvent> events = RooterShockwaveRuntime.getActiveMelee(worldId, now);
        if (events.isEmpty()) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        double px = transform.getPosition().getX();
        double pz = transform.getPosition().getZ();

        for (RooterShockwaveRuntime.MeleeEvent event : events) {
            if (now < event.startMillis) {
                continue;
            }
            if (event.hitPlayers.containsKey(playerRef)) {
                continue;
            }

            double relX = px - event.originX;
            double relZ = pz - event.originZ;
            double forward = (relX * event.dirX) + (relZ * event.dirZ);
            if (forward < 0 || forward > event.range) {
                continue;
            }
            double sideX = -event.dirZ;
            double sideZ = event.dirX;
            double lateral = Math.abs((relX * sideX) + (relZ * sideZ));
            if (lateral > event.lateralWidth) {
                continue;
            }

            Damage damage = new Damage(Damage.NULL_SOURCE, meleeDamageCauseIndex(), MELEE_DAMAGE);
            DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
            applyKnockback(commandBuffer, playerRef, event.dirX, event.dirZ);
            event.hitPlayers.put(playerRef, true);
        }
    }

    private static void applyKnockback(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> playerRef,
            double dirX,
            double dirZ
    ) {
        if (!playerRef.isValid()) {
            return;
        }
        Velocity velocity = commandBuffer.getComponent(playerRef, Velocity.getComponentType());
        if (velocity == null) {
            velocity = new Velocity();
        }
        velocity.addForce(new Vector3d(dirX * MELEE_KNOCKBACK_XZ, MELEE_KNOCKBACK_Y, dirZ * MELEE_KNOCKBACK_XZ));
        commandBuffer.putComponent(playerRef, Velocity.getComponentType(), velocity);
    }

    private static int meleeDamageCauseIndex() {
        int idx = DamageCause.getAssetMap().getIndex("Command");
        if (idx != Integer.MIN_VALUE) {
            return idx;
        }
        idx = DamageCause.getAssetMap().getIndex("Environment");
        return idx != Integer.MIN_VALUE ? idx : 0;
    }
}
