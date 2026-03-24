package dev.hytalemodding.map;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.player.JoinWorld;
import com.hypixel.hytale.protocol.packets.worldmap.MapChunk;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMapSettings;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hytalemodding.debug.CrashTrace;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.logging.Level;

public final class MapReplacementPacketController {
    private static final boolean ENABLED = false;
    // Toggle this off to remove map interaction lock behavior.
    private static final boolean LOCK_WORLD_MAP_INTERACTION = true;
    private static final float LOCKED_MAP_SCALE = 32.0F;
    private static final boolean DISABLE_MAP_TELEPORTS = true;
    private static final boolean LOCK_WORLD_MAP_CENTER_WORKAROUND = false;
    private static final int LOCKED_CENTER_WORLD_X = 500;
    private static final int LOCKED_CENTER_WORLD_Z = 500;

    private static final int MAP_CHUNK_BLOCK_SIZE = 32;
    private static final int OVERLAY_MIN_X = 0;
    private static final int OVERLAY_MIN_Z = 0;
    private static final int OVERLAY_MAX_X = 1000;
    private static final int OVERLAY_MAX_Z = 1000;
    private static final int OPAQUE_ALPHA = 255;
    private static final String CUSTOM_MAP_RESOURCE_PATH = "Common/UI/Custom/Textures/custom_map.png";

    @Nonnull
    private final JavaPlugin plugin;
    private PacketFilter outboundFilter;
    private volatile BufferedImage cachedOverlayImage;

