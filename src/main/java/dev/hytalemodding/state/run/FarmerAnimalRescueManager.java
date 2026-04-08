package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.quest.QuestFlagManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.state.run.GameSessionManager.ActiveSessionSnapshot;
import dev.hytalemodding.state.run.GameSessionManager.RunPhase;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FarmerAnimalRescueManager {
    private static final String RESOURCE_PATH = "Common/NpcData/farmer-animal-rescue.properties";
    private static final String QUEST_ID = "save_the_farm_animals";
    private static final String FOLLOW_STATE = "Follow";
    private static final long ACTIVE_SPAWN_COOLDOWN_MS = 1500L;
    private static final double MARKER_HEIGHT_OFFSET = 0.75D;
    private static final double HUB_HOME_RESET_DISTANCE_SQUARED = 16.0D;
    private static final FarmerAnimalRescueManager INSTANCE = new FarmerAnimalRescueManager();

    private final LinkedHashMap<String, AnimalDefinition> definitions = new LinkedHashMap<>();
    private final ConcurrentHashMap<UUID, ActiveAnimalState> activeByWorld = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> pendingAnimalKeys = new LinkedHashSet<>();
    private volatile boolean loaded;

    private FarmerAnimalRescueManager() {
    }

    @Nonnull
    public static FarmerAnimalRescueManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadDefinitions();
        System.out.println("[FarmerAnimals] Loaded animal rescue definitions: " + this.definitions.size());
    }

    public void tick(@Nonnull Store<EntityStore> store) {
        ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        if (session == null || session.runWorldUuid() == null || !isSessionPhaseActive(session.phase())) {
            clearActiveWorldStates();
            return;
        }
        if (!isQuestAcceptedAndIncomplete()) {
            clearActiveWorldStates();
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!session.runWorldUuid().equals(worldId)) {
            ActiveAnimalState removed = this.activeByWorld.remove(worldId);
            if (removed != null) {
                removed.reset();
            }
            return;
        }

        ActiveAnimalState active = this.activeByWorld.computeIfAbsent(worldId, ignored -> new ActiveAnimalState());
        if (active.definition == null || isAnimalRescued(active.definition)) {
            active.reset();
            active.definition = chooseNextMissingAnimal(session.templateWorldName());
        }
        if (active.definition == null) {
            active.cachedMarker = null;
            return;
        }

        adoptOrPruneActiveObjectiveAnimal(store, active);
        if (!isUsableNpcRef(active.npcRef, active.definition.objectiveRole)) {
            active.npcRef = null;
            active.cachedMarker = null;
            trySpawnActiveAnimal(store, world, active);
            return;
        }

        NPCEntity npc = store.getComponent(active.npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            active.npcRef = null;
            active.cachedMarker = null;
            return;
        }
        cacheMarker(active, store);
        if (isFollowingState(npc.getRole().getStateSupport().getStateName())) {
            active.escortConfirmed = true;
            active.lastFollowingAtMs = System.currentTimeMillis();
        }
    }

    public boolean handleInteraction(
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> targetRef,
            @Nullable InteractionType interactionType
    ) {
        if (interactionType != null
                && interactionType != InteractionType.Use
                && interactionType != InteractionType.Primary
                && interactionType != InteractionType.Secondary) {
            return false;
        }
        ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        if (session == null || session.runWorldUuid() == null || playerRef.getWorldUuid() == null) {
            return false;
        }
        if (!session.runWorldUuid().equals(playerRef.getWorldUuid())) {
            return false;
        }
        ActiveAnimalState active = this.activeByWorld.get(session.runWorldUuid());
        if (active == null || active.definition == null) {
            return false;
        }

        NPCEntity npc = targetRef.getStore().getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRoleName() == null) {
            return false;
        }
        if (!active.definition.objectiveRole.equalsIgnoreCase(npc.getRoleName())) {
            return false;
        }
        active.npcRef = targetRef;
        active.escortConfirmed = true;
        active.lastFollowingAtMs = System.currentTimeMillis();
        playerRef.sendMessage(Message.raw("The " + active.definition.displayName.toLowerCase(Locale.ROOT) + " is following you."));
        return true;
    }

    public synchronized boolean queueAnimalForExtraction(@Nullable UUID runWorldId) {
        if (runWorldId == null) {
            return false;
        }
        if (!isQuestAcceptedAndIncomplete()) {
            return false;
        }
        ActiveAnimalState active = this.activeByWorld.get(runWorldId);
        if (active == null || active.definition == null || active.npcRef == null || !active.npcRef.isValid()) {
            return false;
        }
        if (!active.escortConfirmed && !isAnimalFollowing(active)) {
            return false;
        }
        this.pendingAnimalKeys.clear();
        this.pendingAnimalKeys.add(active.definition.key);
        this.activeByWorld.remove(runWorldId);
        return true;
    }

    @Nonnull
    public synchronized List<String> commitPendingAnimalsAsRescued() {
        if (this.pendingAnimalKeys.isEmpty()) {
            return List.of();
        }
        ArrayList<String> committed = new ArrayList<>(this.pendingAnimalKeys.size());
        for (String animalKey : this.pendingAnimalKeys) {
            AnimalDefinition definition = this.definitions.get(animalKey);
            if (definition == null) {
                continue;
            }
            QuestFlagManager.get().setFlag(definition.completionFlag);
            committed.add(definition.key);
        }
        this.pendingAnimalKeys.clear();
        return List.copyOf(committed);
    }

    public void ensureHubAnimalsInWorld(@Nonnull World world) {
        initialize();
        if (!GameDoorInteractionHandler.isHubWorld(world)) {
            return;
        }
        for (AnimalDefinition definition : this.definitions.values()) {
            if (!isAnimalRescued(definition)) {
                continue;
            }
            ensureHubAnimalPresentOrReset(world, definition);
        }
    }

    public void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        if (event.getPlayerRef() == null || !event.getPlayerRef().isValid()) {
            return;
        }
        PlayerRef playerRef = event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getWorldUuid() == null) {
            return;
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            return;
        }
        world.execute(() -> ensureHubAnimalsInWorld(world));
    }

    @Nonnull
    public List<String> getQuestProgressLines() {
        initialize();
        ArrayList<String> lines = new ArrayList<>(this.definitions.size() + 1);
        int rescuedCount = 0;
        for (AnimalDefinition definition : this.definitions.values()) {
            boolean rescued = isAnimalRescued(definition);
            if (rescued) {
                rescuedCount++;
            }
            lines.add(definition.displayName + ": " + (rescued ? "rescued" : "missing"));
        }
        lines.add("Animals rescued: " + rescuedCount + "/" + this.definitions.size());
        return List.copyOf(lines);
    }

    public double getQuestProgressRatio() {
        initialize();
        if (this.definitions.isEmpty()) {
            return 0.0;
        }
        int rescuedCount = 0;
        for (AnimalDefinition definition : this.definitions.values()) {
            if (isAnimalRescued(definition)) {
                rescuedCount++;
            }
        }
        return Math.min(1.0, rescuedCount / (double) this.definitions.size());
    }

    @Nonnull
    public String describeIncompleteObjectives() {
        initialize();
        ArrayList<String> missing = new ArrayList<>();
        for (AnimalDefinition definition : this.definitions.values()) {
            if (!isAnimalRescued(definition)) {
                missing.add(definition.displayName);
            }
        }
        return String.join(", ", missing);
    }

    public boolean areQuestRequirementsMet() {
        initialize();
        if (this.definitions.isEmpty()) {
            return false;
        }
        for (AnimalDefinition definition : this.definitions.values()) {
            if (!isAnimalRescued(definition)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    public MapMarker buildActiveMarker(@Nonnull UUID worldUuid) {
        ActiveAnimalState active = this.activeByWorld.get(worldUuid);
        if (active == null) {
            return null;
        }
        return active.cachedMarker;
    }

    @Nonnull
    public List<String> getAllAnimalKeys() {
        initialize();
        return List.copyOf(this.definitions.keySet());
    }

    public synchronized void resetQuestAndRuntimeState() {
        initialize();
        for (AnimalDefinition definition : this.definitions.values()) {
            QuestFlagManager.get().removeFlag(definition.completionFlag);
        }
        this.pendingAnimalKeys.clear();
        clearActiveWorldStatesAsync();
    }

    public void resetQuestRuntimeAndHubAnimalsIfLoaded() {
        resetQuestAndRuntimeState();
        World hubWorld = Universe.get().getWorld(GameFlowConfigManager.get().getHubWorldName());
        if (hubWorld == null) {
            return;
        }
        hubWorld.execute(() -> removeHubAnimalsInWorld(hubWorld));
    }

    public void removeHubAnimalsInWorld(@Nonnull World world) {
        initialize();
        if (!GameDoorInteractionHandler.isHubWorld(world)) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (AnimalDefinition definition : this.definitions.values()) {
            for (Ref<EntityStore> ref : findAllNpcRefsByRole(store, definition.hubRole)) {
                despawnNpc(ref);
            }
        }
    }

    private boolean isQuestAcceptedAndIncomplete() {
        QuestProgressManager.QuestProgress progress = QuestProgressManager.get().getOrCreate(QUEST_ID);
        return progress.accepted && !progress.completed;
    }

    private void trySpawnActiveAnimal(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull ActiveAnimalState active
    ) {
        AnimalDefinition definition = active.definition;
        if (definition == null || isAnimalRescued(definition)) {
            return;
        }
        Transform spawn = GameFlowConfigManager.get().getRescueRunSpawn(definition.key);
        if (spawn == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - active.lastSpawnAttemptAtMs < ACTIVE_SPAWN_COOLDOWN_MS) {
            return;
        }
        active.lastSpawnAttemptAtMs = now;
        int roleIndex = NPCPlugin.get().getIndex(definition.objectiveRole);
        BuilderInfo roleInfo = NPCPlugin.get().getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return;
        }
        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn =
                (npcEntity, npcRef, entityStore) -> entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);
        Pair<Ref<EntityStore>, NPCEntity> spawned = NPCPlugin.get().spawnEntity(
                store,
                roleIndex,
                new Vector3d(spawn.getPosition()),
                new Vector3f(spawn.getRotation()),
                null,
                postSpawn
        );
        if (spawned == null || spawned.first() == null || !spawned.first().isValid()) {
            return;
        }
        active.npcRef = spawned.first();
        cacheMarker(active, store);
    }

    private void ensureHubAnimalPresentOrReset(@Nonnull World world, @Nonnull AnimalDefinition definition) {
        Collection<Ref<EntityStore>> existingRefs = findAllNpcRefsByRole(world.getEntityStore().getStore(), definition.hubRole);
        Ref<EntityStore> survivor = null;
        for (Ref<EntityStore> ref : existingRefs) {
            if (!isUsableNpcRef(ref, definition.hubRole)) {
                continue;
            }
            if (survivor == null) {
                survivor = ref;
                continue;
            }
            despawnNpc(ref);
        }
        if (survivor != null && survivor.isValid()) {
            resetHubAnimalTransformIfNeeded(survivor, definition.hubSpawn, HUB_HOME_RESET_DISTANCE_SQUARED);
            return;
        }
        int roleIndex = NPCPlugin.get().getIndex(definition.hubRole);
        BuilderInfo roleInfo = NPCPlugin.get().getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return;
        }
        NPCPlugin.get().spawnEntity(
                world.getEntityStore().getStore(),
                roleIndex,
                new Vector3d(definition.hubSpawn.getPosition()),
                new Vector3f(definition.hubSpawn.getRotation()),
                null,
                null
        );
    }

    private synchronized void clearActiveWorldStates() {
        for (ActiveAnimalState active : this.activeByWorld.values()) {
            if (active != null) {
                active.reset();
            }
        }
        this.activeByWorld.clear();
    }

    private synchronized void clearActiveWorldStatesAsync() {
        List<Ref<EntityStore>> refsToDespawn = new ArrayList<>();
        for (ActiveAnimalState active : this.activeByWorld.values()) {
            if (active == null || active.npcRef == null || !active.npcRef.isValid()) {
                continue;
            }
            refsToDespawn.add(active.npcRef);
        }
        this.activeByWorld.clear();
        this.pendingAnimalKeys.clear();
        for (Ref<EntityStore> ref : refsToDespawn) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store == null || store.getExternalData() == null ? null : store.getExternalData().getWorld();
            if (world == null) {
                continue;
            }
            world.execute(() -> despawnNpc(ref));
        }
    }

    private void adoptOrPruneActiveObjectiveAnimal(
            @Nonnull Store<EntityStore> store,
            @Nonnull ActiveAnimalState active
    ) {
        AnimalDefinition definition = active.definition;
        if (definition == null) {
            return;
        }
        Collection<Ref<EntityStore>> refs = findAllNpcRefsByRole(store, definition.objectiveRole);
        Ref<EntityStore> survivor = null;
        for (Ref<EntityStore> ref : refs) {
            if (!isUsableNpcRef(ref, definition.objectiveRole)) {
                continue;
            }
            if (survivor == null) {
                survivor = ref;
                continue;
            }
            despawnNpc(ref);
        }
        active.npcRef = survivor;
    }

    private void cacheMarker(@Nonnull ActiveAnimalState active, @Nonnull Store<EntityStore> store) {
        if (active.definition == null || !isUsableNpcRef(active.npcRef, active.definition.objectiveRole)) {
            active.cachedMarker = null;
            return;
        }
        TransformComponent transform = store.getComponent(active.npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            active.cachedMarker = null;
            return;
        }
        FormattedMessage label = new FormattedMessage();
        label.rawText = active.definition.markerName;
        label.color = active.definition.markerColor;
        Transform markerTransform = new Transform(
                new Vector3d(
                        transform.getPosition().getX(),
                        transform.getPosition().getY() + MARKER_HEIGHT_OFFSET,
                        transform.getPosition().getZ()
                ),
                new Vector3f(0.0f, 0.0f, 0.0f)
        );
        active.cachedMarker = new MapMarker(
                active.definition.markerId,
                label,
                active.definition.markerIcon,
                com.hypixel.hytale.server.core.util.PositionUtil.toTransformPacket(markerTransform),
                null,
                null
        );
    }

    private static void resetHubAnimalTransformIfNeeded(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Transform targetTransform,
            double maxDistanceSquared
    ) {
        Store<EntityStore> store = npcRef.getStore();
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        double dx = transform.getPosition().x - targetTransform.getPosition().x;
        double dy = transform.getPosition().y - targetTransform.getPosition().y;
        double dz = transform.getPosition().z - targetTransform.getPosition().z;
        if (dx * dx + dy * dy + dz * dz <= maxDistanceSquared) {
            return;
        }
        transform.getPosition().x = targetTransform.getPosition().x;
        transform.getPosition().y = targetTransform.getPosition().y;
        transform.getPosition().z = targetTransform.getPosition().z;
        transform.getRotation().x = targetTransform.getRotation().x;
        transform.getRotation().y = targetTransform.getRotation().y;
        transform.getRotation().z = targetTransform.getRotation().z;
        store.putComponent(npcRef, TransformComponent.getComponentType(), transform);
    }

    @Nullable
    private AnimalDefinition chooseNextMissingAnimal(@Nonnull String templateWorldName) {
        initialize();
        String normalizedWorld = normalize(templateWorldName);
        for (AnimalDefinition definition : this.definitions.values()) {
            if (!definition.templateWorld.equalsIgnoreCase(normalizedWorld)) {
                continue;
            }
            if (!isAnimalRescued(definition)) {
                return definition;
            }
        }
        return null;
    }

    private boolean isAnimalRescued(@Nonnull AnimalDefinition definition) {
        return QuestFlagManager.get().hasFlag(definition.completionFlag);
    }

    private boolean isAnimalFollowing(@Nonnull ActiveAnimalState active) {
        if (active.definition == null || !isUsableNpcRef(active.npcRef, active.definition.objectiveRole)) {
            return false;
        }
        Store<EntityStore> store = active.npcRef.getStore();
        NPCEntity npc = store.getComponent(active.npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return false;
        }
        return isFollowingState(npc.getRole().getStateSupport().getStateName());
    }

    private static boolean isUsableNpcRef(@Nullable Ref<EntityStore> ref, @Nonnull String roleName) {
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || npc.isDespawning() || npc.getRoleName() == null || !roleName.equalsIgnoreCase(npc.getRoleName())) {
            return false;
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        return transform != null;
    }

    private static void despawnNpc(@Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || npc.isDespawning()) {
            return;
        }
        npc.setToDespawn();
        store.putComponent(ref, NPCEntity.getComponentType(), npc);
    }

    private static boolean isFollowingState(@Nullable String stateName) {
        if (stateName == null) {
            return false;
        }
        return FOLLOW_STATE.equalsIgnoreCase(stateName) || stateName.startsWith("$Interaction");
    }

    private void loadDefinitions() {
        Properties properties = new Properties();
        try (InputStream in = FarmerAnimalRescueManager.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                System.out.println("[FarmerAnimals] Resource not found: " + RESOURCE_PATH);
                return;
            }
            properties.load(in);
        } catch (IOException e) {
            System.out.println("[FarmerAnimals] Failed to load resource: " + e.getMessage());
            return;
        }

        for (String animalKey : parseCsv(properties.getProperty("animals"))) {
            String prefix = "animal." + animalKey + ".";
            String displayName = trimToEmpty(properties.getProperty(prefix + "displayName"));
            String objectiveRole = trimToEmpty(properties.getProperty(prefix + "objectiveRole"));
            String hubRole = trimToEmpty(properties.getProperty(prefix + "hubRole"));
            String completionFlag = trimToEmpty(properties.getProperty(prefix + "completionFlag"));
            String templateWorld = trimToEmpty(properties.getProperty(prefix + "templateWorld"));
            String markerId = trimToEmpty(properties.getProperty(prefix + "markerId"));
            String markerName = trimToEmpty(properties.getProperty(prefix + "markerName"));
            String markerIcon = trimToEmpty(properties.getProperty(prefix + "markerIcon"));
            String markerColor = trimToEmpty(properties.getProperty(prefix + "markerColor"));
            Transform hubSpawn = readTransform(properties, prefix + "hubSpawn.");
            if (displayName.isBlank()
                    || objectiveRole.isBlank()
                    || hubRole.isBlank()
                    || completionFlag.isBlank()
                    || templateWorld.isBlank()
                    || markerId.isBlank()
                    || markerName.isBlank()
                    || markerIcon.isBlank()
                    || markerColor.isBlank()
                    || hubSpawn == null) {
                continue;
            }
            this.definitions.put(animalKey, new AnimalDefinition(
                    animalKey,
                    displayName,
                    objectiveRole,
                    hubRole,
                    completionFlag,
                    templateWorld,
                    markerId,
                    markerName,
                    markerIcon,
                    markerColor,
                    hubSpawn
            ));
        }
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
                if (npc == null || !roleName.equalsIgnoreCase(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    if (isUsableNpcRef(ref, roleName)) {
                        found[0] = ref;
                        return;
                    }
                }
            }
        });
        return found[0];
    }

    @Nonnull
    private static Collection<Ref<EntityStore>> findAllNpcRefsByRole(@Nonnull Store<EntityStore> store, @Nonnull String roleName) {
        List<Ref<EntityStore>> found = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !roleName.equalsIgnoreCase(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found.add(ref);
                }
            }
        });
        return found;
    }

    @Nullable
    private static Transform readTransform(@Nonnull Properties p, @Nonnull String prefix) {
        Double x = readDouble(p.getProperty(prefix + "x"));
        Double y = readDouble(p.getProperty(prefix + "y"));
        Double z = readDouble(p.getProperty(prefix + "z"));
        Double pitch = readDouble(p.getProperty(prefix + "pitch"));
        Double yaw = readDouble(p.getProperty(prefix + "yaw"));
        Double roll = readDouble(p.getProperty(prefix + "roll"));
        if (x == null || y == null || z == null || pitch == null || yaw == null || roll == null) {
            return null;
        }
        return new Transform(
                new Vector3d(x, y, z),
                new Vector3f(pitch.floatValue(), yaw.floatValue(), roll.floatValue())
        );
    }

    @Nullable
    private static Double readDouble(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String normalized = normalize(item);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static String trimToEmpty(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    @Nonnull
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSessionPhaseActive(@Nonnull RunPhase phase) {
        return phase == RunPhase.WAITING_FOR_PLAYERS_READY
                || phase == RunPhase.EXPLORATION
                || phase == RunPhase.CRIMSON_ACTIVE;
    }

    private static final class AnimalDefinition {
        @Nonnull
        private final String key;
        @Nonnull
        private final String displayName;
        @Nonnull
        private final String objectiveRole;
        @Nonnull
        private final String hubRole;
        @Nonnull
        private final String completionFlag;
        @Nonnull
        private final String templateWorld;
        @Nonnull
        private final String markerId;
        @Nonnull
        private final String markerName;
        @Nonnull
        private final String markerIcon;
        @Nonnull
        private final String markerColor;
        @Nonnull
        private final Transform hubSpawn;

        private AnimalDefinition(
                @Nonnull String key,
                @Nonnull String displayName,
                @Nonnull String objectiveRole,
                @Nonnull String hubRole,
                @Nonnull String completionFlag,
                @Nonnull String templateWorld,
                @Nonnull String markerId,
                @Nonnull String markerName,
                @Nonnull String markerIcon,
                @Nonnull String markerColor,
                @Nonnull Transform hubSpawn
        ) {
            this.key = key;
            this.displayName = displayName;
            this.objectiveRole = objectiveRole;
            this.hubRole = hubRole;
            this.completionFlag = completionFlag;
            this.templateWorld = templateWorld;
            this.markerId = markerId;
            this.markerName = markerName;
            this.markerIcon = markerIcon;
            this.markerColor = markerColor;
            this.hubSpawn = hubSpawn;
        }
    }

    private static final class ActiveAnimalState {
        @Nullable
        private AnimalDefinition definition;
        @Nullable
        private Ref<EntityStore> npcRef;
        @Nullable
        private MapMarker cachedMarker;
        private boolean escortConfirmed;
        private long lastFollowingAtMs;
        private long lastSpawnAttemptAtMs;

        private void reset() {
            this.definition = null;
            this.npcRef = null;
            this.cachedMarker = null;
            this.escortConfirmed = false;
            this.lastFollowingAtMs = 0L;
            this.lastSpawnAttemptAtMs = 0L;
        }
    }
}
