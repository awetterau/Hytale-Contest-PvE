package dev.hytalemodding.state.run;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.math.util.ChunkUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class RunChunkSelectionConfigManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "run-selected-chunks.properties";

    private RunChunkSelectionConfigManager() {
    }

    @Nonnull
    public static synchronized LoadedChunkSelection loadSelection(@Nonnull String worldName) {
        Properties properties = loadProperties();
        String prefix = prefix(worldName);
        Integer count = parseInt(properties.getProperty(prefix + ".count"));
        if (count == null || count <= 0) {
            return new LoadedChunkSelection(new LinkedHashSet<>(), new LongOpenHashSet());
        }

        ArrayList<RunChunkSelectionManager.ChunkPosKey> values = new ArrayList<>();
        LongSet pinned = new LongOpenHashSet();
        for (int i = 0; i < count; i++) {
            String base = prefix + ".chunk." + i;
            Integer x = parseInt(properties.getProperty(base + ".x"));
            Integer z = parseInt(properties.getProperty(base + ".z"));
            if (x == null || z == null) {
                continue;
            }
            values.add(new RunChunkSelectionManager.ChunkPosKey(x, z));
            if (parseBoolean(properties.getProperty(base + ".pinned"), false)) {
                pinned.add(ChunkUtil.indexChunk(x, z));
            }
        }
        values.sort(Comparator.comparingInt(RunChunkSelectionManager.ChunkPosKey::x)
                .thenComparingInt(RunChunkSelectionManager.ChunkPosKey::z));
        return new LoadedChunkSelection(new LinkedHashSet<>(values), pinned);
    }

    public static synchronized void saveSelection(
            @Nonnull String worldName,
            @Nonnull Set<RunChunkSelectionManager.ChunkPosKey> chunks,
            @Nonnull LongSet pinnedChunkIndices
    ) {
        Properties properties = loadProperties();
        String prefix = prefix(worldName);

        ArrayList<String> keys = new ArrayList<>(properties.stringPropertyNames());
        for (String key : keys) {
            if (key.startsWith(prefix + ".")) {
                properties.remove(key);
            }
        }

        ArrayList<RunChunkSelectionManager.ChunkPosKey> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparingInt(RunChunkSelectionManager.ChunkPosKey::x)
                .thenComparingInt(RunChunkSelectionManager.ChunkPosKey::z));

        properties.setProperty(prefix + ".count", Integer.toString(ordered.size()));
        for (int i = 0; i < ordered.size(); i++) {
            RunChunkSelectionManager.ChunkPosKey chunk = ordered.get(i);
            String base = prefix + ".chunk." + i;
            properties.setProperty(base + ".x", Integer.toString(chunk.x()));
            properties.setProperty(base + ".z", Integer.toString(chunk.z()));
            boolean pinned = pinnedChunkIndices.contains(ChunkUtil.indexChunk(chunk.x(), chunk.z()));
            properties.setProperty(base + ".pinned", Boolean.toString(pinned));
        }

        saveProperties(properties);
    }

    @Nonnull
    private static String prefix(@Nonnull String worldName) {
        return "world." + normalizeWorldName(worldName);
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
                properties.store(writer, "Run selected chunks");
            }
        } catch (IOException ignored) {
        }
    }

    private static Path getConfigFilePath() {
        Universe universe = Universe.get();
        if (universe == null || universe.getPath() == null) {
            return null;
        }
        return universe.getPath().resolve("plugins").resolve(PLUGIN_CONFIG_DIR).resolve(CONFIG_FILE_NAME);
    }

    private static Integer parseInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    public record LoadedChunkSelection(
            @Nonnull LinkedHashSet<RunChunkSelectionManager.ChunkPosKey> chunks,
            @Nonnull LongSet pinnedChunkIndices
    ) {
    }
}