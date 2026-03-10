package dev.hytalemodding.game;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RunDeathHandler extends DeathSystems.OnDeathSystem {
    private final Set<UUID> processingRuns = ConcurrentHashMap.newKeySet();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent deathComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef deadPlayer = store.getComponent(ref, PlayerRef.getComponentType());
        if (deadPlayer == null) {
            return;
        }

        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            return;
        }
        if (!snapshot.starterPlayerId().equals(deadPlayer.getUuid())) {
            return;
        }

        World runWorld = store.getExternalData().getWorld();
        if (runWorld == null || !snapshot.runWorldUuid().equals(runWorld.getWorldConfig().getUuid())) {
            return;
        }
        if (!this.processingRuns.add(snapshot.runWorldUuid())) {
            return;
        }

        World hubWorld = Universe.get().getWorld(GameFlowConfigManager.get().getHubWorldName());
        if (hubWorld == null) {
            hubWorld = Universe.get().getDefaultWorld();
        }
        Transform baseSpawn = GameFlowConfigManager.get().getBaseSpawn();
        Transform targetSpawn = baseSpawn != null ? baseSpawn : deadPlayer.getTransform();
        if (hubWorld == null || targetSpawn == null) {
            this.processingRuns.remove(snapshot.runWorldUuid());
            return;
        }

        RescueObjectiveManager.get().resetRuntimeStatePreserveRescued();
        GameSessionManager.get().endSessionAndWipeInventory(targetSpawn, hubWorld).whenComplete((result, throwable) -> {
            this.processingRuns.remove(snapshot.runWorldUuid());
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                deadPlayer.sendMessage(Message.raw("Run failed on death, but return failed: " + reason));
                return;
            }
            deadPlayer.sendMessage(Message.raw("You died. Returned to hub and inventory wiped."));
        });
    }
}
