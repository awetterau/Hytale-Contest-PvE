package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RunStartMovementLockManager {
    private static final float MIN_LOCKED_MOVEMENT_VALUE = 0.01f;
    private static final long LOCK_START_DELAY_MS = 0L;
    private static final long LOCK_EXTRA_AFTER_CAMERA_END_MS = 4_000L;
    private static final double LOCK_POSITION_EPSILON = 0.01D;
    private static final RunStartMovementLockManager INSTANCE = new RunStartMovementLockManager();

    private final ConcurrentHashMap<UUID, LockSession> lockByPlayer = new ConcurrentHashMap<>();

    private RunStartMovementLockManager() {
    }

    @Nonnull
    public static RunStartMovementLockManager get() {
        return INSTANCE;
    }

    public void lockPlayerForIntro(@Nonnull PlayerRef playerRef) {
        if (!RunStartPresentationConfig.isMovementLockEnabled()) {
            restoreMovementDefaults(playerRef);
            return;
        }
        long now = System.currentTimeMillis();
        long startsAtMs = now + LOCK_START_DELAY_MS;
        this.lockByPlayer.put(
                playerRef.getUuid(),
                new LockSession(startsAtMs)
        );
        playerRef.sendMessage(Message.raw("[Run] Movement lock enabled."));
    }

    public void unlockPlayers(@Nonnull Collection<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            if (playerId == null) {
                continue;
            }
            this.lockByPlayer.remove(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef != null) {
                restoreMovementDefaults(playerRef);
            }
        }
    }

    public void tick(@Nonnull World world) {
        if (!RunStartPresentationConfig.isMovementLockEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID worldId = world.getWorldConfig().getUuid();

        for (Map.Entry<UUID, LockSession> entry : this.lockByPlayer.entrySet()) {
            UUID playerId = entry.getKey();
            LockSession session = entry.getValue();
            if (session == null) {
                this.lockByPlayer.remove(playerId);
                continue;
            }

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || !playerRef.isValid() || playerRef.getWorldUuid() == null) {
                this.lockByPlayer.remove(playerId, session);
                continue;
            }
            if (!worldId.equals(playerRef.getWorldUuid())) {
                continue;
            }
            if (now < session.startsAtMs) {
                continue;
            }
            if (session.endsAtMs == Long.MAX_VALUE) {
                GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
                if (snapshot != null
                        && snapshot.phase() == GameSessionManager.RunPhase.EXPLORATION
                        && snapshot.runWorldUuid() != null
                        && snapshot.runWorldUuid().equals(playerRef.getWorldUuid())
                        && snapshot.startedAtEpochMillis() > 0L) {
                    session.endsAtMs = snapshot.startedAtEpochMillis()
                            + RunStartCameraManager.getIntroEndFromRunStartMs()
                            + LOCK_EXTRA_AFTER_CAMERA_END_MS;
                }
            }
            if (now >= session.endsAtMs) {
                restoreMovementDefaults(playerRef);
                this.lockByPlayer.remove(playerId, session);
                continue;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                this.lockByPlayer.remove(playerId, session);
                continue;
            }
            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                this.lockByPlayer.remove(playerId, session);
                continue;
            }
            if (!worldId.equals(session.lockedWorldId)) {
                session.lockedWorldId = worldId;
                session.lockedPosition = null;
            }
            applyZeroMovementSettings(playerRef, ref, store);

            Vector3d currentPosition = transform.getPosition();
            if (session.lockedPosition == null) {
                session.lockedPosition = new Vector3d(currentPosition);
            }
            double dx = currentPosition.getX() - session.lockedPosition.getX();
            double dy = currentPosition.getY() - session.lockedPosition.getY();
            double dz = currentPosition.getZ() - session.lockedPosition.getZ();
            boolean drifted = (dx * dx) + (dy * dy) + (dz * dz) > LOCK_POSITION_EPSILON;

            if (drifted) {
                transform.teleportPosition(new Vector3d(session.lockedPosition));
                store.putComponent(ref, TransformComponent.getComponentType(), transform);
            }

            Velocity velocity = store.getComponent(ref, Velocity.getComponentType());
            if (velocity != null) {
                velocity.set(Vector3d.ZERO);
                store.putComponent(ref, Velocity.getComponentType(), velocity);
            }
        }
    }

    private static void applyZeroMovementSettings(
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null || movementManager.getSettings() == null) {
            return;
        }

        MovementSettings settings = movementManager.getSettings();
        settings.baseSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.acceleration = MIN_LOCKED_MOVEMENT_VALUE;
        settings.jumpForce = MIN_LOCKED_MOVEMENT_VALUE;
        settings.swimJumpForce = MIN_LOCKED_MOVEMENT_VALUE;
        settings.jumpBufferDuration = 0.0f;
        settings.jumpBufferMaxYVelocity = 0.0f;
        settings.climbSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.climbSpeedLateral = MIN_LOCKED_MOVEMENT_VALUE;
        settings.climbUpSprintSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.climbDownSprintSpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.horizontalFlySpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.verticalFlySpeed = MIN_LOCKED_MOVEMENT_VALUE;
        settings.maxSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.minSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardWalkSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.backwardWalkSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.strafeWalkSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardRunSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.backwardRunSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.strafeRunSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardCrouchSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.backwardCrouchSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.strafeCrouchSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        settings.forwardSprintSpeedMultiplier = MIN_LOCKED_MOVEMENT_VALUE;
        movementManager.update(playerRef.getPacketHandler());
        store.putComponent(ref, MovementManager.getComponentType(), movementManager);
    }

    private static void restoreMovementDefaults(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }
        movementManager.resetDefaultsAndUpdate(ref, store);
    }

    private static final class LockSession {
        private final long startsAtMs;
        private long endsAtMs;
        private UUID lockedWorldId;
        private Vector3d lockedPosition;

        private LockSession(long startsAtMs) {
            this.startsAtMs = startsAtMs;
            this.endsAtMs = Long.MAX_VALUE;
        }
    }
}
