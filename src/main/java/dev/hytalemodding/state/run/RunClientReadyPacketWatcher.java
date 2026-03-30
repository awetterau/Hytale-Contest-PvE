package dev.hytalemodding.state.run;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.player.ClientReady;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.UUID;

public final class RunClientReadyPacketWatcher implements PacketWatcher {
    public void register() {
        PacketAdapters.registerInbound(this);
    }

    public void unregister() {
        try {
            PacketAdapters.class.getMethod("unregisterInbound", PacketWatcher.class).invoke(null, this);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            System.out.println("[RunClientReady] packet watcher unregister failed: " + e.getMessage());
        }
    }

    @Override
    public void accept(PacketHandler packetHandler, Packet packet) {
        if (!(packet instanceof ClientReady clientReady) || packetHandler.getAuth() == null) {
            return;
        }
        UUID playerUuid = packetHandler.getAuth().getUuid();
        PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        GameSessionManager.get().onClientReadyPacket(playerUuid, clientReady.readyForGameplay);
    }
}