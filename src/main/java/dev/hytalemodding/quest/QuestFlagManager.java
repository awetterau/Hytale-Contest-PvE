package dev.hytalemodding.quest;

import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class QuestFlagManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "quest-flags.properties";
    private static final QuestFlagManager INSTANCE = new QuestFlagManager();

    private final Set<String> flags = new HashSet<>();
    private boolean loaded;

    private QuestFlagManager() {
    }

    @Nonnull
    public static QuestFlagManager get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        ensureLoaded();
    }

    public synchronized boolean hasFlag(@Nonnull String flag) {
        ensureLoaded();
        return this.flags.contains(normalize(flag));
    }

    public synchronized void setFlag(@Nonnull String flag) {
        ensureLoaded();
        String normalized = normalize(flag);
        if (normalized.isBlank()) {
            return;
        }
        if (this.flags.add(normalized)) {
            saveQuietly();
        }
    }

    @Nonnull
    public synchronized Set<String> getFlags() {
        ensureLoaded();
        return Set.copyOf(this.flags);
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
            System.out.println("[QuestFlags] Failed to load: " + e.getMessage());
            return;
        }
        String raw = p.getProperty("flags", "");
        if (raw.isBlank()) {
            return;
        }
        for (String item : raw.split(",")) {
            String value = normalize(item);
            if (!value.isBlank()) {
                this.flags.add(value);
            }
        }
    }

    private synchronized void saveQuietly() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("flags", String.join(",", this.flags));
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(writer, "Quest flags");
            }
        } catch (IOException e) {
            System.out.println("[QuestFlags] Failed to save: " + e.getMessage());
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
}



