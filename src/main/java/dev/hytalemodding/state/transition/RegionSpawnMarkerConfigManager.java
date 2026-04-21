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

public final class RegionSpawnMarkerConfigManager {
    public static final String CONFIG_FILE_NAME = "RegionSpawnMarkers.properties";

    public record RegionMarkerEntry(@Nonnull String regionId, @Nonnull Vector3i position, @Nonnull String dimension) {
        @Nonnull
        public String key() {
            return this.dimension + ":" + this.regionId + ":" + this.position.x + ":" + this.position.y + ":" + this.position.z;
        }
    }

    private RegionSpawnMarkerConfigManager() {
    }

    @Nonnull
    public static synchronized List<RegionMarkerEntry> load(@Nonnull String worldKey) {
        Properties properties = loadProperties();
        String prefix = "world." + sanitizeWorldKey(worldKey) + ".";
        int count = Math.max(0, parseInt(properties.getProperty(prefix + "count"), 0));
        ArrayList<RegionMarkerEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String base = prefix + i;
            String regionId = normalize(properties.getProperty(base + ".region"));
            Integer x = parseInt(properties.getProperty(base + ".x"));
            Integer y = parseInt(properties.getProperty(base + ".y"));
            Integer z = parseInt(properties.getProperty(base + ".z"));
            String dimension = normalizeDimension(properties.getProperty(base + ".dimension"), worldKey);
            if (regionId.isBlank() || x == null || y == null || z == null) {
                continue;
            }
            entries.add(new RegionMarkerEntry(regionId, new Vector3i(x, y, z), dimension));
        }
        return List.copyOf(dedupeAndSort(entries, worldKey));
    }

    public static synchronized void save(@Nonnull String worldKey, @Nonnull List<RegionMarkerEntry> entries) {
        Properties properties = loadProperties();
        String prefix = "world." + sanitizeWorldKey(worldKey) + ".";

        ArrayList<String> keys = new ArrayList<>(properties.stringPropertyNames());
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                properties.remove(key);
            }
        }

        ArrayList<RegionMarkerEntry> cleaned = dedupeAndSort(entries, worldKey);
        properties.setProperty(prefix + "count", Integer.toString(cleaned.size()));
        for (int i = 0; i < cleaned.size(); i++) {
            RegionMarkerEntry entry = cleaned.get(i);
            String base = prefix + i;
            properties.setProperty(base + ".region", normalize(entry.regionId()));
            properties.setProperty(base + ".x", Integer.toString(entry.position().x));
            properties.setProperty(base + ".y", Integer.toString(entry.position().y));
            properties.setProperty(base + ".z", Integer.toString(entry.position().z));
            properties.setProperty(base + ".dimension", normalizeDimension(entry.dimension(), worldKey));
        }

        saveProperties(properties);
    }

    public static synchronized boolean addMarker(@Nonnull String worldKey, @Nonnull RegionMarkerEntry target) {
        ArrayList<RegionMarkerEntry> entries = new ArrayList<>(load(worldKey));
        RegionMarkerEntry cleaned = new RegionMarkerEntry(
                normalize(target.regionId()),
                new Vector3i(target.position().x, target.position().y, target.position().z),
                normalizeDimension(target.dimension(), worldKey)
        );
        for (RegionMarkerEntry entry : entries) {
            if (entry.key().equals(cleaned.key())) {
                return false;
            }
        }
        entries.add(cleaned);
        save(worldKey, entries);
        return true;
    }

    public static synchronized void pruneToWorldState(
            @Nonnull String worldKey,
            @Nonnull java.util.function.Predicate<RegionMarkerEntry> keepPredicate
    ) {
        ArrayList<RegionMarkerEntry> kept = new ArrayList<>();
        for (RegionMarkerEntry entry : load(worldKey)) {
            if (keepPredicate.test(entry)) {
                kept.add(entry);
            }
        }
        save(worldKey, kept);
    }

    @Nonnull
    private static ArrayList<RegionMarkerEntry> dedupeAndSort(@Nonnull List<RegionMarkerEntry> input, @Nonnull String fallbackWorld) {
        LinkedHashMap<String, RegionMarkerEntry> deduped = new LinkedHashMap<>();
        for (RegionMarkerEntry entry : input) {
            if (entry == null || entry.position() == null) {
                continue;
            }
            RegionMarkerEntry cleaned = new RegionMarkerEntry(
                    normalize(entry.regionId()),
                    new Vector3i(entry.position().x, entry.position().y, entry.position().z),
                    normalizeDimension(entry.dimension(), fallbackWorld)
            );
            if (cleaned.regionId().isBlank()) {
                continue;
            }
            deduped.putIfAbsent(cleaned.key(), cleaned);
        }
        ArrayList<RegionMarkerEntry> out = new ArrayList<>(deduped.values());
        out.sort((a, b) -> {
            int cmp = a.regionId().compareToIgnoreCase(b.regionId());
            if (cmp != 0) return cmp;
            cmp = a.dimension().compareToIgnoreCase(b.dimension());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.position().x, b.position().x);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.position().y, b.position().y);
            if (cmp != 0) return cmp;
            return Integer.compare(a.position().z, b.position().z);
        });
        return out;
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
            properties.store(writer, "Region spawn markers");
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

    @Nonnull
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    @Nonnull
    private static String normalizeDimension(String dimension, @Nonnull String fallback) {
        if (dimension == null || dimension.isBlank()) {
            return fallback;
        }
        return dimension.trim();
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
