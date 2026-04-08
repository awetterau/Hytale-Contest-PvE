package dev.hytalemodding.map;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.state.run.FarmerAnimalRescueManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FarmerAnimalMarkerPacketController {
    @Nonnull
    private final JavaPlugin plugin;
    private PacketFilter outboundFilter;

    public FarmerAnimalMarkerPacketController(@Nonnull JavaPlugin plugin) {
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
        if (!(packet instanceof UpdateWorldMap updateWorldMap)) {
            return false;
        }
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) {
            return false;
        }
        MapMarker marker = FarmerAnimalRescueManager.get().buildActiveMarker(worldUuid);
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
                if (marker.id.equalsIgnoreCase(existingMarker.id)) {
                    continue;
                }
                markers.add(existingMarker);
            }
        }
        markers.add(marker);
        updateWorldMap.addedMarkers = markers.toArray(MapMarker[]::new);
        return false;
    }
}
