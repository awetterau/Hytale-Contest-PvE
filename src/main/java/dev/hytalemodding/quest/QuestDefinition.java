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
    @Nonnull
    public final java.util.List<String> rewardSetFlags;
    @Nonnull
    public final java.util.List<String> rewardRescueNpcs;
    @Nonnull
    public final java.util.List<String> rewardUnlockCrafts;
    @Nonnull
    public final java.util.List<String> rewardUnlockTrades;
    public final boolean rewardAutoAcceptNext;

    public QuestDefinition(
            @Nonnull String questId,
            @Nonnull QuestCategory category,
            @Nonnull String title,
            @Nonnull String summary,
            @Nullable String sourceType,
            @Nullable String sourceId,
            @Nullable String nextQuestId,
            @Nonnull java.util.List<String> rewardSetFlags,
            @Nonnull java.util.List<String> rewardRescueNpcs,
            @Nonnull java.util.List<String> rewardUnlockCrafts,
            @Nonnull java.util.List<String> rewardUnlockTrades,
            boolean rewardAutoAcceptNext
    ) {
        this.questId = normalize(questId);
        this.category = category;
        this.title = title;
        this.summary = summary;
        this.sourceType = normalizeNullable(sourceType);
        this.sourceId = normalizeNullable(sourceId);
        this.nextQuestId = normalizeNullable(nextQuestId);
        this.rewardSetFlags = java.util.List.copyOf(rewardSetFlags);
        this.rewardRescueNpcs = java.util.List.copyOf(rewardRescueNpcs);
        this.rewardUnlockCrafts = java.util.List.copyOf(rewardUnlockCrafts);
        this.rewardUnlockTrades = java.util.List.copyOf(rewardUnlockTrades);
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
}



