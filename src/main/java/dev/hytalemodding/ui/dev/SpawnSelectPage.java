package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.run.DoorRunZoneSelectionManager;
import dev.hytalemodding.state.run.SpawnPointZoneManager;

import javax.annotation.Nonnull;

public class SpawnSelectPage extends CustomUIPage {
    private static final String SPAWN_POINT_BLOCK_ID = "SpawnPoint_Block";
    private static final int MAX_ZONE_BUTTONS = 8;
    private static final int MAX_LOCATION_BUTTONS = 8;

    public SpawnSelectPage(@Nonnull PlayerRef playerRef) {
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

        if (isEditingLocked(store)) {
            ui.append("d97's/Pages/SpawnSelectPageLocked.ui");
            ui.set("#HubEditBlockedLabel.Text", "You can only edit spawn zones in: " + String.join(", ", SpawnPointZoneManager.getEditableWorldNames()) + ". Current world: " + store.getExternalData().getWorld().getName() + ".");
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DoorRunZoneButton", EventData.of("Action", "door_run_zone"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
            return;
        }

        ui.append("d97's/Pages/SpawnSelectPage.ui");
        applyZoneUiState(ui);
        bindSelectableButtons(events, ui);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddZoneButton", EventData.of("Action", "add_zone"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveZoneButton", EventData.of("Action", "remove_zone"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddLocationButton", EventData.of("Action", "add_location"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveLocationButton", EventData.of("Action", "remove_location"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetSpawnPointButton", EventData.of("Action", "set_spawnpoint"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DoorRunZoneButton", EventData.of("Action", "door_run_zone"), false);
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

        if (eventData.contains("close")) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if (eventData.contains("door_run_zone")) {
            player.getPageManager().openCustomPage(ref, store, new DoorRunZoneSelectPage(this.playerRef));
            return;
        }

        SpawnPointZoneManager.refreshForPlayer(this.playerRef);

        if (isEditingLocked(store)) {
            return;
        }

        if (eventData.contains("select_zone_")) {
            int zoneIndex = parseTrailingIndex(eventData, "select_zone_");
            if (zoneIndex >= 0) {
                SpawnPointZoneManager.setActiveZone(this.playerRef, zoneIndex);
                reopen(ref, store, player);
            }
            return;
        }
        if (eventData.contains("select_location_")) {
            int locationIndex = parseTrailingIndex(eventData, "select_location_");
            if (locationIndex >= 0) {
                SpawnPointZoneManager.setActiveLocation(this.playerRef, locationIndex);
                reopen(ref, store, player);
            }
            return;
        }
        if (eventData.contains("add_zone")) {
            SpawnPointZoneManager.addZone(this.playerRef);
            reopen(ref, store, player);
            return;
        }
        if (eventData.contains("remove_zone")) {
            SpawnPointZoneManager.removeZone(this.playerRef);
            reopen(ref, store, player);
            return;
        }
        if (eventData.contains("add_location")) {
            SpawnPointZoneManager.addLocation(this.playerRef);
            reopen(ref, store, player);
            return;
        }
        if (eventData.contains("remove_location")) {
            SpawnPointZoneManager.removeLocation(this.playerRef);
            reopen(ref, store, player);
            return;
        }
        if (eventData.contains("set_spawnpoint")) {
            World world = store.getExternalData().getWorld();
            Transform transform = this.playerRef.getTransform();
            Vector3i pos = new Vector3i(
                    MathUtil.floor(transform.getPosition().getX()),
                    MathUtil.floor(transform.getPosition().getY()) - 1,
                    MathUtil.floor(transform.getPosition().getZ())
            );
            world.setBlock(pos.x, pos.y, pos.z, SPAWN_POINT_BLOCK_ID);
            SpawnPointZoneManager.registerPlacement(pos);
            reopen(ref, store, player);
        }
    }

    private void bindSelectableButtons(@Nonnull UIEventBuilder events, @Nonnull UICommandBuilder ui) {
        int zoneCount = SpawnPointZoneManager.getZoneCount();
        int activeZoneIndex = SpawnPointZoneManager.getActiveZoneIndex();
        int locationCount = SpawnPointZoneManager.getLocationCount(activeZoneIndex);

        for (int zoneIndex = 0; zoneIndex < MAX_ZONE_BUTTONS; zoneIndex++) {
            boolean visible = zoneIndex < zoneCount;
            String buttonId = "#SetZone" + (zoneIndex + 1) + "Button";
            ui.set(buttonId + ".Visible", visible);
            if (visible) {
                ui.set("#SetZone" + (zoneIndex + 1) + "ButtonLabel.Text", SpawnPointZoneManager.getFormattedZoneLabel(zoneIndex));
                events.addEventBinding(CustomUIEventBindingType.Activating, buttonId, EventData.of("Action", "select_zone_" + zoneIndex), false);
            }
        }

        for (int locationIndex = 0; locationIndex < MAX_LOCATION_BUTTONS; locationIndex++) {
            boolean visible = locationIndex < locationCount;
            String buttonId = "#SetLocation" + (locationIndex + 1) + "Button";
            ui.set(buttonId + ".Visible", visible);
            if (visible) {
                ui.set("#SetLocation" + (locationIndex + 1) + "ButtonLabel.Text",
                        SpawnPointZoneManager.getFormattedLocationLabel(activeZoneIndex, locationIndex));
                events.addEventBinding(CustomUIEventBindingType.Activating, buttonId, EventData.of("Action", "select_location_" + locationIndex), false);
            }
        }

        ui.set("#RemoveZoneButton.Visible", zoneCount > 1);
        ui.set("#RemoveLocationButton.Visible", locationCount > 1);
        ui.set("#ZoneButtonLimitLabel.Visible", zoneCount >= MAX_ZONE_BUTTONS);
        ui.set("#LocationButtonLimitLabel.Visible", locationCount >= MAX_LOCATION_BUTTONS);
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        player.getPageManager().openCustomPage(ref, store, new SpawnSelectPage(this.playerRef));
    }

    private boolean isEditingLocked(@Nonnull Store<EntityStore> store) {
        return !SpawnPointZoneManager.isEditableWorld(store.getExternalData().getWorld());
    }

    private void applyZoneUiState(@Nonnull UICommandBuilder ui) {
        int zoneIndex = SpawnPointZoneManager.getActiveZoneIndex();
        int locationIndex = SpawnPointZoneManager.getActiveLocationIndex();
        ui.set("#PlacementModeLabel.Text", "Active: " + SpawnPointZoneManager.getPlacementSelectionLabel());
        ui.set("#DoorRunZoneStatusLabel.Text", "Door zone: " + DoorRunZoneSelectionManager.getSelectedZoneLabel(this.playerRef.getUuid()));
        ui.set("#ActiveLocationLabel.Text", "Selected location: " + SpawnPointZoneManager.getFormattedLocationLabel(zoneIndex, locationIndex));
        ui.set("#ActiveLocationCountLabel.Text", "Registered blocks: " + SpawnPointZoneManager.getCountForLocation(zoneIndex, locationIndex));
        ui.set("#ActiveLocationListLabel.Text", SpawnPointZoneManager.getListText(zoneIndex, locationIndex));
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