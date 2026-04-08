package dev.hytalemodding.npc;

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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcProgressManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "npc-progress.properties";
    private static final NpcProgressManager INSTANCE = new NpcProgressManager();

    private final ConcurrentHashMap<String, NpcProgress> progressByNpc = new ConcurrentHashMap<>();
    private boolean loaded;

    private NpcProgressManager() {
    }

    @Nonnull
    public static NpcProgressManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        ensureLoaded();
    }

    @Nonnull
    public synchronized NpcProgress getOrCreate(@Nonnull String npcKey) {
        ensureLoaded();
        String key = normalize(npcKey);
        if (key.isEmpty()) {
            key = "unknown";
        }
        NpcProgress existing = this.progressByNpc.get(key);
        if (existing != null) {
            return existing.copy();
        }
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(key);
        NpcProgress created = NpcProgress.defaultFor(
                key,
                archetype == null ? List.of() : archetype.defaultCraftUnlocks,
                archetype == null ? List.of() : archetype.defaultTradeUnlocks
        );
        this.progressByNpc.put(key, created);
        saveQuietly();
        return created.copy();
    }

    public synchronized boolean isNpcRescued(@Nonnull String npcKey) {
        ensureLoaded();
        return getOrCreate(npcKey).rescued;
    }

    @Nonnull
    public synchronized Set<String> getUnlockedCrafts(@Nonnull String npcKey) {
        ensureLoaded();
        return getOrCreate(npcKey).unlockedCrafts;
    }

    @Nonnull
    public synchronized Set<String> getUnlockedTrades(@Nonnull String npcKey) {
        ensureLoaded();
        return getOrCreate(npcKey).unlockedTrades;
    }

    public synchronized void grantCraftUnlocks(@Nonnull String npcKey, @Nonnull List<String> unlocks) {
        ensureLoaded();
        String key = normalize(npcKey);
        if (key.isBlank() || unlocks.isEmpty()) {
            return;
        }
        NpcProgress current = getOrCreate(key);
        this.progressByNpc.put(key, current.withAddedCraftUnlocks(unlocks));
        saveQuietly();
    }

    public synchronized void grantTradeUnlocks(@Nonnull String npcKey, @Nonnull List<String> unlocks) {
        ensureLoaded();
        String key = normalize(npcKey);
        if (key.isBlank() || unlocks.isEmpty()) {
            return;
        }
        NpcProgress current = getOrCreate(key);
        this.progressByNpc.put(key, current.withAddedTradeUnlocks(unlocks));
        saveQuietly();
    }

    public synchronized void setNpcRescued(@Nonnull String npcKey, boolean rescued) {
        ensureLoaded();
        String key = normalize(npcKey);
        if (key.isEmpty()) {
            return;
        }
        NpcProgress current = getOrCreate(key);
        NpcProgressState nextState;
        if (!rescued) {
            nextState = NpcProgressState.UNRESCUED;
        } else if (current.assignedPlotId == null) {
            nextState = NpcProgressState.RESCUED_UNASSIGNED;
        } else {
            nextState = current.state;
        }
        this.progressByNpc.put(
                key,
                current.withRescuedState(
                        rescued,
                        nextState,
                        System.currentTimeMillis()
                )
        );
        saveQuietly();
    }

    public synchronized void setUpgradeTier(@Nonnull String npcKey, int tier) {
        ensureLoaded();
        String key = normalize(npcKey);
        if (key.isBlank()) {
            return;
        }
        NpcProgress current = getOrCreate(key);
        int nextTier = Math.max(0, tier);
        int nextLevel = Math.max(current.level, nextTier + 1);
        this.progressByNpc.put(key, current.withTierAndLevel(nextTier, nextLevel));
        saveQuietly();
    }

    public synchronized void devOverwriteProgress(
            @Nonnull String npcKey,
            boolean rescued,
            @Nonnull NpcProgressState state,
            @Nullable String assignedPlotId,
            int level,
            int upgradeTier,
            @Nonnull List<String> unlockedCrafts,
            @Nonnull List<String> unlockedTrades
    ) {
        ensureLoaded();
        String key = normalize(npcKey);
        if (key.isBlank()) {
            return;
        }
        this.progressByNpc.put(
                key,
                new NpcProgress(
                        key,
                        rescued,
                        state,
                        assignedPlotId,
                        Math.max(1, level),
                        Math.max(0, upgradeTier),
                        System.currentTimeMillis(),
                        Set.copyOf(unlockedCrafts),
                        Set.copyOf(unlockedTrades)
                )
        );
        saveQuietly();
    }

    public synchronized void resetAllToDefaults() {
        ensureLoaded();
        this.progressByNpc.clear();
        for (NpcArchetype archetype : NpcDefinitionRegistry.get().getAll()) {
            this.progressByNpc.put(
                    archetype.npcKey,
                    NpcProgress.defaultFor(
                            archetype.npcKey,
                            archetype.defaultCraftUnlocks,
                            archetype.defaultTradeUnlocks
                    )
            );
        }
        saveQuietly();
    }

    @Nonnull
    public synchronized List<String> describeAll() {
        ensureLoaded();
        List<String> lines = new ArrayList<>();
        for (NpcProgress p : this.progressByNpc.values()) {
            lines.add(
                    p.npcKey
                            + " rescued=" + p.rescued
                            + " state=" + p.state.name()
                            + " assignedPlot=" + (p.assignedPlotId == null ? "<none>" : p.assignedPlotId)
                            + " tier=" + p.upgradeTier
                            + " level=" + p.level
                            + " craftUnlocks=" + p.unlockedCrafts.size()
                            + " tradeUnlocks=" + p.unlockedTrades.size()
            );
        }
        if (lines.isEmpty()) {
            lines.add("<none>");
        }
        lines.sort(String::compareToIgnoreCase);
        return lines;
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;

        Path path = getConfigPath();
        if (path != null && Files.exists(path)) {
            loadFromProperties(path);
        }
    }

    private void loadFromProperties(@Nonnull Path path) {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            System.out.println("[NpcProgress] Failed to load: " + e.getMessage());
            return;
        }

        for (String npcKey : parseCsv(p.getProperty("npcs"))) {
            NpcProgress parsed = NpcProgress.fromProperties(p, npcKey);
            if (parsed != null) {
                this.progressByNpc.put(parsed.npcKey, parsed);
            }
        }
    }

    private synchronized void saveQuietly() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }

        Properties p = new Properties();
        p.setProperty("npcs", String.join(",", this.progressByNpc.keySet()));
        for (Map.Entry<String, NpcProgress> entry : this.progressByNpc.entrySet()) {
            entry.getValue().writeToProperties(p);
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(writer, "NPC progression");
            }
        } catch (IOException e) {
            System.out.println("[NpcProgress] Failed to save: " + e.getMessage());
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
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String normalizeNullable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = normalize(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    public enum NpcProgressState {
        UNRESCUED,
        RESCUED_UNASSIGNED,
        MOVING_TO_HOME,
        ACTIVE_AT_HOME,
        UPGRADING,
        BUSY;

        @Nonnull
        private static NpcProgressState fromRaw(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return UNRESCUED;
            }
            try {
                return NpcProgressState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNRESCUED;
            }
        }
    }

    public static final class NpcProgress {
        @Nonnull
        public final String npcKey;
        public final boolean rescued;
        @Nonnull
        public final NpcProgressState state;
        @Nullable
        public final String assignedPlotId;
        public final int level;
        public final int upgradeTier;
        public final long lastStateChangeMs;
        @Nonnull
        public final Set<String> unlockedCrafts;
        @Nonnull
        public final Set<String> unlockedTrades;

        private NpcProgress(
                @Nonnull String npcKey,
                boolean rescued,
                @Nonnull NpcProgressState state,
                @Nullable String assignedPlotId,
                int level,
                int upgradeTier,
                long lastStateChangeMs,
                @Nonnull Set<String> unlockedCrafts,
                @Nonnull Set<String> unlockedTrades
        ) {
            this.npcKey = normalize(npcKey);
            this.rescued = rescued;
            this.state = state;
            this.assignedPlotId = normalizeNullable(assignedPlotId);
            this.level = Math.max(1, level);
            this.upgradeTier = Math.max(0, upgradeTier);
            this.lastStateChangeMs = Math.max(0L, lastStateChangeMs);
            this.unlockedCrafts = Set.copyOf(unlockedCrafts);
            this.unlockedTrades = Set.copyOf(unlockedTrades);
        }

        @Nonnull
        public static NpcProgress defaultFor(@Nonnull String npcKey, @Nonnull List<String> defaultCraftUnlocks, @Nonnull List<String> defaultTradeUnlocks) {
            return new NpcProgress(
                    npcKey,
                    false,
                    NpcProgressState.UNRESCUED,
                    null,
                    1,
                    0,
                    0L,
                    Set.copyOf(defaultCraftUnlocks),
                    Set.copyOf(defaultTradeUnlocks)
            );
        }

        @Nullable
        private static NpcProgress fromProperties(@Nonnull Properties p, @Nonnull String npcKey) {
            String key = normalize(npcKey);
            if (key.isEmpty()) {
                return null;
            }
            String prefix = "npc." + key + ".";
            boolean rescued = Boolean.parseBoolean(p.getProperty(prefix + "rescued", "false"));
            NpcProgressState state = NpcProgressState.fromRaw(p.getProperty(prefix + "state"));
            String assignedPlotId = normalizeNullable(p.getProperty(prefix + "assignedPlotId"));
            Integer level = readInt(p.getProperty(prefix + "level"));
            Integer tier = readInt(p.getProperty(prefix + "upgradeTier"));
            Long lastStateChangeMs = readLong(p.getProperty(prefix + "lastStateChangeMs"));
            Set<String> unlockedCrafts = Set.copyOf(parseCsv(p.getProperty(prefix + "unlockedCrafts")));
            Set<String> unlockedTrades = Set.copyOf(parseCsv(p.getProperty(prefix + "unlockedTrades")));
            return new NpcProgress(
                    key,
                    rescued,
                    state,
                    assignedPlotId,
                    level == null ? 1 : level,
                    tier == null ? 0 : tier,
                    lastStateChangeMs == null ? 0L : lastStateChangeMs,
                    unlockedCrafts,
                    unlockedTrades
            );
        }

        private void writeToProperties(@Nonnull Properties p) {
            String prefix = "npc." + this.npcKey + ".";
            p.setProperty(prefix + "rescued", Boolean.toString(this.rescued));
            p.setProperty(prefix + "state", this.state.name());
            p.setProperty(prefix + "assignedPlotId", this.assignedPlotId == null ? "" : this.assignedPlotId);
            p.setProperty(prefix + "level", Integer.toString(this.level));
            p.setProperty(prefix + "upgradeTier", Integer.toString(this.upgradeTier));
            p.setProperty(prefix + "lastStateChangeMs", Long.toString(this.lastStateChangeMs));
            p.setProperty(prefix + "unlockedCrafts", String.join(",", this.unlockedCrafts));
            p.setProperty(prefix + "unlockedTrades", String.join(",", this.unlockedTrades));
        }

        @Nonnull
        private NpcProgress withRescuedState(
                boolean rescued,
                @Nonnull NpcProgressState state,
                long lastStateChangeMs
        ) {
            return new NpcProgress(
                    this.npcKey,
                    rescued,
                    state,
                    this.assignedPlotId,
                    this.level,
                    this.upgradeTier,
                    lastStateChangeMs,
                    this.unlockedCrafts,
                    this.unlockedTrades
            );
        }

        @Nonnull
        private NpcProgress withAddedCraftUnlocks(@Nonnull List<String> unlocks) {
            Set<String> next = new HashSet<>(this.unlockedCrafts);
            for (String unlock : unlocks) {
                String normalized = normalize(unlock);
                if (!normalized.isBlank()) {
                    next.add(normalized);
                }
            }
            return new NpcProgress(
                    this.npcKey,
                    this.rescued,
                    this.state,
                    this.assignedPlotId,
                    this.level,
                    this.upgradeTier,
                    this.lastStateChangeMs,
                    next,
                    this.unlockedTrades
            );
        }

        @Nonnull
        private NpcProgress withAddedTradeUnlocks(@Nonnull List<String> unlocks) {
            Set<String> next = new HashSet<>(this.unlockedTrades);
            for (String unlock : unlocks) {
                String normalized = normalize(unlock);
                if (!normalized.isBlank()) {
                    next.add(normalized);
                }
            }
            return new NpcProgress(
                    this.npcKey,
                    this.rescued,
                    this.state,
                    this.assignedPlotId,
                    this.level,
                    this.upgradeTier,
                    this.lastStateChangeMs,
                    this.unlockedCrafts,
                    next
            );
        }

        @Nonnull
        private NpcProgress copy() {
            return new NpcProgress(
                    this.npcKey,
                    this.rescued,
                    this.state,
                    this.assignedPlotId,
                    this.level,
                    this.upgradeTier,
                    this.lastStateChangeMs,
                    this.unlockedCrafts,
                    this.unlockedTrades
            );
        }

        @Nonnull
        private NpcProgress withTierAndLevel(int tier, int level) {
            return new NpcProgress(
                    this.npcKey,
                    this.rescued,
                    this.state,
                    this.assignedPlotId,
                    Math.max(1, level),
                    Math.max(0, tier),
                    this.lastStateChangeMs,
                    this.unlockedCrafts,
                    this.unlockedTrades
            );
        }
    }
}



