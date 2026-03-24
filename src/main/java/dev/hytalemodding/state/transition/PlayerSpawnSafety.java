package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PlayerSpawnSafety {
    private static final double CHUNK_SIZE = 32.0d;
    private static final double EDGE_EPSILON = 0.25d;
    private static final double SAFE_INSET = 2.0d;

    private PlayerSpawnSafety() {
    }

    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        if (event.getPlayer() == null || event.getPlayer().getReference() == null) {
            return;
        }
        var initialRef = event.getPlayer().getReference();
        var initialStore = initialRef.getStore();
        if (initialStore == null) {
            return;
        }
        World world = initialStore.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        world.execute(() -> {
            var ref = event.getPlayer().getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            var store = ref.getStore();
            if (store == null) {
                return;
            }
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return;
            }

            resetMovement(ref, store);

            Vector3d safePosition = sanitizePosition(transform.getPosition());
            Vector3f safeRotation = sanitizeRotation(transform.getRotation());
            boolean positionChanged = !samePosition(transform.getPosition(), safePosition);
            boolean rotationChanged = !sameRotation(transform.getRotation(), safeRotation);
            if (!positionChanged && !rotationChanged) {
                return;
            }

            TransformComponent updated = transform.clone();
            if (positionChanged) {
                updated.teleportPosition(safePosition);
            }
            if (rotationChanged) {
                updated.teleportRotation(safeRotation);
            }
            store.putComponent(ref, TransformComponent.getComponentType(), updated);
        });
    }

    private static void resetMovement(@Nonnull com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                                      @Nonnull com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager != null) {
            movementManager.resetDefaultsAndUpdate(ref, store);
            store.putComponent(ref, MovementManager.getComponentType(), movementManager);
        }

        MovementStatesComponent movementStatesComponent = store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (movementStatesComponent != null) {
            MovementStates resetStates = new MovementStates();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            resetStates.onGround = transform != null && isStandingStillOnGround(transform);
            movementStatesComponent.setMovementStates(resetStates);
            movementStatesComponent.setSentMovementStates(new MovementStates());
            store.putComponent(ref, MovementStatesComponent.getComponentType(), movementStatesComponent);
        }

        Velocity velocity = store.getComponent(ref, Velocity.getComponentType());
        if (velocity != null) {
            velocity.set(Vector3d.ZERO);
            store.putComponent(ref, Velocity.getComponentType(), velocity);
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.applyMovementStates(ref, new com.hypixel.hytale.protocol.SavedMovementStates(false), new MovementStates(), store);
        }
    }

    private static boolean isStandingStillOnGround(@Nonnull TransformComponent transform) {
        double y = transform.getPosition().getY();
        return !Double.isNaN(y) && !Double.isInfinite(y);
    }

    @Nullable
    public static Transform sanitizeTransform(@Nullable Transform transform) {
        if (transform == null) {
            return null;
        }
        Vector3d safePosition = sanitizePosition(transform.getPosition());
        Vector3f safeRotation = sanitizeRotation(transform.getRotation());
        return new Transform(safePosition, safeRotation);
    }

    @Nonnull
    private static Vector3d sanitizePosition(@Nonnull Vector3d position) {
        return new Vector3d(
                sanitizeAxis(position.getX()),
                sanitizeCoordinate(position.getY()),
                sanitizeAxis(position.getZ())
        );
    }

    @Nonnull
    private static Vector3f sanitizeRotation(@Nonnull Vector3f rotation) {
        return new Vector3f(
                sanitizeAngle(rotation.getPitch()),
                sanitizeAngle(rotation.getYaw()),
                sanitizeAngle(rotation.getRoll())
        );
    }

    private static double sanitizeAxis(double value) {
        double safe = sanitizeCoordinate(value);
        double chunkStart = Math.floor(safe / CHUNK_SIZE) * CHUNK_SIZE;
        double local = safe - chunkStart;
        if (local >= 0.0d && local < EDGE_EPSILON) {
            return chunkStart + SAFE_INSET;
        }
        if (local > (CHUNK_SIZE - EDGE_EPSILON) && local <= CHUNK_SIZE) {
            return chunkStart + CHUNK_SIZE - SAFE_INSET;
        }
        return safe;
    }

    private static double sanitizeCoordinate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return value;
    }

    private static float sanitizeAngle(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return value;
    }

    private static boolean samePosition(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        return Double.compare(a.getX(), b.getX()) == 0
                && Double.compare(a.getY(), b.getY()) == 0
                && Double.compare(a.getZ(), b.getZ()) == 0;
    }

    private static boolean sameRotation(@Nonnull Vector3f a, @Nonnull Vector3f b) {
        return Float.compare(a.getPitch(), b.getPitch()) == 0
                && Float.compare(a.getYaw(), b.getYaw()) == 0
                && Float.compare(a.getRoll(), b.getRoll()) == 0;
    }
}
