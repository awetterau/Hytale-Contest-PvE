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
    private static final String BLACKSMITH_KEY = "blacksmith";
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
        boolean followingState = isFollowingStateName(stateName);

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

    public boolean isBlacksmithRescued() {
        return NpcProgressManager.get().isNpcRescued(BLACKSMITH_KEY);
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

    public synchronized void setBlacksmithRescued(boolean rescued) {
        setNpcRescued(BLACKSMITH_KEY, rescued);
        if (rescued) {
            this.pendingRescueNpcKey = null;
            this.pendingBaseSpawn = false;
            this.baseSpawnInProgress = false;
        }
    }

    public synchronized void resetRuntimeStatePreserveRescued() {
        this.objectives.clear();
        this.pendingRescueNpcKey = null;
        this.pendingBaseSpawn = false;
        this.baseSpawnInProgress = false;
    }

    public synchronized void resetBlacksmithProgress() {
        this.objectives.clear();
        this.pendingRescueNpcKey = null;
        this.pendingBaseSpawn = false;
        this.baseSpawnInProgress = false;
        setBlacksmithRescued(false);
    }

    public boolean spawnBaseBlacksmithNow(@Nonnull World destinationWorld, @Nonnull Transform destinationTransform) {
        if (isBlacksmithRescued()) {
            return false;
        }
        boolean success = spawnBaseNpcNow(destinationWorld, destinationTransform, BLACKSMITH_KEY);
        if (success) {
            setBlacksmithRescued(true);
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
                if (BaseHousingManager.get().isNpcWorking(hubNpcKey)) {
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
        if (announce) {
            playerRef.sendMessage(Message.raw("Rescue NPC follow started."));
            System.out.println("[RescueDebug] follow signal from interaction player=" + playerRef.getUuid());
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
        boolean following = objective.state == RescueState.FOLLOWING || isNpcFollowingNow(objective);
        if (!following) {
            System.out.println("[RescueDebug] queue rejected: npc is not actively following");
            return false;
        }

        objective.state = RescueState.SAFE;
        this.objectives.remove(runWorldId);
        this.pendingRescueNpcKey = objective.npcKey;
        this.pendingBaseSpawn = true;
        System.out.println("[RescueDebug] queued rescue transfer npc=" + objective.npcKey + " player=" + extractingPlayerId + " world=" + runWorldId);
        return true;
    }

    @Nonnull
    public CompletableFuture<Boolean> spawnQueuedRescueInBase(
            @Nonnull World destinationWorld,
            @Nonnull Transform destinationTransform
    ) {
        final String pendingNpc;
        synchronized (this) {
            if (!this.pendingBaseSpawn || this.baseSpawnInProgress || this.pendingRescueNpcKey == null) {
                return CompletableFuture.completedFuture(false);
            }
            pendingNpc = this.pendingRescueNpcKey;
            this.baseSpawnInProgress = true;
            this.pendingBaseSpawn = false;
            this.pendingRescueNpcKey = null;
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        destinationWorld.execute(() -> {
            boolean success = spawnBaseNpcNow(destinationWorld, destinationTransform, pendingNpc);
            if (success) {
                setNpcRescued(pendingNpc, true);
                System.out.println("[RescueDebug] base spawn success npc=" + pendingNpc + " world=" + destinationWorld.getName());
            } else {
                System.out.println("[RescueDebug] base spawn failed npc=" + pendingNpc + " world=" + destinationWorld.getName());
            }
            this.baseSpawnInProgress = false;
            result.complete(success);
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

    private boolean spawnBaseNpcNow(@Nonnull World destinationWorld, @Nonnull Transform destinationTransform, @Nonnull String npcKey) {
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            return false;
        }
        Ref<EntityStore> existingBase = findExistingNpcRefByRole(destinationWorld.getEntityStore().getStore(), archetype.hubRole);
        if (existingBase != null && existingBase.isValid()) {
            return true;
        }
        return spawnNpcAt(destinationWorld, destinationTransform, archetype.hubRole, true);
    }

    private static boolean spawnNpcAt(
            @Nonnull World destinationWorld,
            @Nonnull Transform destinationTransform,
            @Nonnull String roleName,
            boolean interactable
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(roleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return false;
        }

        Vector3d spawnPos = new Vector3d(destinationTransform.getPosition());
        spawnPos.setX(spawnPos.getX() + 1.0);
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
        System.out.println("[RescueDebug] spawned rescue npc key=" + objective.npcKey + " role=" + objective.runRescueRoleName + " at " + spawnPos);
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
        return isFollowingStateName(stateName);
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

    private static boolean isFollowingStateName(@Nullable String stateName) {
        if (stateName == null) {
            return false;
        }
        return FOLLOW_STATE.equalsIgnoreCase(stateName) || stateName.startsWith("$Interaction");
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

    private static void setNpcRescued(@Nonnull String npcKey, boolean rescued) {
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
}
