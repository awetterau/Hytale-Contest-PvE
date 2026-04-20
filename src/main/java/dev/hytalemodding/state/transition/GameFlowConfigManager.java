package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class GameFlowConfigManager {
    private static final String DEFAULT_TEMPLATE_WORLD = "game";
    private static final String DEFAULT_HUB_WORLD = "default";
    private static final int DEFAULT_RUN_DURATION_SECONDS = 1200;
    private static final int DEFAULT_RUN_TIME_HOUR = 8;
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "game-flow.properties";
    private static final GameFlowConfigManager INSTANCE = new GameFlowConfigManager();

    @Nonnull
    private String templateWorldName = DEFAULT_TEMPLATE_WORLD;
    @Nonnull
    private String hubWorldName = DEFAULT_HUB_WORLD;
    @Nullable
    private Vector3i doorBlock;
    private int runDurationSeconds = DEFAULT_RUN_DURATION_SECONDS;
    private int runTimeHourMin = DEFAULT_RUN_TIME_HOUR;
    private int runTimeHourMax = DEFAULT_RUN_TIME_HOUR;
    @Nullable
    private Long runSeed;
    private boolean statusMessagesEnabled = true;
    private boolean chunkLoadingMessagesEnabled = true;
    private boolean hazardFogWeatherEnabled = true;
    private boolean coreRadiusChatMessagesEnabled = true;
    @Nullable
    private Transform runSpawn;
    @Nullable
    private Transform baseSpawn;
    @Nullable
    private Transform rescueRunSpawn;
    @Nonnull
    private HashMap<String, Transform> rescueRunSpawnsByNpc = new HashMap<>();
    @Nonnull
    private Set<String> rescuedNpcKeys = new HashSet<>();
    @Nonnull
    private HashMap<String, ArrayList<RedCoreProfileRegistry.RedCoreProfile>> crimsonCoreProfilesByWorld = new HashMap<>();
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
        return resolveHubWorldName(this.hubWorldName);
    }


    public synchronized int getRunDurationSeconds() {
        ensureLoaded();
        return Math.max(1, this.runDurationSeconds);
    }

    public synchronized void setRunDurationSeconds(int runDurationSeconds) {
        ensureLoaded();
        this.runDurationSeconds = Math.max(1, runDurationSeconds);
        saveQuietly();
    }


    @Nullable
    public synchronized Long getRunSeed() {
        ensureLoaded();
        return this.runSeed;
    }

    public synchronized void setRunSeed(@Nullable Long runSeed) {
        ensureLoaded();
        this.runSeed = runSeed;
        saveQuietly();
    }

    public synchronized int getRunTimeHourMin() {
        ensureLoaded();
        return Math.max(0, Math.min(23, this.runTimeHourMin));
    }

    public synchronized int getRunTimeHourMax() {
        ensureLoaded();
        return Math.max(0, Math.min(23, this.runTimeHourMax));
    }

    public synchronized void setRunTimeHourRange(int minHour, int maxHour) {
        ensureLoaded();
        int safeMin = Math.max(0, Math.min(23, minHour));
        int safeMax = Math.max(0, Math.min(23, maxHour));
        if (safeMax < safeMin) {
            int tmp = safeMin;
            safeMin = safeMax;
            safeMax = tmp;
        }
        this.runTimeHourMin = safeMin;
        this.runTimeHourMax = safeMax;
        saveQuietly();
    }

    public synchronized boolean isStatusMessagesEnabled() {
        ensureLoaded();
        return this.statusMessagesEnabled;
    }

    public synchronized void setStatusMessagesEnabled(boolean enabled) {
        ensureLoaded();
        this.statusMessagesEnabled = enabled;
        saveQuietly();
    }

    public synchronized boolean isChunkLoadingMessagesEnabled() {
        ensureLoaded();
        return this.chunkLoadingMessagesEnabled;
    }

    public synchronized void setChunkLoadingMessagesEnabled(boolean enabled) {
        ensureLoaded();
        this.chunkLoadingMessagesEnabled = enabled;
        saveQuietly();
    }

    public synchronized boolean isHazardFogWeatherEnabled() {
        ensureLoaded();
        return this.hazardFogWeatherEnabled;
    }

    public synchronized void setHazardFogWeatherEnabled(boolean enabled) {
        ensureLoaded();
        this.hazardFogWeatherEnabled = enabled;
        saveQuietly();
    }

    public synchronized boolean isCoreRadiusChatMessagesEnabled() {
        ensureLoaded();
        return this.coreRadiusChatMessagesEnabled;
    }

    public synchronized void setCoreRadiusChatMessagesEnabled(boolean enabled) {
        ensureLoaded();
        this.coreRadiusChatMessagesEnabled = enabled;
        saveQuietly();
    }

    public synchronized void setHubWorldName(@Nonnull String hubWorldName) {
        ensureLoaded();
        this.hubWorldName = normalizeWorldName(hubWorldName, DEFAULT_HUB_WORLD);
        saveQuietly();
    }

    @Nonnull
    private static String resolveHubWorldName(@Nullable String configuredHubWorldName) {
        String normalized = normalizeWorldName(configuredHubWorldName, DEFAULT_HUB_WORLD);
        World defaultWorld = Universe.get().getDefaultWorld();
        if ("hub".equalsIgnoreCase(normalized) && defaultWorld != null && defaultWorld.getName() != null && !defaultWorld.getName().isBlank()) {
            return defaultWorld.getName();
        }
        if (normalized.isBlank() && defaultWorld != null && defaultWorld.getName() != null && !defaultWorld.getName().isBlank()) {
            return defaultWorld.getName();
        }
        return normalized;
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

    public synchronized void setRescueRunSpawn(@Nonnull String npcKey, @Nonnull Transform rescueRunSpawn) {
        ensureLoaded();
        String normalizedNpc = normalizeNpcKey(npcKey);
        if (normalizedNpc.isEmpty()) {
            return;
        }
        this.rescueRunSpawnsByNpc.put(normalizedNpc, copyTransform(rescueRunSpawn));
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

    public synchronized void clearRescueRunSpawn(@Nonnull String npcKey) {
        ensureLoaded();
        String normalizedNpc = normalizeNpcKey(npcKey);
        if (normalizedNpc.isEmpty()) {
            return;
        }
        this.rescueRunSpawnsByNpc.remove(normalizedNpc);
        saveQuietly();
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
        saveQuietly();
    }

    public synchronized void clearRescuedNpcKeys() {
        ensureLoaded();
        this.rescuedNpcKeys.clear();
        saveQuietly();
    }

    @Nonnull
    public synchronized Set<String> getRescuedNpcKeys() {
        ensureLoaded();
        return Set.copyOf(this.rescuedNpcKeys);
    }

    @Nonnull
    public synchronized List<RedCoreProfileRegistry.RedCoreProfile> getCrimsonCoreProfiles(@Nonnull String worldName) {
        ensureLoaded();
        String normalizedWorld = normalizeWorldName(worldName, DEFAULT_TEMPLATE_WORLD);
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> existing = this.crimsonCoreProfilesByWorld.get(normalizedWorld);
        if (existing == null || existing.isEmpty()) {
            return List.of();
        }
        return copyAndSortProfiles(existing);
    }

    public synchronized void setCrimsonCoreProfiles(@Nonnull String worldName, @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        ensureLoaded();
        String normalizedWorld = normalizeWorldName(worldName, DEFAULT_TEMPLATE_WORLD);
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> cleaned = new ArrayList<>();
        for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
            if (profile == null || profile.corePos() == null) {
                continue;
            }
            cleaned.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }
        if (cleaned.isEmpty()) {
            this.crimsonCoreProfilesByWorld.remove(normalizedWorld);
        } else {
            this.crimsonCoreProfilesByWorld.put(normalizedWorld, copyAndSortProfiles(cleaned));
        }
        saveQuietly();
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

    @Nullable
    public synchronized Transform getRescueRunSpawn(@Nonnull String npcKey) {
        ensureLoaded();
        String normalizedNpc = normalizeNpcKey(npcKey);
        Transform transform = this.rescueRunSpawnsByNpc.get(normalizedNpc);
        return transform == null ? null : copyTransform(transform);
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
        lines.add("Hub world: " + getHubWorldName());
        lines.add("Run spawn: " + formatTransform(this.runSpawn));
        lines.add("Base spawn: " + formatTransform(this.baseSpawn));
        lines.add("Rescue run spawn: " + formatTransform(this.rescueRunSpawn));
        if (!this.rescueRunSpawnsByNpc.isEmpty()) {
            ArrayList<String> npcKeys = new ArrayList<>(this.rescueRunSpawnsByNpc.keySet());
            npcKeys.sort(String::compareToIgnoreCase);
            for (String npcKey : npcKeys) {
                lines.add("Rescue run spawn [" + npcKey + "]: " + formatTransform(this.rescueRunSpawnsByNpc.get(npcKey)));
            }
        }
        lines.add("Door block: " + formatVector(this.doorBlock));
        lines.add("Run duration seconds: " + this.runDurationSeconds);
        lines.add("Run time hour range: " + this.runTimeHourMin + "-" + this.runTimeHourMax);
        lines.add("Run seed: " + (this.runSeed == null ? "<random>" : this.runSeed));
        lines.add("Status chat messages: " + this.statusMessagesEnabled);
        lines.add("Chunk loading messages: " + this.chunkLoadingMessagesEnabled);
        lines.add("Hazard fog weather: " + this.hazardFogWeatherEnabled);
        lines.add("Core radius chat messages: " + this.coreRadiusChatMessagesEnabled);
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
        this.hubWorldName = resolveHubWorldName(normalizeWorldName(properties.getProperty("hubWorld"), DEFAULT_HUB_WORLD));
        this.doorBlock = readVector3i(properties, "doorBlock");
        Integer loadedRunDuration = readInt(properties.getProperty("runDurationSeconds"));
        this.runDurationSeconds = loadedRunDuration == null ? DEFAULT_RUN_DURATION_SECONDS : Math.max(1, loadedRunDuration);
        Integer loadedMinHour = readInt(properties.getProperty("runTimeHourMin"));
        Integer loadedMaxHour = readInt(properties.getProperty("runTimeHourMax"));
        this.runTimeHourMin = loadedMinHour == null ? DEFAULT_RUN_TIME_HOUR : Math.max(0, Math.min(23, loadedMinHour));
        this.runTimeHourMax = loadedMaxHour == null ? this.runTimeHourMin : Math.max(0, Math.min(23, loadedMaxHour));
        if (this.runTimeHourMax < this.runTimeHourMin) {
            int tmp = this.runTimeHourMin;
            this.runTimeHourMin = this.runTimeHourMax;
            this.runTimeHourMax = tmp;
        }
        this.runSeed = readLong(properties.getProperty("runSeed"));
        this.statusMessagesEnabled = parseBoolean(properties.getProperty("statusMessagesEnabled"), true);
        this.chunkLoadingMessagesEnabled = parseBoolean(properties.getProperty("chunkLoadingMessagesEnabled"), true);
        this.hazardFogWeatherEnabled = parseBoolean(properties.getProperty("hazardFogWeatherEnabled"), true);
        this.coreRadiusChatMessagesEnabled = parseBoolean(properties.getProperty("coreRadiusChatMessagesEnabled"), true);
        this.runSpawn = readTransform(properties, "runSpawn");
        this.baseSpawn = readTransform(properties, "baseSpawn");
        this.rescueRunSpawn = readTransform(properties, "rescueRunSpawn");
        this.rescueRunSpawnsByNpc = readNpcTransforms(properties, "rescueRunSpawnNpc.");
        this.rescuedNpcKeys = new HashSet<>(parseCsv(properties.getProperty("rescuedNpcs")));
        this.crimsonCoreProfilesByWorld = readCrimsonProfiles(properties);
    }

    private synchronized void saveQuietly() {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("templateWorld", this.templateWorldName);
        properties.setProperty("hubWorld", getHubWorldName());
        properties.setProperty("runDurationSeconds", Integer.toString(Math.max(1, this.runDurationSeconds)));
        properties.setProperty("runTimeHourMin", Integer.toString(Math.max(0, Math.min(23, this.runTimeHourMin))));
        properties.setProperty("runTimeHourMax", Integer.toString(Math.max(0, Math.min(23, this.runTimeHourMax))));
        if (this.runSeed == null) {
            properties.remove("runSeed");
        } else {
            properties.setProperty("runSeed", Long.toString(this.runSeed));
        }
        properties.setProperty("statusMessagesEnabled", Boolean.toString(this.statusMessagesEnabled));
        properties.setProperty("chunkLoadingMessagesEnabled", Boolean.toString(this.chunkLoadingMessagesEnabled));
        properties.setProperty("hazardFogWeatherEnabled", Boolean.toString(this.hazardFogWeatherEnabled));
        properties.setProperty("coreRadiusChatMessagesEnabled", Boolean.toString(this.coreRadiusChatMessagesEnabled));
        writeVector3i(properties, "doorBlock", this.doorBlock);
        writeTransform(properties, "runSpawn", this.runSpawn);
        writeTransform(properties, "baseSpawn", this.baseSpawn);
        writeTransform(properties, "rescueRunSpawn", this.rescueRunSpawn);
        writeNpcTransforms(properties, "rescueRunSpawnNpc.", this.rescueRunSpawnsByNpc);
        properties.setProperty("rescuedNpcs", String.join(",", this.rescuedNpcKeys));
        writeCrimsonProfiles(properties, this.crimsonCoreProfilesByWorld);

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

    @Nonnull
    private static HashMap<String, Transform> readNpcTransforms(@Nonnull Properties properties, @Nonnull String prefix) {
        HashMap<String, Transform> out = new HashMap<>();
        HashSet<String> npcKeys = new HashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String suffix = key.substring(prefix.length());
            int dotIndex = suffix.indexOf('.');
            if (dotIndex <= 0) {
                continue;
            }
            String npcKey = normalizeNpcKey(suffix.substring(0, dotIndex));
            if (!npcKey.isEmpty()) {
                npcKeys.add(npcKey);
            }
        }
        for (String npcKey : npcKeys) {
            Transform transform = readTransform(properties, prefix + npcKey);
            if (transform != null) {
                out.put(npcKey, transform);
            }
        }
        return out;
    }

    private static void writeNpcTransforms(
            @Nonnull Properties properties,
            @Nonnull String prefix,
            @Nonnull HashMap<String, Transform> byNpc
    ) {
        for (String key : new ArrayList<>(properties.stringPropertyNames())) {
            if (key.startsWith(prefix)) {
                properties.remove(key);
            }
        }
        for (var entry : byNpc.entrySet()) {
            String npcKey = normalizeNpcKey(entry.getKey());
            if (npcKey.isEmpty()) {
                continue;
            }
            writeTransform(properties, prefix + npcKey, entry.getValue());
        }
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
    private static Long readLong(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean parseBoolean(@Nullable String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
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
    private static ArrayList<RedCoreProfileRegistry.RedCoreProfile> copyAndSortProfiles(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> copy = new ArrayList<>();
        for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
            if (profile == null || profile.corePos() == null) {
                continue;
            }
            copy.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(profile.corePos()), profile.radiusBlocks(), profile.startSeconds()));
        }
        copy.sort(Comparator
                .comparingInt((RedCoreProfileRegistry.RedCoreProfile p) -> p.corePos().x)
                .thenComparingInt(p -> p.corePos().y)
                .thenComparingInt(p -> p.corePos().z));
        return copy;
    }

    @Nonnull
    private static HashMap<String, ArrayList<RedCoreProfileRegistry.RedCoreProfile>> readCrimsonProfiles(@Nonnull Properties properties) {
        HashMap<String, ArrayList<RedCoreProfileRegistry.RedCoreProfile>> out = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("crimsonProfiles.")) {
                continue;
            }
            String worldName = key.substring("crimsonProfiles.".length()).trim();
            if (worldName.isEmpty()) {
                continue;
            }
            String raw = properties.getProperty(key, "");
            ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles = new ArrayList<>();
            if (!raw.isBlank()) {
                for (String token : raw.split(";")) {
                    String item = token.trim();
                    if (item.isEmpty()) {
                        continue;
                    }
                    String[] parts = item.split(":");
                    if (parts.length != 5) {
                        continue;
                    }
                    Integer x = readInt(parts[0]);
                    Integer y = readInt(parts[1]);
                    Integer z = readInt(parts[2]);
                    Integer radius = readInt(parts[3]);
                    Double seconds = readDouble(parts[4]);
                    if (x == null || y == null || z == null || radius == null || seconds == null) {
                        continue;
                    }
                    profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(x, y, z), radius, seconds.floatValue()));
                }
            }
            if (!profiles.isEmpty()) {
                out.put(normalizeWorldName(worldName, DEFAULT_TEMPLATE_WORLD), copyAndSortProfiles(profiles));
            }
        }
        return out;
    }

    private static void writeCrimsonProfiles(
            @Nonnull Properties properties,
            @Nonnull HashMap<String, ArrayList<RedCoreProfileRegistry.RedCoreProfile>> profilesByWorld
    ) {
        for (String key : new ArrayList<>(properties.stringPropertyNames())) {
            if (key.startsWith("crimsonProfiles.")) {
                properties.remove(key);
            }
        }
        for (var entry : profilesByWorld.entrySet()) {
            String worldName = normalizeWorldName(entry.getKey(), DEFAULT_TEMPLATE_WORLD);
            ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles = copyAndSortProfiles(entry.getValue());
            if (profiles.isEmpty()) {
                continue;
            }
            ArrayList<String> tokens = new ArrayList<>();
            for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
                Vector3i pos = profile.corePos();
                tokens.add(pos.x + ":" + pos.y + ":" + pos.z + ":" + profile.radiusBlocks() + ":" + profile.startSeconds());
            }
            properties.setProperty("crimsonProfiles." + worldName, String.join(";", tokens));
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