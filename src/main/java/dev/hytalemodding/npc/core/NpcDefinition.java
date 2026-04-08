package dev.hytalemodding.npc.core;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import dev.hytalemodding.npc.NpcArchetype;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class NpcDefinition {
    @Nonnull
    public final String npcKey;
    @Nonnull
    public final String displayName;
    @Nonnull
    public final NpcArchetype.NpcCategory category;
    @Nonnull
    public final RoleBindings roles;
    @Nonnull
    public final RescueConfig rescue;
    @Nonnull
    public final HubConfig hub;
    @Nonnull
    public final WorkstationConfig workstation;
    @Nonnull
    public final ServiceConfig services;
    @Nonnull
    public final DependencyConfig dependencies;
    @Nonnull
    public final StoryConfig story;
    @Nonnull
    public final RuntimeDefaults runtimeDefaults;

    public NpcDefinition(
            @Nonnull String npcKey,
            @Nonnull String displayName,
            @Nonnull NpcArchetype.NpcCategory category,
            @Nonnull RoleBindings roles,
            @Nonnull RescueConfig rescue,
            @Nonnull HubConfig hub,
            @Nonnull WorkstationConfig workstation,
            @Nonnull ServiceConfig services,
            @Nonnull DependencyConfig dependencies,
            @Nonnull StoryConfig story,
            @Nonnull RuntimeDefaults runtimeDefaults
    ) {
        this.npcKey = normalizeKey(npcKey);
        this.displayName = displayName == null || displayName.isBlank() ? this.npcKey : displayName.trim();
        this.category = category;
        this.roles = roles;
        this.rescue = rescue;
        this.hub = hub;
        this.workstation = workstation;
        this.services = services;
        this.dependencies = dependencies;
        this.story = story;
        this.runtimeDefaults = runtimeDefaults;
    }

    @Nonnull
    public static String normalizeKey(@Nullable String raw) {
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
    public static List<String> normalizeList(@Nullable List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String raw : rawValues) {
            String value = normalizeKey(raw);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    @Nonnull
    public static Set<String> normalizeSet(@Nullable Set<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String raw : rawValues) {
            String value = normalizeKey(raw);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return Set.copyOf(values);
    }

    @Nullable
    public static Transform copyTransform(@Nullable Transform transform) {
        if (transform == null) {
            return null;
        }
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }

    public enum InitialPresenceMode {
        HIDDEN,
        HUB,
        RESCUE_OBJECTIVE
    }

    public enum HubBehaviorMode {
        STANDING,
        WANDERING,
        WAITING_FOR_WORKSTATION,
        WORKING
    }

    public static final class RoleBindings {
        @Nullable
        public final String hubRole;
        @Nullable
        public final String runRescueRole;

        public RoleBindings(@Nullable String hubRole, @Nullable String runRescueRole) {
            this.hubRole = normalizeNullable(hubRole);
            this.runRescueRole = normalizeNullable(runRescueRole);
        }
    }

    public static final class RescueConfig {
        public final boolean enabled;
        @Nonnull
        public final List<String> templateWorlds;
        @Nullable
        public final Transform preferredRunSpawn;
        @Nonnull
        public final List<String> followStateAliases;

        public RescueConfig(
                boolean enabled,
                @Nonnull List<String> templateWorlds,
                @Nullable Transform preferredRunSpawn,
                @Nonnull List<String> followStateAliases
        ) {
            this.enabled = enabled;
            this.templateWorlds = normalizeList(templateWorlds);
            this.preferredRunSpawn = copyTransform(preferredRunSpawn);
            this.followStateAliases = normalizeList(followStateAliases);
        }
    }

    public static final class HubConfig {
        public final boolean enabled;
        public final boolean alwaysPresent;
        @Nullable
        public final Transform defaultSpawn;
        @Nullable
        public final Transform waitingSpawn;
        @Nullable
        public final Transform workstationSpawn;
        @Nonnull
        public final HubBehaviorMode defaultBehavior;

        public HubConfig(
                boolean enabled,
                boolean alwaysPresent,
                @Nullable Transform defaultSpawn,
                @Nullable Transform waitingSpawn,
                @Nullable Transform workstationSpawn,
                @Nonnull HubBehaviorMode defaultBehavior
        ) {
            this.enabled = enabled;
            this.alwaysPresent = alwaysPresent;
            this.defaultSpawn = copyTransform(defaultSpawn);
            this.waitingSpawn = copyTransform(waitingSpawn);
            this.workstationSpawn = copyTransform(workstationSpawn);
            this.defaultBehavior = defaultBehavior;
        }
    }

    public static final class WorkstationConfig {
        public final boolean required;
        @Nullable
        public final String workstationType;
        @Nullable
        public final String homeTemplateId;
        @Nullable
        public final String preUnlockQuestId;
        @Nonnull
        public final NpcArchetype.PlotUnlockMode plotUnlockMode;

        public WorkstationConfig(
                boolean required,
                @Nullable String workstationType,
                @Nullable String homeTemplateId,
                @Nullable String preUnlockQuestId,
                @Nonnull NpcArchetype.PlotUnlockMode plotUnlockMode
        ) {
            this.required = required;
            this.workstationType = normalizeNullable(workstationType);
            this.homeTemplateId = normalizeNullable(homeTemplateId);
            this.preUnlockQuestId = normalizeNullable(preUnlockQuestId);
            this.plotUnlockMode = plotUnlockMode;
        }
    }

    public static final class ServiceConfig {
        public final boolean canTalk;
        public final boolean canCraft;
        public final boolean canTrade;
        public final boolean canGiveQuests;
        public final boolean canUpgrade;
        @Nonnull
        public final Set<String> customServiceIds;

        public ServiceConfig(
                boolean canTalk,
                boolean canCraft,
                boolean canTrade,
                boolean canGiveQuests,
                boolean canUpgrade,
                @Nonnull Set<String> customServiceIds
        ) {
            this.canTalk = canTalk;
            this.canCraft = canCraft;
            this.canTrade = canTrade;
            this.canGiveQuests = canGiveQuests;
            this.canUpgrade = canUpgrade;
            this.customServiceIds = normalizeSet(customServiceIds);
        }
    }

    public static final class DependencyConfig {
        @Nonnull
        public final List<String> requiredFlags;
        @Nonnull
        public final List<String> requiredCompletedQuests;
        @Nonnull
        public final List<String> requiredRescuedNpcs;
        public final int requiredWorkstationLevel;

        public DependencyConfig(
                @Nonnull List<String> requiredFlags,
                @Nonnull List<String> requiredCompletedQuests,
                @Nonnull List<String> requiredRescuedNpcs,
                int requiredWorkstationLevel
        ) {
            this.requiredFlags = normalizeList(requiredFlags);
            this.requiredCompletedQuests = normalizeList(requiredCompletedQuests);
            this.requiredRescuedNpcs = normalizeList(requiredRescuedNpcs);
            this.requiredWorkstationLevel = Math.max(0, requiredWorkstationLevel);
        }
    }

    public static final class StoryConfig {
        @Nonnull
        public final List<String> linkedQuestIds;
        @Nonnull
        public final List<String> tags;

        public StoryConfig(@Nonnull List<String> linkedQuestIds, @Nonnull List<String> tags) {
            this.linkedQuestIds = normalizeList(linkedQuestIds);
            this.tags = normalizeList(tags);
        }
    }

    public static final class RuntimeDefaults {
        @Nonnull
        public final InitialPresenceMode initialPresenceMode;
        @Nonnull
        public final List<String> defaultCraftUnlocks;
        @Nonnull
        public final List<String> defaultTradeUnlocks;

        public RuntimeDefaults(
                @Nonnull InitialPresenceMode initialPresenceMode,
                @Nonnull List<String> defaultCraftUnlocks,
                @Nonnull List<String> defaultTradeUnlocks
        ) {
            this.initialPresenceMode = initialPresenceMode;
            this.defaultCraftUnlocks = normalizeList(defaultCraftUnlocks);
            this.defaultTradeUnlocks = normalizeList(defaultTradeUnlocks);
        }
    }
}
