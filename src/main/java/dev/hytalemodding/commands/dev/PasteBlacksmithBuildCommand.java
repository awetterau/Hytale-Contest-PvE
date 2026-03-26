package dev.hytalemodding.commands.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.hub.BlacksmithPrefabController;

import javax.annotation.Nonnull;

public final class PasteBlacksmithBuildCommand extends AbstractPlayerCommand {
    public PasteBlacksmithBuildCommand() {
        super("pasteblacksmithbuild", "Build the fixed custom blacksmith prefab into the hub over time.");
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
        BlacksmithPrefabController.Result result = BlacksmithPrefabController.startCustomBlacksmithBuild();
        context.sendMessage(Message.raw(result.message()));
    }
}
