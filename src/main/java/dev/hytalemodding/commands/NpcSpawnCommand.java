package dev.hytalemodding.commands;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;

public class NpcSpawnCommand extends AbstractPlayerCommand {
    private static final String DEFAULT_ROLE = "Blacksmith_Escort_Base";
    @Nonnull
    private final OptionalArg<String> roleArg = this.withOptionalArg("role", "Optional NPC role name", ArgTypes.STRING);

    public NpcSpawnCommand() {
        super("npcspawn", "Spawn an escort NPC with role-based movement.");
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
        String requestedRole = this.roleArg.provided(context) ? this.roleArg.get(context) : DEFAULT_ROLE;
        if (requestedRole == null || requestedRole.isEmpty()) {
            context.sendMessage(Message.raw("Default NPC role is unavailable: " + DEFAULT_ROLE));
            return;
        }

        int roleIndex = npcPlugin.getIndex(requestedRole);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null) {
            context.sendMessage(Message.raw("Unknown NPC role: " + requestedRole));
            return;
        }
        if (!roleInfo.getBuilder().isSpawnable()) {
            context.sendMessage(Message.raw("NPC role is abstract and cannot be spawned: " + requestedRole));
            return;
        }

        Transform playerTransform = playerRef.getTransform();
        Vector3d spawnPos = new Vector3d(playerTransform.getPosition());
        Vector3f spawnRot = new Vector3f(playerTransform.getRotation());

        Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                store,
                roleIndex,
                spawnPos,
                spawnRot,
                null,
                null
        );

        if (npcPair == null || npcPair.first() == null || !npcPair.first().isValid()) {
            context.sendMessage(Message.raw("Failed to spawn NPC for role: " + requestedRole));
            return;
        }

        context.sendMessage(Message.raw("Spawned companion role '" + requestedRole + "' at " + spawnPos + ". Press F to toggle follow."));
    }
}