    public MapReplacementPacketController(@Nonnull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (!ENABLED) {
            return;
        }
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

    public void validateCustomMapAssetRegistration() {
        if (!ENABLED) {
            return;
        }
        // **MOVE `custom_map.png` TO: `Assets/Common/UI/Custom/Textures/custom_map.png` (source path: `src/main/resources/Common/UI/Custom/Textures/custom_map.png`).**
        try (InputStream in = this.plugin.getClassLoader().getResourceAsStream(CUSTOM_MAP_RESOURCE_PATH)) {
            if (in == null) {
                this.plugin.getLogger().at(Level.WARNING).log(
                        "Custom map texture missing. Expected asset path: " + CUSTOM_MAP_RESOURCE_PATH
                );
            }
        } catch (Exception e) {
            this.plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to validate custom map texture registration");
        }
    }

    private boolean handleOutbound(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (packet instanceof JoinWorld) {
            CrashTrace.beginJoinTrace(playerRef, playerRef.getWorldUuid() == null ? null : playerRef.getTransform() == null ? "<unknown>" : null);
            CrashTrace.log(playerRef, "packet", "sent JoinWorld");
            return false;
        }
        if (LOCK_WORLD_MAP_INTERACTION && packet instanceof UpdateWorldMapSettings settings) {
            CrashTrace.logLimited(
                    playerRef,
                    "world-map-settings",
                    4,
                    "packet",
                    "UpdateWorldMapSettings before lock default=" + settings.defaultScale
                            + " min=" + settings.minScale
                            + " max=" + settings.maxScale
            );
            applyMapLockSettings(settings);
            CrashTrace.logLimited(
                    playerRef,
                    "world-map-settings-locked",
                    4,
                    "packet",
                    "UpdateWorldMapSettings after lock default=" + settings.defaultScale
                            + " min=" + settings.minScale
                            + " max=" + settings.maxScale
            );
            return false;
        }
        if (packet instanceof UpdateWorldMap updateWorldMap && updateWorldMap.chunks != null) {
            CrashTrace.logLimited(
                    playerRef,
                    "world-map",
                    8,
                    "packet",
                    "UpdateWorldMap chunks=" + updateWorldMap.chunks.length
                            + " markersAdded=" + (updateWorldMap.addedMarkers == null ? 0 : updateWorldMap.addedMarkers.length)
                            + " markersRemoved=" + (updateWorldMap.removedMarkers == null ? 0 : updateWorldMap.removedMarkers.length)
            );
            BufferedImage overlayImage = loadOverlayImage();
            if (overlayImage == null) {
                return false;
            }

            int streamCenterChunkX;
            int streamCenterChunkZ;
            if (LOCK_WORLD_MAP_CENTER_WORKAROUND) {
                int[] packetCenter = estimatePacketCenter(updateWorldMap.chunks);
                streamCenterChunkX = packetCenter[0];
                streamCenterChunkZ = packetCenter[1];
                updateWorldMap.addedMarkers = (MapMarker[]) null;
                updateWorldMap.removedMarkers = (String[]) null;
            } else {
                streamCenterChunkX = 0;
                streamCenterChunkZ = 0;
            }

            int lockedCenterChunkX = Math.floorDiv(LOCKED_CENTER_WORLD_X, MAP_CHUNK_BLOCK_SIZE);
            int lockedCenterChunkZ = Math.floorDiv(LOCKED_CENTER_WORLD_Z, MAP_CHUNK_BLOCK_SIZE);

            for (MapChunk chunk : updateWorldMap.chunks) {
                if (chunk == null || chunk.image == null || chunk.image.data == null) {
                    continue;
                }
                CrashTrace.logLimited(
                        playerRef,
                        "world-map-chunk",
                        8,
                        "packet",
                        "chunk=(" + chunk.chunkX + "," + chunk.chunkZ + ") image="
                                + chunk.image.width + "x" + chunk.image.height
                                + " data=" + chunk.image.data.length
                );
                int sampleChunkX = chunk.chunkX;
                int sampleChunkZ = chunk.chunkZ;
                if (LOCK_WORLD_MAP_CENTER_WORKAROUND) {
                    sampleChunkX = lockedCenterChunkX + (chunk.chunkX - streamCenterChunkX);
                    sampleChunkZ = lockedCenterChunkZ + (chunk.chunkZ - streamCenterChunkZ);
                }
                applyOverlayToChunk(chunk, overlayImage, sampleChunkX, sampleChunkZ);
            }
        }
        return false;
    }

    private void applyMapLockSettings(@Nonnull UpdateWorldMapSettings settings) {
        settings.defaultScale = LOCKED_MAP_SCALE;
        settings.minScale = LOCKED_MAP_SCALE;
        settings.maxScale = LOCKED_MAP_SCALE;
        if (DISABLE_MAP_TELEPORTS) {
            settings.allowTeleportToCoordinates = false;
            settings.allowTeleportToMarkers = false;
        }
    }

    private void applyOverlayToChunk(@Nonnull MapChunk chunk, @Nonnull BufferedImage overlayImage, int sampleChunkX, int sampleChunkZ) {
        MapImage image = chunk.image;
        if (image.width <= 0 || image.height <= 0 || image.data.length < image.width * image.height) {
            return;
        }

        int chunkMinX = sampleChunkX * MAP_CHUNK_BLOCK_SIZE;
        int chunkMinZ = sampleChunkZ * MAP_CHUNK_BLOCK_SIZE;
        int chunkMaxX = chunkMinX + MAP_CHUNK_BLOCK_SIZE;
        int chunkMaxZ = chunkMinZ + MAP_CHUNK_BLOCK_SIZE;

        if (chunkMaxX <= OVERLAY_MIN_X || chunkMinX >= OVERLAY_MAX_X || chunkMaxZ <= OVERLAY_MIN_Z || chunkMinZ >= OVERLAY_MAX_Z) {
            return;
        }

        double overlayWidth = OVERLAY_MAX_X - OVERLAY_MIN_X;
        double overlayHeight = OVERLAY_MAX_Z - OVERLAY_MIN_Z;
        if (overlayWidth <= 0 || overlayHeight <= 0) {
            return;
        }

        for (int py = 0; py < image.height; py++) {
            double worldZ = chunkMinZ + ((py + 0.5D) * MAP_CHUNK_BLOCK_SIZE / image.height);
            if (worldZ < OVERLAY_MIN_Z || worldZ >= OVERLAY_MAX_Z) {
                continue;
            }
            double v = (worldZ - OVERLAY_MIN_Z) / overlayHeight;
            int srcY = clamp((int) Math.floor(v * overlayImage.getHeight()), 0, overlayImage.getHeight() - 1);

            for (int px = 0; px < image.width; px++) {
                double worldX = chunkMinX + ((px + 0.5D) * MAP_CHUNK_BLOCK_SIZE / image.width);
                if (worldX < OVERLAY_MIN_X || worldX >= OVERLAY_MAX_X) {
                    continue;
                }
                double u = (worldX - OVERLAY_MIN_X) / overlayWidth;
                int srcX = clamp((int) Math.floor(u * overlayImage.getWidth()), 0, overlayImage.getWidth() - 1);
                int src = overlayImage.getRGB(srcX, srcY);
                int r = (src >>> 16) & 0xFF;
                int g = (src >>> 8) & 0xFF;
                int b = src & 0xFF;
                image.data[(py * image.width) + px] = packMapColor(r, g, b, OPAQUE_ALPHA);
            }
        }
    }

    private BufferedImage loadOverlayImage() {
        BufferedImage cached = this.cachedOverlayImage;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.cachedOverlayImage != null) {
                return this.cachedOverlayImage;
            }
            try (InputStream in = this.plugin.getClassLoader().getResourceAsStream(CUSTOM_MAP_RESOURCE_PATH)) {
                if (in == null) {
                    this.plugin.getLogger().at(Level.WARNING).log(
                            "Cannot load custom map overlay image from " + CUSTOM_MAP_RESOURCE_PATH
                    );
                    return null;
                }
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    this.plugin.getLogger().at(Level.WARNING).log(
                            "ImageIO failed to decode custom map overlay image at " + CUSTOM_MAP_RESOURCE_PATH
                    );
                    return null;
                }
                this.cachedOverlayImage = image;
                return image;
            } catch (Exception e) {
                this.plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed loading custom map overlay image");
                return null;
            }
        }
    }

    private static int packMapColor(int r, int g, int b, int a) {
        return ((r & 0xFF) << 24) | ((g & 0xFF) << 16) | ((b & 0xFF) << 8) | (a & 0xFF);
    }

    private static int[] estimatePacketCenter(@Nonnull MapChunk[] chunks) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;

        for (MapChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            found = true;
            minX = Math.min(minX, chunk.chunkX);
            maxX = Math.max(maxX, chunk.chunkX);
            minZ = Math.min(minZ, chunk.chunkZ);
            maxZ = Math.max(maxZ, chunk.chunkZ);
        }

        if (!found) {
            return new int[]{0, 0};
        }
        return new int[]{(minX + maxX) / 2, (minZ + maxZ) / 2};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
