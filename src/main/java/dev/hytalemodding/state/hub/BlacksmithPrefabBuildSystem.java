package dev.hytalemodding.state.hub;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;

public final class BlacksmithPrefabBuildSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        if (!GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(world.getName())) {
            return;
        }
        BlacksmithPrefabController.processBuildTick(world, store);
    }
}
