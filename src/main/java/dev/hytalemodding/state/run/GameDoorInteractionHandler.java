package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.SpawnPointZoneConfigManager;
import dev.hytalemodding.state.transition.RunHubTransferService;
import dev.hytalemodding.ui.dev.DoorRunZoneSelectPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
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
            openDoorZoneSelection(playerRef);
            return true;
        }

        if (session.runWorldUuid() != null && session.runWorldUuid().equals(playerWorldUuid)) {
            tryExtractFromDoor(playerRef, session);
            return true;
        }
        playerRef.sendMessage(Message.raw("[DoorDebug] Active run exists but you are not in run world."));
        return false;
    }

    public static void openDoorZoneSelection(@Nonnull PlayerRef playerRef) {
        SpawnPointZoneManager.refreshForPlayer(playerRef);
        DoorRunZoneSelectionManager.ensureSelectedZoneOrDefault(playerRef.getUuid());
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new DoorRunZoneSelectPage(playerRef));
    }

    public static void tryStartFromDoorSelection(@Nonnull PlayerRef playerRef) {
        UUID playerWorldUuid = playerRef.getWorldUuid();
        if (playerWorldUuid == null) {
            return;
        }
        World templateWorld = Universe.get().getWorld(playerWorldUuid);
        if (templateWorld == null) {
            return;
        }
        tryStartFromDoor(playerRef, templateWorld);
    }

    private static void tryStartFromDoor(@Nonnull PlayerRef playerRef, @Nonnull World templateWorld) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        String hubWorldName = config.getHubWorldName();
        if (!isHubWorld(templateWorld)) {
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

        boolean hasBaseSpawn = config.hasBaseSpawn();
        if (!hasBaseSpawn) {
            playerRef.sendMessage(Message.raw("Setup missing: /setbasespawn"));
            System.out.println("[GameDoorDebug] start rejected: missing base spawn config");
            return;
        }

        SpawnPointZoneManager.refreshForPlayer(playerRef);
        Integer selectedZone = DoorRunZoneSelectionManager.ensureSelectedZoneOrDefault(playerRef.getUuid());
        if (selectedZone == null) {
            playerRef.sendMessage(Message.raw("No spawn zones with registered SpawnPoint_Block entries are available."));
            System.out.println("[GameDoorDebug] start rejected: missing selected door zone");
            return;
        }

        SpawnPointZoneManager.SpawnSelectionResult spawnSelection =
                SpawnPointZoneManager.reserveRandomSpawnForPlayer(runTemplateWorld, selectedZone.intValue(), playerRef.getUuid(), playerRef.getTransform());
        if (spawnSelection == null) {
            playerRef.sendMessage(Message.raw("Selected door zone " + SpawnPointZoneManager.getFormattedZoneLabel(selectedZone.intValue()) + " has no registered SpawnPoint_Block entries."));
            System.out.println("[GameDoorDebug] start rejected: selected zone has no spawn points zone=" + selectedZone);
            return;
        }

        Transform baseSpawn = config.getBaseSpawn();
        List<UUID> lockedPlayerIds = collectRunStartPlayerIds(playerRef);
        RunStartMovementLockManager.get().unlockPlayers(lockedPlayerIds);
        RunStartMovementLockManager.get().lockPlayerForIntro(playerRef);
        System.out.println("[GameDoorDebug] start requested by=" + playerRef.getUuid() + " template=" + runTemplateWorld.getName() + " zone=" + selectedZone);

        GameSessionManager.get().startSession(playerRef, runTemplateWorld, spawnSelection.transform(), baseSpawn).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                SpawnPointZoneManager.releaseReservedSpawn(playerRef.getUuid());
                RunStartMovementLockManager.get().unlockPlayers(lockedPlayerIds);
                playerRef.sendMessage(Message.raw("Failed to start run: " + reason));
                System.out.println("[GameDoorDebug] start failed: " + reason);
                return;
            }
            System.out.println("[GameDoorDebug] start prepared runWorld=" + result.runWorldName() + " location=" + SpawnPointZoneManager.getFormattedLocationLabel(selectedZone.intValue(), spawnSelection.locationIndex()));
            playerRef.sendMessage(Message.raw("Loading run from " + SpawnPointZoneManager.getFormattedLocationLabel(selectedZone.intValue(), spawnSelection.locationIndex()) + ". The timer will start when gameplay is ready."));
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
        boolean queuedRescue = RunHubTransferService.get().queueRescueForExtraction(session.runWorldUuid(), playerRef.getUuid());
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
            SpawnPointZoneManager.releaseReservedSpawn(playerRef.getUuid());
            System.out.println("[GameDoorDebug] extract success: " + result.message());
            playerRef.sendMessage(Message.raw("Extraction complete."));
            if (queuedRescue) {
                RunHubTransferService.get().spawnQueuedRescueInBase(playerRef, hubWorld, baseSpawn);
            }
        });
    }

    public static boolean isHubWorld(@Nullable World world) {
        return world != null && isHubWorld(world.getName());
    }

    public static boolean isHubWorld(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(worldName);
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

    @Nonnull
    private static List<UUID> collectRunStartPlayerIds(@Nonnull PlayerRef playerRef) {
        return List.of(playerRef.getUuid());
    }
}