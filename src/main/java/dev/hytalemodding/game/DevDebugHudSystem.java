package dev.hytalemodding.game;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.hud.DevDebugHud;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DevDebugHudSystem extends TickingSystem<EntityStore> {
    private static final String BLACKSMITH = "blacksmith";
    private final ConcurrentHashMap<UUID, DevDebugHud> huds = new ConcurrentHashMap<>();
    private long lastUpdateMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        if (now - this.lastUpdateMs < 700L) {
            return;
        }
        this.lastUpdateMs = now;
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            boolean enabled = DevDebugManager.get().isHudEnabled(playerRef.getUuid());
            if (!enabled) {
                hideHud(playerRef);
                continue;
            }
            showHud(playerRef);
        }
    }

    private void showHud(@Nonnull PlayerRef playerRef) {
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
            DevDebugHud hud = this.huds.computeIfAbsent(playerRef.getUuid(), ignored -> new DevDebugHud(playerRef));
            hud.setLines(buildLines(playerRef));
            hud.setVisible(true);
            player.getHudManager().setCustomHud(playerRef, hud);
            hud.show();
        });
    }

    private void hideHud(@Nonnull PlayerRef playerRef) {
        DevDebugHud hud = this.huds.get(playerRef.getUuid());
        if (hud == null) {
            return;
        }
        hud.setVisible(false);
        hud.show();
    }

    @Nonnull
    private static List<String> buildLines(@Nonnull PlayerRef playerRef) {
        List<String> lines = new ArrayList<>();
        BaseHousingManager housing = BaseHousingManager.get();
        HubNpcManager.NpcData npc = housing.getNpcData(BLACKSMITH);
        String worldName = "<unknown>";
        UUID worldId = playerRef.getWorldUuid();
        if (worldId != null) {
            World world = Universe.get().getWorld(worldId);
            if (world != null) {
                worldName = world.getName();
            }
        }

        lines.add("[DEV] Hub NPC/Plot Debug");
        lines.add("World=" + worldName + " rescued=" + GameFlowConfigManager.get().isBlacksmithRescued());
        lines.add("NPC=" + npc.profession + " lvl=" + npc.level + " state=" + npc.state.name() + " assignedPlot=" + (npc.assignedPlotId == null ? "<none>" : npc.assignedPlotId));
        lines.add("Recipes=" + npc.unlockedRecipes.size() + " Quests=" + npc.availableQuests.size());

        List<String> plotIds = housing.getPlotIdsForWorld(worldName);
        if (plotIds.isEmpty()) {
            lines.add("Plots: <none>");
        } else {
            BaseHousingManager.PlotData first = housing.getPlot(plotIds.get(0));
            if (first != null) {
                lines.add("Plot[" + first.id + "] type=" + first.plotType + " purchased=" + first.purchased + " lvl=" + first.buildingLevel);
                lines.add("Plot[" + first.id + "] assignedNPC=" + (first.assignedNpcKey == null ? "<none>" : first.assignedNpcKey));
            }
            if (plotIds.size() > 1) {
                BaseHousingManager.PlotData second = housing.getPlot(plotIds.get(1));
                if (second != null) {
                    lines.add("Plot[" + second.id + "] type=" + second.plotType + " purchased=" + second.purchased + " assigned=" + (second.assignedNpcKey == null ? "<none>" : second.assignedNpcKey));
                }
            }
        }
        lines.add("Use /devpanel for guided test buttons.");
        return lines;
    }
}


