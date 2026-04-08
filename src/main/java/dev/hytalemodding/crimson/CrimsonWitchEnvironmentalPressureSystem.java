package dev.hytalemodding.crimson;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class CrimsonWitchEnvironmentalPressureSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, DeathComponent> DEATH = DeathComponent.getComponentType();
    private static final ComponentType<EntityStore, UUIDComponent> UUID_COMPONENT = UUIDComponent.getComponentType();

    private static final String CRIMSON_WITCH_ROLE_PREFIX = "Crimson_Witch";
    private static final long INITIAL_DELAY_MS = 6000L;
    private static final long MIN_PRESSURE_INTERVAL_MS = 3000L;
    private static final long MAX_PRESSURE_INTERVAL_MS = 8000L;
    private static final long INTERVAL_REDUCTION_PER_STEP_MS = 500L;
    private static final int MAX_HAZARDS_PER_TRIGGER = 3;
    private static final long TRAP_DURATION_MS = 7000L;
    private static final double HAZARD_SPAWN_RADIUS = 10.0d;

    private static final Vector3i[] PRESSURE_PATTERN = new Vector3i[]{
            new Vector3i(0, 0, 0),
            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1),
            new Vector3i(1, 0, 1),
            new Vector3i(-1, 0, -1)
    };

    private final Map<UUID, WitchPressureRuntime> runtimes = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        long now = System.currentTimeMillis();
        ArrayList<UUID> activeWitches = new ArrayList<>();

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
                if (witchRef == null || !witchRef.isValid() || witchId == null || store.getComponent(witchRef, DEATH) != null) {
                    continue;
                }

                activeWitches.add(witchId);
                WitchPressureRuntime runtime = this.runtimes.computeIfAbsent(
                        witchId,
                        id -> {
                            System.out.println("[PressureSystem] New Witch detected: " + id);
                            return new WitchPressureRuntime(now + INITIAL_DELAY_MS);
                        }
                );

                if (now >= runtime.nextPressureAtMillis) {
                    System.out.println("[PressureSystem] Triggering pressure event for " + witchId + " (Interval: " + runtime.currentIntervalMs + "ms)");
                    triggerPressureEvent(world, store, witchTransform.getPosition(), runtime, now);
                    
                    // Increase pressure by reducing interval and potentially increasing hazard count
                    long currentInterval = runtime.currentIntervalMs;
                    if (currentInterval > MIN_PRESSURE_INTERVAL_MS) {
                        runtime.currentIntervalMs = Math.max(MIN_PRESSURE_INTERVAL_MS, currentInterval - INTERVAL_REDUCTION_PER_STEP_MS);
                    }
                    runtime.nextPressureAtMillis = now + runtime.currentIntervalMs;
                    runtime.triggerCount++;
                }
            }
        });

        this.runtimes.entrySet().removeIf(entry -> {
            UUID id = entry.getKey();
            WitchPressureRuntime runtime = entry.getValue();
            if (activeWitches.contains(id)) {
                runtime.ticksMissing = 0;
                return false;
            }
            runtime.ticksMissing++;
            if (runtime.ticksMissing > 10) {
                System.out.println("[PressureSystem] Witch permanently removed: " + id);
                return true;
            }
            return false;
        });
    }

    private void triggerPressureEvent(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d witchPos,
            @Nonnull WitchPressureRuntime runtime,
            long now
    ) {
        PlayerTarget target = findNearestPlayer(store, witchPos, 24.0d);
        Vector3d baseLocation = (target != null) ? target.position : witchPos;
        if (target == null) {
            System.out.println("[PressureSystem] No player found within 24m, using Witch position.");
        } else {
            System.out.println("[PressureSystem] Found player target at " + target.position);
        }

        int hazardCount = Math.min(MAX_HAZARDS_PER_TRIGGER, 1 + (runtime.triggerCount / 4));
        System.out.println("[PressureSystem] Spawning " + hazardCount + " hazards.");
        for (int i = 0; i < hazardCount; i++) {
            double angle = this.random.nextDouble() * Math.PI * 2.0d;
            double dist = this.random.nextDouble() * HAZARD_SPAWN_RADIUS;
            Vector3d spawnPos = new Vector3d(
                    baseLocation.getX() + Math.cos(angle) * dist,
                    baseLocation.getY(),
                    baseLocation.getZ() + Math.sin(angle) * dist
            );
            
            placeHazard(world, spawnPos, now);
        }
    }

    private void placeHazard(@Nonnull World world, @Nonnull Vector3d pos, long now) {
        int baseX = (int) Math.floor(pos.getX());
        int baseY = (int) Math.floor(pos.getY());
        int baseZ = (int) Math.floor(pos.getZ());
        
        // Find ground
        int groundY = baseY;
        boolean foundGround = false;
        for (int y = baseY + 2; y >= baseY - 6; y--) {
            BlockType blockAt = world.getBlockType(baseX, y, baseZ);
            BlockType blockAbove = world.getBlockType(baseX, y + 1, baseZ);
            if (RedWaveManager.isFullCubeBlock(blockAt) && !RedWaveManager.isFullCubeBlock(blockAbove)) {
                groundY = y;
                foundGround = true;
                break;
            }
        }
        if (!foundGround) {
            System.out.println("[PressureSystem] Failed to find ground at X:" + baseX + " Z:" + baseZ);
            return;
        }

        UUID worldId = world.getWorldConfig().getUuid();
        ArrayList<CrimsonWitchGroundTrapRuntime.BlockRestore> restores = new ArrayList<>();
        
        for (Vector3i offset : PRESSURE_PATTERN) {
            int x = baseX + offset.x;
            int y = groundY + offset.y;
            int z = baseZ + offset.z;
            
            if (CrimsonWitchGroundTrapRuntime.getActivePatchNear(worldId, x, y, z) != null) {
                continue;
            }

            BlockType existing = world.getBlockType(x, y, z);
            if (!RedWaveManager.shouldConvertBlock(existing)) {
                continue;
            }

            String existingId = existing.getId();
            if (existingId == null || existingId.isEmpty()) {
                continue;
            }

            restores.add(new CrimsonWitchGroundTrapRuntime.BlockRestore(
                    UUID.randomUUID(),
                    worldId,
                    x,
                    y,
                    z,
                    existingId
            ));
            world.setBlock(x, y, z, RedWaveConfig.CRIMSON_BLOCK_ID);
        }

        if (!restores.isEmpty()) {
            System.out.println("[PressureSystem] Placed hazard patch with " + restores.size() + " blocks.");
            CrimsonWitchGroundTrapRuntime.addPatch(new CrimsonWitchGroundTrapRuntime.TrapPatch(
                    UUID.randomUUID(),
                    worldId,
                    now + TRAP_DURATION_MS,
                    restores
            ));
        } else {
            System.out.println("[PressureSystem] No blocks converted for hazard at X:" + baseX + " Y:" + groundY + " Z:" + baseZ);
        }
    }

    @Nullable
    private PlayerTarget findNearestPlayer(
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d pos,
            double maxRange
    ) {
        double maxRangeSq = maxRange * maxRange;
        final PlayerTarget[] closest = new PlayerTarget[1];

        store.forEachChunk(PLAYER, (chunk, ignored) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                TransformComponent playerTransform = chunk.getComponent(i, TRANSFORM);
                if (playerTransform == null) continue;
                
                Vector3d playerPos = playerTransform.getPosition();
                double dx = playerPos.getX() - pos.getX();
                double dy = playerPos.getY() - pos.getY();
                double dz = playerPos.getZ() - pos.getZ();
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distSq > maxRangeSq) continue;
                
                if (closest[0] == null || distSq < closest[0].distanceSq) {
                    closest[0] = new PlayerTarget(playerPos.clone(), distSq);
                }
            }
        });
        return closest[0];
    }

    private static boolean isCrimsonWitch(@Nullable NPCEntity npc) {
        return npc != null && npc.getRoleName() != null && npc.getRoleName().startsWith(CRIMSON_WITCH_ROLE_PREFIX);
    }

    @Nullable
    private static UUID getEntityUuid(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> entityRef) {
        if (entityRef == null || !entityRef.isValid()) return null;
        UUIDComponent uuidComponent = store.getComponent(entityRef, UUID_COMPONENT);
        return uuidComponent == null ? null : uuidComponent.getUuid();
    }

    private static final class WitchPressureRuntime {
        private long nextPressureAtMillis;
        private long currentIntervalMs;
        private int triggerCount;
        private int ticksMissing;

        private WitchPressureRuntime(long nextPressureAtMillis) {
            this.nextPressureAtMillis = nextPressureAtMillis;
            this.currentIntervalMs = MAX_PRESSURE_INTERVAL_MS;
            this.triggerCount = 0;
            this.ticksMissing = 0;
        }
    }

    private static final class PlayerTarget {
        private final Vector3d position;
        private final double distanceSq;

        private PlayerTarget(Vector3d position, double distanceSq) {
            this.position = position;
            this.distanceSq = distanceSq;
        }
    }
}
