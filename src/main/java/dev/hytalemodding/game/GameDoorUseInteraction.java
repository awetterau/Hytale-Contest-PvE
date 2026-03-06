package dev.hytalemodding.game;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class GameDoorUseInteraction extends SimpleBlockInteraction {
    @Nonnull
    public static final BuilderCodec<GameDoorUseInteraction> CODEC = BuilderCodec.builder(
            GameDoorUseInteraction.class,
            GameDoorUseInteraction::new,
            SimpleBlockInteraction.CODEC
    ).documentation("Runs start/extract flow for the custom game door.").build();

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull Vector3i targetBlock,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        Ref<EntityStore> ref = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (playerRef == null || player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        System.out.println("[GameDoorDebug] interaction fired: player=" + playerRef.getUuid() + " block=" + targetBlock);
        player.sendMessage(Message.raw("[DoorDebug] Interaction fired at " + targetBlock.x + ", " + targetBlock.y + ", " + targetBlock.z));

        boolean handled = GameDoorInteractionHandler.handleDoorTrigger(playerRef, targetBlock);
        if (handled) {
            context.getState().state = InteractionState.Finished;
            return;
        }

        player.sendMessage(Message.raw("[DoorDebug] Door trigger rejected. Check setup or world state."));
        context.getState().state = InteractionState.Failed;
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull World world,
            @Nonnull Vector3i targetBlock
    ) {
    }
}
