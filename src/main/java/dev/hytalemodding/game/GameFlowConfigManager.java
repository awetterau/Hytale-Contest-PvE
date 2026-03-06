package dev.hytalemodding.game;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GameFlowConfigManager {
    private static final GameFlowConfigManager INSTANCE = new GameFlowConfigManager();

    private final Map<UUID, FlowConfig> byTemplateWorld = new ConcurrentHashMap<>();

    private GameFlowConfigManager() {
    }

    @Nonnull
    public static GameFlowConfigManager get() {
        return INSTANCE;
    }

    public void setDoorBlock(@Nonnull UUID templateWorldId, @Nonnull Vector3i doorBlock) {
        this.byTemplateWorld.computeIfAbsent(templateWorldId, ignored -> new FlowConfig()).doorBlock = new Vector3i(doorBlock);
    }

    public void setRunSpawn(@Nonnull UUID templateWorldId, @Nonnull Transform runSpawn) {
        this.byTemplateWorld.computeIfAbsent(templateWorldId, ignored -> new FlowConfig()).runSpawn = copyTransform(runSpawn);
    }

    public void setBaseSpawn(@Nonnull UUID templateWorldId, @Nonnull Transform baseSpawn) {
        this.byTemplateWorld.computeIfAbsent(templateWorldId, ignored -> new FlowConfig()).baseSpawn = copyTransform(baseSpawn);
    }

    public void setRescueRunSpawn(@Nonnull UUID templateWorldId, @Nonnull Transform rescueRunSpawn) {
        this.byTemplateWorld.computeIfAbsent(templateWorldId, ignored -> new FlowConfig()).rescueRunSpawn = copyTransform(rescueRunSpawn);
    }

    @Nullable
    public Vector3i getDoorBlock(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config == null || config.doorBlock == null ? null : new Vector3i(config.doorBlock);
    }

    @Nullable
    public Transform getRunSpawn(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config == null || config.runSpawn == null ? null : copyTransform(config.runSpawn);
    }

    @Nullable
    public Transform getBaseSpawn(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config == null || config.baseSpawn == null ? null : copyTransform(config.baseSpawn);
    }

    @Nullable
    public Transform getRescueRunSpawn(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config == null || config.rescueRunSpawn == null ? null : copyTransform(config.rescueRunSpawn);
    }

    public boolean isConfigured(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config != null && config.runSpawn != null && config.baseSpawn != null;
    }

    public boolean hasRunSpawn(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config != null && config.runSpawn != null;
    }

    public boolean hasBaseSpawn(@Nonnull UUID templateWorldId) {
        FlowConfig config = this.byTemplateWorld.get(templateWorldId);
        return config != null && config.baseSpawn != null;
    }

    @Nonnull
    private static Transform copyTransform(@Nonnull Transform transform) {
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }

    private static final class FlowConfig {
        @Nullable
        private Vector3i doorBlock;
        @Nullable
        private Transform runSpawn;
        @Nullable
        private Transform baseSpawn;
        @Nullable
        private Transform rescueRunSpawn;
    }
}
