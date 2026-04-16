package dev.hytalemodding.map;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.worldmap.MapChunk;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import dev.hytalemodding.state.run.RunChunkSelectionManager;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class RunChunkSelectionMapController {
    private static final int PREWARM_BORDER_COLOR = packMapColor(0xFF, 0x00, 0x55, 0xFF);
    private static final double PREWARM_BORDER_BLEND = 0.55D;
    private static final int PINNED_BORDER_COLOR = packMapColor(0xA0, 0x00, 0x35, 0xFF);
    private static final double PINNED_BORDER_BLEND = 0.75D;

    @Nonnull
    private final JavaPlugin plugin;
    private PacketFilter outboundFilter;

    public RunChunkSelectionMapController(@Nonnull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        this.outboundFilter = PacketAdapters.registerOutbound((PlayerPacketFilter) this::handleOutbound);
    }

    public void unregister() {
        if (this.outboundFilter != null) {
            try {
                PacketAdapters.deregisterOutbound(this.outboundFilter);
            } catch (IllegalArgumentException ignored) {
            }
            this.outboundFilter = null;
        }
    }

    private boolean handleOutbound(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (!(packet instanceof UpdateWorldMap updateWorldMap) || updateWorldMap.chunks == null || updateWorldMap.chunks.length == 0) {
            return false;
        }

        RunChunkSelectionManager manager = RunChunkSelectionManager.get();
        if (!manager.isEnabled(playerRef)) {
            return false;
        }

        String worldName = RunChunkSelectionManager.getPlayerWorldName(playerRef);
        if (worldName == null) {
            return false;
        }
        Set<RunChunkSelectionManager.ChunkPosKey> selected = manager.getSelectedChunks(worldName);
        if (selected.isEmpty()) {
            return false;
        }

        for (MapChunk chunk : updateWorldMap.chunks) {
            if (chunk == null || chunk.image == null) {
                continue;
            }
            if (!selected.contains(new RunChunkSelectionManager.ChunkPosKey(chunk.chunkX, chunk.chunkZ))) {
                continue;
            }
            boolean pinned = manager.isPinned(worldName, chunk.chunkX, chunk.chunkZ);
            tintChunk(chunk, pinned);
        }

        return false;
    }

    private static void tintChunk(@Nonnull MapChunk chunk, boolean pinned) {
        MapImage image = chunk.image;
        int pixelCount = image.width * image.height;
        if (image.width <= 0 || image.height <= 0 || pixelCount <= 0) {
            return;
        }
        int borderColor = pinned ? PINNED_BORDER_COLOR : PREWARM_BORDER_COLOR;
        double borderBlend = pinned ? PINNED_BORDER_BLEND : PREWARM_BORDER_BLEND;
        int[] pixels = unpackPixels(image, pixelCount);
        int borderThickness = Math.max(1, Math.min(image.width, image.height) / 10);
        int maxX = image.width - 1;
        int maxY = image.height - 1;
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                boolean isBorder = x < borderThickness
                        || y < borderThickness
                        || x > maxX - borderThickness
                        || y > maxY - borderThickness;
                if (!isBorder) {
                    continue;
                }
                int index = (y * image.width) + x;
                pixels[index] = blendMapColor(pixels[index], borderColor, borderBlend);
            }
        }
        chunk.image = packPixels(image.width, image.height, pixels);
    }

    private static int packMapColor(int r, int g, int b, int a) {
        return ((r & 0xFF) << 24) | ((g & 0xFF) << 16) | ((b & 0xFF) << 8) | (a & 0xFF);
    }

    private static int blendMapColor(int baseColor, int overlayColor, double overlayWeight) {
        double weight = Math.max(0.0D, Math.min(1.0D, overlayWeight));
        int br = (baseColor >>> 24) & 0xFF;
        int bg = (baseColor >>> 16) & 0xFF;
        int bb = (baseColor >>> 8) & 0xFF;
        int ba = baseColor & 0xFF;

        int or = (overlayColor >>> 24) & 0xFF;
        int og = (overlayColor >>> 16) & 0xFF;
        int ob = (overlayColor >>> 8) & 0xFF;
        int oa = overlayColor & 0xFF;

        int r = (int) Math.round((br * (1.0D - weight)) + (or * weight));
        int g = (int) Math.round((bg * (1.0D - weight)) + (og * weight));
        int b = (int) Math.round((bb * (1.0D - weight)) + (ob * weight));
        int a = (int) Math.round((ba * (1.0D - weight)) + (oa * weight));
        return packMapColor(r, g, b, a);
    }

    @Nonnull
    private static int[] unpackPixels(@Nonnull MapImage image, int pixelCount) {
        int[] pixels = new int[pixelCount];
        if (image.palette == null || image.palette.length == 0 || image.packedIndices == null || image.bitsPerIndex <= 0) {
            return pixels;
        }

        BitFieldArr indices = new BitFieldArr(Byte.toUnsignedInt(image.bitsPerIndex), pixelCount);
        indices.set(image.packedIndices);
        for (int i = 0; i < pixelCount; i++) {
            int paletteIndex = indices.get(i);
            if (paletteIndex >= 0 && paletteIndex < image.palette.length) {
                pixels[i] = image.palette[paletteIndex];
            }
        }
        return pixels;
    }

    @Nonnull
    private static MapImage packPixels(int width, int height, @Nonnull int[] pixels) {
        if (pixels.length == 0) {
            return new MapImage(width, height, new int[]{0}, (byte) 1, new byte[1]);
        }

        Map<Integer, Integer> paletteLookup = new LinkedHashMap<>();
        int[] pixelIndices = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            Integer paletteIndex = paletteLookup.get(pixels[i]);
            if (paletteIndex == null) {
                paletteIndex = paletteLookup.size();
                paletteLookup.put(pixels[i], paletteIndex);
            }
            pixelIndices[i] = paletteIndex;
        }

        int[] palette = new int[paletteLookup.size()];
        for (Map.Entry<Integer, Integer> entry : paletteLookup.entrySet()) {
            palette[entry.getValue()] = entry.getKey();
        }

        int bitsPerIndex = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
        BitFieldArr packed = new BitFieldArr(bitsPerIndex, pixels.length);
        for (int i = 0; i < pixelIndices.length; i++) {
            packed.set(i, pixelIndices[i]);
        }
        return new MapImage(width, height, palette, (byte) bitsPerIndex, Arrays.copyOf(packed.get(), packed.get().length));
    }
}