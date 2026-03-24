package dev.hytalemodding.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.hypixel.hytale.math.vector.Transform;
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
    @Nonnull
    public final List<String> defaultCraftUnlocks;
    @Nonnull
    public final List<String> defaultTradeUnlocks;
    @Nonnull
    public final List<String> followStateAliases;
    public final boolean animalRoutesToFarmer;
    @Nullable
    public final String farmerNpcKey;
    public final boolean alwaysInHub;
    @Nullable
    public final Transform hubSpawnTransform;

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
            @Nonnull List<String> defaultCraftUnlocks,
            @Nonnull List<String> defaultTradeUnlocks,
            @Nonnull List<String> followStateAliases,
            boolean animalRoutesToFarmer,
            @Nullable String farmerNpcKey,
            boolean alwaysInHub,
            @Nullable Transform hubSpawnTransform
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
        this.defaultCraftUnlocks = List.copyOf(defaultCraftUnlocks);
        this.defaultTradeUnlocks = List.copyOf(defaultTradeUnlocks);
        this.followStateAliases = defaultLowercase(followStateAliases);
        this.animalRoutesToFarmer = animalRoutesToFarmer;
        this.farmerNpcKey = normalizeNullable(farmerNpcKey);
        this.alwaysInHub = alwaysInHub;
        this.hubSpawnTransform = hubSpawnTransform == null
                ? null
                : new Transform(hubSpawnTransform.getPosition(), hubSpawnTransform.getRotation());
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

    @Nonnull
    private static List<String> defaultLowercase(@Nonnull List<String> values) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
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



