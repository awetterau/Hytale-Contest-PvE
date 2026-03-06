package dev.hytalemodding.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;

public class SpawnBlacksmithCommand extends AbstractPlayerCommand {
    private static final String ROLE = "Blacksmith_Escort_Base";

    public SpawnBlacksmithCommand() {
        super("spawnblacksmith", "Spawn the base Blacksmith NPC.");
        this.setPermissionGroup(null);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(ROLE);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null) {
            context.sendMessage(Message.raw("Unknown NPC role: " + ROLE));
            return;
        }
        if (!roleInfo.getBuilder().isSpawnable()) {
            context.sendMessage(Message.raw("NPC role is not spawnable: " + ROLE));
            return;
        }

        Transform playerTransform = playerRef.getTransform();
        Vector3d spawnPos = new Vector3d(playerTransform.getPosition());
        Vector3f spawnRot = new Vector3f(playerTransform.getRotation());

        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) ->
                entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);

        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                store,
                roleIndex,
                spawnPos,
                spawnRot,
                null,
                postSpawn
        );

        if (npcPair == null || npcPair.first() == null || !npcPair.first().isValid()) {
            context.sendMessage(Message.raw("Failed to spawn NPC for role: " + ROLE));
            return;
        }

        context.sendMessage(Message.raw("Spawned Blacksmith NPC role '" + ROLE + "' at " + spawnPos + "."));
    }
}
