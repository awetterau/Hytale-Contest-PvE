package dev.hytalemodding.game;

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
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class HubNpcManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "hub-npcs.properties";
    private static final String BLACKSMITH_KEY = "blacksmith";
    private static final long MOVE_TO_WORKSHOP_TIMEOUT_MS = 12_000L;
    private static final HubNpcManager INSTANCE = new HubNpcManager();

    private final ConcurrentHashMap<String, NpcData> npcs = new ConcurrentHashMap<>();
    private boolean loaded;

    private HubNpcManager() {
    }

    @Nonnull
    public static HubNpcManager get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized NpcData getOrCreate(@Nonnull String profession) {
        ensureLoaded();
        NpcData existing = this.npcs.get(normalizeProfession(profession));
        if (existing != null) {
            return existing.copy();
        }
        NpcData created = new NpcData(
                normalizeProfession(profession),
                1,
                null,
                List.of(),
                List.of(),
                HubNpcState.WANDERING,
                0L
        );
        this.npcs.put(created.profession, created);
        saveQuietly();
        return created.copy();
    }

    @Nullable
    public synchronized NpcData getNpc(@Nonnull String profession) {
        ensureLoaded();
        NpcData npc = this.npcs.get(normalizeProfession(profession));
        return npc == null ? null : npc.copy();
    }

    @Nonnull
    public synchronized HubNpcState getState(@Nonnull String profession) {
        ensureLoaded();
        NpcData npc = this.npcs.get(normalizeProfession(profession));
        return npc == null ? HubNpcState.WANDERING : npc.state;
    }

    public synchronized boolean isWorking(@Nonnull String profession) {
        ensureLoaded();
        NpcData npc = this.npcs.get(normalizeProfession(profession));
        return npc != null && npc.state == HubNpcState.WORKING;
    }

    public synchronized void setWandering(@Nonnull String profession) {
        ensureLoaded();
        String key = normalizeProfession(profession);
        NpcData current = getOrCreate(key);
        this.npcs.put(key, current.withAssignmentAndState(null, HubNpcState.WANDERING, 0L));
        saveQuietly();
    }

    public synchronized void startMovingToWorkshop(@Nonnull String profession, @Nonnull String assignedPlotId) {
        ensureLoaded();
        String key = normalizeProfession(profession);
        NpcData current = getOrCreate(key);
        this.npcs.put(
                key,
                current.withAssignmentAndState(assignedPlotId, HubNpcState.MOVING_TO_WORKSHOP, System.currentTimeMillis())
        );
        saveQuietly();
    }

    public synchronized void promoteToWorkingIfReady(@Nonnull String profession, boolean reachedWorkshop) {
        ensureLoaded();
        String key = normalizeProfession(profession);
        NpcData current = this.npcs.get(key);
        if (current == null || current.state != HubNpcState.MOVING_TO_WORKSHOP) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean timedOut = current.movingSinceMs > 0L && now - current.movingSinceMs >= MOVE_TO_WORKSHOP_TIMEOUT_MS;
        if (!reachedWorkshop && !timedOut) {
            return;
        }
        this.npcs.put(key, current.withAssignmentAndState(current.assignedPlotId, HubNpcState.WORKING, 0L));
        saveQuietly();
    }

    public synchronized void clearAssignment(@Nonnull String profession) {
        ensureLoaded();
        String key = normalizeProfession(profession);
        NpcData current = getOrCreate(key);
        this.npcs.put(key, current.withAssignmentAndState(null, HubNpcState.WANDERING, 0L));
        saveQuietly();
    }

    public synchronized boolean devSetState(@Nonnull String profession, @Nonnull HubNpcState state) {
        ensureLoaded();
        String key = normalizeProfession(profession);
        if (key.isEmpty()) {
            return false;
        }
        NpcData current = getOrCreate(key);
        long movingSince = state == HubNpcState.MOVING_TO_WORKSHOP ? System.currentTimeMillis() : 0L;
        this.npcs.put(key, current.withAssignmentAndState(current.assignedPlotId, state, movingSince));
        saveQuietly();
        return true;
    }

    public synchronized boolean devAssign(@Nonnull String profession, @Nullable String assignedPlotId, @Nonnull HubNpcState state) {
        ensureLoaded();
        String key = normalizeProfession(profession);
        if (key.isEmpty()) {
            return false;
        }
        String normalizedPlot = normalizeNullable(assignedPlotId);
        NpcData current = getOrCreate(key);
        long movingSince = state == HubNpcState.MOVING_TO_WORKSHOP ? System.currentTimeMillis() : 0L;
        this.npcs.put(key, current.withAssignmentAndState(normalizedPlot, state, movingSince));
        saveQuietly();
        return true;
    }

    @Nonnull
    public synchronized List<String> describeAll() {
        ensureLoaded();
        List<String> lines = new ArrayList<>();
        if (this.npcs.isEmpty()) {
            lines.add("<none>");
            return lines;
        }
        for (NpcData npc : this.npcs.values()) {
            lines.add(
                    npc.profession
                            + " lvl=" + npc.level
                            + " state=" + npc.state.name()
                            + " assignedPlot=" + (npc.assignedPlotId == null ? "<none>" : npc.assignedPlotId)
                            + " recipes=" + npc.unlockedRecipes.size()
                            + " quests=" + npc.availableQuests.size()
            );
        }
        lines.sort(String::compareToIgnoreCase);
        return lines;
    }

    public synchronized void resetAll() {
        ensureLoaded();
        this.npcs.clear();
        ensureDefaultNpc();
        saveQuietly();
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        Path path = getConfigFilePath();
        if (path == null || !Files.exists(path)) {
            ensureDefaultNpc();
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("[HubNpc] Failed to load config: " + e.getMessage());
            ensureDefaultNpc();
            return;
        }

        String ids = properties.getProperty("npcs", "");
        for (String rawId : ids.split(",")) {
            String id = normalizeProfession(rawId);
            if (id.isEmpty()) {
                continue;
            }
            NpcData npc = readNpc(properties, id);
            if (npc != null) {
                this.npcs.put(id, npc);
            }
        }
        ensureDefaultNpc();
    }

    private synchronized void ensureDefaultNpc() {
        this.npcs.computeIfAbsent(
                BLACKSMITH_KEY,
                ignored -> new NpcData(BLACKSMITH_KEY, 1, null, List.of(), List.of(), HubNpcState.WANDERING, 0L)
        );
    }

    private synchronized void saveQuietly() {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("npcs", String.join(",", this.npcs.keySet()));
        for (Map.Entry<String, NpcData> entry : this.npcs.entrySet()) {
            writeNpc(properties, entry.getValue());
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Hub NPC configuration");
            }
        } catch (IOException e) {
            System.out.println("[HubNpc] Failed to save config: " + e.getMessage());
        }
    }

    @Nullable
    private static NpcData readNpc(@Nonnull Properties p, @Nonnull String profession) {
        String prefix = "npc." + profession + ".";
        Integer levelRaw = readInt(p.getProperty(prefix + "level"));
        int level = levelRaw == null ? 1 : Math.max(1, levelRaw);
        String assignedPlot = normalizeNullable(p.getProperty(prefix + "assignedPlot"));
        HubNpcState state = parseState(p.getProperty(prefix + "state"));
        Long movingSince = readLong(p.getProperty(prefix + "movingSinceMs"));
        List<String> unlockedRecipes = parseCsv(p.getProperty(prefix + "unlockedRecipes"));
        List<String> availableQuests = parseCsv(p.getProperty(prefix + "availableQuests"));
        return new NpcData(
                profession,
                level,
                assignedPlot,
                unlockedRecipes,
                availableQuests,
                state,
                movingSince == null ? 0L : Math.max(0L, movingSince)
        );
    }

    private static void writeNpc(@Nonnull Properties p, @Nonnull NpcData npc) {
        String prefix = "npc." + npc.profession + ".";
        p.setProperty(prefix + "level", Integer.toString(npc.level));
        p.setProperty(prefix + "assignedPlot", npc.assignedPlotId == null ? "" : npc.assignedPlotId);
        p.setProperty(prefix + "state", npc.state.name());
        p.setProperty(prefix + "movingSinceMs", Long.toString(npc.movingSinceMs));
        p.setProperty(prefix + "unlockedRecipes", String.join(",", npc.unlockedRecipes));
        p.setProperty(prefix + "availableQuests", String.join(",", npc.availableQuests));
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

    @Nonnull
    private static String normalizeProfession(@Nullable String profession) {
        if (profession == null) {
            return "";
        }
        return profession.trim().toLowerCase(Locale.ROOT);
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
    private static HubNpcState parseState(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return HubNpcState.WANDERING;
        }
        try {
            return HubNpcState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return HubNpcState.WANDERING;
        }
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = item == null ? "" : item.trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    public enum HubNpcState {
        WANDERING,
        MOVING_TO_WORKSHOP,
        WORKING
    }

    public static final class NpcData {
        @Nonnull
        public final String profession;
        public final int level;
        @Nullable
        public final String assignedPlotId;
        @Nonnull
        public final List<String> unlockedRecipes;
        @Nonnull
        public final List<String> availableQuests;
        @Nonnull
        public final HubNpcState state;
        public final long movingSinceMs;

        private NpcData(
                @Nonnull String profession,
                int level,
                @Nullable String assignedPlotId,
                @Nonnull List<String> unlockedRecipes,
                @Nonnull List<String> availableQuests,
                @Nonnull HubNpcState state,
                long movingSinceMs
        ) {
            this.profession = profession;
            this.level = level;
            this.assignedPlotId = assignedPlotId;
            this.unlockedRecipes = List.copyOf(unlockedRecipes);
            this.availableQuests = List.copyOf(availableQuests);
            this.state = state;
            this.movingSinceMs = movingSinceMs;
        }

        @Nonnull
        private NpcData withAssignmentAndState(
                @Nullable String assignedPlotId,
                @Nonnull HubNpcState state,
                long movingSinceMs
        ) {
            return new NpcData(
                    this.profession,
                    this.level,
                    assignedPlotId,
                    this.unlockedRecipes,
                    this.availableQuests,
                    state,
                    movingSinceMs
            );
        }

        @Nonnull
        private NpcData copy() {
            return new NpcData(
                    this.profession,
                    this.level,
                    this.assignedPlotId,
                    this.unlockedRecipes,
                    this.availableQuests,
                    this.state,
                    this.movingSinceMs
            );
        }
    }
}


