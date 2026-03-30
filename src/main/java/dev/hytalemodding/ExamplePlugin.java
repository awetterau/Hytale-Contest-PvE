package dev.hytalemodding;

import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.commands.dev.DevPanelCommand;
import dev.hytalemodding.commands.dev.ExampleCommand;
import dev.hytalemodding.commands.dev.MapOpenCommand;
import dev.hytalemodding.commands.dev.PasteBlacksmithBuildCommand;
import dev.hytalemodding.commands.dev.PasteBlacksmithCommand;
import dev.hytalemodding.commands.dev.UndoBlacksmithCommand;
import dev.hytalemodding.commands.hub.GameConfigCommand;
import dev.hytalemodding.commands.hub.SetBaseSpawnCommand;
import dev.hytalemodding.commands.hub.SetRescueSpawnCommand;
import dev.hytalemodding.commands.npc.BlacksmithDevCommand;
import dev.hytalemodding.commands.npc.NpcDevCommand;
import dev.hytalemodding.commands.npc.NpcRolesCommand;
import dev.hytalemodding.commands.npc.NpcSpawnCommand;
import dev.hytalemodding.commands.npc.SpawnRooterCommand;
import dev.hytalemodding.commands.quest.QuestDevCommand;
import dev.hytalemodding.commands.quest.QuestLogCommand;
import dev.hytalemodding.commands.quest.SetQuestChestCommand;
import dev.hytalemodding.commands.redwave.RedCoreCommand;
import dev.hytalemodding.commands.redwave.RedRadiusCommand;
import dev.hytalemodding.commands.redwave.RedStartCommand;
import dev.hytalemodding.commands.redwave.RedUiCommand;
import dev.hytalemodding.commands.redwave.RedUndoCommand;
import dev.hytalemodding.commands.run.GameEndCommand;
import dev.hytalemodding.commands.run.GameResetCommand;
import dev.hytalemodding.commands.run.GameStartCommand;
import dev.hytalemodding.commands.run.SetRunSpawnCommand;
import dev.hytalemodding.commands.run.SpawnStartTriggerCommand;
import dev.hytalemodding.commands.run.SpawnUiCommand;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.DevDebugHudSystem;
import dev.hytalemodding.loot.LifeEssenceChestBreakCleanupSystem;
import dev.hytalemodding.loot.LifeEssenceChestOpenSystem;
import dev.hytalemodding.loot.LifeEssenceContainerKey;
import dev.hytalemodding.loot.LootChestRuntime;
import dev.hytalemodding.loot.QuestChestConfigManager;
import dev.hytalemodding.loot.QuestChestPositionManager;
import dev.hytalemodding.map.MapReplacementPacketController;
import dev.hytalemodding.map.BlacksmithSharedMarkerManager;
import dev.hytalemodding.map.QuestChestMarkerPacketController;
import dev.hytalemodding.map.SpawnMarkerPacketController;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.RunRescueRegistry;
import dev.hytalemodding.quest.QuestDefinitionRegistry;
import dev.hytalemodding.quest.QuestFlagManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.redwave.CrimsonCoreDetectionSystem;
import dev.hytalemodding.redwave.RedWaveBlockSweepSystem;
import dev.hytalemodding.redwave.RedWoolDamageSystem;
import dev.hytalemodding.redwave.RedWoolNpcDamageSystem;
import dev.hytalemodding.rooter.RooterConfig;
import dev.hytalemodding.rooter.RooterEncounterSystem;
import dev.hytalemodding.rooter.RooterManManager;
import dev.hytalemodding.rooter.RooterShockwaveDamageSystem;
import dev.hytalemodding.rooter.RooterShockwaveEmitterSystem;
import dev.hytalemodding.state.hub.BaseHousingSystem;
import dev.hytalemodding.state.hub.BlacksmithPrefabBuildSystem;
import dev.hytalemodding.state.hub.BlacksmithWorkshopEntityMarker;
import dev.hytalemodding.state.hub.BasePlotInteractionHandler;
import dev.hytalemodding.state.hub.BasePlotUseInteraction;
import dev.hytalemodding.state.hub.RescueInteractionPacketWatcher;
import dev.hytalemodding.state.hub.TransparentLightProtectionSystem;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.GameDoorUseInteraction;
import dev.hytalemodding.state.run.GameDoorBlockDetectionSystem;
import dev.hytalemodding.state.run.GameRunDirectorSystem;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.BlightBeastKillTrackerSystem;
import dev.hytalemodding.state.run.GameDoorTriggerSystem;
import dev.hytalemodding.state.run.RescueObjectiveManager;
import dev.hytalemodding.state.run.RescueObjectiveSystem;
import dev.hytalemodding.state.run.RunDeathHandler;
import dev.hytalemodding.state.run.RunClientReadyPacketWatcher;
import dev.hytalemodding.state.run.RunStartCameraSystem;
import dev.hytalemodding.state.run.RunStartMovementLockSystem;
import dev.hytalemodding.state.run.RunStartPlayerAnimationPatcher;
import dev.hytalemodding.state.run.SpawnPointDetectionSystem;
import dev.hytalemodding.state.run.SpawnPointPlacementHandler;
import dev.hytalemodding.state.transition.PlayerSpawnSafety;

