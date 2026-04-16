package dev.hytalemodding.redwave;

import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
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
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;

public class RedWoolDamageSystem extends EntityTickingSystem<EntityStore> {
    private static final float DAMAGE_INTERVAL_SECONDS = 0.5f;
    private static final float DAMAGE_PER_TICK = 5.0f;
    private static final float HAZARD_TRANSITION_SECONDS = 0.6f;
    private static final float NATURAL_TRANSITION_SECONDS = 0.8f;
    private static final float WEATHER_APPLY_INTERVAL_SECONDS = 0.6f;
    private static final float EXIT_CONFIRM_SECONDS = 2.0f;
    private static volatile boolean HAZARD_FOG_ENABLED = true;

    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, PlayerRef> PLAYER_REF = PlayerRef.getComponentType();
    private static final ComponentType<EntityStore, WeatherTracker> WEATHER_TRACKER = WeatherTracker.getComponentType();

    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM, PLAYER_REF, WEATHER_TRACKER);
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedOnHazard = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID, HazardWeatherState> hazardWeatherStateByPlayer = new ConcurrentHashMap<>();

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
        WeatherTracker weatherTracker = archetypeChunk.getComponent(index, WEATHER_TRACKER);
        if (player == null || transform == null || playerRef == null || weatherTracker == null) {
            return;
        }

        Ref<EntityStore> entityId = archetypeChunk.getReferenceTo(index);
        World world = store.getExternalData().getWorld();
        java.util.UUID playerId = playerRef.getUuid();
        boolean onHazard = isStandingOnHazardBlockSafe(transform, world);
        if (onHazard) {
            applyDamage(dt, index, archetypeChunk, commandBuffer, entityId);
            if (HAZARD_FOG_ENABLED) {
                HazardWeatherState state = this.hazardWeatherStateByPlayer.computeIfAbsent(playerId, ignored -> new HazardWeatherState());
                state.secondsOutsideHazard = 0.0f;
                applyHazardWeather(playerRef, weatherTracker, state, dt, store);
            } else {
                clearHazardWeatherForPlayer(playerRef, weatherTracker, playerId);
            }
            return;
        }

        this.elapsedOnHazard.remove(entityId);
        HazardWeatherState state = this.hazardWeatherStateByPlayer.get(playerId);
        if (state == null) {
            return;
        }
        state.secondsOutsideHazard += dt;
        if (state.secondsOutsideHazard >= EXIT_CONFIRM_SECONDS) {
            clearHazardWeatherForPlayer(playerRef, weatherTracker, playerId);
        }
    }

    private void clearHazardWeatherForPlayer(
            @Nonnull PlayerRef playerRef,
            @Nonnull WeatherTracker weatherTracker,
            @Nonnull java.util.UUID playerId
    ) {
        HazardWeatherState state = this.hazardWeatherStateByPlayer.remove(playerId);
        if (state == null) {
            return;
        }
        if (state.lastNaturalWeatherIndex != Integer.MIN_VALUE && weatherTracker.getWeatherIndex() != state.lastNaturalWeatherIndex) {
            weatherTracker.sendWeatherIndex(playerRef, state.lastNaturalWeatherIndex, NATURAL_TRANSITION_SECONDS);
        }
    }

    public static boolean isHazardFogEnabled() {
        return HAZARD_FOG_ENABLED;
    }

    public static void setHazardFogEnabled(boolean enabled) {
        HAZARD_FOG_ENABLED = enabled;
    }

    private void applyDamage(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> entityId
    ) {
        float elapsed = this.elapsedOnHazard.getOrDefault(entityId, 0.0f) + dt;
        if (elapsed >= DAMAGE_INTERVAL_SECONDS) {
            int applications = (int) (elapsed / DAMAGE_INTERVAL_SECONDS);
            Damage damage = new Damage(Damage.NULL_SOURCE, resolveHazardCauseIndexSafe(), applications * DAMAGE_PER_TICK);
            DamageSystems.executeDamage(index, archetypeChunk, commandBuffer, damage);
            elapsed -= applications * DAMAGE_INTERVAL_SECONDS;
        }
        this.elapsedOnHazard.put(entityId, elapsed);
    }

    private void applyHazardWeather(
            @Nonnull PlayerRef playerRef,
            @Nonnull WeatherTracker weatherTracker,
            @Nonnull HazardWeatherState state,
            float dt,
            @Nonnull Store<EntityStore> store
    ) {
        int naturalWeatherIndex = resolveNaturalWeatherIndex(weatherTracker, store);
        if (naturalWeatherIndex != Integer.MIN_VALUE) {
            state.lastNaturalWeatherIndex = naturalWeatherIndex;
        }
        state.secondsUntilNextApply -= dt;
        if (state.appliedAtLeastOnce && state.secondsUntilNextApply > 0.0f) {
            return;
        }

        int hazardWeatherIndex = resolveWeatherIndexSafe(RedWaveConfig.CRIMSON_HAZARD_WEATHER_ID, RedWaveConfig.RUN_DEFAULT_WEATHER_ID);
        if (!state.appliedAtLeastOnce || weatherTracker.getWeatherIndex() != hazardWeatherIndex) {
            weatherTracker.sendWeatherIndex(playerRef, hazardWeatherIndex, HAZARD_TRANSITION_SECONDS);
        }
        state.appliedAtLeastOnce = true;
        state.secondsUntilNextApply = WEATHER_APPLY_INTERVAL_SECONDS;
    }

    private static int resolveNaturalWeatherIndex(@Nonnull WeatherTracker weatherTracker, @Nonnull Store<EntityStore> store) {
        WeatherResource weatherResource = store.getResource(WeatherResource.getResourceType());
        if (weatherResource == null) {
            return Integer.MIN_VALUE;
        }
        int environmentId = weatherTracker.getEnvironmentId();
        int weatherIndex = weatherResource.getWeatherIndexForEnvironment(environmentId);
        return weatherIndex == Integer.MIN_VALUE ? 0 : weatherIndex;
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
        if (blockType == null) {
            return false;
        }
        String id = blockType.getId();
        return RedWaveConfig.CRIMSON_BLOCK_ID.equals(id) || RedWaveConfig.OPTIONAL_CRIMSON_VOID_DAMAGE_BLOCK_ID.equals(id);
    }

    private static int resolveWeatherIndexSafe(@Nonnull String preferredId, @Nonnull String fallbackId) {
        int preferred = Weather.getAssetMap().getIndex(preferredId);
        if (preferred != Integer.MIN_VALUE) {
            return preferred;
        }
        int fallback = Weather.getAssetMap().getIndex(fallbackId);
        if (fallback != Integer.MIN_VALUE) {
            return fallback;
        }
        return 0;
    }

    private static final class HazardWeatherState {
        private boolean appliedAtLeastOnce = false;
        private float secondsUntilNextApply = 0.0f;
        private float secondsOutsideHazard = 0.0f;
        private int lastNaturalWeatherIndex = Integer.MIN_VALUE;
    }
}