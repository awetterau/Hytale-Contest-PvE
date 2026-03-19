package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

public final class SpawnPointZoneConfigManager {
    public static final String CONFIG_FILE_NAME = "SpawnPoint_Zones.properties";
    public static final int DEFAULT_ZONE_COUNT = 3;
    public static final int DEFAULT_LOCATION_COUNT = 3;

    public record SpawnPointEntry(@Nonnull Vector3i position, @Nonnull String dimension) {
        @Nonnull
        public String key() {
            return this.dimension + ":" + this.position.x + ":" + this.position.y + ":" + this.position.z;
        }
    }

    public record SpawnZoneState(
            int zoneCount,
            @Nonnull LinkedHashMap<Integer, Integer> locationCountByZone,
            @Nonnull LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointEntry>>> zones
    ) {
    }

    private SpawnPointZoneConfigManager() {
    }

    @Nonnull
    public static SpawnZoneState load(@Nonnull String worldKey) {
        Properties properties = loadProperties();
        String worldPrefix = "world." + sanitizeWorldKey(worldKey) + ".";

        int zoneCount = Math.max(1, parseInt(properties.getProperty(worldPrefix + "zoneCount"), DEFAULT_ZONE_COUNT));
        int legacyLocationCount = Math.max(1, parseInt(properties.getProperty(worldPrefix + "locationCount"), DEFAULT_LOCATION_COUNT));
        LinkedHashMap<Integer, Integer> locationCountByZone = new LinkedHashMap<>();
        for (int zoneIndex = 0; zoneIndex < zoneCount; zoneIndex++) {
            int perZoneCount = Math.max(1, parseInt(properties.getProperty(worldPrefix + "zone." + zoneIndex + ".locationCount"), legacyLocationCount));
            locationCountByZone.put(zoneIndex, perZoneCount);
        }

        LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointEntry>>> zones = emptyZoneMap(zoneCount, locationCountByZone);

        boolean loadedDynamicData = false;
        for (int zoneIndex = 0; zoneIndex < zoneCount; zoneIndex++) {
            int locationCount = locationCountByZone.getOrDefault(zoneIndex, DEFAULT_LOCATION_COUNT);
            for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
                String locationPrefix = worldPrefix + "zone." + zoneIndex + ".location." + locationIndex + ".";
                Integer count = parseInt(properties.getProperty(locationPrefix + "count"));
                if (count == null || count <= 0) {
                    continue;
                }
                loadedDynamicData = true;
                ArrayList<SpawnPointEntry> entries = zones.get(zoneIndex).get(locationIndex);
                loadEntries(properties, locationPrefix, count, entries, worldKey);
                sortEntries(entries);
            }
        }

        if (!loadedDynamicData) {
            loadLegacy(worldKey, properties, worldPrefix, zones, locationCountByZone);
        }

