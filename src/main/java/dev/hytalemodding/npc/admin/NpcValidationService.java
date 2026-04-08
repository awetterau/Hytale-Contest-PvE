package dev.hytalemodding.npc.admin;

import dev.hytalemodding.npc.core.NpcDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;
import dev.hytalemodding.quest.QuestDefinitionRegistry;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class NpcValidationService {
    private static final NpcValidationService INSTANCE = new NpcValidationService();

    private NpcValidationService() {
    }

    @Nonnull
    public static NpcValidationService get() {
        return INSTANCE;
    }

    @Nonnull
    public List<String> validate(@Nonnull NpcDefinition definition) {
        ArrayList<String> issues = new ArrayList<>();
        if (definition.displayName.isBlank()) {
            issues.add("Missing display name.");
        }
        if (definition.roles.hubRole == null && definition.roles.runRescueRole == null) {
            issues.add("NPC has neither a hub role nor a run rescue role.");
        }
        if (definition.rescue.enabled && (definition.roles.runRescueRole == null || definition.roles.runRescueRole.isBlank())) {
            issues.add("Rescue is enabled but no run rescue role is configured.");
        }
        if (definition.hub.enabled && (definition.roles.hubRole == null || definition.roles.hubRole.isBlank())) {
            issues.add("Hub presence is enabled but no hub role is configured.");
        }
        if (definition.workstation.required && (definition.workstation.workstationType == null || definition.workstation.workstationType.isBlank())) {
            issues.add("Workstation is required but no workstation type is configured.");
        }
        if ((definition.services.canTrade || definition.services.canCraft || definition.services.canUpgrade)
                && NpcEconomyRegistry.get().getNpc(definition.npcKey) == null) {
            issues.add("NPC exposes economy services but has no economy definition file.");
        }
        for (String questId : definition.story.linkedQuestIds) {
            if (QuestDefinitionRegistry.get().getQuest(questId) == null) {
                issues.add("Linked quest is missing: " + questId);
            }
        }
        if (definition.workstation.preUnlockQuestId != null
                && QuestDefinitionRegistry.get().getQuest(definition.workstation.preUnlockQuestId) == null) {
            issues.add("Pre-workstation quest is missing: " + definition.workstation.preUnlockQuestId);
        }
        return List.copyOf(issues);
    }
}
