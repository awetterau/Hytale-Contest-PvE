package dev.hytalemodding.commands.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.hub.FarmerPrefabController;

import javax.annotation.Nonnull;

public final class UndoFarmerCommand extends AbstractPlayerCommand {
    public UndoFarmerCommand() {
        super("undofarmer", "Paste the no-farmer prefab into the hub.");
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
        FarmerPrefabController.Result result = FarmerPrefabController.undoFarmer();
        context.sendMessage(Message.raw(result.message()));
    }
}
