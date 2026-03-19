package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;

import javax.annotation.Nonnull;

public final class SpawnPointPlacementHandler {
    private static final String SPAWN_POINT_BLOCK_ID = "SpawnPoint_Block";

    private SpawnPointPlacementHandler() {
    }

    public static void onPlaceBlock(@Nonnull PlaceBlockEvent event) {
        if (event.getItemInHand() == null) {
            return;
        }

        String itemId = event.getItemInHand().getItemId();
        String blockKey = event.getItemInHand().getBlockKey();
        if (!isSpawnPointPlacement(itemId, blockKey)) {
            return;
        }

        Vector3i target = event.getTargetBlock();
        if (target == null) {
            return;
        }

        SpawnPointZoneManager.registerPlacement(target);
    }

    private static boolean isSpawnPointPlacement(String itemId, String blockKey) {
        return matches(itemId) || matches(blockKey);
    }

    private static boolean matches(String value) {
        return value != null && value.toLowerCase().contains(SPAWN_POINT_BLOCK_ID.toLowerCase());
    }
}