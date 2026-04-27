package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.ui.dev.BlightfallMainPage;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RunControlWorldSafetySystem extends TickingSystem<EntityStore> {
    private static final long TICK_INTERVAL_MS = 2000L;
    private static final double DEFAULT_WORLD_MIN_SAFE_Y = 95.0d;
    private static final long GAME_WORLD_REDIRECT_DELAY_MS = 9000L;
    private static final ConcurrentHashMap<UUID, Long> GAME_WORLD_REDIRECT_PENDING_SINCE = new ConcurrentHashMap<>();
    private static long nextTickAt = 0L;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now < nextTickAt) {
            return;
        }
        nextTickAt = now + TICK_INTERVAL_MS;

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        World defaultWorld = universe.getWorld("default");
        if (defaultWorld == null) {
            defaultWorld = universe.getDefaultWorld();
        }
        if (defaultWorld == null) {
            return;
        }

        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || playerRef.getReference() == null || !playerRef.getReference().isValid()) {
                continue;
            }
            World playerWorld = playerRef.getWorldUuid() == null ? null : universe.getWorld(playerRef.getWorldUuid());
            if (playerWorld == null) {
                continue;
            }

            if (BlightfallMainPage.isGameWorldRedirectEnabled()
                    && "game".equalsIgnoreCase(playerWorld.getName())
                    && !playerWorld.getWorldConfig().getUuid().equals(defaultWorld.getWorldConfig().getUuid())) {
                UUID playerId = playerRef.getUuid();
                long pendingSince = GAME_WORLD_REDIRECT_PENDING_SINCE.getOrDefault(playerId, now);
                GAME_WORLD_REDIRECT_PENDING_SINCE.putIfAbsent(playerId, now);
                if ((now - pendingSince) >= GAME_WORLD_REDIRECT_DELAY_MS) {
                    GAME_WORLD_REDIRECT_PENDING_SINCE.remove(playerId);
                    teleportPlayer(playerRef, defaultWorld, resolveWorldSpawn(playerRef, defaultWorld));
                }
                continue;
            }
            GAME_WORLD_REDIRECT_PENDING_SINCE.remove(playerRef.getUuid());

            if (BlightfallMainPage.isDefaultWorldHeightGuardEnabled()
                    && isDefaultWorldName(playerWorld.getName())
                    && playerRef.getTransform() != null
                    && playerRef.getTransform().getPosition() != null
                    && playerRef.getTransform().getPosition().getY() < DEFAULT_WORLD_MIN_SAFE_Y) {
                teleportPlayer(playerRef, defaultWorld, resolveWorldSpawn(playerRef, defaultWorld));
            }
        }
    }

    private static boolean isDefaultWorldName(String worldName) {
        return "default".equalsIgnoreCase(worldName) || "deafult".equalsIgnoreCase(worldName);
    }

    @Nonnull
    private static Transform resolveWorldSpawn(@Nonnull PlayerRef playerRef, @Nonnull World world) {
        Transform configured = GameFlowConfigManager.get().getBaseSpawn();
        if (configured != null) {
            return new Transform(new Vector3d(configured.getPosition()), new Vector3f(configured.getRotation()));
        }
        if (playerRef.getTransform() != null) {
            Vector3d fallback = new Vector3d(playerRef.getTransform().getPosition());
            fallback.setY(Math.max(64.0d, fallback.getY()));
            return new Transform(fallback, new Vector3f(playerRef.getTransform().getRotation()));
        }
        return new Transform(new Vector3d(0.0d, 64.0d, 0.0d), new Vector3f(0.0f, 0.0f, 0.0f));
    }

    private static void teleportPlayer(@Nonnull PlayerRef playerRef, @Nonnull World targetWorld, @Nonnull Transform target) {
        var initialRef = playerRef.getReference();
        if (initialRef == null || !initialRef.isValid() || initialRef.getStore() == null) {
            return;
        }
        World sourceWorld = initialRef.getStore().getExternalData().getWorld();
        if (sourceWorld == null) {
            return;
        }

        sourceWorld.execute(() -> {
            var ref = playerRef.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() == null) {
                return;
            }
            if (playerRef.getWorldUuid() == null
                    || !sourceWorld.getWorldConfig().getUuid().equals(playerRef.getWorldUuid())) {
                return;
            }
            ref.getStore().putComponent(
                    ref,
                    Teleport.getComponentType(),
                    Teleport.createForPlayer(targetWorld, target)
            );
        });
    }
}
