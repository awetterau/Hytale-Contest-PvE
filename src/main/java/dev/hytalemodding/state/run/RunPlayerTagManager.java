package dev.hytalemodding.state.run;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RunPlayerTagManager {
    public static final String RESET_TAG = "Reset";

    private static final ConcurrentHashMap<UUID, Set<String>> TAGS_BY_PLAYER = new ConcurrentHashMap<>();

    private RunPlayerTagManager() {
    }

    public static void addTag(@Nonnull UUID playerId, @Nonnull String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return;
        }
        TAGS_BY_PLAYER.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(normalized);
    }

    public static void removeTag(@Nonnull UUID playerId, @Nonnull String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return;
        }
        Set<String> tags = TAGS_BY_PLAYER.get(playerId);
        if (tags == null) {
            return;
        }
        tags.remove(normalized);
        if (tags.isEmpty()) {
            TAGS_BY_PLAYER.remove(playerId);
        }
    }

    public static boolean hasTag(@Nonnull UUID playerId, @Nonnull String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) {
            return false;
        }
        Set<String> tags = TAGS_BY_PLAYER.get(playerId);
        return tags != null && tags.contains(normalized);
    }

    @Nonnull
    public static Set<String> snapshot(@Nonnull UUID playerId) {
        Set<String> tags = TAGS_BY_PLAYER.get(playerId);
        if (tags == null || tags.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(Set.copyOf(tags));
    }

    private static String normalize(@Nonnull String tag) {
        return tag.trim();
    }
}