        return new SpawnZoneState(zoneCount, locationCountByZone, zones);
    }

    public static void save(@Nonnull String worldKey, @Nonnull SpawnZoneState state) {
        Properties properties = loadProperties();
        String worldPrefix = "world." + sanitizeWorldKey(worldKey) + ".";

        ArrayList<String> keys = new ArrayList<>(properties.stringPropertyNames());
        for (String key : keys) {
            if (key.startsWith(worldPrefix)) {
                properties.remove(key);
            }
        }

        properties.setProperty(worldPrefix + "zoneCount", Integer.toString(Math.max(1, state.zoneCount())));

        for (int zoneIndex = 0; zoneIndex < Math.max(1, state.zoneCount()); zoneIndex++) {
            int locationCount = Math.max(1, state.locationCountByZone().getOrDefault(zoneIndex, DEFAULT_LOCATION_COUNT));
            properties.setProperty(worldPrefix + "zone." + zoneIndex + ".locationCount", Integer.toString(locationCount));
            LinkedHashMap<Integer, ArrayList<SpawnPointEntry>> locationMap = state.zones().getOrDefault(zoneIndex, emptyLocationMap(locationCount));
            for (int locationIndex = 0; locationIndex < locationCount; locationIndex++) {
                ArrayList<SpawnPointEntry> entries = dedupeAndSort(locationMap.getOrDefault(locationIndex, new ArrayList<>()), worldKey);
                String locationPrefix = worldPrefix + "zone." + zoneIndex + ".location." + locationIndex + ".";
                properties.setProperty(locationPrefix + "count", Integer.toString(entries.size()));
                for (int i = 0; i < entries.size(); i++) {
                    SpawnPointEntry entry = entries.get(i);
                    String base = locationPrefix + i;
                    properties.setProperty(base + ".x", Integer.toString(entry.position().x));
                    properties.setProperty(base + ".y", Integer.toString(entry.position().y));
                    properties.setProperty(base + ".z", Integer.toString(entry.position().z));
                    properties.setProperty(base + ".dimension", normalizeDimension(entry.dimension(), worldKey));
                }
            }
        }

        saveProperties(properties);
    }

    @Nonnull
    public static LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointEntry>>> emptyZoneMap(
            int zoneCount,
            @Nonnull LinkedHashMap<Integer, Integer> locationCountByZone
    ) {
        LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointEntry>>> zones = new LinkedHashMap<>();
        int safeZoneCount = Math.max(1, zoneCount);
        for (int zoneIndex = 0; zoneIndex < safeZoneCount; zoneIndex++) {
            zones.put(zoneIndex, emptyLocationMap(locationCountByZone.getOrDefault(zoneIndex, DEFAULT_LOCATION_COUNT)));
        }
        return zones;
    }

    @Nonnull
    public static LinkedHashMap<Integer, ArrayList<SpawnPointEntry>> emptyLocationMap(int locationCount) {
        LinkedHashMap<Integer, ArrayList<SpawnPointEntry>> locations = new LinkedHashMap<>();
        int safeLocationCount = Math.max(1, locationCount);
        for (int locationIndex = 0; locationIndex < safeLocationCount; locationIndex++) {
            locations.put(locationIndex, new ArrayList<>());
        }
        return locations;
    }

    private static void loadLegacy(
            @Nonnull String worldKey,
            @Nonnull Properties properties,
            @Nonnull String worldPrefix,
            @Nonnull LinkedHashMap<Integer, LinkedHashMap<Integer, ArrayList<SpawnPointEntry>>> zones,
            @Nonnull LinkedHashMap<Integer, Integer> locationCountByZone
    ) {
        String[] legacyZoneKeys = {"zoneA", "zoneB", "zoneC"};
        String[] legacyLocationKeys = {"location1", "location2", "location3"};

        for (int zoneIndex = 0; zoneIndex < legacyZoneKeys.length && zoneIndex < zones.size(); zoneIndex++) {
            int zoneLocationCount = locationCountByZone.getOrDefault(zoneIndex, DEFAULT_LOCATION_COUNT);
            boolean loadedAnyLocation = false;
            for (int locationIndex = 0; locationIndex < legacyLocationKeys.length && locationIndex < zoneLocationCount; locationIndex++) {
                String locationPrefix = worldPrefix + legacyZoneKeys[zoneIndex] + "." + legacyLocationKeys[locationIndex] + ".";
                Integer count = parseInt(properties.getProperty(locationPrefix + "count"));
                if (count == null || count <= 0) {
                    continue;
                }
                loadedAnyLocation = true;
                ArrayList<SpawnPointEntry> entries = zones.get(zoneIndex).get(locationIndex);
                loadEntries(properties, locationPrefix, count, entries, worldKey);
                sortEntries(entries);
            }

            if (!loadedAnyLocation) {
                String legacyPrefix = worldPrefix + legacyZoneKeys[zoneIndex] + ".";
                Integer legacyCount = parseInt(properties.getProperty(legacyPrefix + "count"));
                if (legacyCount != null && legacyCount > 0) {
                    ArrayList<SpawnPointEntry> entries = zones.get(zoneIndex).get(0);
                    loadEntries(properties, legacyPrefix, legacyCount, entries, worldKey);
                    sortEntries(entries);
                }
            }
        }
    }

    private static void loadEntries(
            @Nonnull Properties properties,
            @Nonnull String prefix,
            int count,
            @Nonnull ArrayList<SpawnPointEntry> entries,
            @Nonnull String worldKey
    ) {
        for (int i = 0; i < count; i++) {
            String base = prefix + i;
            Integer x = parseInt(properties.getProperty(base + ".x"));
            Integer y = parseInt(properties.getProperty(base + ".y"));
            Integer z = parseInt(properties.getProperty(base + ".z"));
            String dimension = properties.getProperty(base + ".dimension", worldKey);
            if (x == null || y == null || z == null) {
                continue;
            }
            entries.add(new SpawnPointEntry(new Vector3i(x, y, z), normalizeDimension(dimension, worldKey)));
        }
    }

    @Nonnull
    private static ArrayList<SpawnPointEntry> dedupeAndSort(@Nonnull List<SpawnPointEntry> input, @Nonnull String fallbackDimension) {
        ArrayList<SpawnPointEntry> out = new ArrayList<>();
        for (SpawnPointEntry entry : input) {
            if (entry == null || entry.position() == null) {
                continue;
            }
            SpawnPointEntry cleaned = new SpawnPointEntry(
                    new Vector3i(entry.position().x, entry.position().y, entry.position().z),
                    normalizeDimension(entry.dimension(), fallbackDimension)
            );
            if (containsEntry(out, cleaned)) {
                continue;
            }
            out.add(cleaned);
        }
        sortEntries(out);
        return out;
    }

    private static boolean containsEntry(@Nonnull List<SpawnPointEntry> values, @Nonnull SpawnPointEntry target) {
        String key = target.key();
        for (SpawnPointEntry value : values) {
            if (value.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static void sortEntries(@Nonnull ArrayList<SpawnPointEntry> entries) {
        entries.sort((a, b) -> {
            int cmp = a.dimension().compareToIgnoreCase(b.dimension());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.position().y, b.position().y);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.position().x, b.position().x);
            if (cmp != 0) return cmp;
            return Integer.compare(a.position().z, b.position().z);
        });
    }

    @Nonnull
    private static String normalizeDimension(String dimension, @Nonnull String fallback) {
        if (dimension == null || dimension.isBlank()) {
            return fallback;
        }
        return dimension.trim();
    }

    @Nonnull
    private static Properties loadProperties() {
        Properties properties = new Properties();
        Path path = getConfigPath();
        if (!Files.exists(path)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static void saveProperties(@Nonnull Properties properties) {
        Path path = getConfigPath();
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ignored) {
                return;
            }
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            properties.store(writer, "Spawn point zones");
        } catch (IOException ignored) {
        }
    }

    @Nonnull
    private static Path getConfigPath() {
        Path universePath = Universe.get().getPath();
        if (universePath == null) {
            return Path.of(CONFIG_FILE_NAME);
        }
        return universePath.resolve("plugins").resolve("HytaleModding-ExamplePlugin").resolve(CONFIG_FILE_NAME);
    }

    @Nonnull
    private static String sanitizeWorldKey(@Nonnull String worldKey) {
        String out = worldKey.trim().toLowerCase();
        return out.isEmpty() ? "default" : out.replaceAll("[^a-z0-9._-]", "_");
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        Integer parsed = parseInt(value);
        return parsed == null ? fallback : parsed;
    }
}