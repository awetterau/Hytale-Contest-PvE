package dev.hytalemodding.commands.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.RunChunkSelectionManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nonnull;

public final class RunChunkSelectionCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> actionArg = this.withRequiredArg("action", "on|off|mark|pin|prewarm|unmark|toggle|list", ArgTypes.STRING);

    public RunChunkSelectionCommand() {
        super("runchunks", "Manage and visualize selected run chunks.");
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
        RunChunkSelectionManager manager = RunChunkSelectionManager.get();
        String worldName = world.getName();
        String action = this.actionArg.get(context).trim().toLowerCase();

        int chunkX = Math.floorDiv((int) Math.floor(playerRef.getTransform().getPosition().getX()), 32);
        int chunkZ = Math.floorDiv((int) Math.floor(playerRef.getTransform().getPosition().getZ()), 32);

        switch (action) {
            case "on" -> {
                manager.enableFor(playerRef);
                manager.queueMapRefreshForChunks(worldName, manager.getSelectedChunks(worldName));
                refreshPlayerMapNow(world, playerRef, manager.getSelectedChunks(worldName));
                context.sendMessage(Message.raw("Chunk mode enabled. You can mark/unmark and view selected chunks."));
            }
            case "off" -> {
                manager.disableFor(playerRef);
                refreshPlayerMapNow(world, playerRef, manager.getSelectedChunks(worldName));
                context.sendMessage(Message.raw("Chunk mode disabled. You can no longer view or edit selected chunks."));
            }
            case "mark" -> {
                if (!manager.isEnabled(playerRef)) {
                    context.sendMessage(Message.raw("Enable first: /runchunks on"));
                    return;
                }
                boolean added = manager.mark(worldName, chunkX, chunkZ);
                manager.queueMapRefresh(worldName, chunkX, chunkZ);
                context.sendMessage(Message.raw((added ? "Marked prewarm" : "Already prewarm") + " chunk " + chunkX + "," + chunkZ));
            }
            case "pin" -> {
                if (!manager.isEnabled(playerRef)) {
                    context.sendMessage(Message.raw("Enable first: /runchunks on"));
                    return;
                }
                boolean changed = manager.markPinned(worldName, chunkX, chunkZ);
                manager.queueMapRefresh(worldName, chunkX, chunkZ);
                context.sendMessage(Message.raw((changed ? "Pinned" : "Already pinned") + " chunk " + chunkX + "," + chunkZ));
            }
            case "prewarm" -> {
                if (!manager.isEnabled(playerRef)) {
                    context.sendMessage(Message.raw("Enable first: /runchunks on"));
                    return;
                }
                boolean added = manager.mark(worldName, chunkX, chunkZ);
                boolean changedPinned = manager.setPinned(worldName, chunkX, chunkZ, false);
                manager.queueMapRefresh(worldName, chunkX, chunkZ);
                context.sendMessage(Message.raw((added || changedPinned ? "Set prewarm" : "Already prewarm")
                        + " chunk " + chunkX + "," + chunkZ));
            }
            case "unmark" -> {
                if (!manager.isEnabled(playerRef)) {
                    context.sendMessage(Message.raw("Enable first: /runchunks on"));
                    return;
                }
                boolean removed = manager.unmark(worldName, chunkX, chunkZ);
                manager.queueMapRefresh(worldName, chunkX, chunkZ);
                context.sendMessage(Message.raw((removed ? "Unmarked" : "Not marked") + " chunk " + chunkX + "," + chunkZ));
            }
            case "toggle" -> {
                if (!manager.isEnabled(playerRef)) {
                    context.sendMessage(Message.raw("Enable first: /runchunks on"));
                    return;
                }
                boolean marked = manager.toggle(worldName, chunkX, chunkZ);
                manager.queueMapRefresh(worldName, chunkX, chunkZ);
                context.sendMessage(Message.raw((marked ? "Marked" : "Unmarked") + " chunk " + chunkX + "," + chunkZ));
            }
            case "list" -> {
                int total = manager.count(worldName);
                int pinned = manager.countPinned(worldName);
                int prewarm = Math.max(0, total - pinned);
                context.sendMessage(Message.raw("World '" + worldName + "' chunks: total=" + total + ", pinned=" + pinned + ", prewarm=" + prewarm));
            }
            default -> context.sendMessage(Message.raw("Usage: /runchunks <on|off|mark|pin|prewarm|unmark|toggle|list>"));
        }
    }

    private static void refreshPlayerMapNow(
            @Nonnull World world,
            @Nonnull PlayerRef playerRef,
            @Nonnull java.util.Set<RunChunkSelectionManager.ChunkPosKey> chunks
    ) {
        if (chunks.isEmpty()) {
            return;
        }
        LongSet indices = new LongOpenHashSet(chunks.size());
        for (RunChunkSelectionManager.ChunkPosKey chunk : chunks) {
            indices.add(ChunkUtil.indexChunk(chunk.x(), chunk.z()));
        }

        if (world.getWorldMapManager() != null) {
            world.getWorldMapManager().clearImagesInChunks(indices);
        }
        Player player = world.getEntityStore().getStore().getComponent(playerRef.getReference(), Player.getComponentType());
        if (player != null && player.getWorldMapTracker() != null) {
            player.getWorldMapTracker().clearChunks(indices);
        }
    }
}