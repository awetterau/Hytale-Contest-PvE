package dev.hytalemodding.npc.runtime;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.blob.OrangeBlobBlockManager;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcDialogueManager;
import dev.hytalemodding.npc.state.NpcRuntimeState;
import dev.hytalemodding.state.run.FarmerAnimalRescueManager;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.RescueObjectiveManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class NpcInteractionRouter {
    private static final NpcInteractionRouter INSTANCE = new NpcInteractionRouter();

    private NpcInteractionRouter() {
    }

    @Nonnull
    public static NpcInteractionRouter get() {
        return INSTANCE;
    }

    public void handleInteraction(
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> targetRef,
            @Nullable InteractionType interactionType
    ) {
        if (interactionType != null
                && interactionType != InteractionType.Use
                && interactionType != InteractionType.Primary
                && interactionType != InteractionType.Secondary) {
            return;
        }
        if (!targetRef.isValid()) {
            return;
        }
        if (FarmerAnimalRescueManager.get().handleInteraction(playerRef, targetRef, interactionType)) {
            return;
        }
        Store<EntityStore> targetStore = targetRef.getStore();
        NPCEntity npc = targetStore.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRoleName() == null) {
            return;
        }
        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();
        boolean inActiveRunWorld = session != null
                && session.runWorldUuid() != null
                && playerRef.getWorldUuid() != null
                && session.runWorldUuid().equals(playerRef.getWorldUuid());
        if (inActiveRunWorld) {
            if (OrangeBlobBlockManager.tryLaunchReadyExtractionFromRuneProxy(playerRef, session.runWorldUuid(), targetRef)) {
                return;
            }
        }
        String runNpcKey = NpcDefinitionRegistry.get().getNpcKeyByRunRescueRole(npc.getRoleName());
        if (inActiveRunWorld && runNpcKey != null && !runNpcKey.isBlank()) {
            RescueObjectiveManager.get().markFollowingFromNpcRef(playerRef, targetRef, interactionType);
            return;
        }
        String hubNpcKey = NpcDefinitionRegistry.get().getNpcKeyByHubRole(npc.getRoleName());
        if (hubNpcKey != null && !hubNpcKey.isBlank()) {
            NpcAvailabilityService.AvailabilitySnapshot availability = NpcAvailabilityService.get().getAvailability(hubNpcKey);
            if (availability.canOpenDialogue()) {
                NpcDialogueManager.get().openDialogue(playerRef, targetRef);
            } else {
                NpcRuntimeState runtimeState = availability.runtimeState();
                playerRef.sendMessage(Message.raw(hubNpcKey + " is currently " + runtimeState.hubBehavior.name() + "."));
            }
            return;
        }
        if (runNpcKey != null && !runNpcKey.isBlank()) {
            RescueObjectiveManager.get().markFollowingFromNpcRef(playerRef, targetRef, interactionType);
        }
    }
}
