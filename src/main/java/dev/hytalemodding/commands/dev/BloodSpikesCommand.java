package dev.hytalemodding.commands.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.potion.PotionBrewerWitchBloodSystem;

import javax.annotation.Nonnull;

public final class BloodSpikesCommand extends AbstractPlayerCommand {
    public BloodSpikesCommand() {
        super("bloodspikes", "Spawn the Potion Brewer Witch blood spike burst at your position.");
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
        Vector3d origin = new Vector3d(playerRef.getTransform().getPosition());
        PotionBrewerWitchBloodSystem.spawnDebugBloodSpikes(world, origin);
        context.sendMessage(Message.raw("Spawned blood spikes at " + origin + "."));
    }
}
