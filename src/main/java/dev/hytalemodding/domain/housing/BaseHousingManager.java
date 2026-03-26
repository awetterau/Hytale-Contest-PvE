package dev.hytalemodding.domain.housing;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.map.BlacksmithSharedMarkerManager;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.state.hub.BlacksmithPrefabController;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class BaseHousingManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "base-housing.properties";
    private static final String BLACKSMITH_KEY = "blacksmith";
    private static final String BLACKSMITH_ROLE = "Blacksmith_Escort_Base";
    private static final String DARIUS_KEY = "darius";
    private static final String DARIUS_ROLE = "Captain_Darius_Hub";
    private static final String BLACKSMITH_PLOT_TYPE = "blacksmith";
    private static final String BLACKSMITH_WORKSHOP_ASSIGNMENT_ID = "blacksmith_workshop_fixed";
    private static final String BUILDING_BLOCK = "Rock_Chalk_Brick_Decorative";
    private static final long NPC_SPAWN_COOLDOWN_MS = 1500L;
    private static final Transform BLACKSMITH_WAITING_TRANSFORM = new Transform(
            new Vector3d(572.0, 181.0, -9.0),
            new Vector3f(0.0f, 0.0f, 0.0f)
    );
    private static final Transform BLACKSMITH_WORKSHOP_HOME = new Transform(
            new Vector3d(592.0, 181.0, -11.0),
            new Vector3f(0.0f, 0.0f, 0.0f)
    );
    private static final double BLACKSMITH_WAITING_RADIUS_SQUARED = 16.0D;
    private static final double BLACKSMITH_HOME_RADIUS_SQUARED = 16.0D;
    private static final BaseHousingManager INSTANCE = new BaseHousingManager();

    private final ConcurrentHashMap<String, PlotData> plots = new ConcurrentHashMap<>();
    private final HubNpcManager hubNpcManager = HubNpcManager.get();
    private boolean loaded;
    private long lastBlacksmithSpawnAttemptMs;

    private BaseHousingManager() {
    }

    @Nonnull
    public static BaseHousingManager get() {
        return INSTANCE;
    }

    public synchronized boolean addOrUpdatePlot(
            @Nonnull String id,
            @Nonnull String worldName,
            @Nonnull Vector3i markerPos,
            @Nonnull Transform homeTransform
    ) {
        ensureLoaded();
        PlotData existing = this.plots.get(id);
        String assignedNpc = existing == null ? null : existing.assignedNpcKey;
        String plotType = existing == null ? BLACKSMITH_PLOT_TYPE : existing.plotType;
        boolean purchased = existing != null && existing.purchased;
        int buildingLevel = existing == null ? 0 : existing.buildingLevel;
        this.plots.put(
                id,
                new PlotData(
                        id,
                        worldName,
                        new Vector3i(markerPos),
                        copyTransform(homeTransform),
                        plotType,
                        purchased,
                        assignedNpc,
                        buildingLevel
                )
        );
        saveQuietly();
        return existing == null;
    }

    public synchronized boolean removePlot(@Nonnull String id) {
        ensureLoaded();
        PlotData removed = this.plots.remove(id);
        if (removed == null) {
            return false;
        }
        if (removed.assignedNpcKey != null) {
            this.hubNpcManager.clearAssignment(removed.assignedNpcKey);
        }
        saveQuietly();
        return true;
    }

    public synchronized boolean setPlotHome(@Nonnull String id, @Nonnull Transform homeTransform) {
        ensureLoaded();
        PlotData plot = this.plots.get(id);
        if (plot == null) {
            return false;
        }
        this.plots.put(id, plot.withHome(copyTransform(homeTransform)));
        saveQuietly();
        return true;
    }

    public synchronized boolean clearAssignment(@Nonnull String id) {
        ensureLoaded();
        PlotData plot = this.plots.get(id);
        if (plot == null) {
            return false;
        }
        if (plot.assignedNpcKey != null) {
            this.hubNpcManager.clearAssignment(plot.assignedNpcKey);
        }
        this.plots.put(id, plot.withAssignment(null, plot.purchased, plot.buildingLevel));
        saveQuietly();
        return true;
    }

    @Nonnull
    public synchronized AssignmentResult setPlotType(@Nonnull String id, @Nonnull String plotType) {
        ensureLoaded();
        PlotData plot = this.plots.get(id);
        if (plot == null) {
            return AssignmentResult.fail("Plot not found: " + id);
        }
        if (plot.assignedNpcKey != null) {
            return AssignmentResult.fail("Clear assignment before changing plot type.");
        }
        String normalizedType = normalizeOrDefault(plotType, BLACKSMITH_PLOT_TYPE);
        this.plots.put(id, plot.withPlotType(normalizedType));
        saveQuietly();
        return AssignmentResult.ok("Plot " + id + " type set to " + normalizedType + ".");
    }

    public synchronized void resetAll() {
        ensureLoaded();
        this.plots.clear();
        this.hubNpcManager.resetAll();
        saveQuietly();
    }

    @Nonnull
    public synchronized List<String> getPlotIdsForWorld(@Nonnull String worldName) {
        ensureLoaded();
        List<String> ids = new ArrayList<>();
        for (PlotData plot : this.plots.values()) {
            if (plot.worldName.equalsIgnoreCase(worldName)) {
                ids.add(plot.id);
            }
        }
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    @Nonnull
    public synchronized List<String> describePlots() {
        ensureLoaded();
        List<String> lines = new ArrayList<>();
        if (this.plots.isEmpty()) {
            lines.add("<none>");
            return lines;
        }
        for (PlotData plot : this.plots.values()) {
            lines.add(plot.id + " world=" + plot.worldName
                    + " marker=(" + plot.markerPos.x + "," + plot.markerPos.y + "," + plot.markerPos.z + ")"
                    + " type=" + plot.plotType
                    + " purchased=" + plot.purchased
                    + " assigned=" + (plot.assignedNpcKey == null ? "<none>" : plot.assignedNpcKey)
                    + " buildingLevel=" + plot.buildingLevel);
        }
        return lines;
    }

    @Nullable
    public synchronized PlotData findPlotByMarker(@Nonnull String worldName, @Nonnull Vector3i markerPos) {
        ensureLoaded();
        for (PlotData plot : this.plots.values()) {
            if (plot.worldName.equalsIgnoreCase(worldName)
                    && plot.markerPos.x == markerPos.x
                    && plot.markerPos.y == markerPos.y
                    && plot.markerPos.z == markerPos.z) {
                return plot.copy();
            }
        }
        return null;
    }

    @Nullable
    public synchronized PlotData getPlot(@Nonnull String plotId) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        return plot == null ? null : plot.copy();
    }

    @Nonnull
    public synchronized List<String> getEligibleNpcKeysForPlot(@Nonnull String plotId) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null || !plot.purchased || plot.assignedNpcKey != null) {
            return List.of();
        }
        if (BLACKSMITH_PLOT_TYPE.equalsIgnoreCase(plot.plotType)) {
            return List.of();
        }
        String plotType = normalizeOrDefault(plot.plotType, BLACKSMITH_PLOT_TYPE);
        List<String> keys = new ArrayList<>();
        for (String profession : getNpcKeysForPlotType(plotType)) {
            if (isNpcRescued(profession) && !isNpcAssigned(profession)) {
                keys.add(profession);
            }
        }
        keys.sort(String::compareToIgnoreCase);
        return keys;
    }

    @Nonnull
    public synchronized AssignmentResult purchasePlot(@Nonnull String plotId) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null) {
            return AssignmentResult.fail("Unknown plot: " + plotId);
        }
        if (plot.purchased) {
            return AssignmentResult.fail("This plot is already purchased.");
        }
        if (!isPlotPurchaseUnlocked(plot.plotType)) {
            return AssignmentResult.fail("This plot is currently locked. Complete prerequisite questline first.");
        }
        if (BLACKSMITH_PLOT_TYPE.equalsIgnoreCase(plot.plotType)) {
            return AssignmentResult.fail("Blacksmith workshop construction is handled directly through the blacksmith.");
        }
        World world = Universe.get().getWorld(plot.worldName);
        if (world == null) {
            return AssignmentResult.fail("Plot world is not loaded: " + plot.worldName);
        }

        PlotData purchased = plot.withAssignment(null, true, Math.max(1, plot.buildingLevel));
        this.plots.put(plotId, purchased);
        buildHomeForPlot(world, plot.markerPos, normalizeOrDefault(purchased.plotType, BLACKSMITH_PLOT_TYPE));
        world.setBlock(plot.markerPos.x, plot.markerPos.y, plot.markerPos.z, "Empty");

        for (String profession : getNpcKeysForPlotType(normalizeOrDefault(purchased.plotType, BLACKSMITH_PLOT_TYPE))) {
            if (isNpcRescued(profession) && !isNpcAssigned(profession)) {
                return assignNpcToPlot(plotId, profession);
            }
        }
        saveQuietly();
        return AssignmentResult.ok("Purchased plot " + plotId + ".");
    }

    @Nonnull
    public synchronized AssignmentResult assignNpcToPlot(@Nonnull String plotId, @Nonnull String npcKey) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null) {
            return AssignmentResult.fail("Unknown plot: " + plotId);
        }
        if (!plot.purchased) {
            return AssignmentResult.fail("This plot is not purchased yet.");
        }
        if (plot.assignedNpcKey != null) {
            return AssignmentResult.fail("This plot is already occupied.");
        }
        if (!isNpcRescued(npcKey)) {
            return AssignmentResult.fail("That NPC is not rescued yet.");
        }
        if (isNpcAssigned(npcKey)) {
            return AssignmentResult.fail("That NPC is already assigned to another plot.");
        }
        World world = Universe.get().getWorld(plot.worldName);
        if (world == null) {
            return AssignmentResult.fail("Plot world is not loaded: " + plot.worldName);
        }
        String normalizedNpc = normalizeOrDefault(npcKey, "");
        if (!isSupportedProfession(normalizedNpc)) {
            return AssignmentResult.fail("Unsupported profession: " + npcKey);
        }
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(normalizedNpc);
        String npcPlotType = archetype == null || archetype.plotType == null
                ? normalizeOrDefault(normalizedNpc, "")
                : normalizeOrDefault(archetype.plotType, "");
        if (!npcPlotType.equalsIgnoreCase(plot.plotType)) {
            return AssignmentResult.fail("This plot is designated for " + plot.plotType + ", not " + npcPlotType + ".");
        }
        if (BLACKSMITH_KEY.equalsIgnoreCase(normalizedNpc) || BLACKSMITH_PLOT_TYPE.equalsIgnoreCase(plot.plotType)) {
            return AssignmentResult.fail("Blacksmith uses the fixed workshop flow instead of plot assignment.");
        }

        buildHomeForPlot(world, plot.markerPos, normalizeOrDefault(plot.plotType, BLACKSMITH_PLOT_TYPE));
        world.setBlock(plot.markerPos.x, plot.markerPos.y, plot.markerPos.z, "Empty");
        ensureNpcAtHome(world, normalizedNpc, plot.homeTransform);

        PlotData updated = plot.withAssignment(normalizedNpc, true, Math.max(1, plot.buildingLevel));
        this.plots.put(plotId, updated);
        this.hubNpcManager.startMovingToWorkshop(normalizedNpc, plotId);
        saveQuietly();
        return AssignmentResult.ok("Assigned " + normalizedNpc + " to plot " + plotId + ".");
    }

    public synchronized void ensureAssignmentsInWorld(@Nonnull World world) {
        ensureLoaded();
        String worldName = world.getName();
        if (!GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(worldName)) {
            return;
        }

        reconcileInvalidAssignments(worldName);

        ensureStaticHubResidentsInWorld(world);

        Collection<PlotData> snapshot = new ArrayList<>(this.plots.values());
        for (PlotData plot : snapshot) {
            if (!plot.worldName.equalsIgnoreCase(worldName) || !plot.purchased || plot.assignedNpcKey != null) {
                continue;
            }
            String plotType = normalizeOrDefault(plot.plotType, BLACKSMITH_PLOT_TYPE);
            for (String profession : getNpcKeysForPlotType(plotType)) {
                if (isNpcRescued(profession) && !isNpcAssigned(profession)) {
                    assignNpcToPlot(plot.id, profession);
                    break;
                }
            }
        }

        for (PlotData plot : snapshot) {
            if (!plot.worldName.equalsIgnoreCase(worldName) || !plot.purchased || plot.assignedNpcKey == null) {
                continue;
            }
            String npcKey = normalizeOrDefault(plot.assignedNpcKey, "");
            if (npcKey.isEmpty()) {
                continue;
            }
            if (!isNpcRescued(npcKey)) {
                continue;
            }
            try {
                ensureNpcAtHome(world, npcKey, plot.homeTransform);
                NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
                String roleName = archetype == null ? null : archetype.hubRole;
                if (roleName != null && !roleName.isBlank()) {
                    boolean reachedWorkshop = isNpcCloseTo(world, roleName, plot.homeTransform.getPosition(), 2.25);
                    this.hubNpcManager.promoteToWorkingIfReady(npcKey, reachedWorkshop);
                }
            } catch (Exception e) {
                System.out.println("[BaseHousing] Skipping invalid hub NPC assignment plot=" + plot.id + " npc=" + npcKey + ": " + e.getMessage());
            }
        }
    }

    private void reconcileInvalidAssignments(@Nonnull String worldName) {
        boolean changed = false;
        for (Map.Entry<String, PlotData> entry : this.plots.entrySet()) {
            PlotData plot = entry.getValue();
            if (plot == null || !plot.worldName.equalsIgnoreCase(worldName) || plot.assignedNpcKey == null) {
                continue;
            }

            String npcKey = normalizeOrDefault(plot.assignedNpcKey, "");
            if (npcKey.isEmpty()) {
                this.plots.put(entry.getKey(), plot.withAssignment(null, plot.purchased, plot.buildingLevel));
                changed = true;
                continue;
            }

            NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
            String npcPlotType = archetype == null || archetype.plotType == null
                    ? normalizeOrDefault(npcKey, "")
                    : normalizeOrDefault(archetype.plotType, "");
            boolean valid = plot.purchased
                    && isNpcRescued(npcKey)
                    && isSupportedProfession(npcKey)
                    && !npcPlotType.isEmpty()
                    && npcPlotType.equalsIgnoreCase(normalizeOrDefault(plot.plotType, BLACKSMITH_PLOT_TYPE))
                    && archetype != null
                    && archetype.hubRole != null
                    && !archetype.hubRole.isBlank();

            if (valid) {
                continue;
            }

            System.out.println("[BaseHousing] Clearing stale assignment plot=" + plot.id
                    + " npc=" + npcKey
                    + " purchased=" + plot.purchased
                    + " rescued=" + isNpcRescued(npcKey)
                    + " plotType=" + plot.plotType
                    + " npcPlotType=" + npcPlotType);
            this.plots.put(entry.getKey(), plot.withAssignment(null, plot.purchased, plot.buildingLevel));
            this.hubNpcManager.clearAssignment(npcKey);
            changed = true;
        }

        if (changed) {
            saveQuietly();
        }
    }

    private void ensureStaticHubResidentsInWorld(@Nonnull World world) {
        if (!hasActivePlayerInWorld(world)) {
            return;
        }
        for (NpcArchetype archetype : NpcDefinitionRegistry.get().getAll()) {
            if (!archetype.alwaysInHub || archetype.hubRole == null || archetype.hubRole.isBlank()) {
                continue;
            }
            if (DARIUS_KEY.equalsIgnoreCase(archetype.npcKey)) {
                continue;
            }
            Transform target = resolveStaticHubTransform(archetype);
            if (target == null) {
                continue;
            }
            ensureRoleAtTransform(world, archetype.hubRole, target);
        }
    }

    private static boolean hasActivePlayerInWorld(@Nonnull World world) {
        for (var playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || !playerRef.isValid() || playerRef.getWorldUuid() == null) {
                continue;
            }
            World playerWorld = Universe.get().getWorld(playerRef.getWorldUuid());
            if (playerWorld != null && playerWorld.getName().equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isNpcWorking(@Nonnull String npcKey) {
        ensureLoaded();
        return this.hubNpcManager.isWorking(npcKey);
    }

    public synchronized boolean isBlacksmithWorkshopReady() {
        ensureLoaded();
        return isBlacksmithWorkshopBuilt();
    }

    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        if (event.getPlayer() == null || event.getPlayer().getReference() == null) {
            return;
        }
        var initialRef = event.getPlayer().getReference();
        var initialStore = initialRef.getStore();
        if (initialStore == null) {
            return;
        }
        World world = initialStore.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> {
            BaseHousingManager manager = BaseHousingManager.get();
            manager.ensureBlacksmithPresenceInHubIfLoaded(world);
            manager.ensureDariusPresenceInHubIfLoaded(world);
        });
    }

    @Nonnull
    public synchronized HubNpcManager.NpcData getNpcData(@Nonnull String npcKey) {
        ensureLoaded();
        return this.hubNpcManager.getOrCreate(npcKey);
    }

    @Nonnull
    public synchronized HubNpcManager.HubNpcState getNpcState(@Nonnull String npcKey) {
        ensureLoaded();
        return this.hubNpcManager.getState(npcKey);
    }

    public synchronized boolean canOpenDialogue(@Nonnull String npcKey) {
        ensureLoaded();
        if (this.hubNpcManager.isWorking(npcKey)) {
            return true;
        }
        return isBlacksmithAwaitingWorkshop(npcKey);
    }

    public synchronized boolean canUsePreWorkshopUpgrade(@Nonnull String npcKey) {
        ensureLoaded();
        if (!isBlacksmithAwaitingWorkshop(npcKey)) {
            return false;
        }
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.prePlotQuestId == null || archetype.prePlotQuestId.isBlank()) {
            return true;
        }
        return QuestProgressManager.get().isCompleted(archetype.prePlotQuestId);
    }

    public synchronized boolean isWorkshopBuilt(@Nonnull String npcKey) {
        ensureLoaded();
        if (!BLACKSMITH_KEY.equalsIgnoreCase(normalizeOrDefault(npcKey, ""))) {
            return this.hubNpcManager.getOrCreate(npcKey).assignedPlotId != null;
        }
        return isBlacksmithWorkshopBuilt();
    }

    @Nullable
    public synchronized Transform getFixedHubRescueSpawn(@Nonnull String npcKey) {
        ensureLoaded();
        if (!BLACKSMITH_KEY.equalsIgnoreCase(normalizeOrDefault(npcKey, ""))) {
            return null;
        }
        return copyTransform(BLACKSMITH_WAITING_TRANSFORM);
    }

    @Nonnull
    public synchronized AssignmentResult beginBlacksmithWorkshopUpgradePrecheck(@Nonnull PlayerRef playerRef) {
        ensureLoaded();
        if (!isNpcRescued(BLACKSMITH_KEY)) {
            return AssignmentResult.fail("Blacksmith is not rescued yet.");
        }
        NpcArchetype blacksmith = NpcDefinitionRegistry.get().getArchetype(BLACKSMITH_KEY);
        if (blacksmith != null
                && blacksmith.prePlotQuestId != null
                && !blacksmith.prePlotQuestId.isBlank()
                && !QuestProgressManager.get().isCompleted(blacksmith.prePlotQuestId)) {
            return AssignmentResult.fail("Complete '" + blacksmith.prePlotQuestId + "' before building the blacksmith workshop.");
        }
        if (isBlacksmithWorkshopBuilt()) {
            return AssignmentResult.fail("Blacksmith workshop is already built.");
        }
        if (playerRef.getWorldUuid() == null) {
            return AssignmentResult.fail("Player world is unavailable.");
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null || !GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(world.getName())) {
            return AssignmentResult.fail("Blacksmith workshop can only be built from the hub.");
        }
        BlacksmithPrefabController.Result validation = BlacksmithPrefabController.validateCustomBlacksmithBuildStart();
        return validation.success()
                ? AssignmentResult.ok(validation.message())
                : AssignmentResult.fail(validation.message());
    }

    @Nonnull
    public synchronized AssignmentResult beginBlacksmithWorkshopUpgrade(@Nonnull PlayerRef playerRef) {
        ensureLoaded();
        AssignmentResult precheck = beginBlacksmithWorkshopUpgradePrecheck(playerRef);
        if (!precheck.success) {
            return precheck;
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());

        BlacksmithPrefabController.Result result = BlacksmithPrefabController.scheduleCustomBlacksmithBuildStart(1000L);
        if (!result.success()) {
            return AssignmentResult.fail(result.message());
        }

        this.hubNpcManager.startMovingToWorkshop(BLACKSMITH_KEY, BLACKSMITH_WORKSHOP_ASSIGNMENT_ID);
        ensureNpcAtHome(world, BLACKSMITH_KEY, BLACKSMITH_WORKSHOP_HOME, BLACKSMITH_HOME_RADIUS_SQUARED);
        return AssignmentResult.ok("Blacksmith workshop construction started.");
    }

    @Nonnull
    public synchronized Transform getBlacksmithWorkshopMarkerTransform() {
        return copyTransform(BLACKSMITH_WORKSHOP_HOME);
    }

    public synchronized void onBlacksmithWorkshopBuilt(@Nullable World world) {
        ensureLoaded();
        if (world == null || !GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(world.getName())) {
            return;
        }
        if (!isNpcRescued(BLACKSMITH_KEY) || !isBlacksmithWorkshopBuilt()) {
            return;
        }
        placeBlacksmithWorkshopSupportBlocks(world);
        teleportNpcToTransform(world, BLACKSMITH_ROLE, BLACKSMITH_WORKSHOP_HOME);
        this.hubNpcManager.devAssign(BLACKSMITH_KEY, BLACKSMITH_WORKSHOP_ASSIGNMENT_ID, HubNpcManager.HubNpcState.WORKING);
        ensureNpcAtHome(world, BLACKSMITH_KEY, BLACKSMITH_WORKSHOP_HOME, BLACKSMITH_HOME_RADIUS_SQUARED);
        BlacksmithSharedMarkerManager.sync();
    }

    public synchronized void ensureBlacksmithPresenceInHubIfLoaded(@Nullable World world) {
        ensureLoaded();
        if (world == null || !GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(world.getName())) {
            return;
        }
        syncBlacksmithRescueStateFromHub(world);
        ensureBlacksmithHubFlow(world);
    }

    public synchronized void ensureDariusPresenceInHubIfLoaded(@Nullable World world) {
        ensureLoaded();
        if (world == null || !GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(world.getName())) {
            return;
        }
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(DARIUS_KEY);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            return;
        }
        Transform target = resolveStaticHubTransform(archetype);
        if (target == null) {
            return;
        }
        ensureRoleAtTransform(world, DARIUS_ROLE, target);
    }

    @Nonnull
    public synchronized AssignmentResult devSetPlotPurchased(@Nonnull String plotId, boolean purchased) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null) {
            return AssignmentResult.fail("Unknown plot: " + plotId);
        }
        int nextLevel = purchased ? Math.max(1, plot.buildingLevel) : 0;
        String nextAssigned = purchased ? plot.assignedNpcKey : null;
        if (!purchased && plot.assignedNpcKey != null) {
            this.hubNpcManager.clearAssignment(plot.assignedNpcKey);
        }
        this.plots.put(plotId, plot.withAssignment(nextAssigned, purchased, nextLevel));
        saveQuietly();
        return AssignmentResult.ok("Plot " + plotId + " purchased=" + purchased + ".");
    }

    @Nonnull
    public synchronized AssignmentResult devSetPlotBuildingLevel(@Nonnull String plotId, int level) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null) {
            return AssignmentResult.fail("Unknown plot: " + plotId);
        }
        int clampedLevel = Math.max(0, level);
        boolean purchased = clampedLevel > 0 || plot.purchased;
        this.plots.put(plotId, plot.withAssignment(plot.assignedNpcKey, purchased, clampedLevel));
        saveQuietly();
        return AssignmentResult.ok("Plot " + plotId + " buildingLevel=" + clampedLevel + ".");
    }

    @Nonnull
    public synchronized AssignmentResult devSetPlotType(@Nonnull String plotId, @Nonnull String plotType) {
        return setPlotType(plotId, plotType);
    }

    public synchronized int removeAllBaseBlacksmithsInWorld(@Nonnull World world) {
        int removed = 0;
        Store<EntityStore> store = world.getEntityStore().getStore();
        Collection<Ref<EntityStore>> refs = findAllNpcByRole(store, BLACKSMITH_ROLE);
        for (Ref<EntityStore> ref : refs) {
            NPCEntity npc = ref == null || !ref.isValid() ? null : store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null) {
                npc.setToDespawn();
                store.putComponent(ref, NPCEntity.getComponentType(), npc);
                removed++;
            }
        }
        return removed;
    }

    private void ensureNpcAtHome(@Nonnull World world, @Nonnull String npcKey, @Nonnull Transform homeTransform) {
        ensureNpcAtHome(world, npcKey, homeTransform, 4.0D);
    }

    private void ensureNpcAtHome(
            @Nonnull World world,
            @Nonnull String npcKey,
            @Nonnull Transform homeTransform,
            double idleDistanceSquared
    ) {
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            return;
        }
        ensureRoleAtTransform(world, archetype.hubRole, homeTransform, idleDistanceSquared);
    }

    private void ensureRoleAtTransform(@Nonnull World world, @Nonnull String roleName, @Nonnull Transform targetTransform) {
        ensureRoleAtTransform(world, roleName, targetTransform, 4.0D);
    }

    private void ensureRoleAtTransform(
            @Nonnull World world,
            @Nonnull String roleName,
            @Nonnull Transform targetTransform,
            double idleDistanceSquared
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Collection<Ref<EntityStore>> all = findAllNpcByRole(store, roleName);
        Ref<EntityStore> existing = null;
        for (Ref<EntityStore> ref : all) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            NPCEntity candidate = store.getComponent(ref, NPCEntity.getComponentType());
            if (candidate == null || candidate.isDespawning()) {
                continue;
            }
            if (existing == null) {
                existing = ref;
                continue;
            }
            NPCEntity duplicate = store.getComponent(ref, NPCEntity.getComponentType());
            if (duplicate != null && !duplicate.isDespawning()) {
                duplicate.setToDespawn();
                store.putComponent(ref, NPCEntity.getComponentType(), duplicate);
            }
        }

        if (existing == null || !existing.isValid()) {
            long now = System.currentTimeMillis();
            if (now - this.lastBlacksmithSpawnAttemptMs < NPC_SPAWN_COOLDOWN_MS) {
                return;
            }
            this.lastBlacksmithSpawnAttemptMs = now;
            spawnNpc(world, roleName, targetTransform);
            return;
        }

        TransformComponent transform = store.getComponent(existing, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        NPCEntity npc = store.getComponent(existing, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        store.putComponent(existing, Invulnerable.getComponentType(), Invulnerable.INSTANCE);

        Vector3d home = targetTransform.getPosition();
        npc.saveLeashInformation(new Vector3d(home), new Vector3f(targetTransform.getRotation()));
        store.putComponent(existing, NPCEntity.getComponentType(), npc);

        Vector3d pos = transform.getPosition();
        double dx = pos.getX() - home.getX();
        double dy = pos.getY() - home.getY();
        double dz = pos.getZ() - home.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > idleDistanceSquared && npc.getRole() != null && npc.getRole().getStateSupport() != null && !npc.getRole().getStateSupport().isInBusyState()) {
            npc.getRole().getStateSupport().setState(existing, "Idle", null, store);
        }
    }

    private void ensureBlacksmithHubFlow(@Nonnull World world) {
        if (!isNpcRescued(BLACKSMITH_KEY)) {
            return;
        }

        if (!isBlacksmithWorkshopBuilt()) {
            HubNpcManager.NpcData blacksmithData = this.hubNpcManager.getOrCreate(BLACKSMITH_KEY);
            if (blacksmithData.assignedPlotId != null) {
                this.hubNpcManager.clearAssignment(BLACKSMITH_KEY);
            }
            ensureRoleAtTransform(world, BLACKSMITH_ROLE, BLACKSMITH_WAITING_TRANSFORM, BLACKSMITH_WAITING_RADIUS_SQUARED);
            return;
        }

        placeBlacksmithWorkshopSupportBlocks(world);
        HubNpcManager.NpcData blacksmithData = this.hubNpcManager.getOrCreate(BLACKSMITH_KEY);
        if (!BLACKSMITH_WORKSHOP_ASSIGNMENT_ID.equalsIgnoreCase(
                blacksmithData.assignedPlotId == null ? "" : blacksmithData.assignedPlotId
        )) {
            this.hubNpcManager.startMovingToWorkshop(BLACKSMITH_KEY, BLACKSMITH_WORKSHOP_ASSIGNMENT_ID);
        }

        ensureNpcAtHome(world, BLACKSMITH_KEY, BLACKSMITH_WORKSHOP_HOME, BLACKSMITH_HOME_RADIUS_SQUARED);
        boolean reachedWorkshop = isNpcCloseTo(world, BLACKSMITH_ROLE, BLACKSMITH_WORKSHOP_HOME.getPosition(), BLACKSMITH_HOME_RADIUS_SQUARED);
        this.hubNpcManager.promoteToWorkingIfReady(BLACKSMITH_KEY, reachedWorkshop);
    }

    private void syncBlacksmithRescueStateFromHub(@Nonnull World world) {
        if (isNpcRescued(BLACKSMITH_KEY)) {
            return;
        }
        Ref<EntityStore> blacksmithRef = findNpcByRole(world.getEntityStore().getStore(), BLACKSMITH_ROLE);
        if (blacksmithRef == null || !blacksmithRef.isValid()) {
            return;
        }
        NpcProgressManager.get().setNpcRescued(BLACKSMITH_KEY, true);
        GameFlowConfigManager.get().setNpcRescued(BLACKSMITH_KEY, true);
    }

    @Nullable
    private Transform resolveStaticHubTransform(@Nonnull NpcArchetype archetype) {
        if (archetype.hubSpawnTransform != null) {
            return copyTransform(archetype.hubSpawnTransform);
        }
        Transform baseSpawn = GameFlowConfigManager.get().getBaseSpawn();
        if (baseSpawn == null) {
            return null;
        }
        Vector3d basePos = baseSpawn.getPosition();
        Vector3f baseRot = baseSpawn.getRotation();
        Vector3d forward = Transform.getDirection(0.0f, baseRot.getY());
        Vector3d pos = new Vector3d(
                basePos.getX() - forward.getX() * 2.0,
                basePos.getY(),
                basePos.getZ() - forward.getZ() * 2.0
        );
        return new Transform(pos, new Vector3f(baseRot));
    }

    private static void spawnNpc(@Nonnull World world, @Nonnull String roleName, @Nonnull Transform transform) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(roleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return;
        }

        Vector3d spawnPos = new Vector3d(transform.getPosition());
        Vector3f spawnRot = new Vector3f(transform.getRotation());
        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) -> {
            entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);
            entityStore.putComponent(npcRef, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        };
        Pair<Ref<EntityStore>, NPCEntity> pair = npcPlugin.spawnEntity(
                world.getEntityStore().getStore(),
                roleIndex,
                spawnPos,
                spawnRot,
                null,
                postSpawn
        );
        if (pair == null || pair.first() == null || !pair.first().isValid()) {
            return;
        }
    }

    private static void buildHomeForPlot(@Nonnull World world, @Nonnull Vector3i origin, @Nonnull String plotType) {
        // Phase 2: template strategy lives here. For now all workshop plots use the current base shape.
        buildDefaultWorkshopHome(world, origin);
    }

    private static void placeBlacksmithWorkshopSupportBlocks(@Nonnull World world) {
        world.setBlock(593, 181, -11, "Transparent_Light");
        world.setBlock(594, 183, -15, "Transparent_Light");
        world.setBlock(588, 183, -12, "Transparent_Light");
    }

    private static void teleportNpcToTransform(@Nonnull World world, @Nonnull String roleName, @Nonnull Transform targetTransform) {
        Ref<EntityStore> npcRef = findNpcByRole(world.getEntityStore().getStore(), roleName);
        if (npcRef == null || !npcRef.isValid()) {
            return;
        }
        Store<EntityStore> store = npcRef.getStore();
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
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

    private static void buildDefaultWorkshopHome(@Nonnull World world, @Nonnull Vector3i origin) {
        int ox = origin.x;
        int oy = origin.y;
        int oz = origin.z;

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(ox + x, oy, oz + z, BUILDING_BLOCK);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    boolean wall = x == -3 || x == 3 || z == -3 || z == 3;
                    boolean doorway = (z == -3 && y <= 2 && x >= -1 && x <= 1);
                    if (wall && !doorway) {
                        world.setBlock(ox + x, oy + y, oz + z, BUILDING_BLOCK);
                    } else {
                        world.setBlock(ox + x, oy + y, oz + z, "Empty");
                    }
                }
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(ox + x, oy + 4, oz + z, BUILDING_BLOCK);
            }
        }
    }

    @Nullable
    private static Ref<EntityStore> findNpcByRole(@Nonnull Store<EntityStore> store, @Nonnull String roleName) {
        final Ref<EntityStore>[] found = new Ref[]{null};
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            if (found[0] != null) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !roleName.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found[0] = ref;
                    return;
                }
            }
        });
        return found[0];
    }

    @Nonnull
    private static Collection<Ref<EntityStore>> findAllNpcByRole(@Nonnull Store<EntityStore> store, @Nonnull String roleName) {
        List<Ref<EntityStore>> found = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !roleName.equals(npc.getRoleName())) {
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

    private static boolean isNpcCloseTo(
            @Nonnull World world,
            @Nonnull String roleName,
            @Nonnull Vector3d targetPos,
            double maxDistanceSquared
    ) {
        Ref<EntityStore> npcRef = findNpcByRole(world.getEntityStore().getStore(), roleName);
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        TransformComponent transform = npcRef.getStore().getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return false;
        }
        Vector3d pos = transform.getPosition();
        double dx = pos.getX() - targetPos.getX();
        double dy = pos.getY() - targetPos.getY();
        double dz = pos.getZ() - targetPos.getZ();
        return dx * dx + dy * dy + dz * dz <= maxDistanceSquared;
    }

    private boolean isNpcRescued(@Nonnull String npcKey) {
        return NpcProgressManager.get().isNpcRescued(npcKey);
    }

    private boolean isSupportedProfession(@Nonnull String profession) {
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(profession);
        return archetype != null;
    }

    private boolean isPlotPurchaseUnlocked(@Nonnull String plotType) {
        List<String> npcKeys = getNpcKeysForPlotType(plotType);
        if (npcKeys.isEmpty()) {
            return true;
        }
        for (String npcKey : npcKeys) {
            NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
            if (archetype == null) {
                continue;
            }
            if (archetype.plotUnlockMode == NpcArchetype.PlotUnlockMode.NONE
                    || archetype.plotUnlockMode == NpcArchetype.PlotUnlockMode.MATERIALS
                    || archetype.plotUnlockMode == NpcArchetype.PlotUnlockMode.MATERIALS_OR_QUESTLINE) {
                return true;
            }
            if (archetype.plotUnlockMode == NpcArchetype.PlotUnlockMode.QUESTLINE) {
                if (archetype.prePlotQuestId != null && !archetype.prePlotQuestId.isBlank()) {
                    if (QuestProgressManager.get().isCompleted(archetype.prePlotQuestId)) {
                        return true;
                    }
                    continue;
                }
                return false;
            }
        }
        return false;
    }

    @Nonnull
    private List<String> getNpcKeysForPlotType(@Nonnull String plotType) {
        String normalizedPlotType = normalizeOrDefault(plotType, BLACKSMITH_PLOT_TYPE);
        List<String> keys = new ArrayList<>();
        for (NpcArchetype archetype : NpcDefinitionRegistry.get().getAll()) {
            if (archetype.plotType == null || archetype.plotType.isBlank()) {
                continue;
            }
            if (BLACKSMITH_KEY.equalsIgnoreCase(archetype.npcKey)) {
                continue;
            }
            if (normalizeOrDefault(archetype.plotType, "").equalsIgnoreCase(normalizedPlotType)) {
                keys.add(archetype.npcKey);
            }
        }
        return keys;
    }

    private boolean isNpcAssigned(@Nonnull String npcKey) {
        if (BLACKSMITH_KEY.equalsIgnoreCase(npcKey)) {
            HubNpcManager.NpcData blacksmithData = this.hubNpcManager.getOrCreate(BLACKSMITH_KEY);
            return blacksmithData.assignedPlotId != null;
        }
        for (PlotData plot : this.plots.values()) {
            if (npcKey.equals(plot.assignedNpcKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlacksmithAwaitingWorkshop(@Nonnull String npcKey) {
        String normalizedNpc = normalizeOrDefault(npcKey, "");
        return BLACKSMITH_KEY.equalsIgnoreCase(normalizedNpc)
                && !isBlacksmithWorkshopBuilt();
    }

    private boolean isBlacksmithWorkshopBuilt() {
        return NpcProgressManager.get().getOrCreate(BLACKSMITH_KEY).upgradeTier >= 1;
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        Path path = getConfigFilePath();
        if (path == null || !Files.exists(path)) {
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("[BaseHousing] Failed to load config: " + e.getMessage());
            return;
        }

        String ids = properties.getProperty("plots", "");
        if (ids.isBlank()) {
            return;
        }
        for (String rawId : ids.split(",")) {
            String id = rawId.trim();
            if (id.isEmpty()) {
                continue;
            }
            PlotData plot = readPlot(properties, id);
            if (plot != null) {
                this.plots.put(id, plot);
            }
        }
    }

    private synchronized void saveQuietly() {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }
        Properties properties = new Properties();
        String joinedIds = String.join(",", this.plots.keySet());
        properties.setProperty("plots", joinedIds);
        for (Map.Entry<String, PlotData> entry : this.plots.entrySet()) {
            writePlot(properties, entry.getValue());
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Base housing configuration");
            }
        } catch (IOException e) {
            System.out.println("[BaseHousing] Failed to save config: " + e.getMessage());
        }
    }

    @Nullable
    private static PlotData readPlot(@Nonnull Properties p, @Nonnull String id) {
        String prefix = "plot." + id + ".";
        String world = p.getProperty(prefix + "world");
        Integer mx = readInt(p.getProperty(prefix + "marker.x"));
        Integer my = readInt(p.getProperty(prefix + "marker.y"));
        Integer mz = readInt(p.getProperty(prefix + "marker.z"));
        Double hx = readDouble(p.getProperty(prefix + "home.pos.x"));
        Double hy = readDouble(p.getProperty(prefix + "home.pos.y"));
        Double hz = readDouble(p.getProperty(prefix + "home.pos.z"));
        Double hrx = readDouble(p.getProperty(prefix + "home.rot.x"));
        Double hry = readDouble(p.getProperty(prefix + "home.rot.y"));
        Double hrz = readDouble(p.getProperty(prefix + "home.rot.z"));
        if (world == null || mx == null || my == null || mz == null
                || hx == null || hy == null || hz == null || hrx == null || hry == null || hrz == null) {
            return null;
        }
        String plotType = normalizeOrDefault(p.getProperty(prefix + "plotType"), BLACKSMITH_PLOT_TYPE);
        String assigned = normalizeNullable(p.getProperty(prefix + "assignedNpc"));
        boolean purchased = Boolean.parseBoolean(p.getProperty(prefix + "purchased", "false"));
        boolean legacyBuilt = Boolean.parseBoolean(p.getProperty(prefix + "built", "false"));
        if (legacyBuilt) {
            purchased = true;
        }
        Integer buildingLevelRaw = readInt(p.getProperty(prefix + "buildingLevel"));
        int buildingLevel = buildingLevelRaw == null ? (purchased ? 1 : 0) : Math.max(0, buildingLevelRaw);
        Transform home = new Transform(
                new Vector3d(hx, hy, hz),
                new Vector3f(hrx.floatValue(), hry.floatValue(), hrz.floatValue())
        );
        return new PlotData(id, world, new Vector3i(mx, my, mz), home, plotType, purchased, assigned, buildingLevel);
    }

    private static void writePlot(@Nonnull Properties p, @Nonnull PlotData plot) {
        String prefix = "plot." + plot.id + ".";
        p.setProperty(prefix + "world", plot.worldName);
        p.setProperty(prefix + "marker.x", Integer.toString(plot.markerPos.x));
        p.setProperty(prefix + "marker.y", Integer.toString(plot.markerPos.y));
        p.setProperty(prefix + "marker.z", Integer.toString(plot.markerPos.z));
        p.setProperty(prefix + "home.pos.x", Double.toString(plot.homeTransform.getPosition().getX()));
        p.setProperty(prefix + "home.pos.y", Double.toString(plot.homeTransform.getPosition().getY()));
        p.setProperty(prefix + "home.pos.z", Double.toString(plot.homeTransform.getPosition().getZ()));
        p.setProperty(prefix + "home.rot.x", Float.toString(plot.homeTransform.getRotation().getX()));
        p.setProperty(prefix + "home.rot.y", Float.toString(plot.homeTransform.getRotation().getY()));
        p.setProperty(prefix + "home.rot.z", Float.toString(plot.homeTransform.getRotation().getZ()));
        p.setProperty(prefix + "plotType", plot.plotType);
        p.setProperty(prefix + "purchased", Boolean.toString(plot.purchased));
        p.setProperty(prefix + "assignedNpc", plot.assignedNpcKey == null ? "" : plot.assignedNpcKey);
        p.setProperty(prefix + "buildingLevel", Integer.toString(plot.buildingLevel));
    }

    @Nullable
    private static Path getConfigFilePath() {
        try {
            Path universePath = Universe.get().getPath();
            if (universePath == null) {
                return null;
            }
            return universePath.resolve("plugins").resolve(PLUGIN_CONFIG_DIR).resolve(CONFIG_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Integer readInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static Double readDouble(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static String normalizeNullable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nonnull
    private static String normalizeOrDefault(@Nullable String raw, @Nonnull String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed.toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static Transform copyTransform(@Nonnull Transform transform) {
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }

    public static final class AssignmentResult {
        public final boolean success;
        @Nonnull
        public final String message;

        private AssignmentResult(boolean success, @Nonnull String message) {
            this.success = success;
            this.message = message;
        }

        @Nonnull
        public static AssignmentResult ok(@Nonnull String message) {
            return new AssignmentResult(true, message);
        }

        @Nonnull
        public static AssignmentResult fail(@Nonnull String message) {
            return new AssignmentResult(false, message);
        }
    }

    public static final class PlotData {
        @Nonnull
        public final String id;
        @Nonnull
        public final String worldName;
        @Nonnull
        public final Vector3i markerPos;
        @Nonnull
        public final Transform homeTransform;
        @Nonnull
        public final String plotType;
        public final boolean purchased;
        @Nullable
        public final String assignedNpcKey;
        public final int buildingLevel;

        private PlotData(
                @Nonnull String id,
                @Nonnull String worldName,
                @Nonnull Vector3i markerPos,
                @Nonnull Transform homeTransform,
                @Nonnull String plotType,
                boolean purchased,
                @Nullable String assignedNpcKey,
                int buildingLevel
        ) {
            this.id = id;
            this.worldName = worldName;
            this.markerPos = markerPos;
            this.homeTransform = homeTransform;
            this.plotType = plotType;
            this.purchased = purchased;
            this.assignedNpcKey = assignedNpcKey;
            this.buildingLevel = buildingLevel;
        }

        @Nonnull
        private PlotData withHome(@Nonnull Transform home) {
            return new PlotData(
                    this.id,
                    this.worldName,
                    new Vector3i(this.markerPos),
                    home,
                    this.plotType,
                    this.purchased,
                    this.assignedNpcKey,
                    this.buildingLevel
            );
        }

        @Nonnull
        private PlotData withAssignment(@Nullable String npcKey, boolean purchased, int buildingLevel) {
            return new PlotData(
                    this.id,
                    this.worldName,
                    new Vector3i(this.markerPos),
                    copyTransform(this.homeTransform),
                    this.plotType,
                    purchased,
                    npcKey,
                    buildingLevel
            );
        }

        @Nonnull
        private PlotData withPlotType(@Nonnull String plotType) {
            return new PlotData(
                    this.id,
                    this.worldName,
                    new Vector3i(this.markerPos),
                    copyTransform(this.homeTransform),
                    normalizeOrDefault(plotType, BLACKSMITH_PLOT_TYPE),
                    this.purchased,
                    this.assignedNpcKey,
                    this.buildingLevel
            );
        }

        @Nonnull
        private PlotData copy() {
            return new PlotData(
                    this.id,
                    this.worldName,
                    new Vector3i(this.markerPos),
                    copyTransform(this.homeTransform),
                    this.plotType,
                    this.purchased,
                    this.assignedNpcKey,
                    this.buildingLevel
            );
        }
    }
}




