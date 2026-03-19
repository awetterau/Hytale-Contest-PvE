package dev.hytalemodding.loot;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

public class LifeEssenceChestBreakCleanupSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
    private final Set<LifeEssenceContainerKey> processedChests;

    public LifeEssenceChestBreakCleanupSystem(@Nonnull Set<LifeEssenceContainerKey> processedChests) {
        super(BreakBlockEvent.class);
        this.processedChests = processedChests;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event
    ) {
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player == null) return;

        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getWorldUuid() == null) return;

        try {
            var isCancelledMethod = event.getClass().getMethod("isCancelled");
            Object cancelled = isCancelledMethod.invoke(event);
            if (cancelled instanceof Boolean && (Boolean) cancelled) return;
        } catch (Exception ignored) {
            // If cancellation API is unavailable, proceed.
        }

        Vector3i pos = event.getTargetBlock();
        LifeEssenceContainerKey key = LifeEssenceContainerKey.of(playerRef.getWorldUuid(), pos);
        processedChests.remove(key);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
