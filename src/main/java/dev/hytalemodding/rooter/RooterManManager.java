package dev.hytalemodding.rooter;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.state.run.RegionSpawnConfig;
import dev.hytalemodding.state.run.GameSessionManager;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RooterManManager {
    private static final RooterManManager INSTANCE = new RooterManManager();
    private static final String ROOTER_BOSS_ROLE_PREFIX = "Rooter_Man_Boss";

    private final ConcurrentHashMap<UUID, BossSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> spawnedByRunWorld = new ConcurrentHashMap<>();

    private RooterManManager() {
    }

    @Nonnull
    public static RooterManManager get() {
        return INSTANCE;
    }

    public void initialize() {
        RooterConfig.get().initialize();
    }

    public void tick(@Nonnull Store<EntityStore> store, float dt) {
        RooterConfig config = RooterConfig.get();
        if (!config.isEnabled()) {
            cleanupAll();
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();

        discoverBossSessions(store, worldId, config.getBossRole());
        emitPhaseChangeFeedback(store, worldId);
        cleanupExpiredSessions(worldId);

        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot != null
                && snapshot.runWorldUuid() != null
                && snapshot.runWorldUuid().equals(worldId)
                && snapshot.phase() == GameSessionManager.RunPhase.CRIMSON_ACTIVE
                && config.getTemplateWorld().equalsIgnoreCase(snapshot.templateWorldName())) {
            tryAutoSpawnBoss(store, world, snapshot);
        } else {
            this.spawnedByRunWorld.remove(worldId);
        }
    }

    public boolean spawnBossAtPlayer(@Nonnull Store<EntityStore> store, @Nonnull PlayerRef playerRef) {
        if (playerRef.getWorldUuid() == null) {
            return false;
        }

        Vector3d position = new Vector3d(playerRef.getTransform().getPosition());
        Vector3f rotation = new Vector3f(playerRef.getTransform().getRotation());
        Ref<EntityStore> bossRef = spawnNpc(store, RooterConfig.get().getBossRole(), position, rotation);
        if (bossRef == null || !bossRef.isValid()) {
            return false;
        }

        UUID bossId = getEntityUuid(store, bossRef);
        if (bossId != null) {
            this.sessions.putIfAbsent(bossId, new BossSession(playerRef.getWorldUuid(), bossId, bossRef, true));
        }
        sendWorldMessage(playerRef.getWorldUuid(), "Rooter Man emerges.");
        return true;
    }

    private void discoverBossSessions(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID worldId,
            @Nonnull String bossRole
    ) {
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) {
                    continue;
                }
                String roleName = npc.getRoleName();
                if (!bossRole.equals(roleName) && !isRooterBossRole(roleName)) {
                    continue;
                }

                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) {
                    continue;
                }

                UUID bossId = getEntityUuid(store, ref);
                if (bossId == null) {
                    continue;
                }

                BossSession existing = this.sessions.get(bossId);
                if (existing == null) {
                    this.sessions.put(bossId, new BossSession(worldId, bossId, ref, false));
                } else {
                    existing.bossRef = ref;
                }
            }
        });
    }

    private void emitPhaseChangeFeedback(@Nonnull Store<EntityStore> store, @Nonnull UUID worldId) {
        for (BossSession session : this.sessions.values()) {
            if (!worldId.equals(session.worldId) || session.bossRef == null || !session.bossRef.isValid()) {
                continue;
            }

            NPCEntity npc;
            try {
                npc = store.getComponent(session.bossRef, NPCEntity.getComponentType());
            } catch (IllegalStateException ignored) {
                continue;
            }
            if (npc == null) {
                continue;
            }

            String roleName = npc.getRoleName();
            if (!isRooterBossRole(roleName)) {
                continue;
            }

            if (session.lastObservedRoleName == null) {
                session.lastObservedRoleName = roleName;
                continue;
            }
            if (session.lastObservedRoleName.equals(roleName)) {
                continue;
            }

            session.lastObservedRoleName = roleName;
            String phaseLabel = phaseLabelForRole(roleName);
            if (phaseLabel != null) {
                sendWorldMessage(worldId, "Rooter Man shifts into " + phaseLabel + ".");
            }
        }
    }

    private void cleanupExpiredSessions(@Nonnull UUID worldId) {
        List<UUID> expired = new ArrayList<>();
        for (BossSession session : this.sessions.values()) {
            if (!worldId.equals(session.worldId) || session.bossRef == null || !session.bossRef.isValid()) {
                expired.add(session.bossId);
            }
        }

        for (UUID bossId : expired) {
            this.sessions.remove(bossId);
        }
    }

    private void tryAutoSpawnBoss(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull GameSessionManager.ActiveSessionSnapshot snapshot
    ) {
        if (RegionSpawnConfig.get().isEnabled()) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();
        if (this.spawnedByRunWorld.containsKey(worldId)) {
            return;
        }

        List<com.hypixel.hytale.math.vector.Vector3i> cores = RedCoreRegistry.snapshot(worldId);
        if (cores.isEmpty()) {
            return;
        }

        com.hypixel.hytale.math.vector.Vector3i core = cores.get(0);
        Vector3d spawnPos = new Vector3d(core.x, core.y, core.z);
        spawnPos.add(RooterConfig.get().getSpawnOffset());
        Vector3f spawnRot = new Vector3f(0, 0, 0);

        Ref<EntityStore> bossRef = spawnNpc(store, RooterConfig.get().getBossRole(), spawnPos, spawnRot);
        if (bossRef == null || !bossRef.isValid()) {
            return;
        }

        this.spawnedByRunWorld.put(worldId, snapshot.runWorldUuid());
        UUID bossId = getEntityUuid(store, bossRef);
        if (bossId != null) {
            this.sessions.putIfAbsent(bossId, new BossSession(worldId, bossId, bossRef, false));
        }
        sendWorldMessage(worldId, "Rooter Man rises near the infection core.");
    }

    @Nullable
    private static Ref<EntityStore> spawnNpc(
            @Nonnull Store<EntityStore> store,
            @Nonnull String roleName,
            @Nonnull Vector3d spawnPos,
            @Nonnull Vector3f spawnRot
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(roleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return null;
        }

        Pair<Ref<EntityStore>, NPCEntity> pair = npcPlugin.spawnEntity(store, roleIndex, spawnPos, spawnRot, null, null);
        if (pair == null || pair.first() == null || !pair.first().isValid()) {
            return null;
        }
        return pair.first();
    }

    private void cleanupAll() {
        this.sessions.clear();
        this.spawnedByRunWorld.clear();
    }

    public void clearRuntimeForWorld(@Nonnull UUID worldId) {
        this.spawnedByRunWorld.remove(worldId);
        this.sessions.entrySet().removeIf(entry -> {
            BossSession session = entry.getValue();
            return session == null || worldId.equals(session.worldId);
        });
        RooterShockwaveRuntime.clearWorld(worldId);
    }

    private static int resolveCommandCauseIndex() {
        int command = DamageCause.getAssetMap().getIndex("Command");
        if (command != Integer.MIN_VALUE) {
            return command;
        }
        int environment = DamageCause.getAssetMap().getIndex("Environment");
        return environment != Integer.MIN_VALUE ? environment : 0;
    }

    private static boolean isRooterBossRole(@Nullable String roleName) {
        return roleName != null && roleName.startsWith(ROOTER_BOSS_ROLE_PREFIX);
    }

    @Nullable
    private static UUID getEntityUuid(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }

        UUIDComponent uuidComponent;
        try {
            uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
        return uuidComponent == null ? null : uuidComponent.getUuid();
    }

    @Nullable
    private static String phaseLabelForRole(@Nonnull String roleName) {
        if (roleName.endsWith("_P2")) {
            return "Phase 2";
        }
        if (roleName.endsWith("_P3")) {
            return "Phase 3";
        }
        return null;
    }

    private static void sendWorldMessage(@Nonnull UUID worldId, @Nonnull String text) {
        Message message = Message.raw(text);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorld = playerRef.getWorldUuid();
            if (playerWorld != null && playerWorld.equals(worldId)) {
                playerRef.sendMessage(message);
            }
        }
    }

    private static final class BossSession {
        @Nonnull
        private final UUID worldId;
        @Nonnull
        private final UUID bossId;
        @Nullable
        private Ref<EntityStore> bossRef;
        private final boolean manualSpawned;
        @Nullable
        private String lastObservedRoleName;

        private BossSession(@Nonnull UUID worldId, @Nonnull UUID bossId, @Nonnull Ref<EntityStore> bossRef, boolean manualSpawned) {
            this.worldId = worldId;
            this.bossId = bossId;
            this.bossRef = bossRef;
            this.manualSpawned = manualSpawned;
            this.lastObservedRoleName = null;
        }
    }
}
