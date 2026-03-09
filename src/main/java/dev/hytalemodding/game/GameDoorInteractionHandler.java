package dev.hytalemodding.game;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameDoorInteractionHandler {
    public static final String GAME_DOOR_BLOCK_ID = "Game_Start_Door";
    private static final long USE_COOLDOWN_MS = 500L;
    private static final ConcurrentHashMap<UUID, Long> LAST_USE_BY_PLAYER = new ConcurrentHashMap<>();

    private GameDoorInteractionHandler() {
    }

    public static void onPlayerInteract(@Nonnull PlayerInteractEvent event) {
        if (event.getActionType() != InteractionType.Use || event.getTargetBlock() == null) {
            return;
        }
        PlayerRef playerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        if (playerRef == null) {
            playerRef = Universe.get().getPlayer(event.getPlayer().getUuid());
        }
        handleDoorTrigger(playerRef, event.getTargetBlock());
    }

    public static void onPlayerMouseButton(@Nonnull PlayerMouseButtonEvent event) {
        if (event.getMouseButton() == null || event.getMouseButton().state != MouseButtonState.Pressed) {
            return;
        }
        Vector3i target = event.getTargetBlock();
        if (target == null) {
            return;
        }
        handleDoorTrigger(event.getPlayerRefComponent(), target);
    }

    public static boolean handleDoorTrigger(@Nullable PlayerRef playerRef, @Nonnull Vector3i target) {
        if (playerRef == null) {
            return false;
        }

        if (isOnCooldown(playerRef.getUuid())) {
            return false;
        }

        UUID playerWorldUuid = playerRef.getWorldUuid();
        if (playerWorldUuid == null) {
            return false;
        }
        World playerWorld = Universe.get().getWorld(playerWorldUuid);
        if (playerWorld == null) {
            return false;
        }
        if (!isGameDoorBlock(playerWorld, target)) {
            return false;
        }

        System.out.println("[GameDoorDebug] trigger accepted: player=" + playerRef.getUuid() + " world=" + playerWorld.getName() + " block=" + target);

        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        if (session == null) {
            tryStartFromDoor(playerRef, playerWorld);
            return true;
        }

        if (session.runWorldUuid() != null && session.runWorldUuid().equals(playerWorldUuid)) {
            tryExtractFromDoor(playerRef, session);
            return true;
        }
        playerRef.sendMessage(Message.raw("[DoorDebug] Active run exists but you are not in run world."));
        return false;
    }

    private static void tryStartFromDoor(@Nonnull PlayerRef playerRef, @Nonnull World templateWorld) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        String hubWorldName = config.getHubWorldName();
        if (!hubWorldName.equalsIgnoreCase(templateWorld.getName())) {
            playerRef.sendMessage(Message.raw("Use the game door from hub world '" + hubWorldName + "'."));
            System.out.println("[GameDoorDebug] start rejected: wrong world " + templateWorld.getName() + " expected " + hubWorldName);
            return;
        }

        String templateWorldName = config.getTemplateWorldName();
        World runTemplateWorld = Universe.get().getWorld(templateWorldName);
        if (runTemplateWorld == null) {
            playerRef.sendMessage(Message.raw("Run template world not loaded: " + templateWorldName));
            System.out.println("[GameDoorDebug] start rejected: template world missing " + templateWorldName);
            return;
        }

        boolean hasRunSpawn = config.hasRunSpawn();
        boolean hasBaseSpawn = config.hasBaseSpawn();
        if (!hasRunSpawn || !hasBaseSpawn) {
            String missing = (hasRunSpawn ? "" : "/setrunspawn")
                    + ((!hasRunSpawn && !hasBaseSpawn) ? " and " : "")
                    + (hasBaseSpawn ? "" : "/setbasespawn");
            playerRef.sendMessage(Message.raw("Setup missing: " + missing));
            System.out.println("[GameDoorDebug] start rejected: missing config");
            return;
        }

        Transform runSpawn = config.getRunSpawn();
        Transform baseSpawn = config.getBaseSpawn();
        System.out.println("[GameDoorDebug] start requested by=" + playerRef.getUuid() + " template=" + runTemplateWorld.getName());

        GameSessionManager.get().startSession(playerRef, runTemplateWorld, runSpawn, baseSpawn).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to start run: " + reason));
                System.out.println("[GameDoorDebug] start failed: " + reason);
                return;
            }
            System.out.println("[GameDoorDebug] start success runWorld=" + result.runWorldName());
            playerRef.sendMessage(Message.raw("Run started. Reach the door to extract."));
        });
    }

    private static void tryExtractFromDoor(
            @Nonnull PlayerRef playerRef,
            @Nonnull GameSessionManager.ActiveSessionSnapshot session
    ) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        String hubWorldName = config.getHubWorldName();
        World hubWorld = Universe.get().getWorld(hubWorldName);
        if (hubWorld == null) {
            playerRef.sendMessage(Message.raw("Hub world is unavailable: " + hubWorldName));
            System.out.println("[GameDoorDebug] extract rejected: hub world missing " + hubWorldName);
            return;
        }

        Transform baseSpawn = nullableOrDefault(config.getBaseSpawn(), playerRef.getTransform());
        boolean queuedRescue = RescueObjectiveManager.get().queueRescueForExtraction(
                session.runWorldUuid(),
                playerRef.getUuid()
        );
        if (!queuedRescue) {
            playerRef.sendMessage(Message.raw("Rescue transfer not queued (already rescued or objective not ready)."));
        }
        System.out.println("[GameDoorDebug] extract requested by=" + playerRef.getUuid() + " runWorld=" + session.runWorldName() + " queuedRescue=" + queuedRescue);

        GameSessionManager.get().endSession(baseSpawn, hubWorld).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to extract: " + reason));
                System.out.println("[GameDoorDebug] extract failed: " + reason);
                return;
            }
            System.out.println("[GameDoorDebug] extract success: " + result.message());
            playerRef.sendMessage(Message.raw("Extraction complete."));
            if (queuedRescue) {
                RescueObjectiveManager.get().spawnQueuedRescueInBase(hubWorld, baseSpawn).whenComplete((spawned, spawnErr) -> {
                    // Rescue completion is tied to successful extraction while following.
                    // Base transfer spawn can fail independently; preserve rescued progression anyway.
                    RescueObjectiveManager.get().setBlacksmithRescued(true);
                    if (spawnErr != null || Boolean.FALSE.equals(spawned)) {
                        String reason = spawnErr != null ? spawnErr.getMessage() : "spawn returned false";
                        playerRef.sendMessage(Message.raw("Blacksmith marked rescued, but base transfer failed: " + reason));
                        System.out.println("[GameDoorDebug] rescue transfer failed (rescued preserved): " + reason);
                        return;
                    }
                    playerRef.sendMessage(Message.raw("Blacksmith rescued and added to base."));
                    System.out.println("[GameDoorDebug] rescue transfer success");
                });
            }
        });
    }

    private static boolean isGameDoorBlock(@Nonnull World world, @Nonnull Vector3i target) {
        BlockType blockType = world.getBlockType(target);
        return blockType != null && GAME_DOOR_BLOCK_ID.equals(blockType.getId());
    }

    @Nonnull
    private static Transform nullableOrDefault(@Nullable Transform configured, @Nonnull Transform fallback) {
        return configured != null ? configured : fallback;
    }

    private static boolean isOnCooldown(@Nonnull UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_USE_BY_PLAYER.put(playerId, now);
        return last != null && now - last < USE_COOLDOWN_MS;
    }
}
