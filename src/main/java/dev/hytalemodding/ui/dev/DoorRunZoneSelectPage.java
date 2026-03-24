package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.DoorRunZoneSelectionManager;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.SpawnPointZoneManager;

import javax.annotation.Nonnull;

public class DoorRunZoneSelectPage extends InteractiveCustomUIPage<DoorRunZoneSelectPage.Data> {
    private static final int MAX_ZONE_BUTTONS = 8;

    public static final class Data {
        public String action;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
                .build();
    }

    public DoorRunZoneSelectPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        SpawnPointZoneManager.refreshForPlayer(this.playerRef);
        DoorRunZoneSelectionManager.ensureSelectedZoneOrDefault(this.playerRef.getUuid());
        ui.append("d97's/Pages/DoorRunZoneSelectPage.ui");

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

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton", EventData.of("Action", "cancel"), false);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull Data data
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String eventData = data.action == null ? "" : data.action.trim().toLowerCase();

        if (eventData.contains("door_zone_")) {
            int zoneIndex = parseTrailingIndex(eventData, "door_zone_");
            if (zoneIndex >= 0) {
                setZoneAndStart(player, ref, store, zoneIndex);
            }
            return;
        }
        if (eventData.contains("cancel")) {
            close();
            return;
        }
    }

    private void setZoneAndStart(
            @Nonnull Player player,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            int zoneIndex
    ) {
        DoorRunZoneSelectionManager.setSelectedZone(this.playerRef.getUuid(), zoneIndex);
        player.sendMessage(Message.raw("Door run zone selected: " + DoorRunZoneSelectionManager.getSelectedZoneLabel(this.playerRef.getUuid()) + ". Loading run."));
        close();
        GameDoorInteractionHandler.tryStartFromDoorSelection(this.playerRef);
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
