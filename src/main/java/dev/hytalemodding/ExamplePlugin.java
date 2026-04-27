package dev.hytalemodding;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import dev.hytalemodding.commands.run.SpawnExtractionPrototypeCommand;
import dev.hytalemodding.blob.OrangeBlobBlockSystem;
import dev.hytalemodding.blob.OrangeBlobBlockUseInteraction;
import dev.hytalemodding.blob.FlareExtractionSystem;
import dev.hytalemodding.blob.FlareExtractionUseInteraction;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.commands.dev.DevPanelCommand;
import dev.hytalemodding.commands.dev.BloodSpikesCommand;
import dev.hytalemodding.commands.dev.BindingTestCommand;
import dev.hytalemodding.commands.dev.ExampleCommand;
import dev.hytalemodding.commands.dev.HolyTrackCommand;
import dev.hytalemodding.commands.dev.MapOpenCommand;
import dev.hytalemodding.commands.dev.PasteBlacksmithBuildCommand;
import dev.hytalemodding.commands.dev.PasteBlacksmithCommand;
import dev.hytalemodding.commands.dev.PasteFarmerBuildCommand;
import dev.hytalemodding.commands.dev.PasteFarmerCommand;
import dev.hytalemodding.commands.dev.UndoBlacksmithCommand;
import dev.hytalemodding.commands.dev.UndoFarmerCommand;
import dev.hytalemodding.commands.hub.GameConfigCommand;
import dev.hytalemodding.commands.hub.SetBaseSpawnCommand;
import dev.hytalemodding.commands.hub.SetRescueSpawnCommand;
import dev.hytalemodding.commands.hub.StashCommand;
import dev.hytalemodding.commands.npc.NpcAdminCommand;
import dev.hytalemodding.commands.npc.DespawnWitchCommand;
import dev.hytalemodding.commands.npc.NpcRolesCommand;
import dev.hytalemodding.commands.npc.NpcSpawnCommand;
import dev.hytalemodding.commands.npc.SpawnRooterCommand;
import dev.hytalemodding.commands.quest.QuestDevCommand;
import dev.hytalemodding.commands.quest.QuestLogCommand;
import dev.hytalemodding.commands.quest.SetQuestChestCommand;
import dev.hytalemodding.commands.redwave.RedCoreCommand;
import dev.hytalemodding.commands.redwave.RedLimitCommand;
import dev.hytalemodding.commands.redwave.RedRadiusCommand;
import dev.hytalemodding.commands.redwave.RedSpeedCommand;
import dev.hytalemodding.commands.redwave.RedStartCommand;
import dev.hytalemodding.commands.redwave.RedUiCommand;
import dev.hytalemodding.commands.redwave.RedUndoCommand;
import dev.hytalemodding.commands.run.GameEndCommand;
import dev.hytalemodding.commands.run.GameResetCommand;
import dev.hytalemodding.commands.run.GameStartCommand;
import dev.hytalemodding.commands.run.BlightfallCommand;
import dev.hytalemodding.commands.run.PartyCommand;
import dev.hytalemodding.commands.run.RegionSpawnCommand;
import dev.hytalemodding.commands.run.SetRunSpawnCommand;
import dev.hytalemodding.commands.run.RunChunkSelectionCommand;
import dev.hytalemodding.commands.run.SpawnStartTriggerCommand;
import dev.hytalemodding.commands.run.SpawnUiCommand;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.stash.StashChestUseInteraction;
import dev.hytalemodding.quest.QuestBoardUseInteraction;
import dev.hytalemodding.crimson.CrimsonWitchHelperSummonSystem;
import dev.hytalemodding.crimson.CrimsonWitchGroundTrapProjectileSystem;
import dev.hytalemodding.crimson.CrimsonWitchEnvironmentalPressureSystem;
import dev.hytalemodding.game.DevDebugHudSystem;
import dev.hytalemodding.loot.LifeEssenceChestBreakCleanupSystem;
import dev.hytalemodding.loot.LifeEssenceChestOpenSystem;
import dev.hytalemodding.loot.LifeEssenceContainerKey;
import dev.hytalemodding.loot.LootHarvestBlockSystem;
import dev.hytalemodding.loot.MobDropSystem;
import dev.hytalemodding.loot.LootChestRuntime;
import dev.hytalemodding.loot.LootTableRegistry;
import dev.hytalemodding.loot.QuestChestConfigManager;
import dev.hytalemodding.loot.QuestChestPositionManager;
import dev.hytalemodding.map.MapReplacementPacketController;
import dev.hytalemodding.map.BlacksmithSharedMarkerManager;
import dev.hytalemodding.map.FarmerAnimalMarkerPacketController;
import dev.hytalemodding.map.QuestChestMarkerPacketController;
import dev.hytalemodding.map.SpawnMarkerPacketController;
import dev.hytalemodding.map.RunChunkSelectionMapController;
import dev.hytalemodding.npc.NpcDefinitionRegistry;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.npc.RunRescueRegistry;
import dev.hytalemodding.npc.admin.NpcAdminService;
import dev.hytalemodding.npc.config.NpcUnifiedRegistry;
import dev.hytalemodding.quest.QuestDefinitionRegistry;
import dev.hytalemodding.quest.QuestFlagManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.potion.PotionBrewerWitchHealZoneHealingSystem;
import dev.hytalemodding.potion.PotionBrewerWitchHealZoneRuntime;
import dev.hytalemodding.potion.PotionBrewerWitchPoisonDamageSystem;
import dev.hytalemodding.potion.PotionBrewerWitchBindingSystem;
import dev.hytalemodding.potion.PotionBrewerWitchBloodSystem;
import dev.hytalemodding.potion.PotionBrewerWitchHudSystem;
import dev.hytalemodding.potion.PotionBrewerWitchHolySystem;
import dev.hytalemodding.potion.PotionBrewerWitchProjectileSystem;
import dev.hytalemodding.potion.PotionBrewerWitchShockwaveDamageSystem;
import dev.hytalemodding.potion.PotionBrewerWitchShockwaveSystem;
import dev.hytalemodding.potion.PotionBrewerWitchSystem;
import dev.hytalemodding.redwave.CrimsonCoreDetectionSystem;
import dev.hytalemodding.redwave.RedWaveBlockSweepSystem;
import dev.hytalemodding.redwave.RedWoolDamageSystem;
import dev.hytalemodding.redwave.RedWoolNpcDamageSystem;
import dev.hytalemodding.rooter.RooterConfig;
import dev.hytalemodding.rooter.RooterEncounterSystem;
import dev.hytalemodding.rooter.RooterManManager;
import dev.hytalemodding.rooter.RooterMeleeDamageSystem;
import dev.hytalemodding.rooter.RooterShockwaveDamageSystem;
import dev.hytalemodding.rooter.RooterShockwaveEmitterSystem;
import dev.hytalemodding.state.hub.BaseHousingSystem;
import dev.hytalemodding.state.hub.BlacksmithPrefabBuildSystem;
import dev.hytalemodding.state.hub.BlacksmithWorkshopEntityMarker;
import dev.hytalemodding.state.hub.BasePlotInteractionHandler;
import dev.hytalemodding.state.hub.BasePlotUseInteraction;
import dev.hytalemodding.state.hub.FarmerPrefabBuildSystem;
import dev.hytalemodding.state.hub.FarmerWorkshopEntityMarker;
import dev.hytalemodding.state.hub.RescueInteractionPacketWatcher;
import dev.hytalemodding.state.hub.TransparentLightProtectionSystem;
import dev.hytalemodding.state.party.PartyHubInteractionHandler;
import dev.hytalemodding.state.party.PartyManagerUseInteraction;
import dev.hytalemodding.state.run.GameDoorInteractionHandler;
import dev.hytalemodding.state.run.GameDoorUseInteraction;
import dev.hytalemodding.state.run.GameDoorBlockDetectionSystem;
import dev.hytalemodding.state.run.GameRunDirectorSystem;
import dev.hytalemodding.state.run.GameSessionManager;
import dev.hytalemodding.state.run.BlightBeastKillTrackerSystem;
import dev.hytalemodding.state.run.FarmerAnimalRescueManager;
import dev.hytalemodding.state.run.FarmerAnimalRescueSystem;
import dev.hytalemodding.state.run.GameDoorTriggerSystem;
import dev.hytalemodding.state.run.RescueObjectiveManager;
import dev.hytalemodding.state.run.RescueObjectiveSystem;
import dev.hytalemodding.state.run.RegionSpawnManager;
import dev.hytalemodding.state.run.RegionSpawnMarkerDetectionSystem;
import dev.hytalemodding.state.run.RegionSpawnPlacementHandler;
import dev.hytalemodding.state.run.RegionSpawnSystem;
import dev.hytalemodding.state.run.RunDeathHandler;
import dev.hytalemodding.state.run.RunClientReadyPacketWatcher;
import dev.hytalemodding.state.run.RunChunkMapRefreshTickingSystem;
import dev.hytalemodding.state.run.RunActiveMapRefreshSystem;
import dev.hytalemodding.state.run.RunPinnedChunkKeepAliveSystem;
import dev.hytalemodding.state.run.RunControlWorldSafetySystem;
import dev.hytalemodding.state.run.RunStartCameraSystem;
import dev.hytalemodding.state.run.RunStartMovementLockSystem;
import dev.hytalemodding.state.run.InfectionCoreDetectionSystem;
import dev.hytalemodding.state.run.SpawnPointDetectionSystem;
import dev.hytalemodding.state.run.SpawnPointPlacementHandler;
import dev.hytalemodding.state.transition.GameFlowConfigManager;
import dev.hytalemodding.state.transition.PlayerSpawnSafety;
import dev.hytalemodding.npc.state.NpcStateManager;
import dev.hytalemodding.stash.PlayerStashManager;

