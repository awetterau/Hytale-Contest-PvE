package dev.hytalemodding.loot;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public final class LootTableRegistry {
    private static final String RESOURCE_PATH = "Common/LootData/loot-tables.properties";
    private static final int DEFAULT_T1_UNTIL_SECOND = 8 * 60;
    private static final int DEFAULT_T2_UNTIL_SECOND = 16 * 60;
    private static final LootTableRegistry INSTANCE = new LootTableRegistry();

    private final ConcurrentHashMap<String, LootTable> tables = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LootSource> chestSourcesByBlockId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LootSource> blockSourcesByBlockId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LootSource> mobSourcesByRole = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile int t1UntilSecond = DEFAULT_T1_UNTIL_SECOND;
    private volatile int t2UntilSecond = DEFAULT_T2_UNTIL_SECOND;

    private LootTableRegistry() {
    }

    @Nonnull
    public static LootTableRegistry get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadFromResource();
    }

    public synchronized void reload() {
        this.loaded = false;
        initialize();
    }

    public int t1UntilSecond() {
        initialize();
        return this.t1UntilSecond;
    }

    public int t2UntilSecond() {
        initialize();
        return this.t2UntilSecond;
    }

    @Nonnull
    public List<ItemStack> rollChest(@Nullable String blockId, @Nonnull LootTier tier, @Nonnull Random random) {
        initialize();
        return roll(this.chestSourcesByBlockId.get(normalize(blockId)), tier, random);
    }

    @Nonnull
    public List<ItemStack> rollBlock(@Nullable String blockId, @Nonnull LootTier tier, @Nonnull Random random) {
        initialize();
        return roll(this.blockSourcesByBlockId.get(normalize(blockId)), tier, random);
    }

    @Nonnull
    public List<ItemStack> rollMob(@Nullable String roleId, @Nonnull LootTier tier, @Nonnull Random random) {
        initialize();
        return roll(this.mobSourcesByRole.get(normalize(roleId)), tier, random);
    }

    public boolean isConfiguredHarvestBlock(@Nullable String blockId) {
        initialize();
        return this.blockSourcesByBlockId.containsKey(normalize(blockId));
    }

    @Nonnull
    private List<ItemStack> roll(@Nullable LootSource source, @Nonnull LootTier tier, @Nonnull Random random) {
        initialize();
        if (source == null) {
            return List.of();
        }
        String tableId = source.tableFor(tier);
        if (tableId == null || tableId.isBlank()) {
            return List.of();
        }
        LootTable table = this.tables.get(normalize(tableId));
        if (table == null) {
            return List.of();
        }
        return table.roll(random);
    }

    private void loadFromResource() {
        this.tables.clear();
        this.chestSourcesByBlockId.clear();
        this.blockSourcesByBlockId.clear();
        this.mobSourcesByRole.clear();
        this.t1UntilSecond = DEFAULT_T1_UNTIL_SECOND;
        this.t2UntilSecond = DEFAULT_T2_UNTIL_SECOND;

        Properties p = new Properties();
        try (InputStream in = LootTableRegistry.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return;
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[LootTableRegistry] Failed to load " + RESOURCE_PATH + ": " + e.getMessage());
            return;
        }

        this.t1UntilSecond = Math.max(1, parseInt(p.getProperty("tier.t1.untilSecond"), DEFAULT_T1_UNTIL_SECOND));
        this.t2UntilSecond = Math.max(this.t1UntilSecond + 1, parseInt(p.getProperty("tier.t2.untilSecond"), DEFAULT_T2_UNTIL_SECOND));

        for (String tableId : parseCsv(p.getProperty("tables"))) {
            LootTable table = parseTable(p, tableId);
            if (table != null) {
                this.tables.put(table.tableId, table);
            }
        }
        for (String sourceId : parseCsv(p.getProperty("sources"))) {
            LootSource source = parseSource(p, sourceId);
            if (source == null) {
                continue;
            }
            Map<String, LootSource> target = switch (source.type) {
                case CHEST -> this.chestSourcesByBlockId;
                case BLOCK -> this.blockSourcesByBlockId;
                case MOB -> this.mobSourcesByRole;
            };
            for (String key : source.lookupKeys) {
                target.put(normalize(key), source);
            }
        }
    }

    @Nullable
    private static LootTable parseTable(@Nonnull Properties p, @Nonnull String rawTableId) {
        String tableId = normalize(rawTableId);
        if (tableId.isBlank()) {
            return null;
        }
        String prefix = "table." + tableId + ".";
        Range rolls = Range.parse(p.getProperty(prefix + "rolls"), 1, 1);
        ArrayList<LootEntry> entries = new ArrayList<>();
        p.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix + "entry."))
                .sorted(Comparator.naturalOrder())
                .forEach(key -> {
                    LootEntry entry = LootEntry.parse(p.getProperty(key));
                    if (entry != null) {
                        entries.add(entry);
                    }
                });
        if (entries.isEmpty()) {
            return null;
        }
        return new LootTable(tableId, rolls, entries);
    }

    @Nullable
    private static LootSource parseSource(@Nonnull Properties p, @Nonnull String rawSourceId) {
        String sourceId = normalize(rawSourceId);
        if (sourceId.isBlank()) {
            return null;
        }
        String prefix = "source." + sourceId + ".";
        SourceType type = SourceType.fromRaw(p.getProperty(prefix + "type"));
        List<String> lookupKeys = switch (type) {
            case CHEST, BLOCK -> parseCsv(p.getProperty(prefix + "blockIds"));
            case MOB -> parseCsv(p.getProperty(prefix + "roles"));
        };
        if (lookupKeys.isEmpty()) {
            return null;
        }
        HashMap<LootTier, String> tablesByTier = new HashMap<>();
        for (LootTier tier : LootTier.values()) {
            tablesByTier.put(tier, normalize(p.getProperty(prefix + tier.configKey())));
        }
        return new LootSource(sourceId, type, lookupKeys, tablesByTier);
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String value = token == null ? "" : token.trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static int parseInt(@Nullable String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nonnull
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private enum SourceType {
        CHEST,
        BLOCK,
        MOB;

        @Nonnull
        private static SourceType fromRaw(@Nullable String raw) {
            String normalized = normalize(raw);
            if ("block".equals(normalized)) {
                return BLOCK;
            }
            if ("mob".equals(normalized)) {
                return MOB;
            }
            return CHEST;
        }
    }

    private record LootSource(
            @Nonnull String sourceId,
            @Nonnull SourceType type,
            @Nonnull List<String> lookupKeys,
            @Nonnull Map<LootTier, String> tablesByTier
    ) {
        @Nullable
        private String tableFor(@Nonnull LootTier tier) {
            return this.tablesByTier.get(tier);
        }
    }

    private record LootTable(
            @Nonnull String tableId,
            @Nonnull Range rolls,
            @Nonnull List<LootEntry> entries
    ) {
        @Nonnull
        private List<ItemStack> roll(@Nonnull Random random) {
            int rollCount = this.rolls.roll(random);
            if (rollCount <= 0) {
                return List.of();
            }
            ArrayList<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < rollCount; i++) {
                LootEntry entry = pickEntry(random);
                if (entry == null) {
                    continue;
                }
                int amount = entry.amount.roll(random);
                if (amount > 0) {
                    items.add(new ItemStack(entry.itemId, amount));
                }
            }
            return List.copyOf(items);
        }

        @Nullable
        private LootEntry pickEntry(@Nonnull Random random) {
            int totalWeight = 0;
            for (LootEntry entry : this.entries) {
                totalWeight += Math.max(0, entry.weight);
            }
            if (totalWeight <= 0) {
                return null;
            }
            int roll = random.nextInt(totalWeight);
            int cursor = 0;
            for (LootEntry entry : this.entries) {
                cursor += Math.max(0, entry.weight);
                if (roll < cursor) {
                    return entry;
                }
            }
            return null;
        }
    }

    private record LootEntry(@Nonnull String itemId, @Nonnull Range amount, int weight) {
        @Nullable
        private static LootEntry parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.split(":");
            if (parts.length < 3) {
                return null;
            }
            String itemId = parts[0].trim();
            if (itemId.isBlank()) {
                return null;
            }
            Range amount = Range.parse(parts[1], 1, 1);
            int weight = Math.max(0, parseInt(parts[2], 0));
            if (weight <= 0) {
                return null;
            }
            return new LootEntry(itemId, amount, weight);
        }
    }

    private record Range(int min, int max) {
        @Nonnull
        private static Range parse(@Nullable String raw, int fallbackMin, int fallbackMax) {
            if (raw == null || raw.isBlank()) {
                return new Range(fallbackMin, Math.max(fallbackMin, fallbackMax));
            }
            String[] parts = raw.trim().split("-");
            int min = parseInt(parts[0], fallbackMin);
            int max = parts.length > 1 ? parseInt(parts[1], min) : min;
            return new Range(Math.max(0, Math.min(min, max)), Math.max(0, Math.max(min, max)));
        }

        private int roll(@Nonnull Random random) {
            if (this.max <= this.min) {
                return this.min;
            }
            return this.min + random.nextInt(this.max - this.min + 1);
        }
    }
}
