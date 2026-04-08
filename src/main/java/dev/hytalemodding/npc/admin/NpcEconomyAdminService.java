package dev.hytalemodding.npc.admin;

import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class NpcEconomyAdminService {
    private static final NpcEconomyAdminService INSTANCE = new NpcEconomyAdminService();

    private NpcEconomyAdminService() {
    }

    @Nonnull
    public static NpcEconomyAdminService get() {
        return INSTANCE;
    }

    @Nonnull
    public List<NpcEconomyDefinition.OfferDefinition> getTradeOffers(@Nonnull String npcKey) {
        NpcEconomyDefinition definition = NpcEconomyRegistry.get().getNpc(npcKey);
        if (definition == null) {
            return List.of();
        }
        ArrayList<NpcEconomyDefinition.OfferDefinition> offers = new ArrayList<>();
        for (NpcEconomyDefinition.OfferDefinition offer : definition.offers) {
            if (offer.kind == NpcEconomyDefinition.OfferKind.TRADE) {
                offers.add(offer);
            }
        }
        offers.sort(Comparator.comparing(o -> o.offerId));
        return List.copyOf(offers);
    }

    @Nonnull
    public List<String> getKnownItemIds() {
        ArrayList<String> itemIds = new ArrayList<>(NpcEconomyRegistry.get().getAllItemIds());
        if (!itemIds.contains("Ingredient_Life_Essence")) {
            itemIds.add("Ingredient_Life_Essence");
        }
        itemIds.sort(String::compareToIgnoreCase);
        return List.copyOf(itemIds);
    }

    public boolean addTradeOffer(@Nonnull String npcKey) {
        Properties p = loadProperties(npcKey);
        if (p == null) {
            return false;
        }
        LinkedHashSet<String> offers = new LinkedHashSet<>(parseCsv(p.getProperty("offers")));
        String offerId = nextOfferId(offers);
        List<String> knownItems = getKnownItemIds();
        String defaultCost = knownItems.contains("Ingredient_Life_Essence") ? "Ingredient_Life_Essence" : knownItems.get(0);
        String defaultReward = knownItems.isEmpty() ? "Ingredient_Life_Essence" : knownItems.get(0);
        offers.add(offerId);
        p.setProperty("offers", String.join(",", offers));
        p.setProperty("offer." + offerId + ".kind", "trade");
        p.setProperty("offer." + offerId + ".title", "New Trade " + offers.size());
        p.setProperty("offer." + offerId + ".cost", defaultCost + ":1");
        p.setProperty("offer." + offerId + ".reward", defaultReward + ":1");
        p.setProperty("offer." + offerId + ".requireTier", "0");
        return saveProperties(npcKey, p);
    }

    public boolean duplicateOffer(@Nonnull String npcKey, @Nonnull String sourceOfferId) {
        Properties p = loadProperties(npcKey);
        NpcEconomyDefinition.OfferDefinition source = NpcEconomyRegistry.get().getNpc(npcKey) == null
                ? null
                : NpcEconomyRegistry.get().getNpc(npcKey).getOffer(sourceOfferId);
        if (p == null || source == null) {
            return false;
        }
        LinkedHashSet<String> offers = new LinkedHashSet<>(parseCsv(p.getProperty("offers")));
        String duplicateId = nextCopyId(offers, source.offerId);
        offers.add(duplicateId);
        p.setProperty("offers", String.join(",", offers));
        p.setProperty("offer." + duplicateId + ".kind", source.kind == NpcEconomyDefinition.OfferKind.CRAFT ? "craft" : "trade");
        p.setProperty("offer." + duplicateId + ".title", source.title + " Copy");
        p.setProperty("offer." + duplicateId + ".cost", serializeItems(source.cost));
        p.setProperty("offer." + duplicateId + ".reward", serializeItems(source.reward));
        p.setProperty("offer." + duplicateId + ".requireTier", Integer.toString(source.requiredTier));
        p.setProperty("offer." + duplicateId + ".requireFlags", String.join(",", source.requiredFlags));
        return saveProperties(npcKey, p);
    }

    public boolean removeOffer(@Nonnull String npcKey, @Nonnull String offerId) {
        Properties p = loadProperties(npcKey);
        if (p == null) {
            return false;
        }
        LinkedHashSet<String> offers = new LinkedHashSet<>(parseCsv(p.getProperty("offers")));
        if (!offers.remove(NpcEconomyDefinition.normalize(offerId))) {
            return false;
        }
        p.setProperty("offers", String.join(",", offers));
        String prefix = "offer." + NpcEconomyDefinition.normalize(offerId) + ".";
        ArrayList<String> toRemove = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            p.remove(key);
        }
        return saveProperties(npcKey, p);
    }

    public boolean cycleOfferItem(@Nonnull String npcKey, @Nonnull String offerId, boolean reward, int step) {
        Properties p = loadProperties(npcKey);
        NpcEconomyDefinition.OfferDefinition offer = NpcEconomyRegistry.get().getNpc(npcKey) == null
                ? null
                : NpcEconomyRegistry.get().getNpc(npcKey).getOffer(offerId);
        if (p == null || offer == null) {
            return false;
        }
        List<String> catalog = getKnownItemIds();
        if (catalog.isEmpty()) {
            return false;
        }
        List<NpcEconomyDefinition.ItemAmount> items = new ArrayList<>(reward ? offer.reward : offer.cost);
        if (items.isEmpty()) {
            items.add(new NpcEconomyDefinition.ItemAmount(catalog.get(0), 1));
        }
        NpcEconomyDefinition.ItemAmount current = items.get(0);
        int currentIndex = catalog.indexOf(current.itemId);
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        int nextIndex = (currentIndex + step + catalog.size()) % catalog.size();
        items.set(0, new NpcEconomyDefinition.ItemAmount(catalog.get(nextIndex), current.amount));
        p.setProperty("offer." + offer.offerId + "." + (reward ? "reward" : "cost"), serializeItems(items));
        return saveProperties(npcKey, p);
    }

    public boolean adjustOfferAmount(@Nonnull String npcKey, @Nonnull String offerId, boolean reward, int delta) {
        Properties p = loadProperties(npcKey);
        NpcEconomyDefinition.OfferDefinition offer = NpcEconomyRegistry.get().getNpc(npcKey) == null
                ? null
                : NpcEconomyRegistry.get().getNpc(npcKey).getOffer(offerId);
        if (p == null || offer == null) {
            return false;
        }
        List<String> catalog = getKnownItemIds();
        List<NpcEconomyDefinition.ItemAmount> items = new ArrayList<>(reward ? offer.reward : offer.cost);
        if (items.isEmpty()) {
            String defaultItem = catalog.isEmpty() ? "Ingredient_Life_Essence" : catalog.get(0);
            items.add(new NpcEconomyDefinition.ItemAmount(defaultItem, 1));
        }
        NpcEconomyDefinition.ItemAmount current = items.get(0);
        int nextAmount = Math.max(1, current.amount + delta);
        items.set(0, new NpcEconomyDefinition.ItemAmount(current.itemId, nextAmount));
        p.setProperty("offer." + offer.offerId + "." + (reward ? "reward" : "cost"), serializeItems(items));
        return saveProperties(npcKey, p);
    }

    public boolean setOfferTitle(@Nonnull String npcKey, @Nonnull String offerId, @Nullable String title) {
        Properties p = loadProperties(npcKey);
        NpcEconomyDefinition.OfferDefinition offer = NpcEconomyRegistry.get().getNpc(npcKey) == null
                ? null
                : NpcEconomyRegistry.get().getNpc(npcKey).getOffer(offerId);
        if (p == null || offer == null) {
            return false;
        }
        String nextTitle = title == null || title.isBlank() ? offer.offerId : title.trim();
        p.setProperty("offer." + offer.offerId + ".title", nextTitle);
        return saveProperties(npcKey, p);
    }

    public boolean setOfferItemId(@Nonnull String npcKey, @Nonnull String offerId, boolean reward, @Nullable String itemId) {
        Properties p = loadProperties(npcKey);
        NpcEconomyDefinition.OfferDefinition offer = NpcEconomyRegistry.get().getNpc(npcKey) == null
                ? null
                : NpcEconomyRegistry.get().getNpc(npcKey).getOffer(offerId);
        if (p == null || offer == null) {
            return false;
        }
        String nextItemId = itemId == null ? "" : itemId.trim();
        if (nextItemId.isBlank()) {
            return false;
        }
        List<NpcEconomyDefinition.ItemAmount> items = new ArrayList<>(reward ? offer.reward : offer.cost);
        if (items.isEmpty()) {
            items.add(new NpcEconomyDefinition.ItemAmount(nextItemId, 1));
        } else {
            NpcEconomyDefinition.ItemAmount current = items.get(0);
            items.set(0, new NpcEconomyDefinition.ItemAmount(nextItemId, current.amount));
        }
        p.setProperty("offer." + offer.offerId + "." + (reward ? "reward" : "cost"), serializeItems(items));
        return saveProperties(npcKey, p);
    }

    public boolean setOfferAmount(@Nonnull String npcKey, @Nonnull String offerId, boolean reward, int amount) {
        Properties p = loadProperties(npcKey);
        NpcEconomyDefinition.OfferDefinition offer = NpcEconomyRegistry.get().getNpc(npcKey) == null
                ? null
                : NpcEconomyRegistry.get().getNpc(npcKey).getOffer(offerId);
        if (p == null || offer == null) {
            return false;
        }
        int nextAmount = Math.max(1, amount);
        List<NpcEconomyDefinition.ItemAmount> items = new ArrayList<>(reward ? offer.reward : offer.cost);
        if (items.isEmpty()) {
            return false;
        }
        NpcEconomyDefinition.ItemAmount current = items.get(0);
        items.set(0, new NpcEconomyDefinition.ItemAmount(current.itemId, nextAmount));
        p.setProperty("offer." + offer.offerId + "." + (reward ? "reward" : "cost"), serializeItems(items));
        return saveProperties(npcKey, p);
    }

    @Nullable
    private Properties loadProperties(@Nonnull String npcKey) {
        Path path = getEconomyPath(npcKey);
        if (!Files.exists(path)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean saveProperties(@Nonnull String npcKey, @Nonnull Properties p) {
        Path path = getEconomyPath(npcKey);
        try {
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(writer, "NPC economy");
            }
            NpcEconomyRegistry.get().reload();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Nonnull
    private static Path getEconomyPath(@Nonnull String npcKey) {
        return Path.of("src", "main", "resources", "Common", "NpcData", "npcs",
                NpcEconomyDefinition.normalize(npcKey) + ".properties");
    }

    @Nonnull
    private static List<String> parseCsv(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            String value = NpcEconomyDefinition.normalize(token);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    @Nonnull
    private static String nextOfferId(@Nonnull Set<String> existing) {
        int index = 1;
        while (true) {
            String candidate = "new_trade_" + index;
            if (!existing.contains(candidate)) {
                return candidate;
            }
            index++;
        }
    }

    @Nonnull
    private static String nextCopyId(@Nonnull Set<String> existing, @Nonnull String baseOfferId) {
        String normalized = NpcEconomyDefinition.normalize(baseOfferId);
        String candidate = normalized + "_copy";
        int index = 2;
        while (existing.contains(candidate)) {
            candidate = normalized + "_copy" + index;
            index++;
        }
        return candidate;
    }

    @Nonnull
    private static String serializeItems(@Nonnull List<NpcEconomyDefinition.ItemAmount> items) {
        ArrayList<String> out = new ArrayList<>();
        for (NpcEconomyDefinition.ItemAmount item : items) {
            out.add(item.itemId + ":" + item.amount);
        }
        return String.join(",", out);
    }
}
