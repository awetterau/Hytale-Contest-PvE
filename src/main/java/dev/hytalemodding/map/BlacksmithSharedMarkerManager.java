package dev.hytalemodding.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.math.vector.Transform;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

public final class BlacksmithSharedMarkerManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MARKER_ID = "blacksmith_workshop";
    private static final String MARKER_NAME = "Blacksmith";
    private static final String MARKER_ICON = "UserF.png";
    private static final String MARKER_COLOR = "#ff7512";
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SYSTEM_NAME = "System";

    private BlacksmithSharedMarkerManager() {
    }

    public static void sync() {
        Path file = resolveMarkerFile();
        if (file == null) {
            return;
        }
        try {
            JsonObject root = readRoot(file);
            JsonArray markers = getMarkersArray(root);
            removeMarker(markers, MARKER_ID);
            if (BaseHousingManager.get().isBlacksmithWorkshopReady()) {
                markers.add(buildMarker(root));
            }
            writeRoot(file, root);
            writeBackup(file, root);
        } catch (Exception ignored) {
        }
    }

    private static JsonObject buildMarker(@Nonnull JsonObject root) {
        Transform transform = BaseHousingManager.get().getBlacksmithWorkshopMarkerTransform();
        JsonObject marker = new JsonObject();
        marker.addProperty("Id", MARKER_ID);
        marker.addProperty("X", transform.getPosition().getX());
        marker.addProperty("Z", transform.getPosition().getZ());
        marker.addProperty("Name", MARKER_NAME);
        marker.addProperty("Icon", MARKER_ICON);
        marker.addProperty("ColorTint", MARKER_COLOR);

        JsonObject creator = findExistingCreator(root);
        marker.add("CreatedByUuid", creator);
        marker.addProperty("CreatedByName", findExistingCreatorName(root));
        return marker;
    }

    @Nonnull
    private static JsonObject findExistingCreator(@Nonnull JsonObject root) {
        JsonArray markers = getMarkersArray(root);
        for (JsonElement element : markers) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject marker = element.getAsJsonObject();
            JsonElement creator = marker.get("CreatedByUuid");
            if (creator != null && creator.isJsonObject()) {
                return creator.getAsJsonObject().deepCopy();
            }
        }
        return createUuidJson(SYSTEM_UUID);
    }

    @Nonnull
    private static String findExistingCreatorName(@Nonnull JsonObject root) {
        JsonArray markers = getMarkersArray(root);
        for (JsonElement element : markers) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject marker = element.getAsJsonObject();
            JsonElement creator = marker.get("CreatedByName");
            if (creator != null && creator.isJsonPrimitive()) {
                String value = creator.getAsString();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return SYSTEM_NAME;
    }

    @Nonnull
    private static JsonObject createUuidJson(@Nonnull UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - (i * 8)));
            bytes[8 + i] = (byte) (lsb >>> (56 - (i * 8)));
        }
        JsonObject binary = new JsonObject();
        binary.addProperty("$binary", Base64.getEncoder().encodeToString(bytes));
        binary.addProperty("$type", "04");
        return binary;
    }

    private static void removeMarker(@Nonnull JsonArray markers, @Nonnull String id) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            JsonElement element = markers.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject marker = element.getAsJsonObject();
            if (id.equalsIgnoreCase(marker.has("Id") ? marker.get("Id").getAsString() : "")) {
                markers.remove(i);
            }
        }
    }

    @Nonnull
    private static JsonObject readRoot(@Nonnull Path file) throws IOException {
        if (!Files.exists(file)) {
            JsonObject root = new JsonObject();
            root.add("UserMarkers", new JsonArray());
            return root;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            JsonObject root = new JsonObject();
            root.add("UserMarkers", new JsonArray());
            return root;
        }
        JsonElement parsed = JsonParser.parseString(raw);
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    @Nonnull
    private static JsonArray getMarkersArray(@Nonnull JsonObject root) {
        JsonElement existing = root.get("UserMarkers");
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        JsonArray array = new JsonArray();
        root.add("UserMarkers", array);
        return array;
    }

    private static void writeRoot(@Nonnull Path file, @Nonnull JsonObject root) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void writeBackup(@Nonnull Path file, @Nonnull JsonObject root) throws IOException {
        Path backup = file.resolveSibling(file.getFileName().toString() + ".bak");
        Files.writeString(backup, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Path resolveMarkerFile() {
        String hubWorldName = GameFlowConfigManager.get().getHubWorldName();
        if (hubWorldName == null || hubWorldName.isBlank()) {
            return null;
        }
        return Path.of("devserver", "universe", "worlds", hubWorldName, "resources", "SharedUserMapMarkers.json");
    }
}
