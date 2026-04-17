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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.RunEnvironmentPainter;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RedWoolDamageSystem extends EntityTickingSystem<EntityStore> {
<<<<<<< HEAD
    private static final float DAMAGE_INTERVAL_SECONDS = 0.5f;
    private static final float DAMAGE_PER_TICK = 5.0f;
    private static final float EXIT_CONFIRM_SECONDS = 2.0f;
    private static final float WORLD_FORCE_PLAYER_TRANSITION_SECONDS = 0.6f;
    private static final float WORLD_RESTORE_PLAYER_TRANSITION_SECONDS = 0.6f;
    private static final boolean DEBUG_HAZARD_WEATHER = false;
    private static volatile boolean HAZARD_FOG_ENABLED = true;
    private static volatile boolean loggedWeatherIndexResolution = false;
=======
    private static final float DAMAGE_INTERVAL_SECONDS = 0.5f;
    private static final float DAMAGE_PER_TICK = 5.0f;
    private static volatile boolean HAZARD_FOG_ENABLED = true;
>>>>>>> fe9202e (Crimson Update)

    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, PlayerRef> PLAYER_REF = PlayerRef.getComponentType();
<<<<<<< HEAD
    private static final ComponentType<EntityStore, WeatherTracker> WEATHER_TRACKER = WeatherTracker.getComponentType();

    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM, PLAYER_REF, WEATHER_TRACKER);
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedOnHazard = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID, HazardWeatherState> hazardWeatherStateByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID, Boolean> lastHazardPresenceByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<java.util.UUID>> hazardFogPlayersByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> previousForcedWeatherByWorld = new ConcurrentHashMap<>();
    private final Set<String> worldsWithNullPreviousForcedWeather = ConcurrentHashMap.newKeySet();
=======
    private final Query<EntityStore> query = Query.and(PLAYER, TRANSFORM, PLAYER_REF);
    private final ConcurrentHashMap<Ref<EntityStore>, Float> elapsedOnHazard = new ConcurrentHashMap<>();
>>>>>>> fe9202e (Crimson Update)

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
        boolean onHazard = isStandingOnHazardBlockSafe(transform, world);
        logHazardPresenceChange(playerId, transform, weatherTracker, onHazard);
        if (onHazard) {
            applyDamage(dt, index, archetypeChunk, commandBuffer, entityId);
<<<<<<< HEAD
            if (HAZARD_FOG_ENABLED) {
                HazardWeatherState state = this.hazardWeatherStateByPlayer.computeIfAbsent(playerId, ignored -> new HazardWeatherState());
                state.secondsOutsideHazard = 0.0f;
                applyHazardWeather(playerRef, weatherTracker, state, store);
            } else {
                clearHazardWeatherForPlayer(playerRef, weatherTracker, playerId, world, store);
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
            clearHazardWeatherForPlayer(playerRef, weatherTracker, playerId, world, store);
        }
    }

    private void clearHazardWeatherForPlayer(
            @Nonnull PlayerRef playerRef,
            @Nonnull WeatherTracker weatherTracker,
            @Nonnull java.util.UUID playerId,
            @Nonnull World world,
            @Nonnull Store<EntityStore> store
    ) {
        HazardWeatherState state = this.hazardWeatherStateByPlayer.remove(playerId);
        if (state == null) {
            return;
        }
        debugHazardWeather(
                "CLEAR_REQUEST player=" + playerId
                        + " currentWeather=" + weatherTracker.getWeatherIndex()
                        + " restoringWeather=" + state.lastNaturalWeatherIndex
                        + " applied=" + state.appliedAtLeastOnce
                        + " outsideSeconds=" + state.secondsOutsideHazard
                        + " env=" + weatherTracker.getEnvironmentId()
        );
        String worldName = world.getName();
        Set<java.util.UUID> playersInHazard = this.hazardFogPlayersByWorld.get(worldName);
        boolean lastHazardPlayer = false;
        if (playersInHazard != null) {
            playersInHazard.remove(playerId);
            lastHazardPlayer = playersInHazard.isEmpty();
            if (lastHazardPlayer) {
                this.hazardFogPlayersByWorld.remove(worldName);
            }
        }
        if (lastHazardPlayer) {
            String previousForcedWeather = consumePreviousForcedWeather(worldName);
            debugHazardWeather(
                    "WORLD_FORCE_RESTORE player=" + playerId
                            + " world=" + worldName
                            + " restoringForcedWeather=" + previousForcedWeather
            );
            if (state.lastNaturalWeatherIndex != Integer.MIN_VALUE) {
                weatherTracker.sendWeatherIndex(playerRef, state.lastNaturalWeatherIndex, WORLD_RESTORE_PLAYER_TRANSITION_SECONDS);
            }
            setWorldForcedWeather(world, store, previousForcedWeather);
        } else {
            debugHazardWeather(
                    "WORLD_FORCE_KEEP player=" + playerId
                            + " world=" + worldName
                            + " currentWeather=" + weatherTracker.getWeatherIndex()
                            + " remainingHazardPlayers=" + (playersInHazard == null ? 0 : playersInHazard.size())
            );
        }
    }
=======
            return;
        }

        this.elapsedOnHazard.remove(entityId);
    }
