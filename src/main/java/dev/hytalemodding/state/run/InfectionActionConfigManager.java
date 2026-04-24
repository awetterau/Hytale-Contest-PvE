package dev.hytalemodding.state.run;

import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class InfectionActionConfigManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "infection-actions.properties";

    private InfectionActionConfigManager() {
    }

    @Nonnull
    public static synchronized List<ActionEntry> loadActions(@Nonnull String ignoredWorldName) {
        Properties properties = loadProperties();
        String prefix = "global";
        int count = parseInt(properties.getProperty(prefix + ".count"), 0);
        ArrayList<ActionEntry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String base = prefix + ".action." + i;
            String actionType = properties.getProperty(base + ".actionType", "spawn");
            String coreTier = properties.getProperty(base + ".coreTier", "core");
            int triggerSecond = parseInt(properties.getProperty(base + ".triggerSecond"), 30);
            int radius = parseInt(properties.getProperty(base + ".radius"), 6);
            int ticksPerBlock = parseInt(properties.getProperty(base + ".ticksPerBlock"), 2);
            int probabilityPercent = parseInt(properties.getProperty(base + ".probabilityPercent"), 100);
            boolean enabled = Boolean.parseBoolean(properties.getProperty(base + ".enabled", "true"));
            String mainTriggerPctRange = properties.getProperty(base + ".mainTriggerPctRange", "0.70-0.70");
            String seedSpawnDelaySecRange = properties.getProperty(base + ".seedSpawnDelaySecRange", "2.0-2.0");
            String seedRadiusAvgTriggerPctRange = properties.getProperty(base + ".seedRadiusAvgTriggerPctRange", "0.90-0.90");
            String seedTargetRadiusRange = properties.getProperty(base + ".seedTargetRadiusRange", "120-120");
            int chunkRangePerCore = parseInt(properties.getProperty(base + ".chunkRangePerCore"), 1);
            int maxActiveSeeds = parseInt(properties.getProperty(base + ".maxActiveSeeds"), 4);
            out.add(new ActionEntry(
                    actionType,
                    coreTier,
                    Math.max(0, triggerSecond),
                    Math.max(1, radius),
                    Math.max(1, ticksPerBlock),
                    Math.max(0, Math.min(100, probabilityPercent)),
                    enabled,
                    mainTriggerPctRange,
                    seedSpawnDelaySecRange,
                    seedRadiusAvgTriggerPctRange,
                    seedTargetRadiusRange,
                    Math.max(0, chunkRangePerCore),
                    Math.max(1, maxActiveSeeds)
            ));
        }
        return out;
    }

    public static synchronized void saveActions(@Nonnull String ignoredWorldName, @Nonnull List<ActionEntry> actions) {
        Properties properties = loadProperties();
        String prefix = "global";
        for (String key : new ArrayList<>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix + ".")) {
                properties.remove(key);
            }
        }
        properties.setProperty(prefix + ".count", Integer.toString(actions.size()));
        for (int i = 0; i < actions.size(); i++) {
            ActionEntry a = actions.get(i);
            String base = prefix + ".action." + i;
            properties.setProperty(base + ".actionType", a.actionType());
            properties.setProperty(base + ".coreTier", a.coreTier());
            properties.setProperty(base + ".triggerSecond", Integer.toString(a.triggerSecond()));
            properties.setProperty(base + ".radius", Integer.toString(a.radius()));
            properties.setProperty(base + ".ticksPerBlock", Integer.toString(a.ticksPerBlock()));
            properties.setProperty(base + ".probabilityPercent", Integer.toString(a.probabilityPercent()));
            properties.setProperty(base + ".enabled", Boolean.toString(a.enabled()));
            properties.setProperty(base + ".mainTriggerPctRange", a.mainTriggerPctRange());
            properties.setProperty(base + ".seedSpawnDelaySecRange", a.seedSpawnDelaySecRange());
            properties.setProperty(base + ".seedRadiusAvgTriggerPctRange", a.seedRadiusAvgTriggerPctRange());
            properties.setProperty(base + ".seedTargetRadiusRange", a.seedTargetRadiusRange());
            properties.setProperty(base + ".chunkRangePerCore", Integer.toString(a.chunkRangePerCore()));
            properties.setProperty(base + ".maxActiveSeeds", Integer.toString(a.maxActiveSeeds()));
        }
        saveProperties(properties);
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
        } catch (IOException ignored) {
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
                properties.store(writer, "Infection actions");
            }
        } catch (IOException ignored) {
        }
    }

    @Nullable
    private static Path getConfigFilePath() {
        Universe universe = Universe.get();
        if (universe == null || universe.getPath() == null) {
            return null;
        }
        return universe.getPath().resolve("plugins").resolve(PLUGIN_CONFIG_DIR).resolve(CONFIG_FILE_NAME);
    }

    private static int parseInt(@Nullable String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record ActionEntry(
            @Nonnull String actionType,
            @Nonnull String coreTier,
            int triggerSecond,
            int radius,
            int ticksPerBlock,
            int probabilityPercent,
            boolean enabled,
            @Nonnull String mainTriggerPctRange,
            @Nonnull String seedSpawnDelaySecRange,
            @Nonnull String seedRadiusAvgTriggerPctRange,
            @Nonnull String seedTargetRadiusRange,
            int chunkRangePerCore,
            int maxActiveSeeds
    ) {
    }
}