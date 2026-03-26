package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class RunStartCameraManager {
    private static final long INTRO_DELAY_FROM_RUN_START_MS = 5_000L;
    private static final long INTRO_END_FROM_RUN_START_MS = 15_000L;
    private static final double CAMERA_DISTANCE = 2.4D;
    private static final double CAMERA_HEIGHT_OFFSET = 0.7D;
    private static final double PLAYER_LOOK_TARGET_HEIGHT = 1.3D;
    private static final RunStartCameraManager INSTANCE = new RunStartCameraManager();

    private final ConcurrentHashMap<UUID, IntroSession> introByPlayer = new ConcurrentHashMap<>();

    private RunStartCameraManager() {
    }

    @Nonnull
    public static RunStartCameraManager get() {
        return INSTANCE;
    }

    public static long getIntroDelayFromRunStartMs() {
        return INTRO_DELAY_FROM_RUN_START_MS;
    }

    public static long getIntroEndFromRunStartMs() {
        return INTRO_END_FROM_RUN_START_MS;
    }

    public void playSpawnIntro(@Nonnull PlayerRef playerRef) {
        if (!RunStartPresentationConfig.isIntroCameraEnabled()) {
            restoreDefaultCamera(playerRef);
            this.introByPlayer.remove(playerRef.getUuid());
            return;
        }
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.phase() != GameSessionManager.RunPhase.EXPLORATION || snapshot.startedAtEpochMillis() <= 0L) {
            return;
        }

        long runStartedAtMs = snapshot.startedAtEpochMillis();
        this.introByPlayer.put(
                playerRef.getUuid(),
                new IntroSession(
                        runStartedAtMs + INTRO_DELAY_FROM_RUN_START_MS,
                        runStartedAtMs + INTRO_END_FROM_RUN_START_MS
                )
        );
    }

    public void tick(@Nonnull World world) {
        if (!RunStartPresentationConfig.isIntroCameraEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID worldId = world.getWorldConfig().getUuid();

        for (Map.Entry<UUID, IntroSession> entry : this.introByPlayer.entrySet()) {
            UUID playerId = entry.getKey();
            IntroSession session = entry.getValue();
            if (session == null) {
                this.introByPlayer.remove(playerId);
                continue;
            }

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || !playerRef.isValid() || playerRef.getWorldUuid() == null) {
                this.introByPlayer.remove(playerId, session);
                continue;
            }
            if (!worldId.equals(playerRef.getWorldUuid())) {
                continue;
            }

            if (now < session.startsAtMs) {
                continue;
            }
            if (now >= session.endsAtMs) {
                restoreDefaultCamera(playerRef);
                this.introByPlayer.remove(playerId, session);
                continue;
            }

            if (session.cameraPosition == null) {
                session.cameraPosition = captureCameraPosition(playerRef);
            }
            if (!session.cameraApplied) {
                applyTrackingCamera(playerRef, session.cameraPosition);
                session.cameraApplied = true;
            }
        }
    }

    @Nonnull
    private static Position captureCameraPosition(@Nonnull PlayerRef playerRef) {
        Transform transform = playerRef.getTransform();
        Vector3d offset = pickRandomCameraOffset();
        return new Position(
                transform.getPosition().getX() + offset.getX(),
                transform.getPosition().getY() + CAMERA_HEIGHT_OFFSET,
                transform.getPosition().getZ() + offset.getZ()
        );
    }

    @Nonnull
    private static Vector3d pickRandomCameraOffset() {
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> new Vector3d(CAMERA_DISTANCE, 0.0d, 0.0d);
            case 1 -> new Vector3d(-CAMERA_DISTANCE, 0.0d, 0.0d);
            case 2 -> new Vector3d(0.0d, 0.0d, CAMERA_DISTANCE);
            default -> new Vector3d(0.0d, 0.0d, -CAMERA_DISTANCE);
        };
    }

    private static void applyTrackingCamera(@Nonnull PlayerRef playerRef, @Nonnull Position cameraPosition) {
        Transform transform = playerRef.getTransform();
        double targetX = transform.getPosition().getX();
        double targetY = transform.getPosition().getY() + PLAYER_LOOK_TARGET_HEIGHT;
        double targetZ = transform.getPosition().getZ();

        double lookDx = targetX - cameraPosition.x;
        double lookDy = targetY - cameraPosition.y;
        double lookDz = targetZ - cameraPosition.z;
        double horizontalDistance = Math.sqrt((lookDx * lookDx) + (lookDz * lookDz));

        float yaw = (float) Math.atan2(-lookDx, -lookDz);
        float pitch = (float) Math.atan2(lookDy, horizontalDistance);

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.isFirstPerson = false;
        settings.displayReticle = false;
        settings.positionType = PositionType.Custom;
        settings.position = cameraPosition;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(yaw, pitch, 0.0f);

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    private static void restoreDefaultCamera(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.FirstPerson, false, null));
    }

    private static final class IntroSession {
        private final long startsAtMs;
        private final long endsAtMs;
        private Position cameraPosition;
        private boolean cameraApplied;

        private IntroSession(long startsAtMs, long endsAtMs) {
            this.startsAtMs = startsAtMs;
            this.endsAtMs = endsAtMs;
        }
    }
}
