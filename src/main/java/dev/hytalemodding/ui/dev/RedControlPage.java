package dev.hytalemodding.ui.dev;

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
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.redwave.RedWaveConfig;
import dev.hytalemodding.redwave.RedWaveManager;
import dev.hytalemodding.redwave.RedCoreRegistry;
import dev.hytalemodding.redwave.RedCoreProfileRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class RedControlPage extends CustomUIPage {
    private static final int DEFAULT_UI_RADIUS = RedWaveConfig.DEFAULT_UI_RADIUS_BLOCKS;
    private static final float DEFAULT_UI_START_SECONDS = RedWaveConfig.DEFAULT_UI_START_SECONDS;
    private static final float MIN_UI_START_SECONDS = 1.0f;
    private static final float MAX_UI_START_SECONDS = 300.0f;

    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, PlayerUiState>> PLAYER_WORLD_STATE = new ConcurrentHashMap<>();

    public RedControlPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);
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
        PlayerUiState state = this.getState(worldId);
        this.syncDetectedCoresFromWorld(world, state);
        this.ensureRegistryHasAllDetectedCores(world, worldId);
        this.syncDetectedCoresFromWorld(world, state);
        this.syncStateToRegistry(worldId, state);
        this.applySelectedCoreToManager(state, worldId);

        ui.append("d97's/Pages/RedControlPage.ui");
        ui.set("#RadiusValueLabel.Text", "Current Radius: " + state.currentRadius());
        ui.set("#RadiusBigValueLabel.Text", Integer.toString(state.currentRadius()));
        ui.set("#StartValueLabel.Text", "Current Start Seconds: " + this.formatSeconds(state.currentStartSeconds()));
        ui.set("#StartBigValueLabel.Text", this.formatSeconds(state.currentStartSeconds()));
        ui.set("#ActiveCoreLabel.Text", this.buildActiveCoreText(state));
        ui.set("#PrevCoreButtonLabel.Text", this.buildPrevCoreButtonText(state));
        ui.set("#NextCoreButtonLabel.Text", this.buildNextCoreButtonText(state));

        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrevCoreButton", EventData.of("Action", "prevcore"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextCoreButton", EventData.of("Action", "nextcore"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SetCoreButton", EventData.of("Action", "setcore"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RadiusMinusButton", EventData.of("Action", "radiusminus"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RadiusPlusButton", EventData.of("Action", "radiusplus"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartMinusButton", EventData.of("Action", "startminus"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartPlusButton", EventData.of("Action", "startplus"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartWaveButton", EventData.of("Action", "startwave"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#UndoButton", EventData.of("Action", "undo"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#GlobalUndoButton", EventData.of("Action", "globalundo"), false);
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

        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        PlayerUiState state = this.getState(worldId);
        this.syncDetectedCoresFromWorld(world, state);
        this.ensureRegistryHasAllDetectedCores(world, worldId);
        this.syncDetectedCoresFromWorld(world, state);
        this.syncStateToRegistry(worldId, state);

        if (eventData.contains("prevcore")) {
            if (!state.switchCore(-1)) {
                this.playerRef.sendMessage(Message.raw("No cores available yet. Use Set Core to create one."));
            } else {
                this.applySelectedCoreToManager(state, worldId);
            }
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("nextcore")) {
            if (!state.switchCore(1)) {
                this.playerRef.sendMessage(Message.raw("No cores available yet. Use Set Core to create one."));
            } else {
                this.applySelectedCoreToManager(state, worldId);
            }
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("radiusminus")) {
            int next = Math.max(RedWaveConfig.MIN_RADIUS_BLOCKS, state.currentRadius() - 1);
            state.setCurrentRadius(next);
            this.syncStateToRegistry(worldId, state);
            this.applySelectedCoreToManager(state, worldId);
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("radiusplus")) {
            int next = Math.min(RedWaveConfig.MAX_RADIUS_BLOCKS, state.currentRadius() + 1);
            state.setCurrentRadius(next);
            this.syncStateToRegistry(worldId, state);
            this.applySelectedCoreToManager(state, worldId);
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("startminus")) {
            float next = Math.max(MIN_UI_START_SECONDS, state.currentStartSeconds() - 1.0f);
            state.setCurrentStartSeconds(next);
            this.syncStateToRegistry(worldId, state);
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("startplus")) {
            float next = Math.min(MAX_UI_START_SECONDS, state.currentStartSeconds() + 1.0f);
            state.setCurrentStartSeconds(next);
            this.syncStateToRegistry(worldId, state);
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("setcore")) {
            Transform transform = this.playerRef.getTransform();
            Vector3i corePos = new Vector3i(
                    MathUtil.floor(transform.getPosition().getX()),
                    MathUtil.floor(transform.getPosition().getY()) - 1,
                    MathUtil.floor(transform.getPosition().getZ())
            );
            world.setBlock(corePos.x, corePos.y, corePos.z, RedWaveConfig.CORE_BLOCK_ID);
            RedCoreRegistry.register(worldId, corePos);
            state.selectByPosition(corePos);
            this.syncDetectedCoresFromWorld(world, state);
            this.syncStateToRegistry(worldId, state);
            state.selectByPosition(corePos);
            this.applySelectedCoreToManager(state, worldId);
            this.playerRef.sendMessage(Message.raw("Core set at " + corePos.x + "," + corePos.y + "," + corePos.z));
            player.getPageManager().openCustomPage(ref, store, new RedControlPage(this.playerRef));
            return;
        }

        if (eventData.contains("globalundo")) {
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

        if (eventData.contains("undo")) {
            CoreProfile active = state.selectedCore();
            if (active == null || active.corePos == null) {
                this.playerRef.sendMessage(Message.raw("No active core selected."));
                return;
            }

            RedWaveManager.UndoProcessStatus running = RedWaveManager.getUndoProcessStatus(worldId, active.corePos);
            if (running != null && !running.done()) {
                this.playerRef.sendMessage(Message.raw("Undo already running."));
                return;
            }
            RedWaveManager.ActiveWave runningWave = RedWaveManager.getActiveWave(worldId, active.corePos);
            if (runningWave != null) {
                RedWaveManager.clearWave(worldId, active.corePos);
            }

            RedWaveManager.UndoSession undo = RedWaveManager.takeUndoSessionsForCore(worldId, active.corePos);
            if (undo == null || undo.size() == 0 || !RedWaveManager.beginUndoProcess(worldId, active.corePos, undo)) {
                this.playerRef.sendMessage(Message.raw("Nothing to undo for this core."));
                return;
            }
            this.playerRef.sendMessage(Message.raw("Undo started for core #" + (state.selectedIndex + 1) + ": " + undo.chunkCount() + " chunks."));
            return;
        }

        if (eventData.contains("startwave")) {
            CoreProfile active = state.selectedCore();
            if (active == null || active.corePos == null) {
                this.playerRef.sendMessage(Message.raw("No detected Crimson_Core for the selected slot."));
                return;
            }

            this.applySelectedCoreToManager(state, worldId);

            if (!RedWaveManager.isCoreReady(worldId, active.corePos)) {
                this.playerRef.sendMessage(Message.raw("This core is busy or a global undo is running."));
                return;
            }

            Vector3i corePos = active.corePos;
            BlockType coreType = world.getBlockType(corePos.x, corePos.y, corePos.z);
            if (coreType == null || !RedWaveConfig.CORE_BLOCK_ID.equals(coreType.getId())) {
                this.playerRef.sendMessage(Message.raw("Core block mismatch."));
                return;
            }

            RedWaveManager.beginUndoSession(worldId, corePos);
            RedWaveManager.ActiveWave wave = RedWaveManager.startWave(worldId, corePos, active.radius, active.startSeconds);
            this.playerRef.sendMessage(Message.raw("Red wave started for core #" + (state.selectedIndex + 1) + ": " + wave.totalBlocks() + " positions in " + this.formatSeconds(active.startSeconds) + "s."));
        }
    }

    @Nonnull
    private PlayerUiState getState(@Nonnull UUID worldId) {
        ConcurrentHashMap<UUID, PlayerUiState> worldMap = PLAYER_WORLD_STATE.computeIfAbsent(this.playerRef.getUuid(), ignored -> new ConcurrentHashMap<>());
        return worldMap.computeIfAbsent(worldId, ignored -> new PlayerUiState(DEFAULT_UI_RADIUS, DEFAULT_UI_START_SECONDS));
    }


    private void ensureRegistryHasAllDetectedCores(@Nonnull World world, @Nonnull UUID worldId) {
        HashMap<String, RedCoreProfileRegistry.RedCoreProfile> configuredByKey = RedCoreProfileRegistry.snapshotByKey(worldId);
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> merged = new ArrayList<>();

        for (RedCoreProfileRegistry.RedCoreProfile configured : configuredByKey.values()) {
            Vector3i pos = configured.corePos();
            BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
            if (type == null || !RedWaveConfig.CORE_BLOCK_ID.equals(type.getId())) {
                continue;
            }
            merged.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(pos), configured.radiusBlocks(), configured.startSeconds()));
        }

        for (Vector3i pos : RedCoreRegistry.snapshot(worldId, RedCoreRegistry.CoreSortOrder.BY_XYZ_ASC)) {
            BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
            if (type == null || !RedWaveConfig.CORE_BLOCK_ID.equals(type.getId())) {
                continue;
            }
            String key = this.key(pos);
            if (configuredByKey.containsKey(key)) {
                continue;
            }
            merged.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(pos), DEFAULT_UI_RADIUS, DEFAULT_UI_START_SECONDS));
        }

        RedCoreProfileRegistry.setProfiles(worldId, merged);
    }


    private void syncDetectedCoresFromWorld(@Nonnull World world, @Nonnull PlayerUiState state) {
        HashMap<String, CoreProfile> previousByKey = new HashMap<>();
        for (CoreProfile profile : state.cores) {
            if (profile.corePos != null) {
                previousByKey.put(this.key(profile.corePos), profile);
            }
        }
        HashMap<String, RedCoreProfileRegistry.RedCoreProfile> configuredByKey = RedCoreProfileRegistry.snapshotByKey(world.getWorldConfig().getUuid());

        ArrayList<CoreProfile> detected = new ArrayList<>();
        HashSet<String> addedKeys = new HashSet<>();
        ArrayList<Vector3i> snapshot = new ArrayList<>(RedCoreRegistry.snapshot(world.getWorldConfig().getUuid(), RedCoreRegistry.CoreSortOrder.BY_XYZ_ASC));

        for (Vector3i pos : snapshot) {
            String key = this.key(pos);
            BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
            if (type == null || !RedWaveConfig.CORE_BLOCK_ID.equals(type.getId())) {
                continue;
            }
            CoreProfile existing = previousByKey.get(key);
            if (existing != null) {
                detected.add(new CoreProfile(existing.corePos, existing.radius, existing.startSeconds));
                addedKeys.add(key);
                continue;
            }
            RedCoreProfileRegistry.RedCoreProfile configured = configuredByKey.get(key);
            if (configured != null) {
                detected.add(new CoreProfile(pos, configured.radiusBlocks(), configured.startSeconds()));
                addedKeys.add(key);
                continue;
            }
            detected.add(new CoreProfile(pos, DEFAULT_UI_RADIUS, DEFAULT_UI_START_SECONDS));
            addedKeys.add(key);
        }

        for (RedCoreProfileRegistry.RedCoreProfile configured : configuredByKey.values()) {
            Vector3i pos = configured.corePos();
            String key = this.key(pos);
            if (addedKeys.contains(key)) {
                continue;
            }
            BlockType type = world.getBlockType(pos.x, pos.y, pos.z);
            if (type == null || !RedWaveConfig.CORE_BLOCK_ID.equals(type.getId())) {
                continue;
            }
            detected.add(new CoreProfile(pos, configured.radiusBlocks(), configured.startSeconds()));
            addedKeys.add(key);
        }

        String selectedKey = null;
        CoreProfile selected = state.selectedCore();
        if (selected != null && selected.corePos != null) {
            selectedKey = this.key(selected.corePos);
        }

        state.cores.clear();
        state.cores.addAll(detected);

        if (state.cores.isEmpty()) {
            state.selectedIndex = -1;
            return;
        }

        if (selectedKey != null) {
            for (int i = 0; i < state.cores.size(); i++) {
                if (selectedKey.equals(this.key(state.cores.get(i).corePos))) {
                    state.selectedIndex = i;
                    return;
                }
            }
        }

        if (state.selectedIndex < 0 || state.selectedIndex >= state.cores.size()) {
            state.selectedIndex = 0;
        }
    }

    @Nonnull
    private String key(@Nonnull Vector3i pos) {
        return pos.x + ":" + pos.y + ":" + pos.z;
    }

    private void applySelectedCoreToManager(@Nonnull PlayerUiState state, @Nonnull UUID worldId) {
        CoreProfile profile = state.selectedCore();
        if (profile == null || profile.corePos == null) {
            return;
        }
        RedWaveManager.setCore(this.playerRef.getUuid(), worldId, profile.corePos);
        RedWaveManager.setRadius(this.playerRef.getUuid(), worldId, profile.radius);
    }

    private void syncStateToRegistry(@Nonnull UUID worldId, @Nonnull PlayerUiState state) {
        ArrayList<RedCoreProfileRegistry.RedCoreProfile> profiles = new ArrayList<>();
        for (CoreProfile core : state.cores) {
            if (core.corePos == null) {
                continue;
            }
            profiles.add(new RedCoreProfileRegistry.RedCoreProfile(new Vector3i(core.corePos), core.radius, core.startSeconds));
        }
        RedCoreProfileRegistry.setProfiles(worldId, profiles);
    }

    @Nonnull
    private String buildActiveCoreText(@Nonnull PlayerUiState state) {
        CoreProfile profile = state.selectedCore();
        if (profile == null || profile.corePos == null) {
            return "Active Core: Not set";
        }
        return "Active Core: " + profile.corePos.x + ", " + profile.corePos.y + ", " + profile.corePos.z + " | Core #" + (state.selectedIndex + 1);
    }

    @Nonnull
    private String buildPrevCoreButtonText(@Nonnull PlayerUiState state) {
        if (state.cores.isEmpty()) {
            return "No cores";
        }
        int prev = (state.selectedIndex - 1 + state.cores.size()) % state.cores.size();
        return "< #" + (prev + 1);
    }

    @Nonnull
    private String buildNextCoreButtonText(@Nonnull PlayerUiState state) {
        if (state.cores.isEmpty()) {
            return "No cores";
        }
        int next = (state.selectedIndex + 1) % state.cores.size();
        return "#" + (next + 1) + " >";
    }

    @Nonnull
    private String formatSeconds(float seconds) {
        if (Math.abs(seconds - Math.round(seconds)) < 0.001f) {
            return Integer.toString(Math.round(seconds));
        }
        return Float.toString(seconds);
    }

    private static final class PlayerUiState {
        private final ArrayList<CoreProfile> cores = new ArrayList<>();
        private int selectedIndex = -1;
        private int draftRadius;
        private float draftStartSeconds;

        private PlayerUiState(int defaultRadius, float defaultStartSeconds) {
            this.draftRadius = defaultRadius;
            this.draftStartSeconds = defaultStartSeconds;
        }

        private int currentRadius() {
            CoreProfile profile = this.selectedCore();
            return profile == null ? this.draftRadius : profile.radius;
        }

        private float currentStartSeconds() {
            CoreProfile profile = this.selectedCore();
            return profile == null ? this.draftStartSeconds : profile.startSeconds;
        }

        @Nullable
        private CoreProfile selectedCore() {
            if (this.selectedIndex < 0 || this.selectedIndex >= this.cores.size()) {
                return null;
            }
            return this.cores.get(this.selectedIndex);
        }

        private void setCurrentRadius(int radius) {
            CoreProfile profile = this.selectedCore();
            if (profile == null) {
                this.draftRadius = radius;
            } else {
                profile.radius = radius;
            }
        }

        private void setCurrentStartSeconds(float seconds) {
            CoreProfile profile = this.selectedCore();
            if (profile == null) {
                this.draftStartSeconds = seconds;
            } else {
                profile.startSeconds = seconds;
            }
        }

        private boolean switchCore(int step) {
            if (this.cores.isEmpty()) {
                return false;
            }
            this.selectedIndex = (this.selectedIndex + step + this.cores.size()) % this.cores.size();
            return true;
        }

        private void selectByPosition(@Nonnull Vector3i corePos) {
            for (int i = 0; i < this.cores.size(); i++) {
                CoreProfile profile = this.cores.get(i);
                if (profile.corePos != null && profile.corePos.equals(corePos)) {
                    this.selectedIndex = i;
                    return;
                }
            }
        }
    }

    private static final class CoreProfile {
        @Nullable
        private Vector3i corePos;
        private int radius;
        private float startSeconds;

        private CoreProfile(@Nullable Vector3i corePos, int radius, float startSeconds) {
            this.corePos = corePos;
            this.radius = radius;
            this.startSeconds = startSeconds;
        }
    }
}

