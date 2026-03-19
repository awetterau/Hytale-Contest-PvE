package dev.hytalemodding.loot;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import java.util.UUID;

public record LifeEssenceContainerKey(@Nonnull UUID worldUuid, int x, int y, int z) {
    public static LifeEssenceContainerKey of(@Nonnull UUID worldUuid, @Nonnull Vector3i pos) {
        return new LifeEssenceContainerKey(worldUuid, pos.getX(), pos.getY(), pos.getZ());
    }
}
