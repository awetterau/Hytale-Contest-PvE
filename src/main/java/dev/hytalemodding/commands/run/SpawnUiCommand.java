package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.ui.dev.DoorRunZoneSelectPage;
import dev.hytalemodding.ui.dev.SpawnSelectPage;
import dev.hytalemodding.state.run.SpawnPointZoneManager;

import javax.annotation.Nonnull;

public class SpawnUiCommand extends AbstractPlayerCommand {
    public SpawnUiCommand() {
        super("spawnui", "Open or close the spawn selection panel.");
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
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Unable to open spawn UI right now."));
            return;
        }

        CustomUIPage current = player.getPageManager().getCustomPage();
        if (current instanceof SpawnSelectPage || current instanceof DoorRunZoneSelectPage) {
            player.getPageManager().setPage(ref, store, Page.None);
            context.sendMessage(Message.raw("Spawn selector panel closed."));
            return;
        }

        player.getPageManager().openCustomPage(ref, store, new DoorRunZoneSelectPage(playerRef));
        context.sendMessage(Message.raw("Spawn selector panel opened."));
    }
}