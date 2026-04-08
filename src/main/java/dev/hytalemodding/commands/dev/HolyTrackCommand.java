package dev.hytalemodding.commands.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.potion.PotionBrewerWitchHolySystem;

import javax.annotation.Nonnull;
import java.util.UUID;

public final class HolyTrackCommand extends AbstractPlayerCommand {
    public HolyTrackCommand() {
        super("holytrack", "Spawn the Potion Brewer Witch holy tracking marker on yourself.");
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
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        UUID playerId = uuidComponent == null ? null : uuidComponent.getUuid();
        if (playerId == null) {
            context.sendMessage(Message.raw("Could not resolve your player UUID for holy tracking test."));
            return;
        }
        PotionBrewerWitchHolySystem.spawnDebugHolyTracking(world, playerId);
        context.sendMessage(Message.raw("Spawned holy tracking marker on you."));
    }
}
