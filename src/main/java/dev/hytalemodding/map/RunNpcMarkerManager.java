package dev.hytalemodding.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

public final class RunNpcMarkerManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final UUID SYSTEM_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SYSTEM_NAME = "System";

    private RunNpcMarkerManager() {
    }

    public static void addMarkerForNpc(@Nonnull World world, @Nonnull String npcKey, @Nonnull Vector3d position) {
        try {
            UUID worldId = world.getWorldConfig().getUuid();
            if (worldId == null) {
                return;
            }
            // For run worlds, the path is typically "devserver/universe/worlds/<worldname>/resources/SharedUserMapMarkers.json"
            // We'll try to construct it from the world UUID
            Path file = resolveMarkerFile(worldId);
            if (file == null || !Files.exists(file.getParent())) {
                return;
            }

            JsonObject root = readRoot(file);
            JsonArray markers = getMarkersArray(root);

            // Create a unique marker ID based on NPC key
            String markerId = "npc_" + npcKey;
            removeMarker(markers, markerId);

            JsonObject marker = buildNpcMarker(npcKey, position);
            markers.add(marker);

            writeRoot(file, root);
        } catch (Exception ignored) {
        }
    }

    @Nonnull
    private static JsonObject buildNpcMarker(@Nonnull String npcKey, @Nonnull Vector3d position) {
        String displayName;
        String icon;
        String color;

        if ("blacksmith".equals(npcKey)) {
            displayName = "Blacksmith";
            icon = "UserD.png";
            color = "#ff7512";
        } else if ("farmer".equals(npcKey)) {
            displayName = "Farmer";
            icon = "UserE.png";
            color = "#90EE90";
        } else {
            displayName = npcKey;
            icon = "UserF.png";
            color = "#FFFFFF";
        }

        JsonObject marker = new JsonObject();
        marker.addProperty("Id", "npc_" + npcKey);
        marker.addProperty("X", position.getX());
        marker.addProperty("Z", position.getZ());
        marker.addProperty("Name", displayName);
        marker.addProperty("Icon", icon);
        marker.addProperty("ColorTint", color);
        marker.add("CreatedByUuid", createUuidJson(SYSTEM_UUID));
        marker.addProperty("CreatedByName", SYSTEM_NAME);
        return marker;
    }

    @Nonnull
    private static JsonArray getMarkersArray(@Nonnull JsonObject root) {
        JsonElement element = root.get("Markers");
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        JsonArray array = new JsonArray();
        root.add("Markers", array);
        return array;
    }

    private static void removeMarker(@Nonnull JsonArray markers, @Nonnull String markerId) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            JsonElement element = markers.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject marker = element.getAsJsonObject();
            JsonElement id = marker.get("Id");
            if (id != null && id.isJsonPrimitive() && markerId.equals(id.getAsString())) {
                markers.remove(i);
            }
        }
    }

    @Nonnull
    private static JsonObject readRoot(@Nonnull Path file) throws Exception {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement element = JsonParser.parseString(content);
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return new JsonObject();
    }

    private static void writeRoot(@Nonnull Path file, @Nonnull JsonObject root) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Nonnull
    private static JsonObject createUuidJson(@Nonnull UUID uuid) {
        JsonObject obj = new JsonObject();
        String encoded = Base64.getEncoder().encodeToString(uuidToBytes(uuid));
        obj.addProperty("$type", "UUID");
        obj.addProperty("$value", encoded);
        return obj;
    }

    @Nonnull
    private static byte[] uuidToBytes(@Nonnull UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - 8 * i));
            bytes[i + 8] = (byte) (lsb >>> (56 - 8 * i));
        }
        return bytes;
    }

    private static Path resolveMarkerFile(@Nonnull UUID worldId) {
        try {
            // Try common world names for run worlds
            for (String attempt : new String[]{"game", "run", worldId.toString()}) {
                Path file = Path.of("devserver", "universe", "worlds", attempt, "resources", "SharedUserMapMarkers.json");
                if (Files.exists(file)) {
                    return file;
                }
            }
            // Default to UUID as world name
            return Path.of("devserver", "universe", "worlds", worldId.toString(), "resources", "SharedUserMapMarkers.json");
        } catch (Exception ignored) {
            return null;
        }
    }
}
