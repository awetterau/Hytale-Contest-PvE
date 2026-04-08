package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Transform;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class RunStartCameraManager {
    private static final long INTRO_DELAY_FROM_RUN_START_MS = 0L;
    private static final long INTRO_MIN_DURATION_MS = 250L;
    private static final long INTRO_CAMERA_END_OFFSET_FROM_ANIMATION_MS = 200L;
    private static final String INTRO_PLAYER_ANIMATION_PATH = "Characters/Animations/Default/Player_Anim_LargeRope_A.blockyanim";
    private static final double CAMERA_DISTANCE = 2.4D;
    private static final double CAMERA_HEIGHT_OFFSET = 0.7D;
    private static final double PLAYER_LOOK_TARGET_HEIGHT = 1.3D;
    private static final String INTRO_PLAYER_ANIMATION = "Player_Anim_LargeRope_A";
    private static final String INTRO_ROPE_BLOCK_ID = "Large_Rope";
    private static final long CAMERA_REAPPLY_DELAY_MS = 1_600L;
    private static final RunStartCameraManager INSTANCE = new RunStartCameraManager();
    private static volatile long introEndFromRunStartMs = -1L;

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
        long cached = introEndFromRunStartMs;
        if (cached > 0L) {
            return cached;
        }

        BlockyAnimationCache.BlockyAnimation animation = BlockyAnimationCache.getNow(INTRO_PLAYER_ANIMATION_PATH);
        long animationDurationMs = animation == null
                ? 3_000L
                : Math.round(animation.getDurationMillis());
        long cameraDurationMs = Math.max(INTRO_MIN_DURATION_MS, animationDurationMs - INTRO_CAMERA_END_OFFSET_FROM_ANIMATION_MS);
        long resolved = INTRO_DELAY_FROM_RUN_START_MS + cameraDurationMs;
        introEndFromRunStartMs = resolved;
        return resolved;
    }

    public void playSpawnIntro(@Nonnull PlayerRef playerRef) {
        if (!RunStartPresentationConfig.isIntroCameraEnabled()) {
            restoreDefaultCamera(playerRef);
            restoreIntroHud(playerRef);
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
                        runStartedAtMs + getIntroEndFromRunStartMs()
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
                if (now >= session.endsAtMs) {
                    this.introByPlayer.remove(playerId, session);
                }
                continue;
            }
            if (!worldId.equals(playerRef.getWorldUuid())) {
                continue;
            }
            if (now >= session.endsAtMs) {
                restoreDefaultCamera(playerRef);
                restoreIntroHud(playerRef);
                this.introByPlayer.remove(playerId, session);
                continue;
            }
            if (now < session.startsAtMs) {
                continue;
            }

            if (session.cameraOffset == null) {
                session.cameraOffset = captureCameraOffset(world, playerRef);
            }
            if (!session.cameraApplied) {
                hideIntroHud(playerRef);
                applyTrackingCamera(playerRef, session.cameraOffset);
                applyIntroPresentation(world, playerRef, session);
                session.cameraApplied = true;
                session.cameraAppliedAtMs = now;
                continue;
            }
            if (!session.cameraReapplied && session.cameraAppliedAtMs > 0L && now - session.cameraAppliedAtMs >= CAMERA_REAPPLY_DELAY_MS) {
                applyTrackingCamera(playerRef, session.cameraOffset);
                session.cameraReapplied = true;
            }
        }
    }

    @Nonnull
    private static Vector3d captureCameraOffset(@Nonnull World world, @Nonnull PlayerRef playerRef) {
        Transform transform = playerRef.getTransform();
        double playerX = transform.getPosition().getX();
        double playerY = transform.getPosition().getY();
        double playerZ = transform.getPosition().getZ();

        ArrayList<Vector3d> candidates = new ArrayList<>(4);
        candidates.add(new Vector3d(CAMERA_DISTANCE, CAMERA_HEIGHT_OFFSET, 0.0d));
        candidates.add(new Vector3d(-CAMERA_DISTANCE, CAMERA_HEIGHT_OFFSET, 0.0d));
        candidates.add(new Vector3d(0.0d, CAMERA_HEIGHT_OFFSET, CAMERA_DISTANCE));
        candidates.add(new Vector3d(0.0d, CAMERA_HEIGHT_OFFSET, -CAMERA_DISTANCE));
        Collections.shuffle(candidates, ThreadLocalRandom.current());

        for (Vector3d offset : candidates) {
            Position candidate = new Position(playerX + offset.getX(), playerY + offset.getY(), playerZ + offset.getZ());
            if (isClearForCamera(world, candidate)) {
                return offset;
            }
        }

        for (int step = 1; step <= 3; step++) {
            Vector3d raisedOffset = new Vector3d(0.0d, CAMERA_HEIGHT_OFFSET + (step * 0.8d), 0.0d);
            Position raised = new Position(playerX + raisedOffset.getX(), playerY + raisedOffset.getY(), playerZ + raisedOffset.getZ());
            if (isClearForCamera(world, raised)) {
                return raisedOffset;
            }
        }

        return new Vector3d(0.0d, CAMERA_HEIGHT_OFFSET + 1.6d, 0.0d);
    }

    private static boolean isClearForCamera(@Nonnull World world, @Nonnull Position position) {
        int baseX = (int) Math.floor(position.x);
        int baseY = (int) Math.floor(position.y);
        int baseZ = (int) Math.floor(position.z);
        return isOpenBlock(world.getBlockType(baseX, baseY, baseZ))
                && isOpenBlock(world.getBlockType(baseX, baseY + 1, baseZ));
    }

    private static boolean isOpenBlock(BlockType type) {
        return type == null || "Empty".equalsIgnoreCase(type.getId());
    }

    private static void applyTrackingCamera(@Nonnull PlayerRef playerRef, @Nonnull Vector3d cameraOffset) {
        Transform transform = playerRef.getTransform();
        double targetX = transform.getPosition().getX();
        double targetY = transform.getPosition().getY() + PLAYER_LOOK_TARGET_HEIGHT;
        double targetZ = transform.getPosition().getZ();
        double cameraX = targetX + cameraOffset.getX();
        double cameraY = transform.getPosition().getY() + cameraOffset.getY();
        double cameraZ = targetZ + cameraOffset.getZ();

        double lookDx = targetX - cameraX;
        double lookDy = targetY - cameraY;
        double lookDz = targetZ - cameraZ;
        double horizontalDistance = Math.sqrt((lookDx * lookDx) + (lookDz * lookDz));

        float yaw = (float) Math.atan2(-lookDx, -lookDz);
        float pitch = (float) Math.atan2(lookDy, horizontalDistance);

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.isFirstPerson = false;
        settings.displayReticle = false;
        settings.positionType = PositionType.AttachedToPlusOffset;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.positionOffset = new Position(cameraOffset.getX(), cameraOffset.getY(), cameraOffset.getZ());
        settings.positionLerpSpeed = 0.15f;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(yaw, pitch, 0.0f);
        settings.rotationLerpSpeed = 0.15f;

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    private static void restoreDefaultCamera(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.FirstPerson, false, null));
    }

    private static void hideIntroHud(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() == null) {
            return;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getHudManager().setVisibleHudComponents(playerRef, new HudComponent[0]);
        player.getHudManager().setCustomHud(playerRef, null);
        player.getPageManager().setPage(playerEntityRef, store, Page.None);
    }

    private static void restoreIntroHud(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() == null) {
            return;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getHudManager().resetHud(playerRef);
    }

    private static void applyIntroPresentation(@Nonnull World world, @Nonnull PlayerRef playerRef, @Nonnull IntroSession session) {
        Ref<EntityStore> playerRefHandle = playerRef.getReference();
        if (playerRefHandle == null || !playerRefHandle.isValid() || playerRefHandle.getStore() == null) {
            return;
        }
        AnimationUtils.stopAnimation(playerRefHandle, AnimationSlot.Movement, true, playerRefHandle.getStore());
        AnimationUtils.stopAnimation(playerRefHandle, AnimationSlot.Emote, true, playerRefHandle.getStore());
        AnimationUtils.playAnimation(
                playerRefHandle,
                AnimationSlot.Movement,
                null,
                INTRO_PLAYER_ANIMATION,
                true,
                playerRefHandle.getStore()
        );
        AnimationUtils.playAnimation(
                playerRefHandle,
                AnimationSlot.Action,
                null,
                INTRO_PLAYER_ANIMATION,
                true,
                playerRefHandle.getStore()
        );

        Transform transform = playerRef.getTransform();
        int blockX = (int) Math.floor(transform.getPosition().getX());
        int blockY = (int) Math.floor(transform.getPosition().getY());
        int blockZ = (int) Math.floor(transform.getPosition().getZ());
        world.setBlock(blockX, blockY, blockZ, INTRO_ROPE_BLOCK_ID);
    }

    private static final class IntroSession {
        private final long startsAtMs;
        private final long endsAtMs;
        private Vector3d cameraOffset;
        private boolean cameraApplied;
        private boolean cameraReapplied;
        private long cameraAppliedAtMs;

        private IntroSession(long startsAtMs, long endsAtMs) {
            this.startsAtMs = startsAtMs;
            this.endsAtMs = endsAtMs;
        }
    }
}
