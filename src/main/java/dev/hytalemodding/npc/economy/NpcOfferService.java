package dev.hytalemodding.npc.economy;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.quest.QuestFlagManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

public final class NpcOfferService {
    private static final NpcOfferService INSTANCE = new NpcOfferService();

    private NpcOfferService() {
    }

    @Nonnull
    public static NpcOfferService get() {
        return INSTANCE;
    }

    @Nonnull
    public Result executeOffer(@Nonnull PlayerRef playerRef, @Nonnull String npcKey, @Nonnull String offerId) {
        NpcEconomyDefinition npc = NpcEconomyRegistry.get().getNpc(npcKey);
        if (npc == null) {
            return Result.fail("No economy config found for NPC: " + npcKey);
        }
        NpcEconomyDefinition.OfferDefinition offer = npc.getOffer(offerId);
        if (offer == null) {
            return Result.fail("Offer not found: " + offerId);
        }
        if (!canUseOffer(npcKey, offer)) {
            return Result.fail("Offer requirements not met.");
        }

        if (!NpcInventoryService.canAfford(playerRef, offer.cost)) {
            return Result.fail("Not enough materials.");
        }
        boolean applied = NpcInventoryService.executeTransaction(playerRef, offer.cost, offer.reward);
        if (!applied) {
            return Result.fail("Transaction failed (inventory full or unavailable).");
        }
        return Result.ok("Executed " + offer.title + ".");
    }

    public boolean canUseOffer(@Nonnull String npcKey, @Nonnull NpcEconomyDefinition.OfferDefinition offer) {
        if (!offer.requiredFlags.isEmpty()) {
            Set<String> flags = QuestFlagManager.get().getFlags();
            for (String required : offer.requiredFlags) {
                if (!flags.contains(required)) {
                    return false;
                }
            }
        }
        NpcProgressManager.NpcProgress progress = NpcProgressManager.get().getOrCreate(npcKey);
        if (progress.upgradeTier < offer.requiredTier) {
            return false;
        }
        if (offer.kind == NpcEconomyDefinition.OfferKind.CRAFT) {
            return progress.unlockedCrafts.contains(offer.offerId);
        }
        return progress.unlockedTrades.contains(offer.offerId);
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
