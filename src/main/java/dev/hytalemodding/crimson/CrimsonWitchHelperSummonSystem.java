package dev.hytalemodding.crimson;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CrimsonWitchHelperSummonSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, DeathComponent> DEATH = DeathComponent.getComponentType();
    private static final ComponentType<EntityStore, UUIDComponent> UUID_COMPONENT = UUIDComponent.getComponentType();

    private static final String CRIMSON_WITCH_ROLE_PREFIX = "Crimson_Witch";
    private static final String HELPER_ROLE = "Scarak_Louse";
    private static final String[] TARGET_SLOTS = new String[]{"target", "Target", "CombatTarget"};
    private static final String SUMMON_STATE = "Combat.Summon";
    private static final String ATTACK_STATE = "Combat.Attack";
    private static final long HELPER_ACQUIRE_DELAY_MS = 2300L;
    private static final long SUMMON_COOLDOWN_MS = 10000L;
    private static final long RETARGET_COOLDOWN_MS = 1500L;
    private static final int MAX_ACTIVE_HELPERS = 2;
    private static final double SUMMON_TRIGGER_RANGE = 16.0d;
    private static final double HELPER_ACQUIRE_RANGE = 8.0d;
    private static final Vector3d[] SUMMON_OFFSETS = new Vector3d[]{
            new Vector3d(2.0d, 0.0d, 0.0d),
            new Vector3d(-2.0d, 0.0d, 0.0d),
            new Vector3d(0.0d, 0.0d, 2.0d),
            new Vector3d(0.0d, 0.0d, -2.0d)
    };

    private final Map<UUID, WitchRuntime> runtimes = new HashMap<>();
    private boolean helperRoleWarningPrinted;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        long now = System.currentTimeMillis();
        ArrayList<UUID> seenWitches = new ArrayList<>();

        store.forEachChunk(NPC, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity witch = chunk.getComponent(i, NPC);
                TransformComponent witchTransform = chunk.getComponent(i, TRANSFORM);
                if (witch == null || witchTransform == null || !isCrimsonWitch(witch)) {
                    continue;
                }

                Ref<EntityStore> witchRef = chunk.getReferenceTo(i);
                UUID witchId = getEntityUuid(store, witchRef);
                if (witchRef == null || !witchRef.isValid() || witchId == null) {
                    continue;
                }

                seenWitches.add(witchId);
                WitchRuntime runtime = this.runtimes.computeIfAbsent(
                        witchId,
                        ignoredKey -> {
                            System.out.println("[WitchHelpers] New Witch detected: " + witchId);
                            return new WitchRuntime(witchRef);
                        }
                );
                runtime.witchRef = witchRef;
                cleanupDeadHelpers(store, runtime);

                String stateName = getNpcStateName(witch);
                boolean enteredSummonState = stateName != null
                        && SUMMON_STATE.equals(stateName)
                        && !SUMMON_STATE.equals(runtime.lastStateName);
                boolean enteredAttackState = stateName != null
                        && ATTACK_STATE.equals(stateName)
                        && !ATTACK_STATE.equals(runtime.lastStateName);
                runtime.lastStateName = stateName;

                if (enteredSummonState) {
                    runtime.pendingAcquireAtMillis = now + HELPER_ACQUIRE_DELAY_MS;
                    runtime.pendingSpawnAtMillis = 0L;
                } else if (enteredAttackState
                        && runtime.helperRefs.isEmpty()
                        && now >= runtime.nextSummonAllowedAtMillis) {
                    runtime.pendingAcquireAtMillis = now + HELPER_ACQUIRE_DELAY_MS;
                    runtime.pendingSpawnAtMillis = now + HELPER_ACQUIRE_DELAY_MS;
                }

                PlayerTarget target = findNearestValidPlayerTarget(store, witchTransform.getPosition(), SUMMON_TRIGGER_RANGE);
                if (target == null) {
                    continue;
                }

                if (runtime.pendingAcquireAtMillis > 0L && now >= runtime.pendingAcquireAtMillis) {
                    runtime.pendingAcquireAtMillis = 0L;
                    acquireNearbyHelpers(store, runtime, witchTransform.getPosition(), target.playerRef);
                }

                if (runtime.pendingSpawnAtMillis > 0L
                        && now >= runtime.pendingSpawnAtMillis
                        && runtime.helperRefs.isEmpty()
                        && now >= runtime.nextSummonAllowedAtMillis) {
                    runtime.pendingSpawnAtMillis = 0L;
                    if (!isHelperRoleSpawnable()) {
                        continue;
                    }
                    runtime.nextSummonAllowedAtMillis = now + SUMMON_COOLDOWN_MS;
                    Ref<EntityStore> targetRef = target.playerRef;
                    world.execute(() -> spawnHelpers(world, runtime, witchRef, targetRef));
                }

                if (!runtime.helperRefs.isEmpty() && now >= runtime.nextRetargetAtMillis) {
                    runtime.nextRetargetAtMillis = now + RETARGET_COOLDOWN_MS;
                    Ref<EntityStore> targetRef = target.playerRef;
                    world.execute(() -> retargetHelpers(world, runtime, targetRef));
                }
            }
        });

        this.runtimes.entrySet().removeIf(entry -> {
            UUID id = entry.getKey();
            WitchRuntime runtime = entry.getValue();
            if (seenWitches.contains(id)) {
                runtime.ticksMissing = 0;
                return false;
            }
            
            runtime.ticksMissing++;
            if (runtime.ticksMissing > 10) { // Only remove if not seen for 10 ticks (~0.5s)
                System.out.println("[WitchHelpers] Witch permanently removed: " + id);
                return true;
            }
            return false;
        });
    }

    private static void acquireNearbyHelpers(
            @Nonnull Store<EntityStore> store,
            @Nonnull WitchRuntime runtime,
            @Nonnull Vector3d witchPosition,
            @Nonnull Ref<EntityStore> targetRef
    ) {
        cleanupDeadHelpers(store, runtime);
        if (runtime.helperRefs.size() >= MAX_ACTIVE_HELPERS) {
            return;
        }

        double maxRangeSq = HELPER_ACQUIRE_RANGE * HELPER_ACQUIRE_RANGE;
        List<Ref<EntityStore>> nearbyHelpers = new ArrayList<>();
        store.forEachChunk(NPC, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity helper = chunk.getComponent(i, NPC);
                TransformComponent helperTransform = chunk.getComponent(i, TRANSFORM);
                if (helper == null || helperTransform == null || !HELPER_ROLE.equals(helper.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> helperRef = chunk.getReferenceTo(i);
                if (helperRef == null || !helperRef.isValid() || runtime.helperRefs.contains(helperRef)) {
                    continue;
                }
                double dx = helperTransform.getPosition().getX() - witchPosition.getX();
                double dy = helperTransform.getPosition().getY() - witchPosition.getY();
                double dz = helperTransform.getPosition().getZ() - witchPosition.getZ();
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distSq <= maxRangeSq) {
                    nearbyHelpers.add(helperRef);
                }
            }
        });

        int acquired = 0;
        for (Ref<EntityStore> helperRef : nearbyHelpers) {
            if (runtime.helperRefs.size() >= MAX_ACTIVE_HELPERS) {
                break;
            }
            NPCEntity helper = store.getComponent(helperRef, NPC);
            if (helper == null || store.getComponent(helperRef, DEATH) != null) {
                continue;
            }
            runtime.helperRefs.add(helperRef);
            assignHelperTarget(store, helperRef, helper, targetRef);
            acquired++;
        }

        if (acquired > 0) {
            System.out.println("[WitchHelpers] Acquired " + acquired + " summoned helpers.");
            runtime.nextRetargetAtMillis = System.currentTimeMillis() + RETARGET_COOLDOWN_MS;
            runtime.nextSummonAllowedAtMillis = System.currentTimeMillis() + SUMMON_COOLDOWN_MS;
        }
    }

    private static void spawnHelpers(
            @Nonnull World world,
            @Nonnull WitchRuntime runtime,
            @Nonnull Ref<EntityStore> witchRef,
            @Nonnull Ref<EntityStore> targetRef
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (!witchRef.isValid() || !targetRef.isValid()) {
            return;
        }

        cleanupDeadHelpers(store, runtime);
        if (runtime.helperRefs.size() >= MAX_ACTIVE_HELPERS) {
            return;
        }

        TransformComponent witchTransform = store.getComponent(witchRef, TRANSFORM);
        if (witchTransform == null) {
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(HELPER_ROLE);
        if (roleIndex < 0) {
            return;
        }
        int spawnCount = Math.min(MAX_ACTIVE_HELPERS - runtime.helperRefs.size(), runtime.helperRefs.isEmpty() ? 2 : 1);
        System.out.println("[WitchHelpers] Fallback spawning " + spawnCount + " helpers.");
        for (int i = 0; i < spawnCount; i++) {
            Vector3d offset = SUMMON_OFFSETS[(runtime.spawnOffsetIndex + i) % SUMMON_OFFSETS.length];
            Vector3d spawnPos = new Vector3d(
                    witchTransform.getPosition().getX() + offset.getX(),
                    witchTransform.getPosition().getY(),
                    witchTransform.getPosition().getZ() + offset.getZ()
            );
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                    store,
                    roleIndex,
                    spawnPos,
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    null,
                    null
            );
            if (spawned == null || spawned.first() == null || !spawned.first().isValid() || spawned.second() == null) {
                continue;
            }
            runtime.helperRefs.add(spawned.first());
            runtime.spawnOffsetIndex = (runtime.spawnOffsetIndex + 1) % SUMMON_OFFSETS.length;
            assignHelperTarget(store, spawned.first(), spawned.second(), targetRef);
        }

        if (!runtime.helperRefs.isEmpty()) {
            runtime.nextRetargetAtMillis = System.currentTimeMillis() + RETARGET_COOLDOWN_MS;
        }
    }

    private static void retargetHelpers(
            @Nonnull World world,
            @Nonnull WitchRuntime runtime,
            @Nonnull Ref<EntityStore> targetRef
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        cleanupDeadHelpers(store, runtime);
        for (Ref<EntityStore> helperRef : runtime.helperRefs) {
            if (helperRef == null || !helperRef.isValid() || helperRef.getStore() != store) {
                continue;
            }
            NPCEntity helper = store.getComponent(helperRef, NPC);
            if (helper == null || store.getComponent(helperRef, DEATH) != null) {
                continue;
            }
            assignHelperTarget(store, helperRef, helper, targetRef);
        }
    }

    private static void assignHelperTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> helperRef,
            @Nonnull NPCEntity helper,
            @Nonnull Ref<EntityStore> targetRef
    ) {
        if (!targetRef.isValid()) {
            return;
        }

        Role role = helper.getRole();
        if (role == null) {
            return;
        }

        role.setMarkedTarget(MarkedEntitySupport.DEFAULT_TARGET_SLOT, targetRef);
        WorldSupport support = role.getWorldSupport();
        if (support != null) {
            try {
                support.overrideAttitude(targetRef, Attitude.HOSTILE, 60.0 * 60.0);
            } catch (Throwable ignored) {
            }
            support.requestNewPath();
        }
        for (String slot : TARGET_SLOTS) {
            try {
                helper.onFlockSetTarget(slot, targetRef);
            } catch (Throwable ignored) {
            }
        }
        store.putComponent(helperRef, NPC, helper);
    }

    @Nullable
    private static PlayerTarget findNearestValidPlayerTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d witchPos,
            double maxRange
    ) {
        double maxRangeSq = maxRange * maxRange;
        final PlayerTarget[] closest = new PlayerTarget[1];

        store.forEachChunk(PLAYER, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Player player = chunk.getComponent(i, PLAYER);
                TransformComponent playerTransform = chunk.getComponent(i, TRANSFORM);
                if (player == null || playerTransform == null) {
                    continue;
                }
                
                // Skip players in creative mode
                if (player.getGameMode() == GameMode.Creative) {
                    continue;
                }

                Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }
                Vector3d playerPos = playerTransform.getPosition();
                double dx = playerPos.getX() - witchPos.getX();
                double dy = playerPos.getY() - witchPos.getY();
                double dz = playerPos.getZ() - witchPos.getZ();
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distSq > maxRangeSq) {
                    continue;
                }
                if (closest[0] == null || distSq < closest[0].distanceSq) {
                    closest[0] = new PlayerTarget(playerRef, distSq);
                }
            }
        });

        return closest[0];
    }

    private static void cleanupDeadHelpers(
            @Nonnull Store<EntityStore> store,
            @Nonnull WitchRuntime runtime
    ) {
        Iterator<Ref<EntityStore>> iterator = runtime.helperRefs.iterator();
        while (iterator.hasNext()) {
            Ref<EntityStore> helperRef = iterator.next();
            if (helperRef == null || !helperRef.isValid() || helperRef.getStore() != store || store.getComponent(helperRef, DEATH) != null) {
                iterator.remove();
            }
        }
    }

    private static boolean isCrimsonWitch(@Nullable NPCEntity npc) {
        return npc != null && npc.getRoleName() != null && npc.getRoleName().startsWith(CRIMSON_WITCH_ROLE_PREFIX);
    }

    private boolean isHelperRoleSpawnable() {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(HELPER_ROLE);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        boolean spawnable = roleIndex >= 0 && roleInfo != null && roleInfo.getBuilder().isSpawnable();
        if (!spawnable && !this.helperRoleWarningPrinted) {
            this.helperRoleWarningPrinted = true;
            System.out.println("[CrimsonWitchHelpers] Helper role unavailable or not spawnable: " + HELPER_ROLE);
        }
        return spawnable;
    }

    @Nullable
    private static String getNpcStateName(@Nonnull NPCEntity npc) {
        if (npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        return npc.getRole().getStateSupport().getStateName();
    }

    @Nullable
    private static UUID getEntityUuid(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> entityRef
    ) {
        if (entityRef == null || !entityRef.isValid()) {
            return null;
        }
        UUIDComponent uuidComponent;
        try {
            uuidComponent = store.getComponent(entityRef, UUID_COMPONENT);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return uuidComponent == null ? null : uuidComponent.getUuid();
    }

    private static final class WitchRuntime {
        @Nonnull
        private Ref<EntityStore> witchRef;
        @Nonnull
        private final List<Ref<EntityStore>> helperRefs = new ArrayList<>();
        @Nullable
        private String lastStateName;
        private long pendingAcquireAtMillis;
        private long pendingSpawnAtMillis;
        private long nextSummonAllowedAtMillis;
        private long nextRetargetAtMillis;
        private int spawnOffsetIndex;
        private int ticksMissing;

        private WitchRuntime(@Nonnull Ref<EntityStore> witchRef) {
            this.witchRef = witchRef;
            this.lastStateName = null;
            this.pendingAcquireAtMillis = 0L;
            this.pendingSpawnAtMillis = 0L;
            this.nextSummonAllowedAtMillis = 0L;
            this.nextRetargetAtMillis = 0L;
            this.spawnOffsetIndex = 0;
            this.ticksMissing = 0;
        }
    }

    private static final class PlayerTarget {
        @Nonnull
        private final Ref<EntityStore> playerRef;
        private final double distanceSq;

        private PlayerTarget(@Nonnull Ref<EntityStore> playerRef, double distanceSq) {
            this.playerRef = playerRef;
            this.distanceSq = distanceSq;
        }
    }
}
