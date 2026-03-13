package dev.hytalemodding.game;

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
import dev.hytalemodding.ui.BlacksmithDialoguePage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlacksmithDialogueManager {
    private static final String BASE_RESCUED_ROLE = "Blacksmith_Escort_Base";
    private static final String DIALOGUE_STATE = "Dialogue";
    private static final BlacksmithDialogueManager INSTANCE = new BlacksmithDialogueManager();

    private final ConcurrentHashMap<UUID, Ref<EntityStore>> activeNpcByPlayer = new ConcurrentHashMap<>();

    private BlacksmithDialogueManager() {
    }

    @Nonnull
    public static BlacksmithDialogueManager get() {
        return INSTANCE;
    }

    public void openDialogue(@Nonnull PlayerRef playerRef, @Nonnull Ref<EntityStore> npcRef) {
        if (!npcRef.isValid()) {
            return;
        }
        Store<EntityStore> npcStore = npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || !BASE_RESCUED_ROLE.equals(npc.getRoleName())) {
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
        if (this.activeNpcByPlayer.putIfAbsent(playerId, npcRef) != null) {
            return;
        }

        enterDialoguePose(npcStore, npcRef, playerRef);
        applyDialogueCamera(playerRef, npcStore, npcRef);
        player.getPageManager().openCustomPage(playerEntityRef, playerStore, new BlacksmithDialoguePage(playerRef));
    }

    public void closeDialogue(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> npcRef = this.activeNpcByPlayer.remove(playerRef.getUuid());
        restoreDefaultCamera(playerRef);
        if (npcRef == null || !npcRef.isValid()) {
            return;
        }

        Store<EntityStore> npcStore = npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null && npc.getRole().getStateSupport() != null) {
            npc.onFlockSetState(npcRef, "Idle", null, npcStore);
        }
    }

    public void keepDialogueActive(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        Ref<EntityStore> npcRef = this.activeNpcByPlayer.get(playerId);
        if (npcRef == null || !npcRef.isValid()) {
            npcRef = findDialogueNpcInPlayerStore(playerRef);
            if (npcRef == null || !npcRef.isValid()) {
                return;
            }
            this.activeNpcByPlayer.put(playerId, npcRef);
        }

        Store<EntityStore> npcStore = npcRef.getStore();
        enterDialoguePose(npcStore, npcRef, playerRef);
    }

    public void setTalkAnimation(@Nonnull PlayerRef playerRef, boolean talking) {
        Ref<EntityStore> npcRef = this.activeNpcByPlayer.get(playerRef.getUuid());
        if (npcRef == null || !npcRef.isValid()) {
            npcRef = findDialogueNpcInPlayerStore(playerRef);
            if (npcRef == null || !npcRef.isValid()) {
                return;
            }
            this.activeNpcByPlayer.put(playerRef.getUuid(), npcRef);
        }

        Store<EntityStore> npcStore = npcRef.getStore();
        NPCEntity npc = npcStore.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null && npc.getRole().getStateSupport() != null) {
            npc.onFlockSetState(npcRef, talking ? "Talk" : DIALOGUE_STATE, null, npcStore);
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

    @Nullable
    private static Ref<EntityStore> findDialogueNpcInPlayerStore(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return null;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        Collection<Ref<EntityStore>> found = new ArrayList<>(1);
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            if (!found.isEmpty()) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !BASE_RESCUED_ROLE.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found.add(ref);
                    return;
                }
            }
        });
        if (found.isEmpty()) {
            return null;
        }
        return found.iterator().next();
    }
}
