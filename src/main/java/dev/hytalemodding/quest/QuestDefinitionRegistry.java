package dev.hytalemodding.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestDefinitionRegistry {
    private static final String DEFINITIONS_RESOURCE = "Common/QuestData/quest-definitions.properties";
    private static final QuestDefinitionRegistry INSTANCE = new QuestDefinitionRegistry();

    private final ConcurrentHashMap<String, QuestDefinition> byQuestId = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private QuestDefinitionRegistry() {
    }

    @Nonnull
    public static QuestDefinitionRegistry get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadFromResource();
        System.out.println("[QuestDefs] Loaded quest definitions: " + this.byQuestId.size());
    }

    @Nullable
    public QuestDefinition getQuest(@Nonnull String questId) {
        initialize();
        return this.byQuestId.get(QuestDefinition.normalize(questId));
    }

    @Nonnull
    public Collection<QuestDefinition> getAll() {
        initialize();
        List<QuestDefinition> out = new ArrayList<>(this.byQuestId.values());
        out.sort((a, b) -> a.questId.compareToIgnoreCase(b.questId));
        return Collections.unmodifiableList(out);
    }

    @Nonnull
    public List<QuestDefinition> getByCategory(@Nonnull QuestDefinition.QuestCategory category) {
        initialize();
        List<QuestDefinition> out = new ArrayList<>();
        for (Map.Entry<String, QuestDefinition> entry : this.byQuestId.entrySet()) {
            if (entry.getValue().category == category) {
                out.add(entry.getValue());
            }
        }
        out.sort((a, b) -> a.questId.compareToIgnoreCase(b.questId));
        return out;
    }

    @Nonnull
    public List<QuestDefinition> getBySource(@Nonnull String sourceType, @Nonnull String sourceId) {
        initialize();
        String normalizedType = QuestDefinition.normalize(sourceType);
        String normalizedId = QuestDefinition.normalize(sourceId);
        List<QuestDefinition> out = new ArrayList<>();
        for (QuestDefinition definition : this.byQuestId.values()) {
            String type = definition.sourceType == null ? "" : QuestDefinition.normalize(definition.sourceType);
            String id = definition.sourceId == null ? "" : QuestDefinition.normalize(definition.sourceId);
            if (type.equals(normalizedType) && id.equals(normalizedId)) {
                out.add(definition);
            }
        }
        out.sort((a, b) -> a.questId.compareToIgnoreCase(b.questId));
        return out;
    }

    private void loadFromResource() {
        Properties p = new Properties();
        try (InputStream in = QuestDefinitionRegistry.class.getClassLoader().getResourceAsStream(DEFINITIONS_RESOURCE)) {
            if (in == null) {
                System.out.println("[QuestDefs] Resource not found: " + DEFINITIONS_RESOURCE);
                return;
            }
            p.load(in);
        } catch (IOException e) {
            System.out.println("[QuestDefs] Failed to load definitions: " + e.getMessage());
            return;
        }

        for (String questId : parseCsv(p.getProperty("quests"))) {
            QuestDefinition parsed = parseQuest(p, questId);
            if (parsed == null || parsed.questId.isBlank()) {
                continue;
            }
            this.byQuestId.put(parsed.questId, parsed);
        }
    }

    @Nullable
    private static QuestDefinition parseQuest(@Nonnull Properties p, @Nonnull String rawQuestId) {
        String questId = QuestDefinition.normalize(rawQuestId);
        if (questId.isBlank()) {
            return null;
        }
        String prefix = "quest." + questId + ".";
        String title = p.getProperty(prefix + "title", questId);
        String summary = p.getProperty(prefix + "summary", "");
        QuestDefinition.QuestCategory category = QuestDefinition.QuestCategory.fromRaw(p.getProperty(prefix + "category"));
        String sourceType = p.getProperty(prefix + "sourceType");
        String sourceId = p.getProperty(prefix + "sourceId");
        String nextQuestId = p.getProperty(prefix + "nextQuestId");
        List<String> rewardSetFlags = parseCsv(p.getProperty(prefix + "rewards.setFlags"));
        List<String> rewardRescueNpcs = parseCsv(p.getProperty(prefix + "rewards.rescueNpcs"));
        List<String> rewardUnlockCrafts = parseCsv(p.getProperty(prefix + "rewards.unlockCrafts"));
        List<String> rewardUnlockTrades = parseCsv(p.getProperty(prefix + "rewards.unlockTrades"));
        List<QuestDefinition.ItemAmount> requiredItems = parseItems(p.getProperty(prefix + "requirements.items"));
        boolean consumeRequiredItemsOnComplete = Boolean.parseBoolean(
                p.getProperty(prefix + "requirements.consumeItemsOnComplete", "true")
        );
        boolean rewardAutoAcceptNext = Boolean.parseBoolean(p.getProperty(prefix + "rewards.autoAcceptNext", "false"));
        return new QuestDefinition(
                questId,
                category,
                title,
                summary,
                sourceType,
                sourceId,
                nextQuestId,
                rewardSetFlags,
                rewardRescueNpcs,
                rewardUnlockCrafts,
                rewardUnlockTrades,
                requiredItems,
                consumeRequiredItemsOnComplete,
                rewardAutoAcceptNext
        );
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = QuestDefinition.normalize(item);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static List<QuestDefinition.ItemAmount> parseItems(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<QuestDefinition.ItemAmount> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String[] parts = token.split(":");
            if (parts.length == 0) {
                continue;
            }
            String itemId = parts[0].trim();
            if (itemId.isBlank()) {
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            out.add(new QuestDefinition.ItemAmount(itemId, amount));
        }
        return List.copyOf(out);
    }
}



