package dev.hytalemodding.stash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class LocalStashStore {
    static final int STASH_CAPACITY = 80;
    private static final String PLUGIN_DIR = "ExamplePlugin";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> NON_PERSISTENT_ITEM_IDS = Set.of(
            "Restricted_Slot",
            "Restricted_Safe_Slot",
            "Safe_Slot_Separator"
    );

    private LocalStashStore() {
    }

    @Nonnull
    static ItemContainer load(@Nonnull UUID uuid) {
        SimpleItemContainer container = new SimpleItemContainer((short) STASH_CAPACITY);
        Path file = dataFile(uuid);
        if (file == null || !Files.exists(file)) {
            return container;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StashFile data = GSON.fromJson(reader, StashFile.class);
            if (data == null || data.slots == null) {
                return container;
            }
            for (SlotData slot : data.slots) {
                if (slot == null || slot.itemId == null || slot.itemId.isBlank()) {
                    continue;
                }
                if (NON_PERSISTENT_ITEM_IDS.contains(slot.itemId) || slot.quantity <= 0) {
                    continue;
                }
                if (slot.slot < 0 || slot.slot >= STASH_CAPACITY) {
                    continue;
                }
                BsonDocument metadata = parseMetadata(slot.metadata);
                ItemStack stack = slot.maxDurability <= 0.0 && slot.durability <= 0.0 && metadata == null
                        ? new ItemStack(slot.itemId, slot.quantity)
                        : new ItemStack(slot.itemId, slot.quantity, slot.durability, slot.maxDurability, metadata);
                container.setItemStackForSlot((short) slot.slot, stack);
            }
        } catch (Exception e) {
            System.out.println("[LocalStash] Failed to load stash for " + uuid + ": " + e.getMessage());
        }
        return container;
    }

    static void save(@Nonnull UUID uuid, @Nonnull ItemContainer container) {
        Path file = dataFile(uuid);
        if (file == null) {
            return;
        }

        StashFile data = new StashFile();
        data.version = 1;
        data.slots = new ArrayList<>();
        int capacity = Math.min(container.getCapacity(), STASH_CAPACITY);
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId == null || itemId.isBlank() || NON_PERSISTENT_ITEM_IDS.contains(itemId)) {
                continue;
            }

            SlotData slot = new SlotData();
            slot.slot = i;
            slot.itemId = itemId;
            slot.quantity = stack.getQuantity();
            slot.durability = stack.getDurability();
            slot.maxDurability = stack.getMaxDurability();
            BsonDocument metadata = stack.getMetadata();
            slot.metadata = metadata == null ? null : metadata.toJson();
            data.slots.add(slot);
        }

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            System.out.println("[LocalStash] Failed to save stash for " + uuid + ": " + e.getMessage());
        }
    }

    @Nullable
    private static BsonDocument parseMetadata(@Nullable String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            return BsonDocument.parse(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Path dataFile(@Nonnull UUID uuid) {
        try {
            Path universePath = Universe.get().getPath();
            if (universePath == null) {
                return null;
            }
            return universePath
                    .resolve("plugins")
                    .resolve(PLUGIN_DIR)
                    .resolve("data")
                    .resolve(uuid + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    private static final class StashFile {
        int version = 1;
        List<SlotData> slots = new ArrayList<>();
    }

    private static final class SlotData {
        int slot;
        String itemId;
        int quantity;
        double durability;
        double maxDurability;
        String metadata;
    }
}
