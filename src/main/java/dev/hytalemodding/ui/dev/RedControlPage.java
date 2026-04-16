package dev.hytalemodding.ui.dev;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.state.transition.CrimsonCoreConfigManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RedControlPage extends InteractiveCustomUIPage<RedControlPage.PageEventData> {
    private static final int DEFAULT_UI_RADIUS = RedWaveConfig.DEFAULT_UI_RADIUS_BLOCKS;
    private static final float DEFAULT_UI_START_SECONDS = RedWaveConfig.DEFAULT_UI_START_SECONDS;
    private static final float MIN_UI_START_SECONDS = 1.0f;
    private static final float MAX_UI_START_SECONDS = 300.0f;
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Integer>> SELECTED_INDEX_BY_PLAYER_WORLD = new ConcurrentHashMap<>();

    public RedControlPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageEventData.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store
    ) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        UiState state = this.loadState(world);
        this.syncRuntimeRegistry(worldId, state);
        this.applySelectedCoreToManager(state, worldId);

        ui.append("d97's/Pages/RedControlPage.ui");
        ui.set("#ActiveCoreLabel.Text", this.buildActiveCoreText(state));
        ui.set("#CandidateSummaryLabel.Text", "Saved candidates: " + state.profiles.size() + " | Starts per run: " + state.chooseCount);
        ui.set("#RadiusValueLabel.Text", "Global Radius: " + state.globalRadius);
        ui.set("#StartValueLabel.Text", "Global Spread Seconds: " + this.formatSeconds(state.globalSpreadSeconds));
        ui.set("#ChooseCountValueLabel.Text", "Global Active Core Count: " + state.chooseCount);
        ui.set("#PrevCoreButtonLabel.Text", this.buildPrevCoreButtonText(state));
        ui.set("#NextCoreButtonLabel.Text", this.buildNextCoreButtonText(state));
        ui.set("#RadiusInput.Value", (double) state.globalRadius);
        ui.set("#StartInput.Value", (double) state.globalSpreadSeconds);
        ui.set("#ChooseCountInput.Value", (double) state.chooseCount);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevCoreButton", EventData.of("Action", "prevcore"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextCoreButton", EventData.of("Action", "nextcore"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetCoreButton", EventData.of("Action", "setcore"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveCoreButton", EventData.of("Action", "removecore"), false);

        EventData updateRadius = new EventData().append("Action", "UpdateGlobals").append("@Radius", "#RadiusInput.Value");
        EventData updateSpread = new EventData().append("Action", "UpdateGlobals").append("@SpreadSeconds", "#StartInput.Value");
        EventData updateCount = new EventData().append("Action", "UpdateGlobals").append("@ChooseCount", "#ChooseCountInput.Value");
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#RadiusInput", updateRadius, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#RadiusInput", updateRadius, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#RadiusInput", updateRadius, false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#StartInput", updateSpread, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#StartInput", updateSpread, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#StartInput", updateSpread, false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ChooseCountInput", updateCount, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#ChooseCountInput", updateCount, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#ChooseCountInput", updateCount, false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartWaveButton", EventData.of("Action", "startwave"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#UndoButton", EventData.of("Action", "undo"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#GlobalUndoButton", EventData.of("Action", "globalundo"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEventData eventData
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        if ("close".equalsIgnoreCase(eventData.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        UiState state = this.loadState(world);
        this.syncRuntimeRegistry(worldId, state);

        if ("UpdateGlobals".equalsIgnoreCase(eventData.action)) {
            boolean changed = false;
            if (eventData.radius != null) {
                int normalizedRadius = Math.max(RedWaveConfig.MIN_RADIUS_BLOCKS, Math.min(RedWaveConfig.MAX_RADIUS_BLOCKS, (int) Math.round(eventData.radius)));
                if (normalizedRadius != state.globalRadius) {
                    state.globalRadius = normalizedRadius;
                    changed = true;
                }
            }
            if (eventData.spreadSeconds != null) {
                float normalizedSpread = Math.max(MIN_UI_START_SECONDS, Math.min(MAX_UI_START_SECONDS, eventData.spreadSeconds.floatValue()));
                if (Math.abs(normalizedSpread - state.globalSpreadSeconds) > 0.001f) {
                    state.globalSpreadSeconds = normalizedSpread;
                    changed = true;
                }
            }
            if (eventData.chooseCount != null) {
                int normalizedCount = normalizeChooseCount((int) Math.round(eventData.chooseCount), state.profiles.size());
                if (normalizedCount != state.chooseCount) {
                    state.chooseCount = normalizedCount;
                    changed = true;
                }
            }
            if (changed) {
                state.rebuildProfilesWithGlobals();
                this.saveState(world, worldId, state);
                this.applySelectedCoreToManager(state, worldId);
            }
            return;
        }

        if ("prevcore".equalsIgnoreCase(eventData.action)) {
            if (!state.switchCore(-1)) {
                this.playerRef.sendMessage(Message.raw("No saved crimson candidates yet. Use Set Core to add one."));
            } else {
                this.setSelectedIndex(worldId, state.selectedIndex);
                this.applySelectedCoreToManager(state, worldId);
            }
            this.reopen(ref, store, player);
            return;
        }

        if ("nextcore".equalsIgnoreCase(eventData.action)) {
            if (!state.switchCore(1)) {
                this.playerRef.sendMessage(Message.raw("No saved crimson candidates yet. Use Set Core to add one."));
            } else {
                this.setSelectedIndex(worldId, state.selectedIndex);
                this.applySelectedCoreToManager(state, worldId);
            }
            this.reopen(ref, store, player);
            return;
        }

        if ("setcore".equalsIgnoreCase(eventData.action)) {
            Transform transform = this.playerRef.getTransform();
            Vector3i corePos = new Vector3i(
                    MathUtil.floor(transform.getPosition().getX()),
                    MathUtil.floor(transform.getPosition().getY()) - 1,
                    MathUtil.floor(transform.getPosition().getZ())
            );
            world.setBlock(corePos.x, corePos.y, corePos.z, RedWaveConfig.CORE_BLOCK_ID);
            RedCoreRegistry.register(worldId, corePos);

            int existingIndex = state.indexOf(corePos);
            if (existingIndex >= 0) {
                state.selectedIndex = existingIndex;
            } else {
                state.profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(corePos), state.globalRadius, state.globalSpreadSeconds));
                sortProfiles(state.profiles);
                state.selectedIndex = state.indexOf(corePos);
            }
            state.chooseCount = normalizeChooseCount(state.chooseCount, state.profiles.size());
            this.setSelectedIndex(worldId, state.selectedIndex);
            this.saveState(world, worldId, state);
            this.applySelectedCoreToManager(state, worldId);
            this.playerRef.sendMessage(Message.raw("Crimson candidate saved at " + corePos.x + "," + corePos.y + "," + corePos.z));
            this.reopen(ref, store, player);
            return;
        }

        if ("removecore".equalsIgnoreCase(eventData.action)) {
            RedCoreProfileRegistry.RedCoreProfile active = state.selectedProfile();
            if (active == null) {
                this.playerRef.sendMessage(Message.raw("No crimson candidate selected."));
                return;
            }
            Vector3i corePos = active.corePos();
            world.setBlock(corePos.x, corePos.y, corePos.z, "Empty");
            RedCoreRegistry.unregister(worldId, corePos);
            RedWaveManager.clearWave(worldId, corePos);
            state.profiles.remove(state.selectedIndex);
            state.chooseCount = normalizeChooseCount(state.chooseCount, state.profiles.size());
            state.selectedIndex = clampIndex(state.selectedIndex, state.profiles.size());
            this.setSelectedIndex(worldId, state.selectedIndex);
            this.saveState(world, worldId, state);
            this.playerRef.sendMessage(Message.raw("Removed crimson candidate at " + corePos.x + "," + corePos.y + "," + corePos.z));
            this.reopen(ref, store, player);
            return;
        }

        if ("globalundo".equalsIgnoreCase(eventData.action)) {
            RedWaveManager.UndoProcessStatus runningGlobal = RedWaveManager.getUndoProcessStatus(worldId);
            if (RedWaveManager.isGlobalUndoRunning(worldId) && runningGlobal != null && !runningGlobal.done()) {
                this.playerRef.sendMessage(Message.raw("Global undo already running."));
                return;
            }

            RedWaveManager.clearWave(worldId);
            RedWaveManager.UndoSession undo = RedWaveManager.takeUndoSession(worldId);
            if (undo == null || undo.size() == 0 || !RedWaveManager.beginUndoProcess(worldId, undo)) {
                this.playerRef.sendMessage(Message.raw("Nothing to undo globally."));
                return;
            }
            this.playerRef.sendMessage(Message.raw("Global undo started: " + undo.chunkCount() + " chunks."));
            return;
        }

        if ("undo".equalsIgnoreCase(eventData.action)) {
            RedCoreProfileRegistry.RedCoreProfile active = state.selectedProfile();
            if (active == null) {
                this.playerRef.sendMessage(Message.raw("No crimson candidate selected."));
                return;
            }

            Vector3i corePos = active.corePos();
            RedWaveManager.UndoProcessStatus running = RedWaveManager.getUndoProcessStatus(worldId, corePos);
            if (running != null && !running.done()) {
                this.playerRef.sendMessage(Message.raw("Undo already running."));
                return;
            }
            RedWaveManager.ActiveWave runningWave = RedWaveManager.getActiveWave(worldId, corePos);
            if (runningWave != null) {
                RedWaveManager.clearWave(worldId, corePos);
            }

            RedWaveManager.UndoSession undo = RedWaveManager.takeUndoSessionsForCore(worldId, corePos);
            if (undo == null || undo.size() == 0 || !RedWaveManager.beginUndoProcess(worldId, corePos, undo)) {
                this.playerRef.sendMessage(Message.raw("Nothing to undo for this core."));
                return;
            }
            this.playerRef.sendMessage(Message.raw("Undo started for candidate #" + (state.selectedIndex + 1) + ": " + undo.chunkCount() + " chunks."));
            return;
        }

        if ("startwave".equalsIgnoreCase(eventData.action)) {
            RedCoreProfileRegistry.RedCoreProfile active = state.selectedProfile();
            if (active == null) {
                this.playerRef.sendMessage(Message.raw("No saved Crimson_Core for the selected candidate."));
                return;
            }

            this.applySelectedCoreToManager(state, worldId);

            if (!RedWaveManager.isCoreReady(worldId, active.corePos())) {
                this.playerRef.sendMessage(Message.raw("This core is busy or a global undo is running."));
                return;
            }

            Vector3i corePos = active.corePos();
            BlockType coreType = world.getBlockType(corePos.x, corePos.y, corePos.z);
            if (coreType == null || !RedWaveConfig.isCoreBlockId(coreType.getId())) {
                this.playerRef.sendMessage(Message.raw("Core block mismatch."));
                return;
            }

            if (RedWaveManager.isUndoRecordingEnabled()) {
                RedWaveManager.beginUndoSession(worldId, corePos);
            }
            RedWaveManager.ActiveWave wave = RedWaveManager.startWave(worldId, corePos, state.globalRadius, state.globalSpreadSeconds);
            double speed = wave.spreadSpeedPerTick();
            int limit = RedWaveManager.getWorldFrontierLimit(worldId);
            this.playerRef.sendMessage(Message.raw(
                    "Red wave started for candidate #" + (state.selectedIndex + 1)
                            + ": speed=" + String.format("%.3f", speed) + " blocks/tick, limit=" + limit
                            + ", mask=" + wave.totalBlocks() + "."
            ));
        }
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
    }

    @Nonnull
    private UiState loadState(@Nonnull World world) {
        UUID worldId = world.getWorldConfig().getUuid();
        CrimsonCoreConfigManager.CrimsonCoreConfigState configuredState = CrimsonCoreConfigManager.get().getState(world.getName());
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles = new ArrayList<>();
        int globalRadius = configuredState.radiusBlocks();
        float globalSpreadSeconds = configuredState.spreadSeconds();

        for (RedCoreProfileRegistry.RedCoreProfile configured : configuredState.profiles()) {
            Vector3i pos = configured.corePos();
            BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
            if (type == null || !RedWaveConfig.isCoreBlockId(type.getId())) {
                continue;
            }
            profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(pos), globalRadius, globalSpreadSeconds));
        }

        if (profiles.isEmpty()) {
            for (Vector3i pos : RedCoreRegistry.snapshot(worldId, RedCoreRegistry.CoreSortOrder.BY_XYZ_ASC)) {
                BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
                if (type == null || !RedWaveConfig.isCoreBlockId(type.getId())) {
                    continue;
                }
                profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(pos), globalRadius, globalSpreadSeconds));
            }
        }

        sortProfiles(profiles);
        int selectedIndex = clampIndex(this.getSelectedIndex(worldId), profiles.size());
        int chooseCount = normalizeChooseCount(configuredState.chooseCount(), profiles.size());
        return new UiState(profiles, chooseCount, selectedIndex, globalRadius, globalSpreadSeconds);
    }

    private void saveState(@Nonnull World world, @Nonnull UUID worldId, @Nonnull UiState state) {
        int normalizedChooseCount = normalizeChooseCount(state.chooseCount, state.profiles.size());
        this.setSelectedIndex(worldId, clampIndex(state.selectedIndex, state.profiles.size()));
        state.rebuildProfilesWithGlobals();
        CrimsonCoreConfigManager.get().setState(
                world.getName(),
                new CrimsonCoreConfigManager.CrimsonCoreConfigState(normalizedChooseCount, state.globalRadius, state.globalSpreadSeconds, state.profiles)
        );
        this.syncRuntimeRegistry(worldId, new UiState(state.profiles, normalizedChooseCount, clampIndex(state.selectedIndex, state.profiles.size()), state.globalRadius, state.globalSpreadSeconds));
    }

    private void syncRuntimeRegistry(@Nonnull UUID worldId, @Nonnull UiState state) {
        RedCoreProfileRegistry.setProfiles(worldId, state.profiles);
    }

    private void applySelectedCoreToManager(@Nonnull UiState state, @Nonnull UUID worldId) {
        RedCoreProfileRegistry.RedCoreProfile profile = state.selectedProfile();
        if (profile == null) {
            return;
        }
        RedWaveManager.setCore(this.playerRef.getUuid(), worldId, profile.corePos());
        RedWaveManager.setRadius(this.playerRef.getUuid(), worldId, state.globalRadius);
    }

    private int getSelectedIndex(@Nonnull UUID worldId) {
        ConcurrentHashMap<UUID, Integer> perWorld = SELECTED_INDEX_BY_PLAYER_WORLD.get(this.playerRef.getUuid());
        if (perWorld == null) {
            return 0;
        }
        return perWorld.getOrDefault(worldId, 0);
    }

    private void setSelectedIndex(@Nonnull UUID worldId, int index) {
        SELECTED_INDEX_BY_PLAYER_WORLD
                .computeIfAbsent(this.playerRef.getUuid(), ignored -> new ConcurrentHashMap<>())
                .put(worldId, Math.max(0, index));
    }

    @Nonnull
    private String buildActiveCoreText(@Nonnull UiState state) {
        RedCoreProfileRegistry.RedCoreProfile profile = state.selectedProfile();
        if (profile == null) {
            return "Selected Candidate: Not set";
        }
        Vector3i pos = profile.corePos();
        return "Selected Candidate: " + pos.x + ", " + pos.y + ", " + pos.z + " | #" + (state.selectedIndex + 1);
    }

    @Nonnull
    private String buildPrevCoreButtonText(@Nonnull UiState state) {
        if (state.profiles.isEmpty()) {
            return "No saved";
        }
        int prev = (state.selectedIndex - 1 + state.profiles.size()) % state.profiles.size();
        return "< #" + (prev + 1);
    }

    @Nonnull
    private String buildNextCoreButtonText(@Nonnull UiState state) {
        if (state.profiles.isEmpty()) {
            return "No saved";
        }
        int next = (state.selectedIndex + 1) % state.profiles.size();
        return "#" + (next + 1) + " >";
    }

    @Nonnull
    private String formatSeconds(float seconds) {
        if (Math.abs(seconds - Math.round(seconds)) < 0.001f) {
            return Integer.toString(Math.round(seconds));
        }
        return Float.toString(seconds);
    }

    private static int normalizeChooseCount(int chooseCount, int profileCount) {
        if (profileCount <= 0) {
            return 0;
        }
        if (chooseCount < 1) {
            return 1;
        }
        return Math.min(chooseCount, profileCount);
    }

    private static int clampIndex(int index, int size) {
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

    private static void sortProfiles(@Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles) {
        profiles.sort(Comparator
                .comparingInt((RedCoreProfileRegistry.RedCoreProfile p) -> p.corePos().x)
                .thenComparingInt(p -> p.corePos().y)
                .thenComparingInt(p -> p.corePos().z));
    }

    private static final class UiState {
        @Nonnull
        private final ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles;
        private int chooseCount;
        private int selectedIndex;
        private int globalRadius;
        private float globalSpreadSeconds;

        private UiState(
                @Nonnull List<RedCoreProfileRegistry.RedCoreProfile> profiles,
                int chooseCount,
                int selectedIndex,
                int globalRadius,
                float globalSpreadSeconds
        ) {
            this.profiles = new ArrayList<>(profiles);
            this.chooseCount = chooseCount;
            this.selectedIndex = selectedIndex;
            this.globalRadius = globalRadius;
            this.globalSpreadSeconds = globalSpreadSeconds;
        }

        private boolean switchCore(int step) {
            if (this.profiles.isEmpty()) {
                return false;
            }
            this.selectedIndex = (this.selectedIndex + step + this.profiles.size()) % this.profiles.size();
            return true;
        }

        @Nullable
        private RedCoreProfileRegistry.RedCoreProfile selectedProfile() {
            if (this.selectedIndex < 0 || this.selectedIndex >= this.profiles.size()) {
                return null;
            }
            return this.profiles.get(this.selectedIndex);
        }

        private int indexOf(@Nonnull Vector3i corePos) {
            for (int i = 0; i < this.profiles.size(); i++) {
                if (this.profiles.get(i).corePos().equals(corePos)) {
                    return i;
                }
            }
            return -1;
        }

        private void rebuildProfilesWithGlobals() {
            for (int i = 0; i < this.profiles.size(); i++) {
                RedCoreProfileRegistry.RedCoreProfile existing = this.profiles.get(i);
                this.profiles.set(i, new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(existing.corePos()), this.globalRadius, this.globalSpreadSeconds));
            }
        }
    }

    public static final class PageEventData {
        public static final BuilderCodec<PageEventData> CODEC = BuilderCodec.builder(PageEventData.class, PageEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action)
                .add()
                .append(new KeyedCodec<>("@Radius", Codec.DOUBLE), (o, v) -> o.radius = v, o -> o.radius)
                .add()
                .append(new KeyedCodec<>("@SpreadSeconds", Codec.DOUBLE), (o, v) -> o.spreadSeconds = v, o -> o.spreadSeconds)
                .add()
                .append(new KeyedCodec<>("@ChooseCount", Codec.DOUBLE), (o, v) -> o.chooseCount = v, o -> o.chooseCount)
                .add()
                .build();
        @Nullable
        private String action;
        @Nullable
        private Double radius;
        @Nullable
        private Double spreadSeconds;
        @Nullable
        private Double chooseCount;

        public PageEventData() {
        }
    }
}