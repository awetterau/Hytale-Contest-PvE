package dev.hytalemodding.state.run;

import javax.annotation.Nullable;
import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DoorRunZoneSelectionManager {
    /**
     * For now the selection is stored per-player. This is intentionally isolated so it can be
     * replaced by a party/session-scoped selection later without touching the door flow itself.
     */
    private static final ConcurrentHashMap<UUID, Integer> SELECTED_ZONE_BY_PLAYER = new ConcurrentHashMap<>();

    private DoorRunZoneSelectionManager() {
    }

    public static void setSelectedZone(@Nonnull UUID playerId, int zoneIndex) {
        SELECTED_ZONE_BY_PLAYER.put(playerId, Math.max(0, zoneIndex));
    }

    public static Integer getSelectedZone(@Nonnull UUID playerId) {
        return SELECTED_ZONE_BY_PLAYER.get(playerId);
    }

    public static void clearInvalidSelections(int zoneCount) {
        int safeZoneCount = Math.max(1, zoneCount);
        SELECTED_ZONE_BY_PLAYER.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < 0 || entry.getValue() >= safeZoneCount);
    }

    @Nullable
    public static Integer ensureSelectedZoneOrDefault(@Nonnull UUID playerId) {
        Integer selectedZone = getSelectedZone(playerId);
        if (selectedZone != null && SpawnPointZoneManager.hasRegisteredSpawnInZone(selectedZone.intValue())) {
            return selectedZone;
        }

        Integer fallbackZone = SpawnPointZoneManager.getFirstZoneWithRegisteredSpawns();
        if (fallbackZone == null) {
            SELECTED_ZONE_BY_PLAYER.remove(playerId);
            return null;
        }

        setSelectedZone(playerId, fallbackZone.intValue());
        return fallbackZone;
    }

    @Nonnull
    public static String getSelectedZoneLabel(@Nonnull UUID playerId) {
        Integer zoneIndex = getSelectedZone(playerId);
        if (zoneIndex == null) {
            return "none";
        }
        return SpawnPointZoneManager.getFormattedZoneLabel(zoneIndex);
    }
}