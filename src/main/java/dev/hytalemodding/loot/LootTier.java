package dev.hytalemodding.loot;

import dev.hytalemodding.state.run.GameSessionManager;

import javax.annotation.Nonnull;

public enum LootTier {
    T1,
    T2,
    T3;

    @Nonnull
    public static LootTier forActiveRun() {
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.startedAtEpochMillis() <= 0L) {
            return T1;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - snapshot.startedAtEpochMillis()) / 1000L);
        LootTableRegistry registry = LootTableRegistry.get();
        if (elapsedSeconds < registry.t1UntilSecond()) {
            return T1;
        }
        if (elapsedSeconds < registry.t2UntilSecond()) {
            return T2;
        }
        return T3;
    }

    @Nonnull
    public String configKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
