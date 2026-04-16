package dev.hytalemodding.state.party;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.ui.dev.PartyManagerPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PartyHubInteractionHandler {
    public static final String PARTY_MANAGER_BLOCK_ID = "Rock_Calcite_Brick_Half";
    public static final String PARTY_MANAGER_CUSTOM_BLOCK_ID = "Party_Manager_Block";
    private static final long USE_COOLDOWN_MS = 500L;
    private static final ConcurrentHashMap<UUID, Long> LAST_USE_BY_PLAYER = new ConcurrentHashMap<>();

    private PartyHubInteractionHandler() {
    }

    public static void onPlayerInteract(@Nonnull PlayerInteractEvent event) {
        if (event.getActionType() != InteractionType.Use || event.getTargetBlock() == null) {
            return;
        }
        PlayerRef playerRef = resolvePlayerRef(event);
        if (playerRef == null) {
            return;
        }
        handlePartyBlockTrigger(playerRef, event.getTargetBlock());
    }

    public static void onPlayerMouseButton(@Nonnull PlayerMouseButtonEvent event) {
        if (event.getMouseButton() == null || event.getMouseButton().state != MouseButtonState.Pressed || event.getTargetBlock() == null) {
            return;
        }
        handlePartyBlockTrigger(event.getPlayerRefComponent(), event.getTargetBlock());
    }

    public static boolean handlePartyBlockTrigger(@Nullable PlayerRef playerRef, @Nonnull Vector3i targetBlock) {
        if (playerRef == null || !playerRef.isValid() || isOnCooldown(playerRef.getUuid())) {
            return false;
        }
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) {
            return false;
        }
        World world = Universe.get().getWorld(worldUuid);
        if (world == null || !GameDoorInteractionHandler.isHubWorld(world)) {
            playerRef.sendMessage(Message.raw("Party manager only works in hub."));
            return false;
        }
        var block = world.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z);
        if (block == null) {
            return false;
        }
        String blockId = block.getId();
        if (!PARTY_MANAGER_BLOCK_ID.equals(blockId) && !PARTY_MANAGER_CUSTOM_BLOCK_ID.equals(blockId)) {
            return false;
        }
        var entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return false;
        }
        var store = entityRef.getStore();
        var player = store.getComponent(entityRef, com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
        if (player == null) {
            return false;
        }
        player.getPageManager().openCustomPage(entityRef, store, new PartyManagerPage(playerRef));
        return true;
    }

    private static boolean isOnCooldown(@Nonnull UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_USE_BY_PLAYER.put(playerId, now);
        return last != null && (now - last) < USE_COOLDOWN_MS;
    }

    @Nullable
    private static PlayerRef resolvePlayerRef(@Nonnull PlayerInteractEvent event) {
        if (event.getPlayerRef() != null && event.getPlayerRef().isValid()) {
            PlayerRef playerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
            if (playerRef != null) {
                return playerRef;
            }
        }
        return null;
    }
}