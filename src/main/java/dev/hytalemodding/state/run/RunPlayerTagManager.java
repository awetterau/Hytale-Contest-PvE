package dev.hytalemodding.state.run;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RunPlayerTagManager {
    public static final String RESET_TAG = "Reset";
    public static final String EXTRACTED_TAG = "Extracted";
    public static final String DEAD_TAG = "Dead";
    public static final String DISCONNECTED_TAG = "Disconnected";
    public static final String SPECTATING_TAG = "Spectating";

    private static final ConcurrentHashMap<UUID, String> TAG_BY_PLAYER = new ConcurrentHashMap<>();

    private RunPlayerTagManager() {
    }

    public static void addTag(@Nonnull UUID playerId, @Nonnull String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return;
        }
        TAG_BY_PLAYER.put(playerId, normalized);
    }

    public static void removeTag(@Nonnull UUID playerId, @Nonnull String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return;
        }
        String existing = TAG_BY_PLAYER.get(playerId);
        if (existing == null) {
            return;
        }
        if (existing.equals(normalized)) {
            TAG_BY_PLAYER.remove(playerId);
        }
    }

    public static boolean hasTag(@Nonnull UUID playerId, @Nonnull String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return false;
        }
        String existing = TAG_BY_PLAYER.get(playerId);
        return existing != null && existing.equals(normalized);
    }

    @Nullable
    public static String snapshot(@Nonnull UUID playerId) {
        return TAG_BY_PLAYER.get(playerId);
    }

    private static String normalize(@Nonnull String tag) {
        return tag.trim();
    }
}