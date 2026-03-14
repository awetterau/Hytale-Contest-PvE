package dev.hytalemodding.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.List;
import java.util.Set;

public final class NpcArchetype {
    @Nonnull
    public final String npcKey;
    @Nonnull
    public final NpcCategory category;
    @Nonnull
    public final String displayName;
    @Nullable
    public final String runRescueRole;
    @Nullable
    public final String hubRole;
    @Nullable
    public final String plotType;
    @Nullable
    public final String homeTemplateId;
    @Nullable
    public final String prePlotQuestId;
    @Nonnull
    public final PlotUnlockMode plotUnlockMode;
    @Nonnull
    public final NpcServices services;
    @Nullable
    public final String dialogueSetId;
    @Nullable
    public final String questSetId;
    @Nullable
    public final String upgradeTreeId;
    @Nullable
    public final String craftSetId;
    @Nullable
    public final String tradeSetId;
    @Nonnull
    public final List<String> defaultCraftUnlocks;
    @Nonnull
    public final List<String> defaultTradeUnlocks;
    public final boolean animalRoutesToFarmer;
    @Nullable
    public final String farmerNpcKey;

    public NpcArchetype(
            @Nonnull String npcKey,
            @Nonnull NpcCategory category,
            @Nonnull String displayName,
            @Nullable String runRescueRole,
            @Nullable String hubRole,
            @Nullable String plotType,
            @Nullable String homeTemplateId,
            @Nullable String prePlotQuestId,
            @Nonnull PlotUnlockMode plotUnlockMode,
            @Nonnull NpcServices services,
            @Nullable String dialogueSetId,
            @Nullable String questSetId,
            @Nullable String upgradeTreeId,
            @Nullable String craftSetId,
            @Nullable String tradeSetId,
            @Nonnull List<String> defaultCraftUnlocks,
            @Nonnull List<String> defaultTradeUnlocks,
            boolean animalRoutesToFarmer,
            @Nullable String farmerNpcKey
    ) {
        this.npcKey = normalizeKey(npcKey);
        this.category = category;
        this.displayName = displayName;
        this.runRescueRole = normalizeNullable(runRescueRole);
        this.hubRole = normalizeNullable(hubRole);
        this.plotType = normalizeNullable(plotType);
        this.homeTemplateId = normalizeNullable(homeTemplateId);
        this.prePlotQuestId = normalizeNullable(prePlotQuestId);
        this.plotUnlockMode = plotUnlockMode;
        this.services = services;
        this.dialogueSetId = normalizeNullable(dialogueSetId);
        this.questSetId = normalizeNullable(questSetId);
        this.upgradeTreeId = normalizeNullable(upgradeTreeId);
        this.craftSetId = normalizeNullable(craftSetId);
        this.tradeSetId = normalizeNullable(tradeSetId);
        this.defaultCraftUnlocks = List.copyOf(defaultCraftUnlocks);
        this.defaultTradeUnlocks = List.copyOf(defaultTradeUnlocks);
        this.animalRoutesToFarmer = animalRoutesToFarmer;
        this.farmerNpcKey = normalizeNullable(farmerNpcKey);
    }

    @Nonnull
    public static String normalizeKey(@Nullable String raw) {
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

    public enum NpcCategory {
        AMBIENT,
        ANIMAL,
        SPECIALIST;

        @Nonnull
        public static NpcCategory fromRaw(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return SPECIALIST;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "ambient" -> AMBIENT;
                case "animal" -> ANIMAL;
                default -> SPECIALIST;
            };
        }
    }

    public enum PlotUnlockMode {
        NONE,
        MATERIALS,
        QUESTLINE,
        MATERIALS_OR_QUESTLINE;

        @Nonnull
        public static PlotUnlockMode fromRaw(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return MATERIALS;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "none" -> NONE;
                case "questline" -> QUESTLINE;
                case "materials_or_questline" -> MATERIALS_OR_QUESTLINE;
                default -> MATERIALS;
            };
        }
    }

    public static final class NpcServices {
        private static final String TALK = "talk";
        private static final String CRAFT = "craft";
        private static final String TRADE = "trade";
        private static final String QUESTS = "quests";
        private static final String UPGRADES = "upgrades";

        public final boolean canTalk;
        public final boolean canCraft;
        public final boolean canTrade;
        public final boolean canGiveQuests;
        public final boolean canUpgrade;

        public NpcServices(
                boolean canTalk,
                boolean canCraft,
                boolean canTrade,
                boolean canGiveQuests,
                boolean canUpgrade
        ) {
            this.canTalk = canTalk;
            this.canCraft = canCraft;
            this.canTrade = canTrade;
            this.canGiveQuests = canGiveQuests;
            this.canUpgrade = canUpgrade;
        }

        @Nonnull
        public static NpcServices fromCsv(@Nullable String csv) {
            if (csv == null || csv.isBlank()) {
                return new NpcServices(true, false, false, false, false);
            }
            String[] parts = csv.split(",");
            Set<String> values = new java.util.HashSet<>();
            for (String part : parts) {
                String item = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
                if (!item.isEmpty()) {
                    values.add(item);
                }
            }
            return new NpcServices(
                    values.contains(TALK),
                    values.contains(CRAFT),
                    values.contains(TRADE),
                    values.contains(QUESTS),
                    values.contains(UPGRADES)
            );
        }
    }
}



