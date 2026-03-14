package dev.hytalemodding.npc;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class RunRescueRegistry {
    private static final String RESOURCE_PATH = "Common/NpcData/run-rescue-spawns.properties";
    private static final RunRescueRegistry INSTANCE = new RunRescueRegistry();

    private final ConcurrentHashMap<String, RescueSpawnDefinition> byNpcKey = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private RunRescueRegistry() {
    }

    @Nonnull
    public static RunRescueRegistry get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadResource();
        installArchetypeFallbacks();
        System.out.println("[RunRescueRegistry] Loaded rescue entries: " + this.byNpcKey.size());
    }

    @Nullable
    public synchronized String chooseNpcForTemplateWorld(@Nonnull String templateWorldName) {
        initialize();
        String world = normalize(templateWorldName);
        for (RescueSpawnDefinition def : this.byNpcKey.values()) {
            if (!def.enabled) {
                continue;
            }
            if (def.templateWorld != null && !def.templateWorld.equalsIgnoreCase(world)) {
                continue;
            }
            if (NpcProgressManager.get().isNpcRescued(def.npcKey)) {
                continue;
            }
            NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(def.npcKey);
            if (archetype == null || archetype.runRescueRole == null || archetype.runRescueRole.isBlank()) {
                continue;
            }
            return def.npcKey;
        }
        return null;
    }

    @Nullable
    public synchronized Transform getConfiguredSpawn(@Nonnull String npcKey, @Nonnull String templateWorldName) {
        initialize();
        RescueSpawnDefinition def = this.byNpcKey.get(normalize(npcKey));
        if (def == null || !def.enabled) {
            return null;
        }
        if (def.templateWorld != null && !def.templateWorld.equalsIgnoreCase(templateWorldName)) {
            return null;
        }
        if (def.spawnTransform != null) {
            return copy(def.spawnTransform);
        }
        Transform legacyFallback = GameFlowConfigManager.get().getRescueRunSpawn();
        return legacyFallback == null ? null : copy(legacyFallback);
    }

    private void loadResource() {
        Properties p = new Properties();
        try (InputStream in = RunRescueRegistry.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                System.out.println("[RunRescueRegistry] Resource not found: " + RESOURCE_PATH);
                return;
            }
            p.load(in);
        } catch (IOException e) {
            System.out.println("[RunRescueRegistry] Failed to load resource: " + e.getMessage());
            return;
        }

        List<String> keys = parseCsv(p.getProperty("rescue.npcs"));
        for (String key : keys) {
            String prefix = "rescue." + key + ".";
            boolean enabled = Boolean.parseBoolean(p.getProperty(prefix + "enabled", "true"));
            String templateWorld = normalizeNullable(p.getProperty(prefix + "templateWorld"));

            Double x = readDouble(p.getProperty(prefix + "x"));
            Double y = readDouble(p.getProperty(prefix + "y"));
            Double z = readDouble(p.getProperty(prefix + "z"));
            Double yaw = readDouble(p.getProperty(prefix + "yaw"));
            Double pitch = readDouble(p.getProperty(prefix + "pitch"));
            Double roll = readDouble(p.getProperty(prefix + "roll"));
            Transform spawn = null;
            if (x != null && y != null && z != null) {
                float yawF = yaw == null ? 0.0f : yaw.floatValue();
                float pitchF = pitch == null ? 0.0f : pitch.floatValue();
                float rollF = roll == null ? 0.0f : roll.floatValue();
                spawn = new Transform(new Vector3d(x, y, z), new Vector3f(pitchF, yawF, rollF));
            }
            this.byNpcKey.put(key, new RescueSpawnDefinition(key, enabled, templateWorld, spawn));
        }
    }

    private void installArchetypeFallbacks() {
        for (NpcArchetype archetype : NpcDefinitionRegistry.get().getAll()) {
            if (archetype.runRescueRole == null || archetype.runRescueRole.isBlank()) {
                continue;
            }
            this.byNpcKey.putIfAbsent(
                    archetype.npcKey,
                    new RescueSpawnDefinition(archetype.npcKey, true, null, null)
            );
        }
    }

    @Nonnull
    private static Transform copy(@Nonnull Transform transform) {
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : csv.split(",")) {
            String value = normalize(item);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
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

    private static final class RescueSpawnDefinition {
        @Nonnull
        private final String npcKey;
        private final boolean enabled;
        @Nullable
        private final String templateWorld;
        @Nullable
        private final Transform spawnTransform;

        private RescueSpawnDefinition(
                @Nonnull String npcKey,
                boolean enabled,
                @Nullable String templateWorld,
                @Nullable Transform spawnTransform
        ) {
            this.npcKey = npcKey;
            this.enabled = enabled;
            this.templateWorld = templateWorld;
            this.spawnTransform = spawnTransform;
        }
    }
}
