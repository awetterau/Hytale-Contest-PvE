package dev.hytalemodding.npc.state;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.hytalemodding.npc.config.NpcMigrationService;
import dev.hytalemodding.npc.config.NpcUnifiedRegistry;
import dev.hytalemodding.npc.core.NpcDefinition;

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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcStateManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "npc-state-v2.properties";
    private static final NpcStateManager INSTANCE = new NpcStateManager();

    private final ConcurrentHashMap<String, NpcRuntimeState> stateByNpc = new ConcurrentHashMap<>();
    private boolean loaded;

    private NpcStateManager() {
    }

    @Nonnull
    public static NpcStateManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        NpcUnifiedRegistry.get().initialize();
        Path path = getConfigPath();
        if (path != null && Files.exists(path)) {
            loadFromFile(path);
        } else {
            migrateFromLegacy();
            saveQuietly();
        }
        reconcileLoadedStatesWithSources();
        pruneMissingDefinitions();
        ensureAllDefinitionsPresent();
        System.out.println("[NpcStateV2] Loaded unified NPC runtime states: " + this.stateByNpc.size());
    }

    @Nonnull
    public synchronized NpcRuntimeState getState(@Nonnull String npcKey) {
        initialize();
        String normalizedNpcKey = NpcRuntimeState.normalize(npcKey);
        NpcRuntimeState existing = this.stateByNpc.get(normalizedNpcKey);
        if (existing != null) {
            return existing.copy();
        }
        NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(normalizedNpcKey);
        if (definition == null) {
            return new NpcRuntimeState(
                    normalizedNpcKey,
                    false,
                    NpcRuntimeState.PresenceMode.HIDDEN,
                    NpcDefinition.HubBehaviorMode.STANDING,
                    null,
                    0,
                    0L,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    null,
                    null
            );
        }
        NpcRuntimeState migrated = NpcMigrationService.get().buildRuntimeState(definition);
        this.stateByNpc.put(normalizedNpcKey, migrated);
        saveQuietly();
        return migrated.copy();
    }

    @Nonnull
    public synchronized List<String> describeAll() {
        initialize();
        ArrayList<String> lines = new ArrayList<>();
        for (NpcRuntimeState state : this.stateByNpc.values()) {
            lines.add(
                    state.npcKey
                            + " rescued=" + state.rescued
                            + " presence=" + state.presenceMode.name()
                            + " hubBehavior=" + state.hubBehavior.name()
                            + " workstation=" + (state.assignedWorkstationId == null ? "<none>" : state.assignedWorkstationId)
                            + " tier=" + state.workstationLevel
                            + " acceptedQuests=" + state.acceptedQuestIds.size()
                            + " completedQuests=" + state.completedQuestIds.size()
            );
        }
        if (lines.isEmpty()) {
            lines.add("<none>");
        }
        lines.sort(String::compareToIgnoreCase);
        return List.copyOf(lines);
    }

    public synchronized void resetNpcToMigratedDefaults(@Nonnull String npcKey) {
        initialize();
        NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(npcKey);
        if (definition == null) {
            return;
        }
        this.stateByNpc.put(definition.npcKey, NpcMigrationService.get().buildRuntimeState(definition));
        saveQuietly();
    }

    public synchronized void setRescued(@Nonnull String npcKey, boolean rescued) {
        initialize();
        String key = NpcRuntimeState.normalize(npcKey);
        NpcRuntimeState current = getState(key);
        NpcRuntimeState.PresenceMode nextPresence = current.presenceMode;
        if (rescued && nextPresence == NpcRuntimeState.PresenceMode.RUN_RESCUE_OBJECTIVE) {
            nextPresence = NpcRuntimeState.PresenceMode.HUB;
        } else if (!rescued) {
            NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(key);
            nextPresence = definition != null && definition.rescue.enabled
                    ? NpcRuntimeState.PresenceMode.RUN_RESCUE_OBJECTIVE
                    : NpcRuntimeState.PresenceMode.HIDDEN;
        }
        this.stateByNpc.put(key, new NpcRuntimeState(
                current.npcKey,
                rescued,
                nextPresence,
                current.hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setPresenceMode(@Nonnull String npcKey, @Nonnull NpcRuntimeState.PresenceMode presenceMode) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                presenceMode,
                current.hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setHubBehavior(@Nonnull String npcKey, @Nonnull NpcDefinition.HubBehaviorMode hubBehavior) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setHubSpawnOverride(@Nonnull String npcKey, @Nullable Transform transform) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                current.hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                transform,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setRescueSpawnOverride(@Nonnull String npcKey, @Nullable Transform transform) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                current.hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                transform
        ));
        saveQuietly();
    }

    public synchronized void setAssignedWorkstationId(@Nonnull String npcKey, @Nullable String assignedWorkstationId) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                current.hubBehavior,
                NpcRuntimeState.normalizeNullable(assignedWorkstationId),
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setWorkstationLevel(@Nonnull String npcKey, int workstationLevel) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                current.hubBehavior,
                current.assignedWorkstationId,
                Math.max(0, workstationLevel),
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setAcceptedQuestIds(@Nonnull String npcKey, @Nonnull Set<String> acceptedQuestIds) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                current.hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                acceptedQuestIds,
                current.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    public synchronized void setCompletedQuestIds(@Nonnull String npcKey, @Nonnull Set<String> completedQuestIds) {
        initialize();
        NpcRuntimeState current = getState(npcKey);
        this.stateByNpc.put(current.npcKey, new NpcRuntimeState(
                current.npcKey,
                current.rescued,
                current.presenceMode,
                current.hubBehavior,
                current.assignedWorkstationId,
                current.workstationLevel,
                System.currentTimeMillis(),
                current.unlockedCrafts,
                current.unlockedTrades,
                current.acceptedQuestIds,
                completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        ));
        saveQuietly();
    }

    private void migrateFromLegacy() {
        for (NpcDefinition definition : NpcUnifiedRegistry.get().getAll()) {
            this.stateByNpc.put(definition.npcKey, NpcMigrationService.get().buildRuntimeState(definition));
        }
    }

    private void ensureAllDefinitionsPresent() {
        for (NpcDefinition definition : NpcUnifiedRegistry.get().getAll()) {
            this.stateByNpc.computeIfAbsent(definition.npcKey, ignored -> NpcMigrationService.get().buildRuntimeState(definition));
        }
    }

    private void reconcileLoadedStatesWithSources() {
        boolean changed = false;
        for (NpcDefinition definition : NpcUnifiedRegistry.get().getAll()) {
            NpcRuntimeState migrated = NpcMigrationService.get().buildRuntimeState(definition);
            NpcRuntimeState current = this.stateByNpc.get(definition.npcKey);
            if (current == null) {
                this.stateByNpc.put(definition.npcKey, migrated);
                changed = true;
                continue;
            }

            NpcRuntimeState reconciled = mergeWithCurrentOverrides(current, migrated);
            if (!statesEqualForPersistence(current, reconciled)) {
                this.stateByNpc.put(definition.npcKey, reconciled);
                changed = true;
            }
        }
        if (changed) {
            saveQuietly();
        }
    }

    @Nonnull
    private static NpcRuntimeState mergeWithCurrentOverrides(@Nonnull NpcRuntimeState current, @Nonnull NpcRuntimeState migrated) {
        return new NpcRuntimeState(
                migrated.npcKey,
                migrated.rescued,
                migrated.presenceMode,
                migrated.hubBehavior,
                migrated.assignedWorkstationId,
                migrated.workstationLevel,
                Math.max(current.lastStateChangeMs, migrated.lastStateChangeMs),
                migrated.unlockedCrafts,
                migrated.unlockedTrades,
                migrated.acceptedQuestIds,
                migrated.completedQuestIds,
                current.hubSpawnOverride,
                current.rescueSpawnOverride
        );
    }

    private static boolean statesEqualForPersistence(@Nonnull NpcRuntimeState a, @Nonnull NpcRuntimeState b) {
        return a.npcKey.equals(b.npcKey)
                && a.rescued == b.rescued
                && a.presenceMode == b.presenceMode
                && a.hubBehavior == b.hubBehavior
                && safeEquals(a.assignedWorkstationId, b.assignedWorkstationId)
                && a.workstationLevel == b.workstationLevel
                && a.lastStateChangeMs == b.lastStateChangeMs
                && a.unlockedCrafts.equals(b.unlockedCrafts)
                && a.unlockedTrades.equals(b.unlockedTrades)
                && a.acceptedQuestIds.equals(b.acceptedQuestIds)
                && a.completedQuestIds.equals(b.completedQuestIds)
                && transformsEqual(a.hubSpawnOverride, b.hubSpawnOverride)
                && transformsEqual(a.rescueSpawnOverride, b.rescueSpawnOverride);
    }

    private static boolean safeEquals(@Nullable String a, @Nullable String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static boolean transformsEqual(@Nullable Transform a, @Nullable Transform b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Double.compare(a.getPosition().getX(), b.getPosition().getX()) == 0
                && Double.compare(a.getPosition().getY(), b.getPosition().getY()) == 0
                && Double.compare(a.getPosition().getZ(), b.getPosition().getZ()) == 0
                && Float.compare(a.getRotation().getX(), b.getRotation().getX()) == 0
                && Float.compare(a.getRotation().getY(), b.getRotation().getY()) == 0
                && Float.compare(a.getRotation().getZ(), b.getRotation().getZ()) == 0;
    }

    public synchronized void resetAllToMigratedDefaults() {
        initialize();
        this.stateByNpc.clear();
        migrateFromLegacy();
        saveQuietly();
    }

    private void pruneMissingDefinitions() {
        ArrayList<String> toRemove = new ArrayList<>();
        for (String npcKey : this.stateByNpc.keySet()) {
            if (NpcUnifiedRegistry.get().getNpc(npcKey) == null) {
                toRemove.add(npcKey);
            }
        }
        if (toRemove.isEmpty()) {
            return;
        }
        for (String npcKey : toRemove) {
            this.stateByNpc.remove(npcKey);
        }
        saveQuietly();
    }

    private void loadFromFile(@Nonnull Path path) {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            System.out.println("[NpcStateV2] Failed to load config: " + e.getMessage());
            return;
        }
        for (String npcKey : parseCsv(p.getProperty("npcs"))) {
            NpcRuntimeState state = parseState(p, npcKey);
            if (state != null) {
                this.stateByNpc.put(state.npcKey, state);
            }
        }
    }

    @Nullable
    private static NpcRuntimeState parseState(@Nonnull Properties p, @Nonnull String npcKey) {
        String normalizedNpcKey = NpcRuntimeState.normalize(npcKey);
        if (normalizedNpcKey.isEmpty()) {
            return null;
        }
        String prefix = "npc." + normalizedNpcKey + ".";
        boolean rescued = Boolean.parseBoolean(p.getProperty(prefix + "rescued", "false"));
        NpcRuntimeState.PresenceMode presenceMode = readPresenceMode(p.getProperty(prefix + "presenceMode"));
        NpcDefinition.HubBehaviorMode hubBehavior = readHubBehavior(p.getProperty(prefix + "hubBehavior"));
        String assignedWorkstationId = NpcRuntimeState.normalizeNullable(p.getProperty(prefix + "assignedWorkstationId"));
        int workstationLevel = readInt(p.getProperty(prefix + "workstationLevel"), 0);
        long lastStateChangeMs = readLong(p.getProperty(prefix + "lastStateChangeMs"), 0L);
        Set<String> unlockedCrafts = Set.copyOf(parseCsv(p.getProperty(prefix + "unlockedCrafts")));
        Set<String> unlockedTrades = Set.copyOf(parseCsv(p.getProperty(prefix + "unlockedTrades")));
        Set<String> acceptedQuestIds = Set.copyOf(parseCsv(p.getProperty(prefix + "acceptedQuestIds")));
        Set<String> completedQuestIds = Set.copyOf(parseCsv(p.getProperty(prefix + "completedQuestIds")));
        Transform hubSpawnOverride = readTransform(p, prefix + "hubSpawnOverride.");
        Transform rescueSpawnOverride = readTransform(p, prefix + "rescueSpawnOverride.");
        return new NpcRuntimeState(
                normalizedNpcKey,
                rescued,
                presenceMode,
                hubBehavior,
                assignedWorkstationId,
                workstationLevel,
                lastStateChangeMs,
                unlockedCrafts,
                unlockedTrades,
                acceptedQuestIds,
                completedQuestIds,
                hubSpawnOverride,
                rescueSpawnOverride
        );
    }

    private synchronized void saveQuietly() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("npcs", String.join(",", this.stateByNpc.keySet()));
        for (Map.Entry<String, NpcRuntimeState> entry : this.stateByNpc.entrySet()) {
            writeState(p, entry.getValue());
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(writer, "Unified NPC runtime state");
            }
        } catch (IOException e) {
            System.out.println("[NpcStateV2] Failed to save config: " + e.getMessage());
        }
    }

    private static void writeState(@Nonnull Properties p, @Nonnull NpcRuntimeState state) {
        String prefix = "npc." + state.npcKey + ".";
        p.setProperty(prefix + "rescued", Boolean.toString(state.rescued));
        p.setProperty(prefix + "presenceMode", state.presenceMode.name());
        p.setProperty(prefix + "hubBehavior", state.hubBehavior.name());
        p.setProperty(prefix + "assignedWorkstationId", state.assignedWorkstationId == null ? "" : state.assignedWorkstationId);
        p.setProperty(prefix + "workstationLevel", Integer.toString(state.workstationLevel));
        p.setProperty(prefix + "lastStateChangeMs", Long.toString(state.lastStateChangeMs));
        p.setProperty(prefix + "unlockedCrafts", String.join(",", state.unlockedCrafts));
        p.setProperty(prefix + "unlockedTrades", String.join(",", state.unlockedTrades));
        p.setProperty(prefix + "acceptedQuestIds", String.join(",", state.acceptedQuestIds));
        p.setProperty(prefix + "completedQuestIds", String.join(",", state.completedQuestIds));
        writeTransform(p, prefix + "hubSpawnOverride.", state.hubSpawnOverride);
        writeTransform(p, prefix + "rescueSpawnOverride.", state.rescueSpawnOverride);
    }

    private static void writeTransform(@Nonnull Properties p, @Nonnull String prefix, @Nullable Transform transform) {
        if (transform == null) {
            p.setProperty(prefix + "x", "");
            p.setProperty(prefix + "y", "");
            p.setProperty(prefix + "z", "");
            p.setProperty(prefix + "pitch", "");
            p.setProperty(prefix + "yaw", "");
            p.setProperty(prefix + "roll", "");
            return;
        }
        p.setProperty(prefix + "x", Double.toString(transform.getPosition().getX()));
        p.setProperty(prefix + "y", Double.toString(transform.getPosition().getY()));
        p.setProperty(prefix + "z", Double.toString(transform.getPosition().getZ()));
        p.setProperty(prefix + "pitch", Float.toString(transform.getRotation().getX()));
        p.setProperty(prefix + "yaw", Float.toString(transform.getRotation().getY()));
        p.setProperty(prefix + "roll", Float.toString(transform.getRotation().getZ()));
    }

    @Nullable
    private static Transform readTransform(@Nonnull Properties p, @Nonnull String prefix) {
        Double x = readDouble(p.getProperty(prefix + "x"));
        Double y = readDouble(p.getProperty(prefix + "y"));
        Double z = readDouble(p.getProperty(prefix + "z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        float pitch = (float) readDouble(p.getProperty(prefix + "pitch"), 0.0);
        float yaw = (float) readDouble(p.getProperty(prefix + "yaw"), 0.0);
        float roll = (float) readDouble(p.getProperty(prefix + "roll"), 0.0);
        return new Transform(new Vector3d(x, y, z), new Vector3f(pitch, yaw, roll));
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = NpcRuntimeState.normalize(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static NpcRuntimeState.PresenceMode readPresenceMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NpcRuntimeState.PresenceMode.HIDDEN;
        }
        try {
            return NpcRuntimeState.PresenceMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return NpcRuntimeState.PresenceMode.HIDDEN;
        }
    }

    @Nonnull
    private static NpcDefinition.HubBehaviorMode readHubBehavior(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NpcDefinition.HubBehaviorMode.STANDING;
        }
        try {
            return NpcDefinition.HubBehaviorMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return NpcDefinition.HubBehaviorMode.STANDING;
        }
    }

    private static int readInt(@Nullable String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long readLong(@Nullable String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nullable
    private static Double readDouble(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double readDouble(@Nullable String raw, double fallback) {
        Double value = readDouble(raw);
        return value == null ? fallback : value;
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
}
