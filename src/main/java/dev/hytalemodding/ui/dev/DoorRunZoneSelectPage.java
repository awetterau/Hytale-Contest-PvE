package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.DoorRunZoneSelectionManager;
import dev.hytalemodding.state.run.SpawnPointZoneManager;

import javax.annotation.Nonnull;

public class DoorRunZoneSelectPage extends CustomUIPage {
    private static final int MAX_ZONE_BUTTONS = 8;

    public DoorRunZoneSelectPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        SpawnPointZoneManager.refreshForPlayer(this.playerRef);
        ui.append("d97's/Pages/DoorRunZoneSelectPage.ui");
        ui.set("#SelectedDoorZoneLabel.Text", "Selected door zone: " + DoorRunZoneSelectionManager.getSelectedZoneLabel(this.playerRef.getUuid()));

        int zoneCount = SpawnPointZoneManager.getZoneCount();
        for (int zoneIndex = 0; zoneIndex < MAX_ZONE_BUTTONS; zoneIndex++) {
            boolean visible = zoneIndex < zoneCount;
            String buttonId = "#DoorZone" + (zoneIndex + 1) + "Button";
            ui.set(buttonId + ".Visible", visible);
            if (visible) {
                ui.set("#DoorZone" + (zoneIndex + 1) + "Label.Text", SpawnPointZoneManager.getFormattedZoneLabel(zoneIndex));
                events.addEventBinding(CustomUIEventBindingType.Activating, buttonId, EventData.of("Action", "door_zone_" + zoneIndex), false);
            }
        }

        events.addEventBinding(CustomUIEventBindingType.Activating, "#EditZonesButton", EventData.of("Action", "edit_zones"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull String eventData
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        if (eventData.contains("door_zone_")) {
            int zoneIndex = parseTrailingIndex(eventData, "door_zone_");
            if (zoneIndex >= 0) {
                setZone(player, ref, store, zoneIndex);
            }
            return;
        }
        if (eventData.contains("edit_zones")) {
            player.getPageManager().openCustomPage(ref, store, new SpawnSelectPage(this.playerRef));
            return;
        }
        if (eventData.contains("close")) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    private void setZone(
            @Nonnull Player player,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            int zoneIndex
    ) {
        DoorRunZoneSelectionManager.setSelectedZone(this.playerRef.getUuid(), zoneIndex);
        player.sendMessage(Message.raw("Door run zone selected: " + DoorRunZoneSelectionManager.getSelectedZoneLabel(this.playerRef.getUuid()) + ". Select it again before the next door use."));
        player.getPageManager().openCustomPage(ref, store, new DoorRunZoneSelectPage(this.playerRef));
    }

    private int parseTrailingIndex(@Nonnull String eventData, @Nonnull String prefix) {
        int start = eventData.indexOf(prefix);
        if (start < 0) {
            return -1;
        }
        start += prefix.length();
        int end = start;
        while (end < eventData.length() && Character.isDigit(eventData.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(eventData.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}