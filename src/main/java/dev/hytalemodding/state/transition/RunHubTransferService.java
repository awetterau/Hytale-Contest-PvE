package dev.hytalemodding.state.transition;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.npc.NpcArchetype;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.state.run.RescueObjectiveManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public final class RunHubTransferService {
    private static final RunHubTransferService INSTANCE = new RunHubTransferService();

    private RunHubTransferService() {
    }

    @Nonnull
    public static RunHubTransferService get() {
        return INSTANCE;
    }

    public boolean queueRescueForExtraction(@Nullable UUID runWorldId, @Nonnull UUID extractingPlayerId) {
        return RescueObjectiveManager.get().queueRescueForExtraction(runWorldId, extractingPlayerId);
    }

    public void spawnQueuedRescueInBase(@Nonnull PlayerRef playerRef, @Nonnull World hubWorld, @Nonnull Transform baseSpawn) {
        RescueObjectiveManager.get().spawnQueuedRescueInBase(hubWorld, baseSpawn).whenComplete((result, spawnErr) -> {
            if (spawnErr != null) {
                String reason = spawnErr.getMessage();
                playerRef.sendMessage(Message.raw("Rescue transfer failed: " + reason));
                return;
            }
            if (result == null || result.npcKey() == null || result.npcKey().isBlank()) {
                playerRef.sendMessage(Message.raw("No queued rescue to transfer."));
                return;
            }
            if (!result.spawned()) {
                if (isNpcAlreadyPresentInHub(result.npcKey(), hubWorld)) {
                    RescueObjectiveManager.get().setNpcRescued(result.npcKey(), true);
                    playerRef.sendMessage(Message.raw(result.npcKey() + " rescued and added to base."));
                    return;
                }
                String reason = result.reason() == null ? "spawn returned false" : result.reason();
                playerRef.sendMessage(Message.raw("Rescue transfer failed for " + result.npcKey() + ": " + reason));
                return;
            }
            RescueObjectiveManager.get().setNpcRescued(result.npcKey(), true);
            playerRef.sendMessage(Message.raw(result.npcKey() + " rescued and added to base."));
        });
    }

    private static boolean isNpcAlreadyPresentInHub(@Nonnull String npcKey, @Nonnull World hubWorld) {
        NpcArchetype archetype = NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            return false;
        }

        Store<EntityStore> store = hubWorld.getEntityStore().getStore();
        final boolean[] found = {false};
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            if (found[0]) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || npc.isDespawning() || !archetype.hubRole.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }
}

