package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3f;
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
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.state.party.PartyManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.SpawnPointZoneConfigManager;
import dev.hytalemodding.state.transition.RunHubTransferService;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.ui.dev.DoorRunZoneSelectPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
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
        return executeDoorRunFlow(playerRef);
    }

    public static boolean executeDoorRunFlow(@Nullable PlayerRef playerRef) {
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
        sendStatusMessage(playerRef, "[DoorDebug] Active run exists but you are not in run world.");
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
        PartyManager partyManager = PartyManager.get();
        if (!partyManager.isLeaderOrSolo(playerRef.getUuid())) {
            sendStatusMessage(playerRef, "Only team leaders can start a run.");
            return;
        }
        List<UUID> participantIds = partyManager.getRunParticipantIds(playerRef.getUuid());
        if (participantIds.isEmpty()) {
            sendStatusMessage(playerRef, "Only team leaders can start a run.");
            return;
        }

        GameFlowConfigManager config = GameFlowConfigManager.get();
        String hubWorldName = config.getHubWorldName();
        if (!isHubWorld(templateWorld)) {
            sendStatusMessage(playerRef, "Use the game door from hub world '" + hubWorldName + "'.");
            return;
        }

        String templateWorldName = config.getTemplateWorldName();
        World runTemplateWorld = Universe.get().getWorld(templateWorldName);
        if (runTemplateWorld == null) {
            sendStatusMessage(playerRef, "Run template world not loaded: " + templateWorldName);
            return;
        }

        boolean hasBaseSpawn = config.hasBaseSpawn();
        if (!hasBaseSpawn) {
            sendStatusMessage(playerRef, "Setup missing: /setbasespawn");
            return;
        }

        SpawnPointZoneManager.refreshForPlayer(playerRef);
        Integer selectedZone = DoorRunZoneSelectionManager.ensureSelectedZoneOrDefault(playerRef.getUuid());
        if (selectedZone == null) {
            sendStatusMessage(playerRef, "No spawn zones with registered SpawnPoint_Block entries are available.");
            return;
        }
        SpawnPointZoneManager.SpawnSelectionResult spawnSelection =
                SpawnPointZoneManager.reserveRandomSpawnForPlayer(runTemplateWorld, selectedZone.intValue(), playerRef.getUuid(), playerRef.getTransform());
        if (spawnSelection == null) {
            sendStatusMessage(playerRef, "Selected door zone " + SpawnPointZoneManager.getFormattedZoneLabel(selectedZone.intValue()) + " has no registered SpawnPoint_Block entries.");
            return;
        }
        LinkedHashMap<UUID, Transform> spawnByPlayer = planTeamSpawns(
                runTemplateWorld,
                selectedZone.intValue(),
                spawnSelection.locationIndex(),
                participantIds,
                playerRef.getTransform()
        );
        if (spawnByPlayer.size() != participantIds.size()) {
            SpawnPointZoneManager.releaseReservedSpawn(playerRef.getUuid());
            sendStatusMessage(playerRef, "Cannot start run: one or more team members are unavailable.");
            return;
        }

        Transform baseSpawn = config.getBaseSpawn();
        List<UUID> lockedPlayerIds = collectRunStartPlayerIds(playerRef, participantIds);
        RunStartMovementLockManager.get().unlockPlayers(lockedPlayerIds);
        for (UUID playerId : participantIds) {
            PlayerRef memberRef = Universe.get().getPlayer(playerId);
            if (memberRef != null) {
                RunStartMovementLockManager.get().lockPlayerForIntro(memberRef);
            }
        }

        GameSessionManager.get().startSession(
                playerRef,
                runTemplateWorld,
                spawnSelection.transform(),
                baseSpawn,
                participantIds,
                spawnByPlayer
        ).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                for (UUID participantId : participantIds) {
                    SpawnPointZoneManager.releaseReservedSpawn(participantId);
                }
                RunStartMovementLockManager.get().unlockPlayers(lockedPlayerIds);
                sendStatusMessage(playerRef, "Failed to start run: " + reason);
                return;
            }
            sendStatusMessage(
                    playerRef,
                    "Loading run from " + SpawnPointZoneManager.getFormattedLocationLabel(selectedZone.intValue(), spawnSelection.locationIndex())
                            + " for " + participantIds.size() + " player(s). The timer will start when gameplay is ready."
            );
        });
    }

    private static void tryExtractFromDoor(
            @Nonnull PlayerRef playerRef,
            @Nonnull GameSessionManager.ActiveSessionSnapshot session
    ) {
        completeExtraction(playerRef, session).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                sendStatusMessage(playerRef, "Failed to extract: " + reason);
                return;
            }
            if (result == null || !result.success()) {
                playerRef.sendMessage(Message.raw("Failed to extract: " + (result == null ? "unknown error" : result.message())));
            }
        });
    }

    @Nonnull
    public static java.util.concurrent.CompletableFuture<GameSessionManager.EndSessionResult> completeActiveRunExtraction(@Nonnull PlayerRef playerRef) {
        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        if (session == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new GameSessionManager.EndSessionResult(false, null, null, "No active run.")
            );
        }
        return completeExtraction(playerRef, session);
    }

    @Nonnull
    private static java.util.concurrent.CompletableFuture<GameSessionManager.EndSessionResult> completeExtraction(
            @Nonnull PlayerRef playerRef,
            @Nonnull GameSessionManager.ActiveSessionSnapshot session
    ) {
        GameFlowConfigManager config = GameFlowConfigManager.get();
        String hubWorldName = config.getHubWorldName();
        World hubWorld = Universe.get().getWorld(hubWorldName);
        if (hubWorld == null) {
            playerRef.sendMessage(Message.raw("Hub world is unavailable: " + hubWorldName));
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new GameSessionManager.EndSessionResult(false, session.runWorldName(), session.templateWorldName(), "Hub world unavailable.")
            );
        }

        Transform baseSpawn = nullableOrDefault(config.getBaseSpawn(), playerRef.getTransform());
        boolean queuedRescue = RunHubTransferService.get().queueRescueForExtraction(session.runWorldUuid(), playerRef.getUuid());
        boolean queuedAnimal = FarmerAnimalRescueManager.get().queueAnimalForExtraction(session.runWorldUuid());
        if (!queuedRescue) {
            playerRef.sendMessage(Message.raw("Rescue transfer not queued (already rescued or objective not ready)."));
        }
        if (!queuedAnimal && QuestProgressManager.get().getOrCreate("save_the_farm_animals").accepted) {
            playerRef.sendMessage(Message.raw("Animal rescue not queued (the missing animal is not following you yet)."));
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new GameSessionManager.EndSessionResult(false, session.runWorldName(), session.templateWorldName(), "Player reference unavailable.")
            );
        }
        Store<EntityStore> playerStore = playerEntityRef.getStore();
        if (playerStore == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new GameSessionManager.EndSessionResult(false, session.runWorldName(), session.templateWorldName(), "Player store unavailable.")
            );
        }

        Teleport teleport = Teleport.createForPlayer(hubWorld, baseSpawn);
        playerStore.addComponent(playerEntityRef, Teleport.getComponentType(), teleport);

        GameSessionManager.get().markPlayerCategory(playerRef.getUuid(), GameSessionManager.PlayerRunCategory.EXTRACTED);
        SpawnPointZoneManager.releaseReservedSpawn(playerRef.getUuid());
        healIfAdventurePlayer(playerRef);
        sendStatusMessage(playerRef, "Extraction complete.");
        QuestProgressManager.get().incrementSuccessfulExtraction(playerRef);
        if (queuedRescue) {
            RescueObjectiveManager.get().commitPendingRescueAsRescued();
            hubWorld.execute(() -> BaseHousingManager.get().ensureAssignmentsInWorld(hubWorld));
        }
        if (queuedAnimal) {
            FarmerAnimalRescueManager.get().commitPendingAnimalsAsRescued();
            hubWorld.execute(() -> FarmerAnimalRescueManager.get().ensureHubAnimalsInWorld(hubWorld));
        }

        return java.util.concurrent.CompletableFuture.completedFuture(
                new GameSessionManager.EndSessionResult(true, session.runWorldName(), session.templateWorldName(), "Player extracted.")
        );
    }

    public static boolean isHubWorld(@Nullable World world) {
        return world != null && isHubWorld(world.getName());
    }

    private static void sendStatusMessage(@Nonnull PlayerRef playerRef, @Nonnull String text) {
        if (!GameFlowConfigManager.get().isStatusMessagesEnabled()) {
            return;
        }
        playerRef.sendMessage(Message.raw(text));
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
    private static List<UUID> collectRunStartPlayerIds(@Nonnull PlayerRef playerRef, @Nonnull List<UUID> participantIds) {
        if (participantIds.isEmpty()) {
            return List.of(playerRef.getUuid());
        }
        return List.copyOf(participantIds);
    }

    @Nonnull
    private static LinkedHashMap<UUID, Transform> planTeamSpawns(
            @Nonnull World runTemplateWorld,
            int zoneIndex,
            int locationIndex,
            @Nonnull List<UUID> participantIds,
            @Nullable Transform referenceTransform
    ) {
        LinkedHashMap<UUID, Transform> result = new LinkedHashMap<>();
        SpawnPointZoneConfigManager.SpawnZoneState state = SpawnPointZoneConfigManager.load(runTemplateWorld.getName());
        LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> byLocation = state.zones().get(zoneIndex);
        if (byLocation == null || byLocation.isEmpty()) {
            return result;
        }
        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> pool = new ArrayList<>();
        for (SpawnPointZoneConfigManager.SpawnPointEntry entry : byLocation.getOrDefault(locationIndex, new ArrayList<>())) {
            if (entry.dimension().equalsIgnoreCase(runTemplateWorld.getName())) {
                pool.add(entry);
            }
        }
        if (pool.isEmpty()) {
            return result;
        }

        Vector3f rotation = referenceTransform == null
                ? new Vector3f(0.0f, 0.0f, 0.0f)
                : new Vector3f(referenceTransform.getRotation());
        for (int i = 0; i < participantIds.size(); i++) {
            UUID participantId = participantIds.get(i);
            PlayerRef memberRef = Universe.get().getPlayer(participantId);
            if (memberRef == null || !memberRef.isValid() || memberRef.getWorldUuid() == null) {
                return new LinkedHashMap<>();
            }
            SpawnPointZoneConfigManager.SpawnPointEntry assigned = pool.get(i % pool.size());
            Transform spawn = new Transform(
                    new com.hypixel.hytale.math.vector.Vector3d(
                            assigned.position().x + 0.5d,
                            assigned.position().y + 1.0d,
                            assigned.position().z + 0.5d
                    ),
                    new Vector3f(rotation)
            );
            result.put(participantId, spawn);
        }
        return result;
    }
}