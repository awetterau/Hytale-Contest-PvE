package dev.hytalemodding.game;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class BaseHousingManager {
    private static final String PLUGIN_CONFIG_DIR = "HytaleModding-ExamplePlugin";
    private static final String CONFIG_FILE_NAME = "base-housing.properties";
    private static final String BLACKSMITH_KEY = "blacksmith";
    private static final String BLACKSMITH_ROLE = "Blacksmith_Escort_Base";
    private static final String BUILDING_BLOCK = "Rock_Chalk_Brick_Decorative";
    private static final BaseHousingManager INSTANCE = new BaseHousingManager();

    private final ConcurrentHashMap<String, PlotData> plots = new ConcurrentHashMap<>();
    private boolean loaded;

    private BaseHousingManager() {
    }

    @Nonnull
    public static BaseHousingManager get() {
        return INSTANCE;
    }

    public synchronized boolean addOrUpdatePlot(
            @Nonnull String id,
            @Nonnull String worldName,
            @Nonnull Vector3i markerPos,
            @Nonnull Transform homeTransform
    ) {
        ensureLoaded();
        PlotData existing = this.plots.get(id);
        String assignedNpc = existing == null ? null : existing.assignedNpcKey;
        boolean built = existing != null && existing.built;
        this.plots.put(id, new PlotData(id, worldName, new Vector3i(markerPos), copyTransform(homeTransform), assignedNpc, built));
        saveQuietly();
        return existing == null;
    }

    public synchronized boolean removePlot(@Nonnull String id) {
        ensureLoaded();
        PlotData removed = this.plots.remove(id);
        if (removed == null) {
            return false;
        }
        saveQuietly();
        return true;
    }

    public synchronized boolean setPlotHome(@Nonnull String id, @Nonnull Transform homeTransform) {
        ensureLoaded();
        PlotData plot = this.plots.get(id);
        if (plot == null) {
            return false;
        }
        this.plots.put(id, plot.withHome(copyTransform(homeTransform)));
        saveQuietly();
        return true;
    }

    public synchronized boolean clearAssignment(@Nonnull String id) {
        ensureLoaded();
        PlotData plot = this.plots.get(id);
        if (plot == null) {
            return false;
        }
        this.plots.put(id, plot.withAssignment(null, plot.built));
        saveQuietly();
        return true;
    }

    public synchronized void resetAll() {
        ensureLoaded();
        this.plots.clear();
        saveQuietly();
    }

    @Nonnull
    public synchronized List<String> getPlotIdsForWorld(@Nonnull String worldName) {
        ensureLoaded();
        List<String> ids = new ArrayList<>();
        for (PlotData plot : this.plots.values()) {
            if (plot.worldName.equalsIgnoreCase(worldName)) {
                ids.add(plot.id);
            }
        }
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    @Nonnull
    public synchronized List<String> describePlots() {
        ensureLoaded();
        List<String> lines = new ArrayList<>();
        if (this.plots.isEmpty()) {
            lines.add("<none>");
            return lines;
        }
        for (PlotData plot : this.plots.values()) {
            lines.add(plot.id + " world=" + plot.worldName
                    + " marker=(" + plot.markerPos.x + "," + plot.markerPos.y + "," + plot.markerPos.z + ")"
                    + " assigned=" + (plot.assignedNpcKey == null ? "<none>" : plot.assignedNpcKey)
                    + " built=" + plot.built);
        }
        return lines;
    }

    @Nullable
    public synchronized PlotData findPlotByMarker(@Nonnull String worldName, @Nonnull Vector3i markerPos) {
        ensureLoaded();
        for (PlotData plot : this.plots.values()) {
            if (plot.worldName.equalsIgnoreCase(worldName)
                    && plot.markerPos.x == markerPos.x
                    && plot.markerPos.y == markerPos.y
                    && plot.markerPos.z == markerPos.z) {
                return plot.copy();
            }
        }
        return null;
    }

    @Nullable
    public synchronized PlotData getPlot(@Nonnull String plotId) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        return plot == null ? null : plot.copy();
    }

    @Nonnull
    public synchronized List<String> getEligibleNpcKeysForPlot(@Nonnull String plotId) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null || plot.assignedNpcKey != null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (isNpcRescued(BLACKSMITH_KEY) && !isNpcAssigned(BLACKSMITH_KEY)) {
            keys.add(BLACKSMITH_KEY);
        }
        return keys;
    }

    @Nonnull
    public synchronized AssignmentResult assignNpcToPlot(@Nonnull String plotId, @Nonnull String npcKey) {
        ensureLoaded();
        PlotData plot = this.plots.get(plotId);
        if (plot == null) {
            return AssignmentResult.fail("Unknown plot: " + plotId);
        }
        if (plot.assignedNpcKey != null) {
            return AssignmentResult.fail("This plot is already occupied.");
        }
        if (!isNpcRescued(npcKey)) {
            return AssignmentResult.fail("That NPC is not rescued yet.");
        }
        if (isNpcAssigned(npcKey)) {
            return AssignmentResult.fail("That NPC is already assigned to another plot.");
        }
        World world = Universe.get().getWorld(plot.worldName);
        if (world == null) {
            return AssignmentResult.fail("Plot world is not loaded: " + plot.worldName);
        }
        if (!BLACKSMITH_KEY.equals(npcKey)) {
            return AssignmentResult.fail("Unsupported NPC key: " + npcKey);
        }

        buildBlacksmithHome(world, plot.markerPos);
        world.setBlock(plot.markerPos.x, plot.markerPos.y, plot.markerPos.z, "Empty");
        ensureNpcAtHome(world, BLACKSMITH_KEY, plot.homeTransform);

        PlotData updated = plot.withAssignment(npcKey, true);
        this.plots.put(plotId, updated);
        saveQuietly();
        return AssignmentResult.ok("Assigned " + npcKey + " to plot " + plotId + ".");
    }

    public synchronized void ensureAssignmentsInWorld(@Nonnull World world) {
        ensureLoaded();
        String worldName = world.getName();
        Collection<PlotData> snapshot = new ArrayList<>(this.plots.values());
        for (PlotData plot : snapshot) {
            if (!plot.worldName.equalsIgnoreCase(worldName) || plot.assignedNpcKey == null) {
                continue;
            }
            if (!plot.built) {
                continue;
            }
            ensureNpcAtHome(world, plot.assignedNpcKey, plot.homeTransform);
        }
    }

    public synchronized int removeAllBaseBlacksmithsInWorld(@Nonnull World world) {
        int removed = 0;
        Store<EntityStore> store = world.getEntityStore().getStore();
        Collection<Ref<EntityStore>> refs = findAllNpcByRole(store, BLACKSMITH_ROLE);
        for (Ref<EntityStore> ref : refs) {
            NPCEntity npc = ref == null || !ref.isValid() ? null : store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null) {
                npc.setToDespawn();
                store.putComponent(ref, NPCEntity.getComponentType(), npc);
                removed++;
            }
        }
        return removed;
    }

    private void ensureNpcAtHome(@Nonnull World world, @Nonnull String npcKey, @Nonnull Transform homeTransform) {
        if (!BLACKSMITH_KEY.equals(npcKey)) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> existing = findNpcByRole(store, BLACKSMITH_ROLE);
        if (existing == null || !existing.isValid()) {
            spawnNpc(world, BLACKSMITH_ROLE, homeTransform);
            return;
        }

        TransformComponent transform = store.getComponent(existing, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        NPCEntity npc = store.getComponent(existing, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }

        Vector3d home = homeTransform.getPosition();
        npc.saveLeashInformation(new Vector3d(home), new Vector3f(homeTransform.getRotation()));
        store.putComponent(existing, NPCEntity.getComponentType(), npc);

        Vector3d pos = transform.getPosition();
        double dx = pos.getX() - home.getX();
        double dy = pos.getY() - home.getY();
        double dz = pos.getZ() - home.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > 4.0 && npc.getRole() != null && npc.getRole().getStateSupport() != null && !npc.getRole().getStateSupport().isInBusyState()) {
            npc.getRole().getStateSupport().setState(existing, "Idle", null, store);
        }
    }

    private static void spawnNpc(@Nonnull World world, @Nonnull String roleName, @Nonnull Transform transform) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(roleName);
        BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
        if (roleInfo == null || !roleInfo.getBuilder().isSpawnable()) {
            return;
        }

        Vector3d spawnPos = new Vector3d(transform.getPosition());
        Vector3f spawnRot = new Vector3f(transform.getRotation());
        TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> postSpawn = (npcEntity, npcRef, entityStore) ->
                entityStore.putComponent(npcRef, Interactable.getComponentType(), Interactable.INSTANCE);
        Pair<Ref<EntityStore>, NPCEntity> pair = npcPlugin.spawnEntity(
                world.getEntityStore().getStore(),
                roleIndex,
                spawnPos,
                spawnRot,
                null,
                postSpawn
        );
        if (pair == null || pair.first() == null || !pair.first().isValid()) {
            return;
        }
    }

    private static void buildBlacksmithHome(@Nonnull World world, @Nonnull Vector3i origin) {
        int ox = origin.x;
        int oy = origin.y;
        int oz = origin.z;

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(ox + x, oy, oz + z, BUILDING_BLOCK);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    boolean wall = x == -3 || x == 3 || z == -3 || z == 3;
                    boolean doorway = (z == -3 && y <= 2 && x >= -1 && x <= 1);
                    if (wall && !doorway) {
                        world.setBlock(ox + x, oy + y, oz + z, BUILDING_BLOCK);
                    } else {
                        world.setBlock(ox + x, oy + y, oz + z, "Empty");
                    }
                }
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(ox + x, oy + 4, oz + z, BUILDING_BLOCK);
            }
        }
    }

    @Nullable
    private static Ref<EntityStore> findNpcByRole(@Nonnull Store<EntityStore> store, @Nonnull String roleName) {
        final Ref<EntityStore>[] found = new Ref[]{null};
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            if (found[0] != null) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !roleName.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found[0] = ref;
                    return;
                }
            }
        });
        return found[0];
    }

    @Nonnull
    private static Collection<Ref<EntityStore>> findAllNpcByRole(@Nonnull Store<EntityStore> store, @Nonnull String roleName) {
        List<Ref<EntityStore>> found = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || !roleName.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    found.add(ref);
                }
            }
        });
        return found;
    }

    private boolean isNpcRescued(@Nonnull String npcKey) {
        if (BLACKSMITH_KEY.equals(npcKey)) {
            return GameFlowConfigManager.get().isBlacksmithRescued();
        }
        return false;
    }

    private boolean isNpcAssigned(@Nonnull String npcKey) {
        for (PlotData plot : this.plots.values()) {
            if (npcKey.equals(plot.assignedNpcKey)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        Path path = getConfigFilePath();
        if (path == null || !Files.exists(path)) {
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.out.println("[BaseHousing] Failed to load config: " + e.getMessage());
            return;
        }

        String ids = properties.getProperty("plots", "");
        if (ids.isBlank()) {
            return;
        }
        for (String rawId : ids.split(",")) {
            String id = rawId.trim();
            if (id.isEmpty()) {
                continue;
            }
            PlotData plot = readPlot(properties, id);
            if (plot != null) {
                this.plots.put(id, plot);
            }
        }
    }

    private synchronized void saveQuietly() {
        Path path = getConfigFilePath();
        if (path == null) {
            return;
        }
        Properties properties = new Properties();
        String joinedIds = String.join(",", this.plots.keySet());
        properties.setProperty("plots", joinedIds);
        for (Map.Entry<String, PlotData> entry : this.plots.entrySet()) {
            writePlot(properties, entry.getValue());
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Base housing configuration");
            }
        } catch (IOException e) {
            System.out.println("[BaseHousing] Failed to save config: " + e.getMessage());
        }
    }

    @Nullable
    private static PlotData readPlot(@Nonnull Properties p, @Nonnull String id) {
        String prefix = "plot." + id + ".";
        String world = p.getProperty(prefix + "world");
        Integer mx = readInt(p.getProperty(prefix + "marker.x"));
        Integer my = readInt(p.getProperty(prefix + "marker.y"));
        Integer mz = readInt(p.getProperty(prefix + "marker.z"));
        Double hx = readDouble(p.getProperty(prefix + "home.pos.x"));
        Double hy = readDouble(p.getProperty(prefix + "home.pos.y"));
        Double hz = readDouble(p.getProperty(prefix + "home.pos.z"));
        Double hrx = readDouble(p.getProperty(prefix + "home.rot.x"));
        Double hry = readDouble(p.getProperty(prefix + "home.rot.y"));
        Double hrz = readDouble(p.getProperty(prefix + "home.rot.z"));
        if (world == null || mx == null || my == null || mz == null
                || hx == null || hy == null || hz == null || hrx == null || hry == null || hrz == null) {
            return null;
        }
        String assigned = normalizeNullable(p.getProperty(prefix + "assignedNpc"));
        boolean built = Boolean.parseBoolean(p.getProperty(prefix + "built", "false"));
        Transform home = new Transform(
                new Vector3d(hx, hy, hz),
                new Vector3f(hrx.floatValue(), hry.floatValue(), hrz.floatValue())
        );
        return new PlotData(id, world, new Vector3i(mx, my, mz), home, assigned, built);
    }

    private static void writePlot(@Nonnull Properties p, @Nonnull PlotData plot) {
        String prefix = "plot." + plot.id + ".";
        p.setProperty(prefix + "world", plot.worldName);
        p.setProperty(prefix + "marker.x", Integer.toString(plot.markerPos.x));
        p.setProperty(prefix + "marker.y", Integer.toString(plot.markerPos.y));
        p.setProperty(prefix + "marker.z", Integer.toString(plot.markerPos.z));
        p.setProperty(prefix + "home.pos.x", Double.toString(plot.homeTransform.getPosition().getX()));
        p.setProperty(prefix + "home.pos.y", Double.toString(plot.homeTransform.getPosition().getY()));
        p.setProperty(prefix + "home.pos.z", Double.toString(plot.homeTransform.getPosition().getZ()));
        p.setProperty(prefix + "home.rot.x", Float.toString(plot.homeTransform.getRotation().getX()));
        p.setProperty(prefix + "home.rot.y", Float.toString(plot.homeTransform.getRotation().getY()));
        p.setProperty(prefix + "home.rot.z", Float.toString(plot.homeTransform.getRotation().getZ()));
        p.setProperty(prefix + "assignedNpc", plot.assignedNpcKey == null ? "" : plot.assignedNpcKey);
        p.setProperty(prefix + "built", Boolean.toString(plot.built));
    }

    @Nullable
    private static Path getConfigFilePath() {
        try {
            Path universePath = Universe.get().getPath();
            if (universePath == null) {
                return null;
            }
            return universePath.resolve("plugins").resolve(PLUGIN_CONFIG_DIR).resolve(CONFIG_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Integer readInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static Double readDouble(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static String normalizeNullable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nonnull
    private static Transform copyTransform(@Nonnull Transform transform) {
        return new Transform(new Vector3d(transform.getPosition()), new Vector3f(transform.getRotation()));
    }

    public static final class AssignmentResult {
        public final boolean success;
        @Nonnull
        public final String message;

        private AssignmentResult(boolean success, @Nonnull String message) {
            this.success = success;
            this.message = message;
        }

        @Nonnull
        public static AssignmentResult ok(@Nonnull String message) {
            return new AssignmentResult(true, message);
        }

        @Nonnull
        public static AssignmentResult fail(@Nonnull String message) {
            return new AssignmentResult(false, message);
        }
    }

    public static final class PlotData {
        @Nonnull
        public final String id;
        @Nonnull
        public final String worldName;
        @Nonnull
        public final Vector3i markerPos;
        @Nonnull
        public final Transform homeTransform;
        @Nullable
        public final String assignedNpcKey;
        public final boolean built;

        private PlotData(
                @Nonnull String id,
                @Nonnull String worldName,
                @Nonnull Vector3i markerPos,
                @Nonnull Transform homeTransform,
                @Nullable String assignedNpcKey,
                boolean built
        ) {
            this.id = id;
            this.worldName = worldName;
            this.markerPos = markerPos;
            this.homeTransform = homeTransform;
            this.assignedNpcKey = assignedNpcKey;
            this.built = built;
        }

        @Nonnull
        private PlotData withHome(@Nonnull Transform home) {
            return new PlotData(this.id, this.worldName, new Vector3i(this.markerPos), home, this.assignedNpcKey, this.built);
        }

        @Nonnull
        private PlotData withAssignment(@Nullable String npcKey, boolean built) {
            return new PlotData(
                    this.id,
                    this.worldName,
                    new Vector3i(this.markerPos),
                    copyTransform(this.homeTransform),
                    npcKey,
                    built
            );
        }

        @Nonnull
        private PlotData copy() {
            return new PlotData(
                    this.id,
                    this.worldName,
                    new Vector3i(this.markerPos),
                    copyTransform(this.homeTransform),
                    this.assignedNpcKey,
                    this.built
            );
        }
    }
}
