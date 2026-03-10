package dev.hytalemodding.game;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DevDebugManager {
    private static final DevDebugManager INSTANCE = new DevDebugManager();

    private final Set<UUID> hudEnabledPlayers = ConcurrentHashMap.newKeySet();

    private DevDebugManager() {
    }

    @Nonnull
    public static DevDebugManager get() {
        return INSTANCE;
    }

    public boolean isHudEnabled(@Nonnull UUID playerId) {
        return this.hudEnabledPlayers.contains(playerId);
    }

    public boolean setHudEnabled(@Nonnull UUID playerId, boolean enabled) {
        if (enabled) {
            return this.hudEnabledPlayers.add(playerId);
        }
        return this.hudEnabledPlayers.remove(playerId);
    }

    public boolean toggleHud(@Nonnull UUID playerId) {
        if (this.hudEnabledPlayers.contains(playerId)) {
            this.hudEnabledPlayers.remove(playerId);
            return false;
        }
        this.hudEnabledPlayers.add(playerId);
        return true;
    }
}
