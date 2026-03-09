package dev.hytalemodding.game;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class BaseHousingSystem extends TickingSystem<EntityStore> {
    private long lastCheckAtMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        if (!GameFlowConfigManager.get().getHubWorldName().equalsIgnoreCase(world.getName())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastCheckAtMs < 2500L) {
            return;
        }
        this.lastCheckAtMs = now;
        BaseHousingManager.get().ensureAssignmentsInWorld(world);
    }
}
