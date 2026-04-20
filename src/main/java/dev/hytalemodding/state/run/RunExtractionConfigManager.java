package dev.hytalemodding.state.run;

import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class RunExtractionConfigManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "run-extractions.properties";
    private static final RunExtractionConfigManager INSTANCE = new RunExtractionConfigManager();

    public enum VariantKey {
        PLATFORM_RUNE("platformRune"),
        ESCAPE_ROPE("escapeRope");

        private final String propertyKey;

        VariantKey(@Nonnull String propertyKey) {
            this.propertyKey = propertyKey;
        }

        @Nonnull
        public String propertyKey() {
            return this.propertyKey;
        }
    }

    public record VariantState(
            int runEnableFromSecond,
            int runEnableUntilSecond,
            int extractionWaitSeconds,
            int extractionWindowSeconds,
            boolean enemyWavesEnabled,
            int enemyMobsPerWave,
            float coreMaxHealth,
            double extractionRadiusBlocks,
            double extractionMinHeightOffset,
            double extractionMaxHeightOffset,
            double enemySpawnMinRadiusBlocks,
            double enemySpawnMaxRadiusBlocks,
            double enemySpawnMinHeightOffset,
            double enemySpawnMaxHeightOffset
    ) {
    }

    public record ExtractionState(
            @Nonnull VariantState platformRune,
            @Nonnull VariantState escapeRope
    ) {
    }

    private RunExtractionConfigManager() {
    }

    @Nonnull
    public static RunExtractionConfigManager get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized ExtractionState getState() {
        Properties properties = loadProperties();
        return new ExtractionState(
                readVariant(properties, VariantKey.PLATFORM_RUNE),
                readVariant(properties, VariantKey.ESCAPE_ROPE)
        );
    }

    @Nonnull
    public synchronized VariantState getVariantState(@Nonnull VariantKey variantKey) {
        ExtractionState state = getState();
        return variantKey == VariantKey.ESCAPE_ROPE ? state.escapeRope() : state.platformRune();
    }

    public synchronized void saveVariant(@Nonnull VariantKey variantKey, @Nonnull VariantState state) {
        Properties properties = loadProperties();
        String prefix = "global." + variantKey.propertyKey() + ".";
        int enableFrom = Math.max(0, state.runEnableFromSecond());
        int enableUntil = Math.max(enableFrom, state.runEnableUntilSecond());
        int waitSeconds = Math.max(10, state.extractionWaitSeconds());
        int windowSeconds = Math.max(1, state.extractionWindowSeconds());
        int mobsPerWave = Math.max(0, state.enemyMobsPerWave());
        float coreMaxHealth = Math.max(1.0f, state.coreMaxHealth());
        double radius = Math.max(0.25d, state.extractionRadiusBlocks());
        double minHeight = Math.min(state.extractionMinHeightOffset(), state.extractionMaxHeightOffset());
        double maxHeight = Math.max(state.extractionMinHeightOffset(), state.extractionMaxHeightOffset());
        double enemySpawnMinRadius = Math.max(0.5d, Math.min(state.enemySpawnMinRadiusBlocks(), state.enemySpawnMaxRadiusBlocks()));
        double enemySpawnMaxRadius = Math.max(enemySpawnMinRadius, Math.max(state.enemySpawnMinRadiusBlocks(), state.enemySpawnMaxRadiusBlocks()));
        double enemySpawnMinHeight = Math.min(state.enemySpawnMinHeightOffset(), state.enemySpawnMaxHeightOffset());
        double enemySpawnMaxHeight = Math.max(state.enemySpawnMinHeightOffset(), state.enemySpawnMaxHeightOffset());

        properties.setProperty(prefix + "runEnableFromSecond", Integer.toString(enableFrom));
        properties.setProperty(prefix + "runEnableUntilSecond", Integer.toString(enableUntil));
        properties.setProperty(prefix + "extractionWaitSeconds", Integer.toString(waitSeconds));
        properties.setProperty(prefix + "extractionWindowSeconds", Integer.toString(windowSeconds));
        properties.setProperty(prefix + "enemyWavesEnabled", Boolean.toString(state.enemyWavesEnabled()));
        properties.setProperty(prefix + "enemyMobsPerWave", Integer.toString(mobsPerWave));
        properties.setProperty(prefix + "coreMaxHealth", Float.toString(coreMaxHealth));
        properties.setProperty(prefix + "extractionRadiusBlocks", Double.toString(radius));
        properties.setProperty(prefix + "extractionMinHeightOffset", Double.toString(minHeight));
        properties.setProperty(prefix + "extractionMaxHeightOffset", Double.toString(maxHeight));
        properties.setProperty(prefix + "enemySpawnMinRadiusBlocks", Double.toString(enemySpawnMinRadius));
        properties.setProperty(prefix + "enemySpawnMaxRadiusBlocks", Double.toString(enemySpawnMaxRadius));
        properties.setProperty(prefix + "enemySpawnMinHeightOffset", Double.toString(enemySpawnMinHeight));
        properties.setProperty(prefix + "enemySpawnMaxHeightOffset", Double.toString(enemySpawnMaxHeight));
        saveProperties(properties);
    }

    @Nonnull
    private static VariantState readVariant(@Nonnull Properties properties, @Nonnull VariantKey variantKey) {
        String prefix = "global." + variantKey.propertyKey() + ".";
        int fromSecond = readInt(properties, prefix + "runEnableFromSecond", 0);
        int untilSecond = Math.max(fromSecond, readInt(properties, prefix + "runEnableUntilSecond", 1200));
        int waitSeconds = Math.max(10, readInt(properties, prefix + "extractionWaitSeconds", 20));
        int extractionWindowSeconds = Math.max(1, readInt(properties, prefix + "extractionWindowSeconds", 1));
        boolean enemyWavesEnabled = readBoolean(properties, prefix + "enemyWavesEnabled", true);
        int enemyMobsPerWave = Math.max(0, readInt(properties, prefix + "enemyMobsPerWave", 2));
        float coreMaxHealth = Math.max(1.0f, (float) readDouble(properties, prefix + "coreMaxHealth", 300.0d));
        double extractionRadius = Math.max(0.25d, readDouble(properties, prefix + "extractionRadiusBlocks", 6.0d));
        double minHeight = readDouble(properties, prefix + "extractionMinHeightOffset", -1.0d);
        double maxHeight = readDouble(properties, prefix + "extractionMaxHeightOffset", 3.0d);
        double enemySpawnMinRadius = Math.max(0.5d, readDouble(properties, prefix + "enemySpawnMinRadiusBlocks", 7.0d));
        double enemySpawnMaxRadius = Math.max(enemySpawnMinRadius, readDouble(properties, prefix + "enemySpawnMaxRadiusBlocks", 16.0d));
        double enemySpawnMinHeight = readDouble(properties, prefix + "enemySpawnMinHeightOffset", -8.0d);
        double enemySpawnMaxHeight = readDouble(properties, prefix + "enemySpawnMaxHeightOffset", 4.0d);
        if (minHeight > maxHeight) {
            double swap = minHeight;
            minHeight = maxHeight;
            maxHeight = swap;
        }
        if (enemySpawnMinHeight > enemySpawnMaxHeight) {
            double swap = enemySpawnMinHeight;
            enemySpawnMinHeight = enemySpawnMaxHeight;
            enemySpawnMaxHeight = swap;
        }
        return new VariantState(
                fromSecond,
                untilSecond,
                waitSeconds,
                extractionWindowSeconds,
                enemyWavesEnabled,
                enemyMobsPerWave,
                coreMaxHealth,
                extractionRadius,
                minHeight,
                maxHeight,
                enemySpawnMinRadius,
                enemySpawnMaxRadius,
                enemySpawnMinHeight,
                enemySpawnMaxHeight
        );
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

    private static boolean readBoolean(@Nonnull Properties properties, @Nonnull String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    @Nonnull
    private static Properties loadProperties() {
        Properties properties = new Properties();
        Path file = resolveConfigPath();
        if (!Files.exists(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static void saveProperties(@Nonnull Properties properties) {
        Path file = resolveConfigPath();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "Run extraction settings");
            }
        } catch (IOException ignored) {
        }
    }

    @Nonnull
    private static Path resolveConfigPath() {
        return Universe.get().getPath()
                .resolve("plugins")
                .resolve(PLUGIN_CONFIG_DIR)
                .resolve(CONFIG_FILE_NAME);
    }
}