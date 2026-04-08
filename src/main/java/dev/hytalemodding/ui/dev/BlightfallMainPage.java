package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.InfectionCoreRegistry;
import dev.hytalemodding.state.run.InfectionActionConfigManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlightfallMainPage extends InteractiveCustomUIPage<BlightfallMainPage.PageEventData> {
    private static final int DEFAULT_RUN_DURATION_SECONDS = 1200;
    private static final String SPAWN_CORE_BLOCK_ID = "Spawn_Crimson_Core_Block";
    private static final String SPAWN_WEAK_BLOCK_ID = "Spawn_Crimson_Core_Weak_Block";
    private static final int MAX_TIMELINE_EVENTS = 8;
    private static final ConcurrentHashMap<UUID, String> SECTION_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> SELECTED_TIMELINE_EVENT_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> SELECTED_WEAK_INDEX_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> SELECTED_CORE_INDEX_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ArrayList<TimelineEvent>> TIMELINE_EVENTS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> LOADED_ACTION_WORLD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> PENDING_EDITOR_INDEX_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PENDING_ACTION_TYPE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PENDING_CORE_TIER_BY_PLAYER = new ConcurrentHashMap<>();

    public BlightfallMainPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageEventData.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        String section = SECTION_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), "run");
        GameSessionManager.ActiveSessionSnapshot session = GameSessionManager.get().getActiveSession();

        ui.append("d97's/Pages/BlightfallMainPage.ui");
        ui.set("#WorldLabel.Text", "World: " + world.getName());
        ui.set("#SectionLabel.Text", "Section: " + formatSectionName(section));
        ui.set("#TemplateInput.Value", GameFlowConfigManager.get().getTemplateWorldName());
        ui.set("#RunDurationInput.Value", (double) GameSessionManager.get().getRunDurationSeconds());
        Long runSeed = GameSessionManager.get().getRunSeed();
        ui.set("#RunSeedInput.Value", runSeed == null ? "" : String.valueOf(runSeed));
        ui.set("#RunTimeHourMinInput.Value", (double) GameFlowConfigManager.get().getRunTimeHourMin());
        ui.set("#RunTimeHourMaxInput.Value", (double) GameFlowConfigManager.get().getRunTimeHourMax());
        boolean runControlVisible = "run".equals(section);
        ui.set("#TemplateWorldLabel.Visible", runControlVisible);
        ui.set("#TemplateInput.Visible", runControlVisible);
        ui.set("#RunDurationLabel.Visible", runControlVisible);
        ui.set("#RunDurationInput.Visible", runControlVisible);
        ui.set("#RunSeedLabel.Visible", runControlVisible);
        ui.set("#RunSeedInput.Visible", runControlVisible);
        ui.set("#RunTimeHourLabel.Visible", runControlVisible);
        ui.set("#RunTimeHourMinInput.Visible", runControlVisible);
        ui.set("#RunTimeHourMaxInput.Visible", runControlVisible);
        ui.set("#ApplyGameControlButton.Visible", runControlVisible);
        ui.set("#CenterContent.Text", buildCenterContent(section, worldId, session));
        ui.set("#RightContent.Text", buildRightContent(section, session));
        ui.set("#ToggleStatusChatButton.Text", "Status Chat: " + (GameFlowConfigManager.get().isStatusMessagesEnabled() ? "ON" : "OFF"));
        applyInfectionButtons(ui, events, worldId, "red".equals(section));
        applyInfectionTimeline(ui, events, "red".equals(section));

        events.addEventBinding(CustomUIEventBindingType.Activating, "#NavRunButton", EventData.of("Action", "nav_run"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NavRedButton", EventData.of("Action", "nav_red"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RunStartButton", EventData.of("Action", "run_start"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RunResetButton", EventData.of("Action", "run_reset"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RunStopButton", EventData.of("Action", "run_stop"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyGameControlButton", withGameControl("apply_game_control"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddEventButton", EventData.of("Action", "timeline_add"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveEventButton", EventData.of("Action", "timeline_remove"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevActionButton", EventData.of("Action", "action_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextActionButton", EventData.of("Action", "action_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ActionTypePrevButton", EventData.of("Action", "action_type_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ActionTypeNextButton", EventData.of("Action", "action_type_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CoreTierPrevButton", EventData.of("Action", "core_tier_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CoreTierNextButton", EventData.of("Action", "core_tier_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyEventButton", withTimelineEditor("timeline_apply"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleStatusChatButton", EventData.of("Action", "toggle_status_chat"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton", EventData.of("Action", "refresh"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageEventData eventData) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = eventData.action == null ? "" : eventData.action.trim().toLowerCase();
        switch (action) {
            case "close" -> player.getPageManager().setPage(ref, store, Page.None);
            case "refresh" -> reopen(ref, store, player);
            case "nav_run" -> {
                SECTION_BY_PLAYER.put(this.playerRef.getUuid(), "run");
                reopen(ref, store, player);
            }
            case "nav_red" -> {
                SECTION_BY_PLAYER.put(this.playerRef.getUuid(), "red");
                reopen(ref, store, player);
            }
            case "run_start" -> startRun();
            case "run_reset" -> resetRun();
            case "run_stop" -> stopRun();
            case "apply_game_control" -> {
                applyGameControl(eventData);
                reopen(ref, store, player);
            }
            case "timeline_add" -> {
                addTimelineEvent();
                reopen(ref, store, player);
            }
            case "timeline_remove" -> {
                removeTimelineEvent();
                reopen(ref, store, player);
            }
            case "action_prev" -> {
                cycleTimelineSelection(-1);
                reopen(ref, store, player);
            }
            case "action_next" -> {
                cycleTimelineSelection(1);
                reopen(ref, store, player);
            }
            case "action_type_prev" -> {
                cycleSelectedActionType(-1);
                reopen(ref, store, player);
            }
            case "action_type_next" -> {
                cycleSelectedActionType(1);
                reopen(ref, store, player);
            }
            case "core_tier_prev" -> {
                cycleSelectedCoreTier(-1);
                reopen(ref, store, player);
            }
            case "core_tier_next" -> {
                cycleSelectedCoreTier(1);
                reopen(ref, store, player);
            }
            case "timeline_apply" -> {
                applyTimelineEventEditor(eventData);
                reopen(ref, store, player);
            }
            case "toggle_status_chat" -> {
                boolean next = !GameFlowConfigManager.get().isStatusMessagesEnabled();
                GameFlowConfigManager.get().setStatusMessagesEnabled(next);
                if (next) {
                    sendUiMessage("Status chat messages: ON");
                }
                reopen(ref, store, player);
            }
            case "weak_prev" -> {
                shiftInfectionSelection(store, true, -1);
                reopen(ref, store, player);
            }
            case "weak_next" -> {
                shiftInfectionSelection(store, true, 1);
                reopen(ref, store, player);
            }
            case "core_prev" -> {
                shiftInfectionSelection(store, false, -1);
                reopen(ref, store, player);
            }
            case "core_next" -> {
                shiftInfectionSelection(store, false, 1);
                reopen(ref, store, player);
            }
            case "weak_tp" -> teleportToSelectedInfectionBlock(store, true);
            case "core_tp" -> teleportToSelectedInfectionBlock(store, false);
            default -> {
                if (action.startsWith("timeline_select_")) {
                    selectTimelineEvent(action);
                    reopen(ref, store, player);
                }
            }
        }
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        player.getPageManager().openCustomPage(ref, store, new BlightfallMainPage(this.playerRef));
    }

    @Nonnull
    private static EventData withGameControl(@Nonnull String action) {
        return new EventData()
                .append("Action", action)
                .append("@TemplateWorld", "#TemplateInput.Value")
                .append("@RunDurationSeconds", "#RunDurationInput.Value")
                .append("@RunSeed", "#RunSeedInput.Value")
                .append("@RunTimeHourMin", "#RunTimeHourMinInput.Value")
                .append("@RunTimeHourMax", "#RunTimeHourMaxInput.Value");
    }

    @Nonnull
    private static EventData withTimelineEditor(@Nonnull String action) {
        return new EventData()
                .append("Action", action)
                .append("@TriggerSecond", "#EventTriggerSecondInput.Value")
                .append("@CoreCount", "#EventCoreCountInput.Value")
                .append("@CoreType", "#EventCoreTypeInput.Value")
                .append("@EventEnabled", "#EventEnabledInput.Value")
                .append("@ActionType", "#ActionTypeInput.Value")
                .append("@CoreTier", "#CoreTierInput.Value")
                .append("@Probability", "#ProbabilityInput.Value");
    }

    @Nonnull
    private static String buildCenterContent(@Nonnull String section, @Nonnull UUID worldId, GameSessionManager.ActiveSessionSnapshot session) {
        return switch (section) {
            case "red" -> buildInfectionManagerContent(worldId);
            default -> "RUN CONTROL\nUse this panel for start/reset/stop and Apply Game Control.\n\nActive session: " + (session == null ? "No" : "Yes");
        };
    }

    @Nonnull
    private static String buildRightContent(@Nonnull String section, GameSessionManager.ActiveSessionSnapshot session) {
        long elapsedSeconds = 0L;
        if (session != null
                && (session.phase() == GameSessionManager.RunPhase.EXPLORATION
                || session.phase() == GameSessionManager.RunPhase.CRIMSON_ACTIVE)
                && session.startedAtEpochMillis() > 0L) {
            elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - session.startedAtEpochMillis()) / 1000L);
        }
        return "EXTRAS\nSection: " + formatSectionName(section) + "\nRun phase: "
                + (session == null ? "IDLE" : session.phase().name()) + "\nElapsed: " + elapsedSeconds + "s";
    }

    @Nonnull
    private static String formatSectionName(@Nonnull String section) {
        if ("red".equals(section)) {
            return "INFECTION MANAGER";
        }
        return "run".equals(section) ? "RUN CONTROL" : section.toUpperCase();
    }

    @Nonnull
    private static String buildInfectionManagerContent(@Nonnull UUID worldId) {
        int weakCoreDetected = InfectionCoreRegistry.getWeakCoreCount(worldId);
        int coreDetected = InfectionCoreRegistry.getCoreCount(worldId);
        int configuredCrimsonCores = RedCoreRegistry.snapshot(worldId).size();
        return "INFECTION MANAGER\n"
                + "Selected world live tracking + timeline editor.\n\n"
                + "Weak block: " + InfectionCoreRegistry.WEAK_CORE_BLOCK_ID + "\n"
                + "Weak state: " + InfectionCoreRegistry.WEAK_CORE_BLOCK_ENTITY_STATE_ID + " -> " + weakCoreDetected + "\n"
                + "Core block: " + InfectionCoreRegistry.CORE_BLOCK_ID + "\n"
                + "Core state: " + InfectionCoreRegistry.CORE_BLOCK_ENTITY_STATE_ID + " -> " + coreDetected + "\n\n"
                + "Saved redwave core candidates: " + configuredCrimsonCores + "\n"
                + "Active waves: " + RedWaveManager.getActiveWaves(worldId).size() + "\n"
                + "Spawn core block: " + SPAWN_CORE_BLOCK_ID + "\n"
                + "Spawn weak block: " + SPAWN_WEAK_BLOCK_ID;
    }

    private void applyInfectionButtons(
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull UUID worldId,
            boolean visible
    ) {
        List<Vector3i> weakPositions = InfectionCoreRegistry.snapshotWeakPositions(worldId);
        List<Vector3i> corePositions = InfectionCoreRegistry.snapshotCorePositions(worldId);
        ui.set("#WeakListTitle.Text", "Weak (" + weakPositions.size() + ")");
        ui.set("#CoreListTitle.Text", "Core (" + corePositions.size() + ")");
        ui.set("#WeakListTitle.Visible", visible);
        ui.set("#CoreListTitle.Visible", visible);
        ui.set("#WeakDropdownRow.Visible", visible);
        ui.set("#CoreDropdownRow.Visible", visible);

        int weakIndex = normalizeSelectionIndex(SELECTED_WEAK_INDEX_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), 0), weakPositions.size());
        int coreIndex = normalizeSelectionIndex(SELECTED_CORE_INDEX_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), 0), corePositions.size());
        SELECTED_WEAK_INDEX_BY_PLAYER.put(this.playerRef.getUuid(), weakIndex);
        SELECTED_CORE_INDEX_BY_PLAYER.put(this.playerRef.getUuid(), coreIndex);

        ui.set("#WeakDropdownValue.Text", formatSelectionLabel(weakPositions, weakIndex));
        ui.set("#CoreDropdownValue.Text", formatSelectionLabel(corePositions, coreIndex));

        ui.set("#WeakPrevButton.Visible", visible);
        ui.set("#WeakNextButton.Visible", visible);
        ui.set("#WeakTpButton.Visible", visible);
        ui.set("#CorePrevButton.Visible", visible);
        ui.set("#CoreNextButton.Visible", visible);
        ui.set("#CoreTpButton.Visible", visible);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#WeakPrevButton", EventData.of("Action", "weak_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#WeakNextButton", EventData.of("Action", "weak_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#WeakTpButton", EventData.of("Action", "weak_tp"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CorePrevButton", EventData.of("Action", "core_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CoreNextButton", EventData.of("Action", "core_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CoreTpButton", EventData.of("Action", "core_tp"), false);
    }

    private void applyInfectionTimeline(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, boolean visible) {
        ui.set("#TimelineSection.Visible", visible);
        ui.set("#EventEditorSection.Visible", visible);
        if (!visible) {
            return;
        }

        List<TimelineEvent> timeline = getTimelineForPlayer();
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        TimelineEvent selected = selectedIndex >= 0 && selectedIndex < timeline.size() ? timeline.get(selectedIndex) : null;
        String selectedActionType = selected == null ? "spawn" : selected.actionType;
        String selectedCoreTier = selected == null ? "core" : selected.coreTier;
        Integer pendingIndex = PENDING_EDITOR_INDEX_BY_PLAYER.get(this.playerRef.getUuid());
        if (selected != null && pendingIndex != null && pendingIndex.intValue() == selectedIndex) {
            selectedActionType = PENDING_ACTION_TYPE_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), selectedActionType);
            selectedCoreTier = PENDING_CORE_TIER_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), selectedCoreTier);
        }

        ui.set("#TimelineHeader.Text", "Timeline Events (" + timeline.size() + ")");
        for (int i = 0; i < MAX_TIMELINE_EVENTS; i++) {
            String buttonId = "#TimelineEventButton" + (i + 1);
            boolean buttonVisible = i < timeline.size();
            ui.set(buttonId + ".Visible", buttonVisible);
            if (buttonVisible) {
                TimelineEvent event = timeline.get(i);
                String selectedMarker = i == selectedIndex ? "* " : "";
                ui.set(buttonId + ".Text", selectedMarker + event.actionType.toUpperCase() + "/" + event.coreTier.toUpperCase() + " @" + event.triggerSecond + "s p=" + event.probabilityPercent + "%");
                events.addEventBinding(CustomUIEventBindingType.Activating, buttonId, EventData.of("Action", "timeline_select_" + i), false);
            }
        }

        ui.set("#ActionSelectorLabel.Text", selected == null ? "No action" : "Action " + (selectedIndex + 1) + "/" + timeline.size() + " - " + selectedActionType.toUpperCase());
        ui.set("#EventEditorTitle.Text", selected == null ? "No action selected" : "Configure action #" + (selectedIndex + 1));
        ui.set("#EventTriggerSecondInput.Value", selected == null ? 30d : (double) selected.triggerSecond);
        ui.set("#EventCoreCountInput.Value", selected == null ? 6d : (double) selected.coreCount);
        ui.set("#EventCoreTypeInput.Value", selected == null ? "2" : String.valueOf(selected.ticksPerBlock));
        ui.set("#EventEnabledInput.Value", selected == null ? "true" : String.valueOf(selected.enabled));
        ui.set("#ActionTypeSelectorValue.Text", selectedActionType.toUpperCase());
        ui.set("#ActionTypeInput.Value", selectedActionType);
        ui.set("#CoreTierSelectorValue.Text", selectedCoreTier.toUpperCase());
        ui.set("#CoreTierInput.Value", selectedCoreTier);
        ui.set("#ProbabilityInput.Value", selected == null ? 100d : (double) selected.probabilityPercent);
    }

    private void shiftInfectionSelection(@Nonnull Store<EntityStore> store, boolean weakList, int direction) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        List<Vector3i> positions = weakList
                ? InfectionCoreRegistry.snapshotWeakPositions(worldId)
                : InfectionCoreRegistry.snapshotCorePositions(worldId);
        if (positions.isEmpty()) {
            return;
        }
        ConcurrentHashMap<UUID, Integer> map = weakList ? SELECTED_WEAK_INDEX_BY_PLAYER : SELECTED_CORE_INDEX_BY_PLAYER;
        int current = normalizeSelectionIndex(map.getOrDefault(this.playerRef.getUuid(), 0), positions.size());
        int next = (current + direction) % positions.size();
        if (next < 0) {
            next += positions.size();
        }
        map.put(this.playerRef.getUuid(), next);
    }

    private void teleportToSelectedInfectionBlock(@Nonnull Store<EntityStore> store, boolean weakList) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        List<Vector3i> positions = weakList
                ? InfectionCoreRegistry.snapshotWeakPositions(worldId)
                : InfectionCoreRegistry.snapshotCorePositions(worldId);
        if (positions.isEmpty()) {
            sendUiMessage("No infection blocks available in this list.");
            return;
        }
        ConcurrentHashMap<UUID, Integer> map = weakList ? SELECTED_WEAK_INDEX_BY_PLAYER : SELECTED_CORE_INDEX_BY_PLAYER;
        int selectedIndex = normalizeSelectionIndex(map.getOrDefault(this.playerRef.getUuid(), 0), positions.size());
        map.put(this.playerRef.getUuid(), selectedIndex);

        Vector3i pos = positions.get(selectedIndex);
        Transform target = new Transform(new Vector3d(pos.x + 0.5d, pos.y + 0.15d, pos.z + 0.5d), new Vector3f(0f, 0f, 0f));
        Ref<EntityStore> playerEntityRef = this.playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }
        Teleport teleport = Teleport.createForPlayer(world, target);
        store.addComponent(playerEntityRef, Teleport.getComponentType(), teleport);
    }

    private static int normalizeSelectionIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        if (index >= size) {
            return size - 1;
        }
        return index;
    }

    @Nonnull
    private static String formatSelectionLabel(@Nonnull List<Vector3i> positions, int index) {
        if (positions.isEmpty()) {
            return "(empty)";
        }
        int normalized = normalizeSelectionIndex(index, positions.size());
        Vector3i pos = positions.get(normalized);
        return "[" + (normalized + 1) + "/" + positions.size() + "] " + formatPos(pos);
    }

    private void applyGameControl(@Nonnull PageEventData eventData) {
        String templateWorld = normalizedTemplate(eventData.templateWorld);
        World resolvedTemplate = Universe.get().getWorld(templateWorld);
        if (resolvedTemplate == null) {
            sendUiMessage("Template world not found: " + templateWorld);
            return;
        }

        int runDurationSeconds = DEFAULT_RUN_DURATION_SECONDS;
        if (eventData.runDurationSeconds != null) {
            runDurationSeconds = Math.max(1, (int) Math.round(eventData.runDurationSeconds));
        }

        Long runSeed = parseRunSeed(eventData.runSeed);
        int minHour = parseHourOrDefault(eventData.runTimeHourMin, GameFlowConfigManager.get().getRunTimeHourMin());
        int maxHour = parseHourOrDefault(eventData.runTimeHourMax, GameFlowConfigManager.get().getRunTimeHourMax());
        GameFlowConfigManager.get().setTemplateWorldName(templateWorld);
        GameSessionManager.get().setRunDurationSeconds(runDurationSeconds);
        GameSessionManager.get().setRunSeed(runSeed);
        GameFlowConfigManager.get().setRunTimeHourRange(minHour, maxHour);

        sendUiMessage("Applied Game Control: template=" + templateWorld
                + ", duration=" + runDurationSeconds + "s, seed=" + (runSeed == null ? "random" : runSeed)
                + ", timeRange=" + minHour + "-" + maxHour);
    }

    private void addTimelineEvent() {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        if (timeline.size() >= MAX_TIMELINE_EVENTS) {
            sendUiMessage("Timeline is full (max " + MAX_TIMELINE_EVENTS + " events).");
            return;
        }
        timeline.add(defaultAction("spawn"));
        SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), timeline.size() - 1);
        persistActionsForPlayer();
        sendUiMessage("Action added: spawn.");
    }

    private void removeTimelineEvent() {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        if (selectedIndex < 0) {
            sendUiMessage("No timeline event selected.");
            return;
        }
        if (timeline.size() <= 1) {
            sendUiMessage("At least one action must remain.");
            return;
        }
        timeline.remove(selectedIndex);
        int replacement = timeline.isEmpty() ? -1 : Math.min(selectedIndex, timeline.size() - 1);
        SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), replacement);
        persistActionsForPlayer();
        sendUiMessage("Action removed.");
    }


    private void cycleTimelineSelection(int direction) {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        if (timeline.isEmpty()) {
            return;
        }
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        int next = (selectedIndex + direction) % timeline.size();
        if (next < 0) {
            next += timeline.size();
        }
        SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), next);
        clearPendingEditorSelection();
    }


    private void cycleSelectedActionType(int direction) {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        if (selectedIndex < 0) {
            return;
        }
        String[] options = {"spawn", "grow"};
        String currentValue = getCurrentActionTypeForSelection(timeline, selectedIndex);
        int current = "grow".equalsIgnoreCase(currentValue) ? 1 : 0;
        int next = (current + direction) % options.length;
        if (next < 0) {
            next += options.length;
        }
        PENDING_EDITOR_INDEX_BY_PLAYER.put(this.playerRef.getUuid(), selectedIndex);
        PENDING_ACTION_TYPE_BY_PLAYER.put(this.playerRef.getUuid(), options[next]);
    }


    private void cycleSelectedCoreTier(int direction) {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        if (selectedIndex < 0) {
            return;
        }
        String[] options = {"core", "weak"};
        String currentValue = getCurrentCoreTierForSelection(timeline, selectedIndex);
        int current = "weak".equalsIgnoreCase(currentValue) ? 1 : 0;
        int next = (current + direction) % options.length;
        if (next < 0) {
            next += options.length;
        }
        PENDING_EDITOR_INDEX_BY_PLAYER.put(this.playerRef.getUuid(), selectedIndex);
        PENDING_CORE_TIER_BY_PLAYER.put(this.playerRef.getUuid(), options[next]);
    }

    private void selectTimelineEvent(@Nonnull String action) {
        int selectedIndex = -1;
        if (action.startsWith("timeline_select_")) {
            try {
                selectedIndex = Integer.parseInt(action.substring("timeline_select_".length()));
            } catch (NumberFormatException ignored) {
                selectedIndex = -1;
            }
        }
        List<TimelineEvent> timeline = getTimelineForPlayer();
        if (selectedIndex >= 0 && selectedIndex < timeline.size()) {
            SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), selectedIndex);
            clearPendingEditorSelection();
        }
    }

    private void applyTimelineEventEditor(@Nonnull PageEventData eventData) {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        if (selectedIndex < 0) {
            sendUiMessage("No timeline event selected.");
            return;
        }

        TimelineEvent event = timeline.get(selectedIndex);
        Integer pendingIndex = PENDING_EDITOR_INDEX_BY_PLAYER.get(this.playerRef.getUuid());
        if (pendingIndex != null && pendingIndex.intValue() == selectedIndex) {
            String pendingActionType = PENDING_ACTION_TYPE_BY_PLAYER.remove(this.playerRef.getUuid());
            String pendingCoreTier = PENDING_CORE_TIER_BY_PLAYER.remove(this.playerRef.getUuid());
            if (pendingActionType != null) {
                event.actionType = pendingActionType;
            }
            if (pendingCoreTier != null) {
                event.coreTier = pendingCoreTier;
            }
            PENDING_EDITOR_INDEX_BY_PLAYER.remove(this.playerRef.getUuid());
        }
        if (eventData.triggerSecond != null) {
            event.triggerSecond = Math.max(0, (int) Math.round(eventData.triggerSecond));
        }
        if (eventData.coreCount != null) {
            event.coreCount = Math.max(1, (int) Math.round(eventData.coreCount));
        }
        if (eventData.coreType != null && !eventData.coreType.isBlank()) {
            try {
                event.ticksPerBlock = Math.max(1, Integer.parseInt(eventData.coreType.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (eventData.eventEnabled != null) {
            event.enabled = Boolean.parseBoolean(eventData.eventEnabled.trim());
        }

        if (eventData.actionType != null && !eventData.actionType.isBlank()) {
            String normalizedAction = eventData.actionType.trim().toLowerCase();
            if ("spawn".equals(normalizedAction) || "grow".equals(normalizedAction)) {
                event.actionType = normalizedAction;
            }
        }
        if (eventData.coreTier != null && !eventData.coreTier.isBlank()) {
            String normalizedTier = eventData.coreTier.trim().toLowerCase();
            if ("core".equals(normalizedTier) || "weak".equals(normalizedTier)) {
                event.coreTier = normalizedTier;
            }
        }
        if (eventData.probability != null) {
            event.probabilityPercent = Math.max(0, Math.min(100, (int) Math.round(eventData.probability)));
        }

        persistActionsForPlayer();
        sendUiMessage("Action #" + (selectedIndex + 1) + " updated.");
    }


    @Nonnull
    private static TimelineEvent defaultAction(@Nonnull String actionType) {
        if ("grow".equalsIgnoreCase(actionType)) {
            return new TimelineEvent("grow", "core", 60, 12, 1, 100, true);
        }
        return new TimelineEvent("spawn", "core", 30, 6, 2, 100, true);
    }

    private List<TimelineEvent> getTimelineForPlayer() {
        String worldName = resolvePlayerWorldName();
        String loadedWorld = LOADED_ACTION_WORLD_BY_PLAYER.get(this.playerRef.getUuid());
        if (loadedWorld == null || !loadedWorld.equalsIgnoreCase(worldName)) {
            ArrayList<TimelineEvent> loaded = new ArrayList<>();
            for (InfectionActionConfigManager.ActionEntry entry : InfectionActionConfigManager.loadActions(worldName)) {
                loaded.add(new TimelineEvent(entry.actionType(), entry.coreTier(), entry.triggerSecond(), entry.radius(), entry.ticksPerBlock(), entry.probabilityPercent(), entry.enabled()));
            }
            if (loaded.isEmpty()) {
                loaded.add(defaultAction("spawn"));
                InfectionActionConfigManager.saveActions(worldName, List.of(new InfectionActionConfigManager.ActionEntry("spawn", "core", 30, 6, 2, 100, true)));
            }
            TIMELINE_EVENTS_BY_PLAYER.put(this.playerRef.getUuid(), loaded);
            LOADED_ACTION_WORLD_BY_PLAYER.put(this.playerRef.getUuid(), worldName);
        }
        return TIMELINE_EVENTS_BY_PLAYER.computeIfAbsent(this.playerRef.getUuid(), ignored -> {
            ArrayList<TimelineEvent> defaults = new ArrayList<>();
            defaults.add(defaultAction("spawn"));
            return defaults;
        });
    }

    private void persistActionsForPlayer() {
        String worldName = resolvePlayerWorldName();
        List<TimelineEvent> timeline = TIMELINE_EVENTS_BY_PLAYER.get(this.playerRef.getUuid());
        if (timeline == null) {
            return;
        }
        ArrayList<InfectionActionConfigManager.ActionEntry> entries = new ArrayList<>(timeline.size());
        for (TimelineEvent event : timeline) {
            entries.add(new InfectionActionConfigManager.ActionEntry(event.actionType, event.coreTier, event.triggerSecond, event.coreCount, event.ticksPerBlock, event.probabilityPercent, event.enabled));
        }
        InfectionActionConfigManager.saveActions(worldName, entries);
    }

    private void clearPendingEditorSelection() {
        PENDING_EDITOR_INDEX_BY_PLAYER.remove(this.playerRef.getUuid());
        PENDING_ACTION_TYPE_BY_PLAYER.remove(this.playerRef.getUuid());
        PENDING_CORE_TIER_BY_PLAYER.remove(this.playerRef.getUuid());
    }

    @Nonnull
    private String getCurrentActionTypeForSelection(@Nonnull List<TimelineEvent> timeline, int selectedIndex) {
        if (selectedIndex < 0 || selectedIndex >= timeline.size()) {
            return "spawn";
        }
        Integer pendingIndex = PENDING_EDITOR_INDEX_BY_PLAYER.get(this.playerRef.getUuid());
        if (pendingIndex != null && pendingIndex.intValue() == selectedIndex) {
            return PENDING_ACTION_TYPE_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), timeline.get(selectedIndex).actionType);
        }
        return timeline.get(selectedIndex).actionType;
    }

    @Nonnull
    private String getCurrentCoreTierForSelection(@Nonnull List<TimelineEvent> timeline, int selectedIndex) {
        if (selectedIndex < 0 || selectedIndex >= timeline.size()) {
            return "core";
        }
        Integer pendingIndex = PENDING_EDITOR_INDEX_BY_PLAYER.get(this.playerRef.getUuid());
        if (pendingIndex != null && pendingIndex.intValue() == selectedIndex) {
            return PENDING_CORE_TIER_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), timeline.get(selectedIndex).coreTier);
        }
        return timeline.get(selectedIndex).coreTier;
    }

    @Nonnull
    private String resolvePlayerWorldName() {
        UUID worldUuid = this.playerRef.getWorldUuid();
        if (worldUuid != null) {
            World world = Universe.get().getWorld(worldUuid);
            if (world != null) {
                return world.getName();
            }
        }
        return GameFlowConfigManager.get().getTemplateWorldName();
    }

    private int getSelectedTimelineIndex(int timelineSize) {
        int index = SELECTED_TIMELINE_EVENT_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), 0);
        if (timelineSize <= 0) {
            return -1;
        }
        if (index < 0 || index >= timelineSize) {
            index = 0;
            SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), index);
        }
        return index;
    }

    @Nullable
    private static Long parseRunSeed(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseHourOrDefault(@Nullable Double value, int fallback) {
        if (value == null || Double.isNaN(value)) {
            return Math.max(0, Math.min(23, fallback));
        }
        return Math.max(0, Math.min(23, (int) Math.round(value)));
    }


    @Nonnull
    private static String formatPos(@Nonnull Vector3i pos) {
        return pos.x + ", " + pos.y + ", " + pos.z;
    }

    @Nonnull
    private static String normalizedTemplate(String template) {
        if (template == null || template.isBlank()) {
            return GameFlowConfigManager.get().getTemplateWorldName();
        }
        return template.trim();
    }

    private void startRun() {
        if (!GameDoorInteractionHandler.executeDoorRunFlow(this.playerRef)) {
            sendUiMessage("Unable to trigger door start flow from this context.");
        }
    }

    private void resetRun() {
        if (!GameSessionManager.get().hasActiveSession()) {
            sendUiMessage("No active run to reset. Use Run Start.");
            return;
        }
        GameSessionManager.get().endSession().whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                sendUiMessage("Failed to reset run: " + reason);
                return;
            }
            if (!result.success()) {
                sendUiMessage("Reset cancelled: " + result.message());
                return;
            }
            sendUiMessage("Run ended. Starting a new run using your selected door zone...");
            GameDoorInteractionHandler.tryStartFromDoorSelection(this.playerRef);
        });
    }

    private void stopRun() {
        if (!GameSessionManager.get().hasActiveSession()) {
            sendUiMessage("No active run to stop.");
            return;
        }
        GameFlowConfigManager config = GameFlowConfigManager.get();
        World hubWorld = resolveHubWorldOrDefault(config.getHubWorldName());
        Transform returnSpawn = config.getBaseSpawn();
        GameSessionManager.get().endSession(returnSpawn, hubWorld).whenComplete((result, throwable) -> {
            if (throwable != null) {
                String reason = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
                sendUiMessage("Failed to stop run: " + reason);
                return;
            }
            sendUiMessage(result.message());
        });
    }

    private void sendUiMessage(@Nonnull String text) {
        if (!GameFlowConfigManager.get().isStatusMessagesEnabled()) {
            return;
        }
        this.playerRef.sendMessage(Message.raw(text));
    }

    private World resolveHubWorldOrDefault(String hubWorldName) {
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        if (hubWorldName != null && !hubWorldName.isBlank()) {
            World configuredHub = universe.getWorld(hubWorldName);
            if (configuredHub != null) {
                return configuredHub;
            }
        }
        UUID playerWorldUuid = this.playerRef.getWorldUuid();
        if (playerWorldUuid != null) {
            World playerWorld = universe.getWorld(playerWorldUuid);
            if (playerWorld != null) {
                return playerWorld;
            }
        }
        return universe.getDefaultWorld();
    }

    private static final class TimelineEvent {
        @Nonnull
        private String actionType;
        @Nonnull
        private String coreTier;
        private int triggerSecond;
        private int coreCount;
        private int ticksPerBlock;
        private int probabilityPercent;
        private boolean enabled;

        private TimelineEvent(@Nonnull String actionType, @Nonnull String coreTier, int triggerSecond, int coreCount, int ticksPerBlock, int probabilityPercent, boolean enabled) {
            this.actionType = actionType;
            this.coreTier = coreTier;
            this.triggerSecond = triggerSecond;
            this.coreCount = coreCount;
            this.ticksPerBlock = ticksPerBlock;
            this.probabilityPercent = probabilityPercent;
            this.enabled = enabled;
        }
    }


    public static List<RuntimeAction> snapshotRuntimeActions(@Nonnull UUID playerId) {
        ArrayList<TimelineEvent> events = TIMELINE_EVENTS_BY_PLAYER.get(playerId);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        ArrayList<RuntimeAction> copy = new ArrayList<>(events.size());
        for (TimelineEvent e : events) {
            copy.add(new RuntimeAction(e.actionType, e.coreTier, e.triggerSecond, e.coreCount, e.ticksPerBlock, e.probabilityPercent, e.enabled));
        }
        return copy;
    }

    public record RuntimeAction(
            @Nonnull String actionType,
            @Nonnull String coreTier,
            int triggerSecond,
            int radius,
            int ticksPerBlock,
            int probabilityPercent,
            boolean enabled
    ) {
    }

    public static class PageEventData {
        @Nonnull
        public String action = "";
        @Nonnull
        public String templateWorld = "";
        @Nullable
        public Double runDurationSeconds;
        @Nonnull
        public String runSeed = "";
        @Nullable
        public Double runTimeHourMin;
        @Nullable
        public Double runTimeHourMax;
        @Nullable
        public Double triggerSecond;
        @Nullable
        public Double coreCount;
        @Nonnull
        public String coreType = "";
        @Nonnull
        public String eventEnabled = "";
        @Nonnull
        public String actionType = "";
        @Nonnull
        public String coreTier = "";
        @Nullable
        public Double probability;

        public static final BuilderCodec<PageEventData> CODEC = BuilderCodec.builder(PageEventData.class, PageEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
                .add()
                .append(new KeyedCodec<>("@TemplateWorld", Codec.STRING), (d, v) -> d.templateWorld = v, d -> d.templateWorld)
                .add()
                .append(new KeyedCodec<>("@RunDurationSeconds", Codec.DOUBLE), (d, v) -> d.runDurationSeconds = v, d -> d.runDurationSeconds)
                .add()
                .append(new KeyedCodec<>("@RunSeed", Codec.STRING), (d, v) -> d.runSeed = v, d -> d.runSeed)
                .add()
                .append(new KeyedCodec<>("@RunTimeHourMin", Codec.DOUBLE), (d, v) -> d.runTimeHourMin = v, d -> d.runTimeHourMin)
                .add()
                .append(new KeyedCodec<>("@RunTimeHourMax", Codec.DOUBLE), (d, v) -> d.runTimeHourMax = v, d -> d.runTimeHourMax)
                .add()
                .append(new KeyedCodec<>("@TriggerSecond", Codec.DOUBLE), (d, v) -> d.triggerSecond = v, d -> d.triggerSecond)
                .add()
                .append(new KeyedCodec<>("@CoreCount", Codec.DOUBLE), (d, v) -> d.coreCount = v, d -> d.coreCount)
                .add()
                .append(new KeyedCodec<>("@CoreType", Codec.STRING), (d, v) -> d.coreType = v, d -> d.coreType)
                .add()
                .append(new KeyedCodec<>("@EventEnabled", Codec.STRING), (d, v) -> d.eventEnabled = v, d -> d.eventEnabled)
                .add()
                .append(new KeyedCodec<>("@ActionType", Codec.STRING), (d, v) -> d.actionType = v, d -> d.actionType)
                .add()
                .append(new KeyedCodec<>("@CoreTier", Codec.STRING), (d, v) -> d.coreTier = v, d -> d.coreTier)
                .add()
                .append(new KeyedCodec<>("@Probability", Codec.DOUBLE), (d, v) -> d.probability = v, d -> d.probability)
                .add()
                .build();
    }
}