package dev.hytalemodding.loot;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestChestPositionManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "quest-chest-positions.properties";
    private static final QuestChestPositionManager INSTANCE = new QuestChestPositionManager();

    private final ConcurrentHashMap<String, Vector3i> positionsByKey = new ConcurrentHashMap<>();
    private boolean loaded;

    private QuestChestPositionManager() {
    }

    @Nonnull
    public static QuestChestPositionManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        ensureLoaded();
    }

    @Nullable
    public synchronized Vector3i getPosition(@Nonnull String chestId, @Nonnull String templateWorldName) {
        ensureLoaded();
        Vector3i pos = this.positionsByKey.get(buildKey(chestId, templateWorldName));
        return pos == null ? null : new Vector3i(pos);
    }

    public synchronized void setPosition(@Nonnull String chestId, @Nonnull String templateWorldName, @Nonnull Vector3i pos) {
        ensureLoaded();
        this.positionsByKey.put(buildKey(chestId, templateWorldName), new Vector3i(pos));
        saveQuietly();
    }

    public synchronized void clearPosition(@Nonnull String chestId, @Nonnull String templateWorldName) {
        ensureLoaded();
        this.positionsByKey.remove(buildKey(chestId, templateWorldName));
        saveQuietly();
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        Path path = getConfigPath();
        if (path == null || !Files.exists(path)) {
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("[QuestChestPosition] Failed to load: " + e.getMessage());
            return;
        }

        for (String propertyName : properties.stringPropertyNames()) {
            if (!propertyName.endsWith(".x")) {
                continue;
            }
            String prefix = propertyName.substring(0, propertyName.length() - 2);
            Integer x = readInt(properties.getProperty(prefix + ".x"));
            Integer y = readInt(properties.getProperty(prefix + ".y"));
            Integer z = readInt(properties.getProperty(prefix + ".z"));
            if (x == null || y == null || z == null) {
                continue;
            }
            this.positionsByKey.put(prefix, new Vector3i(x, y, z));
        }
    }

    private synchronized void saveQuietly() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }

        Properties properties = new Properties();
        for (var entry : this.positionsByKey.entrySet()) {
            Vector3i pos = entry.getValue();
            String prefix = entry.getKey();
            properties.setProperty(prefix + ".x", Integer.toString(pos.x));
            properties.setProperty(prefix + ".y", Integer.toString(pos.y));
            properties.setProperty(prefix + ".z", Integer.toString(pos.z));
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Quest chest positions");
            }
        } catch (IOException e) {
            System.out.println("[QuestChestPosition] Failed to save: " + e.getMessage());
        }
    }

    @Nullable
    private static Path getConfigPath() {
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

    @Nonnull
    private static String buildKey(@Nonnull String chestId, @Nonnull String templateWorldName) {
        return "questChest." + normalize(templateWorldName) + "." + normalize(chestId);
    }

    @Nonnull
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static Integer readInt(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
