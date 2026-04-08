package dev.hytalemodding.state.run;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.math.util.ChunkUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RunChunkSelectionManager {
    private static final RunChunkSelectionManager INSTANCE = new RunChunkSelectionManager();
    private final Map<String, LinkedHashSet<ChunkPosKey>> chunksByWorld = new ConcurrentHashMap<>();
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, LongSet> worldMapRefreshQueue = new ConcurrentHashMap<>();

    private RunChunkSelectionManager() {
    }

    @Nonnull
    public static RunChunkSelectionManager get() {
        return INSTANCE;
    }

    public boolean enableFor(@Nonnull PlayerRef playerRef) {
        return this.enabledPlayers.add(playerRef.getUuid());
    }

    public boolean disableFor(@Nonnull PlayerRef playerRef) {
        return this.enabledPlayers.remove(playerRef.getUuid());
    }

    public boolean isEnabled(@Nonnull PlayerRef playerRef) {
        return this.enabledPlayers.contains(playerRef.getUuid());
    }

    public boolean isEnabled(@Nonnull UUID playerId) {
        return this.enabledPlayers.contains(playerId);
    }

    public void clearPlayer(@Nonnull UUID playerId) {
        this.enabledPlayers.remove(playerId);
    }

    @Nonnull
    public LinkedHashSet<ChunkPosKey> getSelectedChunks(@Nonnull String worldName) {
        String worldKey = normalizeWorldKey(worldName);
        return this.chunksByWorld.computeIfAbsent(worldKey, RunChunkSelectionConfigManager::load);
    }

    public int count(@Nonnull String worldName) {
        return getSelectedChunks(worldName).size();
    }

    public boolean mark(@Nonnull String worldName, int chunkX, int chunkZ) {
        String worldKey = normalizeWorldKey(worldName);
        LinkedHashSet<ChunkPosKey> chunks = getSelectedChunks(worldKey);
        boolean added = chunks.add(new ChunkPosKey(chunkX, chunkZ));
        if (added) {
            RunChunkSelectionConfigManager.save(worldKey, chunks);
        }
        return added;
    }

    public boolean unmark(@Nonnull String worldName, int chunkX, int chunkZ) {
        String worldKey = normalizeWorldKey(worldName);
        LinkedHashSet<ChunkPosKey> chunks = getSelectedChunks(worldKey);
        boolean removed = chunks.remove(new ChunkPosKey(chunkX, chunkZ));
        if (removed) {
            RunChunkSelectionConfigManager.save(worldKey, chunks);
        }
        return removed;
    }

    public boolean toggle(@Nonnull String worldName, int chunkX, int chunkZ) {
        if (unmark(worldName, chunkX, chunkZ)) {
            return false;
        }
        mark(worldName, chunkX, chunkZ);
        return true;
    }

    public void queueMapRefresh(@Nonnull String worldName, int chunkX, int chunkZ) {
        String worldKey = normalizeWorldKey(worldName);
        LongSet queued = this.worldMapRefreshQueue.computeIfAbsent(worldKey, ignored -> new LongOpenHashSet());
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dz = {-1, -1, -1, 0, 0, 1, 1, 1};
        for (int i = 0; i < dx.length; i++) {
            queued.add(ChunkUtil.indexChunk(chunkX + dx[i], chunkZ + dz[i]));
        }
        queued.add(ChunkUtil.indexChunk(chunkX, chunkZ));
    }

    public void queueMapRefreshForChunks(@Nonnull String worldName, @Nonnull Set<ChunkPosKey> chunks) {
        for (ChunkPosKey chunk : chunks) {
            queueMapRefresh(worldName, chunk.x(), chunk.z());
        }
    }

    @Nullable
    public LongSet pollMapRefreshQueue(@Nonnull String worldName) {
        return this.worldMapRefreshQueue.remove(normalizeWorldKey(worldName));
    }

    @Nullable
    public static String getPlayerWorldName(@Nonnull PlayerRef playerRef) {
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) {
            return null;
        }
        World world = Universe.get().getWorld(worldUuid);
        return world == null ? null : world.getName();
    }

    public record ChunkPosKey(int x, int z) {
    }

    @Nonnull
    private static String normalizeWorldKey(@Nonnull String worldName) {
        String trimmed = worldName.trim();
        if (trimmed.isEmpty()) {
            return "default";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}