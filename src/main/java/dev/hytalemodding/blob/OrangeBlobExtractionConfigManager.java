package dev.hytalemodding.blob;

import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class OrangeBlobExtractionConfigManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "orange-blob-extraction.properties";
    private static final OrangeBlobExtractionConfigManager INSTANCE = new OrangeBlobExtractionConfigManager();

    public enum TraversalHelperMode {
        NONE,
        CROSS
    }

    public record ExtractionConfigState(
            double activationRadiusBlocks,
            double defendRadiusBlocks,
            long defenseDurationMs,
            long readyExtractWindowMs,
            boolean enemySpawnsEnabled,
            long waveIntervalMs,
            int mobsPerWave,
            int maxWaves,
            float runeMaxHealth,
            long runeDamageIntervalMs,
            float runeDamagePerMobTick,
            double runeDamageRadiusBlocks,
            boolean resetWhenNoPlayersNearby,
            boolean pauseWhenNoPlayersNearby,
            long abandonmentGraceMs,
            @Nonnull TraversalHelperMode traversalHelperMode,
            boolean proceduralSupportEnabled,
            int proceduralSupportCount,
            @Nonnull String loweredIslandBlockId,
            @Nonnull String idleRuneBlockId,
            @Nonnull String activeRuneBlockId,
            @Nonnull String traversalHelperBlockId,
            @Nonnull String proceduralSupportBlockId
    ) {
    }

    private OrangeBlobExtractionConfigManager() {
    }

    @Nonnull
    public static OrangeBlobExtractionConfigManager get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized ExtractionConfigState getState(@Nonnull String worldName) {
        Properties properties = loadProperties();
        String prefix = "world." + normalizeWorldName(worldName) + ".";
        return new ExtractionConfigState(
                readDouble(properties, prefix + "activationRadiusBlocks", 1.85d),
                readDouble(properties, prefix + "defendRadiusBlocks", 12.0d),
                readLong(properties, prefix + "defenseDurationMs", 30000L),
                readLong(properties, prefix + "readyExtractWindowMs", 60000L),
                readBoolean(properties, prefix + "enemySpawnsEnabled", false),
                readLong(properties, prefix + "waveIntervalMs", 10000L),
                readInt(properties, prefix + "mobsPerWave", 2),
                readInt(properties, prefix + "maxWaves", 2),
                readFloat(properties, prefix + "runeMaxHealth", 300.0f),
                readLong(properties, prefix + "runeDamageIntervalMs", 1000L),
                readFloat(properties, prefix + "runeDamagePerMobTick", 8.0f),
                readDouble(properties, prefix + "runeDamageRadiusBlocks", 2.75d),
                readBoolean(properties, prefix + "resetWhenNoPlayersNearby", false),
                readBoolean(properties, prefix + "pauseWhenNoPlayersNearby", false),
                readLong(properties, prefix + "abandonmentGraceMs", 6000L),
                readTraversalMode(properties, prefix + "traversalHelperMode", TraversalHelperMode.CROSS),
                readBoolean(properties, prefix + "proceduralSupportEnabled", false),
                readInt(properties, prefix + "proceduralSupportCount", 6),
                readString(properties, prefix + "loweredIslandBlockId", OrangeBlobBlockManager.ACTIVE_BLOCK_ID),
                readString(properties, prefix + "idleRuneBlockId", OrangeBlobBlockManager.RUNE_BLOCK_ID),
                readString(properties, prefix + "activeRuneBlockId", OrangeBlobBlockManager.ACTIVE_RUNE_BLOCK_ID),
                readString(properties, prefix + "traversalHelperBlockId", OrangeBlobBlockManager.BLOCK_ID),
                readString(properties, prefix + "proceduralSupportBlockId", OrangeBlobBlockManager.ACTIVE_BLOCK_ID)
        );
    }

    @Nonnull
    private static String readString(@Nonnull Properties properties, @Nonnull String key, @Nonnull String fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static double readDouble(@Nonnull Properties properties, @Nonnull String key, double fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float readFloat(@Nonnull Properties properties, @Nonnull String key, float fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long readLong(@Nonnull Properties properties, @Nonnull String key, long fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(@Nonnull Properties properties, @Nonnull String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(@Nonnull Properties properties, @Nonnull String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    @Nonnull
    private static TraversalHelperMode readTraversalMode(
            @Nonnull Properties properties,
            @Nonnull String key,
            @Nonnull TraversalHelperMode fallback
    ) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return TraversalHelperMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    @Nonnull
    private static String normalizeWorldName(@Nonnull String worldName) {
        String trimmed = worldName.trim();
        return trimmed.isEmpty() ? "default" : trimmed.toLowerCase(Locale.ROOT);
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
            System.out.println("[OrangeBlobConfig] Failed to load config: " + e.getMessage());
        }
        return properties;
    }

    @SuppressWarnings("unused")
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
                properties.store(writer, "Orange blob extraction settings");
            }
        } catch (IOException e) {
            System.out.println("[OrangeBlobConfig] Failed to save config: " + e.getMessage());
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
}
