package dev.hytalemodding.debug;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SprintCrashTraceSystem extends EntityTickingSystem<EntityStore> {
    private static final ConcurrentHashMap<UUID, Snapshot> LAST_BY_PLAYER = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(
                PlayerRef.getComponentType(),
                MovementStatesComponent.getComponentType(),
                TransformComponent.getComponentType(),
                MovementManager.getComponentType()
        );
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null || !CrashTrace.isTracing(playerRef) || playerRef.getUuid() == null) {
            return;
        }

        MovementStatesComponent movementStatesComponent = archetypeChunk.getComponent(index, MovementStatesComponent.getComponentType());
        TransformComponent transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        MovementManager movementManager = archetypeChunk.getComponent(index, MovementManager.getComponentType());
        if (movementStatesComponent == null || transformComponent == null || movementManager == null) {
            return;
        }

        MovementStates states = movementStatesComponent.getMovementStates();
        MovementSettings settings = movementManager.getSettings();
        Vector3d position = transformComponent.getPosition();
        int chunkX = MathUtil.floor(position.x) >> 5;
        int chunkZ = MathUtil.floor(position.z) >> 5;

        UUID playerId = playerRef.getUuid();
        Snapshot previous = LAST_BY_PLAYER.get(playerId);
        if (previous == null) {
            LAST_BY_PLAYER.put(playerId, Snapshot.capture(states, settings, position, chunkX, chunkZ));
            CrashTrace.log(
                    playerRef,
                    "sprint-trace",
                    "initial pos=" + formatPos(position)
                            + " chunk=(" + chunkX + "," + chunkZ + ")"
                            + " sprinting=" + states.sprinting
                            + " running=" + states.running
                            + " walking=" + states.walking
                            + " onGround=" + states.onGround
                            + " canFly=" + settings.canFly
                            + " baseSpeed=" + settings.baseSpeed
                            + " sprintMult=" + settings.forwardSprintSpeedMultiplier
                            + " runMult=" + settings.forwardRunSpeedMultiplier
                            + " walkMult=" + settings.forwardWalkSpeedMultiplier
            );
            return;
        }

        if (previous.chunkX != chunkX || previous.chunkZ != chunkZ) {
            CrashTrace.log(
                    playerRef,
                    "sprint-trace",
                    "chunk-change from=(" + previous.chunkX + "," + previous.chunkZ + ")"
                            + " to=(" + chunkX + "," + chunkZ + ") pos=" + formatPos(position)
            );
        }

        if (previous.sprinting != states.sprinting
                || previous.running != states.running
                || previous.walking != states.walking
                || previous.onGround != states.onGround
                || previous.sliding != states.sliding
                || previous.crouching != states.crouching) {
            CrashTrace.log(
                    playerRef,
                    "sprint-trace",
                    "state-change sprinting=" + previous.sprinting + "->" + states.sprinting
                            + " running=" + previous.running + "->" + states.running
                            + " walking=" + previous.walking + "->" + states.walking
                            + " onGround=" + previous.onGround + "->" + states.onGround
                            + " sliding=" + previous.sliding + "->" + states.sliding
                            + " crouching=" + previous.crouching + "->" + states.crouching
                            + " pos=" + formatPos(position)
                            + " chunk=(" + chunkX + "," + chunkZ + ")"
            );
        }

        if (previous.baseSpeed != settings.baseSpeed
                || previous.sprintMultiplier != settings.forwardSprintSpeedMultiplier
                || previous.runMultiplier != settings.forwardRunSpeedMultiplier
                || previous.walkMultiplier != settings.forwardWalkSpeedMultiplier) {
            CrashTrace.log(
                    playerRef,
                    "sprint-trace",
                    "settings-change baseSpeed=" + previous.baseSpeed + "->" + settings.baseSpeed
                            + " sprintMult=" + previous.sprintMultiplier + "->" + settings.forwardSprintSpeedMultiplier
                            + " runMult=" + previous.runMultiplier + "->" + settings.forwardRunSpeedMultiplier
                            + " walkMult=" + previous.walkMultiplier + "->" + settings.forwardWalkSpeedMultiplier
            );
        }

        LAST_BY_PLAYER.put(playerId, Snapshot.capture(states, settings, position, chunkX, chunkZ));
    }

    @Nonnull
    private static String formatPos(@Nonnull Vector3d position) {
        return "("
                + String.format("%.3f", position.x)
                + ","
                + String.format("%.3f", position.y)
                + ","
                + String.format("%.3f", position.z)
                + ")";
    }

    private record Snapshot(
            boolean sprinting,
            boolean running,
            boolean walking,
            boolean onGround,
            boolean sliding,
            boolean crouching,
            float baseSpeed,
            float sprintMultiplier,
            float runMultiplier,
            float walkMultiplier,
            int chunkX,
            int chunkZ
    ) {
        @Nonnull
        private static Snapshot capture(
                @Nonnull MovementStates states,
                @Nonnull MovementSettings settings,
                @Nonnull Vector3d position,
                int chunkX,
                int chunkZ
        ) {
            return new Snapshot(
                    states.sprinting,
                    states.running,
                    states.walking,
                    states.onGround,
                    states.sliding,
                    states.crouching,
                    settings.baseSpeed,
                    settings.forwardSprintSpeedMultiplier,
                    settings.forwardRunSpeedMultiplier,
                    settings.forwardWalkSpeedMultiplier,
                    chunkX,
                    chunkZ
            );
        }
    }
}
