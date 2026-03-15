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
        RescueObjectiveManager.get().spawnQueuedRescueInBase(hubWorld, baseSpawn).whenComplete((spawned, spawnErr) -> {
            // Preserve rescued progression after successful extraction regardless of spawn success.
            RescueObjectiveManager.get().setBlacksmithRescued(true);
            if (spawnErr != null || Boolean.FALSE.equals(spawned)) {
                String reason = spawnErr != null ? spawnErr.getMessage() : "spawn returned false";
                playerRef.sendMessage(Message.raw("Blacksmith marked rescued, but base transfer failed: " + reason));
                System.out.println("[GameDoorDebug] rescue transfer failed (rescued preserved): " + reason);
                return;
            }
            playerRef.sendMessage(Message.raw("Blacksmith rescued and added to base."));
            System.out.println("[GameDoorDebug] rescue transfer success");
        });
    }
}

