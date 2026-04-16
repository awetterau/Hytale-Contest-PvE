package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RunDeathHandler extends DeathSystems.OnDeathSystem {
    private final Set<UUID> processingPlayers = ConcurrentHashMap.newKeySet();

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
        World runWorld = store.getExternalData().getWorld();
        if (runWorld == null || !snapshot.runWorldUuid().equals(runWorld.getWorldConfig().getUuid())) {
            return;
        }
        if (!this.processingPlayers.add(deadPlayer.getUuid())) {
            return;
        }

        World hubWorld = Universe.get().getWorld(GameFlowConfigManager.get().getHubWorldName());
        if (hubWorld == null) {
            hubWorld = Universe.get().getDefaultWorld();
        }
        Transform baseSpawn = GameFlowConfigManager.get().getBaseSpawn();
        Transform targetSpawn = baseSpawn != null ? baseSpawn : deadPlayer.getTransform();
        if (hubWorld == null || targetSpawn == null) {
            this.processingPlayers.remove(deadPlayer.getUuid());
            return;
        }

        dev.hytalemodding.npc.economy.NpcInventoryService.clearAll(deadPlayer);
        GameSessionManager.get().markPlayerCategory(deadPlayer.getUuid(), GameSessionManager.PlayerRunCategory.DEAD);

        Teleport teleport = Teleport.createForPlayer(hubWorld, targetSpawn);
        commandBuffer.addComponent(ref, Teleport.getComponentType(), teleport);
        deadPlayer.sendMessage(Message.raw("You died. Penalty applied. You can spectate or stay in lobby."));
        this.processingPlayers.remove(deadPlayer.getUuid());
    }
}

