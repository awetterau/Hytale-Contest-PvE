package dev.hytalemodding.npc.economy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class NpcEconomyDefinition {
    @Nonnull
    public final String npcKey;
    @Nonnull
    public final String readyDialogueText;
    @Nonnull
    public final String noWorkshopDialogueText;
    @Nonnull
    public final List<OfferDefinition> offers;
    @Nonnull
    public final List<UpgradeDefinition> upgrades;

    public NpcEconomyDefinition(
            @Nonnull String npcKey,
            @Nonnull String readyDialogueText,
            @Nonnull String noWorkshopDialogueText,
            @Nonnull List<OfferDefinition> offers,
            @Nonnull List<UpgradeDefinition> upgrades
    ) {
        this.npcKey = normalize(npcKey);
        this.readyDialogueText = readyDialogueText;
        this.noWorkshopDialogueText = noWorkshopDialogueText;
        this.offers = List.copyOf(offers);
        this.upgrades = List.copyOf(upgrades);
    }

    @Nullable
    public OfferDefinition getOffer(@Nonnull String offerId) {
        String key = normalize(offerId);
        for (OfferDefinition offer : this.offers) {
            if (offer.offerId.equals(key)) {
                return offer;
            }
        }
        return null;
    }

    @Nullable
    public UpgradeDefinition getUpgrade(@Nonnull String upgradeId) {
        String key = normalize(upgradeId);
        for (UpgradeDefinition upgrade : this.upgrades) {
            if (upgrade.upgradeId.equals(key)) {
                return upgrade;
            }
        }
        return null;
    }

    @Nonnull
    public List<OfferDefinition> getOffersByKind(@Nonnull OfferKind kind) {
        ArrayList<OfferDefinition> out = new ArrayList<>();
        for (OfferDefinition offer : this.offers) {
            if (offer.kind == kind) {
                out.add(offer);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public enum OfferKind {
        CRAFT,
        TRADE;

        @Nonnull
        public static OfferKind fromRaw(@Nullable String raw) {
            String normalized = normalize(raw);
            if ("craft".equals(normalized)) {
                return CRAFT;
            }
            return TRADE;
        }
    }

    public static final class OfferDefinition {
        @Nonnull
        public final String offerId;
        @Nonnull
        public final String title;
        @Nonnull
        public final OfferKind kind;
        @Nonnull
        public final List<ItemAmount> cost;
        @Nonnull
        public final List<ItemAmount> reward;
        @Nonnull
        public final Set<String> requiredFlags;
        public final int requiredTier;

        public OfferDefinition(
                @Nonnull String offerId,
                @Nonnull String title,
                @Nonnull OfferKind kind,
                @Nonnull List<ItemAmount> cost,
                @Nonnull List<ItemAmount> reward,
                @Nonnull Set<String> requiredFlags,
                int requiredTier
        ) {
            this.offerId = normalize(offerId);
            this.title = title.isBlank() ? this.offerId : title;
            this.kind = kind;
            this.cost = List.copyOf(cost);
            this.reward = List.copyOf(reward);
            this.requiredFlags = Set.copyOf(requiredFlags);
            this.requiredTier = Math.max(0, requiredTier);
        }
    }

    public static final class UpgradeDefinition {
        @Nonnull
        public final String upgradeId;
        @Nonnull
        public final String title;
        public final int targetTier;
        @Nonnull
        public final List<ItemAmount> cost;
        @Nonnull
        public final Set<String> requiredFlags;
        @Nonnull
        public final Set<String> grantCrafts;
        @Nonnull
        public final Set<String> grantTrades;
        @Nonnull
        public final Set<String> setFlags;

        public UpgradeDefinition(
                @Nonnull String upgradeId,
                @Nonnull String title,
                int targetTier,
                @Nonnull List<ItemAmount> cost,
                @Nonnull Set<String> requiredFlags,
                @Nonnull Set<String> grantCrafts,
                @Nonnull Set<String> grantTrades,
                @Nonnull Set<String> setFlags
        ) {
            this.upgradeId = normalize(upgradeId);
            this.title = title.isBlank() ? this.upgradeId : title;
            this.targetTier = Math.max(1, targetTier);
            this.cost = List.copyOf(cost);
            this.requiredFlags = Set.copyOf(requiredFlags);
            this.grantCrafts = Set.copyOf(grantCrafts);
            this.grantTrades = Set.copyOf(grantTrades);
            this.setFlags = Set.copyOf(setFlags);
        }
    }

    public static final class ItemAmount {
        @Nonnull
        public final String itemId;
        public final int amount;

        public ItemAmount(@Nonnull String itemId, int amount) {
            this.itemId = itemId.trim();
            this.amount = Math.max(1, amount);
        }
    }
}
