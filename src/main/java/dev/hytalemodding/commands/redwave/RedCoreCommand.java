package dev.hytalemodding.commands.redwave;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.state.transition.CrimsonCoreConfigManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;

public class RedCoreCommand extends AbstractPlayerCommand {
    public RedCoreCommand() {
        super("redcore", "Set crimson core at the block under your feet. Usage: /redcore");
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
        Transform transform = playerRef.getTransform();
        Vector3i corePos = new Vector3i(
                MathUtil.floor(transform.getPosition().getX()),
                MathUtil.floor(transform.getPosition().getY()) - 1,
                MathUtil.floor(transform.getPosition().getZ())
        );

        world.setBlock(corePos.x, corePos.y, corePos.z, RedWaveConfig.CORE_BLOCK_ID);
        RedCoreRegistry.register(world.getWorldConfig().getUuid(), corePos);
        RedWaveManager.setCore(playerRef.getUuid(), world.getWorldConfig().getUuid(), corePos);
        CrimsonCoreConfigManager.CrimsonCoreConfigState existing = CrimsonCoreConfigManager.get().getState(world.getName());
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles = new ArrayList<>(existing.profiles());
        boolean found = false;
        for (RedCoreProfileRegistry.RedCoreProfile profile : profiles) {
            if (profile.corePos().equals(corePos)) {
                found = true;
                break;
            }
        }
        if (!found) {
            profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(corePos), existing.radiusBlocks(), existing.spreadSeconds()));
            int chooseCount = existing.chooseCount() <= 0 ? 1 : Math.min(existing.chooseCount(), profiles.size());
            CrimsonCoreConfigManager.get().setState(world.getName(), new CrimsonCoreConfigManager.CrimsonCoreConfigState(chooseCount, existing.radiusBlocks(), existing.spreadSeconds(), profiles));
        }
        context.sendMessage(Message.raw(
                "Crimson core saved at: " + corePos.x + ", " + corePos.y + ", " + corePos.z
        ));
    }
}
