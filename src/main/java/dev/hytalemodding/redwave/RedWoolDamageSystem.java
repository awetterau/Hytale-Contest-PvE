package dev.hytalemodding.redwave;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;

public class RedWoolDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float DAMAGE_INTERVAL_SECONDS = 0.5f;
    private static final float DAMAGE_PER_TICK = 5.0f;
    
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();

    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM);
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedOnHazard = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        Player player = archetypeChunk.getComponent(index, PLAYER);
        TransformComponent transform = archetypeChunk.getComponent(index, TRANSFORM);
        if (player == null || transform == null) {
            return;
        }

        Ref<EntityStore> entityId = archetypeChunk.getReferenceTo(index);
        if (!isStandingOnHazardBlockSafe(transform, store.getExternalData().getWorld())) {
            this.elapsedOnHazard.remove(entityId);
            return;
        }

        float elapsed = this.elapsedOnHazard.getOrDefault(entityId, 0.0f) + dt;
        if (elapsed >= DAMAGE_INTERVAL_SECONDS) {
            int applications = (int) (elapsed / DAMAGE_INTERVAL_SECONDS);
            Damage damage = new Damage(Damage.NULL_SOURCE, resolveHazardCauseIndexSafe(), applications * DAMAGE_PER_TICK);
            DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
            elapsed -= applications * DAMAGE_INTERVAL_SECONDS;
        }
        this.elapsedOnHazard.put(entityId, elapsed);
    }

    public static int resolveHazardCauseIndexSafe() {
        int environment = DamageCause.getAssetMap().getIndex("Environment");
        if (environment != Integer.MIN_VALUE) {
            return environment;
        }
        int command = DamageCause.getAssetMap().getIndex("Command");
        if (command != Integer.MIN_VALUE) {
            return command;
        }
        return 0;
    }

    public static boolean isStandingOnHazardBlockSafe(@Nonnull TransformComponent transform, @Nonnull World world) {
        Ref<ChunkStore> chunkRef = transform.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return false;
        }

        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        WorldChunk worldChunk = chunkStore.getComponent(chunkRef, WorldChunk.getComponentType());
        if (worldChunk == null) {
            return false;
        }

        Vector3d pos = transform.getPosition();
        int x = MathUtil.floor(pos.getX());
        int y = MathUtil.floor(pos.getY());
        int z = MathUtil.floor(pos.getZ());

        // Never force-load chunks from inside an entity tick system.
        long expectedChunk = ChunkUtil.indexChunkFromBlock(x, z);
        long loadedChunk = ChunkUtil.indexChunk(worldChunk.getX(), worldChunk.getZ());
        if (expectedChunk != loadedChunk) {
            return false;
        }

        return isHazard(worldChunk.getBlockType(x, y - 1, z)) || isHazard(worldChunk.getBlockType(x, y, z));
    }

    private static boolean isHazard(BlockType blockType) {
        if (blockType == null) {
            return false;
        }
        String id = blockType.getId();
        return RedWaveConfig.CRIMSON_BLOCK_ID.equals(id) || RedWaveConfig.OPTIONAL_CRIMSON_VOID_DAMAGE_BLOCK_ID.equals(id);
    }
}


