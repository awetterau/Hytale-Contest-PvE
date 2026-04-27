package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class InfectionCoreDetectionSystem extends RefSystem<ChunkStore> {
    private static final ComponentType<ChunkStore, BlockModule.BlockStateInfo> BLOCK_STATE_INFO = BlockModule.BlockStateInfo.getComponentType();
    private static final int ARENA_ACTIVATION_RADIUS_BLOCKS = 22;
    private static final List<String> CRIMSON_MUSHROOM_FOX_SPAWN_ROLES = List.of(
            "Wolf_Black",
            "Spider_Cave",
            "Crawler_Void"
    );

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        syncRegistry(ref, commandBuffer, true);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        syncRegistry(ref, commandBuffer, false);
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return Query.and(BLOCK_STATE_INFO);
    }

    private static void syncRegistry(@Nonnull Ref<ChunkStore> ref, @Nonnull CommandBuffer<ChunkStore> commandBuffer, boolean adding) {
        BlockModule.BlockStateInfo blockStateInfo = commandBuffer.getComponent(ref, BLOCK_STATE_INFO);
        if (blockStateInfo == null) {
            return;
        }

        WorldChunk chunk = commandBuffer.getComponent(blockStateInfo.getChunkRef(), WorldChunk.getComponentType());
        if (chunk == null) {
            return;
        }

        int localX = ChunkUtil.xFromBlockInColumn(blockStateInfo.getIndex());
        int localY = ChunkUtil.yFromBlockInColumn(blockStateInfo.getIndex());
        int localZ = ChunkUtil.zFromBlockInColumn(blockStateInfo.getIndex());

        int worldX = (chunk.getX() * 32) + localX;
        int worldZ = (chunk.getZ() * 32) + localZ;
        Vector3i pos = new Vector3i(worldX, localY, worldZ);

        World world = chunk.getWorld();
        if (world == null || world.getWorldConfig() == null || world.getWorldConfig().getUuid() == null) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();

        if (!adding) {
            InfectionCoreRegistry.unregisterWeakCore(worldId, pos);
            InfectionCoreRegistry.unregisterCore(worldId, pos);
            InfectionCoreRegistry.unregisterCrimsonMushroomPoison(worldId, pos);
            InfectionCoreRegistry.unregisterArenaActivation(worldId, pos);
            return;
        }

        if (chunk.getBlockComponentHolder(localX, localY, localZ) == null) {
            return;
        }

        BlockType type = world.getBlockType(worldX, localY, worldZ);
        if (type == null) {
            return;
        }
        String rawBlockId = type.getId();
        String normalizedBlockId = normalizeBlockTypeId(rawBlockId);
        if (matchesBlockEntityState(rawBlockId, normalizedBlockId, InfectionCoreRegistry.WEAK_CORE_BLOCK_ID, InfectionCoreRegistry.WEAK_CORE_BLOCK_ENTITY_STATE_ID)) {
            InfectionCoreRegistry.registerWeakCore(worldId, pos);
            return;
        }
        if (matchesBlockEntityState(rawBlockId, normalizedBlockId, InfectionCoreRegistry.CORE_BLOCK_ID, InfectionCoreRegistry.CORE_BLOCK_ENTITY_STATE_ID)) {
            InfectionCoreRegistry.registerCore(worldId, pos);
            return;
        }
        if (matchesBlockEntityState(
                rawBlockId,
                normalizedBlockId,
                InfectionCoreRegistry.CRIMSON_MUSHROOM_POISON_BLOCK_ID,
                InfectionCoreRegistry.CRIMSON_MUSHROOM_POISON_BLOCK_ENTITY_STATE_ID
        )) {
            InfectionCoreRegistry.registerCrimsonMushroomPoison(worldId, pos);
            return;
        }
        if (matchesBlockEntityState(
                rawBlockId,
                normalizedBlockId,
                InfectionCoreRegistry.ARENA_ACTIVATION_BLOCK_ID,
                InfectionCoreRegistry.ARENA_ACTIVATION_BLOCK_ENTITY_STATE_ID
        )) {
            InfectionCoreRegistry.registerArenaActivation(worldId, pos);
            RunEnvironmentPainter.paintArenaEnvironmentZone(world, pos.x, pos.y, pos.z, ARENA_ACTIVATION_RADIUS_BLOCKS);
            return;
        }
        if (matchesBlockEntityState(
                rawBlockId,
                normalizedBlockId,
                InfectionCoreRegistry.CRIMSON_MUSHROOM_FOX_BLOCK_ID,
                InfectionCoreRegistry.CRIMSON_MUSHROOM_FOX_BLOCK_ENTITY_STATE_ID
        )) {
            spawnRandomCrimsonMushroomFoxMob(world, pos);
        }
    }

    private static void spawnRandomCrimsonMushroomFoxMob(@Nonnull World world, @Nonnull Vector3i pos) {
        if (CRIMSON_MUSHROOM_FOX_SPAWN_ROLES.isEmpty()) {
            return;
        }
        String selectedRole = CRIMSON_MUSHROOM_FOX_SPAWN_ROLES.get(ThreadLocalRandom.current().nextInt(CRIMSON_MUSHROOM_FOX_SPAWN_ROLES.size()));
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(selectedRole);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || roleInfo.getBuilder() == null || !roleInfo.getBuilder().isSpawnable()) {
            return;
        }
        Vector3d spawnPos = new Vector3d(pos.x + 0.5d, pos.y + 1.0d, pos.z + 0.5d);
        Vector3f spawnRot = new Vector3f(0.0f, ThreadLocalRandom.current().nextFloat() * 360.0f, 0.0f);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(entityStore, roleIndex, spawnPos, spawnRot, null, null);
        if (spawned == null || spawned.first() == null || !spawned.first().isValid()) {
            System.out.println("[InfectionCoreDetection] Failed to spawn fox mushroom mob role=" + selectedRole + " at " + pos.x + "," + pos.y + "," + pos.z);
        }
    }

    private static boolean matchesBlockEntityState(
            String rawBlockId,
            String normalizedBlockId,
            String expectedBlockId,
            String expectedStateId
    ) {
        if (expectedBlockId == null || expectedStateId == null) {
            return false;
        }
        String rawLower = rawBlockId == null ? "" : rawBlockId.toLowerCase();
        String normalizedLower = normalizedBlockId == null ? "" : normalizedBlockId.toLowerCase();
        String expectedBlockLower = expectedBlockId.toLowerCase();
        String expectedStateLower = expectedStateId.toLowerCase();

        boolean blockMatch = normalizedLower.equals(expectedBlockLower) || rawLower.contains(expectedBlockLower);
        boolean stateMatch = rawLower.contains(expectedStateLower) || normalizedLower.contains(expectedStateLower);
        return blockMatch || stateMatch;
    }

    private static String normalizeBlockTypeId(String blockId) {
        if (blockId == null) {
            return "";
        }
        String normalized = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        int stateIndex = normalized.indexOf("_State_Definitions_");
        if (stateIndex >= 0) {
            normalized = normalized.substring(0, stateIndex);
        }
        return normalized.trim();
    }
}