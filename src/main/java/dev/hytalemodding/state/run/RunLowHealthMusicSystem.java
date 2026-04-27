package dev.hytalemodding.state.run;

import com.hypixel.hytale.builtin.ambience.resources.AmbienceResource;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RunLowHealthMusicSystem extends TickingSystem<EntityStore> {
    private static final String ARENA_MUSIC_ID = "AmbFX_Blightfall_The_Crimson_Witch";
    private static final double ARENA_TRIGGER_RANGE_BLOCKS = 22.0d;
    private static final long CHECK_INTERVAL_MS = 250L;
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final Query<EntityStore> PLAYER_WITH_TRANSFORM = Query.and(PLAYER, TRANSFORM);

    private final ConcurrentHashMap<UUID, Boolean> forcedByWorld = new ConcurrentHashMap<>();
    private long lastCheckAtMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastCheckAtMs < CHECK_INTERVAL_MS) {
            return;
        }
        this.lastCheckAtMs = now;

        UUID worldId = store.getExternalData().getWorld().getWorldConfig().getUuid();
        if (worldId == null) {
            return;
        }

        List<Vector3i> arenaActivationPositions = InfectionCoreRegistry.snapshotArenaActivationPositions(worldId);
        boolean shouldForceMusic = isAnyPlayerInsideArenaRange(store, arenaActivationPositions);
        boolean wasForced = this.forcedByWorld.getOrDefault(worldId, false);
        if (shouldForceMusic == wasForced) {
            return;
        }

        AmbienceResource ambienceResource = store.getResource(AmbienceResource.getResourceType());
        if (ambienceResource == null) {
            return;
        }

        if (shouldForceMusic) {
            ambienceResource.setForcedMusicAmbience(ARENA_MUSIC_ID);
            this.forcedByWorld.put(worldId, true);
            return;
        }

        ambienceResource.setForcedMusicAmbience(null);
        this.forcedByWorld.put(worldId, false);
    }

    private static boolean isAnyPlayerInsideArenaRange(@Nonnull Store<EntityStore> store, @Nonnull List<Vector3i> arenaPositions) {
        if (arenaPositions.isEmpty()) {
            return false;
        }
        double rangeSquared = ARENA_TRIGGER_RANGE_BLOCKS * ARENA_TRIGGER_RANGE_BLOCKS;
        final boolean[] out = {false};
        store.forEachChunk(PLAYER_WITH_TRANSFORM, (chunk, ignored) -> {
            if (out[0]) {
                return;
            }
            for (int i = 0; i < chunk.size(); i++) {
                Player player = chunk.getComponent(i, PLAYER);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                if (player == null || transform == null) {
                    continue;
                }
                Vector3d playerPos = transform.getPosition();
                for (Vector3i arenaPos : arenaPositions) {
                    double dx = playerPos.getX() - (arenaPos.x + 0.5d);
                    double dz = playerPos.getZ() - (arenaPos.z + 0.5d);
                    if ((dx * dx) + (dz * dz) <= rangeSquared) {
                        out[0] = true;
                        return;
                    }
                }
            }
        });
        return out[0];
    }
}