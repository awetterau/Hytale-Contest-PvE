package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.hytalemodding.redwave.RedWaveConfig;
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

    public record CrimsonCoreConfigState(
            int chooseCount,
            int radiusBlocks,
            float spreadSeconds,
            @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles
    ) {
    }

    @Nonnull
    public synchronized List<RedCoreProfileRegistry.RedCoreProfile> getProfiles(@Nonnull String worldName) {
        return this.getState(worldName).profiles();
    }

    @Nonnull
    public synchronized CrimsonCoreConfigState getState(@Nonnull String worldName) {
        Properties properties = loadProperties();
        String worldKey = normalizeWorldName(worldName);
        Integer count = readInt(properties.getProperty(prefix(worldKey) + ".count"));
        if (count == null || count <= 0) {
            return new CrimsonCoreConfigState(
                    0,
                    RedWaveConfig.DEFAULT_UI_RADIUS_BLOCKS,
                    RedWaveConfig.DEFAULT_UI_START_SECONDS,
                    List.of()
            );
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

        ArrayList<RedCoreProfileRegistry.RedCoreProfile> sorted = copyAndSortProfiles(profiles);
        Integer configuredChooseCount = readInt(properties.getProperty(prefix(worldKey) + ".chooseCount"));
        Integer configuredRadius = readInt(properties.getProperty(prefix(worldKey) + ".radius"));
        Double configuredSpreadSeconds = readDouble(properties.getProperty(prefix(worldKey) + ".spreadSeconds"));
        int radius = configuredRadius == null
                ? inferGlobalRadius(sorted)
                : normalizeRadius(configuredRadius);
        float spreadSeconds = configuredSpreadSeconds == null
                ? inferGlobalSpreadSeconds(sorted)
                : normalizeSpreadSeconds(configuredSpreadSeconds.floatValue());
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> normalizedProfiles = new ArrayList<>(sorted.size());
        for (RedCoreProfileRegistry.RedCoreProfile profile : sorted) {
            normalizedProfiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(profile.corePos()), radius, spreadSeconds));
        }
        return new CrimsonCoreConfigState(normalizeChooseCount(configuredChooseCount, normalizedProfiles.size()), radius, spreadSeconds, normalizedProfiles);
    }

    public synchronized void setProfiles(@Nonnull String worldName, @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        CrimsonCoreConfigState existing = this.getState(worldName);
        int chooseCount = normalizeChooseCount(existing.chooseCount(), profiles.size());
        this.setState(worldName, new CrimsonCoreConfigState(chooseCount, existing.radiusBlocks(), existing.spreadSeconds(), profiles));
    }

    public synchronized void setState(@Nonnull String worldName, @Nonnull CrimsonCoreConfigState state) {
        Properties properties = loadProperties();
        String worldKey = normalizeWorldName(worldName);

        clearWorld(properties, worldKey);

        ArrayList<RedCoreProfileRegistry.RedCoreProfile> cleaned = copyAndSortProfiles(state.profiles());

        if (cleaned.isEmpty()) {
            saveProperties(properties);
            return;
        }

        properties.setProperty(prefix(worldKey) + ".count", Integer.toString(cleaned.size()));
        properties.setProperty(prefix(worldKey) + ".chooseCount", Integer.toString(normalizeChooseCount(state.chooseCount(), cleaned.size())));
        properties.setProperty(prefix(worldKey) + ".radius", Integer.toString(normalizeRadius(state.radiusBlocks())));
        properties.setProperty(prefix(worldKey) + ".spreadSeconds", Float.toString(normalizeSpreadSeconds(state.spreadSeconds())));
        for (int i = 0; i < cleaned.size(); i++) {
            RedCoreProfileRegistry.RedCoreProfile profile = cleaned.get(i);
            Vector3i pos = profile.corePos();
            String base = prefix(worldKey) + ".core." + i;
            properties.setProperty(base + ".x", Integer.toString(pos.x));
            properties.setProperty(base + ".y", Integer.toString(pos.y));
            properties.setProperty(base + ".z", Integer.toString(pos.z));
            properties.setProperty(base + ".radius", Integer.toString(normalizeRadius(state.radiusBlocks())));
            properties.setProperty(base + ".startSeconds", Float.toString(normalizeSpreadSeconds(state.spreadSeconds())));
        }

        saveProperties(properties);
    }

    @Nonnull
    private static ArrayList<RedCoreProfileRegistry.RedCoreProfile> copyAndSortProfiles(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
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
        return cleaned;
    }

    private static int normalizeChooseCount(Integer chooseCount, int profileCount) {
        if (profileCount <= 0) {
            return 0;
        }
        if (chooseCount == null) {
            return profileCount;
        }
        if (chooseCount < 1) {
            return 1;
        }
        return Math.min(chooseCount, profileCount);
    }

    private static int normalizeRadius(int radius) {
        if (radius < RedWaveConfig.MIN_RADIUS_BLOCKS || radius > RedWaveConfig.MAX_RADIUS_BLOCKS) {
            return RedWaveConfig.DEFAULT_UI_RADIUS_BLOCKS;
        }
        return radius;
    }

    private static float normalizeSpreadSeconds(float spreadSeconds) {
        if (spreadSeconds <= 0.0f || Float.isNaN(spreadSeconds) || Float.isInfinite(spreadSeconds)) {
            return RedWaveConfig.DEFAULT_UI_START_SECONDS;
        }
        return spreadSeconds;
    }

    private static int inferGlobalRadius(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        if (profiles.isEmpty()) {
            return RedWaveConfig.DEFAULT_UI_RADIUS_BLOCKS;
        }
        return normalizeRadius(profiles.get(0).radiusBlocks());
    }

    private static float inferGlobalSpreadSeconds(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        if (profiles.isEmpty()) {
            return RedWaveConfig.DEFAULT_UI_START_SECONDS;
        }
        return normalizeSpreadSeconds(profiles.get(0).startSeconds());
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
