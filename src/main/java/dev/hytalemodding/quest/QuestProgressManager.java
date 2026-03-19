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

    public synchronized boolean isCompleted(@Nonnull String questId) {
        ensureLoaded();
        String key = normalize(questId);
        if (key.isBlank()) {
            return false;
        }
        return getOrCreate(key).completed;
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

    @Nonnull
    public synchronized List<String> describeAll() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (QuestDefinition definition : QuestDefinitionRegistry.get().getAll()) {
            QuestProgress progress = getOrCreate(definition.questId);
            out.add(definition.questId
                    + " [" + definition.category.name() + "]"
                    + " accepted=" + progress.accepted
                    + " completed=" + progress.completed);
        }
        if (out.isEmpty()) {
            out.add("<none>");
        }
        return out;
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

    public static final class QuestProgress {
        @Nonnull
        public final String questId;
        public final boolean accepted;
        public final boolean completed;

        private QuestProgress(@Nonnull String questId, boolean accepted, boolean completed) {
            this.questId = normalize(questId);
            this.accepted = accepted;
            this.completed = completed;
        }

        @Nonnull
        public static QuestProgress defaultFor(@Nonnull String questId) {
            return new QuestProgress(questId, false, false);
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
            return new QuestProgress(key, accepted, completed);
        }

        private void writeToProperties(@Nonnull Properties p) {
            String prefix = "quest." + this.questId + ".";
            p.setProperty(prefix + "accepted", Boolean.toString(this.accepted));
            p.setProperty(prefix + "completed", Boolean.toString(this.completed));
        }

        @Nonnull
        private QuestProgress withState(boolean accepted, boolean completed) {
            return new QuestProgress(this.questId, accepted, completed);
        }

        @Nonnull
        private QuestProgress copy() {
            return new QuestProgress(this.questId, this.accepted, this.completed);
        }
    }
}



