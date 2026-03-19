package dev.hytalemodding.redwave;

import java.util.List;

public final class RedWaveConfig {
    public static final String CRIMSON_BLOCK_ID = "Cloth_Block_Wool_Red";
    public static final String CORE_BLOCK_ID = "Crimson_Core";
    public static final String OPTIONAL_CRIMSON_VOID_DAMAGE_BLOCK_ID = "Crimson_Void_Damage";

    public static final int DEFAULT_RADIUS_BLOCKS = 24;
    public static final int DEFAULT_UI_RADIUS_BLOCKS = 6;
    public static final float DEFAULT_UI_START_SECONDS = 9.0f;
    public static final int MIN_RADIUS_BLOCKS = 1;
    public static final int MAX_RADIUS_BLOCKS = 256;

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