import javax.annotation.Nonnull;

public class ExamplePlugin extends JavaPlugin {
    private boolean introAnimationPatched;
    private RescueInteractionPacketWatcher rescueInteractionPacketWatcher;
    private RunClientReadyPacketWatcher runClientReadyPacketWatcher;
    private MapReplacementPacketController mapReplacementPacketController;
    private SpawnMarkerPacketController spawnMarkerPacketController;
    private QuestChestMarkerPacketController questChestMarkerPacketController;

    public ExamplePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        BlacksmithWorkshopEntityMarker.setComponentType(
                this.getEntityStoreRegistry().registerComponent(
                        BlacksmithWorkshopEntityMarker.class,
                        "BlacksmithWorkshopEntity",
                        BlacksmithWorkshopEntityMarker.CODEC
                )
        );
        this.getCommandRegistry().registerCommand(new GameStartCommand());
        this.getCommandRegistry().registerCommand(new GameEndCommand());
        this.getCommandRegistry().registerCommand(new GameResetCommand());
        this.getCommandRegistry().registerCommand(new SetRunSpawnCommand());
        this.getCommandRegistry().registerCommand(new SpawnStartTriggerCommand());
        this.getCommandRegistry().registerCommand(new SpawnUiCommand());
        this.getCommandRegistry().registerCommand(new SetBaseSpawnCommand());
        this.getCommandRegistry().registerCommand(new SetRescueSpawnCommand());
        this.getCommandRegistry().registerCommand(new GameConfigCommand());
        this.getCommandRegistry().registerCommand(new NpcRolesCommand());
        this.getCommandRegistry().registerCommand(new NpcSpawnCommand());
        this.getCommandRegistry().registerCommand(new SpawnRooterCommand());
        this.getCommandRegistry().registerCommand(new BlacksmithDevCommand());
        this.getCommandRegistry().registerCommand(new NpcDevCommand());
        this.getCommandRegistry().registerCommand(new QuestDevCommand());
        this.getCommandRegistry().registerCommand(new QuestLogCommand());
        this.getCommandRegistry().registerCommand(new SetQuestChestCommand());
        this.getCommandRegistry().registerCommand(new DevPanelCommand());
        this.getCommandRegistry().registerCommand(new MapOpenCommand());
        this.getCommandRegistry().registerCommand(new PasteBlacksmithBuildCommand());
        this.getCommandRegistry().registerCommand(new PasteBlacksmithCommand());
        this.getCommandRegistry().registerCommand(new UndoBlacksmithCommand());
        this.getCommandRegistry().registerCommand(new ExampleCommand("example", "An example command"));
        this.getCodecRegistry(Interaction.CODEC).register(
                "game_door_use_interaction",
                GameDoorUseInteraction.class,
                GameDoorUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "base_plot_use_interaction",
                BasePlotUseInteraction.class,
                BasePlotUseInteraction.CODEC
        );
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, GameSessionManager.get()::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, PlayerSpawnSafety::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, BaseHousingManager::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, GameDoorInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, BasePlotInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, RescueObjectiveManager.get()::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, GameDoorInteractionHandler::onPlayerMouseButton);
        this.getEventRegistry().registerGlobal(PlaceBlockEvent.class, SpawnPointPlacementHandler::onPlaceBlock);

        this.getCommandRegistry().registerCommand(new RedCoreCommand());
        this.getCommandRegistry().registerCommand(new RedRadiusCommand());
        this.getCommandRegistry().registerCommand(new RedStartCommand());
        this.getCommandRegistry().registerCommand(new RedUndoCommand());
        this.getCommandRegistry().registerCommand(new RedUiCommand());

        this.introAnimationPatched = patchRunIntroAnimationSet("setup");
    }

    @Override
    protected void start() {
        if (!this.introAnimationPatched) {
            this.introAnimationPatched = patchRunIntroAnimationSet("start");
        }
        GameSessionManager.get().cleanupOrphanRunWorldsOnStartup();
        NpcDefinitionRegistry.get().initialize();
        NpcProgressManager.get().initialize();
        RunRescueRegistry.get().initialize();
        QuestDefinitionRegistry.get().initialize();
        QuestProgressManager.get().initialize();
        QuestFlagManager.get().initialize();
        QuestChestConfigManager.get().initialize();
        QuestChestPositionManager.get().initialize();
        RooterConfig.get().initialize();
        RooterManManager.get().initialize();
        BlacksmithSharedMarkerManager.sync();
        this.mapReplacementPacketController = new MapReplacementPacketController(this);
        this.mapReplacementPacketController.register();
        this.mapReplacementPacketController.validateCustomMapAssetRegistration();
        this.spawnMarkerPacketController = new SpawnMarkerPacketController(this);
        this.spawnMarkerPacketController.register();
        this.questChestMarkerPacketController = new QuestChestMarkerPacketController(this);
        this.questChestMarkerPacketController.register();
        this.rescueInteractionPacketWatcher = new RescueInteractionPacketWatcher();
        this.rescueInteractionPacketWatcher.register();
        this.runClientReadyPacketWatcher = new RunClientReadyPacketWatcher();
        this.runClientReadyPacketWatcher.register();
        java.util.Set<LifeEssenceContainerKey> processedLifeEssenceContainers = LootChestRuntime.get().processedChests();
        this.getEntityStoreRegistry().registerSystem(new LifeEssenceChestOpenSystem(processedLifeEssenceContainers));
        this.getEntityStoreRegistry().registerSystem(new LifeEssenceChestBreakCleanupSystem(processedLifeEssenceContainers));
        this.getEntityStoreRegistry().registerSystem(new GameRunDirectorSystem());
        this.getEntityStoreRegistry().registerSystem(new RescueObjectiveSystem());
        this.getEntityStoreRegistry().registerSystem(new RunStartCameraSystem());
        this.getEntityStoreRegistry().registerSystem(new RunStartMovementLockSystem());
        this.getEntityStoreRegistry().registerSystem(new RunDeathHandler());
        this.getEntityStoreRegistry().registerSystem(new BlightBeastKillTrackerSystem());
        this.getEntityStoreRegistry().registerSystem(new BaseHousingSystem());
        this.getEntityStoreRegistry().registerSystem(new BlacksmithPrefabBuildSystem());
        this.getEntityStoreRegistry().registerSystem(new TransparentLightProtectionSystem());
        this.getEntityStoreRegistry().registerSystem(new DevDebugHudSystem());

        try {
            this.getChunkStoreRegistry().registerSystem(new CrimsonCoreDetectionSystem());
            this.getChunkStoreRegistry().registerSystem(new GameDoorBlockDetectionSystem());
            this.getChunkStoreRegistry().registerSystem(new SpawnPointDetectionSystem());
        } catch (Throwable ignored) {
            // Keep plugin startup and command registration alive even when chunk-store APIs/components are unavailable.
        }
        this.getEntityStoreRegistry().registerSystem(new GameDoorTriggerSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWaveBlockSweepSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWoolDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWoolNpcDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new RooterEncounterSystem());
        this.getEntityStoreRegistry().registerSystem(new RooterShockwaveEmitterSystem());
        this.getEntityStoreRegistry().registerSystem(new RooterShockwaveDamageSystem());
    }

    @Override
    protected void shutdown() {
        if (this.rescueInteractionPacketWatcher != null) {
            this.rescueInteractionPacketWatcher.unregister();
            this.rescueInteractionPacketWatcher = null;
        }
        if (this.runClientReadyPacketWatcher != null) {
            this.runClientReadyPacketWatcher.unregister();
            this.runClientReadyPacketWatcher = null;
        }
        if (this.mapReplacementPacketController != null) {
            this.mapReplacementPacketController.unregister();
            this.mapReplacementPacketController = null;
        }
        if (this.spawnMarkerPacketController != null) {
            this.spawnMarkerPacketController.unregister();
            this.spawnMarkerPacketController = null;
        }
        if (this.questChestMarkerPacketController != null) {
            this.questChestMarkerPacketController.unregister();
            this.questChestMarkerPacketController = null;
        }
    }

    private static boolean patchRunIntroAnimationSet(@Nonnull String phase) {
        boolean patchedIntroAnimation = RunStartPlayerAnimationPatcher.ensureAnimationSetPatched(
                "Player_Anim_LargeRope_A",
                "Characters/Animations/Default/Player_Anim_LargeRope_A.blockyanim"
        );
        if (!patchedIntroAnimation) {
            System.out.println("[RunStartIntro] (" + phase + ") Failed to patch player AnimationSets for Player_Anim_LargeRope_A.");
        }
        return patchedIntroAnimation;
    }
}