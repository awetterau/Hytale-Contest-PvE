package dev.hytalemodding.npc.economy;

import com.hypixel.hytale.server.core.universe.PlayerRef;
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
        if (!NpcInventoryService.executeTransaction(playerRef, upgrade.cost, java.util.List.of())) {
            return Result.fail("Upgrade transaction failed.");
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
        return Result.ok("Upgrade applied: " + upgrade.title + ".");
    }

    public boolean canUpgrade(@Nonnull String npcKey, @Nonnull NpcEconomyDefinition.UpgradeDefinition upgrade) {
        NpcProgressManager.NpcProgress progress = NpcProgressManager.get().getOrCreate(npcKey);
        if (upgrade.targetTier != progress.upgradeTier + 1) {
            return false;
        }
        return hasRequiredFlags(upgrade.requiredFlags);
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

    public record Result(boolean success, @Nonnull String message) {
        @Nonnull
        public static Result ok(@Nonnull String message) {
            return new Result(true, message);
        }

        @Nonnull
        public static Result fail(@Nonnull String message) {
            return new Result(false, message);
        }
    }
}
