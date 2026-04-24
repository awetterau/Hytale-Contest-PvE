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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public final class RooterShockwaveDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float SHOCKWAVE_DAMAGE = 18.0f;
    private static final double WAVE_KNOCKBACK_XZ = 0.01;
    private static final double WAVE_KNOCKBACK_Y = 0.005;
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
        List<RooterShockwaveRuntime.WaveEvent> waves = RooterShockwaveRuntime.getActiveWaves(worldId, now);
        if (waves.isEmpty()) {
            return;
        }

        Ref<EntityStore> playerRef = archetypeChunk.getReferenceTo(index);
        double px = transform.getPosition().getX();
        double pz = transform.getPosition().getZ();
        for (RooterShockwaveRuntime.WaveEvent wave : waves) {
            if (wave.hitPlayers.containsKey(playerRef)) {
                continue;
            }

            double relX = px - wave.originX;
            double relZ = pz - wave.originZ;
            double forward = (relX * wave.dirX) + (relZ * wave.dirZ);
            if (forward < 0 || forward > wave.maxDistance) {
                continue;
            }
            double sideX = -wave.dirZ;
            double sideZ = wave.dirX;
            double lateral = Math.abs((relX * sideX) + (relZ * sideZ));

            double elapsedSeconds = (now - wave.startMillis) / 1000.0;
            double ringDistance = 1.0 + (elapsedSeconds * wave.speedBlocksPerSecond);
            if (Math.abs(forward - ringDistance) > 1.1) {
                continue;
            }
            if (lateral > (1.2 + (forward * 0.12))) {
                continue;
            }

            Damage damage = new Damage(Damage.NULL_SOURCE, RootDamageCause.causeIndex(), SHOCKWAVE_DAMAGE);
            DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
            applyKnockback(commandBuffer, playerRef, wave.dirX, wave.dirZ, WAVE_KNOCKBACK_XZ, WAVE_KNOCKBACK_Y);
            wave.hitPlayers.put(playerRef, true);
        }
    }

    private static void applyKnockback(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> playerRef,
            double dirX,
            double dirZ,
            double horizontalStrength,
            double verticalStrength
    ) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        Velocity velocity = commandBuffer.getComponent(playerRef, Velocity.getComponentType());
        if (velocity == null) {
            velocity = new Velocity();
        }
        velocity.addForce(new Vector3d(dirX * horizontalStrength, verticalStrength, dirZ * horizontalStrength));
        commandBuffer.putComponent(playerRef, Velocity.getComponentType(), velocity);
    }

    private static final class RootDamageCause {
        private static int causeIndex = Integer.MIN_VALUE;

        private static int causeIndex() {
            if (causeIndex != Integer.MIN_VALUE) {
                return causeIndex;
            }
            int command = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Command");
            if (command != Integer.MIN_VALUE) {
                causeIndex = command;
                return causeIndex;
            }
            int environment = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Environment");
            causeIndex = environment != Integer.MIN_VALUE ? environment : 0;
            return causeIndex;
        }
    }
}
