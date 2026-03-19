package dev.hytalemodding.rooter;

import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class RooterConfig {
    private static final String RESOURCE_PATH = "Common/NpcData/rooter-man.properties";
    private static final RooterConfig INSTANCE = new RooterConfig();

    private volatile boolean loaded;
    private volatile boolean enabled = true;
    @Nonnull
    private volatile String templateWorld = "game";
    @Nonnull
    private volatile String bossRole = "Rooter_Man_Boss_P1";
    @Nonnull
    private volatile String vulnerableBossRole = "Rooter_Man_Boss_P1";
    @Nonnull
    private final Vector3d spawnOffset = new Vector3d(4.0, 0.0, 4.0);

    private RooterConfig() {
    }

    @Nonnull
    public static RooterConfig get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        loadResource();
    }

    public boolean isEnabled() {
        initialize();
        return this.enabled;
    }

    @Nonnull
    public String getTemplateWorld() {
        initialize();
        return this.templateWorld;
    }

    @Nonnull
    public String getBossRole() {
        initialize();
        return this.bossRole;
    }

    @Nonnull
    public String getVulnerableBossRole() {
        initialize();
        return this.vulnerableBossRole;
    }

    @Nonnull
    public Vector3d getSpawnOffset() {
        initialize();
        return new Vector3d(this.spawnOffset);
    }

    private void loadResource() {
        Properties properties = new Properties();
        try (InputStream in = RooterConfig.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                System.out.println("[RooterConfig] Resource not found: " + RESOURCE_PATH);
                return;
            }
            properties.load(in);
        } catch (IOException e) {
            System.out.println("[RooterConfig] Failed to load: " + e.getMessage());
            return;
        }

        this.enabled = Boolean.parseBoolean(properties.getProperty("rooter.enabled", "true"));
        this.templateWorld = stringOrDefault(properties.getProperty("rooter.spawn.templateWorld"), this.templateWorld);
        this.bossRole = stringOrDefault(properties.getProperty("rooter.spawn.role"), this.bossRole);
        this.vulnerableBossRole = stringOrDefault(
                properties.getProperty("rooter.spawn.vulnerableRole"),
                this.vulnerableBossRole
        );
        this.spawnOffset.setX(readDouble(properties.getProperty("rooter.spawn.offsetX"), this.spawnOffset.getX()));
        this.spawnOffset.setY(readDouble(properties.getProperty("rooter.spawn.offsetY"), this.spawnOffset.getY()));
        this.spawnOffset.setZ(readDouble(properties.getProperty("rooter.spawn.offsetZ"), this.spawnOffset.getZ()));

        System.out.println(
                "[RooterConfig] Loaded config for role=" + this.bossRole
                        + ", vulnerableRole=" + this.vulnerableBossRole
        );
    }

    @Nonnull
    private static String stringOrDefault(@Nullable String value, @Nonnull String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static double readDouble(@Nullable String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
