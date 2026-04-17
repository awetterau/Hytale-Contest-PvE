package dev.hytalemodding.commands.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

public final class DespawnWitchCommand extends AbstractPlayerCommand {
    private static final String WITCH_ROLE = "Potion_Brewer_Witch";

    public DespawnWitchCommand() {
        super("despawnwitch", "Despawn all Potion Brewer Witches in the current world.");
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
        final int[] queued = {0};
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || npc.isDespawning() || !WITCH_ROLE.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                npc.setToDespawn();
                store.putComponent(npcRef, NPCEntity.getComponentType(), npc);
                queued[0]++;
            }
        });
        context.sendMessage(Message.raw("Queued Potion Brewer Witch despawns: " + queued[0]));
        System.out.println("[DespawnWitchCommand] world=" + world.getName() + " queued=" + queued[0]);
    }
}
