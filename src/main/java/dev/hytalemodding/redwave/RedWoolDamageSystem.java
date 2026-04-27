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
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.InfectionCoreRegistry;
import dev.hytalemodding.state.run.RunEnvironmentPainter;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RedWoolDamageSystem extends EntityTickingSystem<EntityStore> {
    private static volatile boolean HAZARD_FOG_ENABLED = true;

    private static final String CRIMSON_POISON_EFFECT_ID = "Crimson_Poison";
    private static final double CRIMSON_MUSHROOM_POISON_RANGE_BLOCKS = 3.0d;
    private static final int CRIMSON_MUSHROOM_POISON_VERTICAL_RANGE_BLOCKS = 2;
    private static final long CRIMSON_POISON_REAPPLY_COOLDOWN_MS = 1_000L;

    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, PlayerRef> PLAYER_REF = PlayerRef.getComponentType();
    private static final ComponentType<EntityStore, EffectControllerComponent> EFFECTS = EffectControllerComponent.getComponentType();
    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM, PLAYER_REF);
    private final ConcurrentHashMap<Ref<EntityStore>, Long> lastPoisonApplyAtMs = new ConcurrentHashMap<>();

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
        PlayerRef playerRef = archetypeChunk.getComponent(index, PLAYER_REF);
        if (player == null || transform == null || playerRef == null) {
            return;
        }

        Ref<EntityStore> entityId = archetypeChunk.getReferenceTo(index);
        World world = store.getExternalData().getWorld();
        if (isInsideCrimsonMushroomPoisonRange(transform, world)) {
            applyCrimsonPoison(store, commandBuffer, entityId);
            return;
        }

        this.lastPoisonApplyAtMs.remove(entityId);
    }

    public static boolean isHazardFogEnabled() {
        return HAZARD_FOG_ENABLED;
    }

    public static void setHazardFogEnabled(boolean enabled) {
        HAZARD_FOG_ENABLED = enabled;
        RunEnvironmentPainter.setCrimsonZoneEnvironmentEnabled(enabled);
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

    private void applyCrimsonPoison(
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> entityId
    ) {
        long now = System.currentTimeMillis();
        long lastApplyAtMs = this.lastPoisonApplyAtMs.getOrDefault(entityId, 0L);
        if ((now - lastApplyAtMs) < CRIMSON_POISON_REAPPLY_COOLDOWN_MS) {
            return;
        }

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(CRIMSON_POISON_EFFECT_ID);
        if (effect == null) {
            return;
        }

        EffectControllerComponent controller = commandBuffer.getComponent(entityId, EFFECTS);
        if (controller == null) {
            controller = new EffectControllerComponent();
        } else {
            controller = controller.clone();
        }

        controller.addEffect(entityId, effect, store);
        commandBuffer.putComponent(entityId, EFFECTS, controller);
        this.lastPoisonApplyAtMs.put(entityId, now);
    }

    private static boolean isInsideCrimsonMushroomPoisonRange(@Nonnull TransformComponent transform, @Nonnull World world) {
        if (world.getWorldConfig() == null || world.getWorldConfig().getUuid() == null) {
            return false;
        }

        UUID worldId = world.getWorldConfig().getUuid();
        List<Vector3i> poisonMushrooms = InfectionCoreRegistry.snapshotCrimsonMushroomPoisonPositions(worldId);
        if (poisonMushrooms.isEmpty()) {
            return false;
        }

        Vector3d pos = transform.getPosition();
        double playerX = pos.getX();
        int playerY = MathUtil.floor(pos.getY());
        double playerZ = pos.getZ();
        double rangeSquared = CRIMSON_MUSHROOM_POISON_RANGE_BLOCKS * CRIMSON_MUSHROOM_POISON_RANGE_BLOCKS;

        for (Vector3i mushroom : poisonMushrooms) {
            int verticalDelta = Math.abs(playerY - mushroom.y);
            if (verticalDelta > CRIMSON_MUSHROOM_POISON_VERTICAL_RANGE_BLOCKS) {
                continue;
            }

            double dx = playerX - (mushroom.x + 0.5d);
            double dz = playerZ - (mushroom.z + 0.5d);
            double horizontalDistanceSquared = (dx * dx) + (dz * dz);
            if (horizontalDistanceSquared <= rangeSquared) {
                return true;
            }
        }

        return false;
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

        long expectedChunk = ChunkUtil.indexChunkFromBlock(x, z);
        long loadedChunk = ChunkUtil.indexChunk(worldChunk.getX(), worldChunk.getZ());
        if (expectedChunk != loadedChunk) {
            return false;
        }

        return isHazard(worldChunk.getBlockType(x, y, z))
                || isHazard(worldChunk.getBlockType(x, y - 1, z))
                || isHazard(worldChunk.getBlockType(x, y - 2, z))
                || isHazard(worldChunk.getBlockType(x, y - 3, z));
    }

    private static boolean isHazard(BlockType blockType) {
        return false;
    }
}