package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.RegionSpawnMarkerConfigManager;

import javax.annotation.Nonnull;

public final class RegionSpawnPlacementHandler {
    private RegionSpawnPlacementHandler() {
    }

    public static void onPlaceBlock(@Nonnull PlaceBlockEvent event) {
        if (event.getItemInHand() == null) {
            return;
        }

        String itemId = event.getItemInHand().getItemId();
        String blockKey = event.getItemInHand().getBlockKey();
        String regionId = RegionSpawnConfig.get().findRegionForMarkerBlock(itemId);
        if (regionId == null) {
            regionId = RegionSpawnConfig.get().findRegionForMarkerBlock(blockKey);
        }
        if (regionId == null) {
            return;
        }

        Vector3i target = event.getTargetBlock();
        if (target == null) {
            return;
        }

        String worldName = resolveWorldName();
        RegionSpawnMarkerConfigManager.RegionMarkerEntry entry =
                new RegionSpawnMarkerConfigManager.RegionMarkerEntry(regionId, new Vector3i(target.x, target.y, target.z), worldName);
        boolean added = RegionSpawnMarkerConfigManager.addMarker(worldName, entry);
        if (!added) {
            return;
        }

        Universe.get().sendMessage(Message.raw("[Region Spawn] "
                + regionId + " marker registered at "
                + target.x + "," + target.y + "," + target.z
                + " (world=" + worldName + ")"));
    }

    @Nonnull
    private static String resolveWorldName() {
        return GameFlowConfigManager.get().getTemplateWorldName();
    }
}
