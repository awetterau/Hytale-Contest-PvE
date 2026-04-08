package dev.hytalemodding.blob;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
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

public final class OrangeBlobBlockSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final String EMPTY_BLOCK_ID = "Empty";
    private static final String MOB_ROLE = "Blight_Beast";
    private static final String RUNE_OBJECTIVE_ROLE = "Extraction_Rune_Objective";
    private static final long DEBUG_INTERVAL_MS = 1000L;
    private static final Vector3d[] MOB_OFFSETS = new Vector3d[]{
            new Vector3d(20.0d, 0.0d, 0.0d),
            new Vector3d(-20.0d, 0.0d, 0.0d),
            new Vector3d(0.0d, 0.0d, 20.0d),
            new Vector3d(0.0d, 0.0d, -20.0d)
    };
    private static final String[] RUNE_TARGET_SLOTS = new String[]{"target", "Target", "CombatTarget"};

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
            updateMovers(store, session.movers(), now);
            if (now >= session.downEndAt()) {
                removeMovers(store, session.movers());
                if (!session.loweredPlaced()) {
                    placeBlocks(world, session.loweredBlocks());
                    session.loweredPlaced(true);
                }
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
            if (now >= session.upEndAt()) {
                removeMovers(store, session.movers());
                if (!session.sourcePlaced()) {
                    placeBlocks(world, session.sourceBlocks());
                    placeBlock(world, session.sourceRuneBlock());
                    session.sourcePlaced(true);
                }
                session.phase(OrangeBlobBlockRuntime.Phase.COMPLETE);
            }
        }
    }

    private static void tickHoldingDown(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
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
            broadcastToRunWorld(
                    session.worldId(),
                    "The extraction rune is ready. Interact with it again to extract, or it will auto-extract in 60 seconds."
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
        if (now < session.upEndAt()) {
            return;
        }
        removeMovers(store, session.movers());
        if (!session.sourcePlaced()) {
            placeBlocks(world, session.sourceBlocks());
            placeBlock(world, session.sourceRuneBlock());
            session.sourcePlaced(true);
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
        dispatchExtractionCompletion(session, false);
    }

    private static void dispatchExtractionCompletion(
            @Nonnull OrangeBlobBlockRuntime.Session session,
            boolean requirePlayerStandingOnIsland
    ) {
        PlayerRef playerRef = findPlayerRef(session.activatingPlayerId());
        if (playerRef == null) {
            logSessionState("dispatch-no-player", session, "activating player ref unavailable");
            session.extractionDispatchStarted(true);
            session.extractionDispatchFailure("activating player is no longer available");
            return;
        }
        if (requirePlayerStandingOnIsland && !isPlayerStandingOnExtractionIsland(playerRef, session)) {
            logSessionState("dispatch-waiting-for-stand", session,
                    "playerPos=" + formatVector(playerRef.getTransform().getPosition()));
            return;
        }
        session.extractionDispatchStarted(true);
        logSessionState("dispatch-start", session,
                "playerPos=" + formatVector(playerRef.getTransform().getPosition()));
        broadcastToRunWorld(session.worldId(), "Extraction complete. Returning to base.");
        GameDoorInteractionHandler.completeActiveRunExtraction(playerRef).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                logSessionState("dispatch-throwable", session, reason == null ? "unknown throwable" : reason);
                session.extractionDispatchFailure(reason == null ? "unknown extraction error" : reason);
                return;
            }
            if (result == null || !result.success()) {
                logSessionState("dispatch-result-failure", session,
                        result == null ? "result unavailable" : result.message());
                session.extractionDispatchFailure(result == null ? "extraction result unavailable" : result.message());
                return;
            }
            logSessionState("dispatch-result-success", session, result.message());
            session.extractionDispatchSucceeded(true);
        });
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
        session.beginMoveUp(now);
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
        session.beginMoveUp(now);
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
        PlayerRef playerRef = findPlayerRef(session.activatingPlayerId());
        Ref<EntityStore> playerTargetRef = resolvePlayerCombatTarget(store, session, playerRef);
        boolean splitTargets = nearbyPlayerCount > 0 && playerRef != null && playerTargetRef != null && playerTargetRef.isValid();
        int attackers = 0;
        int livingMobIndex = 0;

        for (Ref<EntityStore> mobRef : session.spawnedMobRefs()) {
            if (mobRef == null || !mobRef.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(mobRef, NPC);
            if (npc == null) {
                continue;
            }
            if (splitTargets && livingMobIndex % 2 == 0) {
                aimMobAtPlayer(store, mobRef, npc, playerTargetRef, playerRef);
            } else {
                aimMobAtRune(store, session, mobRef, npc, runePos);
            }
            if (isMobLockedOnRune(store, mobRef, runeRef)) {
                attackers++;
            }
            livingMobIndex++;
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
            session.movers().add(new OrangeBlobBlockRuntime.Mover(
                    source.blockId(),
                    source.rotation(),
                    source.x() + 0.5d,
                    source.y(),
                    source.z() + 0.5d,
                    target.x() + 0.5d,
                    target.y(),
                    target.z() + 0.5d,
                    session.downStartAt(),
                    session.downEndAt()
            ));
        }
        session.movers().add(new OrangeBlobBlockRuntime.Mover(
                session.sourceRuneBlock().blockId(),
                session.sourceRuneBlock().rotation(),
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
            session.movers().add(new OrangeBlobBlockRuntime.Mover(
                    source.blockId(),
                    source.rotation(),
                    source.x() + 0.5d,
                    source.y(),
                    source.z() + 0.5d,
                    target.x() + 0.5d,
                    target.y(),
                    target.z() + 0.5d,
                    session.upStartAt(),
                    session.upEndAt()
            ));
        }
        session.movers().add(new OrangeBlobBlockRuntime.Mover(
                session.loweredRuneBlock().blockId(),
                session.loweredRuneBlock().rotation(),
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

    private static void updateMovers(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<OrangeBlobBlockRuntime.Mover> movers,
            long now
    ) {
        for (OrangeBlobBlockRuntime.Mover mover : movers) {
            Ref<EntityStore> moverRef = mover.moverRef();
            if (moverRef == null || !moverRef.isValid()) {
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
            world.setBlock(block.x(), block.y(), block.z(), block.blockId());
        }
    }

    private static void placeBlock(@Nonnull World world, @Nonnull OrangeBlobBlockRuntime.ClusterBlock block) {
        world.setBlock(block.x(), block.y(), block.z(), block.blockId());
    }

    private static void spawnMobWave(
            @Nonnull Store<EntityStore> store,
            @Nonnull OrangeBlobBlockRuntime.Session session,
            long now
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(MOB_ROLE);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return;
        }

        OrangeBlobBlockRuntime.ClusterBlock center = session.loweredCenterBlock();
        double centerX = center.x() + 0.5d;
        double centerY = session.loweredRuneBlock().y() - 1.0d;
        double centerZ = center.z() + 0.5d;
        Vector3d runePos = new Vector3d(
                session.loweredRuneBlock().x() + 0.5d,
                session.loweredRuneBlock().y() + 0.5d,
                session.loweredRuneBlock().z() + 0.5d
        );
        Random random = new Random(session.id().getLeastSignificantBits() ^ now);
        List<Vector3d> spawnPositions = new ArrayList<>(session.config().mobsPerWave());
        for (int i = 0; i < session.config().mobsPerWave(); i++) {
            Vector3d baseOffset = MOB_OFFSETS[i % MOB_OFFSETS.length];
            double jitterX = (random.nextDouble() - 0.5d) * 1.5d;
            double jitterZ = (random.nextDouble() - 0.5d) * 1.5d;
            spawnPositions.add(new Vector3d(
                    centerX + baseOffset.getX() + jitterX,
                    centerY + baseOffset.getY(),
                    centerZ + baseOffset.getZ() + jitterZ
            ));
        }

        PlayerRef playerRef = findPlayerRef(session.activatingPlayerId());
        Ref<EntityStore> playerTargetRef = resolvePlayerCombatTarget(store, session, playerRef);
        int spawnIndex = 0;
        for (Vector3d spawnPos : spawnPositions) {
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
                if (playerRef != null && playerTargetRef != null && playerTargetRef.isValid() && spawnIndex % 2 == 0) {
                    aimMobAtPlayer(store, spawned.first(), spawned.second(), playerTargetRef, playerRef);
                } else {
                    aimMobAtRune(store, session, spawned.first(), spawned.second(), runePos);
                }
            }
            spawnIndex++;
        }
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
                    session.loweredRuneBlock().rotation(),
                    runePos.getX(),
                    runePos.getY(),
                    runePos.getZ(),
                    0.85f,
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
                    mobStore.removeEntity(mobRef, RemoveReason.REMOVE);
                }
            }
        }
        session.spawnedMobRefs().clear();
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

        applyRotation(holder, rotation);
        holder.ensureComponent(UUIDComponent.getComponentType());
        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
        if (ref == null || !ref.isValid()) {
            return null;
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

    private static void applyRotation(@Nonnull Holder<EntityStore> holder, int rotation) {
        try {
            RotationTuple tuple = RotationTuple.get(rotation);
            Rotation yaw = tuple.yaw();
            Rotation pitch = tuple.pitch();
            Rotation roll = tuple.roll();
            Vector3f rot = new Vector3f(0.0f, 0.0f, 0.0f);
            rot.setYaw((float) (-yaw.getRadians()));
            rot.setPitch((float) (-pitch.getRadians()));
            rot.setRoll((float) (-roll.getRadians()));
            holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rot));
        } catch (Exception ignored) {
        }
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
