package dev.hytalemodding.state.run;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.hud.GameTimerHud;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;
import dev.hytalemodding.redwave.RedWaveConfig;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class GameRunDirectorSystem extends TickingSystem<EntityStore> {
    private final ConcurrentHashMap<UUID, GameTimerHud> timerHuds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastShownSecond = new ConcurrentHashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        GameSessionManager.ActiveSessionSnapshot snapshot = GameSessionManager.get().getActiveSession();
        if (snapshot == null || snapshot.runWorldUuid() == null) {
            hideAllTimerHuds();
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        if (!snapshot.runWorldUuid().equals(worldId)) {
            return;
        }

        long remainingMs = Math.max(0L, snapshot.runEndsAtEpochMillis() - System.currentTimeMillis());
        updateRunWorldTimerHud(worldId, remainingMs);

        if (snapshot.crimsonEnabled() && snapshot.phase() == GameSessionManager.RunPhase.EXPLORATION && GameSessionManager.get().shouldActivateCrimson()) {
            if (RedWaveManager.getActiveWave(worldId) == null) {
                int started = 0;
                for (RedCoreProfileRegistry.RedCoreProfile profile : snapshot.crimsonProfiles()) {
                    var coreType = world.getBlockType(profile.corePos().x, profile.corePos().y, profile.corePos().z);
                    if (coreType == null || !RedWaveConfig.CORE_BLOCK_ID.equals(coreType.getId())) {
                        continue;
                    }
                    RedWaveManager.beginUndoSession(worldId, profile.corePos());
                    RedWaveManager.startWave(worldId, profile.corePos(), profile.radiusBlocks(), profile.startSeconds());
                    started++;
                }
                if (started > 0) {
                    sendRunWorldMessage(worldId, "Crimson infection is spreading. Return to base.");
                } else {
                    sendRunWorldMessage(worldId, "No valid Crimson_Core blocks found in run world.");
                }
            }
            GameSessionManager.get().markCrimsonActive();
        }
    }

    private void updateRunWorldTimerHud(@Nonnull UUID runWorldId, long remainingMs) {
        long secondsLeft = remainingMs / 1000L;
        String formatted = formatTime(secondsLeft);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID playerWorld = playerRef.getWorldUuid();
            UUID playerId = playerRef.getUuid();
            if (playerWorld == null || !playerWorld.equals(runWorldId)) {
                hideTimerHud(playerRef);
                this.lastShownSecond.remove(playerId);
                continue;
            }
            Long last = this.lastShownSecond.get(playerId);
            if (last != null && last == secondsLeft) {
                continue;
            }
            this.lastShownSecond.put(playerId, secondsLeft);
            showTimerHud(playerRef, formatted);
        }
    }

    private void showTimerHud(@Nonnull PlayerRef playerRef, @Nonnull String timeString) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        entityStore.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = entityStore.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            GameTimerHud hud = this.timerHuds.computeIfAbsent(playerRef.getUuid(), ignored -> new GameTimerHud(playerRef));
            hud.setTime(timeString);
            hud.setVisible(true);
            player.getHudManager().setCustomHud(playerRef, hud);
            hud.show();
        });
    }

    private void hideTimerHud(@Nonnull PlayerRef playerRef) {
        GameTimerHud hud = this.timerHuds.get(playerRef.getUuid());
        if (hud == null) {
            return;
        }
        hud.setVisible(false);
        hud.show();
    }

    private void hideAllTimerHuds() {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            hideTimerHud(playerRef);
        }
        this.lastShownSecond.clear();
    }

    @Nonnull
    private static String formatTime(long totalSeconds) {
        long clamped = Math.max(0L, totalSeconds);
        long minutes = clamped / 60L;
        long seconds = clamped % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static void sendRunWorldMessage(@Nonnull UUID runWorldId, @Nonnull String text) {
        Message message = Message.raw(text);
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid != null && worldUuid.equals(runWorldId)) {
                playerRef.sendMessage(message);
            }
        }
    }
}