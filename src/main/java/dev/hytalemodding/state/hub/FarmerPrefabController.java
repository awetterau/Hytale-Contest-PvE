package dev.hytalemodding.state.hub;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.FastRandom;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.FromPrefab;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FarmerPrefabController {
    private static final Path CUSTOM_FARMER_PREFAB = Path.of(
            "C:\\Users\\cryst\\OneDrive\\Desktop\\Hytale-Contest-PvE\\devserver\\prefabs\\farmer.prefab.json"
    );
    private static final Path NO_FARMER_PREFAB = Path.of(
            "C:\\Users\\cryst\\OneDrive\\Desktop\\Hytale-Contest-PvE\\devserver\\prefabs\\no_farmer.prefab.json"
    );

    // Anchor 0,0,0 with prefab bounds x=-19..20, y=0..10, z=-15..16 lands at:
    // x=567..606, y=179..189, z=14..45.
    private static final Vector3i PASTE_ORIGIN = new Vector3i(586, 179, 29);
    private static final Rotation CUSTOM_FARMER_ROTATION = Rotation.None;
    private static final Rotation NO_FARMER_ROTATION = Rotation.None;
    private static final int BUILD_BLOCKS_PER_TICK = 32;
    private static final long DEFAULT_BUILD_DELAY_MS = 1000L;
    @Nullable
    private static BuildSession activeBuildSession;
    @Nullable
    private static PendingBuildSession pendingBuildSession;

    private FarmerPrefabController() {
    }

    @Nonnull
    public static Result pasteCustomFarmer() {
        World world = resolveHubWorld();
        if (world == null) {
            return Result.failed("Hub world is unavailable.");
        }
        removeWorkshopEntities(world);
        return paste(world, CUSTOM_FARMER_PREFAB, CUSTOM_FARMER_ROTATION, true);
    }

    @Nonnull
    public static Result undoFarmer() {
        World world = resolveHubWorld();
        if (world == null) {
            return Result.failed("Hub world is unavailable.");
        }
        activeBuildSession = null;
        pendingBuildSession = null;
        removeWorkshopEntities(world);

        Result pasteResult = paste(world, NO_FARMER_PREFAB, NO_FARMER_ROTATION, false);
        if (!pasteResult.success()) {
            return pasteResult;
        }
        BaseHousingManager.get().resetFarmerWorkshopState();
        QuestProgressManager.get().reset("save_the_farm_animals");
        return Result.success("Undo prefab pasted.");
    }

    @Nonnull
    public static synchronized Result validateCustomFarmerBuildStart() {
        if (activeBuildSession != null || pendingBuildSession != null) {
            return Result.failed("A staged farmer build is already running.");
        }

        World world = resolveHubWorld();
        if (world == null) {
            return Result.failed("Hub world is unavailable.");
        }
        if (!Files.exists(CUSTOM_FARMER_PREFAB)) {
            return Result.failed("Prefab file not found: " + CUSTOM_FARMER_PREFAB);
        }
        return Result.success("Farmer build can start.");
    }

    @Nonnull
    public static synchronized Result startCustomFarmerBuild() {
        Result validation = validateCustomFarmerBuildStart();
        if (!validation.success()) {
            return validation;
        }

        World world = resolveHubWorld();

        try {
            List<BuildBlock> blocks = loadBuildBlocks(CUSTOM_FARMER_PREFAB, CUSTOM_FARMER_ROTATION);
            List<Holder<EntityStore>> entities = loadEntityHolders(CUSTOM_FARMER_PREFAB, CUSTOM_FARMER_ROTATION);
            if (blocks.isEmpty()) {
                return Result.failed("Farmer prefab did not contain any blocks to place.");
            }

            removeWorkshopEntities(world);
            activeBuildSession = new BuildSession(world.getName(), blocks, entities, 0);
            return Result.success("Started staged farmer build (" + blocks.size() + " blocks).");
        } catch (Throwable throwable) {
            String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
            return Result.failed("Failed to start staged farmer build: " + reason);
        }
    }

    @Nonnull
    public static synchronized Result scheduleCustomFarmerBuildStart(long delayMs) {
        Result validation = validateCustomFarmerBuildStart();
        if (!validation.success()) {
            return validation;
        }

        World world = resolveHubWorld();
        if (world == null) {
            return Result.failed("Hub world is unavailable.");
        }

        long actualDelayMs = Math.max(0L, delayMs <= 0L ? DEFAULT_BUILD_DELAY_MS : delayMs);
        pendingBuildSession = new PendingBuildSession(world.getName(), System.currentTimeMillis() + actualDelayMs);
        return Result.success("Farmer construction will begin shortly.");
    }

    public static synchronized void processBuildTick(@Nonnull World world, @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (pendingBuildSession != null
                && pendingBuildSession.worldName().equalsIgnoreCase(world.getName())
                && System.currentTimeMillis() >= pendingBuildSession.startAtMs()) {
            pendingBuildSession = null;
            Result startResult = startCustomFarmerBuild();
            if (!startResult.success()) {
                activeBuildSession = null;
            }
        }
        if (activeBuildSession == null) {
            return;
        }
        if (!activeBuildSession.worldName().equalsIgnoreCase(world.getName())) {
            return;
        }

        int remaining = activeBuildSession.blocks().size() - activeBuildSession.cursor();
        if (remaining <= 0) {
            spawnWorkshopEntities(world, activeBuildSession.entities());
            BaseHousingManager.get().onFarmerWorkshopBuilt(world);
            activeBuildSession = null;
            return;
        }

        int batchSize = Math.min(BUILD_BLOCKS_PER_TICK, remaining);
        BlockSelection selection = new BlockSelection(batchSize, 0);
        selection.setPosition(0, 0, 0);
        selection.setAnchor(0, 0, 0);

        for (int i = 0; i < batchSize; i++) {
            BuildBlock block = activeBuildSession.blocks().get(activeBuildSession.cursor() + i);
            selection.addBlockAtLocalPos(
                    block.x(),
                    block.y(),
                    block.z(),
                    block.blockId(),
                    block.rotation(),
                    block.filler(),
                    block.supportValue(),
                    block.state() == null ? null : block.state().clone()
            );
        }

        selection.placeNoReturn(world, new Vector3i(PASTE_ORIGIN.x, PASTE_ORIGIN.y, PASTE_ORIGIN.z), componentAccessor);
        activeBuildSession = new BuildSession(
                activeBuildSession.worldName(),
                activeBuildSession.blocks(),
                activeBuildSession.entities(),
                activeBuildSession.cursor() + batchSize
        );

        if (activeBuildSession.cursor() >= activeBuildSession.blocks().size()) {
            spawnWorkshopEntities(world, activeBuildSession.entities());
            BaseHousingManager.get().onFarmerWorkshopBuilt(world);
            activeBuildSession = null;
        }
    }

    @Nullable
    private static World resolveHubWorld() {
        return Universe.get().getWorld(GameFlowConfigManager.get().getHubWorldName());
    }

    @Nonnull
    private static List<BuildBlock> loadBuildBlocks(@Nonnull Path prefabPath, @Nonnull Rotation rotation) {
        IPrefabBuffer prefab = PrefabBufferUtil.getCached(prefabPath);
        PrefabRotation prefabRotation = PrefabRotation.fromRotation(rotation);
        List<BuildBlock> blocks = new ArrayList<>();
        PrefabBufferCall call = new PrefabBufferCall(new FastRandom(), prefabRotation);

        prefab.forEach(
                IPrefabBuffer.iterateAllColumns(),
                (x, y, z, blockId, holder, supportValue, blockRotation, filler, ignoredCall, fluidId, fluidLevel) -> {
                    blocks.add(new BuildBlock(
                            x,
                            y,
                            z,
                            blockId,
                            blockRotation,
                            filler,
                            supportValue,
                            holder == null ? null : holder.clone()
                    ));
                },
                null,
                null,
                call
        );

        double centerX = blocks.stream().mapToInt(BuildBlock::x).average().orElse(0.0D);
        double centerZ = blocks.stream().mapToInt(BuildBlock::z).average().orElse(0.0D);
        int minY = blocks.stream().mapToInt(BuildBlock::y).min().orElse(0);

        blocks.sort(Comparator
                .comparingDouble((BuildBlock block) -> buildWaveScore(block, centerX, centerZ, minY))
                .thenComparingInt(BuildBlock::y)
                .thenComparingInt(BuildBlock::z)
                .thenComparing(Comparator.comparingInt(BuildBlock::x).reversed()));
        return blocks;
    }

    @Nonnull
    private static Result paste(@Nonnull World world, @Nonnull Path prefabPath, @Nonnull Rotation rotation, boolean includeEntities) {
        if (!Files.exists(prefabPath)) {
            return Result.failed("Prefab file not found: " + prefabPath);
        }

        try {
            IPrefabBuffer prefab = PrefabBufferUtil.getCached(prefabPath);
            PrefabUtil.paste(
                    prefab,
                    world,
                    new Vector3i(PASTE_ORIGIN.x, PASTE_ORIGIN.y, PASTE_ORIGIN.z),
                    rotation,
                    true,
                    new FastRandom(),
                    0,
                    false,
                    false,
                    false,
                    world.getEntityStore().getStore()
            );
            if (includeEntities) {
                spawnWorkshopEntities(world, loadEntityHolders(prefabPath, rotation));
                BaseHousingManager.get().onFarmerWorkshopBuilt(world);
            }
            return Result.success("Pasted prefab: " + prefabPath.getFileName());
        } catch (Throwable throwable) {
            String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
            return Result.failed("Failed to paste prefab '" + prefabPath.getFileName() + "': " + reason);
        }
    }

    @Nonnull
    private static List<Holder<EntityStore>> loadEntityHolders(@Nonnull Path prefabPath, @Nonnull Rotation rotation) {
        IPrefabBuffer prefab = PrefabBufferUtil.getCached(prefabPath);
        PrefabRotation prefabRotation = PrefabRotation.fromRotation(rotation);
        List<Holder<EntityStore>> entities = new ArrayList<>();
        PrefabBufferCall call = new PrefabBufferCall(new FastRandom(), prefabRotation);

        prefab.forEach(
                IPrefabBuffer.iterateAllColumns(),
                (x, y, z, blockId, holder, supportValue, blockRotation, filler, ignoredCall, fluidId, fluidLevel) -> {
                },
                (x, z, entityWrappers, ignoredCall) -> {
                    if (entityWrappers == null) {
                        return;
                    }
                    for (Holder<EntityStore> entityWrapper : entityWrappers) {
                        if (entityWrapper != null) {
                            entities.add(entityWrapper.clone());
                        }
                    }
                },
                null,
                call
        );
        return entities;
    }

    private static double buildWaveScore(@Nonnull BuildBlock block, double centerX, double centerZ, int minY) {
        double dx = block.x() - centerX;
        double dz = block.z() - centerZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalLift = Math.max(0, block.y() - minY) * 0.75D;
        return horizontalDistance + verticalLift;
    }

    private static void spawnWorkshopEntities(@Nonnull World world, @Nonnull List<Holder<EntityStore>> entities) {
        if (entities.isEmpty()) {
            return;
        }

        Store<EntityStore> store = world.getEntityStore().getStore();
        PrefabRotation rotation = PrefabRotation.fromRotation(CUSTOM_FARMER_ROTATION);
        for (Holder<EntityStore> original : entities) {
            Holder<EntityStore> entity = original.clone();
            TransformComponent transform = entity.getComponent(TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                continue;
            }

            Vector3d localPosition = transform.getPosition().clone();
            rotation.rotate(localPosition);
            Vector3d worldPosition = localPosition.add(PASTE_ORIGIN.x, PASTE_ORIGIN.y, PASTE_ORIGIN.z);
            transform.getPosition().x = worldPosition.x;
            transform.getPosition().y = worldPosition.y;
            transform.getPosition().z = worldPosition.z;
            entity.ensureComponent(FromPrefab.getComponentType());
            entity.addComponent(FarmerWorkshopEntityMarker.getComponentType(), FarmerWorkshopEntityMarker.INSTANCE);
            store.addEntity(entity, AddReason.LOAD);
        }
    }

    private static void removeWorkshopEntities(@Nonnull World world) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        List<Ref<EntityStore>> toRemove = new ArrayList<>();
        Bounds bounds = getWorkshopBounds();
        store.forEachChunk(FarmerWorkshopEntityMarker.getComponentType(), (chunk, buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                FarmerWorkshopEntityMarker marker = chunk.getComponent(i, FarmerWorkshopEntityMarker.getComponentType());
                if (marker == null) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (ref == null || !ref.isValid() || transform == null || transform.getPosition() == null) {
                    continue;
                }
                if (bounds.contains(transform.getPosition())) {
                    toRemove.add(ref);
                }
            }
        });

        for (Ref<EntityStore> ref : toRemove) {
            store.removeEntity(ref, RemoveReason.REMOVE);
        }
    }

    @Nonnull
    private static Bounds getWorkshopBounds() {
        return Bounds.of(
                567,
                179,
                14,
                606,
                189,
                45
        );
    }

    public record Result(boolean success, @Nonnull String message) {
        @Nonnull
        public static Result success(@Nonnull String message) {
            return new Result(true, message);
        }

        @Nonnull
        public static Result failed(@Nonnull String message) {
            return new Result(false, message);
        }
    }

    private record BuildSession(@Nonnull String worldName, @Nonnull List<BuildBlock> blocks, @Nonnull List<Holder<EntityStore>> entities, int cursor) {
    }

    private record PendingBuildSession(@Nonnull String worldName, long startAtMs) {
    }

    private record BuildBlock(
            int x,
            int y,
            int z,
            int blockId,
            int rotation,
            int filler,
            int supportValue,
            @Nullable Holder<ChunkStore> state
    ) {
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        @Nonnull
        private static Bounds of(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            return new Bounds(
                    Math.min(minX, maxX),
                    Math.min(minY, maxY),
                    Math.min(minZ, maxZ),
                    Math.max(minX, maxX),
                    Math.max(minY, maxY),
                    Math.max(minZ, maxZ)
            );
        }

        private boolean contains(@Nonnull Vector3d position) {
            return position.x >= minX && position.x <= maxX
                    && position.y >= minY && position.y <= maxY
                    && position.z >= minZ && position.z <= maxZ;
        }
    }
}
