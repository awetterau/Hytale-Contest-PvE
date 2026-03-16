package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class CrimsonCoreConfigManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "crimson-cores.properties";
    private static final CrimsonCoreConfigManager INSTANCE = new CrimsonCoreConfigManager();

    private CrimsonCoreConfigManager() {
    }

    @Nonnull
    public static CrimsonCoreConfigManager get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized List<RedCoreProfileRegistry.RedCoreProfile> getProfiles(@Nonnull String worldName) {
        Properties properties = loadProperties();
        String worldKey = normalizeWorldName(worldName);
        Integer count = readInt(properties.getProperty(prefix(worldKey) + ".count"));
        if (count == null || count <= 0) {
            return List.of();
        }

        ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String base = prefix(worldKey) + ".core." + i;
            Integer x = readInt(properties.getProperty(base + ".x"));
            Integer y = readInt(properties.getProperty(base + ".y"));
            Integer z = readInt(properties.getProperty(base + ".z"));
            Integer radius = readInt(properties.getProperty(base + ".radius"));
            Double seconds = readDouble(properties.getProperty(base + ".startSeconds"));
            if (x == null || y == null || z == null || radius == null || seconds == null) {
                continue;
            }
            profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(x, y, z), radius, seconds.floatValue()));
        }

        profiles.sort(Comparator
                .comparingInt((RedCoreProfileRegistry.RedCoreProfile p) -> p.corePos().x)
                .thenComparingInt(p -> p.corePos().y)
                .thenComparingInt(p -> p.corePos().z));
        return profiles;
    }

    public synchronized void setProfiles(@Nonnull String worldName, @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        Properties properties = loadProperties();
        String worldKey = normalizeWorldName(worldName);

        clearWorld(properties, worldKey);

        ArrayList<RedCoreProfileRegistry.RedCoreProfile> cleaned = new ArrayList<>();
        for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
            if (profile == null || profile.corePos() == null) {
                continue;
            }
            cleaned.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }

        cleaned.sort(Comparator
                .comparingInt((RedCoreProfileRegistry.RedCoreProfile p) -> p.corePos().x)
                .thenComparingInt(p -> p.corePos().y)
                .thenComparingInt(p -> p.corePos().z));

        if (cleaned.isEmpty()) {
            saveProperties(properties);
            return;
        }

        properties.setProperty(prefix(worldKey) + ".count", Integer.toString(cleaned.size()));
        for (int i = 0; i < cleaned.size(); i++) {
            RedCoreProfileRegistry.RedCoreProfile profile = cleaned.get(i);
            Vector3i pos = profile.corePos();
            String base = prefix(worldKey) + ".core." + i;
            properties.setProperty(base + ".x", Integer.toString(pos.x));
            properties.setProperty(base + ".y", Integer.toString(pos.y));
            properties.setProperty(base + ".z", Integer.toString(pos.z));
            properties.setProperty(base + ".radius", Integer.toString(profile.radiusBlocks()));
            properties.setProperty(base + ".startSeconds", Float.toString(profile.startSeconds()));
        }

        saveProperties(properties);
    }

    private static void clearWorld(@Nonnull Properties properties, @Nonnull String worldKey) {
        String start = prefix(worldKey) + ".";
        ArrayList<String> keys = new ArrayList<>(properties.stringPropertyNames());
        for (String key : keys) {
            if (key.startsWith(start)) {
                properties.remove(key);
            }
        }
    }

    @Nonnull
    private static String prefix(@Nonnull String worldKey) {
        return "world." + worldKey;
    }

    @Nonnull
    private static String normalizeWorldName(@Nonnull String worldName) {
        String trimmed = worldName.trim();
        if (trimmed.isEmpty()) {
            return "default";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static Properties loadProperties() {
        Properties properties = new Properties();
        Path path = getConfigFilePath();
        if (path == null || !Files.exists(path)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("[CrimsonCoreConfig] Failed to load config: " + e.getMessage());
        }
        return properties;
    }

    private static void saveProperties(@Nonnull Properties properties) {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Crimson core profiles");
            }
        } catch (IOException e) {
            System.out.println("[CrimsonCoreConfig] Failed to save config: " + e.getMessage());
        }
    }

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

    private static Integer readInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double readDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}