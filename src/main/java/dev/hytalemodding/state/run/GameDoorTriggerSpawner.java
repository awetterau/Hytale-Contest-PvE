package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class GameDoorTriggerSpawner {
    public static final String DEFAULT_NAMEPLATE = "Start Run";

    private GameDoorTriggerSpawner() {
    }

    @Nonnull
    public static SpawnAttempt spawn(@Nonnull World world, @Nonnull Transform transform) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE);
        if (roleIndex < 0) {
            return SpawnAttempt.failed("Role not found: " + GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE);
        }

        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null) {
            return SpawnAttempt.failed("Role builder info missing: " + GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE);
        }
        Builder<?> builder = roleInfo.getBuilder();
        if (builder == null || builder.category() != Role.class) {
            return SpawnAttempt.failed("Role builder is invalid: " + GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE);
        }
        if (!builder.isSpawnable()) {
            return SpawnAttempt.failed("Role is not spawnable: " + GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE);
        }

        npcPlugin.forceValidation(roleIndex);
        if (!npcPlugin.testAndValidateRole(roleInfo)) {
            return SpawnAttempt.failed("Role validation failed: " + GameDoorInteractionHandler.GAME_DOOR_TRIGGER_ROLE);
        }

        Vector3d spawnPos = new Vector3d(transform.getPosition());
        Vector3f spawnRot = new Vector3f(transform.getRotation());
        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) -> {
            entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);
            entityStore.putComponent(npcRef, Intangible.getComponentType(), Intangible.INSTANCE);
            entityStore.putComponent(npcRef, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
            entityStore.ensureAndGetComponent(npcRef, Nameplate.getComponentType()).setText(DEFAULT_NAMEPLATE);
            entityStore.removeComponentIfExists(npcRef, HitboxCollision.getComponentType());
        };

        Pair<Ref<EntityStore>, NPCEntity> pair = npcPlugin.spawnEntity(
                world.getEntityStore().getStore(),
                roleIndex,
                spawnPos,
                spawnRot,
                null,
                postSpawn
        );
        if (pair == null || pair.first() == null || !pair.first().isValid()) {
            return SpawnAttempt.failed("NPCPlugin.spawnEntity returned no valid entity.");
        }
        return SpawnAttempt.success(pair.first());
    }

    public static final class SpawnAttempt {
        @Nullable
        private final Ref<EntityStore> entityRef;
        @Nullable
        private final String error;

        private SpawnAttempt(@Nullable Ref<EntityStore> entityRef, @Nullable String error) {
            this.entityRef = entityRef;
            this.error = error;
        }

        @Nonnull
        public static SpawnAttempt success(@Nonnull Ref<EntityStore> entityRef) {
            return new SpawnAttempt(entityRef, null);
        }

        @Nonnull
        public static SpawnAttempt failed(@Nonnull String error) {
            return new SpawnAttempt(null, error);
        }

        public boolean isSuccess() {
            return this.entityRef != null && this.entityRef.isValid();
        }

        @Nullable
        public Ref<EntityStore> getEntityRef() {
            return this.entityRef;
        }

        @Nullable
        public String getError() {
            return this.error;
        }
    }
}
