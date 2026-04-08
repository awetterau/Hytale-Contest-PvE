package dev.hytalemodding.npc.admin;

import com.hypixel.hytale.math.vector.Transform;
import dev.hytalemodding.npc.config.NpcUnifiedRegistry;
import dev.hytalemodding.npc.core.NpcDefinition;
import dev.hytalemodding.npc.state.NpcRuntimeState;
import dev.hytalemodding.npc.state.NpcStateManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NpcAdminService {
    private static final NpcAdminService INSTANCE = new NpcAdminService();

    private NpcAdminService() {
    }

    @Nonnull
    public static NpcAdminService get() {
        return INSTANCE;
    }

    public void initialize() {
        NpcUnifiedRegistry.get().initialize();
        NpcStateManager.get().initialize();
    }

    @Nonnull
    public List<String> describeNpcSummaries() {
        ArrayList<String> lines = new ArrayList<>();
        for (NpcDefinition definition : NpcUnifiedRegistry.get().getAll()) {
            NpcRuntimeState state = NpcStateManager.get().getState(definition.npcKey);
            List<String> issues = NpcValidationService.get().validate(definition);
            lines.add(
                    definition.npcKey
                            + " name=\"" + definition.displayName + "\""
                            + " rescued=" + state.rescued
                            + " presence=" + state.presenceMode.name()
                            + " hubBehavior=" + state.hubBehavior.name()
                            + " quests=" + definition.story.linkedQuestIds.size()
                            + " issues=" + issues.size()
            );
        }
        if (lines.isEmpty()) {
            lines.add("<none>");
        }
        return List.copyOf(lines);
    }

    @Nonnull
    public List<String> describeNpc(@Nonnull String npcKey) {
        NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(npcKey);
        if (definition == null) {
            return List.of("Unknown NPC: " + npcKey);
        }
        NpcRuntimeState state = NpcStateManager.get().getState(npcKey);
        ArrayList<String> lines = new ArrayList<>();
        lines.add("npc=" + definition.npcKey + " displayName=\"" + definition.displayName + "\" category=" + definition.category.name());
        lines.add("roles hub=" + safe(definition.roles.hubRole) + " runRescue=" + safe(definition.roles.runRescueRole));
        lines.add("rescue enabled=" + definition.rescue.enabled + " templates=" + join(definition.rescue.templateWorlds));
        lines.add("hub enabled=" + definition.hub.enabled + " alwaysPresent=" + definition.hub.alwaysPresent + " defaultBehavior=" + definition.hub.defaultBehavior.name());
        lines.add("workstation required=" + definition.workstation.required + " type=" + safe(definition.workstation.workstationType) + " preUnlockQuest=" + safe(definition.workstation.preUnlockQuestId));
        lines.add("services talk=" + definition.services.canTalk + " craft=" + definition.services.canCraft + " trade=" + definition.services.canTrade + " quests=" + definition.services.canGiveQuests + " upgrades=" + definition.services.canUpgrade);
        lines.add("story linkedQuests=" + join(definition.story.linkedQuestIds));
        lines.add("state rescued=" + state.rescued + " presence=" + state.presenceMode.name() + " hubBehavior=" + state.hubBehavior.name() + " workstation=" + safe(state.assignedWorkstationId) + " level=" + state.workstationLevel);
        lines.add("state acceptedQuests=" + join(state.acceptedQuestIds.stream().toList()) + " completedQuests=" + join(state.completedQuestIds.stream().toList()));
        List<String> issues = NpcValidationService.get().validate(definition);
        lines.add("validation issues=" + issues.size());
        for (String issue : issues) {
            lines.add(" - " + issue);
        }
        return List.copyOf(lines);
    }

    @Nonnull
    public List<String> validateAll() {
        ArrayList<String> lines = new ArrayList<>();
        for (NpcDefinition definition : NpcUnifiedRegistry.get().getAll()) {
            List<String> issues = NpcValidationService.get().validate(definition);
            if (issues.isEmpty()) {
                continue;
            }
            for (String issue : issues) {
                lines.add(definition.npcKey + ": " + issue);
            }
        }
        if (lines.isEmpty()) {
            lines.add("No NPC validation issues found.");
        }
        return List.copyOf(lines);
    }

    public void resetNpcToMigratedDefaults(@Nonnull String npcKey) {
        NpcStateManager.get().resetNpcToMigratedDefaults(npcKey);
    }

    public void setRescued(@Nonnull String npcKey, boolean rescued) {
        NpcStateManager.get().setRescued(npcKey, rescued);
    }

    public void setPresenceMode(@Nonnull String npcKey, @Nonnull NpcRuntimeState.PresenceMode presenceMode) {
        NpcStateManager.get().setPresenceMode(npcKey, presenceMode);
    }

    public void setHubBehavior(@Nonnull String npcKey, @Nonnull NpcDefinition.HubBehaviorMode hubBehavior) {
        NpcStateManager.get().setHubBehavior(npcKey, hubBehavior);
    }

    public void setHubSpawnOverride(@Nonnull String npcKey, @Nullable Transform transform) {
        NpcStateManager.get().setHubSpawnOverride(npcKey, transform);
    }

    public void setRescueSpawnOverride(@Nonnull String npcKey, @Nullable Transform transform) {
        NpcStateManager.get().setRescueSpawnOverride(npcKey, transform);
    }

    public void setAssignedWorkstationId(@Nonnull String npcKey, @Nullable String assignedWorkstationId) {
        NpcStateManager.get().setAssignedWorkstationId(npcKey, assignedWorkstationId);
    }

    public void setWorkstationLevel(@Nonnull String npcKey, int workstationLevel) {
        NpcStateManager.get().setWorkstationLevel(npcKey, workstationLevel);
    }

    public void setAcceptedQuestIds(@Nonnull String npcKey, @Nonnull Set<String> acceptedQuestIds) {
        NpcStateManager.get().setAcceptedQuestIds(npcKey, acceptedQuestIds);
    }

    public void setCompletedQuestIds(@Nonnull String npcKey, @Nonnull Set<String> completedQuestIds) {
        NpcStateManager.get().setCompletedQuestIds(npcKey, completedQuestIds);
    }

    @Nonnull
    private static String join(@Nonnull List<String> values) {
        return values.isEmpty() ? "<none>" : String.join(",", values);
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
