package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
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
import dev.hytalemodding.redwave.RedWoolDamageSystem;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.InfectionCoreRegistry;
import dev.hytalemodding.state.run.InfectionActionConfigManager;
import dev.hytalemodding.state.run.RunChunkSelectionManager;
import dev.hytalemodding.state.run.RunExtractionConfigManager;
import dev.hytalemodding.state.transition.GameFlowConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
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
    private static final ConcurrentHashMap<UUID, Integer> TIMELINE_PAGE_START_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> SELECTED_WEAK_INDEX_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> SELECTED_CORE_INDEX_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ArrayList<TimelineEvent>> TIMELINE_EVENTS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> LOADED_ACTION_WORLD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> PENDING_EDITOR_INDEX_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PENDING_ACTION_TYPE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PENDING_CORE_TIER_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> CHUNK_BRUSH_RANGE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> EXTRACTION_VARIANT_BY_PLAYER = new ConcurrentHashMap<>();
    private static final String EXTRACTION_VARIANT_PLATFORM = "platform_rune";
    private static final String EXTRACTION_VARIANT_ROPE = "escape_rope";
    private static volatile boolean defaultWorldHeightGuardEnabled = GameFlowConfigManager.get().isDefaultWorldHeightGuardEnabled();
    private static volatile boolean gameWorldRedirectEnabled = GameFlowConfigManager.get().isGameWorldRedirectEnabled();

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
        boolean chunkControlVisible = "chunks".equals(section);
        boolean infectionControlVisible = "red".equals(section);
        boolean extractionControlVisible = "extractions".equals(section);
        int chunkBrushRange = Math.max(0, Math.min(5, CHUNK_BRUSH_RANGE_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), 0)));
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
        ui.set("#ChunkToolsSection.Visible", chunkControlVisible);
        ui.set("#ChunkVisualToggleButton.Text", "Map Visual: " + (RunChunkSelectionManager.get().isEnabled(this.playerRef) ? "ON" : "OFF"));
        ui.set("#ChunkBrushRangeValue.Text", Integer.toString(chunkBrushRange));
        ui.set("#ChunkBrushSizeLabel.Text", "Brush: " + ((chunkBrushRange * 2) + 1) + "x" + ((chunkBrushRange * 2) + 1));
        boolean alertsEnabled = GameFlowConfigManager.get().isStatusMessagesEnabled() || GameFlowConfigManager.get().isChunkLoadingMessagesEnabled();
        ui.set("#ToggleAlertsButton.Text", "Alerts: " + (alertsEnabled ? "ON" : "OFF"));
        ui.set("#ToggleDynamicMapButton.Visible", runControlVisible);
        ui.set("#ToggleDynamicMapButton.Text", "Dynamic map: " + (GameSessionManager.isDynamicMapRefreshEnabled() ? "ON" : "OFF"));
        ui.set("#ToggleDefaultWorldHeightGuardButton.Visible", runControlVisible);
        ui.set("#ToggleDefaultWorldHeightGuardButton.Text", "Default height guard: " + (defaultWorldHeightGuardEnabled ? "ON" : "OFF"));
        ui.set("#ToggleGameWorldRedirectButton.Visible", runControlVisible);
        ui.set("#ToggleGameWorldRedirectButton.Text", "Game world redirect: " + (gameWorldRedirectEnabled ? "ON" : "OFF"));
        RedWoolDamageSystem.setHazardFogEnabled(GameFlowConfigManager.get().isHazardFogWeatherEnabled());
        ui.set("#ToggleFogWeatherCenterButton.Visible", infectionControlVisible);
        ui.set("#ToggleFogWeatherCenterButton.Text", "Hazard fog: " + (RedWoolDamageSystem.isHazardFogEnabled() ? "ON" : "OFF"));
        ui.set("#ToggleCoreRadiusChatButton.Visible", infectionControlVisible);
        ui.set("#ToggleCoreRadiusChatButton.Text", "Core radius chat: " + (GameFlowConfigManager.get().isCoreRadiusChatMessagesEnabled() ? "ON" : "OFF"));
        ui.set("#ChunkReloadFileButton.Visible", chunkControlVisible);
        applyInfectionButtons(ui, events, worldId, "red".equals(section));
        applyInfectionTimeline(ui, events, "red".equals(section));
        applyExtractionSection(ui, events, extractionControlVisible);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#NavRunButton", EventData.of("Action", "nav_run"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NavRedButton", EventData.of("Action", "nav_red"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NavExtractionButton", EventData.of("Action", "nav_extractions"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NavChunksButton", EventData.of("Action", "nav_chunks"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkBrushRangeDownButton", EventData.of("Action", "chunk_range_down"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkBrushRangeUpButton", EventData.of("Action", "chunk_range_up"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkVisualToggleButton", EventData.of("Action", "chunk_toggle_visual"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkSetPinnedButton", EventData.of("Action", "chunk_set_pin"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkSetMarkerButton", EventData.of("Action", "chunk_set_marker"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkSetClearButton", EventData.of("Action", "chunk_set_clear"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RunStartButton", EventData.of("Action", "run_start"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RunResetButton", EventData.of("Action", "run_reset"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RunStopButton", EventData.of("Action", "run_stop"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyGameControlButton", withGameControl("apply_game_control"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddEventButton", EventData.of("Action", "timeline_add"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveEventButton", EventData.of("Action", "timeline_remove"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TimeLinePagePrevButton", EventData.of("Action", "timeline_page_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TimeLinePageNextButton", EventData.of("Action", "timeline_page_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevActionButton", EventData.of("Action", "action_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextActionButton", EventData.of("Action", "action_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ActionTypePrevButton", EventData.of("Action", "action_type_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ActionTypeNextButton", EventData.of("Action", "action_type_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CoreTierPrevButton", EventData.of("Action", "core_tier_prev"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CoreTierNextButton", EventData.of("Action", "core_tier_next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyEventButton", withTimelineEditor("timeline_apply"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleAlertsButton", EventData.of("Action", "toggle_alerts"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleDynamicMapButton", EventData.of("Action", "toggle_dynamic_map"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleDefaultWorldHeightGuardButton", EventData.of("Action", "toggle_default_world_height_guard"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleGameWorldRedirectButton", EventData.of("Action", "toggle_game_world_redirect"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleFogWeatherCenterButton", EventData.of("Action", "toggle_fog_weather"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleCoreRadiusChatButton", EventData.of("Action", "toggle_core_radius_chat"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ChunkReloadFileButton", EventData.of("Action", "chunk_reload_file"), false);
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
            case "nav_extractions" -> {
                SECTION_BY_PLAYER.put(this.playerRef.getUuid(), "extractions");
                reopen(ref, store, player);
            }
            case "nav_chunks" -> {
                SECTION_BY_PLAYER.put(this.playerRef.getUuid(), "chunks");
                reopen(ref, store, player);
            }
            case "extract_sub_platform" -> {
                EXTRACTION_VARIANT_BY_PLAYER.put(this.playerRef.getUuid(), EXTRACTION_VARIANT_PLATFORM);
                reopen(ref, store, player);
            }
            case "extract_sub_rope" -> {
                EXTRACTION_VARIANT_BY_PLAYER.put(this.playerRef.getUuid(), EXTRACTION_VARIANT_ROPE);
                reopen(ref, store, player);
            }
            case "extract_apply" -> {
                applyExtractionConfig(eventData);
                reopen(ref, store, player);
            }
            case "chunk_range_down" -> {
                shiftChunkBrushRange(-1);
                reopen(ref, store, player);
            }
            case "chunk_range_up" -> {
                shiftChunkBrushRange(1);
                reopen(ref, store, player);
            }
            case "chunk_set_pin" -> {
                applyChunkBrush(store, ChunkBrushMode.PIN);
                reopen(ref, store, player);
            }
            case "chunk_set_marker" -> {
                applyChunkBrush(store, ChunkBrushMode.MARKER);
                reopen(ref, store, player);
            }
            case "chunk_set_clear" -> {
                applyChunkBrush(store, ChunkBrushMode.CLEAR);
                reopen(ref, store, player);
            }
            case "chunk_toggle_visual" -> {
                toggleChunkMapVisual(store);
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
            case "timeline_page_prev" -> {
                shiftTimelinePage(-1);
                reopen(ref, store, player);
            }
            case "timeline_page_next" -> {
                shiftTimelinePage(1);
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
            case "toggle_alerts" -> {
                boolean next = !(GameFlowConfigManager.get().isStatusMessagesEnabled() || GameFlowConfigManager.get().isChunkLoadingMessagesEnabled());
                GameFlowConfigManager.get().setStatusMessagesEnabled(next);
                GameFlowConfigManager.get().setChunkLoadingMessagesEnabled(next);
                sendUiMessage("Alerts: " + (next ? "ON" : "OFF"));
                reopen(ref, store, player);
            }
            case "toggle_dynamic_map" -> {
                boolean next = !GameSessionManager.isDynamicMapRefreshEnabled();
                GameSessionManager.setDynamicMapRefreshEnabled(next);
                sendUiMessage("Dynamic map: " + (next ? "ON" : "OFF"));
                reopen(ref, store, player);
            }
            case "toggle_default_world_height_guard" -> {
                defaultWorldHeightGuardEnabled = !defaultWorldHeightGuardEnabled;
                GameFlowConfigManager.get().setDefaultWorldHeightGuardEnabled(defaultWorldHeightGuardEnabled);
                sendUiMessage("Default world height guard: " + (defaultWorldHeightGuardEnabled ? "ON" : "OFF"));
                reopen(ref, store, player);
            }
            case "toggle_game_world_redirect" -> {
                gameWorldRedirectEnabled = !gameWorldRedirectEnabled;
                GameFlowConfigManager.get().setGameWorldRedirectEnabled(gameWorldRedirectEnabled);
                sendUiMessage("Game world redirect: " + (gameWorldRedirectEnabled ? "ON" : "OFF"));
                reopen(ref, store, player);
            }
            case "toggle_fog_weather" -> {
                boolean next = !RedWoolDamageSystem.isHazardFogEnabled();
                RedWoolDamageSystem.setHazardFogEnabled(next);
                GameFlowConfigManager.get().setHazardFogWeatherEnabled(next);
                sendUiMessage("Hazard fog: " + (next ? "ON" : "OFF"));
                reopen(ref, store, player);
            }
            case "toggle_core_radius_chat" -> {
                boolean next = !GameFlowConfigManager.get().isCoreRadiusChatMessagesEnabled();
                GameFlowConfigManager.get().setCoreRadiusChatMessagesEnabled(next);
                sendUiMessage("Core radius chat: " + (next ? "ON" : "OFF"));
                reopen(ref, store, player);
            }
            case "chunk_reload_file" -> {
                reloadChunkSelectionFromFile();
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
                .append("@MainTriggerPctMin", "#MainTriggerPctMinInput.Value")
                .append("@MainTriggerPctMax", "#MainTriggerPctMaxInput.Value")
                .append("@SeedSpawnDelaySecMin", "#SeedSpawnDelaySecMinInput.Value")
                .append("@SeedSpawnDelaySecMax", "#SeedSpawnDelaySecMaxInput.Value")
                .append("@SeedRadiusAvgPctMin", "#SeedRadiusAvgPctMinInput.Value")
                .append("@SeedRadiusAvgPctMax", "#SeedRadiusAvgPctMaxInput.Value")
                .append("@SeedTargetRadiusMin", "#SeedTargetRadiusMinInput.Value")
                .append("@SeedTargetRadiusMax", "#SeedTargetRadiusMaxInput.Value")
                .append("@ChunkRangePerCore", "#ChunkRangePerCoreInput.Value")
                .append("@MaxActiveSeeds", "#MaxActiveSeedsInput.Value")
                .append("@EventEnabled", "#EventEnabledInput.Value")
                .append("@ActionType", "#ActionTypeInput.Value")
                .append("@CoreTier", "#CoreTierInput.Value")
                .append("@Probability", "#ProbabilityInput.Value");
    }

    @Nonnull
    private static EventData withExtractionEditor(@Nonnull String action) {
        return new EventData()
                .append("Action", action)
                .append("@ExtractRunFromSec", "#ExtractionRunFromInput.Value")
                .append("@ExtractRunUntilSec", "#ExtractionRunUntilInput.Value")
                .append("@ExtractWaitSec", "#ExtractionWaitInput.Value")
                .append("@ExtractWindowSec", "#ExtractionWindowInput.Value")
                .append("@ExtractEnemyWavesEnabled", "#ExtractionEnemyWavesInput.Value")
                .append("@ExtractEnemyMobsPerWave", "#ExtractionEnemyMobsPerWaveInput.Value")
                .append("@ExtractCoreMaxHealth", "#ExtractionCoreMaxHealthInput.Value")
                .append("@ExtractRadiusBlocks", "#ExtractionRadiusInput.Value")
                .append("@ExtractMinHeightOffset", "#ExtractionMinHeightInput.Value")
                .append("@ExtractMaxHeightOffset", "#ExtractionMaxHeightInput.Value")
                .append("@ExtractEnemySpawnMinRadius", "#ExtractionEnemySpawnMinRadiusInput.Value")
                .append("@ExtractEnemySpawnMaxRadius", "#ExtractionEnemySpawnMaxRadiusInput.Value")
                .append("@ExtractEnemySpawnMinHeight", "#ExtractionEnemySpawnMinHeightInput.Value")
                .append("@ExtractEnemySpawnMaxHeight", "#ExtractionEnemySpawnMaxHeightInput.Value");
    }

    @Nonnull
    private static String buildCenterContent(@Nonnull String section, @Nonnull UUID worldId, GameSessionManager.ActiveSessionSnapshot session) {
        return switch (section) {
            case "red" -> buildInfectionManagerContent(worldId);
            case "extractions" -> "EXTRACTIONS\nConfigure extraction variants and interaction coordinates.\nUse subsections on the right panel.";
            case "chunks" -> "RUNCHUNKS TOOLS\nEdit selected chunks from this panel.\nChoose type: PIN / MARKER / CLEAR and set brush range.";
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
        if ("chunks".equals(section)) {
            String templateWorld = GameFlowConfigManager.get().getTemplateWorldName();
            RunChunkSelectionManager manager = RunChunkSelectionManager.get();
            manager.reloadFromConfig(templateWorld);
            int total = manager.count(templateWorld);
            int pinned = manager.countPinned(templateWorld);
            int marker = Math.max(0, total - pinned);
            return "RUNCHUNKS INFO\nTemplate: " + templateWorld
                    + "\nTotal: " + total
                    + "\nPinned: " + pinned
                    + "\nMarker: " + marker;
        }
        if ("extractions".equals(section)) {
            return "EXTRAS\nSection: EXTRACTIONS\nUse right panel to edit run windows,\nwait time, and interaction blocks.";
        }
        return "EXTRAS\nSection: " + formatSectionName(section) + "\nRun phase: "
                + (session == null ? "IDLE" : session.phase().name()) + "\nElapsed: " + elapsedSeconds + "s";
    }

    @Nonnull
    private static String formatSectionName(@Nonnull String section) {
        if ("red".equals(section)) {
            return "INFECTION MANAGER";
        }
        if ("chunks".equals(section)) {
            return "RUNCHUNKS";
        }
        if ("extractions".equals(section)) {
            return "EXTRACTIONS";
        }
        return "run".equals(section) ? "RUN CONTROL" : section.toUpperCase();
    }

    private void applyExtractionSection(
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            boolean visible
    ) {
        ui.set("#ExtractionSection.Visible", visible);
        ui.set("#ExtractionConfigSection.Visible", visible);
        if (!visible) {
            return;
        }
        RunExtractionConfigManager.ExtractionState state = RunExtractionConfigManager.get().getState();
        String selectedVariant = getSelectedExtractionVariantForPlayer(this.playerRef.getUuid());
        RunExtractionConfigManager.VariantState variantState = getVariantState(state, selectedVariant);

        ui.set("#ExtractionSubsectionLabel.Text", "Subsection: " + formatExtractionVariantName(selectedVariant));
        ui.set("#ExtractionPlatformButton.Text", EXTRACTION_VARIANT_PLATFORM.equals(selectedVariant) ? "> Platform rune" : "Platform rune");
        ui.set("#ExtractionRopeButton.Text", EXTRACTION_VARIANT_ROPE.equals(selectedVariant) ? "> Escape rope" : "Escape rope");
        ui.set("#ExtractionRunFromInput.Value", (double) variantState.runEnableFromSecond());
        ui.set("#ExtractionRunUntilInput.Value", (double) variantState.runEnableUntilSecond());
        ui.set("#ExtractionWaitInput.Value", (double) variantState.extractionWaitSeconds());
        ui.set("#ExtractionWindowInput.Value", (double) variantState.extractionWindowSeconds());
        ui.set("#ExtractionEnemyWavesInput.Value", Boolean.toString(variantState.enemyWavesEnabled()));
        ui.set("#ExtractionEnemyMobsPerWaveInput.Value", (double) variantState.enemyMobsPerWave());
        ui.set("#ExtractionCoreMaxHealthInput.Value", (double) variantState.coreMaxHealth());
        ui.set("#ExtractionRadiusInput.Value", variantState.extractionRadiusBlocks());
        ui.set("#ExtractionMinHeightInput.Value", variantState.extractionMinHeightOffset());
        ui.set("#ExtractionMaxHeightInput.Value", variantState.extractionMaxHeightOffset());
        ui.set("#ExtractionEnemySpawnMinRadiusInput.Value", variantState.enemySpawnMinRadiusBlocks());
        ui.set("#ExtractionEnemySpawnMaxRadiusInput.Value", variantState.enemySpawnMaxRadiusBlocks());
        ui.set("#ExtractionEnemySpawnMinHeightInput.Value", variantState.enemySpawnMinHeightOffset());
        ui.set("#ExtractionEnemySpawnMaxHeightInput.Value", variantState.enemySpawnMaxHeightOffset());

        events.addEventBinding(CustomUIEventBindingType.Activating, "#ExtractionPlatformButton", EventData.of("Action", "extract_sub_platform"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ExtractionRopeButton", EventData.of("Action", "extract_sub_rope"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ExtractionApplyButton", withExtractionEditor("extract_apply"), false);
    }

    @Nonnull
    private String getSelectedExtractionVariantForPlayer(@Nullable UUID playerId) {
        UUID effectivePlayerId = playerId == null ? this.playerRef.getUuid() : playerId;
        return EXTRACTION_VARIANT_BY_PLAYER.getOrDefault(effectivePlayerId, EXTRACTION_VARIANT_PLATFORM);
    }

    @Nonnull
    private static String formatExtractionVariantName(@Nonnull String variant) {
        if (EXTRACTION_VARIANT_ROPE.equals(variant)) {
            return "Escape rope";
        }
        return "Platform rune";
    }

    public static boolean isDefaultWorldHeightGuardEnabled() {
        return defaultWorldHeightGuardEnabled;
    }

    public static boolean isGameWorldRedirectEnabled() {
        return gameWorldRedirectEnabled;
    }

    @Nonnull
    private static RunExtractionConfigManager.VariantState getVariantState(
            @Nonnull RunExtractionConfigManager.ExtractionState state,
            @Nonnull String variant
    ) {
        return EXTRACTION_VARIANT_ROPE.equals(variant) ? state.escapeRope() : state.platformRune();
    }

    private void applyExtractionConfig(@Nonnull PageEventData eventData) {
        String selectedVariant = getSelectedExtractionVariantForPlayer(this.playerRef.getUuid());
        int fromSecond = eventData.extractRunFromSec == null ? 0 : Math.max(0, (int) Math.round(eventData.extractRunFromSec));
        int untilSecondBase = eventData.extractRunUntilSec == null ? fromSecond : (int) Math.round(eventData.extractRunUntilSec);
        int untilSecond = Math.max(fromSecond, untilSecondBase);
        int waitSeconds = eventData.extractWaitSec == null ? 20 : Math.max(10, (int) Math.round(eventData.extractWaitSec));
        int windowSeconds = eventData.extractWindowSec == null ? 1 : Math.max(1, (int) Math.round(eventData.extractWindowSec));
        boolean enemyWavesEnabled = eventData.extractEnemyWavesEnabled != null
                && Boolean.parseBoolean(eventData.extractEnemyWavesEnabled.trim());
        int enemyMobsPerWave = eventData.extractEnemyMobsPerWave == null ? 2 : Math.max(0, (int) Math.round(eventData.extractEnemyMobsPerWave));
        float coreMaxHealth = eventData.extractCoreMaxHealth == null ? 300.0f : Math.max(1.0f, eventData.extractCoreMaxHealth.floatValue());
        double extractionRadius = eventData.extractRadiusBlocks == null ? 6.0d : Math.max(0.25d, eventData.extractRadiusBlocks);
        double minHeight = eventData.extractMinHeightOffset == null ? -1.0d : eventData.extractMinHeightOffset;
        double maxHeight = eventData.extractMaxHeightOffset == null ? 3.0d : eventData.extractMaxHeightOffset;
        double enemySpawnMinRadius = eventData.extractEnemySpawnMinRadius == null ? 7.0d : Math.max(0.5d, eventData.extractEnemySpawnMinRadius);
        double enemySpawnMaxRadius = eventData.extractEnemySpawnMaxRadius == null ? 16.0d : Math.max(enemySpawnMinRadius, eventData.extractEnemySpawnMaxRadius);
        double enemySpawnMinHeight = eventData.extractEnemySpawnMinHeight == null ? -8.0d : eventData.extractEnemySpawnMinHeight;
        double enemySpawnMaxHeight = eventData.extractEnemySpawnMaxHeight == null ? 4.0d : eventData.extractEnemySpawnMaxHeight;

        RunExtractionConfigManager.VariantState newState = new RunExtractionConfigManager.VariantState(
                fromSecond,
                untilSecond,
                waitSeconds,
                windowSeconds,
                enemyWavesEnabled,
                enemyMobsPerWave,
                coreMaxHealth,
                extractionRadius,
                minHeight,
                maxHeight,
                enemySpawnMinRadius,
                enemySpawnMaxRadius,
                enemySpawnMinHeight,
                enemySpawnMaxHeight
        );
        RunExtractionConfigManager.VariantKey key = EXTRACTION_VARIANT_ROPE.equals(selectedVariant)
                ? RunExtractionConfigManager.VariantKey.ESCAPE_ROPE
                : RunExtractionConfigManager.VariantKey.PLATFORM_RUNE;
        RunExtractionConfigManager.get().saveVariant(key, newState);
        sendUiMessage("Extraction config saved for " + formatExtractionVariantName(selectedVariant) + ".");
    }

    private void shiftChunkBrushRange(int direction) {
        UUID playerId = this.playerRef.getUuid();
        int current = CHUNK_BRUSH_RANGE_BY_PLAYER.getOrDefault(playerId, 0);
        int next = Math.max(0, Math.min(5, current + (direction < 0 ? -1 : 1)));
        CHUNK_BRUSH_RANGE_BY_PLAYER.put(playerId, next);
        sendUiMessage("Run chunk brush range: " + next);
    }

    private void applyChunkBrush(@Nonnull Store<EntityStore> store, @Nonnull ChunkBrushMode mode) {
        World world = store.getExternalData().getWorld();
        if (world == null || this.playerRef.getTransform() == null || this.playerRef.getTransform().getPosition() == null) {
            return;
        }
        String worldName = world.getName();
        RunChunkSelectionManager manager = RunChunkSelectionManager.get();

        int centerChunkX = ChunkUtil.chunkCoordinate((int) Math.floor(this.playerRef.getTransform().getPosition().getX()));
        int centerChunkZ = ChunkUtil.chunkCoordinate((int) Math.floor(this.playerRef.getTransform().getPosition().getZ()));
        int range = Math.max(0, Math.min(5, CHUNK_BRUSH_RANGE_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), 0)));
        int changed = 0;
        for (int dz = -range; dz <= range; dz++) {
            for (int dx = -range; dx <= range; dx++) {
                int targetChunkX = centerChunkX + dx;
                int targetChunkZ = centerChunkZ + dz;
                boolean didChange = switch (mode) {
                    case PIN -> manager.markPinned(worldName, targetChunkX, targetChunkZ);
                    case MARKER -> {
                        boolean marked = manager.mark(worldName, targetChunkX, targetChunkZ);
                        boolean unpinned = manager.setPinned(worldName, targetChunkX, targetChunkZ, false);
                        yield marked || unpinned;
                    }
                    case CLEAR -> manager.unmark(worldName, targetChunkX, targetChunkZ);
                };
                if (didChange) {
                    changed++;
                }
                manager.queueMapRefresh(worldName, targetChunkX, targetChunkZ);
            }
        }
        sendUiMessage("Runchunks " + mode.label + ": " + changed + " chunks.");
    }

    private void toggleChunkMapVisual(@Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        String worldName = world.getName();
        RunChunkSelectionManager manager = RunChunkSelectionManager.get();
        if (manager.isEnabled(this.playerRef)) {
            manager.disableFor(this.playerRef);
            refreshPlayerMapNow(world, this.playerRef, manager.getSelectedChunks(worldName));
            sendUiMessage("Runchunk map visual: OFF");
            return;
        }
        manager.enableFor(this.playerRef);
        manager.queueMapRefreshForChunks(worldName, manager.getSelectedChunks(worldName));
        refreshPlayerMapNow(world, this.playerRef, manager.getSelectedChunks(worldName));
        sendUiMessage("Runchunk map visual: ON");
    }

    private static void refreshPlayerMapNow(
            @Nonnull World world,
            @Nonnull PlayerRef playerRef,
            @Nonnull java.util.Set<RunChunkSelectionManager.ChunkPosKey> chunks
    ) {
        if (chunks.isEmpty()) {
            return;
        }
        it.unimi.dsi.fastutil.longs.LongSet indices = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunks.size());
        for (RunChunkSelectionManager.ChunkPosKey chunk : chunks) {
            indices.add(ChunkUtil.indexChunk(chunk.x(), chunk.z()));
        }
        if (world.getWorldMapManager() != null) {
            world.getWorldMapManager().clearImagesInChunks(indices);
        }
        Player player = world.getEntityStore().getStore().getComponent(playerRef.getReference(), Player.getComponentType());
        if (player != null && player.getWorldMapTracker() != null) {
            player.getWorldMapTracker().clearChunks(indices);
        }
    }

    private void reloadChunkSelectionFromFile() {
        String templateWorld = GameFlowConfigManager.get().getTemplateWorldName();
        RunChunkSelectionManager.get().reloadFromConfig(templateWorld);
        sendUiMessage("Run chunk file reloaded for: " + templateWorld);
    }

    private enum ChunkBrushMode {
        PIN("pin"),
        MARKER("marker"),
        CLEAR("clear");

        private final String label;

        ChunkBrushMode(String label) {
            this.label = label;
        }
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
        int pageStart = getTimelinePageStart(timeline.size());
        int pageCount = Math.max(1, (int) Math.ceil(timeline.size() / (double) MAX_TIMELINE_EVENTS));
        int currentPage = (pageStart / MAX_TIMELINE_EVENTS) + 1;
        ui.set("#TimelinePageLabel.Text", "Page " + currentPage + "/" + pageCount);
        ui.set("#TimeLinePagePrevButton.Visible", pageCount > 1);
        ui.set("#TimeLinePageNextButton.Visible", pageCount > 1);
        for (int i = 0; i < MAX_TIMELINE_EVENTS; i++) {
            String buttonId = "#TimelineEventButton" + (i + 1);
            int timelineIndex = pageStart + i;
            boolean buttonVisible = timelineIndex < timeline.size();
            ui.set(buttonId + ".Visible", buttonVisible);
            if (buttonVisible) {
                TimelineEvent event = timeline.get(timelineIndex);
                String selectedMarker = timelineIndex == selectedIndex ? "* " : "";
                ui.set(buttonId + ".Text", selectedMarker + event.actionType.toUpperCase() + "/" + event.coreTier.toUpperCase() + " @" + event.triggerSecond + "s p=" + event.probabilityPercent + "%");
                events.addEventBinding(CustomUIEventBindingType.Activating, buttonId, EventData.of("Action", "timeline_select_" + timelineIndex), false);
            }
        }

        ui.set("#ActionSelectorLabel.Text", selected == null ? "No action" : "Action " + (selectedIndex + 1) + "/" + timeline.size() + " - " + selectedActionType.toUpperCase());
        ui.set("#EventEditorTitle.Text", selected == null ? "No action selected" : "Configure action #" + (selectedIndex + 1));
        ui.set("#EventTriggerSecondInput.Value", selected == null ? 30d : (double) selected.triggerSecond);
        ui.set("#EventCoreCountInput.Value", selected == null ? 6d : (double) selected.coreCount);
        ui.set("#EventCoreTypeInput.Value", selected == null ? "2" : String.valueOf(selected.ticksPerBlock));
        String mainTriggerRange = selected == null ? "0.70-0.70" : selected.mainTriggerPctRange;
        String seedDelayRange = selected == null ? "2.0-2.0" : selected.seedSpawnDelaySecRange;
        String seedRadiusAvgRange = selected == null ? "0.90-0.90" : selected.seedRadiusAvgTriggerPctRange;
        String seedRadiusRange = selected == null ? "120-120" : selected.seedTargetRadiusRange;
        ui.set("#MainTriggerPctMinInput.Value", doubleNumericText(rangePart(mainTriggerRange, true)));
        ui.set("#MainTriggerPctMaxInput.Value", doubleNumericText(rangePart(mainTriggerRange, false)));
        ui.set("#SeedSpawnDelaySecMinInput.Value", rangePart(seedDelayRange, true));
        ui.set("#SeedSpawnDelaySecMaxInput.Value", rangePart(seedDelayRange, false));
        ui.set("#SeedRadiusAvgPctMinInput.Value", doubleNumericText(rangePart(seedRadiusAvgRange, true)));
        ui.set("#SeedRadiusAvgPctMaxInput.Value", doubleNumericText(rangePart(seedRadiusAvgRange, false)));
        ui.set("#SeedTargetRadiusMinInput.Value", rangePart(seedRadiusRange, true));
        ui.set("#SeedTargetRadiusMaxInput.Value", rangePart(seedRadiusRange, false));
        ui.set("#ChunkRangePerCoreInput.Value", selected == null ? "1" : String.valueOf(selected.chunkRangePerCore));
        ui.set("#MaxActiveSeedsInput.Value", selected == null ? "4" : String.valueOf(selected.maxActiveSeeds));
        ui.set("#EventEnabledInput.Value", selected == null ? "true" : String.valueOf(selected.enabled));
        ui.set("#ActionTypeSelectorValue.Text", selectedActionType.toUpperCase());
        ui.set("#ActionTypeInput.Value", selectedActionType);
        ui.set("#CoreTierSelectorValue.Text", selectedCoreTier.toUpperCase());
        ui.set("#CoreTierInput.Value", selectedCoreTier);
        ui.set("#ProbabilityInput.Value", selected == null ? 100d : (double) selected.probabilityPercent);
        boolean seededOptionsVisible = "seeded_grow".equalsIgnoreCase(selectedActionType);
        ui.set("#SeededMainTriggerLabel.Visible", seededOptionsVisible);
        ui.set("#MainTriggerPctMinInput.Visible", seededOptionsVisible);
        ui.set("#MainTriggerPctMaxInput.Visible", seededOptionsVisible);
        ui.set("#SeededDelayLabel.Visible", seededOptionsVisible);
        ui.set("#SeedSpawnDelaySecMinInput.Visible", seededOptionsVisible);
        ui.set("#SeedSpawnDelaySecMaxInput.Visible", seededOptionsVisible);
        ui.set("#SeededRadiusAvgLabel.Visible", seededOptionsVisible);
        ui.set("#SeedRadiusAvgPctMinInput.Visible", seededOptionsVisible);
        ui.set("#SeedRadiusAvgPctMaxInput.Visible", seededOptionsVisible);
        ui.set("#SeededTargetRadiusLabel.Visible", seededOptionsVisible);
        ui.set("#SeedTargetRadiusMinInput.Visible", seededOptionsVisible);
        ui.set("#SeedTargetRadiusMaxInput.Visible", seededOptionsVisible);
        ui.set("#SeededChunkRangeLabel.Visible", seededOptionsVisible);
        ui.set("#ChunkRangePerCoreInput.Visible", seededOptionsVisible);
        ui.set("#SeededMaxActiveLabel.Visible", seededOptionsVisible);
        ui.set("#MaxActiveSeedsInput.Visible", seededOptionsVisible);
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

    @Nonnull
    private static String rangePart(@Nonnull String rangeText, boolean minPart) {
        String[] split = rangeText.split("[-:,]");
        if (split.length >= 2) {
            return (minPart ? split[0] : split[1]).trim();
        }
        return rangeText.trim();
    }

    @Nonnull
    private static String halveNumericText(@Nonnull String text) {
        if (text.isBlank()) {
            return text;
        }
        try {
            BigDecimal value = new BigDecimal(text.trim());
            return value
                    .divide(BigDecimal.valueOf(2))
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException ignored) {
            return text.trim();
        }
    }

    @Nonnull
    private static String doubleNumericText(@Nonnull String text) {
        if (text.isBlank()) {
            return text;
        }
        try {
            BigDecimal value = new BigDecimal(text.trim());
            return value
                    .multiply(BigDecimal.valueOf(2))
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException ignored) {
            return text.trim();
        }
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
        timeline.add(defaultAction("spawn"));
        SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), timeline.size() - 1);
        alignTimelinePageToSelection(timeline.size());
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
        alignTimelinePageToSelection(timeline.size());
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
        alignTimelinePageToSelection(timeline.size());
        clearPendingEditorSelection();
    }


    private void cycleSelectedActionType(int direction) {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        int selectedIndex = getSelectedTimelineIndex(timeline.size());
        if (selectedIndex < 0) {
            return;
        }
        String[] options = {"spawn", "grow", "seeded_grow"};
        String currentValue = getCurrentActionTypeForSelection(timeline, selectedIndex);
        int current = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(currentValue)) {
                current = i;
                break;
            }
        }
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
            alignTimelinePageToSelection(timeline.size());
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
            if ("spawn".equals(normalizedAction) || "grow".equals(normalizedAction) || "seeded_grow".equals(normalizedAction)) {
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
        if (!eventData.mainTriggerPctMin.isBlank() || !eventData.mainTriggerPctMax.isBlank()) {
            String min = eventData.mainTriggerPctMin.isBlank()
                    ? rangePart(event.mainTriggerPctRange, true)
                    : halveNumericText(eventData.mainTriggerPctMin.trim());
            String max = eventData.mainTriggerPctMax.isBlank()
                    ? rangePart(event.mainTriggerPctRange, false)
                    : halveNumericText(eventData.mainTriggerPctMax.trim());
            event.mainTriggerPctRange = min + "-" + max;
        }
        if (!eventData.seedSpawnDelaySecMin.isBlank() || !eventData.seedSpawnDelaySecMax.isBlank()) {
            String min = eventData.seedSpawnDelaySecMin.isBlank() ? rangePart(event.seedSpawnDelaySecRange, true) : eventData.seedSpawnDelaySecMin.trim();
            String max = eventData.seedSpawnDelaySecMax.isBlank() ? rangePart(event.seedSpawnDelaySecRange, false) : eventData.seedSpawnDelaySecMax.trim();
            event.seedSpawnDelaySecRange = min + "-" + max;
        }
        if (!eventData.seedRadiusAvgPctMin.isBlank() || !eventData.seedRadiusAvgPctMax.isBlank()) {
            String min = eventData.seedRadiusAvgPctMin.isBlank()
                    ? rangePart(event.seedRadiusAvgTriggerPctRange, true)
                    : halveNumericText(eventData.seedRadiusAvgPctMin.trim());
            String max = eventData.seedRadiusAvgPctMax.isBlank()
                    ? rangePart(event.seedRadiusAvgTriggerPctRange, false)
                    : halveNumericText(eventData.seedRadiusAvgPctMax.trim());
            event.seedRadiusAvgTriggerPctRange = min + "-" + max;
        }
        if (!eventData.seedTargetRadiusMin.isBlank() || !eventData.seedTargetRadiusMax.isBlank()) {
            String min = eventData.seedTargetRadiusMin.isBlank() ? rangePart(event.seedTargetRadiusRange, true) : eventData.seedTargetRadiusMin.trim();
            String max = eventData.seedTargetRadiusMax.isBlank() ? rangePart(event.seedTargetRadiusRange, false) : eventData.seedTargetRadiusMax.trim();
            event.seedTargetRadiusRange = min + "-" + max;
        }
        if (!eventData.chunkRangePerCore.isBlank()) {
            try {
                event.chunkRangePerCore = Math.max(0, Integer.parseInt(eventData.chunkRangePerCore.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!eventData.maxActiveSeeds.isBlank()) {
            try {
                event.maxActiveSeeds = Math.max(1, Integer.parseInt(eventData.maxActiveSeeds.trim()));
            } catch (NumberFormatException ignored) {
            }
        }

        persistActionsForPlayer();
        sendUiMessage("Action #" + (selectedIndex + 1) + " updated.");
    }


    @Nonnull
    private static TimelineEvent defaultAction(@Nonnull String actionType) {
        if ("grow".equalsIgnoreCase(actionType)) {
            return new TimelineEvent("grow", "core", 60, 12, 1, 100, true, "0.70-0.70", "2.0-2.0", "0.90-0.90", "120-120", 1, 4);
        }
        if ("seeded_grow".equalsIgnoreCase(actionType)) {
            return new TimelineEvent("seeded_grow", "core", 60, 50, 1, 100, true, "0.70-0.70", "2.0-4.0", "0.90-0.90", "100-140", 1, 4);
        }
        return new TimelineEvent("spawn", "core", 30, 6, 2, 100, true, "0.70-0.70", "2.0-2.0", "0.90-0.90", "120-120", 1, 4);
    }

    private List<TimelineEvent> getTimelineForPlayer() {
        String worldName = resolvePlayerWorldName();
        String loadedWorld = LOADED_ACTION_WORLD_BY_PLAYER.get(this.playerRef.getUuid());
        if (loadedWorld == null || !loadedWorld.equalsIgnoreCase(worldName)) {
            ArrayList<TimelineEvent> loaded = new ArrayList<>();
            for (InfectionActionConfigManager.ActionEntry entry : InfectionActionConfigManager.loadActions(worldName)) {
                loaded.add(new TimelineEvent(
                        entry.actionType(),
                        entry.coreTier(),
                        entry.triggerSecond(),
                        entry.radius(),
                        entry.ticksPerBlock(),
                        entry.probabilityPercent(),
                        entry.enabled(),
                        entry.mainTriggerPctRange(),
                        entry.seedSpawnDelaySecRange(),
                        entry.seedRadiusAvgTriggerPctRange(),
                        entry.seedTargetRadiusRange(),
                        entry.chunkRangePerCore(),
                        entry.maxActiveSeeds()
                ));
            }
            if (loaded.isEmpty()) {
                loaded.add(defaultAction("spawn"));
                InfectionActionConfigManager.saveActions(worldName, List.of(new InfectionActionConfigManager.ActionEntry(
                        "spawn",
                        "core",
                        30,
                        6,
                        2,
                        100,
                        true,
                        "0.70-0.70",
                        "2.0-2.0",
                        "0.90-0.90",
                        "120-120",
                        1,
                        4
                )));
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
            entries.add(new InfectionActionConfigManager.ActionEntry(
                    event.actionType,
                    event.coreTier,
                    event.triggerSecond,
                    event.coreCount,
                    event.ticksPerBlock,
                    event.probabilityPercent,
                    event.enabled,
                    event.mainTriggerPctRange,
                    event.seedSpawnDelaySecRange,
                    event.seedRadiusAvgTriggerPctRange,
                    event.seedTargetRadiusRange,
                    event.chunkRangePerCore,
                    event.maxActiveSeeds
            ));
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

    private int getTimelinePageStart(int timelineSize) {
        if (timelineSize <= 0) {
            TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), 0);
            return 0;
        }
        int maxStart = Math.max(0, ((timelineSize - 1) / MAX_TIMELINE_EVENTS) * MAX_TIMELINE_EVENTS);
        int start = TIMELINE_PAGE_START_BY_PLAYER.getOrDefault(this.playerRef.getUuid(), 0);
        if (start < 0 || start > maxStart || start % MAX_TIMELINE_EVENTS != 0) {
            start = 0;
        }
        int selected = getSelectedTimelineIndex(timelineSize);
        if (selected >= 0 && (selected < start || selected >= start + MAX_TIMELINE_EVENTS)) {
            start = (selected / MAX_TIMELINE_EVENTS) * MAX_TIMELINE_EVENTS;
        }
        TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), start);
        return start;
    }

    private void shiftTimelinePage(int direction) {
        List<TimelineEvent> timeline = getTimelineForPlayer();
        if (timeline.isEmpty()) {
            TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), 0);
            return;
        }
        int pageCount = Math.max(1, (int) Math.ceil(timeline.size() / (double) MAX_TIMELINE_EVENTS));
        int currentPage = getTimelinePageStart(timeline.size()) / MAX_TIMELINE_EVENTS;
        int nextPage = (currentPage + direction) % pageCount;
        if (nextPage < 0) {
            nextPage += pageCount;
        }
        int newStart = nextPage * MAX_TIMELINE_EVENTS;
        TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), newStart);
        int selected = getSelectedTimelineIndex(timeline.size());
        if (selected < newStart || selected >= newStart + MAX_TIMELINE_EVENTS) {
            SELECTED_TIMELINE_EVENT_BY_PLAYER.put(this.playerRef.getUuid(), Math.min(newStart, timeline.size() - 1));
            clearPendingEditorSelection();
        }
    }

    private void alignTimelinePageToSelection(int timelineSize) {
        if (timelineSize <= 0) {
            TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), 0);
            return;
        }
        int selected = getSelectedTimelineIndex(timelineSize);
        if (selected < 0) {
            TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), 0);
            return;
        }
        TIMELINE_PAGE_START_BY_PLAYER.put(this.playerRef.getUuid(), (selected / MAX_TIMELINE_EVENTS) * MAX_TIMELINE_EVENTS);
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
        @Nonnull
        private String mainTriggerPctRange;
        @Nonnull
        private String seedSpawnDelaySecRange;
        @Nonnull
        private String seedRadiusAvgTriggerPctRange;
        @Nonnull
        private String seedTargetRadiusRange;
        private int chunkRangePerCore;
        private int maxActiveSeeds;

        private TimelineEvent(
                @Nonnull String actionType,
                @Nonnull String coreTier,
                int triggerSecond,
                int coreCount,
                int ticksPerBlock,
                int probabilityPercent,
                boolean enabled,
                @Nonnull String mainTriggerPctRange,
                @Nonnull String seedSpawnDelaySecRange,
                @Nonnull String seedRadiusAvgTriggerPctRange,
                @Nonnull String seedTargetRadiusRange,
                int chunkRangePerCore,
                int maxActiveSeeds
        ) {
            this.actionType = actionType;
            this.coreTier = coreTier;
            this.triggerSecond = triggerSecond;
            this.coreCount = coreCount;
            this.ticksPerBlock = ticksPerBlock;
            this.probabilityPercent = probabilityPercent;
            this.enabled = enabled;
            this.mainTriggerPctRange = mainTriggerPctRange;
            this.seedSpawnDelaySecRange = seedSpawnDelaySecRange;
            this.seedRadiusAvgTriggerPctRange = seedRadiusAvgTriggerPctRange;
            this.seedTargetRadiusRange = seedTargetRadiusRange;
            this.chunkRangePerCore = chunkRangePerCore;
            this.maxActiveSeeds = maxActiveSeeds;
        }
    }


    public static List<RuntimeAction> snapshotRuntimeActions(@Nonnull UUID playerId) {
        ArrayList<TimelineEvent> events = TIMELINE_EVENTS_BY_PLAYER.get(playerId);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        ArrayList<RuntimeAction> copy = new ArrayList<>(events.size());
        for (TimelineEvent e : events) {
            copy.add(new RuntimeAction(
                    e.actionType,
                    e.coreTier,
                    e.triggerSecond,
                    e.coreCount,
                    e.ticksPerBlock,
                    e.probabilityPercent,
                    e.enabled,
                    e.mainTriggerPctRange,
                    e.seedSpawnDelaySecRange,
                    e.seedRadiusAvgTriggerPctRange,
                    e.seedTargetRadiusRange,
                    e.chunkRangePerCore,
                    e.maxActiveSeeds
            ));
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
            boolean enabled,
            @Nonnull String mainTriggerPctRange,
            @Nonnull String seedSpawnDelaySecRange,
            @Nonnull String seedRadiusAvgTriggerPctRange,
            @Nonnull String seedTargetRadiusRange,
            int chunkRangePerCore,
            int maxActiveSeeds
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
        @Nonnull
        public String mainTriggerPctMin = "";
        @Nonnull
        public String mainTriggerPctMax = "";
        @Nonnull
        public String seedSpawnDelaySecMin = "";
        @Nonnull
        public String seedSpawnDelaySecMax = "";
        @Nonnull
        public String seedRadiusAvgPctMin = "";
        @Nonnull
        public String seedRadiusAvgPctMax = "";
        @Nonnull
        public String seedTargetRadiusMin = "";
        @Nonnull
        public String seedTargetRadiusMax = "";
        @Nonnull
        public String chunkRangePerCore = "";
        @Nonnull
        public String maxActiveSeeds = "";
        @Nullable
        public Double extractRunFromSec;
        @Nullable
        public Double extractRunUntilSec;
        @Nullable
        public Double extractWaitSec;
        @Nullable
        public Double extractWindowSec;
        @Nonnull
        public String extractEnemyWavesEnabled = "";
        @Nullable
        public Double extractEnemyMobsPerWave;
        @Nullable
        public Double extractCoreMaxHealth;
        @Nullable
        public Double extractRadiusBlocks;
        @Nullable
        public Double extractMinHeightOffset;
        @Nullable
        public Double extractMaxHeightOffset;
        @Nullable
        public Double extractEnemySpawnMinRadius;
        @Nullable
        public Double extractEnemySpawnMaxRadius;
        @Nullable
        public Double extractEnemySpawnMinHeight;
        @Nullable
        public Double extractEnemySpawnMaxHeight;

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
                .append(new KeyedCodec<>("@MainTriggerPctMin", Codec.STRING), (d, v) -> d.mainTriggerPctMin = v, d -> d.mainTriggerPctMin)
                .add()
                .append(new KeyedCodec<>("@MainTriggerPctMax", Codec.STRING), (d, v) -> d.mainTriggerPctMax = v, d -> d.mainTriggerPctMax)
                .add()
                .append(new KeyedCodec<>("@SeedSpawnDelaySecMin", Codec.STRING), (d, v) -> d.seedSpawnDelaySecMin = v, d -> d.seedSpawnDelaySecMin)
                .add()
                .append(new KeyedCodec<>("@SeedSpawnDelaySecMax", Codec.STRING), (d, v) -> d.seedSpawnDelaySecMax = v, d -> d.seedSpawnDelaySecMax)
                .add()
                .append(new KeyedCodec<>("@SeedRadiusAvgPctMin", Codec.STRING), (d, v) -> d.seedRadiusAvgPctMin = v, d -> d.seedRadiusAvgPctMin)
                .add()
                .append(new KeyedCodec<>("@SeedRadiusAvgPctMax", Codec.STRING), (d, v) -> d.seedRadiusAvgPctMax = v, d -> d.seedRadiusAvgPctMax)
                .add()
                .append(new KeyedCodec<>("@SeedTargetRadiusMin", Codec.STRING), (d, v) -> d.seedTargetRadiusMin = v, d -> d.seedTargetRadiusMin)
                .add()
                .append(new KeyedCodec<>("@SeedTargetRadiusMax", Codec.STRING), (d, v) -> d.seedTargetRadiusMax = v, d -> d.seedTargetRadiusMax)
                .add()
                .append(new KeyedCodec<>("@ChunkRangePerCore", Codec.STRING), (d, v) -> d.chunkRangePerCore = v, d -> d.chunkRangePerCore)
                .add()
                .append(new KeyedCodec<>("@MaxActiveSeeds", Codec.STRING), (d, v) -> d.maxActiveSeeds = v, d -> d.maxActiveSeeds)
                .add()
                .append(new KeyedCodec<>("@ExtractRunFromSec", Codec.DOUBLE), (d, v) -> d.extractRunFromSec = v, d -> d.extractRunFromSec)
                .add()
                .append(new KeyedCodec<>("@ExtractRunUntilSec", Codec.DOUBLE), (d, v) -> d.extractRunUntilSec = v, d -> d.extractRunUntilSec)
                .add()
                .append(new KeyedCodec<>("@ExtractWaitSec", Codec.DOUBLE), (d, v) -> d.extractWaitSec = v, d -> d.extractWaitSec)
                .add()
                .append(new KeyedCodec<>("@ExtractWindowSec", Codec.DOUBLE), (d, v) -> d.extractWindowSec = v, d -> d.extractWindowSec)
                .add()
                .append(new KeyedCodec<>("@ExtractEnemyWavesEnabled", Codec.STRING), (d, v) -> d.extractEnemyWavesEnabled = v, d -> d.extractEnemyWavesEnabled)
                .add()
                .append(new KeyedCodec<>("@ExtractEnemyMobsPerWave", Codec.DOUBLE), (d, v) -> d.extractEnemyMobsPerWave = v, d -> d.extractEnemyMobsPerWave)
                .add()
                .append(new KeyedCodec<>("@ExtractCoreMaxHealth", Codec.DOUBLE), (d, v) -> d.extractCoreMaxHealth = v, d -> d.extractCoreMaxHealth)
                .add()
                .append(new KeyedCodec<>("@ExtractRadiusBlocks", Codec.DOUBLE), (d, v) -> d.extractRadiusBlocks = v, d -> d.extractRadiusBlocks)
                .add()
                .append(new KeyedCodec<>("@ExtractMinHeightOffset", Codec.DOUBLE), (d, v) -> d.extractMinHeightOffset = v, d -> d.extractMinHeightOffset)
                .add()
                .append(new KeyedCodec<>("@ExtractMaxHeightOffset", Codec.DOUBLE), (d, v) -> d.extractMaxHeightOffset = v, d -> d.extractMaxHeightOffset)
                .add()
                .append(new KeyedCodec<>("@ExtractEnemySpawnMinRadius", Codec.DOUBLE), (d, v) -> d.extractEnemySpawnMinRadius = v, d -> d.extractEnemySpawnMinRadius)
                .add()
                .append(new KeyedCodec<>("@ExtractEnemySpawnMaxRadius", Codec.DOUBLE), (d, v) -> d.extractEnemySpawnMaxRadius = v, d -> d.extractEnemySpawnMaxRadius)
                .add()
                .append(new KeyedCodec<>("@ExtractEnemySpawnMinHeight", Codec.DOUBLE), (d, v) -> d.extractEnemySpawnMinHeight = v, d -> d.extractEnemySpawnMinHeight)
                .add()
                .append(new KeyedCodec<>("@ExtractEnemySpawnMaxHeight", Codec.DOUBLE), (d, v) -> d.extractEnemySpawnMaxHeight = v, d -> d.extractEnemySpawnMaxHeight)
                .add()
                .build();
    }
}