>>>>>>> fe9202e (Crimson Update)

    public static boolean isHazardFogEnabled() {
        return HAZARD_FOG_ENABLED;
    }

    public static void setHazardFogEnabled(boolean enabled) {
        HAZARD_FOG_ENABLED = enabled;
        RunEnvironmentPainter.setCrimsonZoneEnvironmentEnabled(enabled);
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

<<<<<<< HEAD
    private void applyHazardWeather(
            @Nonnull PlayerRef playerRef,
            @Nonnull WeatherTracker weatherTracker,
            @Nonnull HazardWeatherState state,
            @Nonnull Store<EntityStore> store
    ) {
        int naturalWeatherIndex = resolveNaturalWeatherIndex(weatherTracker, store);
        if (naturalWeatherIndex != Integer.MIN_VALUE) {
            state.lastNaturalWeatherIndex = naturalWeatherIndex;
        }
        World world = store.getExternalData().getWorld();
        String worldName = world.getName();
        int hazardWeatherIndex = resolveWeatherIndexSafe(RedWaveConfig.CRIMSON_HAZARD_WEATHER_ID, RedWaveConfig.RUN_DEFAULT_WEATHER_ID);
        logWeatherIndexResolutionOnce(hazardWeatherIndex);
        Set<java.util.UUID> playersInHazard = this.hazardFogPlayersByWorld.computeIfAbsent(worldName, ignored -> ConcurrentHashMap.newKeySet());
        boolean wasEmpty = playersInHazard.isEmpty();
        boolean added = playersInHazard.add(playerRef.getUuid());
        if (wasEmpty && added) {
            rememberPreviousForcedWeather(worldName, world.getWorldConfig().getForcedWeather());
            debugHazardWeather(
                    "WORLD_FORCE_SEND player=" + playerRef.getUuid()
                            + " world=" + worldName
                            + " currentWeather=" + weatherTracker.getWeatherIndex()
                            + " hazardWeather=" + hazardWeatherIndex
                            + " hazardWeatherId=" + RedWaveConfig.CRIMSON_HAZARD_WEATHER_ID
                            + " naturalWeather=" + state.lastNaturalWeatherIndex
                            + " env=" + weatherTracker.getEnvironmentId()
                            + " reason=first_world_hazard_player"
            );
            setWorldForcedWeather(world, store, RedWaveConfig.CRIMSON_HAZARD_WEATHER_ID);
            weatherTracker.sendWeatherIndex(playerRef, hazardWeatherIndex, WORLD_FORCE_PLAYER_TRANSITION_SECONDS);
        } else {
            debugHazardWeather("WORLD_FORCE_SKIP player=" + playerRef.getUuid() + " world=" + worldName);
        }
        state.appliedAtLeastOnce = true;
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

=======
>>>>>>> fe9202e (Crimson Update)
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

<<<<<<< HEAD
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

    private void rememberPreviousForcedWeather(@Nonnull String worldName, String previousForcedWeather) {
        if (previousForcedWeather == null || previousForcedWeather.isBlank()) {
            this.previousForcedWeatherByWorld.remove(worldName);
            this.worldsWithNullPreviousForcedWeather.add(worldName);
            return;
        }
        this.worldsWithNullPreviousForcedWeather.remove(worldName);
        this.previousForcedWeatherByWorld.put(worldName, previousForcedWeather);
    }

    private String consumePreviousForcedWeather(@Nonnull String worldName) {
        String previousForcedWeather = this.previousForcedWeatherByWorld.remove(worldName);
        if (previousForcedWeather != null) {
            return previousForcedWeather;
        }
        this.worldsWithNullPreviousForcedWeather.remove(worldName);
        return null;
    }

    private static void setWorldForcedWeather(@Nonnull World world, @Nonnull Store<EntityStore> store, String weatherId) {
        WeatherResource weatherResource = store.getResource(WeatherResource.getResourceType());
        if (weatherResource != null) {
            weatherResource.setForcedWeather(weatherId);
        }
        world.getWorldConfig().setForcedWeather(weatherId);
        world.getWorldConfig().markChanged();
    }

    private void logHazardPresenceChange(
            @Nonnull java.util.UUID playerId,
            @Nonnull TransformComponent transform,
            @Nonnull WeatherTracker weatherTracker,
            boolean onHazard
    ) {
        Boolean previous = this.lastHazardPresenceByPlayer.put(playerId, onHazard);
        if (previous != null && previous == onHazard) {
            return;
        }
        Vector3d pos = transform.getPosition();
        debugHazardWeather(
                "PRESENCE player=" + playerId
                        + " onHazard=" + onHazard
                        + " pos=" + MathUtil.floor(pos.getX()) + "," + MathUtil.floor(pos.getY()) + "," + MathUtil.floor(pos.getZ())
                        + " currentWeather=" + weatherTracker.getWeatherIndex()
                        + " env=" + weatherTracker.getEnvironmentId()
        );
    }

    private static void logWeatherIndexResolutionOnce(int resolvedHazardWeatherIndex) {
        if (loggedWeatherIndexResolution) {
            return;
        }
        loggedWeatherIndexResolution = true;
        debugHazardWeather(
                "INDEXES "
                        + RedWaveConfig.RUN_DEFAULT_WEATHER_ID + "=" + Weather.getAssetMap().getIndex(RedWaveConfig.RUN_DEFAULT_WEATHER_ID)
                        + " "
                        + RedWaveConfig.CRIMSON_HAZARD_WEATHER_ID + "=" + Weather.getAssetMap().getIndex(RedWaveConfig.CRIMSON_HAZARD_WEATHER_ID)
                        + " resolvedHazard=" + resolvedHazardWeatherIndex
        );
    }

    private static void debugHazardWeather(@Nonnull String message) {
        if (!DEBUG_HAZARD_WEATHER) {
            return;
        }
        System.out.println("[HazardFogDebug] " + message);
    }

    private static final class HazardWeatherState {
        private boolean appliedAtLeastOnce = false;
        private float secondsOutsideHazard = 0.0f;
        private int lastNaturalWeatherIndex = Integer.MIN_VALUE;
    }
}
=======
}
>>>>>>> fe9202e (Crimson Update)
