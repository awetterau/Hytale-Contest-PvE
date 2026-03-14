package dev.hytalemodding.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.ui.npc.NpcDialoguePage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcDialogueManager {
    private static final String DIALOGUE_STATE = "Dialogue";
    private static final NpcDialogueManager INSTANCE = new NpcDialogueManager();

    private final ConcurrentHashMap<UUID, DialogueSession> sessionByPlayer = new ConcurrentHashMap<>();

    private NpcDialogueManager() {
    }

    @Nonnull
    public static NpcDialogueManager get() {
        return INSTANCE;
    }

    public void openDialogue(@Nonnull PlayerRef playerRef, @Nonnull Ref<EntityStore> npcRef) {
        if (!npcRef.isValid()) {
            return;
        }
        Store<EntityStore> npcStore = npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRoleName() == null) {
            return;
        }
        String npcKey = NpcDefinitionRegistry.get().getNpcKeyByHubRole(npc.getRoleName());
        if (npcKey == null || npcKey.isBlank()) {
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }
        Store<EntityStore> playerStore = playerEntityRef.getStore();
        Player player = playerStore.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        this.sessionByPlayer.put(playerId, new DialogueSession(npcKey, npcRef));
        enterDialoguePose(npcStore, npcRef, playerRef);
        applyDialogueCamera(playerRef, npcStore, npcRef);
        player.getPageManager().openCustomPage(playerEntityRef, playerStore, new NpcDialoguePage(playerRef, npcKey));
    }

    public void closeDialogue(@Nonnull PlayerRef playerRef) {
        DialogueSession session = this.sessionByPlayer.remove(playerRef.getUuid());
        restoreDefaultCamera(playerRef);
        if (session == null || session.npcRef == null || !session.npcRef.isValid()) {
            return;
        }

        Store<EntityStore> npcStore = session.npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(session.npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null && npc.getRole().getStateSupport() != null) {
            npc.onFlockSetState(session.npcRef, "Idle", null, npcStore);
        }
    }

    @Nullable
    public String getActiveNpcKey(@Nonnull PlayerRef playerRef) {
        DialogueSession session = this.sessionByPlayer.get(playerRef.getUuid());
        return session == null ? null : session.npcKey;
    }

    public void keepDialogueActive(@Nonnull PlayerRef playerRef) {
        DialogueSession session = this.sessionByPlayer.get(playerRef.getUuid());
        if (session == null || session.npcRef == null || !session.npcRef.isValid()) {
            return;
        }
        enterDialoguePose(session.npcRef.getStore(), session.npcRef, playerRef);
    }

    public void setTalkAnimation(@Nonnull PlayerRef playerRef, boolean talking) {
        DialogueSession session = this.sessionByPlayer.get(playerRef.getUuid());
        if (session == null || session.npcRef == null || !session.npcRef.isValid()) {
            return;
        }
        Store<EntityStore> npcStore = session.npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(session.npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null && npc.getRole().getStateSupport() != null) {
            npc.onFlockSetState(session.npcRef, talking ? "Talk" : DIALOGUE_STATE, null, npcStore);
        }
    }

    private static void enterDialoguePose(
            @Nonnull Store<EntityStore> npcStore,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull PlayerRef playerRef
    ) {
        TransformComponent transform = npcStore.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d npcPos = transform.getPosition();
        Vector3d playerPos = playerRef.getTransform().getPosition();
        double dx = playerPos.getX() - npcPos.getX();
        double dz = playerPos.getZ() - npcPos.getZ();
        float yaw = (float) Math.atan2(-dx, -dz);

        Vector3f nextRot = new Vector3f(transform.getRotation());
        nextRot.setYaw(yaw);
        transform.teleportRotation(nextRot);
        npcStore.putComponent(npcRef, TransformComponent.getComponentType(), transform);

        NPCEntity npc = npcStore.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null && npc.getRole().getStateSupport() != null) {
            npc.onFlockSetState(npcRef, DIALOGUE_STATE, null, npcStore);
        }
    }

    private static void applyDialogueCamera(
            @Nonnull PlayerRef playerRef,
            @Nonnull Store<EntityStore> npcStore,
            @Nonnull Ref<EntityStore> npcRef
    ) {
        TransformComponent transform = npcStore.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d npcPos = transform.getPosition();
        float npcYaw = transform.getRotation().getYaw();
        Vector3d forward = com.hypixel.hytale.math.vector.Transform.getDirection(0.0f, npcYaw);

        double camX = npcPos.getX() + forward.getX() * 2.05;
        double camY = npcPos.getY() + 1.48;
        double camZ = npcPos.getZ() + forward.getZ() * 2.05;
        double targetX = npcPos.getX();
        double targetY = npcPos.getY() + 1.42;
        double targetZ = npcPos.getZ();

        double lookDx = targetX - camX;
        double lookDy = targetY - camY;
        double lookDz = targetZ - camZ;
        double horiz = Math.sqrt(lookDx * lookDx + lookDz * lookDz);

        float yaw = (float) Math.atan2(-lookDx, -lookDz);
        float pitch = (float) Math.atan2(lookDy, horiz);

        ServerCameraSettings settings = new ServerCameraSettings();
        settings.isFirstPerson = false;
        settings.displayReticle = false;
        settings.positionType = PositionType.Custom;
        settings.position = new Position(camX, camY, camZ);
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(yaw, pitch, 0.0f);

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    private static void restoreDefaultCamera(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.FirstPerson, false, null));
    }

    private static final class DialogueSession {
        @Nonnull
        private final String npcKey;
        @Nonnull
        private final Ref<EntityStore> npcRef;

        private DialogueSession(@Nonnull String npcKey, @Nonnull Ref<EntityStore> npcRef) {
            this.npcKey = npcKey;
            this.npcRef = npcRef;
        }
    }
}



