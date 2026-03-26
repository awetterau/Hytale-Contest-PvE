package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GameDoorTriggerSystem extends com.hypixel.hytale.component.system.tick.TickingSystem<EntityStore> {
    private static final double MATCH_DISTANCE_SQ = 0.36d;
    private long lastCheckAtMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        if (!hasActivePlayerInWorld(world) || world.getWorldConfig() == null || world.getWorldConfig().getUuid() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - this.lastCheckAtMs < 2500L) {
            return;
        }
        this.lastCheckAtMs = now;

        UUID worldId = world.getWorldConfig().getUuid();
        List<Vector3i> doorPositions = GameDoorTriggerRegistry.snapshot(worldId);
        if (doorPositions.isEmpty()) {
            return;
        }

        List<Ref<EntityStore>> existingTriggers = findExistingTriggers(store);
        for (Vector3i doorPos : doorPositions) {
            Transform desiredTransform = toTriggerTransform(doorPos);
            if (findMatchingTrigger(store, existingTriggers, desiredTransform) != null) {
                continue;
            }
            GameDoorTriggerSpawner.spawn(world, desiredTransform);
        }
    }

    private static boolean hasActivePlayerInWorld(@Nonnull World world) {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || !playerRef.isValid() || playerRef.getWorldUuid() == null) {
                continue;
            }
            World playerWorld = Universe.get().getWorld(playerRef.getWorldUuid());
            if (playerWorld != null && playerWorld.getName().equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static List<Ref<EntityStore>> findExistingTriggers(@Nonnull Store<EntityStore> store) {
        List<Ref<EntityStore>> found = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found.add(ref);
                }
            }
        });
        return found;
    }

    @Nullable
    private static Ref<EntityStore> findMatchingTrigger(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<Ref<EntityStore>> existingTriggers,
            @Nonnull Transform desiredTransform
    ) {
        Vector3d desiredPos = desiredTransform.getPosition();
        for (Ref<EntityStore> ref : existingTriggers) {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                continue;
            }
            Vector3d pos = transform.getPosition();
            double dx = pos.getX() - desiredPos.getX();
            double dy = pos.getY() - desiredPos.getY();
            double dz = pos.getZ() - desiredPos.getZ();
            if ((dx * dx) + (dy * dy) + (dz * dz) <= MATCH_DISTANCE_SQ) {
                return ref;
            }
        }
        return null;
    }

    @Nonnull
    private static Transform toTriggerTransform(@Nonnull Vector3i doorPos) {
        return new Transform(
                new Vector3d(doorPos.x + 0.5d, doorPos.y, doorPos.z + 0.5d),
                new Vector3f(0.0f, 0.0f, 0.0f)
        );
    }
}
