package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.state.party.PartyManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartyManagerPage extends InteractiveCustomUIPage<PartyManagerPage.Data> {
    private static final int MAX_PARTY_ROWS = 8;
    private static final ConcurrentHashMap<UUID, List<PartyManager.PartySummary>> LAST_LIST_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> SHOW_CREATE_OVERLAY_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PENDING_PARTY_NAME_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PENDING_PARTY_SIZE_BY_PLAYER = new ConcurrentHashMap<>();

    public static final class Data {
        public String action;
        public String groupName;
        public String partyName;
        public String partySize;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .addField(new KeyedCodec("@GroupName", Codec.STRING), (d, v) -> ((Data) d).groupName = (String) v, d -> ((Data) d).groupName)
                .addField(new KeyedCodec("@PartyName", Codec.STRING), (d, v) -> ((Data) d).partyName = (String) v, d -> ((Data) d).partyName)
                .addField(new KeyedCodec("@PartySize", Codec.STRING), (d, v) -> ((Data) d).partySize = (String) v, d -> ((Data) d).partySize)
                .build();
    }

    public PartyManagerPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        PartyManager manager = PartyManager.get();
        PartyManager.PartySnapshot snapshot = manager.snapshot(this.playerRef.getUuid());
        List<PartyManager.PartySummary> all = new ArrayList<>(manager.listParties());
        LAST_LIST_BY_PLAYER.put(this.playerRef.getUuid(), all);
        UUID playerId = this.playerRef.getUuid();
        boolean hasParty = snapshot.partyId() != null;
        if (hasParty) {
            SHOW_CREATE_OVERLAY_BY_PLAYER.remove(playerId);
        }
        boolean showCreateOverlay = SHOW_CREATE_OVERLAY_BY_PLAYER.getOrDefault(playerId, false) && !hasParty;

        ui.append("d97's/Pages/PartyManagerPage.ui");
        ui.set("#PartyStatusLabel.Text", buildStatusText(snapshot));
        ui.set("#PartyMembersLabel.Text", buildMembersText(snapshot));
        ui.set("#InviteTargetInput.Value", snapshot.partyDisplayName());
        ui.set("#InviteButton.Text", "Save group");
        PartyManager.PartyInvite invite = manager.getPendingInvite(this.playerRef.getUuid());
        ui.set("#PendingInviteLabel.Text", invite == null
                ? "Pending invite: none"
                : "Pending invite from: " + PartyManager.resolvePlayerName(invite.leaderId()));

        for (int i = 0; i < MAX_PARTY_ROWS; i++) {
            String rowLabel = "#PartyRow" + (i + 1) + "Label";
            String rowButton = "#PartyRow" + (i + 1) + "JoinButton";
            boolean visible = i < all.size();
            ui.set(rowLabel + ".Visible", visible);
            ui.set(rowButton + ".Visible", visible);
            if (!visible) {
                continue;
            }
            PartyManager.PartySummary party = all.get(i);
            ui.set(
                    rowLabel + ".Text",
                    party.partyDisplayName() + " | " + party.members() + "/" + party.maxMembers() + " | Leader " + party.leaderName()
            );
            events.addEventBinding(CustomUIEventBindingType.Activating, rowButton, EventData.of("Action", "join_index_" + i), false);
        }

        ui.set("#CreatePartyButton.Visible", !hasParty);
        ui.set("#CreatePartyOverlay.Visible", showCreateOverlay);
        ui.set("#CreatePartyNameInput.Value", PENDING_PARTY_NAME_BY_PLAYER.getOrDefault(playerId, PartyManager.DEFAULT_PARTY_DISPLAY_NAME));
        ui.set("#CreatePartySizeInput.Value", PENDING_PARTY_SIZE_BY_PLAYER.getOrDefault(playerId, Integer.toString(PartyManager.DEFAULT_PARTY_SIZE)));

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CreatePartyButton", EventData.of("Action", "open_create_overlay"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CreatePartyCancelButton", EventData.of("Action", "cancel_create_overlay"), false);
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CreatePartyConfirmButton",
                new EventData()
                        .append("Action", "confirm_create_overlay")
                        .append("@PartyName", "#CreatePartyNameInput.Value")
                        .append("@PartySize", "#CreatePartySizeInput.Value"),
                false
        );
        events.addEventBinding(CustomUIEventBindingType.Activating, "#LeavePartyButton", EventData.of("Action", "leave"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DisbandPartyButton", EventData.of("Action", "disband"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AcceptInviteButton", EventData.of("Action", "accept"), false);
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#InviteButton",
                new EventData().append("Action", "rename_group").append("@GroupName", "#InviteTargetInput.Value"),
                false
        );
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton", EventData.of("Action", "refresh"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        PartyManager manager = PartyManager.get();
        String action = data.action == null ? "" : data.action.trim().toLowerCase(Locale.ROOT);
        PartyManager.ActionResult result = null;
        UUID playerId = this.playerRef.getUuid();
        boolean overlayOpen = SHOW_CREATE_OVERLAY_BY_PLAYER.getOrDefault(playerId, false);

        if ("close".equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if ("refresh".equals(action)) {
            reopen(ref, store, player);
            return;
        }
        if ("open_create_overlay".equals(action) || "create".equals(action)) {
            SHOW_CREATE_OVERLAY_BY_PLAYER.put(playerId, Boolean.TRUE);
            reopen(ref, store, player);
            return;
        }
        if ("cancel_create_overlay".equals(action)) {
            SHOW_CREATE_OVERLAY_BY_PLAYER.put(playerId, Boolean.FALSE);
            reopen(ref, store, player);
            return;
        }
        if (overlayOpen
                && !"confirm_create_overlay".equals(action)
                && !"cancel_create_overlay".equals(action)
                && !"open_create_overlay".equals(action)
                && !"refresh".equals(action)
                && !"close".equals(action)) {
            SHOW_CREATE_OVERLAY_BY_PLAYER.put(playerId, Boolean.FALSE);
        }
        if ("confirm_create_overlay".equals(action)) {
            String requestedName = data.partyName == null ? "" : data.partyName.trim();
            String requestedSize = data.partySize == null ? "" : data.partySize.trim();
            PENDING_PARTY_NAME_BY_PLAYER.put(playerId, requestedName);
            PENDING_PARTY_SIZE_BY_PLAYER.put(playerId, requestedSize);
            result = manager.createParty(playerId, requestedName, parsePartySize(requestedSize));
            if (result.success()) {
                SHOW_CREATE_OVERLAY_BY_PLAYER.put(playerId, Boolean.FALSE);
            } else {
                SHOW_CREATE_OVERLAY_BY_PLAYER.put(playerId, Boolean.TRUE);
            }
        } else if ("leave".equals(action)) {
            result = manager.leave(playerId);
        } else if ("disband".equals(action)) {
            result = manager.disband(playerId);
        } else if ("accept".equals(action)) {
            result = manager.accept(playerId);
        } else if ("rename_group".equals(action)) {
            result = manager.setPartyDisplayName(playerId, data.groupName == null ? "" : data.groupName);
        } else if (action.startsWith("join_index_")) {
            int index = parseIndex(action, "join_index_");
            List<PartyManager.PartySummary> list = LAST_LIST_BY_PLAYER.getOrDefault(playerId, List.of());
            if (index < 0 || index >= list.size()) {
                result = PartyManager.ActionResult.error("Party list index invalid.");
            } else {
                result = manager.joinPartyDirect(playerId, list.get(index).partyId());
            }
        }

        if (result != null) {
            player.sendMessage(Message.raw("[Party] " + result.message()));
        }
        reopen(ref, store, player);
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        player.getPageManager().openCustomPage(ref, store, new PartyManagerPage(this.playerRef));
    }

    @Nonnull
    private static String buildStatusText(@Nonnull PartyManager.PartySnapshot snapshot) {
        if (snapshot.partyId() == null) {
            return "You are not in a party.";
        }
        String leaderName = snapshot.leaderName() == null ? "unknown" : snapshot.leaderName();
        return "Group: " + snapshot.partyDisplayName() + "\nLeader: " + leaderName + "\nMembers: "
                + snapshot.members().size() + "/" + snapshot.maxMembers();
    }

    @Nonnull
    private static String buildMembersText(@Nonnull PartyManager.PartySnapshot snapshot) {
        if (snapshot.members().isEmpty()) {
            return "Members: none";
        }
        StringBuilder sb = new StringBuilder("Members:\n");
        for (PartyManager.MemberSnapshot member : snapshot.members()) {
            sb.append("- ").append(member.playerName())
                    .append(" (").append(member.leader() ? "L" : "M").append(")")
                    .append(" [").append(member.online() ? member.worldName() : "offline").append("]\n");
        }
        return sb.toString().trim();
    }

    private static int parseIndex(@Nonnull String raw, @Nonnull String prefix) {
        try {
            return Integer.parseInt(raw.substring(prefix.length()));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int parsePartySize(@Nonnull String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return PartyManager.DEFAULT_PARTY_SIZE;
        }
    }

}