package dev.hytalemodding.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.ui.RedControlPage;

import javax.annotation.Nonnull;

public class RedUiCommand extends AbstractPlayerCommand {
    public RedUiCommand() {
        super("redui", "Open or close the red control panel.");
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
            context.sendMessage(Message.raw("Unable to open red UI right now."));
            return;
        }

        CustomUIPage current = player.getPageManager().getCustomPage();
        if (current instanceof RedControlPage) {
            player.getPageManager().setPage(ref, store, Page.None);
            context.sendMessage(Message.raw("Red control panel closed."));
            return;
        }

        player.getPageManager().openCustomPage(ref, store, new RedControlPage(playerRef));
        context.sendMessage(Message.raw("Red control panel opened."));
    }
}
