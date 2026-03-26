package dev.hytalemodding.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestChestConfigManager {
    private static final String RESOURCE_PATH = "Common/QuestData/quest-chests.properties";
    private static final QuestChestConfigManager INSTANCE = new QuestChestConfigManager();

    private final ConcurrentHashMap<String, QuestChestDefinition> byChestId = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private QuestChestConfigManager() {
    }

    @Nonnull
    public static QuestChestConfigManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadFromResource();
    }

    @Nullable
    public QuestChestDefinition getForTemplateWorld(@Nonnull String templateWorldName) {
        initialize();
        String normalizedWorld = normalize(templateWorldName);
        for (QuestChestDefinition definition : this.byChestId.values()) {
            if (definition.templateWorldName.equals(normalizedWorld)) {
                return definition;
            }
        }
        return null;
    }

    @Nullable
    public QuestChestDefinition getByChestId(@Nonnull String chestId) {
        initialize();
        return this.byChestId.get(normalize(chestId));
    }

    private void loadFromResource() {
        this.byChestId.clear();
        Properties p = new Properties();
        try (InputStream in = QuestChestConfigManager.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return;
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return;
        }

        for (String chestId : parseCsv(p.getProperty("questChests"))) {
            QuestChestDefinition definition = parseDefinition(p, chestId);
            if (definition != null) {
                this.byChestId.put(definition.chestId, definition);
            }
        }
    }

    @Nullable
    private static QuestChestDefinition parseDefinition(@Nonnull Properties p, @Nonnull String rawChestId) {
        String chestId = normalize(rawChestId);
        if (chestId.isBlank()) {
            return null;
        }
        String prefix = "questChest." + chestId + ".";
        String templateWorld = normalize(p.getProperty(prefix + "templateWorld"));
        String questId = normalize(p.getProperty(prefix + "questId"));
        String blockId = trimToEmpty(p.getProperty(prefix + "blockId"));
        String rewardItemId = trimToEmpty(p.getProperty(prefix + "rewardItemId"));
        int rewardAmount = parseInt(p.getProperty(prefix + "rewardAmount"), 1);
        String fallbackItemId = trimToEmpty(p.getProperty(prefix + "fallbackItemId"));
        int fallbackMinAmount = parseInt(p.getProperty(prefix + "fallbackMinAmount"), 3);
        int fallbackMaxAmount = parseInt(p.getProperty(prefix + "fallbackMaxAmount"), 6);
        String markerId = trimToEmpty(p.getProperty(prefix + "markerId"));
        String markerName = trimToEmpty(p.getProperty(prefix + "markerName"));
        String markerIcon = trimToEmpty(p.getProperty(prefix + "markerIcon"));
        String markerColor = trimToEmpty(p.getProperty(prefix + "markerColor"));
        if (templateWorld.isBlank() || questId.isBlank() || blockId.isBlank() || rewardItemId.isBlank() || fallbackItemId.isBlank()) {
            return null;
        }
        return new QuestChestDefinition(
                chestId,
                templateWorld,
                questId,
                blockId,
                rewardItemId,
                Math.max(1, rewardAmount),
                fallbackItemId,
                Math.max(1, fallbackMinAmount),
                Math.max(Math.max(1, fallbackMinAmount), fallbackMaxAmount),
                markerId.isBlank() ? "quest-chest-" + chestId : markerId,
                markerName.isBlank() ? chestId : markerName,
                markerIcon.isBlank() ? "UserF.png" : markerIcon,
                markerColor.isBlank() ? "#ff9b3d" : markerColor
        );
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String value = normalize(token);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String trimToEmpty(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
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

    public record QuestChestDefinition(
            @Nonnull String chestId,
            @Nonnull String templateWorldName,
            @Nonnull String questId,
            @Nonnull String blockId,
            @Nonnull String rewardItemId,
            int rewardAmount,
            @Nonnull String fallbackItemId,
            int fallbackMinAmount,
            int fallbackMaxAmount,
            @Nonnull String markerId,
            @Nonnull String markerName,
            @Nonnull String markerIcon,
            @Nonnull String markerColor
    ) {
    }
}
