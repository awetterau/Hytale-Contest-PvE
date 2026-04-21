package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.state.transition.RegionSpawnMarkerConfigManager;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class RegionSpawnManager {
    private static final RegionSpawnManager INSTANCE = new RegionSpawnManager();
    private static final String EMPTY_BLOCK_ID = "Empty";

    private final ConcurrentHashMap<UUID, RunSpawnState> statesByWorld = new ConcurrentHashMap<>();

    private RegionSpawnManager() {
    }

    @Nonnull
    public static RegionSpawnManager get() {
        return INSTANCE;
    }

    public void initialize() {
        RegionSpawnConfig.get().initialize();
    }

    public void syncTemplateMarkersToConfig(@Nonnull String templateWorldName) {
        World templateWorld = Universe.get().getWorld(templateWorldName);
        if (templateWorld == null) {
            return;
        }

        RegionSpawnMarkerConfigManager.pruneToWorldState(templateWorldName, entry -> shouldKeepConfiguredMarker(entry, templateWorld));

        if (templateWorld.getWorldConfig() == null || templateWorld.getWorldConfig().getUuid() == null) {
            return;
        }
        List<RegionSpawnMarkerRegistry.Marker> detected = RegionSpawnMarkerRegistry.snapshot(templateWorld.getWorldConfig().getUuid());
        if (!detected.isEmpty()) {
            int added = mergeDetectedMarkers(templateWorldName, detected);
            System.out.println("[RegionSpawn] Synced template markers from live detection; world="
                    + templateWorldName + " detected=" + detected.size() + " added=" + added);
        }
    }

    public void clearWorld(@Nonnull UUID worldId) {
        this.statesByWorld.remove(worldId);
        RegionSpawnMarkerRegistry.clearWorld(worldId);
    }

    public void tick(@Nonnull Store<EntityStore> store, @Nonnull World world, @Nonnull GameSessionManager.ActiveSessionSnapshot snapshot) {
        RegionSpawnConfig config = RegionSpawnConfig.get();
        if (!config.isEnabled() || snapshot.runWorldUuid() == null || snapshot.startedAtEpochMillis() <= 0L) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            return;
        }

        RunSpawnState state = this.statesByWorld.computeIfAbsent(worldId, ignored -> new RunSpawnState());
        if (!state.initialized) {
            List<RegionSpawnMarkerRegistry.Marker> markers = loadConfiguredMarkers(snapshot.templateWorldName());
            int savedMarkerCount = markers.size();
            int detectedMarkerCount = 0;
            if (markers.isEmpty()) {
                List<RegionSpawnMarkerRegistry.Marker> detectedMarkers = RegionSpawnMarkerRegistry.snapshot(worldId);
                detectedMarkerCount = detectedMarkers.size();
                if (!detectedMarkers.isEmpty()) {
                    persistDetectedMarkers(snapshot.templateWorldName(), detectedMarkers);
                }
                markers = detectedMarkers;
            }
            if (markers.isEmpty()) {
                logMissingMarkers(state, worldId);
                return;
            }
            state.markers.addAll(markers);
            removeMarkerBlocks(world, markers);
            state.initialized = true;
            System.out.println("[RegionSpawn] Found markers=" + markers.size()
                    + " in world=" + world.getName()
                    + " saved=" + savedMarkerCount
                    + " detectedFallback=" + detectedMarkerCount);
        }
        removeMarkerBlocks(world, state.markers);
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - snapshot.startedAtEpochMillis()) / 1000L);
        if (!state.spawnedTiers.contains(1)) {
            spawnTier(store, world, worldId, state, 1);
        }
        if (elapsedSeconds >= config.tier2Second() && !state.spawnedTiers.contains(2)) {
            spawnTier(store, world, worldId, state, 2);
        }
        if (elapsedSeconds >= config.tier3Second() && !state.spawnedTiers.contains(3)) {
            spawnTier(store, world, worldId, state, 3);
        }
    }

    private void spawnTier(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull RunSpawnState state,
            int tier
    ) {
        RegionSpawnConfig config = RegionSpawnConfig.get();
        int spawned = 0;
        int skipped = 0;
        SpawnDebugStats debugStats = new SpawnDebugStats();

        for (String regionId : distinctRegionIds(state.markers)) {
            List<RegionSpawnMarkerRegistry.Marker> regionMarkers = markersForRegion(state.markers, regionId);
            MarkerPicker markerPicker = new MarkerPicker(regionMarkers);
            RegionSpawnConfig.RegionDefinition region = config.getRegion(regionId);
            if (region == null) {
                debugStats.missingRegion++;
                continue;
            }
            if (regionMarkers.isEmpty()) {
                debugStats.missingMarkerPool++;
                continue;
            }
            RegionSpawnConfig.SpawnTable table = config.getTable(config.tableForTier(region, tier));
            if (table == null) {
                debugStats.missingTable++;
                continue;
            }
            System.out.println("[RegionSpawn][Debug] tier=t" + tier
                    + " region=" + region.id()
                    + " markers=" + regionMarkers.size()
                    + " table=" + table.id()
                    + " entries=" + table.entries().size());
            for (RegionSpawnConfig.SpawnEntry entry : table.entries()) {
                int count = roll(entry.minCount(), entry.maxCount());
                System.out.println("[RegionSpawn][Debug] tier=t" + tier
                        + " region=" + region.id()
                        + " role=" + entry.roleId()
                        + " requested=" + count
                        + " range=" + entry.minCount() + "-" + entry.maxCount());
                for (int i = 0; i < count; i++) {
                    Vector3d spawnPos = chooseMarkerSpawnPosition(markerPicker, debugStats);
                    if (spawnNpc(store, entry.roleId(), spawnPos)) {
                        spawned++;
                        debugStats.spawnedByRole.merge(entry.roleId(), 1, Integer::sum);
                    } else {
                        skipped++;
                        debugStats.roleSpawnFailed++;
                        debugStats.failedRoles.add(entry.roleId());
                    }
                }
            }
        }

        if (tier == config.rooterTier() && !state.rooterSpawned) {
            if (trySpawnRooter(store, state, debugStats)) {
                spawned++;
                state.rooterSpawned = true;
                sendRunWorldMessage(worldId, "Rooter Man rises near the deepest corruption.");
            } else {
                skipped++;
                System.out.println("[RegionSpawn] Rooterman spawn skipped; no valid T3 marker point.");
            }
        }

        state.spawnedTiers.add(tier);
        System.out.println("[RegionSpawn] Spawned tier=t" + tier + " mobs=" + spawned + " skipped=" + skipped + " world=" + world.getName());
        System.out.println("[RegionSpawn][Debug] tier=t" + tier
                + " positionSelections=" + debugStats.positionSelections
                + " roleSpawnFailed=" + debugStats.roleSpawnFailed
                + " missingRegion=" + debugStats.missingRegion
                + " missingTable=" + debugStats.missingTable
                + " missingMarkerPool=" + debugStats.missingMarkerPool
                + " spawnedByRole=" + debugStats.spawnedByRole
                + " failedRoles=" + debugStats.failedRoles);
    }

    private boolean trySpawnRooter(
            @Nonnull Store<EntityStore> store,
            @Nonnull RunSpawnState state,
            @Nonnull SpawnDebugStats debugStats
    ) {
        RegionSpawnConfig config = RegionSpawnConfig.get();
        ArrayList<RegionSpawnMarkerRegistry.Marker> candidates = new ArrayList<>();
        for (RegionSpawnMarkerRegistry.Marker marker : state.markers) {
            RegionSpawnConfig.RegionDefinition region = config.getRegion(marker.regionId());
            if (region == null) {
                continue;
            }
            boolean regionMatches = "t3".equalsIgnoreCase(config.rooterRegion())
                    ? config.getTable(region.tableT3()) != null
                    : marker.regionId().equalsIgnoreCase(config.rooterRegion());
            if (regionMatches) {
                candidates.add(marker);
            }
        }
        if (candidates.isEmpty()) {
            debugStats.missingMarkerPool++;
            return false;
        }

        for (int i = 0; i < candidates.size(); i++) {
            RegionSpawnMarkerRegistry.Marker marker = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            Vector3d spawnPos = markerToSpawnPosition(marker.position(), debugStats);
            if (spawnNpc(store, config.rooterRole(), spawnPos)) {
                debugStats.spawnedByRole.merge(config.rooterRole(), 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static List<String> distinctRegionIds(@Nonnull List<RegionSpawnMarkerRegistry.Marker> markers) {
        LinkedHashSet<String> regionIds = new LinkedHashSet<>();
        for (RegionSpawnMarkerRegistry.Marker marker : markers) {
            regionIds.add(marker.regionId());
        }
        return List.copyOf(regionIds);
    }

    @Nonnull
    private static List<RegionSpawnMarkerRegistry.Marker> markersForRegion(
            @Nonnull List<RegionSpawnMarkerRegistry.Marker> markers,
            @Nonnull String regionId
    ) {
        ArrayList<RegionSpawnMarkerRegistry.Marker> out = new ArrayList<>();
        for (RegionSpawnMarkerRegistry.Marker marker : markers) {
            if (regionId.equalsIgnoreCase(marker.regionId())) {
                out.add(marker);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static Vector3d chooseMarkerSpawnPosition(
            @Nonnull MarkerPicker markerPicker,
            @Nonnull SpawnDebugStats debugStats
    ) {
        RegionSpawnMarkerRegistry.Marker marker = markerPicker.next();
        return markerToSpawnPosition(marker.position(), debugStats);
    }

    private static boolean spawnNpc(@Nonnull Store<EntityStore> store, @Nonnull String roleName, @Nonnull Vector3d spawnPos) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(roleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            System.out.println("[RegionSpawn] Role unavailable or not spawnable: " + roleName);
            return false;
        }
        Vector3f spawnRot = new Vector3f(0, ThreadLocalRandom.current().nextFloat() * 360.0f, 0);
        Pair<Ref<EntityStore>, NPCEntity> pair = npcPlugin.spawnEntity(store, roleIndex, spawnPos, spawnRot, null, null);
        return pair != null && pair.first() != null && pair.first().isValid();
    }

    private static int roll(int min, int max) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return ThreadLocalRandom.current().nextInt(Math.max(0, min), Math.max(0, max) + 1);
    }

    private static void removeMarkerBlocks(@Nonnull World world, @Nonnull List<RegionSpawnMarkerRegistry.Marker> markers) {
        for (RegionSpawnMarkerRegistry.Marker marker : markers) {
            Vector3i pos = marker.position();
            world.setBlock(pos.x, pos.y, pos.z, EMPTY_BLOCK_ID);
        }
    }

    private static void sendRunWorldMessage(@Nonnull UUID worldId, @Nonnull String text) {
        Message message = Message.raw(text);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorld = playerRef.getWorldUuid();
            if (worldId.equals(playerWorld)) {
                playerRef.sendMessage(message);
            }
        }
    }

    private static void logMissingMarkers(@Nonnull RunSpawnState state, @Nonnull UUID worldId) {
        long now = System.currentTimeMillis();
        if (now < state.nextMissingMarkerLogAt) {
            return;
        }
        state.nextMissingMarkerLogAt = now + 10_000L;
        System.out.println("[RegionSpawn] Waiting for region markers in run world=" + worldId);
    }

    @Nonnull
    private static List<RegionSpawnMarkerRegistry.Marker> loadConfiguredMarkers(@Nonnull String templateWorldName) {
        World templateWorld = Universe.get().getWorld(templateWorldName);
        RegionSpawnMarkerConfigManager.pruneToWorldState(templateWorldName, entry -> shouldKeepConfiguredMarker(entry, templateWorld));

        ArrayList<RegionSpawnMarkerRegistry.Marker> out = new ArrayList<>();
        for (RegionSpawnMarkerConfigManager.RegionMarkerEntry entry : RegionSpawnMarkerConfigManager.load(templateWorldName)) {
            out.add(new RegionSpawnMarkerRegistry.Marker(
                    entry.regionId(),
                    new Vector3i(entry.position().x, entry.position().y, entry.position().z)
            ));
        }
        return List.copyOf(out);
    }

    private static boolean shouldKeepConfiguredMarker(
            @Nonnull RegionSpawnMarkerConfigManager.RegionMarkerEntry entry,
            @Nullable World templateWorld
    ) {
        RegionSpawnConfig.RegionDefinition region = RegionSpawnConfig.get().getRegion(entry.regionId());
        if (region == null) {
            return false;
        }
        if (templateWorld == null) {
            return true;
        }
        Vector3i pos = entry.position();
        BlockType type = templateWorld.getBlockType(pos.x, pos.y, pos.z);
        return type != null && region.markerBlock().equalsIgnoreCase(type.getId());
    }

    private static void persistDetectedMarkers(
            @Nonnull String templateWorldName,
            @Nonnull List<RegionSpawnMarkerRegistry.Marker> markers
    ) {
        ArrayList<RegionSpawnMarkerConfigManager.RegionMarkerEntry> entries = new ArrayList<>();
        for (RegionSpawnMarkerRegistry.Marker marker : markers) {
            entries.add(new RegionSpawnMarkerConfigManager.RegionMarkerEntry(
                    marker.regionId(),
                    new Vector3i(marker.position().x, marker.position().y, marker.position().z),
                    templateWorldName
            ));
        }
        RegionSpawnMarkerConfigManager.save(templateWorldName, entries);
    }

    private static int mergeDetectedMarkers(
            @Nonnull String templateWorldName,
            @Nonnull List<RegionSpawnMarkerRegistry.Marker> markers
    ) {
        ArrayList<RegionSpawnMarkerConfigManager.RegionMarkerEntry> entries =
                new ArrayList<>(RegionSpawnMarkerConfigManager.load(templateWorldName));
        HashSet<String> keys = new HashSet<>();
        for (RegionSpawnMarkerConfigManager.RegionMarkerEntry entry : entries) {
            keys.add(entry.key());
        }

        int added = 0;
        for (RegionSpawnMarkerRegistry.Marker marker : markers) {
            RegionSpawnMarkerConfigManager.RegionMarkerEntry entry =
                    new RegionSpawnMarkerConfigManager.RegionMarkerEntry(
                            marker.regionId(),
                            new Vector3i(marker.position().x, marker.position().y, marker.position().z),
                            templateWorldName
                    );
            if (!keys.add(entry.key())) {
                continue;
            }
            entries.add(entry);
            added++;
        }
        if (added > 0) {
            RegionSpawnMarkerConfigManager.save(templateWorldName, entries);
        }
        return added;
    }

    private static final class MarkerPicker {
        private final ArrayList<RegionSpawnMarkerRegistry.Marker> markers;
        private int nextIndex;

        private MarkerPicker(@Nonnull List<RegionSpawnMarkerRegistry.Marker> markers) {
            this.markers = new ArrayList<>(markers);
            shuffle();
        }

        @Nonnull
        private RegionSpawnMarkerRegistry.Marker next() {
            if (this.markers.isEmpty()) {
                throw new IllegalStateException("Marker picker requires at least one marker.");
            }
            if (this.nextIndex >= this.markers.size()) {
                shuffle();
            }
            return this.markers.get(this.nextIndex++);
        }

        private void shuffle() {
            Collections.shuffle(this.markers, ThreadLocalRandom.current());
            this.nextIndex = 0;
        }
    }

    @Nonnull
    private static String formatPos(@Nonnull Vector3i pos) {
        return pos.x + "," + pos.y + "," + pos.z;
    }

    @Nonnull
    private static Vector3d markerToSpawnPosition(@Nonnull Vector3i pos, @Nonnull SpawnDebugStats debugStats) {
        debugStats.positionSelections++;
        return new Vector3d(pos.x + 0.5d, pos.y, pos.z + 0.5d);
    }

    private static final class RunSpawnState {
        private final ArrayList<RegionSpawnMarkerRegistry.Marker> markers = new ArrayList<>();
        private final Set<Integer> spawnedTiers = new HashSet<>();
        private boolean initialized;
        private boolean rooterSpawned;
        private long nextMissingMarkerLogAt;
    }

    private static final class SpawnDebugStats {
        private int positionSelections;
        private int roleSpawnFailed;
        private int missingRegion;
        private int missingTable;
        private int missingMarkerPool;
        private final ConcurrentHashMap<String, Integer> spawnedByRole = new ConcurrentHashMap<>();
        private final Set<String> failedRoles = ConcurrentHashMap.newKeySet();
    }
}
