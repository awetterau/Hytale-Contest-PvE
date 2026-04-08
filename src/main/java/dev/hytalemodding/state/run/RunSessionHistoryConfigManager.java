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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public final class RunSessionHistoryConfigManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "run-sessions.properties";
    private static final int MAX_STORED_RUNS = 13;

    private RunSessionHistoryConfigManager() {
    }

    public static synchronized void recordSessionCreated(@Nonnull String runWorldName, @Nonnull String templateWorldName, @Nonnull UUID starterPlayerId) {
        Properties properties = loadProperties();
        String prefix = prefix(runWorldName);
        long now = System.currentTimeMillis();

        properties.setProperty(prefix + ".runWorldName", runWorldName);
        properties.setProperty(prefix + ".templateWorldName", templateWorldName);
        properties.setProperty(prefix + ".starterPlayerId", starterPlayerId.toString());
        properties.setProperty(prefix + ".createdAtEpochMillis", Long.toString(now));
        properties.setProperty(prefix + ".phase", GameSessionManager.RunPhase.PREPARING.name());
        properties.setProperty(prefix + ".active", "true");

        saveProperties(properties);
    }

    public static synchronized void recordRunWorldUuid(@Nonnull String runWorldName, @Nonnull UUID runWorldUuid) {
        Properties properties = loadProperties();
        String prefix = prefix(runWorldName);
        properties.setProperty(prefix + ".runWorldUuid", runWorldUuid.toString());
        saveProperties(properties);
    }

    public static synchronized void recordPhase(@Nonnull String runWorldName, @Nonnull GameSessionManager.RunPhase phase) {
        Properties properties = loadProperties();
        String prefix = prefix(runWorldName);
        properties.setProperty(prefix + ".phase", phase.name());
        saveProperties(properties);
    }

    public static synchronized void recordRunTiming(@Nonnull String runWorldName, long startedAtEpochMillis, long runEndsAtEpochMillis, long crimsonStartAtEpochMillis) {
        Properties properties = loadProperties();
        String prefix = prefix(runWorldName);
        properties.setProperty(prefix + ".startedAtEpochMillis", Long.toString(startedAtEpochMillis));
        properties.setProperty(prefix + ".runEndsAtEpochMillis", Long.toString(runEndsAtEpochMillis));
        properties.setProperty(prefix + ".crimsonStartAtEpochMillis", Long.toString(crimsonStartAtEpochMillis));
        saveProperties(properties);
    }

    public static synchronized void recordPlayerTeleportedToRun(@Nonnull String runWorldName, @Nonnull UUID playerId) {
        Properties properties = loadProperties();
        String prefix = prefix(runWorldName);
        int count = parseInt(properties.getProperty(prefix + ".players.count"), 0);
        String playerKey = prefix + ".players." + count;

        properties.setProperty(playerKey + ".playerId", playerId.toString());
        properties.setProperty(playerKey + ".teleportedAtEpochMillis", Long.toString(System.currentTimeMillis()));
        properties.setProperty(prefix + ".players.count", Integer.toString(count + 1));

        saveProperties(properties);
    }

    public static synchronized void recordSessionEnded(@Nonnull String runWorldName, boolean success, @Nullable String message) {
        Properties properties = loadProperties();
        String prefix = prefix(runWorldName);

        properties.setProperty(prefix + ".active", "false");
        properties.setProperty(prefix + ".phase", GameSessionManager.RunPhase.IDLE.name());
        properties.setProperty(prefix + ".endedAtEpochMillis", Long.toString(System.currentTimeMillis()));
        properties.setProperty(prefix + ".endSuccess", Boolean.toString(success));
        if (message != null && !message.isBlank()) {
            properties.setProperty(prefix + ".endMessage", message);
        }

        saveProperties(properties);
    }

    @Nonnull
    private static String prefix(@Nonnull String runWorldName) {
        return "run." + normalizeRunWorldName(runWorldName);
    }

    @Nonnull
    private static String normalizeRunWorldName(@Nonnull String runWorldName) {
        String trimmed = runWorldName.trim();
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
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static void saveProperties(@Nonnull Properties properties) {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }
        pruneOldSessions(properties);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Run sessions");
            }
        } catch (IOException ignored) {
        }
    }

    private static void pruneOldSessions(@Nonnull Properties properties) {
        HashMap<String, Long> runCreatedAtByPrefix = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("run.")) {
                continue;
            }
            int suffixDot = key.indexOf('.', 4);
            if (suffixDot <= 4) {
                continue;
            }
            String prefix = key.substring(0, suffixDot);
            Long current = runCreatedAtByPrefix.get(prefix);
            if (current != null) {
                continue;
            }
            long createdAt = parseLong(properties.getProperty(prefix + ".createdAtEpochMillis"), 0L);
            if (createdAt <= 0L) {
                createdAt = parseLong(properties.getProperty(prefix + ".endedAtEpochMillis"), 0L);
            }
            runCreatedAtByPrefix.put(prefix, createdAt);
        }

        if (runCreatedAtByPrefix.size() <= MAX_STORED_RUNS) {
            return;
        }

        ArrayList<Map.Entry<String, Long>> sessions = new ArrayList<>(runCreatedAtByPrefix.entrySet());
        sessions.sort(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed());

        for (int i = MAX_STORED_RUNS; i < sessions.size(); i++) {
            String stalePrefix = sessions.get(i).getKey();
            ArrayList<String> keys = new ArrayList<>(properties.stringPropertyNames());
            for (String key : keys) {
                if (key.startsWith(stalePrefix + ".")) {
                    properties.remove(key);
                }
            }
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
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(@Nullable String raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}