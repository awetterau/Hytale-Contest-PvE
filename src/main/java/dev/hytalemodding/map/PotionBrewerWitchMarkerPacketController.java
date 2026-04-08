package dev.hytalemodding.map;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PotionBrewerWitchMarkerPacketController {
    private static final String WITCH_MARKER_ID = "potion_brewer_witch";
    private static final String WITCH_MARKER_NAME = "Potion Brewer Witch";
    private static final String WITCH_MARKER_ICON = "UserF.png";
    private static final String WITCH_MARKER_COLOR = "#FF00FF";

    private static final ConcurrentHashMap<UUID, Vector3d> witchPositionsByWorld = new ConcurrentHashMap<>();

    @Nonnull
    private final JavaPlugin plugin;
    private PacketFilter outboundFilter;

    public PotionBrewerWitchMarkerPacketController(@Nonnull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        this.outboundFilter = PacketAdapters.registerOutbound((PlayerPacketFilter) this::handleOutbound);
    }

    public void unregister() {
        if (this.outboundFilter != null) {
            try {
                PacketAdapters.deregisterOutbound(this.outboundFilter);
            } catch (IllegalArgumentException ignored) {
            }
            this.outboundFilter = null;
        }
    }

    public static void setWitchPosition(@Nonnull UUID worldUuid, @Nonnull Vector3d position) {
        witchPositionsByWorld.put(worldUuid, position);
    }

    public static void clearWitchPosition(@Nonnull UUID worldUuid) {
        witchPositionsByWorld.remove(worldUuid);
    }

    private boolean handleOutbound(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (!(packet instanceof UpdateWorldMap updateWorldMap)) {
            return false;
        }
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) {
            return false;
        }

        @Nullable Vector3d witchPos = witchPositionsByWorld.get(worldUuid);
        if (witchPos == null) {
            return false;
        }

        MapMarker marker = buildWitchMarker(witchPos);
        if (marker == null) {
            return false;
        }

        MapMarker[] existing = updateWorldMap.addedMarkers;
        List<MapMarker> markers = new ArrayList<>(existing == null ? 1 : existing.length + 1);
        if (existing != null) {
            for (MapMarker existingMarker : existing) {
                if (existingMarker == null) {
                    continue;
                }
                if (WITCH_MARKER_ID.equalsIgnoreCase(existingMarker.id)) {
                    continue;
                }
                markers.add(existingMarker);
            }
        }
        markers.add(marker);
        updateWorldMap.addedMarkers = markers.toArray(MapMarker[]::new);
        return false;
    }

    @Nullable
    private static MapMarker buildWitchMarker(@Nonnull Vector3d position) {
        FormattedMessage label = new FormattedMessage();
        label.rawText = WITCH_MARKER_NAME;
        label.color = WITCH_MARKER_COLOR;
        Transform markerTransform = new Transform(
                position,
                new com.hypixel.hytale.math.vector.Vector3f(0, 0, 0)
        );
        return new MapMarker(
                WITCH_MARKER_ID,
                label,
                WITCH_MARKER_ICON,
                com.hypixel.hytale.server.core.util.PositionUtil.toTransformPacket(markerTransform),
                null,
                null
        );
    }
}
