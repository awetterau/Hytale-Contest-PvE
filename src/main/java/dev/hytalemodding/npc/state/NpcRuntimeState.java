package dev.hytalemodding.npc.state;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import dev.hytalemodding.npc.core.NpcDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class NpcRuntimeState {
    @Nonnull
    public final String npcKey;
    public final boolean rescued;
    @Nonnull
    public final PresenceMode presenceMode;
    @Nonnull
    public final NpcDefinition.HubBehaviorMode hubBehavior;
    @Nullable
    public final String assignedWorkstationId;
    public final int workstationLevel;
    public final long lastStateChangeMs;
    @Nonnull
    public final Set<String> unlockedCrafts;
    @Nonnull
    public final Set<String> unlockedTrades;
    @Nonnull
    public final Set<String> acceptedQuestIds;
    @Nonnull
    public final Set<String> completedQuestIds;
    @Nullable
    public final Transform hubSpawnOverride;
    @Nullable
    public final Transform rescueSpawnOverride;

    public NpcRuntimeState(
            @Nonnull String npcKey,
            boolean rescued,
            @Nonnull PresenceMode presenceMode,
            @Nonnull NpcDefinition.HubBehaviorMode hubBehavior,
            @Nullable String assignedWorkstationId,
            int workstationLevel,
            long lastStateChangeMs,
            @Nonnull Set<String> unlockedCrafts,
            @Nonnull Set<String> unlockedTrades,
            @Nonnull Set<String> acceptedQuestIds,
            @Nonnull Set<String> completedQuestIds,
            @Nullable Transform hubSpawnOverride,
            @Nullable Transform rescueSpawnOverride
    ) {
        this.npcKey = normalize(npcKey);
        this.rescued = rescued;
        this.presenceMode = presenceMode;
        this.hubBehavior = hubBehavior;
        this.assignedWorkstationId = normalizeNullable(assignedWorkstationId);
        this.workstationLevel = Math.max(0, workstationLevel);
        this.lastStateChangeMs = Math.max(0L, lastStateChangeMs);
        this.unlockedCrafts = normalizeSet(unlockedCrafts);
        this.unlockedTrades = normalizeSet(unlockedTrades);
        this.acceptedQuestIds = normalizeSet(acceptedQuestIds);
        this.completedQuestIds = normalizeSet(completedQuestIds);
        this.hubSpawnOverride = copyTransform(hubSpawnOverride);
        this.rescueSpawnOverride = copyTransform(rescueSpawnOverride);
    }

    public enum PresenceMode {
        HIDDEN,
        HUB,
        HUB_WAITING_FOR_WORKSTATION,
        HUB_WORKING,
        RUN_RESCUE_OBJECTIVE
    }

    @Nonnull
    public NpcRuntimeState copy() {
        return new NpcRuntimeState(
                this.npcKey,
                this.rescued,
                this.presenceMode,
                this.hubBehavior,
                this.assignedWorkstationId,
                this.workstationLevel,
                this.lastStateChangeMs,
                this.unlockedCrafts,
                this.unlockedTrades,
                this.acceptedQuestIds,
                this.completedQuestIds,
                this.hubSpawnOverride,
                this.rescueSpawnOverride
        );
    }

    @Nonnull
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static String normalizeNullable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nonnull
    public static Set<String> normalizeSet(@Nullable Set<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawValues) {
            String value = normalize(raw);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return Set.copyOf(out);
    }

    @Nullable
    public static Transform copyTransform(@Nullable Transform transform) {
        if (transform == null) {
            return null;
        }
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }
}
