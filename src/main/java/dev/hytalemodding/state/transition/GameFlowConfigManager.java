package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class GameFlowConfigManager {
    private static final String DEFAULT_TEMPLATE_WORLD = "game";
    private static final String DEFAULT_HUB_WORLD = "hub";
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "game-flow.properties";
    private static final GameFlowConfigManager INSTANCE = new GameFlowConfigManager();

    @Nonnull
    private String templateWorldName = DEFAULT_TEMPLATE_WORLD;
    @Nonnull
    private String hubWorldName = DEFAULT_HUB_WORLD;
    @Nullable
    private Vector3i doorBlock;
    @Nullable
    private Transform runSpawn;
    @Nullable
    private Transform baseSpawn;
    @Nullable
    private Transform rescueRunSpawn;
    private boolean blacksmithRescued;
    @Nonnull
    private Set<String> rescuedNpcKeys = new HashSet<>();
    private boolean loaded;

    private GameFlowConfigManager() {
    }

    @Nonnull
    public static GameFlowConfigManager get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized String getTemplateWorldName() {
        ensureLoaded();
        return this.templateWorldName;
    }

    public synchronized void setTemplateWorldName(@Nonnull String templateWorldName) {
        ensureLoaded();
        this.templateWorldName = normalizeWorldName(templateWorldName, DEFAULT_TEMPLATE_WORLD);
        saveQuietly();
    }

    @Nonnull
    public synchronized String getHubWorldName() {
        ensureLoaded();
        return this.hubWorldName;
    }

    public synchronized void setHubWorldName(@Nonnull String hubWorldName) {
        ensureLoaded();
        this.hubWorldName = normalizeWorldName(hubWorldName, DEFAULT_HUB_WORLD);
        saveQuietly();
    }

    public synchronized void setDoorBlock(@Nonnull Vector3i doorBlock) {
        ensureLoaded();
        this.doorBlock = new Vector3i(doorBlock);
        saveQuietly();
    }

    public synchronized void setRunSpawn(@Nonnull Transform runSpawn) {
        ensureLoaded();
        this.runSpawn = copyTransform(runSpawn);
        saveQuietly();
    }

    public synchronized void setBaseSpawn(@Nonnull Transform baseSpawn) {
        ensureLoaded();
        this.baseSpawn = copyTransform(baseSpawn);
        saveQuietly();
    }

    public synchronized void setRescueRunSpawn(@Nonnull Transform rescueRunSpawn) {
        ensureLoaded();
        this.rescueRunSpawn = copyTransform(rescueRunSpawn);
        saveQuietly();
    }

    public synchronized void clearRunSpawn() {
        ensureLoaded();
        this.runSpawn = null;
        saveQuietly();
    }

    public synchronized void clearBaseSpawn() {
        ensureLoaded();
        this.baseSpawn = null;
        saveQuietly();
    }

    public synchronized void clearRescueRunSpawn() {
        ensureLoaded();
        this.rescueRunSpawn = null;
        saveQuietly();
    }

    public synchronized boolean isBlacksmithRescued() {
        ensureLoaded();
        return isNpcRescued("blacksmith");
    }

    public synchronized void setBlacksmithRescued(boolean rescued) {
        setNpcRescued("blacksmith", rescued);
    }

    public synchronized boolean isNpcRescued(@Nonnull String npcKey) {
        ensureLoaded();
        String key = normalizeNpcKey(npcKey);
        return !key.isEmpty() && this.rescuedNpcKeys.contains(key);
    }

    public synchronized void setNpcRescued(@Nonnull String npcKey, boolean rescued) {
        ensureLoaded();
        String key = normalizeNpcKey(npcKey);
        if (key.isEmpty()) {
            return;
        }
        if (rescued) {
            this.rescuedNpcKeys.add(key);
        } else {
            this.rescuedNpcKeys.remove(key);
        }
        this.blacksmithRescued = this.rescuedNpcKeys.contains("blacksmith");
        saveQuietly();
    }

    @Nonnull
    public synchronized Set<String> getRescuedNpcKeys() {
        ensureLoaded();
        return Set.copyOf(this.rescuedNpcKeys);
    }

    @Nullable
    public synchronized Vector3i getDoorBlock() {
        ensureLoaded();
        return this.doorBlock == null ? null : new Vector3i(this.doorBlock);
    }

    @Nullable
    public synchronized Transform getRunSpawn() {
        ensureLoaded();
        return this.runSpawn == null ? null : copyTransform(this.runSpawn);
    }

    @Nullable
    public synchronized Transform getBaseSpawn() {
        ensureLoaded();
        return this.baseSpawn == null ? null : copyTransform(this.baseSpawn);
    }

    @Nullable
    public synchronized Transform getRescueRunSpawn() {
        ensureLoaded();
        return this.rescueRunSpawn == null ? null : copyTransform(this.rescueRunSpawn);
    }

    public synchronized boolean isConfigured() {
        ensureLoaded();
        return this.runSpawn != null && this.baseSpawn != null;
    }

    public synchronized boolean hasRunSpawn() {
        ensureLoaded();
        return this.runSpawn != null;
    }

    public synchronized boolean hasBaseSpawn() {
        ensureLoaded();
        return this.baseSpawn != null;
    }

    @Nonnull
    public synchronized List<String> describe() {
        ensureLoaded();
        List<String> lines = new ArrayList<>();
        lines.add("Template world: " + this.templateWorldName);
        lines.add("Hub world: " + this.hubWorldName);
        lines.add("Run spawn: " + formatTransform(this.runSpawn));
        lines.add("Base spawn: " + formatTransform(this.baseSpawn));
        lines.add("Rescue run spawn: " + formatTransform(this.rescueRunSpawn));
        lines.add("Door block: " + formatVector(this.doorBlock));
        lines.add("Blacksmith rescued: " + this.blacksmithRescued);
        lines.add("Rescued NPC keys: " + String.join(",", this.rescuedNpcKeys));
        return lines;
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        Path path = getConfigFilePath();
        if (path == null || !Files.exists(path)) {
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("[GameFlowConfig] Failed to load config: " + e.getMessage());
            return;
        }

        this.templateWorldName = normalizeWorldName(properties.getProperty("templateWorld"), DEFAULT_TEMPLATE_WORLD);
        this.hubWorldName = normalizeWorldName(properties.getProperty("hubWorld"), DEFAULT_HUB_WORLD);
        this.doorBlock = readVector3i(properties, "doorBlock");
        this.runSpawn = readTransform(properties, "runSpawn");
        this.baseSpawn = readTransform(properties, "baseSpawn");
        this.rescueRunSpawn = readTransform(properties, "rescueRunSpawn");
        this.blacksmithRescued = Boolean.parseBoolean(properties.getProperty("blacksmithRescued", "false"));
        this.rescuedNpcKeys = new HashSet<>(parseCsv(properties.getProperty("rescuedNpcs")));
        if (this.blacksmithRescued) {
            this.rescuedNpcKeys.add("blacksmith");
        }
        this.blacksmithRescued = this.rescuedNpcKeys.contains("blacksmith");
    }

    private synchronized void saveQuietly() {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("templateWorld", this.templateWorldName);
        properties.setProperty("hubWorld", this.hubWorldName);
        writeVector3i(properties, "doorBlock", this.doorBlock);
        writeTransform(properties, "runSpawn", this.runSpawn);
        writeTransform(properties, "baseSpawn", this.baseSpawn);
        writeTransform(properties, "rescueRunSpawn", this.rescueRunSpawn);
        properties.setProperty("rescuedNpcs", String.join(",", this.rescuedNpcKeys));
        this.blacksmithRescued = this.rescuedNpcKeys.contains("blacksmith");
        properties.setProperty("blacksmithRescued", Boolean.toString(this.blacksmithRescued));

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Game flow configuration");
            }
        } catch (IOException e) {
            System.out.println("[GameFlowConfig] Failed to save config: " + e.getMessage());
        }
    }

    @Nullable
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

    @Nullable
    private static Transform readTransform(@Nonnull Properties properties, @Nonnull String prefix) {
        Double px = readDouble(properties.getProperty(prefix + ".pos.x"));
        Double py = readDouble(properties.getProperty(prefix + ".pos.y"));
        Double pz = readDouble(properties.getProperty(prefix + ".pos.z"));
        Double rx = readDouble(properties.getProperty(prefix + ".rot.x"));
        Double ry = readDouble(properties.getProperty(prefix + ".rot.y"));
        Double rz = readDouble(properties.getProperty(prefix + ".rot.z"));
        if (px == null || py == null || pz == null || rx == null || ry == null || rz == null) {
            return null;
        }
        return new Transform(new Vector3d(px, py, pz), new Vector3f(rx.floatValue(), ry.floatValue(), rz.floatValue()));
    }

    private static void writeTransform(@Nonnull Properties properties, @Nonnull String prefix, @Nullable Transform transform) {
        if (transform == null) {
            properties.remove(prefix + ".pos.x");
            properties.remove(prefix + ".pos.y");
            properties.remove(prefix + ".pos.z");
            properties.remove(prefix + ".rot.x");
            properties.remove(prefix + ".rot.y");
            properties.remove(prefix + ".rot.z");
            return;
        }
        properties.setProperty(prefix + ".pos.x", Double.toString(transform.getPosition().getX()));
        properties.setProperty(prefix + ".pos.y", Double.toString(transform.getPosition().getY()));
        properties.setProperty(prefix + ".pos.z", Double.toString(transform.getPosition().getZ()));
        properties.setProperty(prefix + ".rot.x", Float.toString(transform.getRotation().getX()));
        properties.setProperty(prefix + ".rot.y", Float.toString(transform.getRotation().getY()));
        properties.setProperty(prefix + ".rot.z", Float.toString(transform.getRotation().getZ()));
    }

    @Nullable
    private static Vector3i readVector3i(@Nonnull Properties properties, @Nonnull String prefix) {
        Integer x = readInt(properties.getProperty(prefix + ".x"));
        Integer y = readInt(properties.getProperty(prefix + ".y"));
        Integer z = readInt(properties.getProperty(prefix + ".z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Vector3i(x, y, z);
    }

    private static void writeVector3i(@Nonnull Properties properties, @Nonnull String prefix, @Nullable Vector3i value) {
        if (value == null) {
            properties.remove(prefix + ".x");
            properties.remove(prefix + ".y");
            properties.remove(prefix + ".z");
            return;
        }
        properties.setProperty(prefix + ".x", Integer.toString(value.getX()));
        properties.setProperty(prefix + ".y", Integer.toString(value.getY()));
        properties.setProperty(prefix + ".z", Integer.toString(value.getZ()));
    }

    @Nullable
    private static Integer readInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static Double readDouble(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String normalizeWorldName(@Nullable String name, @Nonnull String fallback) {
        if (name == null) {
            return fallback;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    @Nonnull
    private static String normalizeNpcKey(@Nullable String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String key = normalizeNpcKey(item);
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static String formatTransform(@Nullable Transform transform) {
        if (transform == null) {
            return "<unset>";
        }
        return String.format(
                Locale.ROOT,
                "pos(%.2f, %.2f, %.2f) rot(%.2f, %.2f, %.2f)",
                transform.getPosition().getX(),
                transform.getPosition().getY(),
                transform.getPosition().getZ(),
                transform.getRotation().getX(),
                transform.getRotation().getY(),
                transform.getRotation().getZ()
        );
    }

    @Nonnull
    private static String formatVector(@Nullable Vector3i vector) {
        if (vector == null) {
            return "<unset>";
        }
        return "(" + vector.getX() + ", " + vector.getY() + ", " + vector.getZ() + ")";
    }

    @Nonnull
    private static Transform copyTransform(@Nonnull Transform transform) {
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }
}



