package dev.hytalemodding.stash;

import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStashManager {
    private static final PlayerStashManager INSTANCE = new PlayerStashManager();

    private final ConcurrentHashMap<UUID, ItemContainer> stashes = new ConcurrentHashMap<>();

    private PlayerStashManager() {
    }

    @Nonnull
    public static PlayerStashManager get() {
        return INSTANCE;
    }

    @Nonnull
    public ItemContainer getStash(@Nonnull UUID uuid) {
        return this.stashes.computeIfAbsent(uuid, LocalStashStore::load);
    }

    public void load(@Nonnull UUID uuid) {
        getStash(uuid);
    }

    public void save(@Nonnull UUID uuid) {
        ItemContainer stash = this.stashes.get(uuid);
        if (stash != null) {
            LocalStashStore.save(uuid, stash);
        }
    }

    public void unload(@Nonnull UUID uuid) {
        save(uuid);
        this.stashes.remove(uuid);
    }

    public void saveAll() {
        for (UUID uuid : this.stashes.keySet()) {
            save(uuid);
        }
    }

    @Nonnull
    public Set<UUID> loadedPlayers() {
        return this.stashes.keySet();
    }
}
