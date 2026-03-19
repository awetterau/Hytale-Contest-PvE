package dev.hytalemodding.state.transition;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
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
                System.out.println("[GameDoorDebug] rescue transfer failed: " + reason);
                return;
            }
            if (result == null || result.npcKey() == null || result.npcKey().isBlank()) {
                playerRef.sendMessage(Message.raw("No queued rescue to transfer."));
                return;
            }
            if (!result.spawned()) {
                String reason = result.reason() == null ? "spawn returned false" : result.reason();
                playerRef.sendMessage(Message.raw("Rescue transfer failed for " + result.npcKey() + ": " + reason));
                System.out.println("[GameDoorDebug] rescue transfer failed npc=" + result.npcKey() + ": " + reason);
                return;
            }
            RescueObjectiveManager.get().setNpcRescued(result.npcKey(), true);
            playerRef.sendMessage(Message.raw(result.npcKey() + " rescued and added to base."));
            System.out.println("[GameDoorDebug] rescue transfer success npc=" + result.npcKey());
        });
    }
}

