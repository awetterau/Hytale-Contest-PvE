package dev.hytalemodding.map;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.util.PositionUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.hytalemodding.domain.housing.BaseHousingManager;

import javax.annotation.Nonnull;

public final class BlacksmithWorkshopMarkerProvider implements WorldMapManager.MarkerProvider {
    public static final BlacksmithWorkshopMarkerProvider INSTANCE = new BlacksmithWorkshopMarkerProvider();

    private static final String MARKER_ID = "blacksmith-workshop";
    private static final String MARKER_NAME = "Blacksmith";
    private static final String MARKER_ICON = "UserF.png";
    private static final String MARKER_TEXT_COLOR = "#ff9b3d";

    private BlacksmithWorkshopMarkerProvider() {
    }

    @Override
    public void update(
            @Nonnull World world,
            @Nonnull Player player,
            @Nonnull MarkersCollector collector
    ) {
        BaseHousingManager housing = BaseHousingManager.get();
        if (!housing.isBlacksmithWorkshopReady()) {
            return;
        }
        Transform markerTransform = housing.getBlacksmithWorkshopMarkerTransform();
        Vector3d markerPos = markerTransform.getPosition();
        if (!collector.isInViewDistance(markerPos)) {
            return;
        }
        FormattedMessage markerName = new FormattedMessage();
        markerName.rawText = MARKER_NAME;
        markerName.color = MARKER_TEXT_COLOR;
        collector.add(new MapMarker(
                MARKER_ID,
                markerName,
                null,
                MARKER_ICON,
                PositionUtil.toTransformPacket(markerTransform),
                null,
                null
        ));
    }
}
