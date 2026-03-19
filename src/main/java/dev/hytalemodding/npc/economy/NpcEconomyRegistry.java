package dev.hytalemodding.npc.economy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class NpcEconomyRegistry {
    private static final String ECONOMY_ROOT = "Common/NpcData/npcs";
    private static final NpcEconomyRegistry INSTANCE = new NpcEconomyRegistry();

    private final ConcurrentHashMap<String, NpcEconomyDefinition> byNpcKey = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private NpcEconomyRegistry() {
    }

    @Nonnull
    public static NpcEconomyRegistry get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadAll();
        System.out.println("[NpcEconomy] Loaded npc economy files: " + this.byNpcKey.size());
    }

    @Nullable
    public NpcEconomyDefinition getNpc(@Nonnull String npcKey) {
        initialize();
        return this.byNpcKey.get(normalize(npcKey));
    }

    @Nonnull
    public Set<String> getNpcKeys() {
        initialize();
        return Set.copyOf(this.byNpcKey.keySet());
    }

    private void loadAll() {
        this.byNpcKey.clear();
        for (String resourcePath : discoverEconomyFiles()) {
            NpcEconomyDefinition parsed = parseResource(resourcePath);
            if (parsed == null || parsed.npcKey.isBlank()) {
                continue;
            }
            this.byNpcKey.put(parsed.npcKey, parsed);
        }
    }

    @Nonnull
    private List<String> discoverEconomyFiles() {
        ArrayList<String> out = new ArrayList<>();
        ClassLoader classLoader = NpcEconomyRegistry.class.getClassLoader();
        try {
            Enumeration<URL> roots = classLoader.getResources(ECONOMY_ROOT);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                collectFromUrl(url, out);
            }
        } catch (IOException e) {
            System.out.println("[NpcEconomy] Failed to discover files from " + ECONOMY_ROOT + ": " + e.getMessage());
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    private static void collectFromUrl(@Nonnull URL url, @Nonnull List<String> out) {
        String protocol = url.getProtocol();
        if ("jar".equalsIgnoreCase(protocol)) {
            collectFromJar(url, out);
            return;
        }
        if ("file".equalsIgnoreCase(protocol)) {
            collectFromDirectory(url, out);
        }
    }

    private static void collectFromJar(@Nonnull URL url, @Nonnull List<String> out) {
        try {
            URLConnection connection = url.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                return;
            }
            try (JarFile jar = jarConnection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (name.startsWith(ECONOMY_ROOT + "/") && name.endsWith(".properties")) {
                        out.add(name);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[NpcEconomy] Failed reading jar resources: " + e.getMessage());
        }
    }

    private static void collectFromDirectory(@Nonnull URL url, @Nonnull List<String> out) {
        try {
            java.nio.file.Path rootPath = java.nio.file.Path.of(url.toURI());
            if (!java.nio.file.Files.isDirectory(rootPath)) {
                return;
            }
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(rootPath, 1)) {
                stream.filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".properties"))
                        .forEach(path -> out.add(ECONOMY_ROOT + "/" + path.getFileName()));
            }
        } catch (Exception e) {
            System.out.println("[NpcEconomy] Failed reading file resources: " + e.getMessage());
        }
    }

    @Nullable
    private static NpcEconomyDefinition parseResource(@Nonnull String resourcePath) {
        Properties p = new Properties();
        try (InputStream in = NpcEconomyRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[NpcEconomy] Failed to load " + resourcePath + ": " + e.getMessage());
            return null;
        }

        String npcKey = normalize(firstNonBlank(
                p.getProperty("npc.key"),
                stripSuffix(resourcePath.substring(resourcePath.lastIndexOf('/') + 1), ".properties")
        ));
        if (npcKey.isBlank()) {
            return null;
        }

        String readyDialogueText = firstNonBlank(
                p.getProperty("npc.readyDialogueText"),
                "I am ready. What do you need?"
        );
        String noWorkshopDialogueText = firstNonBlank(
                p.getProperty("npc.noWorkshopDialogueText"),
                "I need a workshop before I can help."
        );

        List<NpcEconomyDefinition.OfferDefinition> offers = new ArrayList<>();
        for (String offerId : parseCsv(p.getProperty("offers"))) {
            NpcEconomyDefinition.OfferDefinition offer = parseOffer(p, offerId);
            if (offer != null) {
                offers.add(offer);
            }
        }

        List<NpcEconomyDefinition.UpgradeDefinition> upgrades = new ArrayList<>();
        for (String upgradeId : parseCsv(p.getProperty("upgrades"))) {
            NpcEconomyDefinition.UpgradeDefinition upgrade = parseUpgrade(p, upgradeId);
            if (upgrade != null) {
                upgrades.add(upgrade);
            }
        }

        return new NpcEconomyDefinition(npcKey, readyDialogueText, noWorkshopDialogueText, offers, upgrades);
    }

    @Nullable
    private static NpcEconomyDefinition.OfferDefinition parseOffer(@Nonnull Properties p, @Nonnull String offerId) {
        String key = normalize(offerId);
        if (key.isBlank()) {
            return null;
        }
        String prefix = "offer." + key + ".";
        NpcEconomyDefinition.OfferKind kind = NpcEconomyDefinition.OfferKind.fromRaw(p.getProperty(prefix + "kind"));
        String title = firstNonBlank(p.getProperty(prefix + "title"), key);
        List<NpcEconomyDefinition.ItemAmount> cost = parseItems(p.getProperty(prefix + "cost"));
        List<NpcEconomyDefinition.ItemAmount> reward = parseItems(p.getProperty(prefix + "reward"));
        if (reward.isEmpty()) {
            System.out.println("[NpcEconomy] offer " + key + " skipped: no reward items");
            return null;
        }
        Set<String> requiredFlags = parseCsvSet(p.getProperty(prefix + "requireFlags"));
        int requiredTier = parseIntOrDefault(p.getProperty(prefix + "requireTier"), 0);
        return new NpcEconomyDefinition.OfferDefinition(
                key,
                title,
                kind,
                cost,
                reward,
                requiredFlags,
                requiredTier
        );
    }

    @Nullable
    private static NpcEconomyDefinition.UpgradeDefinition parseUpgrade(@Nonnull Properties p, @Nonnull String upgradeId) {
        String key = normalize(upgradeId);
        if (key.isBlank()) {
            return null;
        }
        String prefix = "upgrade." + key + ".";
        String title = firstNonBlank(p.getProperty(prefix + "title"), key);
        int targetTier = parseIntOrDefault(p.getProperty(prefix + "tier"), 1);
        List<NpcEconomyDefinition.ItemAmount> cost = parseItems(p.getProperty(prefix + "cost"));
        Set<String> requiredFlags = parseCsvSet(p.getProperty(prefix + "requireFlags"));
        Set<String> grantCrafts = parseCsvSet(p.getProperty(prefix + "grantCrafts"));
        Set<String> grantTrades = parseCsvSet(p.getProperty(prefix + "grantTrades"));
        Set<String> setFlags = parseCsvSet(p.getProperty(prefix + "setFlags"));
        return new NpcEconomyDefinition.UpgradeDefinition(
                key,
                title,
                targetTier,
                cost,
                requiredFlags,
                grantCrafts,
                grantTrades,
                setFlags
        );
    }

    @Nonnull
    private static List<NpcEconomyDefinition.ItemAmount> parseItems(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<NpcEconomyDefinition.ItemAmount> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String[] parts = token.split(":");
            String itemId = parts.length > 0 ? parts[0].trim() : "";
            if (itemId.isBlank()) {
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                amount = parseIntOrDefault(parts[1], 1);
            }
            out.add(new NpcEconomyDefinition.ItemAmount(itemId, amount));
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static Set<String> parseCsvSet(@Nullable String raw) {
        return Set.copyOf(parseCsv(raw));
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String value = normalize(token);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static int parseIntOrDefault(@Nullable String raw, int fallback) {
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

    @Nonnull
    private static String stripSuffix(@Nonnull String value, @Nonnull String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }
}
