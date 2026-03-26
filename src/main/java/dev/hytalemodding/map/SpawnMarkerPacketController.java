package dev.hytalemodding.map;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class SpawnMarkerPacketController {
    private static final String SPAWN_MARKER_IMAGE = "Spawn.png";

    @Nonnull
    private final JavaPlugin plugin;
    private PacketFilter outboundFilter;

    public SpawnMarkerPacketController(@Nonnull JavaPlugin plugin) {
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

    private boolean handleOutbound(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (!(packet instanceof UpdateWorldMap updateWorldMap) || updateWorldMap.addedMarkers == null || updateWorldMap.addedMarkers.length == 0) {
            return false;
        }
        MapMarker[] filtered = filterSpawnMarkers(updateWorldMap.addedMarkers);
        if (filtered.length != updateWorldMap.addedMarkers.length) {
            updateWorldMap.addedMarkers = filtered.length == 0 ? null : filtered;
        }
        return false;
    }

    @Nonnull
    private static MapMarker[] filterSpawnMarkers(@Nonnull MapMarker[] markers) {
        List<MapMarker> kept = new ArrayList<>(markers.length);
        for (MapMarker marker : markers) {
            if (marker == null) {
                continue;
            }
            if (SPAWN_MARKER_IMAGE.equalsIgnoreCase(marker.markerImage)) {
                continue;
            }
            kept.add(marker);
        }
        return kept.toArray(MapMarker[]::new);
    }
}