import javax.annotation.Nonnull;

public class ExamplePlugin extends JavaPlugin {
    private RescueInteractionPacketWatcher rescueInteractionPacketWatcher;
    private RunClientReadyPacketWatcher runClientReadyPacketWatcher;
    private MapReplacementPacketController mapReplacementPacketController;
    private SpawnMarkerPacketController spawnMarkerPacketController;
    private QuestChestMarkerPacketController questChestMarkerPacketController;
    private RunChunkSelectionMapController runChunkSelectionMapController;
    private FarmerAnimalMarkerPacketController farmerAnimalMarkerPacketController;

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
        FarmerWorkshopEntityMarker.setComponentType(
                this.getEntityStoreRegistry().registerComponent(
                        FarmerWorkshopEntityMarker.class,
                        "FarmerWorkshopEntity",
                        FarmerWorkshopEntityMarker.CODEC
                )
        );
        this.getCommandRegistry().registerCommand(new GameStartCommand());
        this.getCommandRegistry().registerCommand(new SpawnExtractionPrototypeCommand());
        this.getCommandRegistry().registerCommand(new GameEndCommand());
        this.getCommandRegistry().registerCommand(new GameResetCommand());
        this.getCommandRegistry().registerCommand(new BlightfallCommand());
        this.getCommandRegistry().registerCommand(new SetRunSpawnCommand());
        this.getCommandRegistry().registerCommand(new SpawnStartTriggerCommand());
        this.getCommandRegistry().registerCommand(new SpawnUiCommand());
        this.getCommandRegistry().registerCommand(new PartyCommand());
        this.getCommandRegistry().registerCommand(new RegionSpawnCommand());
        this.getCommandRegistry().registerCommand(new RunChunkSelectionCommand());
        this.getCommandRegistry().registerCommand(new SetBaseSpawnCommand());
        this.getCommandRegistry().registerCommand(new SetRescueSpawnCommand());
        this.getCommandRegistry().registerCommand(new StashCommand());
        this.getCommandRegistry().registerCommand(new GameConfigCommand());
        this.getCommandRegistry().registerCommand(new NpcRolesCommand());
        this.getCommandRegistry().registerCommand(new NpcSpawnCommand());
        this.getCommandRegistry().registerCommand(new DespawnWitchCommand());
        this.getCommandRegistry().registerCommand(new SpawnRooterCommand());
        this.getCommandRegistry().registerCommand(new NpcAdminCommand());
        this.getCommandRegistry().registerCommand(new QuestDevCommand());
        this.getCommandRegistry().registerCommand(new QuestLogCommand());
        this.getCommandRegistry().registerCommand(new SetQuestChestCommand());
        this.getCommandRegistry().registerCommand(new DevPanelCommand());
        this.getCommandRegistry().registerCommand(new BloodSpikesCommand());
        this.getCommandRegistry().registerCommand(new BindingTestCommand());
        this.getCommandRegistry().registerCommand(new HolyTrackCommand());
        this.getCommandRegistry().registerCommand(new MapOpenCommand());
        this.getCommandRegistry().registerCommand(new PasteBlacksmithBuildCommand());
        this.getCommandRegistry().registerCommand(new PasteBlacksmithCommand());
        this.getCommandRegistry().registerCommand(new UndoBlacksmithCommand());
        this.getCommandRegistry().registerCommand(new PasteFarmerBuildCommand());
        this.getCommandRegistry().registerCommand(new PasteFarmerCommand());
        this.getCommandRegistry().registerCommand(new UndoFarmerCommand());
        this.getCommandRegistry().registerCommand(new ExampleCommand("example", "An example command"));
        this.getCodecRegistry(Interaction.CODEC).register(
                "game_door_use_interaction",
                GameDoorUseInteraction.class,
                GameDoorUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "orange_blob_block_use_interaction",
                OrangeBlobBlockUseInteraction.class,
                OrangeBlobBlockUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "flare_extraction_use_interaction",
                FlareExtractionUseInteraction.class,
                FlareExtractionUseInteraction.CODEC
        );

