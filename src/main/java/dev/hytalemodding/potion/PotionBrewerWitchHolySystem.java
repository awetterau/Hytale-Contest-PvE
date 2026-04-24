package dev.hytalemodding.potion;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PotionBrewerWitchHolySystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, EntityStatMap> STATS = EntityStatMap.getComponentType();
    private static final ComponentType<EntityStore, EffectControllerComponent> EFFECTS = EffectControllerComponent.getComponentType();

    private static final String ROLE_NAME = "Potion_Brewer_Witch";
    private static final String HOLY_TRACK_PARTICLE_ID = "Potion_Brewer_Witch_Holy_Track";
    private static final String HOLY_EXPLOSION_PARTICLE_ID = "Potion_Brewer_Witch_Holy_Explosion";
    private static final int HEALTH_STAT_INDEX = DefaultEntityStatTypes.getHealth();
    private static final float HOLY_SHIELD_RATIO = 0.22f;
    private static final long HOLY_SHIELD_DURATION_MS = 8000L;
    private static final long HOLY_TRACK_DURATION_MS = 1800L;
    private static final long HOLY_LOCK_DELAY_MS = 900L;
    private static final long HOLY_VISUAL_INTERVAL_MS = 90L;
    private static final long HOLY_EXPLOSION_VISUAL_MS = 650L;
    private static final double HOLY_EXPLOSION_RADIUS = 3.4d;
    private static final float HOLY_EXPLOSION_DAMAGE = 35.0f;

    private static final Map<UUID, List<PendingHolyCast>> PENDING_CASTS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, List<TrackingMarker>> TRACKERS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, List<ExplosionEvent>> EXPLOSIONS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, HolyShield>> SHIELDS_BY_WORLD = new HashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();

        List<PlayerSnapshot> players = collectPlayers(store);
        Map<UUID, PlayerSnapshot> playersById = new HashMap<>();
        ArrayList<Ref<EntityStore>> particleRecipients = new ArrayList<>();
        for (PlayerSnapshot player : players) {
            playersById.put(player.playerId, player);
            particleRecipients.add(player.playerRef);
        }

        List<ShieldCmd> shieldCmds = new ArrayList<>();

        processPendingCasts(world, worldId, playersById, now);
        processTrackingMarkers(store, world, worldId, playersById, particleRecipients, now);
        processExplosions(store, world, worldId, players, particleRecipients, now);
        processShields(store, worldId, now, shieldCmds);

        for (ShieldCmd cmd : shieldCmds) {
            world.execute(() -> applyShieldCmd(world, cmd));
        }
    }

    public static void queueThrownHolyMarker(
            @Nonnull UUID worldId,
            @Nonnull UUID ownerBossId,
            @Nonnull UUID targetPlayerId
    ) {
        synchronized (PENDING_CASTS_BY_WORLD) {
            PENDING_CASTS_BY_WORLD.computeIfAbsent(worldId, ignored -> new ArrayList<>())
                    .add(new PendingHolyCast(ownerBossId, targetPlayerId));
        }
    }

    public static void spawnDebugHolyTracking(
            @Nonnull World world,
            @Nonnull UUID targetPlayerId
    ) {
        queueThrownHolyMarker(world.getWorldConfig().getUuid(), UUID.randomUUID(), targetPlayerId);
    }

    public static void triggerSelfHolyShield(
            @Nonnull World world,
            @Nonnull UUID ownerBossId,
            @Nullable Ref<EntityStore> ownerRef
    ) {
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        synchronized (SHIELDS_BY_WORLD) {
            SHIELDS_BY_WORLD.computeIfAbsent(worldId, ignored -> new HashMap<>())
                    .put(ownerBossId, new HolyShield(PotionBrewerWitchSystem.getConfiguredMaxHealth() * HOLY_SHIELD_RATIO, now + HOLY_SHIELD_DURATION_MS));
        }
        world.execute(() -> {
            if (ownerRef == null || !ownerRef.isValid()) {
                return;
            }
            world.getEntityStore().getStore().putComponent(ownerRef, EFFECTS, new EffectControllerComponent());
        });
        broadcastToWorld(world, "Potion Brewer Witch invokes a holy draught, cleansing itself and gaining a protective shield.");
    }

    public static void clearWorld(@Nonnull UUID worldId) {
        synchronized (PENDING_CASTS_BY_WORLD) {
            PENDING_CASTS_BY_WORLD.remove(worldId);
        }
        synchronized (TRACKERS_BY_WORLD) {
            TRACKERS_BY_WORLD.remove(worldId);
        }
        synchronized (EXPLOSIONS_BY_WORLD) {
            EXPLOSIONS_BY_WORLD.remove(worldId);
        }
        synchronized (SHIELDS_BY_WORLD) {
            SHIELDS_BY_WORLD.remove(worldId);
        }
    }

    public static void clearSelfHolyShield(@Nonnull UUID worldId, @Nonnull UUID ownerBossId) {
        synchronized (SHIELDS_BY_WORLD) {
            Map<UUID, HolyShield> worldShields = SHIELDS_BY_WORLD.get(worldId);
            if (worldShields == null) {
                return;
            }
            worldShields.remove(ownerBossId);
            if (worldShields.isEmpty()) {
                SHIELDS_BY_WORLD.remove(worldId);
            }
        }
    }

    @Nonnull
    private static List<PlayerSnapshot> collectPlayers(@Nonnull Store<EntityStore> store) {
        ArrayList<PlayerSnapshot> players = new ArrayList<>();
        store.forEachChunk(PLAYER, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                UUID playerId = getEntityUuid(store, playerRef);
                if (playerRef == null || !playerRef.isValid() || transform == null || playerId == null) {
                    continue;
                }
                players.add(new PlayerSnapshot(playerRef, playerId, new Vector3d(transform.getPosition())));
            }
        });
        return players;
    }

    private static void processPendingCasts(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull Map<UUID, PlayerSnapshot> playersById,
            long now
    ) {
        List<PendingHolyCast> pending;
        synchronized (PENDING_CASTS_BY_WORLD) {
            List<PendingHolyCast> queued = PENDING_CASTS_BY_WORLD.remove(worldId);
            pending = queued == null ? List.of() : new ArrayList<>(queued);
        }
        if (pending.isEmpty()) {
            return;
        }

        ArrayList<TrackingMarker> trackers = new ArrayList<>();
        for (PendingHolyCast cast : pending) {
            PlayerSnapshot target = playersById.get(cast.targetPlayerId);
            if (target == null) {
                continue;
            }
            Vector3d markerPos = markerPosition(world, target.position);
            trackers.add(new TrackingMarker(
                    cast.ownerBossId,
                    cast.targetPlayerId,
                    markerPos.getX(),
                    markerPos.getY(),
                    markerPos.getZ(),
                    now + HOLY_TRACK_DURATION_MS,
                    now + HOLY_TRACK_DURATION_MS + HOLY_LOCK_DELAY_MS,
                    now
            ));
        }
        if (!trackers.isEmpty()) {
            synchronized (TRACKERS_BY_WORLD) {
                TRACKERS_BY_WORLD.computeIfAbsent(worldId, ignored -> new ArrayList<>()).addAll(trackers);
            }
        }
    }

    private static void processTrackingMarkers(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull Map<UUID, PlayerSnapshot> playersById,
            @Nonnull List<Ref<EntityStore>> particleRecipients,
            long now
    ) {
        List<TrackingMarker> trackers;
        synchronized (TRACKERS_BY_WORLD) {
            trackers = TRACKERS_BY_WORLD.get(worldId) == null ? List.of() : new ArrayList<>(TRACKERS_BY_WORLD.get(worldId));
        }
        if (trackers.isEmpty()) {
            return;
        }

        ArrayList<TrackingMarker> expired = new ArrayList<>();
        ArrayList<ExplosionEvent> explosions = new ArrayList<>();
        for (TrackingMarker tracker : trackers) {
            PlayerSnapshot target = playersById.get(tracker.targetPlayerId);
            if (target != null) {
                Vector3d markerPos = markerPosition(world, target.position);
                tracker.x = markerPos.getX();
                tracker.y = markerPos.getY();
                tracker.z = markerPos.getZ();
            }

            if (now >= tracker.nextVisualAtMillis) {
                ParticleUtil.spawnParticleEffect(HOLY_TRACK_PARTICLE_ID, new Vector3d(tracker.x, tracker.y, tracker.z), particleRecipients, store);
                tracker.nextVisualAtMillis = now + HOLY_VISUAL_INTERVAL_MS;
            }

            if (now < tracker.trackUntilMillis) {
                continue;
            }

            explosions.add(new ExplosionEvent(
                    tracker.ownerBossId,
                    tracker.x,
                    tracker.y,
                    tracker.z,
                    tracker.lockUntilMillis,
                    tracker.lockUntilMillis + HOLY_EXPLOSION_VISUAL_MS,
                    tracker.nextVisualAtMillis
            ));
            expired.add(tracker);
        }

        synchronized (TRACKERS_BY_WORLD) {
            List<TrackingMarker> worldTrackers = TRACKERS_BY_WORLD.get(worldId);
            if (worldTrackers != null) {
                worldTrackers.removeAll(expired);
                if (worldTrackers.isEmpty()) {
                    TRACKERS_BY_WORLD.remove(worldId);
                }
            }
        }
        if (!explosions.isEmpty()) {
            synchronized (EXPLOSIONS_BY_WORLD) {
                EXPLOSIONS_BY_WORLD.computeIfAbsent(worldId, ignored -> new ArrayList<>()).addAll(explosions);
            }
        }
    }

    private static void processExplosions(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull List<Ref<EntityStore>> particleRecipients,
            long now
    ) {
        List<ExplosionEvent> explosions;
        synchronized (EXPLOSIONS_BY_WORLD) {
            explosions = EXPLOSIONS_BY_WORLD.get(worldId) == null ? List.of() : new ArrayList<>(EXPLOSIONS_BY_WORLD.get(worldId));
        }
        if (explosions.isEmpty()) {
            return;
        }

        ArrayList<ExplosionEvent> expired = new ArrayList<>();
        for (ExplosionEvent explosion : explosions) {
            if (!explosion.detonated && now >= explosion.nextVisualAtMillis && now < explosion.detonateAtMillis) {
                ParticleUtil.spawnParticleEffect(HOLY_TRACK_PARTICLE_ID, new Vector3d(explosion.x, explosion.y, explosion.z), particleRecipients, store);
                explosion.nextVisualAtMillis = now + HOLY_VISUAL_INTERVAL_MS;
            }

            if (!explosion.detonated && now >= explosion.detonateAtMillis) {
                ParticleUtil.spawnParticleEffect(HOLY_EXPLOSION_PARTICLE_ID, new Vector3d(explosion.x, explosion.y, explosion.z), particleRecipients, store);
                for (PlayerSnapshot player : players) {
                    if (!isInExplosion(player.position, explosion.x, explosion.y, explosion.z)) {
                        continue;
                    }
                    DamageSystems.executeDamage(player.playerRef, store, new Damage(Damage.NULL_SOURCE, resolveDamageCauseIndex(), HOLY_EXPLOSION_DAMAGE));
                    explosion.hitPlayers.add(player.playerId);
                }
                explosion.detonated = true;
            }

            if (now >= explosion.expireAtMillis) {
                expired.add(explosion);
            }
        }

        synchronized (EXPLOSIONS_BY_WORLD) {
            List<ExplosionEvent> worldExplosions = EXPLOSIONS_BY_WORLD.get(worldId);
            if (worldExplosions != null) {
                worldExplosions.removeAll(expired);
                if (worldExplosions.isEmpty()) {
                    EXPLOSIONS_BY_WORLD.remove(worldId);
                }
            }
        }
    }

    private static void processShields(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID worldId,
            long now,
            @Nonnull List<ShieldCmd> shieldCmds
    ) {
        Map<UUID, HolyShield> shieldsSnapshot;
        synchronized (SHIELDS_BY_WORLD) {
            Map<UUID, HolyShield> worldShields = SHIELDS_BY_WORLD.get(worldId);
            shieldsSnapshot = worldShields == null ? Map.of() : new HashMap<>(worldShields);
        }
        if (shieldsSnapshot.isEmpty()) {
            return;
        }

        EntityStore entityStore = (EntityStore) store.getExternalData();
        ArrayList<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, HolyShield> entry : shieldsSnapshot.entrySet()) {
            UUID bossId = entry.getKey();
            HolyShield shield = entry.getValue();
            if (now >= shield.expireAtMillis || shield.remainingAbsorb <= 0.0f) {
                remove.add(bossId);
                continue;
            }

            Ref<EntityStore> bossRef = entityStore.getRefFromUUID(bossId);
            if (bossRef == null || !bossRef.isValid()) {
                remove.add(bossId);
                continue;
            }
            NPCEntity npc = store.getComponent(bossRef, NPC);
            if (npc == null || !ROLE_NAME.equals(npc.getRoleName())) {
                remove.add(bossId);
                continue;
            }

            float currentHealth = readHealth(store.getComponent(bossRef, STATS));
            if (currentHealth < 0.0f) {
                remove.add(bossId);
                continue;
            }
            if (shield.lastObservedHealth < 0.0f) {
                shield.lastObservedHealth = currentHealth;
                continue;
            }
            if (currentHealth < shield.lastObservedHealth) {
                float damageTaken = shield.lastObservedHealth - currentHealth;
                float absorbed = Math.min(damageTaken, shield.remainingAbsorb);
                if (absorbed > 0.0f) {
                    shield.remainingAbsorb -= absorbed;
                    shield.lastObservedHealth = currentHealth + absorbed;
                    shieldCmds.add(new ShieldCmd(bossRef, absorbed));
                } else {
                    shield.lastObservedHealth = currentHealth;
                }
            } else {
                shield.lastObservedHealth = currentHealth;
            }
            if (shield.remainingAbsorb <= 0.0f) {
                remove.add(bossId);
            }
        }

        if (!remove.isEmpty()) {
            synchronized (SHIELDS_BY_WORLD) {
                Map<UUID, HolyShield> worldShields = SHIELDS_BY_WORLD.get(worldId);
                if (worldShields != null) {
                    for (UUID bossId : remove) {
                        worldShields.remove(bossId);
                    }
                    if (worldShields.isEmpty()) {
                        SHIELDS_BY_WORLD.remove(worldId);
                    }
                }
            }
        }
    }

    private static void applyShieldCmd(@Nonnull World world, @Nonnull ShieldCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        EntityStatMap stats = store.getComponent(cmd.bossRef, STATS);
        if (stats == null) {
            return;
        }
        float currentHealth = readHealth(stats);
        float healCeiling = PotionBrewerWitchSystem.getHealCeilingForCurrentHealth(currentHealth);
        if (currentHealth < 0.0f || currentHealth >= healCeiling) {
            return;
        }
        EntityStatMap updated = stats.clone();
        updated.addStatValue(HEALTH_STAT_INDEX, Math.min(cmd.healAmount, healCeiling - currentHealth));
        store.putComponent(cmd.bossRef, STATS, updated);
    }

    private static float readHealth(@Nullable EntityStatMap stats) {
        if (stats == null || HEALTH_STAT_INDEX < 0 || stats.get(HEALTH_STAT_INDEX) == null) {
            return -1.0f;
        }
        return stats.get(HEALTH_STAT_INDEX).get();
    }

    private static boolean isInExplosion(@Nonnull Vector3d playerPos, double x, double y, double z) {
        double dx = playerPos.getX() - x;
        double dz = playerPos.getZ() - z;
        return (dx * dx) + (dz * dz) <= HOLY_EXPLOSION_RADIUS * HOLY_EXPLOSION_RADIUS
                && Math.abs(playerPos.getY() - y) <= 2.5d;
    }

    @Nonnull
    private static Vector3d markerPosition(@Nonnull World world, @Nonnull Vector3d playerPos) {
        int x = (int) Math.floor(playerPos.getX());
        int z = (int) Math.floor(playerPos.getZ());
        int startY = (int) Math.floor(playerPos.getY()) + 1;
        for (int y = startY; y >= startY - 16; y--) {
            if (y < 0) {
                break;
            }
            String blockId = world.getBlockType(x, y, z) == null ? "Empty" : world.getBlockType(x, y, z).getId();
            if (!"Empty".equals(blockId)) {
                return new Vector3d(playerPos.getX(), y + 1.12d, playerPos.getZ());
            }
        }
        return new Vector3d(playerPos.getX(), Math.floor(playerPos.getY()) + 0.12d, playerPos.getZ());
    }

    @Nullable
    private static UUID getEntityUuid(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent component = store.getComponent(ref, UUIDComponent.getComponentType());
        return component == null ? null : component.getUuid();
    }

    private static int resolveDamageCauseIndex() {
        int command = DamageCause.getAssetMap().getIndex("Command");
        if (command != Integer.MIN_VALUE) {
            return command;
        }
        int environment = DamageCause.getAssetMap().getIndex("Environment");
        return environment != Integer.MIN_VALUE ? environment : 0;
    }

    private static void broadcastToWorld(@Nonnull World world, @Nonnull String text) {
    }

    private static final class PendingHolyCast {
        private final UUID ownerBossId;
        private final UUID targetPlayerId;

        private PendingHolyCast(@Nonnull UUID ownerBossId, @Nonnull UUID targetPlayerId) {
            this.ownerBossId = ownerBossId;
            this.targetPlayerId = targetPlayerId;
        }
    }

    private static final class TrackingMarker {
        private final UUID ownerBossId;
        private final UUID targetPlayerId;
        private final long trackUntilMillis;
        private final long lockUntilMillis;
        private double x;
        private double y;
        private double z;
        private long nextVisualAtMillis;

        private TrackingMarker(
                @Nonnull UUID ownerBossId,
                @Nonnull UUID targetPlayerId,
                double x,
                double y,
                double z,
                long trackUntilMillis,
                long lockUntilMillis,
                long nextVisualAtMillis
        ) {
            this.ownerBossId = ownerBossId;
            this.targetPlayerId = targetPlayerId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.trackUntilMillis = trackUntilMillis;
            this.lockUntilMillis = lockUntilMillis;
            this.nextVisualAtMillis = nextVisualAtMillis;
        }
    }

    private static final class ExplosionEvent {
        private final UUID ownerBossId;
        private final double x;
        private final double y;
        private final double z;
        private final long detonateAtMillis;
        private final long expireAtMillis;
        private final Set<UUID> hitPlayers = new HashSet<>();
        private boolean detonated;
        private long nextVisualAtMillis;

        private ExplosionEvent(
                @Nonnull UUID ownerBossId,
                double x,
                double y,
                double z,
                long detonateAtMillis,
                long expireAtMillis,
                long nextVisualAtMillis
        ) {
            this.ownerBossId = ownerBossId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.detonateAtMillis = detonateAtMillis;
            this.expireAtMillis = expireAtMillis;
            this.nextVisualAtMillis = nextVisualAtMillis;
        }
    }

    private static final class HolyShield {
        private float remainingAbsorb;
        private final long expireAtMillis;
        private float lastObservedHealth = -1.0f;

        private HolyShield(float remainingAbsorb, long expireAtMillis) {
            this.remainingAbsorb = remainingAbsorb;
            this.expireAtMillis = expireAtMillis;
        }
    }

    private static final class ShieldCmd {
        private final Ref<EntityStore> bossRef;
        private final float healAmount;

        private ShieldCmd(@Nonnull Ref<EntityStore> bossRef, float healAmount) {
            this.bossRef = bossRef;
            this.healAmount = healAmount;
        }
    }

    private static final class PlayerSnapshot {
        private final Ref<EntityStore> playerRef;
        private final UUID playerId;
        private final Vector3d position;

        private PlayerSnapshot(@Nonnull Ref<EntityStore> playerRef, @Nonnull UUID playerId, @Nonnull Vector3d position) {
            this.playerRef = playerRef;
            this.playerId = playerId;
            this.position = position;
        }
    }
}
