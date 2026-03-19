package dev.hytalemodding.npc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcDefinitionRegistry {
    private static final String DEFINITIONS_RESOURCE = "Common/NpcData/npc-archetypes.properties";
    private static final NpcDefinitionRegistry INSTANCE = new NpcDefinitionRegistry();

    private final ConcurrentHashMap<String, NpcArchetype> byNpcKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> npcKeyByHubRole = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> npcKeyByRunRole = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private NpcDefinitionRegistry() {
    }

    @Nonnull
    public static NpcDefinitionRegistry get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadFromResource();
        if (this.byNpcKey.isEmpty()) {
            installFallbackBlacksmith();
        }
        rebuildRoleIndex();
        System.out.println("[NpcDefs] Loaded archetypes: " + this.byNpcKey.size());
    }

    @Nullable
    public NpcArchetype getArchetype(@Nonnull String npcKey) {
        initialize();
        String normalized = normalize(npcKey);
        NpcArchetype archetype = this.byNpcKey.get(normalized);
        return archetype == null ? null : archetype;
    }

    @Nullable
    public String getNpcKeyByHubRole(@Nonnull String hubRoleName) {
        initialize();
        if (hubRoleName == null || hubRoleName.isBlank()) {
            return null;
        }
        return this.npcKeyByHubRole.get(hubRoleName.trim());
    }

    @Nullable
    public String getNpcKeyByRunRescueRole(@Nonnull String runRoleName) {
        initialize();
        if (runRoleName == null || runRoleName.isBlank()) {
            return null;
        }
        return this.npcKeyByRunRole.get(runRoleName.trim());
    }

    @Nonnull
    public Collection<NpcArchetype> getAll() {
        initialize();
        return Collections.unmodifiableCollection(new ArrayList<>(this.byNpcKey.values()));
    }

    private void loadFromResource() {
        Properties p = new Properties();
        try (InputStream in = NpcDefinitionRegistry.class.getClassLoader().getResourceAsStream(DEFINITIONS_RESOURCE)) {
            if (in == null) {
                System.out.println("[NpcDefs] Resource not found: " + DEFINITIONS_RESOURCE);
                return;
            }
            p.load(in);
        } catch (IOException e) {
            System.out.println("[NpcDefs] Failed to load definitions: " + e.getMessage());
            return;
        }

        List<String> npcKeys = parseCsv(p.getProperty("npcs"));
        for (String npcKey : npcKeys) {
            NpcArchetype archetype = parseArchetype(p, npcKey);
            if (archetype == null || archetype.npcKey.isEmpty()) {
                continue;
            }
            this.byNpcKey.put(archetype.npcKey, archetype);
        }
    }

    @Nullable
    private static NpcArchetype parseArchetype(@Nonnull Properties p, @Nonnull String npcKey) {
        String key = normalize(npcKey);
        if (key.isEmpty()) {
            return null;
        }
        String prefix = "npc." + key + ".";

        String displayName = p.getProperty(prefix + "displayName", key);
        NpcArchetype.NpcCategory category = NpcArchetype.NpcCategory.fromRaw(p.getProperty(prefix + "category"));
        String runRescueRole = normalizeNullable(p.getProperty(prefix + "runRescueRole"));
        String hubRole = normalizeNullable(p.getProperty(prefix + "hubRole"));
        String plotType = normalizeNullable(p.getProperty(prefix + "plotType"));
        String homeTemplateId = normalizeNullable(p.getProperty(prefix + "homeTemplateId"));
        String prePlotQuestId = normalizeNullable(p.getProperty(prefix + "prePlotQuestId"));
        NpcArchetype.PlotUnlockMode plotUnlockMode = NpcArchetype.PlotUnlockMode.fromRaw(p.getProperty(prefix + "plotUnlockMode"));

        NpcArchetype.NpcServices services = NpcArchetype.NpcServices.fromCsv(p.getProperty(prefix + "services"));
        List<String> defaultCraftUnlocks = parseCsv(p.getProperty(prefix + "defaultCraftUnlocks"));
        List<String> defaultTradeUnlocks = parseCsv(p.getProperty(prefix + "defaultTradeUnlocks"));
        List<String> followStateAliases = parseCsv(p.getProperty(prefix + "followStateAliases"));
        boolean animalRoutesToFarmer = Boolean.parseBoolean(p.getProperty(prefix + "animal.routeToFarmer", "false"));
        String farmerNpcKey = normalizeNullable(p.getProperty(prefix + "animal.farmerNpcKey"));

        return new NpcArchetype(
                key,
                category,
                displayName,
                runRescueRole,
                hubRole,
                plotType,
                homeTemplateId,
                prePlotQuestId,
                plotUnlockMode,
                services,
                defaultCraftUnlocks,
                defaultTradeUnlocks,
                followStateAliases,
                animalRoutesToFarmer,
                farmerNpcKey
        );
    }

    private void rebuildRoleIndex() {
        this.npcKeyByHubRole.clear();
        this.npcKeyByRunRole.clear();
        for (Map.Entry<String, NpcArchetype> entry : this.byNpcKey.entrySet()) {
            String hubRole = entry.getValue().hubRole;
            if (hubRole == null || hubRole.isBlank()) {
                // no-op
            } else {
                this.npcKeyByHubRole.put(hubRole, entry.getKey());
            }
            String runRole = entry.getValue().runRescueRole;
            if (runRole == null || runRole.isBlank()) {
                continue;
            }
            this.npcKeyByRunRole.put(runRole, entry.getKey());
        }
    }

    private void installFallbackBlacksmith() {
        NpcArchetype fallback = new NpcArchetype(
                "blacksmith",
                NpcArchetype.NpcCategory.SPECIALIST,
                "Blacksmith",
                "Blacksmith_Escort_Objective",
                "Blacksmith_Escort_Base",
                "blacksmith",
                "blacksmith_tier1",
                null,
                NpcArchetype.PlotUnlockMode.MATERIALS,
                new NpcArchetype.NpcServices(true, true, true, true, true),
                List.of("iron_sword"),
                List.of("basic_armor_trade"),
                List.of("follow"),
                false,
                null
        );
        this.byNpcKey.put(fallback.npcKey, fallback);
    }

    @Nonnull
    private static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
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
    private static List<String> parseCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : csv.split(",")) {
            String value = normalize(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }
}