        this.getCodecRegistry(Interaction.CODEC).register(
                "party_manager_use_interaction",
                PartyManagerUseInteraction.class,
                PartyManagerUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "Party_Manager_Use_Interaction",
                PartyManagerUseInteraction.class,
                PartyManagerUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "base_plot_use_interaction",
                BasePlotUseInteraction.class,
                BasePlotUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "quest_board_use_interaction",
                QuestBoardUseInteraction.class,
                QuestBoardUseInteraction.CODEC
        );
        this.getCodecRegistry(Interaction.CODEC).register(
                "stash_chest_use_interaction",
                StashChestUseInteraction.class,
                StashChestUseInteraction.CODEC
        );
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, GameSessionManager.get()::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onStashPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onStashPlayerDisconnect);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, PlayerSpawnSafety::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, BaseHousingManager::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, FarmerAnimalRescueManager.get()::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, GameDoorInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, PartyHubInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, BasePlotInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, RescueObjectiveManager.get()::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, GameDoorInteractionHandler::onPlayerMouseButton);
        this.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, PartyHubInteractionHandler::onPlayerMouseButton);
        this.getEventRegistry().registerGlobal(PlaceBlockEvent.class, SpawnPointPlacementHandler::onPlaceBlock);
        this.getEventRegistry().registerGlobal(PlaceBlockEvent.class, RegionSpawnPlacementHandler::onPlaceBlock);

        this.getCommandRegistry().registerCommand(new RedCoreCommand());
        this.getCommandRegistry().registerCommand(new RedRadiusCommand());
        this.getCommandRegistry().registerCommand(new RedStartCommand());
        this.getCommandRegistry().registerCommand(new RedUndoCommand());
        this.getCommandRegistry().registerCommand(new RedUiCommand());
        this.getCommandRegistry().registerCommand(new RedSpeedCommand());
        this.getCommandRegistry().registerCommand(new RedLimitCommand());

    }

    private void onStashPlayerReady(PlayerReadyEvent event) {
        Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref = event.getPlayerRef();
        Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            PlayerStashManager.get().load(playerRef.getUuid());
        }
    }

    private void onStashPlayerDisconnect(PlayerDisconnectEvent event) {
        PlayerStashManager.get().unload(event.getPlayerRef().getUuid());
    }

    @Override
    protected void start() {
        GameSessionManager.get().cleanupOrphanRunWorldsOnStartup();
        applyDefaultWorldWeatherOverride();
        NpcDefinitionRegistry.get().initialize();
        NpcProgressManager.get().initialize();
        RunRescueRegistry.get().initialize();
        QuestDefinitionRegistry.get().initialize();
        QuestProgressManager.get().initialize();
        QuestFlagManager.get().initialize();
        QuestChestConfigManager.get().initialize();
        QuestChestPositionManager.get().initialize();
        LootTableRegistry.get().initialize();
        FarmerAnimalRescueManager.get().initialize();
        RegionSpawnManager.get().initialize();
        RooterConfig.get().initialize();
        RooterManManager.get().initialize();
        NpcUnifiedRegistry.get().initialize();
        NpcStateManager.get().initialize();
        NpcAdminService.get().initialize();
        RedWoolDamageSystem.setHazardFogEnabled(GameFlowConfigManager.get().isHazardFogWeatherEnabled());
        BlacksmithSharedMarkerManager.sync();
        this.mapReplacementPacketController = new MapReplacementPacketController(this);
        this.mapReplacementPacketController.register();
        this.mapReplacementPacketController.validateCustomMapAssetRegistration();
        this.spawnMarkerPacketController = new SpawnMarkerPacketController(this);
        this.spawnMarkerPacketController.register();
        this.questChestMarkerPacketController = new QuestChestMarkerPacketController(this);
        this.questChestMarkerPacketController.register();
        this.runChunkSelectionMapController = new RunChunkSelectionMapController(this);
        this.runChunkSelectionMapController.register();
        this.farmerAnimalMarkerPacketController = new FarmerAnimalMarkerPacketController(this);
        this.farmerAnimalMarkerPacketController.register();
        this.rescueInteractionPacketWatcher = new RescueInteractionPacketWatcher();
        this.rescueInteractionPacketWatcher.register();
        this.runClientReadyPacketWatcher = new RunClientReadyPacketWatcher();
        this.runClientReadyPacketWatcher.register();
        java.util.Set<LifeEssenceContainerKey> processedLifeEssenceContainers = LootChestRuntime.get().processedChests();
        this.getEntityStoreRegistry().registerSystem(new LifeEssenceChestOpenSystem(processedLifeEssenceContainers));
        this.getEntityStoreRegistry().registerSystem(new LootHarvestBlockSystem());
        this.getEntityStoreRegistry().registerSystem(new LifeEssenceChestBreakCleanupSystem(processedLifeEssenceContainers));
        this.getEntityStoreRegistry().registerSystem(new GameRunDirectorSystem());
        this.getEntityStoreRegistry().registerSystem(new RescueObjectiveSystem());
        this.getEntityStoreRegistry().registerSystem(new FarmerAnimalRescueSystem());
        this.getEntityStoreRegistry().registerSystem(new RegionSpawnSystem());
        this.getEntityStoreRegistry().registerSystem(new RunStartCameraSystem());
        this.getEntityStoreRegistry().registerSystem(new RunStartMovementLockSystem());
        this.getEntityStoreRegistry().registerSystem(new RunChunkMapRefreshTickingSystem());
        this.getEntityStoreRegistry().registerSystem(new RunActiveMapRefreshSystem());
        this.getEntityStoreRegistry().registerSystem(new RunPinnedChunkKeepAliveSystem());
        this.getEntityStoreRegistry().registerSystem(new RunControlWorldSafetySystem());
        this.getEntityStoreRegistry().registerSystem(new RunDeathHandler());
        this.getEntityStoreRegistry().registerSystem(new BlightBeastKillTrackerSystem());
        this.getEntityStoreRegistry().registerSystem(new MobDropSystem());
        this.getEntityStoreRegistry().registerSystem(new BaseHousingSystem());
        this.getEntityStoreRegistry().registerSystem(new BlacksmithPrefabBuildSystem());
        this.getEntityStoreRegistry().registerSystem(new FarmerPrefabBuildSystem());
        this.getEntityStoreRegistry().registerSystem(new TransparentLightProtectionSystem());
        this.getEntityStoreRegistry().registerSystem(new DevDebugHudSystem());
        this.getEntityStoreRegistry().registerSystem(new OrangeBlobBlockSystem());
        this.getEntityStoreRegistry().registerSystem(new FlareExtractionSystem());

        try {
            this.getChunkStoreRegistry().registerSystem(new CrimsonCoreDetectionSystem());
            this.getChunkStoreRegistry().registerSystem(new GameDoorBlockDetectionSystem());
            this.getChunkStoreRegistry().registerSystem(new SpawnPointDetectionSystem());
            this.getChunkStoreRegistry().registerSystem(new RegionSpawnMarkerDetectionSystem());
            this.getChunkStoreRegistry().registerSystem(new InfectionCoreDetectionSystem());
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
        this.getEntityStoreRegistry().registerSystem(new RooterMeleeDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new CrimsonWitchGroundTrapProjectileSystem());
        this.getEntityStoreRegistry().registerSystem(new CrimsonWitchHelperSummonSystem());
        this.getEntityStoreRegistry().registerSystem(new CrimsonWitchEnvironmentalPressureSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchHudSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchBindingSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchBloodSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchHolySystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchShockwaveSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchShockwaveDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchProjectileSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchHealZoneHealingSystem());
        this.getEntityStoreRegistry().registerSystem(new PotionBrewerWitchPoisonDamageSystem());
    }

    private void applyDefaultWorldWeatherOverride() {
        com.hypixel.hytale.server.core.universe.Universe universe = com.hypixel.hytale.server.core.universe.Universe.get();
        if (universe == null || universe.getDefaultWorld() == null) {
            return;
        }
        com.hypixel.hytale.server.core.universe.world.World defaultWorld = universe.getDefaultWorld();
        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store =
                defaultWorld.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        com.hypixel.hytale.builtin.weather.resources.WeatherResource weatherResource =
                store.getResource(com.hypixel.hytale.builtin.weather.resources.WeatherResource.getResourceType());
        if (weatherResource == null) {
            return;
        }
        weatherResource.setForcedWeather("Skylands_Rapids_Marsh_Cloudy_Medium");
    }

    @Override
    protected void shutdown() {
        PlayerStashManager.get().saveAll();

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
        if (this.runChunkSelectionMapController != null) {
            this.runChunkSelectionMapController.unregister();
            this.runChunkSelectionMapController = null;
        }
        if (this.farmerAnimalMarkerPacketController != null) {
            this.farmerAnimalMarkerPacketController.unregister();
            this.farmerAnimalMarkerPacketController = null;
        }

        for (com.hypixel.hytale.server.core.universe.PlayerRef player : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
            java.util.UUID worldId = player.getWorldUuid();
            if (worldId != null) {
                PotionBrewerWitchProjectileSystem.clearWorld(worldId);
                PotionBrewerWitchHealZoneRuntime.clearWorld(worldId);
            }
        }
    }

}
