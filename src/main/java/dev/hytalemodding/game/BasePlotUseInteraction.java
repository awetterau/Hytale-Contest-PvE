package dev.hytalemodding.game;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
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

public final class BasePlotUseInteraction extends SimpleBlockInteraction {
    @Nonnull
    public static final BuilderCodec<BasePlotUseInteraction> CODEC = BuilderCodec.builder(
            BasePlotUseInteraction.class,
            BasePlotUseInteraction::new,
            SimpleBlockInteraction.CODEC
    ).documentation("Opens plot assignment UI for base housing marker blocks.").build();

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
        Ref<EntityStore> entityRef = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(entityRef, PlayerRef.getComponentType());
        Player player = commandBuffer.getComponent(entityRef, Player.getComponentType());
        if (playerRef == null || player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        boolean handled = BasePlotInteractionHandler.handlePlotUse(playerRef, player, world, targetBlock);
        context.getState().state = handled ? InteractionState.Finished : InteractionState.Failed;
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
