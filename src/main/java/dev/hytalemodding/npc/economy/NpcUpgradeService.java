package dev.hytalemodding.npc.economy;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.quest.QuestFlagManager;

import javax.annotation.Nonnull;
import java.util.Set;

public final class NpcUpgradeService {
    private static final NpcUpgradeService INSTANCE = new NpcUpgradeService();

    private NpcUpgradeService() {
    }

    @Nonnull
    public static NpcUpgradeService get() {
        return INSTANCE;
    }

    @Nonnull
    public Result executeUpgrade(@Nonnull PlayerRef playerRef, @Nonnull String npcKey, @Nonnull String upgradeId) {
        NpcEconomyDefinition npc = NpcEconomyRegistry.get().getNpc(npcKey);
        if (npc == null) {
            return Result.fail("No economy config found for NPC: " + npcKey);
        }
        NpcEconomyDefinition.UpgradeDefinition upgrade = npc.getUpgrade(upgradeId);
        if (upgrade == null) {
            return Result.fail("Upgrade not found: " + upgradeId);
        }

        NpcProgressManager.NpcProgress progress = NpcProgressManager.get().getOrCreate(npcKey);
        if (upgrade.targetTier != progress.upgradeTier + 1) {
            return Result.fail("Upgrade order invalid. Current tier: " + progress.upgradeTier);
        }
        if (!hasRequiredFlags(upgrade.requiredFlags)) {
            return Result.fail("Upgrade requirements not met.");
        }
        if (!NpcInventoryService.canAfford(playerRef, upgrade.cost)) {
            return Result.fail("Not enough materials.");
        }

        boolean blacksmithWorkshopUpgrade = "blacksmith".equalsIgnoreCase(npcKey) && upgrade.targetTier == 1;
        boolean farmerWorkshopUpgrade = "farmer".equalsIgnoreCase(npcKey) && upgrade.targetTier == 1;
        if (blacksmithWorkshopUpgrade) {
            BaseHousingManager.AssignmentResult precheck = BaseHousingManager.get().beginBlacksmithWorkshopUpgradePrecheck(playerRef);
            if (!precheck.success) {
                return Result.fail(precheck.message);
            }
        }
        if (farmerWorkshopUpgrade) {
            BaseHousingManager.AssignmentResult precheck = BaseHousingManager.get().beginFarmerWorkshopUpgradePrecheck(playerRef);
            if (!precheck.success) {
                return Result.fail(precheck.message);
            }
        }
        if (!NpcInventoryService.executeTransaction(playerRef, upgrade.cost, java.util.List.of())) {
            return Result.fail("Upgrade transaction failed.");
        }

        if (blacksmithWorkshopUpgrade) {
            BaseHousingManager.AssignmentResult workshopResult = BaseHousingManager.get().beginBlacksmithWorkshopUpgrade(playerRef);
            if (!workshopResult.success) {
                return Result.fail(workshopResult.message);
            }
        }
        if (farmerWorkshopUpgrade) {
            BaseHousingManager.AssignmentResult workshopResult = BaseHousingManager.get().beginFarmerWorkshopUpgrade(playerRef);
            if (!workshopResult.success) {
                return Result.fail(workshopResult.message);
            }
        }
        NpcProgressManager.get().setUpgradeTier(npcKey, upgrade.targetTier);
        if (!upgrade.grantCrafts.isEmpty()) {
            NpcProgressManager.get().grantCraftUnlocks(npcKey, java.util.List.copyOf(upgrade.grantCrafts));
        }
        if (!upgrade.grantTrades.isEmpty()) {
            NpcProgressManager.get().grantTradeUnlocks(npcKey, java.util.List.copyOf(upgrade.grantTrades));
        }
        for (String flag : upgrade.setFlags) {
            QuestFlagManager.get().setFlag(flag);
        }
        if (blacksmithWorkshopUpgrade) {
            return Result.ok("Blacksmith workshop construction will begin shortly.", true);
        }
        if (farmerWorkshopUpgrade) {
            return Result.ok("Farmer workstation construction will begin shortly.", true);
        }
        return Result.ok("Upgrade applied: " + upgrade.title + ".", false);
    }

    public boolean canUpgrade(@Nonnull String npcKey, @Nonnull NpcEconomyDefinition.UpgradeDefinition upgrade) {
        NpcProgressManager.NpcProgress progress = NpcProgressManager.get().getOrCreate(npcKey);
        if (upgrade.targetTier != progress.upgradeTier + 1) {
            return false;
        }
        return hasRequiredFlags(upgrade.requiredFlags);
    }

    public boolean hasAvailableUpgrade(@Nonnull String npcKey) {
        NpcEconomyDefinition npc = NpcEconomyRegistry.get().getNpc(npcKey);
        if (npc == null) {
            return false;
        }
        for (NpcEconomyDefinition.UpgradeDefinition upgrade : npc.upgrades) {
            if (canUpgrade(npcKey, upgrade)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRequiredFlags(@Nonnull Set<String> requiredFlags) {
        if (requiredFlags.isEmpty()) {
            return true;
        }
        Set<String> flags = QuestFlagManager.get().getFlags();
        for (String required : requiredFlags) {
            if (!flags.contains(required)) {
                return false;
            }
        }
        return true;
    }

    public record Result(boolean success, @Nonnull String message, boolean closeUiOnSuccess) {
        @Nonnull
        public static Result ok(@Nonnull String message, boolean closeUiOnSuccess) {
            return new Result(true, message, closeUiOnSuccess);
        }

        @Nonnull
        public static Result fail(@Nonnull String message) {
            return new Result(false, message, false);
        }
    }
}
