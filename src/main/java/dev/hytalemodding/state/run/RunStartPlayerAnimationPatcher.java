package dev.hytalemodding.state.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.asset.AssetModule;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RunStartPlayerAnimationPatcher {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PLAYER_MODEL_PATH = "Server/Models/Human/Player.json";

    private RunStartPlayerAnimationPatcher() {
    }

    public static boolean ensureAnimationSetPatched(@Nonnull String animationSetId, @Nonnull String animationPath) {
        try {
            AssetModule module = AssetModule.get();
            if (module == null || module.getBaseAssetPack() == null || module.getBaseAssetPack().getRoot() == null) {
                return false;
            }
            Path playerModelPath = module.getBaseAssetPack().getRoot().resolve(PLAYER_MODEL_PATH);
            if (!Files.exists(playerModelPath)) {
                return false;
            }

            JsonObject playerJson = JsonParser.parseString(Files.readString(playerModelPath)).getAsJsonObject();
            JsonObject animationSets = playerJson.has("AnimationSets") && playerJson.get("AnimationSets").isJsonObject()
                    ? playerJson.getAsJsonObject("AnimationSets")
                    : new JsonObject();

            JsonObject expectedSet = buildAnimationSet(animationPath);
            if (animationSets.has(animationSetId) && expectedSet.equals(animationSets.get(animationSetId))) {
                return true;
            }

            animationSets.add(animationSetId, expectedSet);
            playerJson.add("AnimationSets", animationSets);
            Files.writeString(playerModelPath, GSON.toJson(playerJson));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Nonnull
    private static JsonObject buildAnimationSet(@Nonnull String animationPath) {
        JsonObject root = new JsonObject();
        JsonArray animations = new JsonArray();

        JsonObject animation = new JsonObject();
        animation.addProperty("Animation", animationPath);
        animation.addProperty("Looping", false);
        animation.addProperty("Speed", 1.0);
        animations.add(animation);

        root.add("Animations", animations);
        return root;
    }
}