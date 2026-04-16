package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public final class RunPinnedChunkKeepAliveSystem extends DelayedSystem<EntityStore> {
    public RunPinnedChunkKeepAliveSystem() {
        super(4);
    }

    @Override
    public void delayedTick(float dt, int systemIndex, @NonNullDecl Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        GameSessionManager.maintainPinnedRunChunks(world);
    }
}