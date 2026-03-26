package dev.hytalemodding.map;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.util.PositionUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.loot.LootChestRuntime;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QuestChestMarkerPacketController {
    @Nonnull
    private final JavaPlugin plugin;
    private PacketFilter outboundFilter;

    public QuestChestMarkerPacketController(@Nonnull JavaPlugin plugin) {
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
        LootChestRuntime.QuestChestState state = LootChestRuntime.get().getQuestChest(worldUuid);
        if (state == null || !state.markerEnabled()) {
            return false;
        }

        MapMarker marker = buildMarker(state);
        MapMarker[] existing = updateWorldMap.addedMarkers;
        if (containsMarker(existing, state.markerId())) {
            return false;
        }
        List<MapMarker> markers = new ArrayList<>(existing == null ? 1 : existing.length + 1);
        if (existing != null) {
            for (MapMarker existingMarker : existing) {
                if (existingMarker != null) {
                    markers.add(existingMarker);
                }
            }
        }
        markers.add(marker);
        updateWorldMap.addedMarkers = markers.toArray(MapMarker[]::new);
        return false;
    }

    @Nonnull
    private static MapMarker buildMarker(@Nonnull LootChestRuntime.QuestChestState state) {
        FormattedMessage label = new FormattedMessage();
        label.rawText = state.markerName();
        label.color = state.markerColor();
        Transform transform = new Transform(
                new Vector3d(state.position().x + 0.5D, state.position().y + 0.5D, state.position().z + 0.5D),
                new Vector3f(0.0f, 0.0f, 0.0f)
        );
        return new MapMarker(
                state.markerId(),
                label,
                null,
                state.markerIcon(),
                PositionUtil.toTransformPacket(transform),
                null,
                null
        );
    }

    private static boolean containsMarker(MapMarker[] markers, @Nonnull String markerId) {
        if (markers == null) {
            return false;
        }
        for (MapMarker marker : markers) {
            if (marker != null && markerId.equalsIgnoreCase(marker.id)) {
                return true;
            }
        }
        return false;
    }
}
