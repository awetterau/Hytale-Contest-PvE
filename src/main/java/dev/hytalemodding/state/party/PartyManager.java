package dev.hytalemodding.state.party;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PartyManager {
    public static final int DEFAULT_PARTY_SIZE = 4;
    public static final int MIN_PARTY_SIZE = 2;
    public static final int MAX_PARTY_SIZE = 5;
    public static final String DEFAULT_PARTY_DISPLAY_NAME = "Exploration group";
    private static final PartyManager INSTANCE = new PartyManager();
    private static final long INVITE_TTL_MS = 30_000L;
    private static final int MAX_PARTY_DISPLAY_NAME_LENGTH = 20;
    private static final ConcurrentHashMap<UUID, String> DISPLAY_NAME_CACHE_BY_PLAYER = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Party> partyById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> partyIdByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PartyInvite> inviteByTarget = new ConcurrentHashMap<>();

    private PartyManager() {
    }

    @Nonnull
    public static PartyManager get() {
        return INSTANCE;
    }

    @Nonnull
    public synchronized ActionResult createParty(@Nonnull UUID leaderId) {
        return createParty(leaderId, DEFAULT_PARTY_DISPLAY_NAME, DEFAULT_PARTY_SIZE);
    }

    @Nonnull
    public synchronized ActionResult createParty(@Nonnull UUID leaderId, @Nullable String requestedDisplayName, int requestedMaxMembers) {
        if (this.partyIdByPlayer.containsKey(leaderId)) {
            return ActionResult.error("You are already in a party.");
        }
        Party party = new Party(
                UUID.randomUUID(),
                leaderId,
                normalizePartyDisplayName(requestedDisplayName),
                normalizePartySize(requestedMaxMembers)
        );
        this.partyById.put(party.partyId(), party);
        this.partyIdByPlayer.put(leaderId, party.partyId());
        return ActionResult.ok("Party created. You are the leader.");
    }

    @Nonnull
    public synchronized ActionResult invite(@Nonnull UUID leaderId, @Nonnull UUID targetId) {
        Party party = getPartyByMemberId(leaderId);
        if (party == null) {
            return ActionResult.error("Create a party first.");
        }
        if (!party.leaderId().equals(leaderId)) {
            return ActionResult.error("Only the party leader can invite.");
        }
        if (leaderId.equals(targetId)) {
            return ActionResult.error("You cannot invite yourself.");
        }
        if (party.memberIds().size() >= party.maxMembers()) {
            return ActionResult.error("Party is full (max " + party.maxMembers() + ").");
        }
        if (this.partyIdByPlayer.containsKey(targetId)) {
            return ActionResult.error("Target is already in a party.");
        }
        PlayerRef leaderRef = Universe.get().getPlayer(leaderId);
        PlayerRef targetRef = Universe.get().getPlayer(targetId);
        if (!isHubTogether(leaderRef, targetRef)) {
            return ActionResult.error("Invites only work in hub with leader present.");
        }
        this.inviteByTarget.put(targetId, new PartyInvite(leaderId, targetId, System.currentTimeMillis() + INVITE_TTL_MS));
        return ActionResult.ok("Invitation sent.");
    }

    @Nonnull
    public synchronized ActionResult accept(@Nonnull UUID targetId) {
        PartyInvite invite = this.inviteByTarget.get(targetId);
        if (invite == null) {
            return ActionResult.error("No pending invite.");
        }
        if (invite.expiresAtMs() < System.currentTimeMillis()) {
            this.inviteByTarget.remove(targetId);
            return ActionResult.error("Invite expired.");
        }
        Party party = getPartyByMemberId(invite.leaderId());
        if (party == null || !party.leaderId().equals(invite.leaderId())) {
            this.inviteByTarget.remove(targetId);
            return ActionResult.error("Party/leader no longer available.");
        }
        if (party.memberIds().size() >= party.maxMembers()) {
            this.inviteByTarget.remove(targetId);
            return ActionResult.error("Party is full.");
        }
        PlayerRef leaderRef = Universe.get().getPlayer(invite.leaderId());
        PlayerRef targetRef = Universe.get().getPlayer(targetId);
        if (!isHubTogether(leaderRef, targetRef)) {
            return ActionResult.error("Join requires leader present in hub.");
        }
        if (this.partyIdByPlayer.containsKey(targetId)) {
            return ActionResult.error("You are already in a party.");
        }
        party.memberIds().add(targetId);
        this.partyIdByPlayer.put(targetId, party.partyId());
        this.inviteByTarget.remove(targetId);
        return ActionResult.ok("Joined party.");
    }

    @Nonnull
    public synchronized ActionResult leave(@Nonnull UUID playerId) {
        Party party = getPartyByMemberId(playerId);
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (party.leaderId().equals(playerId)) {
            return disband(playerId);
        }
        party.memberIds().remove(playerId);
        this.partyIdByPlayer.remove(playerId);
        this.inviteByTarget.remove(playerId);
        return ActionResult.ok("You left the party.");
    }

    @Nonnull
    public synchronized ActionResult disband(@Nonnull UUID leaderId) {
        Party party = getPartyByMemberId(leaderId);
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (!party.leaderId().equals(leaderId)) {
            return ActionResult.error("Only the party leader can disband.");
        }
        for (UUID memberId : party.memberIds()) {
            this.partyIdByPlayer.remove(memberId);
            this.inviteByTarget.remove(memberId);
        }
        this.partyById.remove(party.partyId());
        return ActionResult.ok("Party disbanded.");
    }

    @Nonnull
    public synchronized ActionResult kick(@Nonnull UUID leaderId, @Nonnull UUID targetId) {
        Party party = getPartyByMemberId(leaderId);
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (!party.leaderId().equals(leaderId)) {
            return ActionResult.error("Only the leader can kick.");
        }
        if (!party.memberIds().contains(targetId)) {
            return ActionResult.error("Target is not in your party.");
        }
        if (leaderId.equals(targetId)) {
            return ActionResult.error("Leader cannot kick self. Use /party disband.");
        }
        party.memberIds().remove(targetId);
        this.partyIdByPlayer.remove(targetId);
        this.inviteByTarget.remove(targetId);
        return ActionResult.ok("Player removed from party.");
    }

    @Nonnull
    public synchronized ActionResult transferLeader(@Nonnull UUID leaderId, @Nonnull UUID targetId) {
        Party party = getPartyByMemberId(leaderId);
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (!party.leaderId().equals(leaderId)) {
            return ActionResult.error("Only the leader can transfer leadership.");
        }
        if (!party.memberIds().contains(targetId)) {
            return ActionResult.error("Target is not in your party.");
        }
        party.leaderId = targetId;
        return ActionResult.ok("Leader transferred.");
    }

    @Nonnull
    public synchronized ActionResult setPartyDisplayName(@Nonnull UUID leaderId, @Nonnull String requestedDisplayName) {
        Party party = getPartyByMemberId(leaderId);
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (!party.leaderId().equals(leaderId)) {
            return ActionResult.error("Only the party leader can edit the group name.");
        }
        party.displayName = normalizePartyDisplayName(requestedDisplayName);
        return ActionResult.ok("Group name updated.");
    }

    @Nullable
    public synchronized Party getPartyByMemberId(@Nonnull UUID playerId) {
        UUID partyId = this.partyIdByPlayer.get(playerId);
        if (partyId == null) {
            return null;
        }
        Party party = this.partyById.get(partyId);
        if (party == null) {
            this.partyIdByPlayer.remove(playerId);
            return null;
        }
        return party;
    }

    @Nonnull
    public synchronized PartySnapshot snapshot(@Nonnull UUID playerId) {
        Party party = getPartyByMemberId(playerId);
        if (party == null) {
            return new PartySnapshot(null, null, null, DEFAULT_PARTY_DISPLAY_NAME, DEFAULT_PARTY_SIZE, List.of());
        }
        List<MemberSnapshot> members = new ArrayList<>();
        for (UUID memberId : party.memberIds()) {
            PlayerRef memberRef = Universe.get().getPlayer(memberId);
            boolean online = memberRef != null && memberRef.isValid();
            String worldName = "offline";
            if (online && memberRef.getWorldUuid() != null) {
                var world = Universe.get().getWorld(memberRef.getWorldUuid());
                worldName = world == null ? "<unknown>" : world.getName();
            }
            members.add(new MemberSnapshot(memberId, resolvePlayerName(memberId), memberId.equals(party.leaderId()), online, worldName));
        }
        return new PartySnapshot(
                party.partyId(),
                party.leaderId(),
                resolvePlayerName(party.leaderId()),
                party.displayName(),
                party.maxMembers(),
                members
        );
    }

    @Nonnull
    public synchronized List<PartySummary> listParties() {
        List<PartySummary> summaries = new ArrayList<>();
        for (Party party : this.partyById.values()) {
            summaries.add(new PartySummary(
                    party.partyId(),
                    party.leaderId(),
                    resolvePlayerName(party.leaderId()),
                    party.displayName(),
                    party.memberIds().size(),
                    party.maxMembers()
            ));
        }
        summaries.sort(java.util.Comparator.comparing(PartySummary::partyId));
        return summaries;
    }

    public synchronized boolean isLeaderOrSolo(@Nonnull UUID playerId) {
        Party party = getPartyByMemberId(playerId);
        return party == null || party.leaderId().equals(playerId);
    }

    @Nonnull
    public synchronized List<UUID> getRunParticipantIds(@Nonnull UUID initiatorId) {
        Party party = getPartyByMemberId(initiatorId);
        if (party == null) {
            return List.of(initiatorId);
        }
        if (!party.leaderId().equals(initiatorId)) {
            return List.of();
        }
        return List.copyOf(party.memberIds());
    }

    @Nonnull
    public static String resolvePlayerName(@Nonnull UUID playerId) {
        String cached = DISPLAY_NAME_CACHE_BY_PLAYER.get(playerId);
        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null) {
            return cached == null || cached.isBlank() ? "Unknown Player" : cached;
        }
        var entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return cached == null || cached.isBlank() ? "Unknown Player" : cached;
        }
        var store = entityRef.getStore();
        if (store == null) {
            return cached == null || cached.isBlank() ? "Unknown Player" : cached;
        }
        var nameplate = store.getComponent(entityRef, Nameplate.getComponentType());
        if (nameplate != null && nameplate.getText() != null && !nameplate.getText().isBlank()) {
            String value = nameplate.getText().trim();
            DISPLAY_NAME_CACHE_BY_PLAYER.put(playerId, value);
            return value;
        }
        var player = store.getComponent(entityRef, com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
        if (player == null || player.getDisplayName() == null || player.getDisplayName().isBlank()) {
            return cached == null || cached.isBlank() ? "Unknown Player" : cached;
        }
        String displayName = player.getDisplayName().trim();
        DISPLAY_NAME_CACHE_BY_PLAYER.put(playerId, displayName);
        return displayName;
    }

    @Nonnull
    private static String normalizePartyDisplayName(@Nullable String requestedDisplayName) {
        if (requestedDisplayName == null) {
            return DEFAULT_PARTY_DISPLAY_NAME;
        }
        String trimmed = requestedDisplayName.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_PARTY_DISPLAY_NAME;
        }
        if (trimmed.length() > MAX_PARTY_DISPLAY_NAME_LENGTH) {
            return trimmed.substring(0, MAX_PARTY_DISPLAY_NAME_LENGTH);
        }
        return trimmed;
    }

    private static int normalizePartySize(int requestedMaxMembers) {
        if (requestedMaxMembers < MIN_PARTY_SIZE) {
            return MIN_PARTY_SIZE;
        }
        return Math.min(MAX_PARTY_SIZE, requestedMaxMembers);
    }

    @Nonnull
    public synchronized ActionResult joinPartyDirect(@Nonnull UUID playerId, @Nonnull UUID partyId) {
        if (this.partyIdByPlayer.containsKey(playerId)) {
            return ActionResult.error("You are already in a party.");
        }
        Party party = this.partyById.get(partyId);
        if (party == null) {
            return ActionResult.error("Party not found.");
        }
        if (party.memberIds().size() >= party.maxMembers()) {
            return ActionResult.error("Party is full.");
        }
        PlayerRef leaderRef = Universe.get().getPlayer(party.leaderId());
        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (!isHubTogether(leaderRef, playerRef)) {
            return ActionResult.error("Leader must be present in hub.");
        }
        party.memberIds().add(playerId);
        this.partyIdByPlayer.put(playerId, partyId);
        return ActionResult.ok("You joined the party.");
    }

    @Nullable
    public synchronized PartyInvite getPendingInvite(@Nonnull UUID playerId) {
        PartyInvite invite = this.inviteByTarget.get(playerId);
        if (invite == null) {
            return null;
        }
        if (invite.expiresAtMs() < System.currentTimeMillis()) {
            this.inviteByTarget.remove(playerId);
            return null;
        }
        return invite;
    }

    private static boolean isHubTogether(@Nullable PlayerRef leaderRef, @Nullable PlayerRef otherRef) {
        if (leaderRef == null || otherRef == null || !leaderRef.isValid() || !otherRef.isValid()) {
            return false;
        }
        if (leaderRef.getWorldUuid() == null || otherRef.getWorldUuid() == null) {
            return false;
        }
        if (!leaderRef.getWorldUuid().equals(otherRef.getWorldUuid())) {
            return false;
        }
        var world = Universe.get().getWorld(leaderRef.getWorldUuid());
        return world != null && GameDoorInteractionHandler.isHubWorld(world);
    }

    public static final class Party {
        private final UUID partyId;
        private UUID leaderId;
        private String displayName;
        private final int maxMembers;
        private final Set<UUID> memberIds = new LinkedHashSet<>();

        private Party(@Nonnull UUID partyId, @Nonnull UUID leaderId, @Nonnull String displayName, int maxMembers) {
            this.partyId = partyId;
            this.leaderId = leaderId;
            this.displayName = displayName;
            this.maxMembers = normalizePartySize(maxMembers);
            this.memberIds.add(leaderId);
        }

        @Nonnull
        public UUID partyId() {
            return this.partyId;
        }

        @Nonnull
        public UUID leaderId() {
            return this.leaderId;
        }

        @Nonnull
        public Set<UUID> memberIds() {
            return this.memberIds;
        }

        @Nonnull
        public String displayName() {
            return this.displayName;
        }

        public int maxMembers() {
            return this.maxMembers;
        }
    }

    public record ActionResult(boolean success, @Nonnull String message) {
        @Nonnull
        public static ActionResult ok(@Nonnull String message) {
            return new ActionResult(true, message);
        }

        @Nonnull
        public static ActionResult error(@Nonnull String message) {
            return new ActionResult(false, message);
        }
    }

    public record PartyInvite(@Nonnull UUID leaderId, @Nonnull UUID targetId, long expiresAtMs) {
    }

    public record MemberSnapshot(@Nonnull UUID playerId, @Nonnull String playerName, boolean leader, boolean online, @Nonnull String worldName) {
    }

    public record PartySnapshot(
            @Nullable UUID partyId,
            @Nullable UUID leaderId,
            @Nullable String leaderName,
            @Nonnull String partyDisplayName,
            int maxMembers,
            @Nonnull List<MemberSnapshot> members
    ) {
    }

    public record PartySummary(
            @Nonnull UUID partyId,
            @Nonnull UUID leaderId,
            @Nonnull String leaderName,
            @Nonnull String partyDisplayName,
            int members,
            int maxMembers
    ) {
    }
}