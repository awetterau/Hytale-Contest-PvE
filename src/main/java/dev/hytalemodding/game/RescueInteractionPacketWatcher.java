package dev.hytalemodding.game;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RescueInteractionPacketWatcher implements PacketWatcher {
    private static final long COOLDOWN_MS = 250L;
    private final ConcurrentHashMap<UUID, Long> cooldownByPlayer = new ConcurrentHashMap<>();

    public void register() {
        PacketAdapters.registerInbound(this);
    }

    public void unregister() {
        try {
            PacketAdapters.class.getMethod("unregisterInbound", PacketWatcher.class).invoke(null, this);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            System.out.println("[RescueDebug] packet watcher unregister failed: " + e.getMessage());
        }
    }

    @Override
    public void accept(PacketHandler packetHandler, Packet packet) {
        if (!(packet instanceof SyncInteractionChains) || packetHandler.getAuth() == null) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(packetHandler.getAuth().getUuid());
        if (playerRef == null || !playerRef.isValid() || playerRef.getWorldUuid() == null) {
            return;
        }
        if (!checkCooldown(playerRef.getUuid())) {
            return;
        }

        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            return;
        }

        SyncInteractionChain[] updates = ((SyncInteractionChains) packet).updates;
        world.execute(() -> processChains(playerRef, world, updates));
    }

    private void processChains(
            @Nonnull PlayerRef playerRef,
            @Nonnull World world,
            @Nonnull SyncInteractionChain[] updates
    ) {
        EntityStore entityStore = world.getEntityStore().getStore().getExternalData();
        for (SyncInteractionChain chain : updates) {
            if (chain == null || chain.data == null) {
                continue;
            }
            InteractionType type = chain.interactionType;
            if (type != InteractionType.Use && type != InteractionType.Primary && type != InteractionType.Secondary) {
                continue;
            }
            Ref<EntityStore> targetRef = entityStore.getRefFromNetworkId(chain.data.entityId);
            if (targetRef == null || !targetRef.isValid()) {
                continue;
            }
            RescueObjectiveManager.get().markFollowingFromNpcRef(playerRef, targetRef, chain.interactionType);
        }
    }

    private boolean checkCooldown(@Nonnull UUID playerUuid) {
        long now = System.currentTimeMillis();
        Long previous = this.cooldownByPlayer.put(playerUuid, now);
        return previous == null || now - previous >= COOLDOWN_MS;
    }
}
