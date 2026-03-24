package dev.hytalemodding.quest;

import com.hypixel.hytale.server.core.universe.Universe;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcInventoryService;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestProgressManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "quest-progress.properties";
    private static final QuestProgressManager INSTANCE = new QuestProgressManager();

    private final ConcurrentHashMap<String, QuestProgress> byQuestId = new ConcurrentHashMap<>();
    private boolean loaded;

    private QuestProgressManager() {
    }

    @Nonnull
    public static QuestProgressManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        ensureLoaded();
    }

    @Nonnull
    public synchronized QuestProgress getOrCreate(@Nonnull String questId) {
        ensureLoaded();
        String key = normalize(questId);
        if (key.isBlank()) {
            return QuestProgress.defaultFor("unknown");
        }
        QuestProgress existing = this.byQuestId.get(key);
        if (existing != null) {
            return existing.copy();
        }
        QuestProgress created = QuestProgress.defaultFor(key);
        this.byQuestId.put(key, created);
        saveQuietly();
        return created.copy();
    }

    public synchronized boolean accept(@Nonnull String questId) {
        ensureLoaded();
        QuestDefinition definition = QuestDefinitionRegistry.get().getQuest(questId);
        if (definition == null) {
            return false;
        }
        if (!arePrerequisitesMet(definition)) {
            return false;
        }
        QuestProgress current = getOrCreate(definition.questId);
        if (current.completed) {
            return false;
        }
        this.byQuestId.put(definition.questId, current.withState(true, false));
        saveQuietly();
        return true;
    }

    public synchronized boolean complete(@Nonnull String questId) {
        return complete(questId, null);
    }

    public synchronized boolean complete(@Nonnull String questId, @Nullable PlayerRef playerRef) {
        ensureLoaded();
        QuestDefinition definition = QuestDefinitionRegistry.get().getQuest(questId);
        if (definition == null) {
            return false;
        }
        if (!canComplete(definition, playerRef)) {
            return false;
        }
        if (playerRef != null && !definition.requiredItems.isEmpty()) {
            java.util.List<NpcEconomyDefinition.ItemAmount> costs = toEconomyItems(definition.requiredItems);
            if (!NpcInventoryService.canAfford(playerRef, costs)) {
                return false;
            }
            if (definition.consumeRequiredItemsOnComplete) {
                if (!NpcInventoryService.executeTransaction(playerRef, costs, java.util.List.of())) {
                    return false;
                }
            }
        }
        QuestProgress current = getOrCreate(definition.questId);
        this.byQuestId.put(definition.questId, current.withState(true, true));
        applyCompletionRewards(definition);
        saveQuietly();
        return true;
    }

    public synchronized boolean canComplete(@Nonnull String questId, @Nullable PlayerRef playerRef) {
        ensureLoaded();
        QuestDefinition definition = QuestDefinitionRegistry.get().getQuest(questId);
        return definition != null && canComplete(definition, playerRef);
    }

    public synchronized boolean isCompleted(@Nonnull String questId) {
        ensureLoaded();
        String key = normalize(questId);
        if (key.isBlank()) {
            return false;
        }
        return getOrCreate(key).completed;
    }

    public synchronized boolean arePrerequisitesMet(@Nonnull QuestDefinition definition) {
        ensureLoaded();
        for (String requiredQuestId : definition.requiredCompletedQuests) {
            if (!isCompleted(requiredQuestId)) {
                return false;
            }
        }
        return true;
    }

    public synchronized void reset(@Nonnull String questId) {
        ensureLoaded();
        String key = normalize(questId);
        if (key.isBlank()) {
            return;
        }
        this.byQuestId.put(key, QuestProgress.defaultFor(key));
        saveQuietly();
    }

    public synchronized boolean incrementSuccessfulExtraction() {
        ensureLoaded();
        boolean changed = false;
        for (QuestDefinition definition : QuestDefinitionRegistry.get().getAll()) {
            if (definition.requiredSuccessfulExtractions <= 0) {
                continue;
            }
            QuestProgress progress = getOrCreate(definition.questId);
            if (!progress.accepted || progress.completed) {
                continue;
            }
            this.byQuestId.put(definition.questId, progress.withSuccessfulExtractions(progress.successfulExtractions + 1));
            changed = true;
        }
        if (changed) {
            saveQuietly();
        }
        return changed;
    }

    public synchronized boolean incrementNpcKillForRole(@Nullable String roleName) {
        ensureLoaded();
        String normalizedRoleName = normalize(roleName);
        if (normalizedRoleName.isBlank()) {
            return false;
        }
        boolean changed = false;
        for (QuestDefinition definition : QuestDefinitionRegistry.get().getAll()) {
            if (definition.requiredNpcKills <= 0) {
                continue;
            }
            if (definition.trackedNpcRoleName == null || !normalize(definition.trackedNpcRoleName).equals(normalizedRoleName)) {
                continue;
            }
            QuestProgress progress = getOrCreate(definition.questId);
            if (!progress.accepted || progress.completed) {
                continue;
            }
            this.byQuestId.put(definition.questId, progress.withTrackedNpcKills(progress.trackedNpcKills + 1));
            changed = true;
        }
        if (changed) {
            saveQuietly();
        }
        return changed;
    }

    @Nonnull
    public synchronized List<String> describeAll() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (QuestDefinition definition : QuestDefinitionRegistry.get().getAll()) {
            QuestProgress progress = getOrCreate(definition.questId);
            out.add(definition.questId
                    + " [" + definition.category.name() + "]"
                    + " accepted=" + progress.accepted
                    + " completed=" + progress.completed
                    + " extractions=" + progress.successfulExtractions
                    + " npcKills=" + progress.trackedNpcKills);
        }
        if (out.isEmpty()) {
            out.add("<none>");
        }
        return out;
    }

    @Nonnull
    public synchronized List<QuestDefinition> getActiveQuestDefinitions() {
        ensureLoaded();
        List<QuestDefinition> out = new ArrayList<>();
        for (QuestDefinition definition : QuestDefinitionRegistry.get().getAll()) {
            QuestProgress progress = getOrCreate(definition.questId);
            if (progress.accepted && !progress.completed) {
                out.add(definition);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    public synchronized List<String> getProgressLines(@Nonnull QuestDefinition definition) {
        QuestProgress progress = getOrCreate(definition.questId);
        List<String> lines = new ArrayList<>();
        if (definition.requiredSuccessfulExtractions > 0) {
            lines.add("Missions extracted: "
                    + Math.min(progress.successfulExtractions, definition.requiredSuccessfulExtractions)
                    + "/" + definition.requiredSuccessfulExtractions);
        }
        if (definition.requiredNpcKills > 0) {
            lines.add("Blight Beasts slain: "
                    + Math.min(progress.trackedNpcKills, definition.requiredNpcKills)
                    + "/" + definition.requiredNpcKills);
        }
        if (lines.isEmpty() && definition.hudMarkerText != null && !definition.hudMarkerText.isBlank()) {
            lines.add(definition.hudMarkerText);
        }
        return List.copyOf(lines);
    }

    public synchronized double getOverallProgressRatio(@Nonnull QuestDefinition definition) {
        QuestProgress progress = getOrCreate(definition.questId);
        double total = 0.0;
        int parts = 0;
        if (definition.requiredSuccessfulExtractions > 0) {
            total += cappedRatio(progress.successfulExtractions, definition.requiredSuccessfulExtractions);
            parts++;
        }
        if (definition.requiredNpcKills > 0) {
            total += cappedRatio(progress.trackedNpcKills, definition.requiredNpcKills);
            parts++;
        }
        if (parts == 0) {
            return 0.0;
        }
        return total / (double) parts;
    }

    public synchronized double getSuccessfulExtractionRatio(@Nonnull QuestDefinition definition) {
        QuestProgress progress = getOrCreate(definition.questId);
        return cappedRatio(progress.successfulExtractions, definition.requiredSuccessfulExtractions);
    }

    public synchronized double getTrackedNpcKillRatio(@Nonnull QuestDefinition definition) {
        QuestProgress progress = getOrCreate(definition.questId);
        return cappedRatio(progress.trackedNpcKills, definition.requiredNpcKills);
    }

    @Nonnull
    public synchronized String describeIncompleteObjectives(@Nonnull QuestDefinition definition) {
        List<String> missing = new ArrayList<>();
        QuestProgress progress = getOrCreate(definition.questId);
        if (definition.requiredSuccessfulExtractions > 0 && progress.successfulExtractions < definition.requiredSuccessfulExtractions) {
            missing.add("missions extracted " + progress.successfulExtractions + "/" + definition.requiredSuccessfulExtractions);
        }
        if (definition.requiredNpcKills > 0 && progress.trackedNpcKills < definition.requiredNpcKills) {
            missing.add("Blight Beasts slain " + progress.trackedNpcKills + "/" + definition.requiredNpcKills);
        }
        return String.join(", ", missing);
    }

    private void applyCompletionRewards(@Nonnull QuestDefinition definition) {
        for (String flag : definition.rewardSetFlags) {
            QuestFlagManager.get().setFlag(flag);
        }
        for (String npcKey : definition.rewardRescueNpcs) {
            NpcProgressManager.get().setNpcRescued(npcKey, true);
            GameFlowConfigManager.get().setNpcRescued(npcKey, true);
        }
        if (definition.sourceType != null
                && "npc".equalsIgnoreCase(definition.sourceType)
                && definition.sourceId != null
                && !definition.sourceId.isBlank()) {
            if (!definition.rewardUnlockCrafts.isEmpty()) {
                NpcProgressManager.get().grantCraftUnlocks(definition.sourceId, definition.rewardUnlockCrafts);
            }
            if (!definition.rewardUnlockTrades.isEmpty()) {
                NpcProgressManager.get().grantTradeUnlocks(definition.sourceId, definition.rewardUnlockTrades);
            }
        }
        if (definition.rewardAutoAcceptNext && definition.nextQuestId != null && !definition.nextQuestId.isBlank()) {
            accept(definition.nextQuestId);
        }
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        Path path = getConfigPath();
        if (path == null || !Files.exists(path)) {
            return;
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            System.out.println("[QuestProgress] Failed to load: " + e.getMessage());
            return;
        }
        for (String questId : parseCsv(p.getProperty("quests"))) {
            QuestProgress progress = QuestProgress.fromProperties(p, questId);
            if (progress != null) {
                this.byQuestId.put(progress.questId, progress);
            }
        }
    }

    private boolean canComplete(@Nonnull QuestDefinition definition, @Nullable PlayerRef playerRef) {
        QuestProgress current = getOrCreate(definition.questId);
        if (!current.accepted || current.completed) {
            return false;
        }
        if (definition.requiredSuccessfulExtractions > 0
                && current.successfulExtractions < definition.requiredSuccessfulExtractions) {
            return false;
        }
        if (definition.requiredNpcKills > 0
                && current.trackedNpcKills < definition.requiredNpcKills) {
            return false;
        }
        if (playerRef != null && !definition.requiredItems.isEmpty()) {
            java.util.List<NpcEconomyDefinition.ItemAmount> costs = toEconomyItems(definition.requiredItems);
            return NpcInventoryService.canAfford(playerRef, costs);
        }
        return true;
    }

    private synchronized void saveQuietly() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("quests", String.join(",", this.byQuestId.keySet()));
        for (Map.Entry<String, QuestProgress> entry : this.byQuestId.entrySet()) {
            entry.getValue().writeToProperties(p);
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(writer, "Quest progression");
            }
        } catch (IOException e) {
            System.out.println("[QuestProgress] Failed to save: " + e.getMessage());
        }
    }

    @Nullable
    private static Path getConfigPath() {
        try {
            Path universePath = Universe.get().getPath();
            if (universePath == null) {
                return null;
            }
            return universePath.resolve("plugins").resolve(PLUGIN_CONFIG_DIR).resolve(CONFIG_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    @Nonnull
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = normalize(item);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static int parseInt(@Nullable String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nonnull
    private static java.util.List<NpcEconomyDefinition.ItemAmount> toEconomyItems(
            @Nonnull java.util.List<QuestDefinition.ItemAmount> requiredItems
    ) {
        java.util.ArrayList<NpcEconomyDefinition.ItemAmount> out = new java.util.ArrayList<>(requiredItems.size());
        for (QuestDefinition.ItemAmount required : requiredItems) {
            out.add(new NpcEconomyDefinition.ItemAmount(required.itemId, required.amount));
        }
        return java.util.List.copyOf(out);
    }

    private static double cappedRatio(int current, int target) {
        if (target <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) current / (double) target);
    }

    public static final class QuestProgress {
        @Nonnull
        public final String questId;
        public final boolean accepted;
        public final boolean completed;
        public final int successfulExtractions;
        public final int trackedNpcKills;

        private QuestProgress(
                @Nonnull String questId,
                boolean accepted,
                boolean completed,
                int successfulExtractions,
                int trackedNpcKills
        ) {
            this.questId = normalize(questId);
            this.accepted = accepted;
            this.completed = completed;
            this.successfulExtractions = Math.max(0, successfulExtractions);
            this.trackedNpcKills = Math.max(0, trackedNpcKills);
        }

        @Nonnull
        public static QuestProgress defaultFor(@Nonnull String questId) {
            return new QuestProgress(questId, false, false, 0, 0);
        }

        @Nullable
        private static QuestProgress fromProperties(@Nonnull Properties p, @Nonnull String questId) {
            String key = normalize(questId);
            if (key.isBlank()) {
                return null;
            }
            String prefix = "quest." + key + ".";
            boolean accepted = Boolean.parseBoolean(p.getProperty(prefix + "accepted", "false"));
            boolean completed = Boolean.parseBoolean(p.getProperty(prefix + "completed", "false"));
            int successfulExtractions = parseInt(p.getProperty(prefix + "progress.successfulExtractions"), 0);
            int trackedNpcKills = parseInt(p.getProperty(prefix + "progress.trackedNpcKills"), 0);
            return new QuestProgress(key, accepted, completed, successfulExtractions, trackedNpcKills);
        }

        private void writeToProperties(@Nonnull Properties p) {
            String prefix = "quest." + this.questId + ".";
            p.setProperty(prefix + "accepted", Boolean.toString(this.accepted));
            p.setProperty(prefix + "completed", Boolean.toString(this.completed));
            p.setProperty(prefix + "progress.successfulExtractions", Integer.toString(this.successfulExtractions));
            p.setProperty(prefix + "progress.trackedNpcKills", Integer.toString(this.trackedNpcKills));
        }

        @Nonnull
        private QuestProgress withState(boolean accepted, boolean completed) {
            return new QuestProgress(this.questId, accepted, completed, this.successfulExtractions, this.trackedNpcKills);
        }

        @Nonnull
        private QuestProgress withSuccessfulExtractions(int successfulExtractions) {
            return new QuestProgress(this.questId, this.accepted, this.completed, successfulExtractions, this.trackedNpcKills);
        }

        @Nonnull
        private QuestProgress withTrackedNpcKills(int trackedNpcKills) {
            return new QuestProgress(this.questId, this.accepted, this.completed, this.successfulExtractions, trackedNpcKills);
        }

        @Nonnull
        private QuestProgress copy() {
            return new QuestProgress(this.questId, this.accepted, this.completed, this.successfulExtractions, this.trackedNpcKills);
        }
    }
}



