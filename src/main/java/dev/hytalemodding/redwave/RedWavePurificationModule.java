package dev.hytalemodding.redwave;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;

/**
 * Placeholder module for future purification/protection mechanics.
 * For now it only exposes a centralized hook used by infection systems.
 */
public final class RedWavePurificationModule {
    private static final RedWavePurificationModule INSTANCE = new RedWavePurificationModule();

    private RedWavePurificationModule() {
    }

    @Nonnull
    public static RedWavePurificationModule get() {
        return INSTANCE;
    }

    /**
     * Future extension point: block infection inside purified/protected areas.
     */
    public boolean canInfect(@Nonnull World world, @Nonnull Vector3i position) {
        return true;
    }
}