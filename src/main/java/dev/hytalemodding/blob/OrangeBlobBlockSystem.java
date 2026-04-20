package dev.hytalemodding.blob;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OrangeBlobBlockSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final String EMPTY_BLOCK_ID = "Empty";
    private static final String MOB_ROLE = "Crimson_OBrute_Ext";
    private static final String WOLF_BLACK_ROLE = "Crimson_Wolf_Ext";
    private static final String RUNE_OBJECTIVE_ROLE = "Extraction_Rune_Objective";
    private static final boolean FORCE_TEST_ROTATION_ENABLED = false;
    private static final int FORCE_TEST_ROTATION_INDEX = 3;
    private static final long DEBUG_INTERVAL_MS = 1000L;
    private static final double MOB_SPAWN_MIN_GAP_BLOCKS = 3.5d;
    private static final int MOB_SPAWN_MAX_ATTEMPTS_PER_MOB = 48;
    private static final int MOB_SPAWN_HISTORY_LIMIT = 24;
    private static final String[] RUNE_TARGET_SLOTS = new String[]{"target", "Target", "CombatTarget"};
    private static int extractionDamageCauseIndex = Integer.MIN_VALUE;
    private static final List<SpawnRoleProfile> SPAWN_ROLE_PROFILES = List.of(
            new SpawnRoleProfile(MOB_ROLE, 75, 0.90d),
            new SpawnRoleProfile(WOLF_BLACK_ROLE, 25, 0.45d)
    );

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        List<OrangeBlobBlockRuntime.Session> sessions = OrangeBlobBlockRuntime.getSessions(worldId);
        if (sessions.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (OrangeBlobBlockRuntime.Session session : sessions) {
            tickSession(store, world, session, now);
        }
        OrangeBlobBlockRuntime.removeCompleted(worldId);
    }

    private static void tickSession(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
        if (session.phase() == OrangeBlobBlockRuntime.Phase.MOVING_DOWN) {
            ensureDownMovers(session);
            if (!session.rockDownSignalPending() && session.rockPhase() == OrangeBlobBlockRuntime.RockPhase.IDLE_UP) {
                session.signalRockMoveDown(session.downStartAt());
            }
            updateMovers(store, session.movers(), now);
            tickIndependentRockMovement(store, world, session, now);
            if (now >= session.downEndAt()) {
                if (!session.loweredPlaced()) {
                    placeBlocksFromMovers(world, session.movers(), session.loweredBlocks());
                    session.loweredPlaced(true);
                }
                removeMovers(store, session.movers());
                ensureRuneTargetEntity(store, session);
                session.phase(OrangeBlobBlockRuntime.Phase.HOLDING_DOWN);
                broadcastToRunWorld(session.worldId(), "The extraction rune is exposed. Defend it until it stabilizes.");
            }
            return;
        }

        if (session.phase() == OrangeBlobBlockRuntime.Phase.HOLDING_DOWN) {
            tickHoldingDown(store, world, session, now);
            return;
        }

        if (session.phase() == OrangeBlobBlockRuntime.Phase.RETURNING_FOR_EXTRACTION) {
            tickReturningForExtraction(store, world, session, now);
            return;
        }

        if (session.phase() == OrangeBlobBlockRuntime.Phase.MOVING_UP) {
            updateMovers(store, session.movers(), now);
            tickIndependentRockMovement(store, world, session, now);
            if (now >= session.upEndAt()) {
                if (!session.sourcePlaced()) {
                    placeBlocksFromMovers(world, session.movers(), session.sourceBlocks());
                    placeBlock(world, new OrangeBlobBlockRuntime.ClusterBlock(
                            session.sourceRuneBlock().x(),
                            session.sourceRuneBlock().y(),
                            session.sourceRuneBlock().z(),
                            session.sourceRuneBlock().blockId(),
                            session.sourceRuneInitialRotation()
                    ));
                    session.sourcePlaced(true);
                }
                removeMovers(store, session.movers());
                if (!rocksStillInMotion(session)) {
                    session.phase(OrangeBlobBlockRuntime.Phase.COMPLETE);
                }
            }
        }
    }

    private static void tickHoldingDown(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
        tickIndependentRockMovement(store, world, session, now);

        NearbyPlayersResult nearbyPlayers = measureNearbyPlayers(session);
        if (nearbyPlayers.count() > 0) {
            session.lastNearbyPlayerAt(now);
        }

        if (session.config().pauseWhenNoPlayersNearby() && nearbyPlayers.count() <= 0) {
            session.holdEndAt(session.holdEndAt() + 50L);
        }

        ensureRuneTargetEntity(store, session);

        while (session.config().enemySpawnsEnabled()
                && now >= session.nextMobSpawnAt()
                && now < session.holdEndAt()
                && session.wavesSpawned() < session.config().maxWaves()) {
            spawnMobWave(store, session, now);
            session.wavesSpawned(session.wavesSpawned() + 1);
            session.nextMobSpawnAt(session.nextMobSpawnAt() + session.config().waveIntervalMs());
        }

        if (now >= session.nextRuneDamageAt()) {
            int attackers = applyRunePressure(store, session, nearbyPlayers.count());
            session.nextRuneDamageAt(now + session.config().runeDamageIntervalMs());
            announceHealthThresholds(session);
        }

        if (now >= session.nextDebugAt()) {
            logSessionState("tick", session,
                    "playersNearby=" + nearbyPlayers.count()
                            + " runeHealth=" + String.format(java.util.Locale.ROOT, "%.1f", session.runeHealth())
                            + " holdEndsInMs=" + Math.max(0L, session.holdEndAt() - now)
                            + " extractionReady=" + session.extractionReady()
                            + " autoLaunchInMs=" + Math.max(0L, session.extractionAutoLaunchAt() - now)
                            + " launchRequested=" + session.launchRequested()
                            + " dispatchStarted=" + session.extractionDispatchStarted()
                            + " dispatchSucceeded=" + session.extractionDispatchSucceeded()
                            + " wavesSpawned=" + session.wavesSpawned()
                            + " runeBodyValid=" + isValidRef(session.runeBodyRef())
                            + " runeProxyValid=" + isValidRef(session.runeAggroProxyRef()));
            session.nextDebugAt(now + DEBUG_INTERVAL_MS);
        }

        if (session.runeHealth() <= 0.0f) {
            logSessionState("rune-destroyed", session, "runeHealth=" + session.runeHealth());
            broadcastToRunWorld(session.worldId(), "The extraction rune was destroyed.");
            beginMoveUp(world, store, session, now, "rune destroyed");
            return;
        }

        if (session.config().resetWhenNoPlayersNearby()
                && !session.extractionReady()
                && nearbyPlayers.count() <= 0
                && now - session.lastNearbyPlayerAt() >= session.config().abandonmentGraceMs()) {
            logSessionState("abandoned", session,
                    "playersNearby=0 lastNearbyAgoMs=" + (now - session.lastNearbyPlayerAt()));
            broadcastToRunWorld(session.worldId(), "No one is defending the extraction island. The rune collapses.");
            beginMoveUp(world, store, session, now, "no players nearby");
            return;
        }

        if (session.extractionDispatchStarted()) {
            if (session.extractionDispatchSucceeded()) {
                logSessionState("dispatch-succeeded", session, "cleanup and complete");
                cleanupHoldPhase(store, world, session);
                session.phase(OrangeBlobBlockRuntime.Phase.COMPLETE);
                return;
            }
            if (session.extractionDispatchFailure() != null) {
                logSessionState("dispatch-failed", session, session.extractionDispatchFailure());
                broadcastToRunWorld(session.worldId(), "Extraction failed: " + session.extractionDispatchFailure());
                session.extractionDispatchStarted(false);
                session.extractionDispatchFailure(null);
                beginMoveUp(world, store, session, now, "dispatch failure");
            }
            return;
        }

        if (!session.extractionReady() && now >= session.holdEndAt()) {
            session.extractionReady(true);
            session.extractionAutoLaunchAt(now + session.config().readyExtractWindowMs());
            long autoExtractSeconds = Math.max(0L, Math.round(session.config().readyExtractWindowMs() / 1000.0d));
            broadcastToRunWorld(
                    session.worldId(),
                    autoExtractSeconds <= 0L
                            ? "The extraction rune is ready. Extraction is launching now."
                            : "The extraction rune is ready. Interact with it again to extract, or it will auto-extract in " + autoExtractSeconds + " seconds."
            );
            return;
        }

        if (session.extractionReady() && (session.launchRequested() || now >= session.extractionAutoLaunchAt())) {
            String reason = session.launchRequested() ? "player reactivated rune" : "auto extraction timeout";
            broadcastToRunWorld(session.worldId(), "The extraction island rises for departure.");
            beginReturnForExtraction(world, store, session, now, reason);
        }
    }

    private static void tickReturningForExtraction(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
        updateMovers(store, session.movers(), now);
        tickIndependentRockMovement(store, world, session, now);
        if (now < session.upEndAt()) {
            return;
        }
        if (!session.sourcePlaced()) {
            placeBlocksFromMovers(world, session.movers(), session.sourceBlocks());
            placeBlock(world, session.sourceRuneBlock());
            session.sourcePlaced(true);
        }
        removeMovers(store, session.movers());
        if (rocksStillInMotion(session)) {
            return;
        }
        if (session.extractionDispatchStarted()) {
            if (session.extractionDispatchSucceeded()) {
                logSessionState("dispatch-succeeded", session, "cleanup and complete");
                session.phase(OrangeBlobBlockRuntime.Phase.COMPLETE);
                return;
            }
            if (session.extractionDispatchFailure() != null) {
                logSessionState("dispatch-failed", session, session.extractionDispatchFailure());
                broadcastToRunWorld(session.worldId(), "Extraction failed: " + session.extractionDispatchFailure());
                session.phase(OrangeBlobBlockRuntime.Phase.COMPLETE);
            }
            return;
        }
        dispatchExtractionCompletion(session);
    }

    private static void dispatchExtractionCompletion(
            @Nonnull OrangeBlobBlockRuntime.Session session
    ) {
        long now = System.currentTimeMillis();
        if (!session.extractionWindowActive()) {
            session.extractionWindowActive(true);
            session.extractionWindowEndsAt(now + session.extractionWindowDurationMs());
            session.extractionWindowPlayerIds().clear();
            long durationSeconds = Math.max(1L, Math.round(session.extractionWindowDurationMs() / 1000.0d));
            broadcastToRunWorld(session.worldId(), "Extraction area active for " + durationSeconds + " second(s). Enter the zone now.");
        }

        for (PlayerRef playerRef : collectPlayersInsideExtractionCylinder(session)) {
            session.extractionWindowPlayerIds().add(playerRef.getUuid());
        }

        if (now < session.extractionWindowEndsAt()) {
            return;
        }

        List<PlayerRef> playersToExtract = resolvePlayersByIds(session.extractionWindowPlayerIds(), session.worldId());
        session.extractionWindowActive(false);
        session.extractionWindowPlayerIds().clear();

        if (playersToExtract.isEmpty()) {
            logSessionState("dispatch-no-candidates", session, "playersInCylinder=0");
            broadcastToRunWorld(session.worldId(), "No players in extraction zone. The rune goes dormant.");
            session.phase(OrangeBlobBlockRuntime.Phase.COMPLETE);
            return;
        }

        session.extractionDispatchStarted(true);
        logSessionState("dispatch-start", session, "playersInCylinder=" + playersToExtract.size());
        broadcastToRunWorld(session.worldId(), "Extracting all players in the cylinder zone.");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<String> failureReason = new AtomicReference<>(null);
        CompletableFuture<?>[] futures = playersToExtract.stream()
                .map(playerRef -> GameDoorInteractionHandler.completeActiveRunExtraction(playerRef)
                        .handle((result, throwable) -> {
                            if (throwable != null) {
                                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                                logSessionState("dispatch-throwable", session, reason == null ? "unknown throwable" : reason);
                                failureReason.compareAndSet(null, reason == null ? "unknown extraction error" : reason);
                                return null;
                            }
                            if (result == null || !result.success()) {
                                String reason = result == null ? "extraction result unavailable" : result.message();
                                logSessionState("dispatch-result-failure", session, reason);
                                failureReason.compareAndSet(null, reason);
                                return null;
                            }
                            logSessionState("dispatch-result-success", session, result.message());
                            successCount.incrementAndGet();
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                session.extractionDispatchFailure(reason == null ? "unknown extraction error" : reason);
                return;
            }
            if (successCount.get() > 0) {
                session.extractionDispatchSucceeded(true);
                return;
            }
            String reason = failureReason.get();
            session.extractionDispatchFailure(reason == null ? "no player on the island could be extracted" : reason);
        });
    }

    @Nonnull
    private static List<PlayerRef> collectPlayersInsideExtractionCylinder(@Nonnull OrangeBlobBlockRuntime.Session session) {
        ArrayList<PlayerRef> insidePlayers = new ArrayList<>();
        double centerX = session.loweredRuneBlock().x() + 0.5d;
        double centerY = session.loweredRuneBlock().y() + 0.5d;
        double centerZ = session.loweredRuneBlock().z() + 0.5d;
        double radiusSq = session.extractionRadiusBlocks() * session.extractionRadiusBlocks();
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getWorldUuid() == null || !playerRef.getWorldUuid().equals(session.worldId())) {
                continue;
            }
            Vector3d position = playerRef.getTransform().getPosition();
            double dx = position.getX() - centerX;
            double dz = position.getZ() - centerZ;
            double horizontalSq = (dx * dx) + (dz * dz);
            if (horizontalSq > radiusSq) {
                continue;
            }
            double dy = position.getY() - centerY;
            if (dy < session.extractionMinHeightOffset() || dy > session.extractionMaxHeightOffset()) {
                continue;
            }
            insidePlayers.add(playerRef);
        }
        return List.copyOf(insidePlayers);
    }

    @Nonnull
    private static List<PlayerRef> resolvePlayersByIds(@Nonnull java.util.Set<UUID> playerIds, @Nonnull UUID worldId) {
        ArrayList<PlayerRef> players = new ArrayList<>(playerIds.size());
        for (UUID playerId : playerIds) {
            if (playerId == null) {
                continue;
            }
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || playerRef.getWorldUuid() == null || !worldId.equals(playerRef.getWorldUuid())) {
                continue;
            }
            players.add(playerRef);
        }
        return List.copyOf(players);
    }

    private static void beginMoveUp(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now,
            @Nonnull String reason
    ) {
        logSessionState("begin-move-up", session, reason);
        cleanupHoldPhase(store, world, session);
        if (session.loweredPlaced()) {
            clearBlocks(world, session.loweredBlocks());
            session.loweredPlaced(false);
        }
        if (session.loweredRocksPlaced()) {
            removeMovers(store, session.rockMovers());
            session.loweredRocksPlaced(false);
        }
        session.beginMoveUp(now);
        session.signalRockMoveUp(now);
        ensureUpMovers(session);
        session.phase(OrangeBlobBlockRuntime.Phase.MOVING_UP);
    }

    private static void beginReturnForExtraction(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now,
            @Nonnull String reason
    ) {
        logSessionState("begin-return-for-extraction", session, reason);
        cleanupHoldPhase(store, world, session);
        if (session.loweredPlaced()) {
            clearBlocks(world, session.loweredBlocks());
            session.loweredPlaced(false);
        }
        if (session.loweredRocksPlaced()) {
            removeMovers(store, session.rockMovers());
            session.loweredRocksPlaced(false);
        }
        session.beginMoveUp(now);
        session.signalRockMoveUp(now);
        ensureUpMovers(session);
        session.phase(OrangeBlobBlockRuntime.Phase.RETURNING_FOR_EXTRACTION);
    }

    private static void cleanupHoldPhase(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session
    ) {
        removeSpawnedMobs(session);
        removeRuneTargetEntity(store, session);
        if (session.supportBlocksPlaced()) {
            OrangeBlobBlockManager.restoreSupportBlocks(world, session.supportBlocks());
            session.supportBlocksPlaced(false);
        }
    }

    private static NearbyPlayersResult measureNearbyPlayers(@Nonnull OrangeBlobBlockRuntime.Session session) {
        OrangeBlobBlockRuntime.ClusterBlock center = session.loweredRuneBlock();
        double centerX = center.x() + 0.5d;
        double centerZ = center.z() + 0.5d;
        int count = 0;
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getWorldUuid() == null || !playerRef.getWorldUuid().equals(session.worldId())) {
                continue;
            }
            Vector3d position = playerRef.getTransform().getPosition();
            double dx = position.getX() - centerX;
            double dz = position.getZ() - centerZ;
            double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));
            if (horizontalDistance <= session.config().defendRadiusBlocks()) {
                count++;
            }
        }
        return new NearbyPlayersResult(count);
    }

    private static boolean isPlayerStandingOnExtractionIsland(
            @Nonnull PlayerRef playerRef,
            @Nonnull OrangeBlobBlockRuntime.Session session
    ) {
        Vector3d position = playerRef.getTransform().getPosition();
        int footX = (int) Math.floor(position.getX());
        int footY = (int) Math.floor(position.getY() - 0.1d);
        int footZ = (int) Math.floor(position.getZ());
        for (OrangeBlobBlockRuntime.ClusterBlock block : session.loweredBlocks()) {
            if (block.x() == footX && block.z() == footZ && (block.y() == footY || block.y() == footY - 1)) {
                return true;
            }
        }
        return false;
    }

    private static int applyRunePressure(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            int nearbyPlayerCount
    ) {
        Ref<EntityStore> runeRef = session.runeAggroProxyRef();
        if (!isLivingEntityAlive(store, runeRef)) {
            logSessionState("rune-proxy-not-alive", session,
                    "runeProxyValid=" + isValidRef(runeRef));
            session.runeHealth(0.0f);
            return 0;
        }

        Vector3d runePos = new Vector3d(
                session.loweredRuneBlock().x() + 0.5d,
                session.loweredRuneBlock().y() + 0.5d,
                session.loweredRuneBlock().z() + 0.5d
        );
        int attackers = 0;

        for (Ref<EntityStore> mobRef : session.spawnedMobRefs()) {
            if (mobRef == null || !mobRef.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(mobRef, NPC);
            if (npc == null) {
                continue;
            }
            aimMobAtRune(store, session, mobRef, npc, runePos);
            if (isMobLockedOnRune(store, mobRef, runeRef)) {
                attackers++;
            }
        }

        float liveHealth = readCurrentHealth(store, runeRef);
        if (liveHealth >= 0.0f) {
            session.runeHealth(liveHealth);
        } else {
            logSessionState("rune-health-unavailable", session,
                    "keepingPreviousHealth=" + session.runeHealth());
        }
        return attackers;
    }

    private static void aimMobAtRune(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            @Nonnull Ref<EntityStore> mobRef,
            @Nonnull NPCEntity npc,
            @Nonnull Vector3d runePos
    ) {
        Ref<EntityStore> runeAggroProxyRef = session.runeAggroProxyRef();
        if (runeAggroProxyRef != null && runeAggroProxyRef.isValid()) {
            setMobTargetHostile(store, mobRef, npc, runeAggroProxyRef);
        } else {
            Ref<EntityStore> runeBodyRef = session.runeBodyRef();
            if (runeBodyRef != null && runeBodyRef.isValid()) {
                setMobTargetHostile(store, mobRef, npc, runeBodyRef);
            }
        }
        npc.saveLeashInformation(new Vector3d(runePos), new Vector3f(0.0f, 0.0f, 0.0f));
        store.putComponent(mobRef, NPC, npc);
    }

    private static void aimMobAtPlayer(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> mobRef,
            @Nonnull NPCEntity npc,
            @Nonnull Ref<EntityStore> playerTargetRef,
            @Nonnull PlayerRef playerRef
    ) {
        setMobTargetHostile(store, mobRef, npc, playerTargetRef);
        Vector3d playerPos = playerRef.getTransform().getPosition();
        npc.saveLeashInformation(new Vector3d(playerPos), new Vector3f(0.0f, 0.0f, 0.0f));
        store.putComponent(mobRef, NPC, npc);
    }

    private static void announceHealthThresholds(@Nonnull OrangeBlobBlockRuntime.Session session) {
        float maxHealth = Math.max(1.0f, session.config().runeMaxHealth());
        float ratio = session.runeHealth() / maxHealth;
        int tier = ratio <= 0.25f ? 1 : ratio <= 0.5f ? 2 : ratio <= 0.75f ? 3 : 4;
        if (tier >= session.lastAnnouncedHealthTier()) {
            return;
        }
        session.lastAnnouncedHealthTier(tier);
        if (tier == 3) {
            broadcastToRunWorld(session.worldId(), "The extraction rune is taking damage.");
        } else if (tier == 2) {
            broadcastToRunWorld(session.worldId(), "The extraction rune is unstable.");
        } else {
            broadcastToRunWorld(session.worldId(), "The extraction rune is close to breaking.");
        }
    }

    private static void setMobTargetHostile(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> mobRef,
            @Nonnull NPCEntity npc,
            @Nonnull Ref<EntityStore> runeTargetRef
    ) {
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        role.setMarkedTarget(MarkedEntitySupport.DEFAULT_TARGET_SLOT, runeTargetRef);
        WorldSupport support = role.getWorldSupport();
        if (support != null) {
            applyHighDetectionRangeIfAvailable(support);
            try {
                support.overrideAttitude(runeTargetRef, Attitude.HOSTILE, 60.0 * 60.0);
            } catch (Throwable ignored) {
            }
            support.requestNewPath();
        }
        for (String slot : RUNE_TARGET_SLOTS) {
            try {
                npc.onFlockSetTarget(slot, runeTargetRef);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isMobLockedOnRune(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> mobRef,
            @Nonnull Ref<EntityStore> runeRef
    ) {
        NPCEntity npc = store.getComponent(mobRef, NPC);
        if (npc == null || npc.getRole() == null) {
            return false;
        }
        Ref<EntityStore> current = npc.getRole()
                .getMarkedEntitySupport()
                .getMarkedEntityRef(MarkedEntitySupport.DEFAULT_TARGET_SLOT);
        return current != null && current.isValid() && current.equals(runeRef);
    }

    @Nullable
    private static Ref<EntityStore> resolvePlayerCombatTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            @Nullable PlayerRef playerRef
    ) {
        if (playerRef == null || playerRef.getWorldUuid() == null || !playerRef.getWorldUuid().equals(session.worldId())) {
            return null;
        }
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid() || playerEntityRef.getStore() != store) {
            return null;
        }
        return playerEntityRef;
    }

    private static boolean isLivingEntityAlive(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref
    ) {
        return ref != null
                && ref.isValid()
                && ref.getStore() == store
                && store.getComponent(ref, DeathComponent.getComponentType()) == null;
    }

    private static float readCurrentHealth(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref
    ) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return -1.0f;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return -1.0f;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0 || stats.get(healthIndex) == null) {
            return -1.0f;
        }
        return stats.get(healthIndex).get();
    }

    private static void logSessionState(
            @Nonnull String event,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            @Nonnull String detail
    ) {
        System.out.println("[OrangeBlobDebug] event=" + event
                + " session=" + session.id()
                + " world=" + session.worldId()
                + " phase=" + session.phase()
                + " runeHealth=" + String.format(java.util.Locale.ROOT, "%.1f", session.runeHealth())
                + " detail=" + detail);
    }

    private static boolean isValidRef(@Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid();
    }

    @Nonnull
    private static String formatVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return "null";
        }
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)",
                vector.getX(), vector.getY(), vector.getZ());
    }

    private record NearbyPlayersResult(int count) {
    }

    private static void ensureDownMovers(@Nonnull OrangeBlobBlockRuntime.Session session) {
        if (!session.movers().isEmpty()) {
            return;
        }
        for (int i = 0; i < session.sourceBlocks().size(); i++) {
            OrangeBlobBlockRuntime.ClusterBlock source = session.sourceBlocks().get(i);
            OrangeBlobBlockRuntime.ClusterBlock target = session.loweredBlocks().get(i);
            long downStartAt = session.downStartAt();
            long downEndAt = session.downStartAt() + session.moveDurationMs();
            if (OrangeBlobBlockManager.isDelayedRockBlockId(source.blockId())) {
                downStartAt += 2000L;
                downEndAt += 2000L;
            }
            session.movers().add(new OrangeBlobBlockRuntime.Mover(
                    source.blockId(),
                    source.rotation(),
                    source.x() + 0.5d,
                    source.y(),
                    source.z() + 0.5d,
                    target.x() + 0.5d,
                    target.y(),
                    target.z() + 0.5d,
                    downStartAt,
                    downEndAt
            ));
        }
        session.movers().add(new OrangeBlobBlockRuntime.Mover(
                session.sourceRuneBlock().blockId(),
                session.sourceRuneInitialRotation(),
                session.sourceRuneBlock().x() + 0.5d,
                session.sourceRuneBlock().y(),
                session.sourceRuneBlock().z() + 0.5d,
                session.loweredRuneBlock().x() + 0.5d,
                session.loweredRuneBlock().y(),
                session.loweredRuneBlock().z() + 0.5d,
                session.downStartAt(),
                session.downEndAt()
        ));
    }

    private static void ensureUpMovers(@Nonnull OrangeBlobBlockRuntime.Session session) {
        if (!session.movers().isEmpty()) {
            return;
        }
        for (int i = 0; i < session.loweredBlocks().size(); i++) {
            OrangeBlobBlockRuntime.ClusterBlock source = session.loweredBlocks().get(i);
            OrangeBlobBlockRuntime.ClusterBlock target = session.sourceBlocks().get(i);
            long upStartAt = session.upStartAt();
            long upEndAt = session.upStartAt() + session.moveDurationMs();
            if (OrangeBlobBlockManager.isDelayedRockBlockId(source.blockId())) {
                upStartAt += 2000L;
                upEndAt += 2000L;
            }
            session.movers().add(new OrangeBlobBlockRuntime.Mover(
                    source.blockId(),
                    source.rotation(),
                    source.x() + 0.5d,
                    source.y(),
                    source.z() + 0.5d,
                    target.x() + 0.5d,
                    target.y(),
                    target.z() + 0.5d,
                    upStartAt,
                    upEndAt
            ));
        }
        session.movers().add(new OrangeBlobBlockRuntime.Mover(
                session.loweredRuneBlock().blockId(),
                session.sourceRuneInitialRotation(),
                session.loweredRuneBlock().x() + 0.5d,
                session.loweredRuneBlock().y(),
                session.loweredRuneBlock().z() + 0.5d,
                session.sourceRuneBlock().x() + 0.5d,
                session.sourceRuneBlock().y(),
                session.sourceRuneBlock().z() + 0.5d,
                session.upStartAt(),
                session.upEndAt()
        ));
    }

    private static void ensureDownRockMovers(@Nonnull OrangeBlobBlockRuntime.Session session, long downStartAt) {
        if (!session.rockMovers().isEmpty()) {
            return;
        }
        if (session.sourceDetachedRocks().isEmpty()) {
            session.rockPhase(OrangeBlobBlockRuntime.RockPhase.IDLE_DOWN);
            return;
        }
        for (int i = 0; i < session.sourceDetachedRocks().size(); i++) {
            OrangeBlobBlockRuntime.ClusterBlock source = session.sourceDetachedRocks().get(i);
            OrangeBlobBlockRuntime.ClusterBlock target = session.loweredDetachedRocks().get(i);
            long delayedStartAt = downStartAt + session.rockTailDelayMs();
            long downEndAt = delayedStartAt + session.moveDurationMs();
            session.rockMovers().add(new OrangeBlobBlockRuntime.Mover(
                    source.blockId(),
                    source.rotation(),
                    source.x() + 0.5d,
                    source.y(),
                    source.z() + 0.5d,
                    target.x() + 0.5d,
                    target.y(),
                    target.z() + 0.5d,
                    delayedStartAt,
                    downEndAt
            ));
            session.rockDownEndAt(downEndAt);
        }
        session.rockPhase(OrangeBlobBlockRuntime.RockPhase.MOVING_DOWN);
    }

    private static void ensureUpRockMovers(@Nonnull OrangeBlobBlockRuntime.Session session, long upStartAt) {
        if (!session.rockMovers().isEmpty()) {
            return;
        }
        if (session.loweredDetachedRocks().isEmpty()) {
            session.rockPhase(OrangeBlobBlockRuntime.RockPhase.IDLE_UP);
            return;
        }
        for (int i = 0; i < session.loweredDetachedRocks().size(); i++) {
            OrangeBlobBlockRuntime.ClusterBlock source = session.loweredDetachedRocks().get(i);
            OrangeBlobBlockRuntime.ClusterBlock target = session.sourceDetachedRocks().get(i);
            long delayedStartAt = upStartAt + session.rockTailDelayMs();
            long upEndAt = delayedStartAt + session.moveDurationMs();
            session.rockMovers().add(new OrangeBlobBlockRuntime.Mover(
                    source.blockId(),
                    source.rotation(),
                    source.x() + 0.5d,
                    source.y(),
                    source.z() + 0.5d,
                    target.x() + 0.5d,
                    target.y(),
                    target.z() + 0.5d,
                    delayedStartAt,
                    upEndAt
            ));
            session.rockUpEndAt(upEndAt);
        }
        session.rockPhase(OrangeBlobBlockRuntime.RockPhase.MOVING_UP);
    }

    private static void tickIndependentRockMovement(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
        if (session.rockDownSignalPending() && now >= session.rockDownSignalAt()) {
            if (session.rockMovers().isEmpty() && session.rockPhase() != OrangeBlobBlockRuntime.RockPhase.MOVING_UP) {
                session.rockDownSignalPending(false);
                ensureDownRockMovers(session, now);
            }
        }
        if (session.rockUpSignalPending() && now >= session.rockUpSignalAt()) {
            if (session.rockMovers().isEmpty() && session.rockPhase() != OrangeBlobBlockRuntime.RockPhase.MOVING_DOWN) {
                session.rockUpSignalPending(false);
                ensureUpRockMovers(session, now);
            }
        }

        updateMovers(store, session.rockMovers(), now);

        if (session.rockPhase() == OrangeBlobBlockRuntime.RockPhase.MOVING_DOWN && now >= session.rockDownEndAt()) {
            placeBlocksFromMovers(world, session.rockMovers(), session.loweredDetachedRocks());
            removeMovers(store, session.rockMovers());
            session.loweredRocksPlaced(true);
            session.sourceRocksPlaced(false);
            session.rockPhase(OrangeBlobBlockRuntime.RockPhase.IDLE_DOWN);
        } else if (session.rockPhase() == OrangeBlobBlockRuntime.RockPhase.MOVING_UP && now >= session.rockUpEndAt()) {
            placeBlocksFromMovers(world, session.rockMovers(), session.sourceDetachedRocks());
            removeMovers(store, session.rockMovers());
            session.sourceRocksPlaced(true);
            session.loweredRocksPlaced(false);
            session.rockPhase(OrangeBlobBlockRuntime.RockPhase.IDLE_UP);
        }
    }

    private static boolean rocksStillInMotion(@Nonnull OrangeBlobBlockRuntime.Session session) {
        return session.rockDownSignalPending()
                || session.rockUpSignalPending()
                || !session.rockMovers().isEmpty()
                || session.rockPhase() == OrangeBlobBlockRuntime.RockPhase.MOVING_DOWN
                || session.rockPhase() == OrangeBlobBlockRuntime.RockPhase.MOVING_UP;
    }

    private static void placeBlocksFromMovers(
            @Nonnull World world,
            @Nonnull List<OrangeBlobBlockRuntime.Mover> movers,
            @Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> targetBlocks
    ) {
        int count = Math.min(movers.size(), targetBlocks.size());
        for (int i = 0; i < count; i++) {
            OrangeBlobBlockRuntime.Mover mover = movers.get(i);
            OrangeBlobBlockRuntime.ClusterBlock target = targetBlocks.get(i);
            int finalRotation = forcedRotation(mover.rotation());
            placeRotatedBlock(world, target.x(), target.y(), target.z(), target.blockId(), finalRotation);
        }
        for (int i = count; i < targetBlocks.size(); i++) {
            OrangeBlobBlockRuntime.ClusterBlock target = targetBlocks.get(i);
            placeRotatedBlock(world, target.x(), target.y(), target.z(), target.blockId(), target.rotation());
        }
    }

    private static int forcedRotation(int rotation) {
        return FORCE_TEST_ROTATION_ENABLED ? FORCE_TEST_ROTATION_INDEX : rotation;
    }

    private static boolean skipRotationForBlock(@Nullable String blockId) {
        return OrangeBlobBlockManager.BLOCK_ID.equals(blockId)
                || OrangeBlobBlockManager.ACTIVE_BLOCK_ID.equals(blockId);
    }

    private static int effectiveRotation(@Nullable String blockId, int rotationIndex) {
        if (skipRotationForBlock(blockId)) {
            return 0;
        }
        return forcedRotation(rotationIndex);
    }

    private static void updateMovers(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<OrangeBlobBlockRuntime.Mover> movers,
            long now
    ) {
        for (OrangeBlobBlockRuntime.Mover mover : movers) {
            if (now < mover.startAt()) {
                continue;
            }
            Ref<EntityStore> moverRef = mover.moverRef();
            if (moverRef == null || !moverRef.isValid()) {
                World world = store.getExternalData().getWorld();
                world.setBlock((int) Math.floor(mover.startX()), (int) Math.floor(mover.startY()), (int) Math.floor(mover.startZ()), EMPTY_BLOCK_ID);
                moverRef = spawnStaticBlockEntity(
                        store,
                        mover.blockId(),
                        mover.rotation(),
                        mover.startX(),
                        mover.startY(),
                        mover.startZ(),
                        2.0f,
                        true
                );
                mover.moverRef(moverRef);
            }
            if (moverRef == null || !moverRef.isValid()) {
                continue;
            }

            TransformComponent transform = store.getComponent(moverRef, TRANSFORM);
            if (transform == null) {
                continue;
            }

            double alpha = normalizedProgress(now, mover.startAt(), mover.endAt());
            double eased = easeInOut(alpha);
            transform.teleportPosition(new Vector3d(
                    lerp(mover.startX(), mover.endX(), eased),
                    lerp(mover.startY(), mover.endY(), eased),
                    lerp(mover.startZ(), mover.endZ(), eased)
            ));
            transform.markChunkDirty(store);
        }
    }

    private static void removeMovers(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<OrangeBlobBlockRuntime.Mover> movers
    ) {
        for (OrangeBlobBlockRuntime.Mover mover : movers) {
            Ref<EntityStore> moverRef = mover.moverRef();
            if (moverRef != null && moverRef.isValid()) {
                store.removeEntity(moverRef, RemoveReason.REMOVE);
            }
            mover.moverRef(null);
        }
        movers.clear();
    }

    private static void clearBlocks(@Nonnull World world, @Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> blocks) {
        for (OrangeBlobBlockRuntime.ClusterBlock block : blocks) {
            world.setBlock(block.x(), block.y(), block.z(), EMPTY_BLOCK_ID);
        }
    }

    private static void clearBlock(@Nonnull World world, @Nonnull OrangeBlobBlockRuntime.ClusterBlock block) {
        world.setBlock(block.x(), block.y(), block.z(), EMPTY_BLOCK_ID);
    }

    private static void placeBlocks(@Nonnull World world, @Nonnull List<OrangeBlobBlockRuntime.ClusterBlock> blocks) {
        for (OrangeBlobBlockRuntime.ClusterBlock block : blocks) {
            placeRotatedBlock(world, block.x(), block.y(), block.z(), block.blockId(), forcedRotation(block.rotation()));
        }
    }

    private static void placeBlock(@Nonnull World world, @Nonnull OrangeBlobBlockRuntime.ClusterBlock block) {
        placeRotatedBlock(world, block.x(), block.y(), block.z(), block.blockId(), forcedRotation(block.rotation()));
    }

    private static void placeRotatedBlock(
            @Nonnull World world,
            int x,
            int y,
            int z,
            @Nonnull String blockId,
            int rotationIndex
    ) {
        int forcedIndex = effectiveRotation(blockId, rotationIndex);
        RotationTuple tuple = RotationTuple.get(forcedIndex);
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk worldChunk = world.getChunk(chunkIndex);
        if (worldChunk != null) {
            boolean placed = worldChunk.placeBlock(x, y, z, blockId, tuple, 0, true);
            if (placed) {
                return;
            }
        }
        world.setBlock(x, y, z, blockId, forcedIndex);
    }

    private static void spawnMobWave(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();

        World world = store.getExternalData().getWorld();
        OrangeBlobBlockRuntime.ClusterBlock center = session.loweredRuneBlock();
        double centerX = center.x() + 0.5d;
        double centerY = center.y();
        double centerZ = center.z() + 0.5d;
        Vector3d runePos = new Vector3d(
                session.loweredRuneBlock().x() + 0.5d,
                session.loweredRuneBlock().y() + 0.5d,
                session.loweredRuneBlock().z() + 0.5d
        );
        Random random = new Random(session.id().getLeastSignificantBits() ^ now);
        List<Vector3d> spawnPositions = new ArrayList<>(session.config().mobsPerWave());
        for (int i = 0; i < session.config().mobsPerWave(); i++) {
            Vector3d candidate = findDynamicSpawnPosition(world, session, centerX, centerY, centerZ, random);
            if (candidate == null) {
                continue;
            }
            spawnPositions.add(candidate);
            session.recentMobSpawnPositions().add(candidate);
            while (session.recentMobSpawnPositions().size() > MOB_SPAWN_HISTORY_LIMIT) {
                session.recentMobSpawnPositions().remove(0);
            }
        }

        for (Vector3d spawnPos : spawnPositions) {
            SpawnRoleProfile profile = chooseSpawnRoleProfile(random);
            int roleIndex = npcPlugin.getIndex(profile.roleName());
            BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
            if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
                roleIndex = npcPlugin.getIndex(MOB_ROLE);
            }
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                    store,
                    roleIndex,
                    spawnPos,
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    null,
                    null
            );
            if (spawned == null || spawned.first() == null || !spawned.first().isValid()) {
                return;
            }
            session.spawnedMobRefs().add(spawned.first());
            if (spawned.second() != null) {
                aimMobAtRune(store, session, spawned.first(), spawned.second(), runePos);
            }
        }
    }

    @Nullable
    private static Vector3d findDynamicSpawnPosition(
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            double centerX,
            double centerY,
            double centerZ,
            @Nonnull Random random
    ) {
        SpawnRoleProfile profile = chooseSpawnRoleProfile(random);
        for (int attempt = 0; attempt < MOB_SPAWN_MAX_ATTEMPTS_PER_MOB; attempt++) {
            double angle = random.nextDouble() * (Math.PI * 2.0d);
            double minRadius = session.enemySpawnMinRadiusBlocks();
            double maxRadius = session.enemySpawnMaxRadiusBlocks();
            double range = Math.max(0.0d, maxRadius - minRadius);
            double rolePercent = Math.max(0.0d, Math.min(1.0d, profile.distanceRangePercent()));
            double jitter = (random.nextDouble() * 0.20d) - 0.10d;
            double effectivePercent = Math.max(0.0d, Math.min(1.0d, rolePercent + jitter));
            double radius = minRadius + (range * effectivePercent);
            int minYOffset = (int) Math.floor(session.enemySpawnMinHeightOffset());
            int maxYOffset = (int) Math.ceil(session.enemySpawnMaxHeightOffset());
            int yOffset = minYOffset + random.nextInt(Math.max(1, (maxYOffset - minYOffset) + 1));
            double x = centerX + (Math.cos(angle) * radius);
            double y = centerY + yOffset;
            double z = centerZ + (Math.sin(angle) * radius);
            if (isTooCloseToRecentSpawns(session, x, y, z)) {
                continue;
            }
            if (!isSpawnVolumeClear(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
                continue;
            }
            return new Vector3d(x, y, z);
        }
        return null;
    }

    private static boolean isTooCloseToRecentSpawns(
            @Nonnull OrangeBlobBlockRuntime.Session session,
            double x,
            double y,
            double z
    ) {
        double minGapSq = MOB_SPAWN_MIN_GAP_BLOCKS * MOB_SPAWN_MIN_GAP_BLOCKS;
        for (Vector3d recent : session.recentMobSpawnPositions()) {
            double dx = recent.getX() - x;
            double dy = recent.getY() - y;
            double dz = recent.getZ() - z;
            if ((dx * dx) + (dy * dy) + (dz * dz) < minGapSq) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpawnVolumeClear(@Nonnull World world, int x, int y, int z) {
        if (OrangeBlobBlockManager.isEmpty(world.getBlockType(x, y - 1, z))) {
            return false;
        }
        for (int dy = 0; dy <= 2; dy++) {
            int checkY = y + dy;
            if (!OrangeBlobBlockManager.isEmpty(world.getBlockType(x, checkY, z))) {
                return false;
            }
            if (!OrangeBlobBlockManager.isEmpty(world.getBlockType(x + 1, checkY, z))
                    || !OrangeBlobBlockManager.isEmpty(world.getBlockType(x - 1, checkY, z))
                    || !OrangeBlobBlockManager.isEmpty(world.getBlockType(x, checkY, z + 1))
                    || !OrangeBlobBlockManager.isEmpty(world.getBlockType(x, checkY, z - 1))) {
                return false;
            }
        }
        return true;
    }

    private static void ensureRuneTargetEntity(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session
    ) {
        if (session.runeBodyRef() == null || !session.runeBodyRef().isValid()) {
            Vector3d runePos = new Vector3d(
                    session.loweredRuneBlock().x() + 0.5d,
                    session.loweredRuneBlock().y() + 0.5d,
                    session.loweredRuneBlock().z() + 0.5d
            );
            Ref<EntityStore> spawnedBody = spawnStaticBlockEntity(
                    store,
                    session.loweredRuneBlock().blockId(),
                    session.sourceRuneInitialRotation(),
                    runePos.getX(),
                    runePos.getY(),
                    runePos.getZ(),
                    2.0f,
                    true
            );
            if (spawnedBody != null && spawnedBody.isValid()) {
                store.putComponent(spawnedBody, Interactable.getComponentType(), Interactable.INSTANCE);
            }
            session.runeBodyRef(spawnedBody);
        }
        if (session.runeAggroProxyRef() == null || !session.runeAggroProxyRef().isValid()) {
            Ref<EntityStore> spawnedProxy = spawnRuneAggroProxy(store, session);
            session.runeAggroProxyRef(spawnedProxy);
        }
    }

    private static void removeRuneTargetEntity(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session
    ) {
        Ref<EntityStore> runeBodyRef = session.runeBodyRef();
        if (runeBodyRef != null && runeBodyRef.isValid()) {
            store.removeEntity(runeBodyRef, RemoveReason.REMOVE);
        }
        session.runeBodyRef(null);
        Ref<EntityStore> runeAggroProxyRef = session.runeAggroProxyRef();
        if (runeAggroProxyRef != null && runeAggroProxyRef.isValid()) {
            store.removeEntity(runeAggroProxyRef, RemoveReason.REMOVE);
        }
        session.runeAggroProxyRef(null);
    }

    private static void removeSpawnedMobs(@Nonnull OrangeBlobBlockRuntime.Session session) {
        for (Ref<EntityStore> mobRef : session.spawnedMobRefs()) {
            if (mobRef != null && mobRef.isValid()) {
                Store<EntityStore> mobStore = mobRef.getStore();
                if (mobStore != null) {
                    applyExtractionEndDamage(mobStore, mobRef);
                }
            }
        }
        session.spawnedMobRefs().clear();
    }

    private static void applyExtractionEndDamage(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> mobRef
    ) {
        final float extractionEndDamage = 200.0f;
        AtomicBoolean appliedViaDamageSystem = new AtomicBoolean(false);
        store.forEachChunk(NPC, (chunk, buffer) -> {
            if (appliedViaDamageSystem.get()) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid() || !ref.equals(mobRef)) {
                    continue;
                }
                Damage damage = new Damage(
                        Damage.NULL_SOURCE,
                        resolveExtractionDamageCauseIndexSafe(),
                        extractionEndDamage
                );
                DamageSystems.executeDamage(i, chunk, buffer, damage);
                appliedViaDamageSystem.set(true);
                return;
            }
        });
        if (appliedViaDamageSystem.get()) {
            return;
        }
        EntityStatMap stats = store.getComponent(mobRef, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        int healthIndex = DefaultEntityStatTypes.getHealth();
        if (healthIndex < 0 || stats.get(healthIndex) == null) {
            return;
        }
        float current = stats.get(healthIndex).get();
        if (current <= 0.0f) {
            return;
        }
        EntityStatMap updated = stats.clone();
        updated.addStatValue(healthIndex, -extractionEndDamage);
        store.putComponent(mobRef, EntityStatMap.getComponentType(), updated);
    }

    private static int resolveExtractionDamageCauseIndexSafe() {
        if (extractionDamageCauseIndex != Integer.MIN_VALUE) {
            return extractionDamageCauseIndex;
        }
        try {
            extractionDamageCauseIndex = DamageCause.getAssetMap().getIndex("Command");
        } catch (Throwable ignored) {
            extractionDamageCauseIndex = 0;
        }
        return extractionDamageCauseIndex;
    }

    private static void applyHighDetectionRangeIfAvailable(@Nonnull WorldSupport support) {
        invokeDetectionSetter(support, "setDetectionRange", 50.0d);
        invokeDetectionSetter(support, "setAggroRange", 50.0d);
        invokeDetectionSetter(support, "setDetectionRadius", 50.0d);
        invokeDetectionSetter(support, "setChaseRange", 50.0d);
    }

    private static void invokeDetectionSetter(
            @Nonnull Object target,
            @Nonnull String methodName,
            double value
    ) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName, double.class);
            method.invoke(target, value);
            return;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName, float.class);
            method.invoke(target, (float) value);
            return;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName, int.class);
            method.invoke(target, (int) Math.round(value));
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static Ref<EntityStore> spawnRuneAggroProxy(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(RUNE_OBJECTIVE_ROLE);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleIndex < 0 || roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            logSessionState("spawn-rune-proxy-failed", session,
                    "roleIndex=" + roleIndex + " roleInfoPresent=" + (roleInfo != null));
            return null;
        }

        Vector3d runePos = new Vector3d(
                session.loweredRuneBlock().x() + 0.5d,
                session.loweredRuneBlock().y() + 0.5d,
                session.loweredRuneBlock().z() + 0.5d
        );
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                store,
                roleIndex,
                runePos,
                new Vector3f(0.0f, 0.0f, 0.0f),
                null,
                null
        );
        if (spawned == null || spawned.first() == null || !spawned.first().isValid()) {
            logSessionState("spawn-rune-proxy-failed", session,
                    "spawn returned invalid ref at pos=" + formatVector(runePos));
            return null;
        }

        store.putComponent(spawned.first(), Interactable.getComponentType(), Interactable.INSTANCE);

        TransformComponent transform = store.getComponent(spawned.first(), TRANSFORM);
        if (transform != null) {
            transform.teleportPosition(runePos);
            transform.markChunkDirty(store);
        }
        logSessionState("spawn-rune-proxy", session,
                "proxyValid=true pos=" + formatVector(runePos));
        return spawned.first();
    }

    @Nullable
    private static PlayerRef findPlayerRef(@Nonnull UUID playerId) {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef != null && playerId.equals(playerRef.getUuid())) {
                return playerRef;
            }
        }
        return null;
    }

    private static void broadcastToRunWorld(@Nonnull UUID worldId, @Nonnull String message) {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || playerRef.getWorldUuid() == null || !worldId.equals(playerRef.getWorldUuid())) {
                continue;
            }
            playerRef.sendMessage(Message.raw(message));
        }
    }

    @Nullable
    private static Ref<EntityStore> spawnStaticBlockEntity(
            @Nonnull Store<EntityStore> store,
            @Nonnull String blockId,
            int rotation,
            double x,
            double y,
            double z,
            float scale,
            boolean hardCollision
    ) {
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(((EntityStore) store.getExternalData()).takeNextNetworkId()));
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(blockId));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(x, y, z), Vector3f.FORWARD));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);

        if (hardCollision) {
            try {
                HitboxCollisionConfig config = HitboxCollisionConfig.getAssetMap().getAsset("HardCollision");
                if (config != null) {
                    holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(config));
                }
            } catch (Exception ignored) {
            }
        }

        Vector3f appliedRotation = applyRotation(holder, blockId, effectiveRotation(blockId, rotation));
        holder.ensureComponent(UUIDComponent.getComponentType());
        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        TransformComponent transform = store.getComponent(ref, TRANSFORM);
        if (transform != null) {
            transform.teleportRotation(new Vector3f(appliedRotation));
            transform.markChunkDirty(store);
        }

        BlockEntity blockEntity = store.getComponent(ref, BlockEntity.getComponentType());
        if (blockEntity != null) {
            blockEntity.initPhysics(new BoundingBox(
                    com.hypixel.hytale.math.shape.Box.centeredCube(new Vector3d(0.0d, 0.5d, 0.0d), 0.5d)
            ));
            blockEntity.getSimplePhysicsProvider().setResting(true);
        }
        return ref;
    }

    @Nonnull
    private static boolean isRuneBlockId(@Nullable String blockId) {
        return OrangeBlobBlockManager.RUNE_BLOCK_ID.equals(blockId)
                || OrangeBlobBlockManager.ACTIVE_RUNE_BLOCK_ID.equals(blockId);
    }

    private static Vector3f applyRotation(@Nonnull Holder<EntityStore> holder, @Nullable String blockId, int rotation) {
        Vector3f rot = new Vector3f(0.0f, 0.0f, 0.0f);
        try {
            RotationTuple tuple = RotationTuple.get(forcedRotation(rotation));
            Rotation yaw = tuple.yaw();
            Rotation pitch = tuple.pitch();
            Rotation roll = tuple.roll();
            float sign = isRuneBlockId(blockId) ? 1.0f : -1.0f;
            rot.setYaw((float) (sign * yaw.getRadians()));
            rot.setPitch((float) (sign * pitch.getRadians()));
            rot.setRoll((float) (sign * roll.getRadians()));
            holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rot));
        } catch (Exception ignored) {
        }
        return rot;
    }

    private static SpawnRoleProfile chooseSpawnRoleProfile(@Nonnull Random random) {
        int totalWeight = 0;
        for (SpawnRoleProfile profile : SPAWN_ROLE_PROFILES) {
            totalWeight += Math.max(0, profile.weight());
        }
        if (totalWeight <= 0) {
            return SPAWN_ROLE_PROFILES.get(0);
        }
        int roll = random.nextInt(totalWeight);
        int cursor = 0;
        for (SpawnRoleProfile profile : SPAWN_ROLE_PROFILES) {
            cursor += Math.max(0, profile.weight());
            if (roll < cursor) {
                return profile;
            }
        }
        return SPAWN_ROLE_PROFILES.get(0);
    }

    private record SpawnRoleProfile(
            @Nonnull String roleName,
            int weight,
            double distanceRangePercent
    ) {
    }

    private static double normalizedProgress(long now, long startAt, long endAt) {
        if (endAt <= startAt) {
            return 1.0d;
        }
        if (now <= startAt) {
            return 0.0d;
        }
        if (now >= endAt) {
            return 1.0d;
        }
        return (double) (now - startAt) / (double) (endAt - startAt);
    }

    private static double easeInOut(double alpha) {
        if (alpha <= 0.0d) {
            return 0.0d;
        }
        if (alpha >= 1.0d) {
            return 1.0d;
        }
        return alpha * alpha * (3.0d - (2.0d * alpha));
    }

    private static double lerp(double start, double end, double alpha) {
        return start + ((end - start) * alpha);
    }
}