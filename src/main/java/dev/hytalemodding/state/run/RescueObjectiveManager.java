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
import dev.hytalemodding.map.RunNpcMarkerManager;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.state.NpcStateManager;
import dev.hytalemodding.npc.RunRescueRegistry;
import dev.hytalemodding.npc.runtime.NpcInteractionRouter;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class RescueObjectiveManager {
    private static final String FOLLOW_STATE = "Follow";
    private static final RescueObjectiveManager INSTANCE = new RescueObjectiveManager();

    private final ConcurrentHashMap<UUID, RescueRunState> objectivesByWorld = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> pendingRescueNpcKeys = new LinkedHashSet<>();
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
            this.objectivesByWorld.clear();
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!session.runWorldUuid().equals(worldId)) {
            this.objectivesByWorld.remove(worldId);
            return;
        }

        RescueRunState runState = this.objectivesByWorld.computeIfAbsent(worldId, ignored -> new RescueRunState(session.starterPlayerId()));
        ensureObjectiveNpcSelection(runState, session.templateWorldName());

        for (RescueObjective objective : runState.objectivesByNpc.values()) {
            if (objective.npcKey == null || objective.runRescueRoleName == null) {
                objective.state = RescueState.SAFE;
                continue;
            }

            if (objective.npcRef == null || !objective.npcRef.isValid()) {
                trySpawnRescueNpc(store, world, session, objective);
                continue;
            }

            NPCEntity npc = store.getComponent(objective.npcRef, NPCEntity.getComponentType());
            if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
                if (objective.state != RescueState.FAILED) {
                    objective.state = RescueState.FAILED;
                    sendRunWorldMessage(worldId, "Rescue NPC failed: " + objective.npcKey + ".");
                }
                continue;
            }

            String stateName = npc.getRole().getStateSupport().getStateName();
            boolean followingState = isFollowingStateName(objective, stateName);
            long now = System.currentTimeMillis();
            if (followingState && objective.state == RescueState.WAITING) {
                debug("tick following-transition npc=" + objective.npcKey
                        + " role=" + objective.runRescueRoleName
                        + " stateName=" + stateName
                        + " escortConfirmed=" + objective.escortConfirmed);
                objective.state = RescueState.FOLLOWING;
                objective.lastFollowingAtMs = now;
                sendRunWorldMessage(worldId, objective.npcKey + " is now following. Escort them to base.");
            } else if (!followingState && objective.state == RescueState.FOLLOWING) {
                if (now - objective.lastFollowingAtMs > 10_000L) {
                    debug("tick following-timeout npc=" + objective.npcKey
                            + " role=" + objective.runRescueRoleName
                            + " stateName=" + stateName
                            + " lastFollowingAtMs=" + objective.lastFollowingAtMs);
                    objective.state = RescueState.WAITING;
                }
            } else if (followingState) {
                debug("tick following-heartbeat npc=" + objective.npcKey
                        + " role=" + objective.runRescueRoleName
                        + " stateName=" + stateName);
                objective.lastFollowingAtMs = now;
            }
        }
    }

    @Nullable
    public RescueState getState(@Nonnull UUID worldUuid) {
        RescueRunState runState = this.objectivesByWorld.get(worldUuid);
        if (runState == null || runState.objectivesByNpc.isEmpty()) {
            return null;
        }
        boolean anyFollowing = false;
        boolean anyWaiting = false;
        boolean anyFailed = false;
        for (RescueObjective objective : runState.objectivesByNpc.values()) {
            if (objective.state == RescueState.FOLLOWING) {
                anyFollowing = true;
            } else if (objective.state == RescueState.WAITING) {
                anyWaiting = true;
            } else if (objective.state == RescueState.FAILED) {
                anyFailed = true;
            }
        }
        if (anyFollowing) {
            return RescueState.FOLLOWING;
        }
        if (anyWaiting) {
            return RescueState.WAITING;
        }
        if (anyFailed) {
            return RescueState.FAILED;
        }
        return RescueState.SAFE;
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
        RescueRunState runState = this.objectivesByWorld.get(worldUuid);
        if (runState == null) {
            return false;
        }
        for (RescueObjective objective : runState.objectivesByNpc.values()) {
            if (objective.npcRef != null && objective.npcRef.isValid()) {
                return true;
            }
        }
        return false;
    }

    public synchronized void resetRuntimeStatePreserveRescued() {
        this.objectivesByWorld.clear();
        this.pendingRescueNpcKeys.clear();
        this.pendingBaseSpawn = false;
        this.baseSpawnInProgress = false;
    }

    public synchronized void setNpcRescued(@Nonnull String npcKey, boolean rescued) {
        setNpcRescuedInternal(npcKey, rescued);
        if (rescued) {
            this.pendingRescueNpcKeys.remove(npcKey.trim().toLowerCase());
            this.pendingBaseSpawn = !this.pendingRescueNpcKeys.isEmpty();
            this.baseSpawnInProgress = false;
        }
    }

    @Nonnull
    public synchronized List<String> commitPendingRescueAsRescued() {
        if (this.pendingRescueNpcKeys.isEmpty()) {
            debug("commitPendingRescueAsRescued none pending");
            return List.of();
        }
        List<String> committed = List.copyOf(this.pendingRescueNpcKeys);
        for (String npcKey : committed) {
            setNpcRescuedInternal(npcKey, true);
        }
        this.pendingRescueNpcKeys.clear();
        this.pendingBaseSpawn = false;
        this.baseSpawnInProgress = false;
        debug("commitPendingRescueAsRescued committed=" + String.join(",", committed));
        return committed;
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

        PlayerRef playerRef = null;
        if (event.getPlayerRef() != null && event.getPlayerRef().isValid()) {
            playerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        }
        if (playerRef == null || playerRef.getWorldUuid() == null) {
            return;
        }
        NpcInteractionRouter.get().handleInteraction(playerRef, event.getTargetRef(), event.getActionType());
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
        RescueRunState runState = this.objectivesByWorld.get(playerRef.getWorldUuid());
        if (runState == null) {
            return;
        }

        Store<EntityStore> targetStore = targetRef.getStore();
        NPCEntity npc = targetStore.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRoleName() == null) {
            return;
        }

        RescueObjective objective = runState.objectivesByRole.get(npc.getRoleName());
        if (objective == null) {
            return;
        }

        if (objective.npcRef == null || !objective.npcRef.isValid() || objective.npcRef != targetRef) {
            objective.npcRef = targetRef;
        }
        if (objective.npcRef != targetRef) {
            return;
        }

        debugToPlayer(playerRef, "markFollowingFromNpcRef npc=" + objective.npcKey
                + " role=" + objective.runRescueRoleName
                + " interaction=" + interactionType
                + " targetValid=" + targetRef.isValid());
        boolean announce = objective.state != RescueState.FOLLOWING;
        objective.state = RescueState.FOLLOWING;
        objective.lastFollowingAtMs = System.currentTimeMillis();
        objective.escortConfirmed = true;

        // Stop working animation when NPC starts following
        if (npc != null && npc.getRole() != null && npc.getRole().getStateSupport() != null) {
            npc.onFlockSetState(targetRef, "Idle", null, targetStore);
        }

        if (announce) {
            playerRef.sendMessage(Message.raw(objective.npcKey + " follow started."));
        }
    }

    public boolean queueRescueForExtraction(@Nullable UUID runWorldId, @Nonnull UUID extractingPlayerId) {
        if (runWorldId == null || this.pendingBaseSpawn) {
            debug("queueRescueForExtraction rejected runWorldId=" + runWorldId + " pendingBaseSpawn=" + this.pendingBaseSpawn);
            return false;
        }

        RescueRunState runState = this.objectivesByWorld.get(runWorldId);
        if (runState == null || runState.objectivesByNpc.isEmpty()) {
            debug("queueRescueForExtraction no runState/objectives for world=" + runWorldId);
            return false;
        }

        ArrayList<String> queued = new ArrayList<>();
        for (RescueObjective objective : runState.objectivesByNpc.values()) {
            if (objective.npcKey == null || NpcProgressManager.get().isNpcRescued(objective.npcKey)) {
                debug("queueRescueForExtraction skip npc=" + (objective.npcKey == null ? "<null>" : objective.npcKey)
                        + " alreadyRescued=" + (objective.npcKey != null && NpcProgressManager.get().isNpcRescued(objective.npcKey)));
                continue;
            }
            boolean following = objective.escortConfirmed || objective.state == RescueState.FOLLOWING || isNpcFollowingNow(objective);
            debug("queueRescueForExtraction npc=" + objective.npcKey
                    + " state=" + objective.state
                    + " escortConfirmed=" + objective.escortConfirmed
                    + " followingNow=" + following);
            if (!following) {
                continue;
            }
            objective.state = RescueState.SAFE;
            queued.add(objective.npcKey);
        }

        if (queued.isEmpty()) {
            debug("queueRescueForExtraction queued empty for world=" + runWorldId);
            return false;
        }

        synchronized (this) {
            this.pendingRescueNpcKeys.clear();
            this.pendingRescueNpcKeys.addAll(queued);
            this.pendingBaseSpawn = true;
            this.baseSpawnInProgress = false;
        }
        this.objectivesByWorld.remove(runWorldId);
        debug("queueRescueForExtraction queued=" + String.join(",", queued) + " for player=" + extractingPlayerId);
        return true;
    }

    @Nonnull
    public CompletableFuture<List<QueuedRescueSpawnResult>> spawnQueuedRescueInBase(
            @Nonnull World destinationWorld,
            @Nonnull Transform destinationTransform
    ) {
        final List<String> pendingNpcs;
        synchronized (this) {
            if (!this.pendingBaseSpawn || this.baseSpawnInProgress || this.pendingRescueNpcKeys.isEmpty()) {
                debug("spawnQueuedRescueInBase short-circuit pendingBaseSpawn=" + this.pendingBaseSpawn
                        + " baseSpawnInProgress=" + this.baseSpawnInProgress
                        + " pendingCount=" + this.pendingRescueNpcKeys.size());
                return CompletableFuture.completedFuture(List.of(
                        new QueuedRescueSpawnResult(null, false, "No queued rescue.")
                ));
            }
            pendingNpcs = List.copyOf(this.pendingRescueNpcKeys);
            this.baseSpawnInProgress = true;
            this.pendingBaseSpawn = false;
            this.pendingRescueNpcKeys.clear();
        }

        CompletableFuture<List<QueuedRescueSpawnResult>> result = new CompletableFuture<>();
        destinationWorld.execute(() -> {
            ArrayList<QueuedRescueSpawnResult> results = new ArrayList<>();
            for (String pendingNpc : pendingNpcs) {
                boolean success = spawnBaseNpcNowInternal(destinationWorld, destinationTransform, pendingNpc);
                debug("spawnQueuedRescueInBase world=" + destinationWorld.getName()
                        + " npc=" + pendingNpc
                        + " success=" + success);
                results.add(new QueuedRescueSpawnResult(
                        pendingNpc,
                        success,
                        success ? null : "spawn returned false"
                ));
            }
            this.baseSpawnInProgress = false;
            result.complete(List.copyOf(results));
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
            RescueRunState runState = this.objectivesByWorld.computeIfAbsent(runWorldId, ignored -> new RescueRunState(session.starterPlayerId()));
            ensureObjectiveNpcSelection(runState, session.templateWorldName());
            for (RescueObjective objective : runState.objectivesByNpc.values()) {
                if (objective.npcRef != null && objective.npcRef.isValid()) {
                    continue;
                }
                trySpawnRescueNpc(store, runWorld, session, objective);
            }
        });
    }

    private void ensureObjectiveNpcSelection(@Nonnull RescueRunState runState, @Nonnull String templateWorldName) {
        for (String npcKey : RunRescueRegistry.get().getNpcKeysForTemplateWorld(templateWorldName)) {
            if (runState.objectivesByNpc.containsKey(npcKey)) {
                continue;
            }
            // Skip farmer if blacksmith doesn't have a workshop
            if ("farmer".equals(npcKey) && !BaseHousingManager.get().isWorkshopBuilt("blacksmith")) {
                continue;
            }
            NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
            if (archetype == null || archetype.runRescueRole == null || archetype.runRescueRole.isBlank()) {
                continue;
            }
            RescueObjective objective = new RescueObjective(runState.starterPlayerId, npcKey, archetype.runRescueRole, archetype.hubRole);
            runState.objectivesByNpc.put(npcKey, objective);
            runState.objectivesByRole.put(archetype.runRescueRole, objective);
        }
    }

    private boolean spawnBaseNpcNowInternal(@Nonnull World destinationWorld, @Nonnull Transform destinationTransform, @Nonnull String npcKey) {
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            debug("spawnBaseNpcNowInternal missing archetype/hubRole npc=" + npcKey);
            return false;
        }
        Transform spawnTransform = BaseHousingManager.get().getFixedHubRescueSpawn(npcKey);
        if (spawnTransform == null) {
            spawnTransform = destinationTransform;
        }
        Ref<EntityStore> existingBase = findExistingNpcRefByRole(destinationWorld.getEntityStore().getStore(), archetype.hubRole);
        if (existingBase != null && existingBase.isValid()) {
            debug("spawnBaseNpcNowInternal existing valid hub npc found role=" + archetype.hubRole + " npc=" + npcKey);
            return true;
        }

        ArrayList<Transform> attempts = new ArrayList<>();
        attempts.add(copyTransform(spawnTransform));
        attempts.add(offsetTransform(spawnTransform, 0.0, 1.0, 0.0));
        attempts.add(offsetTransform(spawnTransform, 0.0, 2.0, 0.0));
        if (spawnTransform != destinationTransform) {
            attempts.add(copyTransform(destinationTransform));
            attempts.add(offsetTransform(destinationTransform, 0.0, 1.0, 0.0));
        }

        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            Transform attempt = attempts.get(attemptIndex);
            boolean applyLegacyOffset = attemptIndex >= 3 && spawnTransform != destinationTransform;
            debug("spawnBaseNpcNowInternal attempt=" + (attemptIndex + 1)
                    + "/" + attempts.size()
                    + " role=" + archetype.hubRole
                    + " npc=" + npcKey
                    + " at=" + attempt.getPosition()
                    + " legacyOffset=" + applyLegacyOffset);
            boolean spawned = spawnNpcAt(destinationWorld, attempt, archetype.hubRole, true, applyLegacyOffset);
            if (spawned) {
                debug("spawnBaseNpcNowInternal success role=" + archetype.hubRole
                        + " npc=" + npcKey
                        + " at=" + attempt.getPosition()
                        + " attempt=" + (attemptIndex + 1));
                return true;
            }
        }

        debug("spawnBaseNpcNowInternal failed role=" + archetype.hubRole
                + " npc=" + npcKey
                + " fixedSpawn=" + spawnTransform.getPosition()
                + " destination=" + destinationTransform.getPosition());
        return false;
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
        if (roleIndex < 0) {
            debug("spawnNpcAt invalid roleIndex role=" + roleName);
            return false;
        }
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            debug("spawnNpcAt role not spawnable role=" + roleName
                    + " roleInfo=" + (roleInfo != null)
                    + " builderSpawnable=" + (roleInfo != null && roleInfo.getBuilder().isSpawnable()));
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
        if (npcPair == null) {
            debug("spawnNpcAt spawnEntity returned null role=" + roleName + " pos=" + spawnPos);
            return false;
        }
        if (npcPair.first() == null) {
            debug("spawnNpcAt spawnEntity returned null ref role=" + roleName + " pos=" + spawnPos);
            return false;
        }
        if (!npcPair.first().isValid()) {
            debug("spawnNpcAt spawnEntity returned invalid ref role=" + roleName + " pos=" + spawnPos);
            return false;
        }
        return true;
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

        final Vector3d finalSpawnPos = spawnPos;
        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) -> {
            entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);
            // Add map marker for blacksmith and farmer
            if ("blacksmith".equals(objective.npcKey) || "farmer".equals(objective.npcKey)) {
                RunNpcMarkerManager.addMarkerForNpc(world, objective.npcKey, finalSpawnPos);
            }
        };

        synchronized (objective) {
            if (objective.spawning || objective.spawnedOnce) {
                return;
            }
            objective.spawning = true;
        }
        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(store, roleIndex, spawnPos, spawnRot, null, postSpawn);
        if (npcPair == null || npcPair.first() == null || !npcPair.first().isValid()) {
            debug("trySpawnRescueNpc failed spawn npc=" + objective.npcKey
                    + " role=" + objective.runRescueRoleName
                    + " pos=" + spawnPos);
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
        NpcStateManager.get().setRescued(npcKey, rescued);
    }

    private static void debug(@Nonnull String text) {
    }

    private static void debugToPlayer(@Nonnull PlayerRef playerRef, @Nonnull String text) {
    }

    @Nonnull
    private static Transform copyTransform(@Nonnull Transform source) {
        return new Transform(new Vector3d(source.getPosition()), new Vector3f(source.getRotation()));
    }

    @Nonnull
    private static Transform offsetTransform(@Nonnull Transform source, double dx, double dy, double dz) {
        Vector3d pos = new Vector3d(source.getPosition());
        pos.setX(pos.getX() + dx);
        pos.setY(pos.getY() + dy);
        pos.setZ(pos.getZ() + dz);
        return new Transform(pos, new Vector3f(source.getRotation()));
    }

    private static final class RescueRunState {
        @Nonnull
        private final UUID starterPlayerId;
        @Nonnull
        private final ConcurrentHashMap<String, RescueObjective> objectivesByNpc = new ConcurrentHashMap<>();
        @Nonnull
        private final ConcurrentHashMap<String, RescueObjective> objectivesByRole = new ConcurrentHashMap<>();

        private RescueRunState(@Nonnull UUID starterPlayerId) {
            this.starterPlayerId = starterPlayerId;
        }
    }

    private static final class RescueObjective {
        @Nonnull
        private final UUID starterPlayerId;
        @Nullable
        private Ref<EntityStore> npcRef;
        @Nonnull
        private final String npcKey;
        @Nonnull
        private final String runRescueRoleName;
        @Nullable
        private final String hubRoleName;
        @Nonnull
        private RescueState state = RescueState.WAITING;
        private boolean spawning;
        private boolean spawnedOnce;
        private boolean escortConfirmed;
        private long lastFollowingAtMs;

        private RescueObjective(
                @Nonnull UUID starterPlayerId,
                @Nonnull String npcKey,
                @Nonnull String runRescueRoleName,
                @Nullable String hubRoleName
        ) {
            this.starterPlayerId = starterPlayerId;
            this.npcKey = npcKey;
            this.runRescueRoleName = runRescueRoleName;
            this.hubRoleName = hubRoleName;
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
