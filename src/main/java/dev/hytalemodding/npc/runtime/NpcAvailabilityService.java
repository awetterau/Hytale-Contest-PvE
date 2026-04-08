package dev.hytalemodding.npc.runtime;

import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.config.NpcUnifiedRegistry;
import dev.hytalemodding.npc.core.NpcDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;
import dev.hytalemodding.npc.economy.NpcUpgradeService;
import dev.hytalemodding.npc.state.NpcRuntimeState;
import dev.hytalemodding.npc.state.NpcStateManager;
import dev.hytalemodding.quest.QuestProgressManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class NpcAvailabilityService {
    private static final NpcAvailabilityService INSTANCE = new NpcAvailabilityService();

    private NpcAvailabilityService() {
    }

    @Nonnull
    public static NpcAvailabilityService get() {
        return INSTANCE;
    }

    @Nonnull
    public AvailabilitySnapshot getAvailability(@Nonnull String npcKey) {
        String normalizedNpcKey = NpcDefinition.normalizeKey(npcKey);
        NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(normalizedNpcKey);
        NpcRuntimeState runtimeState = NpcStateManager.get().getState(normalizedNpcKey);
        HubNpcManager.NpcData hubNpcData = BaseHousingManager.get().getNpcData(normalizedNpcKey);
        boolean rescued = runtimeState.rescued || NpcProgressManager.get().isNpcRescued(normalizedNpcKey);
        boolean requiresWorkstation = definition != null && definition.workstation.required;
        boolean workshopBuilt = BaseHousingManager.get().isWorkshopBuilt(normalizedNpcKey);
        boolean hasAssignedWorkstation = runtimeState.assignedWorkstationId != null || hubNpcData.assignedPlotId != null;
        boolean preWorkshopQuestPending = definition != null
                && definition.workstation.preUnlockQuestId != null
                && !definition.workstation.preUnlockQuestId.isBlank()
                && !QuestProgressManager.get().isCompleted(definition.workstation.preUnlockQuestId);
        boolean preWorkshopUpgradeOnly = requiresWorkstation && !workshopBuilt && !preWorkshopQuestPending;
        boolean servicesUnlocked = !requiresWorkstation || workshopBuilt || hasAssignedWorkstation;
        boolean hasTradeUnlocks = !NpcProgressManager.get().getUnlockedTrades(normalizedNpcKey).isEmpty();

        boolean canTalk = definition != null && definition.services.canTalk;
        boolean canTrade = definition != null && definition.services.canTrade && servicesUnlocked && hasTradeUnlocks;
        boolean canQuest = definition != null
                && definition.services.canGiveQuests
                && (servicesUnlocked || preWorkshopQuestPending || preWorkshopUpgradeOnly);
        boolean canUpgrade = definition != null
                && definition.services.canUpgrade
                && (servicesUnlocked || preWorkshopUpgradeOnly)
                && NpcUpgradeService.get().hasAvailableUpgrade(normalizedNpcKey);
        boolean canOpenDialogue = definition != null
                && definition.hub.enabled
                && (definition.hub.alwaysPresent || rescued || servicesUnlocked || preWorkshopQuestPending || preWorkshopUpgradeOnly || canTalk || canQuest);

        NpcEconomyDefinition economy = NpcEconomyRegistry.get().getNpc(normalizedNpcKey);
        String displayName = definition == null ? normalizedNpcKey : definition.displayName;
        String readyText = economy == null ? (displayName + " is ready. What do you need?") : economy.readyDialogueText;
        String lockedText = economy == null ? (displayName + " needs setup before full services are available.") : economy.noWorkshopDialogueText;
        return new AvailabilitySnapshot(
                normalizedNpcKey,
                displayName,
                definition,
                runtimeState,
                rescued,
                requiresWorkstation,
                workshopBuilt,
                hasAssignedWorkstation,
                preWorkshopQuestPending,
                preWorkshopUpgradeOnly,
                servicesUnlocked,
                canOpenDialogue,
                canTalk,
                canTrade,
                canQuest,
                canUpgrade,
                readyText,
                lockedText
        );
    }

    public boolean isHubRoleInteractable(@Nonnull String npcKey) {
        return getAvailability(npcKey).canOpenDialogue;
    }

    public boolean isWorkshopBuilt(@Nonnull String npcKey) {
        return getAvailability(npcKey).workshopBuilt;
    }

    public boolean canUsePreWorkshopUpgrade(@Nonnull String npcKey) {
        return getAvailability(npcKey).preWorkshopUpgradeOnly;
    }

    public record AvailabilitySnapshot(
            @Nonnull String npcKey,
            @Nonnull String displayName,
            @Nullable NpcDefinition definition,
            @Nonnull NpcRuntimeState runtimeState,
            boolean rescued,
            boolean requiresWorkstation,
            boolean workshopBuilt,
            boolean hasAssignedWorkstation,
            boolean preWorkshopQuestPending,
            boolean preWorkshopUpgradeOnly,
            boolean servicesUnlocked,
            boolean canOpenDialogue,
            boolean canTalk,
            boolean canTrade,
            boolean canQuest,
            boolean canUpgrade,
            @Nonnull String readyText,
            @Nonnull String lockedText
    ) {
    }
}
