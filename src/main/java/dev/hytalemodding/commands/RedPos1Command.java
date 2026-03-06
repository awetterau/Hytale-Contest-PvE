package dev.hytalemodding.commands;

import dev.hytalemodding.redwave.RedWaveManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class RedPos1Command extends AbstractPlayerCommand {
    @Nonnull
    private final OptionalArg<Integer> xArg = this.withOptionalArg("x", "X position", ArgTypes.INTEGER);
    @Nonnull
    private final OptionalArg<Integer> yArg = this.withOptionalArg("y", "Y position", ArgTypes.INTEGER);
    @Nonnull
    private final OptionalArg<Integer> zArg = this.withOptionalArg("z", "Z position", ArgTypes.INTEGER);

    public RedPos1Command() {
        super("redpos1", "Set red-wave position 1 from looked block or explicit x y z.");
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
        Vector3i looked;
        if (this.xArg.provided(context) && this.yArg.provided(context) && this.zArg.provided(context)) {
            looked = new Vector3i(this.xArg.get(context), this.yArg.get(context), this.zArg.get(context));
        } else {
            Transform transform = playerRef.getTransform();
            looked = new Vector3i(
                    MathUtil.floor(transform.getPosition().getX()),
                    MathUtil.floor(transform.getPosition().getY()) - 1,
                    MathUtil.floor(transform.getPosition().getZ())
            );
        }

        RedWaveManager.setPos1(playerRef.getUuid(), world.getWorldConfig().getUuid(), looked);
        context.sendMessage(Message.raw("Red area pos1 set: " + looked.x + ", " + looked.y + ", " + looked.z));
    }
}
