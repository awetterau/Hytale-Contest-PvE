package dev.hytalemodding.npc.config;

import com.hypixel.hytale.math.vector.Transform;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.RunRescueRegistry;
import dev.hytalemodding.npc.core.NpcDefinition;
import dev.hytalemodding.npc.state.NpcRuntimeState;
import dev.hytalemodding.quest.QuestDefinition;
import dev.hytalemodding.quest.QuestDefinitionRegistry;
import dev.hytalemodding.quest.QuestProgressManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class NpcMigrationService {
    private static final String RESCUE_RESOURCE = "Common/NpcData/run-rescue-spawns.properties";
    private static final NpcMigrationService INSTANCE = new NpcMigrationService();

    private final Map<String, RescueMetadata> rescueMetadataByNpc = new LinkedHashMap<>();
    private boolean rescueMetadataLoaded;

    private NpcMigrationService() {
    }

    @Nonnull
    public static NpcMigrationService get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized Collection<NpcDefinition> buildAllDefinitions() {
        List<NpcDefinition> out = new ArrayList<>();
        for (NpcArchetype archetype : NpcDefinitionRegistry.get().getAll()) {
            out.add(buildDefinition(archetype.npcKey));
        }
        return List.copyOf(out);
    }

    @Nonnull
    public synchronized NpcDefinition buildDefinition(@Nonnull String npcKey) {
        String normalizedNpcKey = NpcDefinition.normalizeKey(npcKey);
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(normalizedNpcKey);
        RescueMetadata rescueMetadata = getRescueMetadata(normalizedNpcKey);

        String displayName = archetype == null || archetype.displayName.isBlank() ? normalizedNpcKey : archetype.displayName;
        NpcArchetype.NpcCategory category = archetype == null ? NpcArchetype.NpcCategory.SPECIALIST : archetype.category;
        String hubRole = archetype == null ? null : archetype.hubRole;
        String runRescueRole = archetype == null ? null : archetype.runRescueRole;
        boolean rescueEnabled = rescueMetadata != null ? rescueMetadata.enabled : runRescueRole != null && !runRescueRole.isBlank();
        List<String> templateWorlds = rescueMetadata == null || rescueMetadata.templateWorlds.isEmpty()
                ? List.of()
                : rescueMetadata.templateWorlds;
        Transform preferredRunSpawn = rescueMetadata == null
                ? null
                : rescueMetadata.preferredRunSpawn;
        List<String> linkedQuestIds = collectLinkedQuestIds(normalizedNpcKey);
        Set<String> tags = new LinkedHashSet<>();
        if (archetype != null) {
            tags.add(archetype.category.name().toLowerCase(Locale.ROOT));
            if (archetype.plotType != null && !archetype.plotType.isBlank()) {
                tags.add("workstation");
                tags.add(archetype.plotType.toLowerCase(Locale.ROOT));
            }
            if (archetype.alwaysInHub) {
                tags.add("always_in_hub");
            }
            if (archetype.runRescueRole != null && !archetype.runRescueRole.isBlank()) {
                tags.add("rescue");
            }
        }
        boolean workstationRequired = archetype != null && archetype.plotType != null && !archetype.plotType.isBlank();
        Transform defaultHubSpawn = archetype == null ? null : archetype.hubSpawnTransform;
        Transform waitingSpawn = BaseHousingManager.get().getFixedHubRescueSpawn(normalizedNpcKey);
        return new NpcDefinition(
                normalizedNpcKey,
                displayName,
                category,
                new NpcDefinition.RoleBindings(hubRole, runRescueRole),
                new NpcDefinition.RescueConfig(
                        rescueEnabled,
                        templateWorlds,
                        preferredRunSpawn,
                        archetype == null ? List.of() : archetype.followStateAliases
                ),
                new NpcDefinition.HubConfig(
                        hubRole != null && !hubRole.isBlank(),
                        archetype != null && archetype.alwaysInHub,
                        defaultHubSpawn,
                        waitingSpawn,
                        defaultHubSpawn,
                        migrateDefaultBehavior(normalizedNpcKey)
                ),
                new NpcDefinition.WorkstationConfig(
                        workstationRequired,
                        archetype == null ? null : archetype.plotType,
                        archetype == null ? null : archetype.homeTemplateId,
                        archetype == null ? null : archetype.prePlotQuestId,
                        archetype == null ? NpcArchetype.PlotUnlockMode.MATERIALS : archetype.plotUnlockMode
                ),
                new NpcDefinition.ServiceConfig(
                        archetype != null && archetype.services.canTalk,
                        archetype != null && archetype.services.canCraft,
                        archetype != null && archetype.services.canTrade,
                        archetype != null && archetype.services.canGiveQuests,
                        archetype != null && archetype.services.canUpgrade,
                        Set.of()
                ),
                new NpcDefinition.DependencyConfig(
                        List.of(),
                        archetype != null && archetype.prePlotQuestId != null && !archetype.prePlotQuestId.isBlank()
                                ? List.of(archetype.prePlotQuestId)
                                : List.of(),
                        archetype != null && archetype.animalRoutesToFarmer && archetype.farmerNpcKey != null
                                ? List.of(archetype.farmerNpcKey)
                                : List.of(),
                        0
                ),
                new NpcDefinition.StoryConfig(linkedQuestIds, List.copyOf(tags)),
                new NpcDefinition.RuntimeDefaults(
                        migrateInitialPresence(normalizedNpcKey, archetype),
                        archetype == null ? List.of() : archetype.defaultCraftUnlocks,
                        archetype == null ? List.of() : archetype.defaultTradeUnlocks
                )
        );
    }

    @Nonnull
    public synchronized NpcRuntimeState buildRuntimeState(@Nonnull NpcDefinition definition) {
        String npcKey = definition.npcKey;
        NpcProgressManager.NpcProgress progress = NpcProgressManager.get().getOrCreate(npcKey);
        HubNpcManager.NpcData hubNpcData = BaseHousingManager.get().getNpcData(npcKey);
        HubNpcManager.HubNpcState hubState = BaseHousingManager.get().getNpcState(npcKey);
        boolean workshopBuilt = BaseHousingManager.get().isWorkshopBuilt(npcKey);

        LinkedHashSet<String> acceptedQuestIds = new LinkedHashSet<>();
        LinkedHashSet<String> completedQuestIds = new LinkedHashSet<>();
        for (String questId : definition.story.linkedQuestIds) {
            QuestProgressManager.QuestProgress questProgress = QuestProgressManager.get().getOrCreate(questId);
            if (questProgress.accepted) {
                acceptedQuestIds.add(questId);
            }
            if (questProgress.completed) {
                completedQuestIds.add(questId);
            }
        }

        NpcRuntimeState.PresenceMode presenceMode;
        NpcDefinition.HubBehaviorMode hubBehavior;
        if (!progress.rescued && definition.rescue.enabled && definition.roles.runRescueRole != null) {
            presenceMode = NpcRuntimeState.PresenceMode.RUN_RESCUE_OBJECTIVE;
            hubBehavior = NpcDefinition.HubBehaviorMode.STANDING;
        } else if (!progress.rescued && !definition.hub.alwaysPresent) {
            presenceMode = NpcRuntimeState.PresenceMode.HIDDEN;
            hubBehavior = definition.hub.defaultBehavior;
        } else if (hubState == HubNpcManager.HubNpcState.WORKING) {
            presenceMode = NpcRuntimeState.PresenceMode.HUB_WORKING;
            hubBehavior = NpcDefinition.HubBehaviorMode.WORKING;
        } else if (hubState == HubNpcManager.HubNpcState.MOVING_TO_WORKSHOP || (definition.workstation.required && progress.rescued && !workshopBuilt)) {
            presenceMode = NpcRuntimeState.PresenceMode.HUB_WAITING_FOR_WORKSTATION;
            hubBehavior = NpcDefinition.HubBehaviorMode.WAITING_FOR_WORKSTATION;
        } else {
            presenceMode = NpcRuntimeState.PresenceMode.HUB;
            hubBehavior = hubState == HubNpcManager.HubNpcState.WANDERING
                    ? NpcDefinition.HubBehaviorMode.WANDERING
                    : definition.hub.defaultBehavior;
        }

        return new NpcRuntimeState(
                npcKey,
                progress.rescued,
                presenceMode,
                hubBehavior,
                firstNonBlank(progress.assignedPlotId, hubNpcData.assignedPlotId),
                Math.max(progress.upgradeTier, workshopBuilt ? 1 : 0),
                progress.lastStateChangeMs,
                progress.unlockedCrafts,
                progress.unlockedTrades,
                acceptedQuestIds,
                completedQuestIds,
                definition.hub.defaultSpawn,
                definition.rescue.preferredRunSpawn
        );
    }

    @Nonnull
    private List<String> collectLinkedQuestIds(@Nonnull String npcKey) {
        List<String> linkedQuestIds = new ArrayList<>();
        for (QuestDefinition definition : QuestDefinitionRegistry.get().getBySource("npc", npcKey)) {
            linkedQuestIds.add(definition.questId);
        }
        linkedQuestIds.sort(String::compareToIgnoreCase);
        return List.copyOf(linkedQuestIds);
    }

    @Nonnull
    private NpcDefinition.HubBehaviorMode migrateDefaultBehavior(@Nonnull String npcKey) {
        HubNpcManager.HubNpcState state = BaseHousingManager.get().getNpcState(npcKey);
        return switch (state) {
            case WORKING -> NpcDefinition.HubBehaviorMode.WORKING;
            case MOVING_TO_WORKSHOP -> NpcDefinition.HubBehaviorMode.WAITING_FOR_WORKSTATION;
            case WANDERING -> NpcDefinition.HubBehaviorMode.WANDERING;
        };
    }

    @Nonnull
    private NpcDefinition.InitialPresenceMode migrateInitialPresence(@Nonnull String npcKey, @Nullable NpcArchetype archetype) {
        if (archetype != null && archetype.alwaysInHub) {
            return NpcDefinition.InitialPresenceMode.HUB;
        }
        if (NpcProgressManager.get().isNpcRescued(npcKey)) {
            return NpcDefinition.InitialPresenceMode.HUB;
        }
        if (archetype != null && archetype.runRescueRole != null && !archetype.runRescueRole.isBlank()) {
            return NpcDefinition.InitialPresenceMode.RESCUE_OBJECTIVE;
        }
        return NpcDefinition.InitialPresenceMode.HIDDEN;
    }

    @Nullable
    private synchronized RescueMetadata getRescueMetadata(@Nonnull String npcKey) {
        ensureRescueMetadataLoaded();
        return this.rescueMetadataByNpc.get(NpcDefinition.normalizeKey(npcKey));
    }

    private void ensureRescueMetadataLoaded() {
        if (this.rescueMetadataLoaded) {
            return;
        }
        this.rescueMetadataLoaded = true;
        Properties properties = new Properties();
        try (InputStream in = NpcMigrationService.class.getClassLoader().getResourceAsStream(RESCUE_RESOURCE)) {
            if (in == null) {
                return;
            }
            properties.load(in);
        } catch (IOException e) {
            System.out.println("[NpcUnifiedMigration] Failed to load rescue metadata: " + e.getMessage());
            return;
        }
        for (String npcKey : parseCsv(properties.getProperty("rescue.npcs"))) {
            String prefix = "rescue." + npcKey + ".";
            boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "true"));
            List<String> templateWorlds = parseCsv(properties.getProperty(prefix + "templateWorld"));
            Transform preferredSpawn = RunRescueRegistry.get().getConfiguredSpawn(npcKey, templateWorlds.isEmpty() ? "" : templateWorlds.get(0));
            this.rescueMetadataByNpc.put(
                    npcKey,
                    new RescueMetadata(enabled, templateWorlds, preferredSpawn)
            );
        }
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = NpcDefinition.normalizeKey(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        String left = NpcDefinition.normalizeNullable(a);
        if (left != null) {
            return left;
        }
        return NpcDefinition.normalizeNullable(b);
    }

    private static final class RescueMetadata {
        private final boolean enabled;
        @Nonnull
        private final List<String> templateWorlds;
        @Nullable
        private final Transform preferredRunSpawn;

        private RescueMetadata(boolean enabled, @Nonnull List<String> templateWorlds, @Nullable Transform preferredRunSpawn) {
            this.enabled = enabled;
            this.templateWorlds = List.copyOf(templateWorlds);
            this.preferredRunSpawn = NpcDefinition.copyTransform(preferredRunSpawn);
        }
    }
}
