package dev.hytalemodding.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

public final class QuestDefinition {
    @Nonnull
    public final String questId;
    @Nonnull
    public final QuestCategory category;
    @Nonnull
    public final String title;
    @Nonnull
    public final String summary;
    @Nullable
    public final String sourceType;
    @Nullable
    public final String sourceId;
    @Nullable
    public final String nextQuestId;
    @Nullable
    public final String narrativeIntent;
    @Nullable
    public final String playerFantasy;
    @Nullable
    public final String pitch;
    @Nullable
    public final String offerText;
    @Nullable
    public final String inProgressText;
    @Nullable
    public final String completionText;
    @Nullable
    public final String journalSummary;
    @Nullable
    public final String hudMarkerText;
    @Nullable
    public final String completionCheckText;
    @Nullable
    public final String immediateRewardsText;
    @Nullable
    public final String targetPlayerLevel;
    @Nullable
    public final String expectedDuration;
    @Nullable
    public final String difficultyNotes;
    @Nullable
    public final String requiredAssets;
    @Nullable
    public final String requiredEncounters;
    @Nullable
    public final String requiredLocations;
    @Nullable
    public final String requiredUi;
    public final int requiredSuccessfulExtractions;
    public final int requiredNpcKills;
    @Nullable
    public final String trackedNpcRoleName;
    @Nonnull
    public final java.util.List<String> requiredCompletedQuests;
    public final boolean canBeMissed;
    public final boolean repeatable;
    @Nonnull
    public final java.util.List<String> rewardSetFlags;
    @Nonnull
    public final java.util.List<String> rewardRescueNpcs;
    @Nonnull
    public final java.util.List<String> rewardUnlockCrafts;
    @Nonnull
    public final java.util.List<String> rewardUnlockTrades;
    @Nonnull
    public final java.util.List<ItemAmount> requiredItems;
    public final boolean consumeRequiredItemsOnComplete;
    public final boolean rewardAutoAcceptNext;

    public QuestDefinition(
            @Nonnull String questId,
            @Nonnull QuestCategory category,
            @Nonnull String title,
            @Nonnull String summary,
            @Nullable String sourceType,
            @Nullable String sourceId,
            @Nullable String nextQuestId,
            @Nullable String narrativeIntent,
            @Nullable String playerFantasy,
            @Nullable String pitch,
            @Nullable String offerText,
            @Nullable String inProgressText,
            @Nullable String completionText,
            @Nullable String journalSummary,
            @Nullable String hudMarkerText,
            @Nullable String completionCheckText,
            @Nullable String immediateRewardsText,
            @Nullable String targetPlayerLevel,
            @Nullable String expectedDuration,
            @Nullable String difficultyNotes,
            @Nullable String requiredAssets,
            @Nullable String requiredEncounters,
            @Nullable String requiredLocations,
            @Nullable String requiredUi,
            int requiredSuccessfulExtractions,
            int requiredNpcKills,
            @Nullable String trackedNpcRoleName,
            @Nonnull java.util.List<String> requiredCompletedQuests,
            boolean canBeMissed,
            boolean repeatable,
            @Nonnull java.util.List<String> rewardSetFlags,
            @Nonnull java.util.List<String> rewardRescueNpcs,
            @Nonnull java.util.List<String> rewardUnlockCrafts,
            @Nonnull java.util.List<String> rewardUnlockTrades,
            @Nonnull java.util.List<ItemAmount> requiredItems,
            boolean consumeRequiredItemsOnComplete,
            boolean rewardAutoAcceptNext
    ) {
        this.questId = normalize(questId);
        this.category = category;
        this.title = title;
        this.summary = summary;
        this.sourceType = normalizeNullable(sourceType);
        this.sourceId = normalizeNullable(sourceId);
        this.nextQuestId = normalizeNullable(nextQuestId);
        this.narrativeIntent = normalizeNullable(narrativeIntent);
        this.playerFantasy = normalizeNullable(playerFantasy);
        this.pitch = normalizeNullable(pitch);
        this.offerText = normalizeNullable(offerText);
        this.inProgressText = normalizeNullable(inProgressText);
        this.completionText = normalizeNullable(completionText);
        this.journalSummary = normalizeNullable(journalSummary);
        this.hudMarkerText = normalizeNullable(hudMarkerText);
        this.completionCheckText = normalizeNullable(completionCheckText);
        this.immediateRewardsText = normalizeNullable(immediateRewardsText);
        this.targetPlayerLevel = normalizeNullable(targetPlayerLevel);
        this.expectedDuration = normalizeNullable(expectedDuration);
        this.difficultyNotes = normalizeNullable(difficultyNotes);
        this.requiredAssets = normalizeNullable(requiredAssets);
        this.requiredEncounters = normalizeNullable(requiredEncounters);
        this.requiredLocations = normalizeNullable(requiredLocations);
        this.requiredUi = normalizeNullable(requiredUi);
        this.requiredSuccessfulExtractions = Math.max(0, requiredSuccessfulExtractions);
        this.requiredNpcKills = Math.max(0, requiredNpcKills);
        this.trackedNpcRoleName = normalizeNullable(trackedNpcRoleName);
        this.requiredCompletedQuests = java.util.List.copyOf(requiredCompletedQuests);
        this.canBeMissed = canBeMissed;
        this.repeatable = repeatable;
        this.rewardSetFlags = java.util.List.copyOf(rewardSetFlags);
        this.rewardRescueNpcs = java.util.List.copyOf(rewardRescueNpcs);
        this.rewardUnlockCrafts = java.util.List.copyOf(rewardUnlockCrafts);
        this.rewardUnlockTrades = java.util.List.copyOf(rewardUnlockTrades);
        this.requiredItems = java.util.List.copyOf(requiredItems);
        this.consumeRequiredItemsOnComplete = consumeRequiredItemsOnComplete;
        this.rewardAutoAcceptNext = rewardAutoAcceptNext;
    }

    @Nonnull
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String normalizeNullable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum QuestCategory {
        MAIN,
        SIDE,
        NPC,
        WORLD,
        EVENT;

        @Nonnull
        public static QuestCategory fromRaw(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return SIDE;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "main" -> MAIN;
                case "npc" -> NPC;
                case "world" -> WORLD;
                case "event" -> EVENT;
                default -> SIDE;
            };
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



