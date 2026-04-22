package dev.hytalemodding.blob;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FlareExtractionSystem extends TickingSystem<EntityStore> {
    private static final Vector3i[] ROPE_OFFSETS = new Vector3i[]{
            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1)
    };

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        Map<String, FlareExtractionManager.Session> sessions = FlareExtractionManager.getSessions(worldId);
        if (sessions.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<FlareExtractionManager.Session> copy = new ArrayList<>(sessions.values());
        for (FlareExtractionManager.Session session : copy) {
            tickSession(world, session, now);
        }
    }

    private static void tickSession(
            @Nonnull World world,
            @Nonnull FlareExtractionManager.Session session,
            long now
    ) {
        if (session.phase() == FlareExtractionManager.Phase.WAITING && now >= session.waitEndAt()) {
            clearNearbyRopeBlocks(world, session);
            placeRope(world, session);
            session.phase(FlareExtractionManager.Phase.WINDOW_OPEN);
            broadcastToRunWorld(session.worldId(), "Flare extraction rope deployed. Extraction window is now open.");
            return;
        }

        if (session.phase() == FlareExtractionManager.Phase.WINDOW_OPEN && now >= session.windowEndAt()) {
            triggerExtractionForNearbyPlayers(session);
            if (session.ropePlaced()) {
                world.setBlock(session.ropeX(), session.ropeY(), session.ropeZ(), FlareExtractionManager.LARGE_ROPE_UP_BLOCK_ID);
            }
            session.ropeUpEndAt(now + FlareExtractionManager.ropeUpAnimMs());
            session.phase(FlareExtractionManager.Phase.ROPE_RETRACTING);
            broadcastToRunWorld(session.worldId(), "Flare extraction window closed. Rope is retracting.");
            return;
        }

        if (session.phase() == FlareExtractionManager.Phase.ROPE_RETRACTING && now >= session.ropeUpEndAt()) {
            cleanupSession(world, session);
            session.phase(FlareExtractionManager.Phase.COMPLETE);
            FlareExtractionManager.removeSession(session);
            broadcastToRunWorld(session.worldId(), "Flare extraction reset complete.");
        }
    }

    private static void placeRope(
            @Nonnull World world,
            @Nonnull FlareExtractionManager.Session session
    ) {
        int start = (int) (Math.abs(session.id().getLeastSignificantBits()) % ROPE_OFFSETS.length);
        for (int i = 0; i < ROPE_OFFSETS.length; i++) {
            Vector3i offset = ROPE_OFFSETS[(start + i) % ROPE_OFFSETS.length];
            int x = session.flareX() + offset.x;
            int y = session.flareY();
            int z = session.flareZ() + offset.z;
            if (!OrangeBlobBlockManager.isEmpty(world.getBlockType(x, y, z))) {
                continue;
            }
            world.setBlock(x, y, z, FlareExtractionManager.LARGE_ROPE_BLOCK_ID);
            session.ropePosition(x, y, z);
            session.ropePlaced(true);
            return;
        }
    }

    private static void cleanupSession(
            @Nonnull World world,
            @Nonnull FlareExtractionManager.Session session
    ) {
        clearNearbyRopeBlocks(world, session);
        if (session.ropePlaced()) {
            world.setBlock(session.ropeX(), session.ropeY(), session.ropeZ(), FlareExtractionManager.EMPTY_BLOCK_ID);
        }
        world.setBlock(
                session.flareX(),
                session.flareY(),
                session.flareZ(),
                FlareExtractionManager.FLARE_BLOCK_ID,
                session.flareInitialRotation()
        );
    }

    private static void triggerExtractionForNearbyPlayers(@Nonnull FlareExtractionManager.Session session) {
        double centerX = session.flareX() + 0.5d;
        double centerY = session.flareY() + 0.5d;
        double centerZ = session.flareZ() + 0.5d;
        double radiusSq = session.extractionRadiusBlocks() * session.extractionRadiusBlocks();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getWorldUuid() == null || !playerRef.getWorldUuid().equals(session.worldId())) {
                continue;
            }
            Vector3d position = playerRef.getTransform().getPosition();
            double dx = position.getX() - centerX;
            double dz = position.getZ() - centerZ;
            if ((dx * dx) + (dz * dz) > radiusSq) {
                continue;
            }
            double dy = position.getY() - centerY;
            if (dy < session.extractionMinHeightOffset() || dy > session.extractionMaxHeightOffset()) {
                continue;
            }
            GameDoorInteractionHandler.completeActiveRunExtraction(playerRef);
        }
    }

    private static void clearNearbyRopeBlocks(
            @Nonnull World world,
            @Nonnull FlareExtractionManager.Session session
    ) {
        for (Vector3i offset : ROPE_OFFSETS) {
            int x = session.flareX() + offset.x;
            int y = session.flareY();
            int z = session.flareZ() + offset.z;
            String id = world.getBlockType(x, y, z) == null ? null : world.getBlockType(x, y, z).getId();
            if (FlareExtractionManager.LARGE_ROPE_BLOCK_ID.equals(id)
                    || FlareExtractionManager.LARGE_ROPE_UP_BLOCK_ID.equals(id)) {
                world.setBlock(x, y, z, FlareExtractionManager.EMPTY_BLOCK_ID);
            }
        }
    }

    private static void broadcastToRunWorld(@Nonnull UUID worldId, @Nonnull String message) {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getWorldUuid() == null || !playerRef.getWorldUuid().equals(worldId)) {
                continue;
            }
            playerRef.sendMessage(Message.raw(message));
        }
    }
}