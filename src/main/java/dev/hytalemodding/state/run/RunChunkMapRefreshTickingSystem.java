package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public final class RunChunkMapRefreshTickingSystem extends DelayedSystem<EntityStore> {
    public RunChunkMapRefreshTickingSystem() {
        super(5);
    }

    @Override
    public void delayedTick(float dt, int systemIndex, @NonNullDecl Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        RunChunkSelectionManager manager = RunChunkSelectionManager.get();
        LongSet chunks = manager.pollMapRefreshQueue(world.getName());
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        boolean anyEnabled = false;
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (manager.isEnabled(playerRef)) {
                anyEnabled = true;
                break;
            }
        }
        if (!anyEnabled) {
            return;
        }

        world.execute(() -> {
            if (world.getWorldMapManager() != null) {
                world.getWorldMapManager().clearImagesInChunks(chunks);
            }
            for (PlayerRef playerRef : world.getPlayerRefs()) {
                if (!manager.isEnabled(playerRef)) {
                    continue;
                }
                Player player = world.getEntityStore().getStore().getComponent(playerRef.getReference(), Player.getComponentType());
                if (player != null && player.getWorldMapTracker() != null) {
                    player.getWorldMapTracker().clearChunks(chunks);
                }
            }
        });
    }
}