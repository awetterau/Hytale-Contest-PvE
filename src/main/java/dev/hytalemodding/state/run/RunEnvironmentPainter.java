package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

public final class RunEnvironmentPainter {
    private static final int HEIGHT_BLOCKS = 5;
    private static final String CRIMSON_ENVIRONMENT_ID = "Env_Crimson";
    private static final String RUN_WORLD_ENVIRONMENT_ID = "Env_Blightfall_Run";
    private static volatile boolean crimsonZoneEnvironmentEnabled = true;
    @Nullable
    private static volatile Integer cachedCrimsonEnvironmentIndex;
    @Nullable
    private static volatile Integer cachedRunDefaultEnvironmentIndex;

    private RunEnvironmentPainter() {
    }

    public static void paintColumnForRunBlock(@Nonnull World world, int x, int y, int z) {
        if (!crimsonZoneEnvironmentEnabled) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();
        if (!GameSessionManager.get().isActiveRunWorldCandidate(worldId, world.getName())) {
            return;
        }

        int environmentIndex = resolveEnvironmentIndex(CRIMSON_ENVIRONMENT_ID, true);
        if (environmentIndex == Integer.MIN_VALUE) {
            return;
        }

        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(chunkIndex);
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }

        Store<ChunkStore> store = chunkRef.getStore();
        if (store == null) {
            return;
        }

        EnvironmentChunk environmentChunk = store.getComponent(chunkRef, EnvironmentChunk.getComponentType());
        if (environmentChunk == null) {
            environmentChunk = new EnvironmentChunk(environmentIndex);
        }

        int localX = ChunkUtil.localCoordinate(x);
        int localZ = ChunkUtil.localCoordinate(z);
        int minY = ChunkUtil.MIN_Y;
        int maxY = ChunkUtil.MIN_Y + ChunkUtil.HEIGHT - 1;
        boolean changed = false;

        for (int yy = y; yy <= y + HEIGHT_BLOCKS; yy++) {
            if (yy < minY || yy > maxY) {
                continue;
            }
            changed |= environmentChunk.set(localX, yy, localZ, environmentIndex);
        }

        if (changed) {
            store.putComponent(chunkRef, EnvironmentChunk.getComponentType(), environmentChunk);
        }
    }

    public static void paintRunDefaultEnvironmentOnChunk(@Nonnull World world, @Nonnull Ref<ChunkStore> chunkRef) {
        UUID worldId = world.getWorldConfig().getUuid();
        if (!GameSessionManager.get().isActiveRunWorldCandidate(worldId, world.getName())) {
            return;
        }
        if (!chunkRef.isValid()) {
            return;
        }
        int environmentIndex = resolveEnvironmentIndex(RUN_WORLD_ENVIRONMENT_ID, false);
        if (environmentIndex == Integer.MIN_VALUE) {
            return;
        }
        Store<ChunkStore> store = chunkRef.getStore();
        if (store == null) {
            return;
        }
        EnvironmentChunk environmentChunk = store.getComponent(chunkRef, EnvironmentChunk.getComponentType());
        if (environmentChunk == null) {
            environmentChunk = new EnvironmentChunk(environmentIndex);
        }
        for (int localX = 0; localX < ChunkUtil.SIZE; localX++) {
            for (int localZ = 0; localZ < ChunkUtil.SIZE; localZ++) {
                environmentChunk.setColumn(localX, localZ, environmentIndex);
            }
        }
        store.putComponent(chunkRef, EnvironmentChunk.getComponentType(), environmentChunk);
    }

    public static void setCrimsonZoneEnvironmentEnabled(boolean enabled) {
        crimsonZoneEnvironmentEnabled = enabled;
    }

    public static boolean isCrimsonZoneEnvironmentEnabled() {
        return crimsonZoneEnvironmentEnabled;
    }

    private static int resolveEnvironmentIndex(@Nonnull String environmentId, boolean crimsonLookup) {
        Integer cached = crimsonLookup ? cachedCrimsonEnvironmentIndex : cachedRunDefaultEnvironmentIndex;
        if (cached != null) {
            return cached;
        }
        synchronized (RunEnvironmentPainter.class) {
            cached = crimsonLookup ? cachedCrimsonEnvironmentIndex : cachedRunDefaultEnvironmentIndex;
            if (cached != null) {
                return cached;
            }
            Map<?, ?> environmentMap = Environment.getAssetMap().getAssetMap();
            if (environmentMap == null || environmentMap.isEmpty()) {
                return Integer.MIN_VALUE;
            }
            int index = Environment.getAssetMap().getIndex(environmentId);
            if (index == Integer.MIN_VALUE) {
                for (Object key : environmentMap.keySet()) {
                    if (key == null) {
                        continue;
                    }
                    String keyText = String.valueOf(key);
                    if (!matchesEnvironmentKey(keyText, environmentId)) {
                        continue;
                    }
                    index = Environment.getAssetMap().getIndex(keyText);
                    if (index != Integer.MIN_VALUE) {
                        break;
                    }
                }
            }
            if (index == Integer.MIN_VALUE) {
                System.out.println("[RunEnvironmentPainter] Environment not found: " + environmentId + ".");
                return Integer.MIN_VALUE;
            }
            if (crimsonLookup) {
                cachedCrimsonEnvironmentIndex = index;
            } else {
                cachedRunDefaultEnvironmentIndex = index;
            }
            return index;
        }
    }

    private static boolean matchesEnvironmentKey(@Nonnull String keyText, @Nonnull String expectedId) {
        if (keyText.equalsIgnoreCase(expectedId)) {
            return true;
        }
        String lowerKey = keyText.toLowerCase();
        String lowerExpected = expectedId.toLowerCase();
        if (lowerKey.endsWith("/" + lowerExpected) || lowerKey.endsWith("." + lowerExpected)) {
            return true;
        }
        String normalizedKey = lowerKey.replaceAll("[^a-z0-9]+", "");
        String normalizedExpected = lowerExpected.replaceAll("[^a-z0-9]+", "");
        return normalizedKey.endsWith(normalizedExpected);
    }
}