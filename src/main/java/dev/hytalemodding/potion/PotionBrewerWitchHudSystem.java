package dev.hytalemodding.potion;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.hud.PotionBrewerWitchHud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PotionBrewerWitchHudSystem extends TickingSystem<EntityStore> {
    private static final long UPDATE_INTERVAL_MS = 50L;

    private final ConcurrentHashMap<UUID, PotionBrewerWitchHud> huds = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> playersWithActiveHud = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private long lastUpdateMs;

    public static boolean isPlayerSeeingWitchHud(@Nonnull UUID playerId) {
        return playersWithActiveHud.contains(playerId);
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastUpdateMs < UPDATE_INTERVAL_MS) {
            return;
        }
        this.lastUpdateMs = now;

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            PotionBrewerWitchSystem.HudSnapshot snapshot = getSnapshotForPlayer(playerRef);
            if (snapshot == null) {
                hideHud(playerRef);
                playersWithActiveHud.remove(playerRef.getUuid());
                continue;
            }
            playersWithActiveHud.add(playerRef.getUuid());
            showHud(playerRef, snapshot);
        }
    }

    @Nullable
    private static PotionBrewerWitchSystem.HudSnapshot getSnapshotForPlayer(@Nonnull PlayerRef playerRef) {
        UUID worldId = playerRef.getWorldUuid();
        if (worldId == null) {
            return null;
        }
        Vector3d position = playerRef.getTransform() == null
                ? Vector3d.ZERO
                : new Vector3d(playerRef.getTransform().getPosition());
        return PotionBrewerWitchSystem.getNearestHudSnapshot(worldId, position);
    }

    private void showHud(
            @Nonnull PlayerRef playerRef,
            @Nonnull PotionBrewerWitchSystem.HudSnapshot snapshot
    ) {
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

            PotionBrewerWitchHud hud = this.huds.computeIfAbsent(playerRef.getUuid(), ignored -> new PotionBrewerWitchHud(playerRef));
            hud.setHealth(snapshot.currentHealth, snapshot.maxHealth, snapshot.bossStage);
            hud.setCharges(snapshot.brewedCharges);
            hud.setVisible(true);

            player.getHudManager().setCustomHud(playerRef, hud);
            hud.show();
        });
    }

    private void hideHud(@Nonnull PlayerRef playerRef) {
        PotionBrewerWitchHud hud = this.huds.get(playerRef.getUuid());
        if (hud == null) {
            return;
        }
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
            hud.setVisible(false);
            CustomUIHud current = player.getHudManager().getCustomHud();
            if (current == hud) {
                player.getHudManager().setCustomHud(playerRef, null);
            }
        });
    }

}
