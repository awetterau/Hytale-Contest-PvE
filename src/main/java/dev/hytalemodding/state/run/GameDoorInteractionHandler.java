package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.SpawnPointZoneConfigManager;
import dev.hytalemodding.state.transition.RunHubTransferService;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.ui.dev.DoorRunZoneSelectPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameDoorInteractionHandler {
    public static final String GAME_DOOR_BLOCK_ID = "Game_Start_Door";
    public static final String GAME_ROPE_BLOCK_ID = "Game_Start_Rope";
    public static final String GAME_DOOR_TRIGGER_ROLE = "Game_Start_Trigger";
    private static final long USE_COOLDOWN_MS = 500L;
    private static final ConcurrentHashMap<UUID, Long> LAST_USE_BY_PLAYER = new ConcurrentHashMap<>();

    private GameDoorInteractionHandler() {
    }

    public static void onPlayerInteract(@Nonnull PlayerInteractEvent event) {
        if (event.getActionType() != InteractionType.Use) {
            return;
        }
        PlayerRef playerRef = resolvePlayerRef(event);
        if (playerRef == null) {
            return;
        }

        if (event.getTargetBlock() != null && handleDoorTrigger(playerRef, event.getTargetBlock())) {
            return;
        }

        if (isGameDoorTriggerEntity(event.getTargetRef())) {
            handleDoorEntityTrigger(playerRef);
        }
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
        return handleDoorTriggerInternal(playerRef, target, false);
    }

    public static boolean handleDoorEntityTrigger(@Nullable PlayerRef playerRef) {
        return handleDoorTriggerInternal(playerRef, null, true);
    }

    private static boolean handleDoorTriggerInternal(
            @Nullable PlayerRef playerRef,
            @Nullable Vector3i target,
            boolean allowEntityTrigger
    ) {
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
        if (!allowEntityTrigger && (target == null || !isGameDoorBlock(playerWorld, target))) {
            return false;
        }

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
            return;
        }

        String templateWorldName = config.getTemplateWorldName();
        World runTemplateWorld = Universe.get().getWorld(templateWorldName);
        if (runTemplateWorld == null) {
            playerRef.sendMessage(Message.raw("Run template world not loaded: " + templateWorldName));
            return;
        }

        boolean hasBaseSpawn = config.hasBaseSpawn();
        if (!hasBaseSpawn) {
            playerRef.sendMessage(Message.raw("Setup missing: /setbasespawn"));
            return;
        }

        SpawnPointZoneManager.refreshForPlayer(playerRef);
        Integer selectedZone = DoorRunZoneSelectionManager.ensureSelectedZoneOrDefault(playerRef.getUuid());
        if (selectedZone == null) {
            playerRef.sendMessage(Message.raw("No spawn zones with registered SpawnPoint_Block entries are available."));
            return;
        }

        SpawnPointZoneManager.SpawnSelectionResult spawnSelection =
                SpawnPointZoneManager.reserveRandomSpawnForPlayer(runTemplateWorld, selectedZone.intValue(), playerRef.getUuid(), playerRef.getTransform());
        if (spawnSelection == null) {
            playerRef.sendMessage(Message.raw("Selected door zone " + SpawnPointZoneManager.getFormattedZoneLabel(selectedZone.intValue()) + " has no registered SpawnPoint_Block entries."));
            return;
        }

        Transform baseSpawn = config.getBaseSpawn();
        List<UUID> lockedPlayerIds = collectRunStartPlayerIds(playerRef);
        RunStartMovementLockManager.get().unlockPlayers(lockedPlayerIds);
        RunStartMovementLockManager.get().lockPlayerForIntro(playerRef);

        GameSessionManager.get().startSession(playerRef, runTemplateWorld, spawnSelection.transform(), baseSpawn).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                SpawnPointZoneManager.releaseReservedSpawn(playerRef.getUuid());
                RunStartMovementLockManager.get().unlockPlayers(lockedPlayerIds);
                playerRef.sendMessage(Message.raw("Failed to start run: " + reason));
                return;
            }
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
            return;
        }

        Transform baseSpawn = nullableOrDefault(config.getBaseSpawn(), playerRef.getTransform());
        boolean queuedRescue = RunHubTransferService.get().queueRescueForExtraction(session.runWorldUuid(), playerRef.getUuid());
        if (!queuedRescue) {
            playerRef.sendMessage(Message.raw("Rescue transfer not queued (already rescued or objective not ready)."));
        }

        GameSessionManager.get().endSession(baseSpawn, hubWorld).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                playerRef.sendMessage(Message.raw("Failed to extract: " + reason));
                return;
            }
            SpawnPointZoneManager.releaseReservedSpawn(playerRef.getUuid());
            healIfAdventurePlayer(playerRef);
            playerRef.sendMessage(Message.raw("Extraction complete."));
            QuestProgressManager.get().incrementSuccessfulExtraction(playerRef);
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
        if (blockType == null) {
            return false;
        }
        String id = blockType.getId();
        return GAME_DOOR_BLOCK_ID.equals(id) || GAME_ROPE_BLOCK_ID.equals(id);
    }

    public static boolean isGameDoorTriggerEntity(@Nullable Ref<EntityStore> targetRef) {
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = targetRef.getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        return npc != null && GAME_DOOR_TRIGGER_ROLE.equals(npc.getRoleName());
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

    @Nonnull
    private static Transform nullableOrDefault(@Nullable Transform configured, @Nonnull Transform fallback) {
        return configured != null ? configured : fallback;
    }

    private static boolean isOnCooldown(@Nonnull UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_USE_BY_PLAYER.put(playerId, now);
        return last != null && now - last < USE_COOLDOWN_MS;
    }

    private static void healIfAdventurePlayer(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getGameMode() != GameMode.Adventure) {
            return;
        }

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        statMap.maximizeStatValue(DefaultEntityStatTypes.getHealth());
        store.putComponent(ref, EntityStatMap.getComponentType(), statMap);
    }

    @Nonnull
    private static List<UUID> collectRunStartPlayerIds(@Nonnull PlayerRef playerRef) {
        return List.of(playerRef.getUuid());
    }
}
