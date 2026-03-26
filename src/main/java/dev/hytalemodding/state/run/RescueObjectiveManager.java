package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.RunRescueRegistry;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class RescueObjectiveManager {
    private static final String FOLLOW_STATE = "Follow";
    private static final RescueObjectiveManager INSTANCE = new RescueObjectiveManager();

    private final ConcurrentHashMap<UUID, RescueObjective> objectives = new ConcurrentHashMap<>();
    @Nullable
    private volatile String pendingRescueNpcKey;
    private volatile boolean pendingBaseSpawn;
    private volatile boolean baseSpawnInProgress;

    private RescueObjectiveManager() {
    }

    @Nonnull
    public static RescueObjectiveManager get() {
        return INSTANCE;
    }

    public void tick(@Nonnull Store<EntityStore> store) {
        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        if (session == null || session.runWorldUuid() == null) {
            this.objectives.clear();
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!session.runWorldUuid().equals(worldId)) {
            this.objectives.remove(worldId);
            return;
        }

        RescueObjective objective = this.objectives.computeIfAbsent(worldId, ignored -> new RescueObjective(session.starterPlayerId()));
        ensureObjectiveNpcSelection(objective, session.templateWorldName());
        if (objective.npcKey == null || objective.runRescueRoleName == null) {
            objective.state = RescueState.SAFE;
            return;
        }

        if (objective.npcRef == null || !objective.npcRef.isValid()) {
            trySpawnRescueNpc(store, world, session, objective);
            return;
        }

        NPCEntity npc = store.getComponent(objective.npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            if (objective.state != RescueState.FAILED) {
                objective.state = RescueState.FAILED;
                sendRunWorldMessage(worldId, "Rescue NPC failed.");
            }
            return;
        }

        String stateName = npc.getRole().getStateSupport().getStateName();
        boolean followingState = isFollowingStateName(objective, stateName);

        long now = System.currentTimeMillis();
        if (followingState && objective.state == RescueState.WAITING) {
            objective.state = RescueState.FOLLOWING;
            objective.lastFollowingAtMs = now;
            sendRunWorldMessage(worldId, "Rescue NPC is now following. Escort them to base.");
        } else if (!followingState && objective.state == RescueState.FOLLOWING) {
            if (now - objective.lastFollowingAtMs > 10_000L) {
                objective.state = RescueState.WAITING;
            }
        } else if (followingState) {
            objective.lastFollowingAtMs = now;
        }
    }

    @Nullable
    public RescueState getState(@Nonnull UUID worldUuid) {
        RescueObjective objective = this.objectives.get(worldUuid);
        return objective == null ? null : objective.state;
    }

    public boolean isNpcRescued(@Nonnull String npcKey) {
        return NpcProgressManager.get().isNpcRescued(npcKey);
    }

    public boolean isPendingBaseSpawn() {
        return this.pendingBaseSpawn;
    }

    public boolean isBaseSpawnInProgress() {
        return this.baseSpawnInProgress;
    }

    public boolean hasObjectiveNpc(@Nonnull UUID worldUuid) {
        RescueObjective objective = this.objectives.get(worldUuid);
        return objective != null && objective.npcRef != null && objective.npcRef.isValid();
    }

    public synchronized void resetRuntimeStatePreserveRescued() {
        this.objectives.clear();
        this.pendingRescueNpcKey = null;
        this.pendingBaseSpawn = false;
        this.baseSpawnInProgress = false;
    }

    public synchronized void setNpcRescued(@Nonnull String npcKey, boolean rescued) {
        setNpcRescuedInternal(npcKey, rescued);
        if (rescued) {
            this.pendingRescueNpcKey = null;
            this.pendingBaseSpawn = false;
            this.baseSpawnInProgress = false;
        }
    }

    public boolean spawnBaseNpcNow(@Nonnull String npcKey, @Nonnull World destinationWorld, @Nonnull Transform destinationTransform) {
        if (isNpcRescued(npcKey)) {
            return false;
        }
        boolean success = spawnBaseNpcNowInternal(destinationWorld, destinationTransform, npcKey);
        if (success) {
            setNpcRescued(npcKey, true);
        }
        return success;
    }

    public void onPlayerInteract(@Nonnull PlayerInteractEvent event) {
        if (event.getTargetRef() == null || !event.getTargetRef().isValid()) {
            return;
        }

        Ref<EntityStore> targetRef = event.getTargetRef();
        Store<EntityStore> targetStore = targetRef.getStore();
        NPCEntity npc = targetStore.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRoleName() == null) {
            return;
        }
        String roleName = npc.getRoleName();

        PlayerRef playerRef = null;
        if (event.getPlayerRef() != null && event.getPlayerRef().isValid()) {
            playerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        }
        if (playerRef == null && event.getPlayer() != null) {
            playerRef = Universe.get().getPlayer(event.getPlayer().getUuid());
        }
        if (playerRef == null || playerRef.getWorldUuid() == null) {
            return;
        }

        String hubNpcKey = NpcDefinitionRegistry.get().getNpcKeyByHubRole(roleName);
        if (hubNpcKey != null && !hubNpcKey.isBlank()) {
            InteractionType interactionType = event.getActionType();
            if (interactionType == InteractionType.Use
                    || interactionType == InteractionType.Primary
                    || interactionType == InteractionType.Secondary) {
                if (BaseHousingManager.get().canOpenDialogue(hubNpcKey)) {
                    NpcDialogueManager.get().openDialogue(playerRef, targetRef);
                } else {
                    HubNpcManager.HubNpcState state = BaseHousingManager.get().getNpcState(hubNpcKey);
                    playerRef.sendMessage(Message.raw(hubNpcKey + " is currently " + state.name() + "."));
                }
            }
            return;
        }

        String runNpcKey = NpcDefinitionRegistry.get().getNpcKeyByRunRescueRole(roleName);
        if (runNpcKey == null || runNpcKey.isBlank()) {
            return;
        }
        markFollowingFromNpcRef(playerRef, targetRef, event.getActionType());
    }

    public void markFollowingFromNpcRef(
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> targetRef,
            @Nullable InteractionType interactionType
    ) {
        if (interactionType != null
                && interactionType != InteractionType.Use
                && interactionType != InteractionType.Primary
                && interactionType != InteractionType.Secondary) {
            return;
        }

        if (playerRef.getWorldUuid() == null) {
            return;
        }
        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        if (session == null || session.runWorldUuid() == null || !session.runWorldUuid().equals(playerRef.getWorldUuid())) {
            return;
        }
        RescueObjective objective = this.objectives.get(playerRef.getWorldUuid());
        if (objective == null || objective.runRescueRoleName == null) {
            return;
        }

        Store<EntityStore> targetStore = targetRef.getStore();
        NPCEntity npc = targetStore.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || !objective.runRescueRoleName.equals(npc.getRoleName())) {
            return;
        }

        if (objective.npcRef == null || !objective.npcRef.isValid() || objective.npcRef != targetRef) {
            objective.npcRef = targetRef;
        }
        if (objective.npcRef != targetRef) {
            return;
        }

        boolean announce = objective.state != RescueState.FOLLOWING;
        objective.state = RescueState.FOLLOWING;
        objective.lastFollowingAtMs = System.currentTimeMillis();
        objective.escortConfirmed = true;
        if (announce) {
            playerRef.sendMessage(Message.raw("Rescue NPC follow started."));
        }
    }

    public boolean queueRescueForExtraction(@Nullable UUID runWorldId, @Nonnull UUID extractingPlayerId) {
        if (runWorldId == null || this.pendingBaseSpawn) {
            return false;
        }

        RescueObjective objective = this.objectives.get(runWorldId);
        if (objective == null || objective.npcKey == null) {
            return false;
        }
        if (NpcProgressManager.get().isNpcRescued(objective.npcKey)) {
            return false;
        }
        boolean following = objective.escortConfirmed || objective.state == RescueState.FOLLOWING || isNpcFollowingNow(objective);
        if (!following) {
            return false;
        }

        objective.state = RescueState.SAFE;
        this.objectives.remove(runWorldId);
        this.pendingRescueNpcKey = objective.npcKey;
        this.pendingBaseSpawn = true;
        return true;
    }

    @Nonnull
    public CompletableFuture<QueuedRescueSpawnResult> spawnQueuedRescueInBase(
            @Nonnull World destinationWorld,
            @Nonnull Transform destinationTransform
    ) {
        final String pendingNpc;
        synchronized (this) {
            if (!this.pendingBaseSpawn || this.baseSpawnInProgress || this.pendingRescueNpcKey == null) {
                return CompletableFuture.completedFuture(
                        new QueuedRescueSpawnResult(null, false, "No queued rescue.")
                );
            }
            pendingNpc = this.pendingRescueNpcKey;
            this.baseSpawnInProgress = true;
            this.pendingBaseSpawn = false;
            this.pendingRescueNpcKey = null;
        }

        CompletableFuture<QueuedRescueSpawnResult> result = new CompletableFuture<>();
        destinationWorld.execute(() -> {
            boolean success = spawnBaseNpcNowInternal(destinationWorld, destinationTransform, pendingNpc);
            this.baseSpawnInProgress = false;
            result.complete(new QueuedRescueSpawnResult(
                    pendingNpc,
                    success,
                    success ? null : "spawn returned false"
            ));
        });
        return result;
    }

    public void spawnRescueOnRunStart(@Nonnull World runWorld, @Nonnull GameSessionManager.ActiveSessionSnapshot session) {
        if (session.runWorldUuid() == null) {
            return;
        }
        UUID runWorldId = runWorld.getWorldConfig().getUuid();
        if (!session.runWorldUuid().equals(runWorldId)) {
            return;
        }

        runWorld.execute(() -> {
            Store<EntityStore> store = runWorld.getEntityStore().getStore();
            RescueObjective objective = this.objectives.computeIfAbsent(runWorldId, ignored -> new RescueObjective(session.starterPlayerId()));
            ensureObjectiveNpcSelection(objective, session.templateWorldName());
            if (objective.npcRef != null && objective.npcRef.isValid()) {
                return;
            }
            trySpawnRescueNpc(store, runWorld, session, objective);
        });
    }

    private void ensureObjectiveNpcSelection(@Nonnull RescueObjective objective, @Nonnull String templateWorldName) {
        if (objective.npcKey != null && objective.runRescueRoleName != null) {
            return;
        }
        String npcKey = RunRescueRegistry.get().chooseNpcForTemplateWorld(templateWorldName);
        if (npcKey == null || npcKey.isBlank()) {
            return;
        }
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.runRescueRole == null || archetype.runRescueRole.isBlank()) {
            return;
        }
        objective.npcKey = npcKey;
        objective.runRescueRoleName = archetype.runRescueRole;
        objective.hubRoleName = archetype.hubRole;
    }

    private boolean spawnBaseNpcNowInternal(@Nonnull World destinationWorld, @Nonnull Transform destinationTransform, @Nonnull String npcKey) {
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            return false;
        }
        Transform spawnTransform = BaseHousingManager.get().getFixedHubRescueSpawn(npcKey);
        if (spawnTransform == null) {
            spawnTransform = destinationTransform;
        }
        Ref<EntityStore> existingBase = findExistingNpcRefByRole(destinationWorld.getEntityStore().getStore(), archetype.hubRole);
        if (existingBase != null && existingBase.isValid()) {
            return true;
        }
        return spawnNpcAt(destinationWorld, spawnTransform, archetype.hubRole, true, spawnTransform == destinationTransform);
    }

    private static boolean spawnNpcAt(
            @Nonnull World destinationWorld,
            @Nonnull Transform destinationTransform,
            @Nonnull String roleName,
            boolean interactable,
            boolean applyLegacyOffset
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(roleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return false;
        }

        Vector3d spawnPos = new Vector3d(destinationTransform.getPosition());
        if (applyLegacyOffset) {
            spawnPos.setX(spawnPos.getX() + 1.0);
        }
        Vector3f spawnRot = new Vector3f(destinationTransform.getRotation());
        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = null;
        if (interactable) {
            postSpawn = (npcEntity, npcRef, entityStore) ->
                    entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);
        }

        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                destinationWorld.getEntityStore().getStore(),
                roleIndex,
                spawnPos,
                spawnRot,
                null,
                postSpawn
        );
        return npcPair != null && npcPair.first() != null && npcPair.first().isValid();
    }

    private void trySpawnRescueNpc(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull GameSessionManager.ActiveSessionSnapshot session,
            @Nonnull RescueObjective objective
    ) {
        if (this.pendingBaseSpawn) {
            objective.state = RescueState.SAFE;
            return;
        }
        ensureObjectiveNpcSelection(objective, session.templateWorldName());
        if (objective.npcKey == null || objective.runRescueRoleName == null) {
            objective.state = RescueState.SAFE;
            return;
        }
        if (NpcProgressManager.get().isNpcRescued(objective.npcKey)) {
            objective.state = RescueState.SAFE;
            return;
        }

        Ref<EntityStore> existingObjective = findExistingNpcRefByRole(store, objective.runRescueRoleName);
        if (existingObjective != null && existingObjective.isValid()) {
            objective.npcRef = existingObjective;
            objective.state = RescueState.WAITING;
            objective.spawnedOnce = true;
            return;
        }

        PlayerRef starter = Universe.get().getPlayer(session.starterPlayerId());
        if (starter == null || starter.getWorldUuid() == null || !starter.getWorldUuid().equals(session.runWorldUuid())) {
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(objective.runRescueRoleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            if (objective.state != RescueState.FAILED) {
                objective.state = RescueState.FAILED;
                sendRunWorldMessage(session.runWorldUuid(), "Rescue role unavailable: " + objective.runRescueRoleName);
            }
            return;
        }

        Transform starterTransform = starter.getTransform();
        Transform configuredSpawn = RunRescueRegistry.get().getConfiguredSpawn(objective.npcKey, session.templateWorldName());
        Vector3d spawnPos;
        Vector3f spawnRot;
        if (configuredSpawn != null) {
            spawnPos = new Vector3d(configuredSpawn.getPosition());
            spawnRot = new Vector3f(configuredSpawn.getRotation());
        } else {
            spawnPos = new Vector3d(starterTransform.getPosition());
            spawnPos.setX(spawnPos.getX() + 6.0);
            spawnRot = new Vector3f(starterTransform.getRotation());
        }

        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) ->
                entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);

        synchronized (objective) {
            if (objective.spawning || objective.spawnedOnce) {
                return;
            }
            objective.spawning = true;
        }
        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(store, roleIndex, spawnPos, spawnRot, null, postSpawn);
        if (npcPair == null || npcPair.first() == null || !npcPair.first().isValid()) {
            synchronized (objective) {
                objective.spawning = false;
            }
            return;
        }

        synchronized (objective) {
            objective.npcRef = npcPair.first();
            objective.state = RescueState.WAITING;
            objective.spawnedOnce = true;
            objective.spawning = false;
        }
        sendRunWorldMessage(session.runWorldUuid(), "Rescue NPC spawned (" + objective.npcKey + ").");
    }

    private boolean isNpcFollowingNow(@Nonnull RescueObjective objective) {
        Ref<EntityStore> npcRef = objective.npcRef;
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        Store<EntityStore> npcStore = npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return false;
        }
        String stateName = npc.getRole().getStateSupport().getStateName();
        return isFollowingStateName(objective, stateName);
    }

    @Nullable
    private static Ref<EntityStore> findExistingNpcRefByRole(@Nonnull Store<EntityStore> store, @Nonnull String roleName) {
        final Ref<EntityStore>[] found = new Ref[]{null};
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            if (found[0] != null) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) {
                    continue;
                }
                if (roleName.equals(npc.getRoleName())) {
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    if (ref != null && ref.isValid()) {
                        found[0] = ref;
                        return;
                    }
                }
            }
        });
        return found[0];
    }

    private static boolean isFollowingStateName(@Nonnull RescueObjective objective, @Nullable String stateName) {
        if (stateName == null) {
            return false;
        }
        if (FOLLOW_STATE.equalsIgnoreCase(stateName) || stateName.startsWith("$Interaction")) {
            return true;
        }
        if (objective.npcKey == null || objective.npcKey.isBlank()) {
            return false;
        }
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(objective.npcKey);
        if (archetype == null || archetype.followStateAliases.isEmpty()) {
            return false;
        }
        String normalized = stateName.trim().toLowerCase();
        for (String alias : archetype.followStateAliases) {
            if (normalized.equals(alias)) {
                return true;
            }
        }
        return false;
    }

    private static void sendRunWorldMessage(@Nonnull UUID runWorldId, @Nonnull String text) {
        Message message = Message.raw(text);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid != null && worldUuid.equals(runWorldId)) {
                playerRef.sendMessage(message);
            }
        }
    }

    private static void setNpcRescuedInternal(@Nonnull String npcKey, boolean rescued) {
        NpcProgressManager.get().setNpcRescued(npcKey, rescued);
        GameFlowConfigManager.get().setNpcRescued(npcKey, rescued);
    }

    private static final class RescueObjective {
        @Nonnull
        private final UUID starterPlayerId;
        @Nullable
        private Ref<EntityStore> npcRef;
        @Nullable
        private String npcKey;
        @Nullable
        private String runRescueRoleName;
        @Nullable
        private String hubRoleName;
        @Nonnull
        private RescueState state = RescueState.WAITING;
        private boolean spawning;
        private boolean spawnedOnce;
        private boolean escortConfirmed;
        private long lastFollowingAtMs;

        private RescueObjective(@Nonnull UUID starterPlayerId) {
            this.starterPlayerId = starterPlayerId;
        }
    }

    public enum RescueState {
        WAITING,
        FOLLOWING,
        SAFE,
        FAILED
    }

    public record QueuedRescueSpawnResult(
            @Nullable String npcKey,
            boolean spawned,
            @Nullable String reason
    ) {
    }
}
