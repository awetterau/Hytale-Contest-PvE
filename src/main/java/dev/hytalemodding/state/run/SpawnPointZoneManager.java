package dev.hytalemodding.state.run;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.SpawnPointZoneConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class SpawnPointZoneManager {
    private static final double PREVIOUS_LOCATION_WEIGHT = 0.35d;
    private static final List<String> EDITABLE_WORLD_NAMES = List.of("game");
    private static volatile int activeZoneIndex = 0;
    private static volatile int activeLocationIndex = 0;
    private static volatile int zoneCount = SpawnPointZoneConfigManager.DEFAULT_ZONE_COUNT;
    private static volatile LinkedHashMap<Integer, Integer> locationCountByZone = defaultLocationCounts(zoneCount);
    private static volatile String activeWorldName;
    private static volatile LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>>> zonesByActiveWorld;
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Integer>> LAST_LOCATION_BY_PLAYER_AND_ZONE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ReservedSpawn> RESERVED_SPAWN_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UUID> RESERVED_SPAWN_KEYS = new ConcurrentHashMap<>();

    private SpawnPointZoneManager() {
    }

    public static void refreshForPlayer(@Nonnull PlayerRef playerRef) {
        String playerWorldName = resolvePlayerWorldName(playerRef);
        String targetWorldName = resolveEditableTargetWorldName(playerWorldName);
        ensureLoaded(targetWorldName, true);

        if (!isEditableWorld(playerWorldName)) {
            normalizeActiveSelection();
            return;
        }

        UUID worldId = playerRef.getWorldUuid();
        if (worldId == null) {
            return;
        }

        World world = Universe.get().getWorld(worldId);
        reconcileWithWorldState(targetWorldName, worldId, world);
        normalizeActiveSelection();
    }

    public static void setActiveZone(@Nonnull PlayerRef playerRef, int zoneIndex) {
        refreshForPlayer(playerRef);
        activeZoneIndex = clampZoneIndex(zoneIndex);
        activeLocationIndex = 0;
        playerRef.sendMessage(Message.raw("Spawn zone selected: " + getPlacementSelectionLabel() + " (world: " + activeWorldName + ")"));
    }

    public static void setActiveLocation(@Nonnull PlayerRef playerRef, int locationIndex) {
        refreshForPlayer(playerRef);
        activeLocationIndex = clampLocationIndex(getActiveZoneIndex(), locationIndex);
        playerRef.sendMessage(Message.raw("Spawn location selected: " + getPlacementSelectionLabel() + " (world: " + activeWorldName + ")"));
    }

    public static void addZone(@Nonnull PlayerRef playerRef) {
        refreshForPlayer(playerRef);
        zoneCount++;
        locationCountByZone.put(zoneCount - 1, SpawnPointZoneConfigManager.DEFAULT_LOCATION_COUNT);
        zonesByActiveWorld.put(zoneCount - 1, SpawnPointZoneConfigManager.emptyLocationMap(SpawnPointZoneConfigManager.DEFAULT_LOCATION_COUNT));
        activeZoneIndex = zoneCount - 1;
        activeLocationIndex = 0;
        saveActiveWorld();
        playerRef.sendMessage(Message.raw("Added " + getFormattedZoneLabel(activeZoneIndex) + "."));
    }

    public static void removeZone(@Nonnull PlayerRef playerRef) {
        refreshForPlayer(playerRef);
        if (zoneCount <= 1) {
            playerRef.sendMessage(Message.raw("At least one zone must remain."));
            return;
        }
        int removedZone = zoneCount - 1;
        zonesByActiveWorld.remove(removedZone);
        locationCountByZone.remove(removedZone);
        zoneCount--;
        activeZoneIndex = clampZoneIndex(activeZoneIndex);
        activeLocationIndex = clampLocationIndex(activeZoneIndex, activeLocationIndex);
        DoorRunZoneSelectionManager.clearInvalidSelections(zoneCount);
        saveActiveWorld();
        playerRef.sendMessage(Message.raw("Removed " + getFormattedZoneLabel(removedZone) + "."));
    }

    public static void addLocation(@Nonnull PlayerRef playerRef) {
        refreshForPlayer(playerRef);
        int zoneIndex = getActiveZoneIndex();
        int locationCount = getLocationCount(zoneIndex) + 1;
        locationCountByZone.put(zoneIndex, locationCount);
        zonesByActiveWorld.computeIfAbsent(zoneIndex, ignored -> SpawnPointZoneConfigManager.emptyLocationMap(locationCount))
                .put(locationCount - 1, new ArrayList<>());
        activeLocationIndex = locationCount - 1;
        saveActiveWorld();
        playerRef.sendMessage(Message.raw("Added " + getFormattedLocationLabel(zoneIndex, activeLocationIndex) + "."));
    }

    public static void removeLocation(@Nonnull PlayerRef playerRef) {
        refreshForPlayer(playerRef);
        int zoneIndex = getActiveZoneIndex();
        int locationCount = getLocationCount(zoneIndex);
        if (locationCount <= 1) {
            playerRef.sendMessage(Message.raw("At least one location must remain in " + getFormattedZoneLabel(zoneIndex) + "."));
            return;
        }
        int removedLocation = locationCount - 1;
        LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap = zonesByActiveWorld.get(zoneIndex);
        if (locationMap != null) {
            locationMap.remove(removedLocation);
        }
        locationCountByZone.put(zoneIndex, locationCount - 1);
        activeLocationIndex = clampLocationIndex(zoneIndex, activeLocationIndex);
        saveActiveWorld();
        playerRef.sendMessage(Message.raw("Removed " + getFormattedLocationLabel(zoneIndex, removedLocation) + "."));
    }

    public static int getActiveZoneIndex() {
        return clampZoneIndex(activeZoneIndex);
    }

    public static int getActiveLocationIndex() {
        return clampLocationIndex(getActiveZoneIndex(), activeLocationIndex);
    }

    public static int getZoneCount() {
        return Math.max(1, zoneCount);
    }

    public static int getLocationCount(int zoneIndex) {
        return Math.max(1, locationCountByZone.getOrDefault(clampZoneIndex(zoneIndex), SpawnPointZoneConfigManager.DEFAULT_LOCATION_COUNT));
    }

    public static boolean hasRegisteredSpawnInZone(int zoneIndex) {
        var zones = zonesByActiveWorld;
        if (zones == null) {
            return false;
        }

        LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap = zones.get(clampZoneIndex(zoneIndex));
        if (locationMap == null) {
            return false;
        }

        int locationCount = getLocationCount(zoneIndex);
        for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
            ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> entries = locationMap.get(locationIndex);
            if (entries != null && !entries.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static Integer getFirstZoneWithRegisteredSpawns() {
        int safeZoneCount = getZoneCount();
        for (int zoneIndex = 0; zoneIndex < safeZoneCount; zoneIndex++) {
            if (hasRegisteredSpawnInZone(zoneIndex)) {
                return zoneIndex;
            }
        }
        return null;
    }

    @Nonnull
    public static String getActiveZoneLabel() {
        return formatZone(getActiveZoneIndex());
    }

    @Nonnull
    public static String getActiveLocationLabel() {
        return formatLocation(getActiveZoneIndex(), getActiveLocationIndex());
    }

    @Nonnull
    public static String getPlacementSelectionLabel() {
        return getActiveZoneLabel() + " / " + getActiveLocationLabel();
    }

    @Nonnull
    public static List<String> getEditableWorldNames() {
        return EDITABLE_WORLD_NAMES;
    }

    public static boolean isEditableWorld(@Nullable World world) {
        return world != null && isEditableWorld(world.getName());
    }

    public static boolean isEditableWorld(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        for (String editableWorldName : EDITABLE_WORLD_NAMES) {
            if (editableWorldName.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String resolveEditableTargetWorldName(@Nullable String preferredWorldName) {
        if (isEditableWorld(preferredWorldName)) {
            return preferredWorldName;
        }
        if (!EDITABLE_WORLD_NAMES.isEmpty()) {
            return EDITABLE_WORLD_NAMES.get(0);
        }
        String templateWorldName = GameFlowConfigManager.get().getTemplateWorldName();
        return templateWorldName == null ? "default" : templateWorldName.toLowerCase(Locale.ROOT);
    }

    public static boolean registerPlacement(@Nonnull Vector3i position) {
        String worldName = activeWorldName;
        if (worldName == null || worldName.isBlank()) {
            worldName = resolveEditableTargetWorldName(GameFlowConfigManager.get().getTemplateWorldName());
            ensureLoaded(worldName, false);
        }

        if (!isEditableWorld(worldName)) {
            return false;
        }

        var zones = zonesByActiveWorld;
        if (zones == null) {
            ensureLoaded(worldName, false);
            zones = zonesByActiveWorld;
        }
        if (zones == null) {
            return false;
        }

        int zoneIndex = getActiveZoneIndex();
        int locationIndex = getActiveLocationIndex();
        SpawnPointZoneConfigManager.SpawnPointEntry entry =
                new SpawnPointZoneConfigManager.SpawnPointEntry(new Vector3i(position.x, position.y, position.z), worldName);
        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> list = getEntryList(zones, zoneIndex, locationIndex);
        if (!contains(list, entry)) {
            list.add(entry);
            saveActiveWorld();
        }

        Universe.get().sendMessage(Message.raw("[Spawn Zones] SpawnPoint_Block registered in " + formatLocation(zoneIndex, locationIndex)
                + " -> " + position.x + "," + position.y + "," + position.z
                + " (world=" + worldName + ")"));
        return true;
    }

    public record SpawnSelectionResult(
            @Nonnull Transform transform,
            int locationIndex,
            @Nonnull Vector3i spawnPosition
    ) {
    }

    private record ReservedSpawn(
            @Nonnull String worldName,
            int zoneIndex,
            int locationIndex,
            @Nonnull Vector3i spawnPosition
    ) {
        @Nonnull
        private String reservationKey() {
            return buildReservationKey(this.worldName, this.zoneIndex, this.locationIndex, this.spawnPosition);
        }
    }

    @Nullable
    public static SpawnSelectionResult reserveRandomSpawnForPlayer(
            @Nonnull World world,
            int zoneIndex,
            @Nonnull UUID playerId,
            @Nullable Transform referenceTransform
    ) {
        ensureLoaded(world.getName(), true);
        pruneRemovedEntries(world.getName(), world);
        releaseReservedSpawn(playerId);

        var zones = zonesByActiveWorld;
        if (zones == null) {
            return null;
        }

        int safeZoneIndex = clampZoneIndex(zoneIndex);
        LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap = zones.get(safeZoneIndex);
        if (locationMap == null) {
            return null;
        }

        ArrayList<Integer> candidateLocations = new ArrayList<>();
        int locationCount = getLocationCount(safeZoneIndex);
        for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
            ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> entries = locationMap.getOrDefault(locationIndex, new ArrayList<>());
            if (!entries.isEmpty()) {
                candidateLocations.add(locationIndex);
            }
        }
        if (candidateLocations.isEmpty()) {
            return null;
        }

        Integer previousLocation = getPreviousLocationForPlayer(playerId, safeZoneIndex);
        ArrayList<Integer> locationOrder = buildWeightedLocationOrder(candidateLocations, previousLocation);
        for (int locationIndex : locationOrder) {
            SpawnSelectionResult result = reserveSpawnInLocation(world.getName(), safeZoneIndex, locationIndex, playerId, locationMap, referenceTransform);
            if (result != null) {
                rememberPreviousLocation(playerId, safeZoneIndex, locationIndex);
                return result;
            }
        }
        return null;
    }

    public static void releaseReservedSpawn(@Nonnull UUID playerId) {
        ReservedSpawn reservation = RESERVED_SPAWN_BY_PLAYER.remove(playerId);
        if (reservation == null) {
            return;
        }
        RESERVED_SPAWN_KEYS.remove(reservation.reservationKey(), playerId);
    }

    @Nullable
    private static SpawnSelectionResult reserveSpawnInLocation(
            @Nonnull String worldName,
            int zoneIndex,
            int locationIndex,
            @Nonnull UUID playerId,
            @Nonnull LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap,
            @Nullable Transform referenceTransform
    ) {
        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> entries = locationMap.get(locationIndex);
        if (entries == null || entries.isEmpty()) {
            return null;
        }

        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> availableEntries = new ArrayList<>();
        for (SpawnPointZoneConfigManager.SpawnPointEntry entry : entries) {
            String reservationKey = buildReservationKey(worldName, zoneIndex, locationIndex, entry.position());
            if (!RESERVED_SPAWN_KEYS.containsKey(reservationKey)) {
                availableEntries.add(entry);
            }
        }
        if (availableEntries.isEmpty()) {
            return null;
        }

        SpawnPointZoneConfigManager.SpawnPointEntry entry = availableEntries.get(ThreadLocalRandom.current().nextInt(availableEntries.size()));
        ReservedSpawn reservedSpawn = new ReservedSpawn(worldName, zoneIndex, locationIndex, new Vector3i(entry.position().x, entry.position().y, entry.position().z));
        String reservationKey = reservedSpawn.reservationKey();
        if (RESERVED_SPAWN_KEYS.putIfAbsent(reservationKey, playerId) != null) {
            return reserveSpawnInLocation(worldName, zoneIndex, locationIndex, playerId, locationMap, referenceTransform);
        }
        RESERVED_SPAWN_BY_PLAYER.put(playerId, reservedSpawn);

        Vector3f rotation = referenceTransform == null
                ? new Vector3f(0.0f, 0.0f, 0.0f)
                : new Vector3f(referenceTransform.getRotation());
        Transform transform = new Transform(
                new Vector3d(entry.position().x + 0.5d, entry.position().y + 1.0d, entry.position().z + 0.5d),
                rotation
        );
        return new SpawnSelectionResult(transform, locationIndex, new Vector3i(entry.position().x, entry.position().y, entry.position().z));
    }

    @Nonnull
    private static ArrayList<Integer> buildWeightedLocationOrder(
            @Nonnull List<Integer> candidateLocations,
            @Nullable Integer previousLocation
    ) {
        ArrayList<LocationRoll> rolls = new ArrayList<>();
        boolean penalizePrevious = previousLocation != null && candidateLocations.size() > 1 && candidateLocations.contains(previousLocation);
        for (int locationIndex : candidateLocations) {
            double weight = (!penalizePrevious || locationIndex != previousLocation.intValue()) ? 1.0d : PREVIOUS_LOCATION_WEIGHT;
            double roll = -Math.log(Math.max(1.0e-9d, ThreadLocalRandom.current().nextDouble())) / weight;
            rolls.add(new LocationRoll(locationIndex, roll));
        }
        rolls.sort(Comparator.comparingDouble(LocationRoll::roll));
        ArrayList<Integer> out = new ArrayList<>(rolls.size());
        for (LocationRoll roll : rolls) {
            out.add(roll.locationIndex());
        }
        return out;
    }

    private record LocationRoll(int locationIndex, double roll) {
    }

    @Nullable
    private static Integer getPreviousLocationForPlayer(@Nonnull UUID playerId, int zoneIndex) {
        ConcurrentHashMap<Integer, Integer> byZone = LAST_LOCATION_BY_PLAYER_AND_ZONE.get(playerId);
        return byZone == null ? null : byZone.get(zoneIndex);
    }

    private static void rememberPreviousLocation(@Nonnull UUID playerId, int zoneIndex, int locationIndex) {
        LAST_LOCATION_BY_PLAYER_AND_ZONE.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(zoneIndex, locationIndex);
    }

    @Nonnull
    private static String buildReservationKey(@Nonnull String worldName, int zoneIndex, int locationIndex, @Nonnull Vector3i spawnPosition) {
        return worldName + '|' + zoneIndex + '|' + locationIndex + '|' + spawnPosition.x + ':' + spawnPosition.y + ':' + spawnPosition.z;
    }

    public static int getCountForLocation(int zoneIndex, int locationIndex) {
        ensureLoaded(activeWorldNameOrDefault(), false);
        var zones = zonesByActiveWorld;
        if (zones == null) {
            return 0;
        }
        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> list = getEntryList(zones, clampZoneIndex(zoneIndex), clampLocationIndex(zoneIndex, locationIndex));
        return list.size();
    }

    @Nonnull
    public static String getListText(int zoneIndex, int locationIndex) {
        ensureLoaded(activeWorldNameOrDefault(), false);
        var zones = zonesByActiveWorld;
        if (zones == null) {
            return "(none)";
        }
        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> list = getEntryList(zones, clampZoneIndex(zoneIndex), clampLocationIndex(zoneIndex, locationIndex));
        if (list.isEmpty()) {
            return "(none)";
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.get(i);
            if (i > 0) {
                out.append("\n");
            }
            out.append(i + 1).append(") ")
                    .append(entry.position().x).append(",")
                    .append(entry.position().y).append(",")
                    .append(entry.position().z)
                    .append(" | ").append(entry.dimension());
        }
        return out.toString();
    }

    private static void pruneRemovedEntries(@Nonnull String worldName, @Nullable World world) {
        var zones = zonesByActiveWorld;
        if (zones == null || world == null) {
            return;
        }

        boolean changed = false;
        for (int zoneIndex = 0; zoneIndex < zoneCount; zoneIndex++) {
            int locationCount = getLocationCount(zoneIndex);
            for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
                ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> list = getEntryList(zones, zoneIndex, locationIndex);
                for (int i = list.size() - 1; i >= 0; i--) {
                    var entry = list.get(i);
                    if (!entry.dimension().equalsIgnoreCase(worldName)) {
                        continue;
                    }
                    BlockType type = world.getBlockType(entry.position().x, entry.position().y, entry.position().z);
                    if (type != null && !"SpawnPoint_Block".equalsIgnoreCase(type.getId())) {
                        list.remove(i);
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            saveActiveWorld();
        }
    }

    private static void reconcileWithWorldState(@Nonnull String worldName, @Nonnull UUID worldId, @Nullable World world) {
        var zones = zonesByActiveWorld;
        if (zones == null) {
            return;
        }

        List<Vector3i> detected = SpawnPointRegistry.snapshot(worldId);
        HashSet<String> listedKeys = new HashSet<>();
        boolean changed = false;
        for (int zoneIndex = 0; zoneIndex < zoneCount; zoneIndex++) {
            int locationCount = getLocationCount(zoneIndex);
            for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
                ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> list = getEntryList(zones, zoneIndex, locationIndex);
                for (int i = list.size() - 1; i >= 0; i--) {
                    var entry = list.get(i);
                    if (!entry.dimension().equalsIgnoreCase(worldName)) {
                        continue;
                    }
                    String key = entry.position().x + ":" + entry.position().y + ":" + entry.position().z;
                    listedKeys.add(key);

                    if (world == null) {
                        continue;
                    }

                    BlockType type = world.getBlockType(entry.position().x, entry.position().y, entry.position().z);
                    if (type != null && !"SpawnPoint_Block".equalsIgnoreCase(type.getId())) {
                        list.remove(i);
                        listedKeys.remove(key);
                        changed = true;
                    }
                }
            }
        }

        int zoneIndex = getActiveZoneIndex();
        int locationIndex = getActiveLocationIndex();
        ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> target = getEntryList(zones, zoneIndex, locationIndex);

        for (Vector3i pos : detected) {
            String key = pos.x + ":" + pos.y + ":" + pos.z;
            if (listedKeys.contains(key)) {
                continue;
            }
            target.add(new SpawnPointZoneConfigManager.SpawnPointEntry(new Vector3i(pos.x, pos.y, pos.z), worldName));
            changed = true;
        }

        if (changed) {
            saveActiveWorld();
        }
    }

    @Nonnull
    private static String resolvePlayerWorldName(@Nonnull PlayerRef playerRef) {
        if (playerRef.getWorldUuid() != null) {
            var world = Universe.get().getWorld(playerRef.getWorldUuid());
            if (world != null && world.getName() != null && !world.getName().isBlank()) {
                return world.getName();
            }
        }
        return GameFlowConfigManager.get().getTemplateWorldName();
    }

    @Nonnull
    private static String activeWorldNameOrDefault() {
        String worldName = activeWorldName;
        return (worldName == null || worldName.isBlank()) ? GameFlowConfigManager.get().getTemplateWorldName() : worldName;
    }

    private static synchronized void ensureLoaded(@Nonnull String worldName, boolean forceReload) {
        if (!forceReload && worldName.equalsIgnoreCase(activeWorldName) && zonesByActiveWorld != null) {
            return;
        }
        activeWorldName = worldName;
        SpawnPointZoneConfigManager.SpawnZoneState state = SpawnPointZoneConfigManager.load(worldName);
        zoneCount = Math.max(1, state.zoneCount());
        locationCountByZone = new LinkedHashMap<>(state.locationCountByZone());
        zonesByActiveWorld = state.zones();
        normalizeActiveSelection();
    }

    private static void saveActiveWorld() {
        String worldName = activeWorldNameOrDefault();
        if (zonesByActiveWorld == null) {
            zonesByActiveWorld = SpawnPointZoneConfigManager.emptyZoneMap(zoneCount, locationCountByZone);
        }
        SpawnPointZoneConfigManager.save(worldName, new SpawnPointZoneConfigManager.SpawnZoneState(zoneCount, locationCountByZone, zonesByActiveWorld));
    }

    private static void normalizeActiveSelection() {
        zoneCount = Math.max(1, zoneCount);
        if (locationCountByZone == null) {
            locationCountByZone = defaultLocationCounts(zoneCount);
        }
        activeZoneIndex = clampZoneIndex(activeZoneIndex);
        if (zonesByActiveWorld == null) {
            zonesByActiveWorld = SpawnPointZoneConfigManager.emptyZoneMap(zoneCount, locationCountByZone);
        }

        for (int zoneIndex = 0; zoneIndex < zoneCount; zoneIndex++) {
            int locationCount = Math.max(1, locationCountByZone.getOrDefault(zoneIndex, SpawnPointZoneConfigManager.DEFAULT_LOCATION_COUNT));
            locationCountByZone.put(zoneIndex, locationCount);
            LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap =
                    zonesByActiveWorld.computeIfAbsent(zoneIndex, ignored -> SpawnPointZoneConfigManager.emptyLocationMap(locationCount));
            for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
                locationMap.computeIfAbsent(locationIndex, ignored -> new ArrayList<>());
            }
            final int maxLocationCount = locationCount;
            locationMap.keySet().removeIf(locationIndex -> locationIndex < 0 || locationIndex >= maxLocationCount);
        }
        zonesByActiveWorld.keySet().removeIf(zoneIndex -> zoneIndex < 0 || zoneIndex >= zoneCount);
        locationCountByZone.keySet().removeIf(zoneIndex -> zoneIndex < 0 || zoneIndex >= zoneCount);
        activeLocationIndex = clampLocationIndex(activeZoneIndex, activeLocationIndex);
    }

    @Nonnull
    private static ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> getEntryList(
            @Nonnull LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>>> zones,
            int zoneIndex,
            int locationIndex
    ) {
        int safeZoneIndex = clampZoneIndex(zoneIndex);
        int locationCount = getLocationCount(safeZoneIndex);
        LinkedHashMap<Integer, ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry>> locationMap =
                zones.computeIfAbsent(safeZoneIndex, ignored -> SpawnPointZoneConfigManager.emptyLocationMap(locationCount));
        return locationMap.computeIfAbsent(clampLocationIndex(safeZoneIndex, locationIndex), ignored -> new ArrayList<>());
    }

    private static boolean contains(
            @Nonnull ArrayList<SpawnPointZoneConfigManager.SpawnPointEntry> list,
            @Nonnull SpawnPointZoneConfigManager.SpawnPointEntry target
    ) {
        String key = target.key();
        for (SpawnPointZoneConfigManager.SpawnPointEntry value : list) {
            if (value.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static String getFormattedZoneLabel(int zoneIndex) {
        return formatZone(clampZoneIndex(zoneIndex));
    }

    public static String getFormattedLocationLabel(int zoneIndex, int locationIndex) {
        return formatLocation(clampZoneIndex(zoneIndex), clampLocationIndex(zoneIndex, locationIndex));
    }

    @Nonnull
    private static String formatZone(int zoneIndex) {
        return "Zone " + toAlphabetLabel(zoneIndex);
    }

    @Nonnull
    private static String formatLocation(int zoneIndex, int locationIndex) {
        return toAlphabetLabel(zoneIndex) + (locationIndex + 1);
    }

    @Nonnull
    private static String toAlphabetLabel(int index) {
        int value = Math.max(0, index);
        StringBuilder out = new StringBuilder();
        do {
            out.insert(0, (char) ('A' + (value % 26)));
            value = (value / 26) - 1;
        } while (value >= 0);
        return out.toString();
    }

    @Nonnull
    private static LinkedHashMap<Integer, Integer> defaultLocationCounts(int zoneCount) {
        LinkedHashMap<Integer, Integer> out = new LinkedHashMap<>();
        for (int zoneIndex = 0; zoneIndex < Math.max(1, zoneCount); zoneIndex++) {
            out.put(zoneIndex, SpawnPointZoneConfigManager.DEFAULT_LOCATION_COUNT);
        }
        return out;
    }

    private static int clampZoneIndex(int zoneIndex) {
        return clampIndex(zoneIndex, zoneCount);
    }

    private static int clampLocationIndex(int zoneIndex, int locationIndex) {
        return clampIndex(locationIndex, getLocationCount(zoneIndex));
    }

    private static int clampIndex(int index, int count) {
        int safeCount = Math.max(1, count);
        if (index < 0) {
            return 0;
        }
        if (index >= safeCount) {
            return safeCount - 1;
        }
        return index;
    }
}