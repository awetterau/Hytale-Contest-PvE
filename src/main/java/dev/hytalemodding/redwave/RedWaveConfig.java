package dev.hytalemodding.redwave;

import java.util.List;

public final class RedWaveConfig {
    public static final String CRIMSON_LAYER_BLOCK_ID = "Crimson_Layer";
    public static final String CRIMSON_PLATE_BLOCK_ID = "Crimson_Plate";
    public static final String CRIMSON_BLOCK_ID = CRIMSON_LAYER_BLOCK_ID;
    public static final String CORE_BLOCK_ID = "Crimson_Core";
    public static final String OPTIONAL_CRIMSON_VOID_DAMAGE_BLOCK_ID = "Crimson_Void_Damage";

    public static final int DEFAULT_RADIUS_BLOCKS = 24;
    public static final int DEFAULT_SPREAD_SPEED_BLOCKS_PER_TICK = 96;
    public static final int DEFAULT_FRONTIER_LIMIT = 200000;
    public static final int DEFAULT_UI_RADIUS_BLOCKS = 6;
    public static final float DEFAULT_UI_START_SECONDS = 9.0f;
    public static final int MIN_RADIUS_BLOCKS = 1;
    public static final int MAX_RADIUS_BLOCKS = 256;
    public static final int FINAL_SHAPE_SIDES = 24;
    public static final float FINAL_SHAPE_TIP_SCALE = 0.16f;
    public static final float FINAL_SHAPE_WORK_SCALE = 1.10f;
    public static final float FINAL_SHAPE_LOBE_LENGTH_MIN = 1.00f;
    public static final float FINAL_SHAPE_LOBE_LENGTH_MAX = 1.45f;
    public static final float FINAL_SHAPE_LOBE_LENGTH_JITTER = 0.32f;
    public static final float FINAL_SHAPE_LOBE_SHARPNESS_JITTER = 0.45f;
    public static final float FINAL_SHAPE_NOISE_SOLID_RADIUS = 0.40f;
    public static final float FINAL_SHAPE_NOISE_GRADIENT_END = 0.86f;
    public static final float FINAL_SHAPE_NOISE_GRADIENT_CHARGE = 1.25f;
    public static final float FINAL_SHAPE_NOISE_GRADIENT_STRONG_PORTION = 0.50f;
    public static final float FINAL_SHAPE_NOISE_GRADIENT_STRONG_DROP = 0.36f;
    public static final float FINAL_SHAPE_NOISE_GRADIENT_BASE_DROP = 0.12f;
    public static final float FINAL_SHAPE_NOISE_EDGE_HARDEN_START = 1.10f;
    public static final boolean FINAL_SHAPE_INNER_STAR_ENABLED = true;
    public static final float FINAL_SHAPE_INNER_STAR_RADIUS = 0.80f;
    public static final float FINAL_SHAPE_INNER_STAR_LINE_WIDTH = 0.06f;
    public static final int FINAL_SHAPE_BOOTSTRAP_MIN_NEIGHBORS = 2;
    public static final boolean FINAL_SHAPE_RANDOM_TIPS_ENABLED = true;
    public static final boolean ENABLE_UNDO_RECORDING = false;

    public static final List<String> NON_CONVERTIBLE_ID_KEYWORDS = List.of(
            "leaf",
            "leaves",
            "foliage",
            "bush",
            "plant",
            "flower",
            "crop",
            "vine",
            "mushroom",
            "seaweed",
            "sapling",
            "fern",
            "reed"
    );

    public static boolean isCoreBlockId(String blockId) {
        return CORE_BLOCK_ID.equals(blockId);
    }

    private RedWaveConfig() {
    }
}