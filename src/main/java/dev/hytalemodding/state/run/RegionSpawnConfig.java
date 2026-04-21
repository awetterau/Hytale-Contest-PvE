package dev.hytalemodding.state.run;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class RegionSpawnConfig {
    private static final String RESOURCE_PATH = "Common/NpcData/region-spawns.properties";
    private static final RegionSpawnConfig INSTANCE = new RegionSpawnConfig();

    private volatile boolean enabled = true;
    private volatile int tier2Second = 480;
    private volatile int tier3Second = 960;
    private volatile int rooterTier = 3;
    private volatile String rooterRole = "Rooter_Man_Boss_P1";
    private volatile String rooterRegion = "t3";
    private volatile Map<String, RegionDefinition> regions = Map.of();
    private volatile Map<String, SpawnTable> tables = Map.of();
    private volatile Map<String, MobShape> mobShapes = Map.of();
    private volatile Map<String, String> regionByMarkerBlock = Map.of();

    private RegionSpawnConfig() {
    }

    @Nonnull
    public static RegionSpawnConfig get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        reload();
    }

    public synchronized void reload() {
        Properties properties = new Properties();
        try (InputStream in = RegionSpawnConfig.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                properties.load(in);
            } else {
                System.out.println("[RegionSpawnConfig] Resource not found: " + RESOURCE_PATH);
            }
        } catch (Exception e) {
            System.out.println("[RegionSpawnConfig] Failed to load: " + e.getMessage());
        }

        this.enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
        this.tier2Second = readInt(properties.getProperty("tier.t2.second"), 480);
        this.tier3Second = readInt(properties.getProperty("tier.t3.second"), 960);
        this.rooterTier = readInt(properties.getProperty("rooter.tier"), 3);
        this.rooterRole = stringOrDefault(properties.getProperty("rooter.role"), "Rooter_Man_Boss_P1");
        this.rooterRegion = stringOrDefault(properties.getProperty("rooter.region"), "t3");

        LinkedHashMap<String, SpawnTable> nextTables = new LinkedHashMap<>();
        for (String tableId : parseCsv(properties.getProperty("tables"))) {
            SpawnTable table = parseTable(tableId, properties);
            if (!table.entries().isEmpty()) {
                nextTables.put(tableId, table);
            }
        }

        LinkedHashMap<String, RegionDefinition> nextRegions = new LinkedHashMap<>();
        HashMap<String, String> nextRegionByMarker = new HashMap<>();
        for (String regionId : parseCsv(properties.getProperty("regions"))) {
            String prefix = "region." + regionId + ".";
            String markerBlock = stringOrDefault(properties.getProperty(prefix + "markerBlock"), "");
            if (markerBlock.isBlank()) {
                continue;
            }
            RegionDefinition region = new RegionDefinition(
                    regionId,
                    markerBlock,
                    Math.max(1, readInt(properties.getProperty(prefix + "radius"), 45)),
                    Math.max(1, readInt(properties.getProperty(prefix + "maxAttemptsPerMob"), 40)),
                    Math.max(0, readInt(properties.getProperty(prefix + "minDistanceFromPlayers"), 20)),
                    properties.getProperty(prefix + "table.t1", ""),
                    properties.getProperty(prefix + "table.t2", ""),
                    properties.getProperty(prefix + "table.t3", "")
            );
            nextRegions.put(regionId, region);
            nextRegionByMarker.put(normalize(markerBlock), regionId);
        }

        HashMap<String, MobShape> nextShapes = new HashMap<>();
        MobShape defaultShape = new MobShape(
                Math.max(1, readInt(properties.getProperty("mob.default.height"), 2)),
                Math.max(0, readInt(properties.getProperty("mob.default.clearanceRadius"), 1))
        );
        nextShapes.put("default", defaultShape);
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("mob.") || !key.endsWith(".height")) {
                continue;
            }
            String roleId = key.substring("mob.".length(), key.length() - ".height".length());
            if ("default".equalsIgnoreCase(roleId)) {
                continue;
            }
            int height = Math.max(1, readInt(properties.getProperty(key), defaultShape.height()));
            int radius = Math.max(0, readInt(properties.getProperty("mob." + roleId + ".clearanceRadius"), defaultShape.clearanceRadius()));
            nextShapes.put(normalize(roleId), new MobShape(height, radius));
        }

        this.tables = Map.copyOf(nextTables);
        this.regions = Map.copyOf(nextRegions);
        this.regionByMarkerBlock = Map.copyOf(nextRegionByMarker);
        this.mobShapes = Map.copyOf(nextShapes);
        System.out.println("[RegionSpawnConfig] Loaded regions=" + this.regions.size() + " tables=" + this.tables.size());
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public int tier2Second() {
        return this.tier2Second;
    }

    public int tier3Second() {
        return this.tier3Second;
    }

    public int rooterTier() {
        return this.rooterTier;
    }

    @Nonnull
    public String rooterRole() {
        return this.rooterRole;
    }

    @Nonnull
    public String rooterRegion() {
        return this.rooterRegion;
    }

    @Nullable
    public RegionDefinition getRegion(@Nonnull String regionId) {
        return this.regions.get(regionId);
    }

    @Nullable
    public SpawnTable getTable(@Nullable String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return null;
        }
        return this.tables.get(tableId.trim());
    }

    @Nonnull
    public MobShape getMobShape(@Nonnull String roleId) {
        MobShape shape = this.mobShapes.get(normalize(roleId));
        if (shape != null) {
            return shape;
        }
        return this.mobShapes.getOrDefault("default", new MobShape(2, 1));
    }

    @Nullable
    public String findRegionForMarkerBlock(@Nullable String blockId) {
        String normalized = normalizeBlockTypeId(blockId);
        return this.regionByMarkerBlock.get(normalize(normalized));
    }

    @Nullable
    public String tableForTier(@Nonnull RegionDefinition region, int tier) {
        return switch (tier) {
            case 1 -> region.tableT1();
            case 2 -> region.tableT2();
            case 3 -> region.tableT3();
            default -> null;
        };
    }

    @Nonnull
    private static SpawnTable parseTable(@Nonnull String tableId, @Nonnull Properties properties) {
        ArrayList<SpawnEntry> entries = new ArrayList<>();
        String prefix = "table." + tableId + ".entry.";
        for (int i = 1; i < 256; i++) {
            String value = properties.getProperty(prefix + i);
            if (value == null || value.isBlank()) {
                continue;
            }
            SpawnEntry entry = parseEntry(value);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new SpawnTable(tableId, List.copyOf(entries));
    }

    @Nullable
    private static SpawnEntry parseEntry(@Nonnull String value) {
        String[] parts = value.trim().split(":");
        if (parts.length < 2) {
            return null;
        }
        String roleId = parts[0].trim();
        if (roleId.isBlank()) {
            return null;
        }
        int min = 1;
        int max = 1;
        String range = parts[1].trim();
        int dash = range.indexOf('-');
        if (dash >= 0) {
            min = readInt(range.substring(0, dash), 1);
            max = readInt(range.substring(dash + 1), min);
        } else {
            min = readInt(range, 1);
            max = min;
        }
        if (max < min) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return new SpawnEntry(roleId, Math.max(0, min), Math.max(0, max));
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    private static int readInt(@Nullable String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nonnull
    private static String stringOrDefault(@Nullable String value, @Nonnull String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String normalizeBlockTypeId(@Nullable String blockId) {
        if (blockId == null) {
            return "";
        }
        String normalized = blockId.startsWith("*") ? blockId.substring(1) : blockId;
        int stateIndex = normalized.indexOf("_State_Definitions_");
        if (stateIndex >= 0) {
            normalized = normalized.substring(0, stateIndex);
        }
        return normalized.trim();
    }

    public record RegionDefinition(
            @Nonnull String id,
            @Nonnull String markerBlock,
            int radius,
            int maxAttemptsPerMob,
            int minDistanceFromPlayers,
            @Nonnull String tableT1,
            @Nonnull String tableT2,
            @Nonnull String tableT3
    ) {
    }

    public record SpawnTable(@Nonnull String id, @Nonnull List<SpawnEntry> entries) {
    }

    public record SpawnEntry(@Nonnull String roleId, int minCount, int maxCount) {
    }

    public record MobShape(int height, int clearanceRadius) {
    }
}
