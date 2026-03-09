package dev.hytalemodding;

import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.commands.*;
import dev.hytalemodding.events.ExampleEvent;
import dev.hytalemodding.game.GameDoorInteractionHandler;
import dev.hytalemodding.game.GameRunDirectorSystem;
import dev.hytalemodding.game.GameDoorUseInteraction;
import dev.hytalemodding.game.BaseHousingSystem;
import dev.hytalemodding.game.BasePlotInteractionHandler;
import dev.hytalemodding.game.BasePlotUseInteraction;
import dev.hytalemodding.game.RescueInteractionPacketWatcher;
import dev.hytalemodding.game.RescueObjectiveManager;
import dev.hytalemodding.game.RescueObjectiveSystem;
import dev.hytalemodding.redwave.RedWaveBlockSweepSystem;
import dev.hytalemodding.redwave.RedWoolNpcDamageSystem;
import dev.hytalemodding.redwave.RedWoolDamageSystem;


import javax.annotation.Nonnull;

public class ExamplePlugin extends JavaPlugin {
    private RescueInteractionPacketWatcher rescueInteractionPacketWatcher;

    public ExamplePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new GameStartCommand());
        this.getCommandRegistry().registerCommand(new GameEndCommand());
        this.getCommandRegistry().registerCommand(new GameResetCommand());
        this.getCommandRegistry().registerCommand(new SetRunSpawnCommand());
        this.getCommandRegistry().registerCommand(new SetBaseSpawnCommand());
        this.getCommandRegistry().registerCommand(new SetRescueSpawnCommand());
        this.getCommandRegistry().registerCommand(new BasePlotCommand());
        this.getCommandRegistry().registerCommand(new GameConfigCommand());
        this.getCommandRegistry().registerCommand(new BlacksmithCommand());
        this.getCommandRegistry().registerCommand(new SpawnBlacksmithCommand());
        this.getCommandRegistry().registerCommand(new NpcRolesCommand());
        this.getCommandRegistry().registerCommand(new RedPos1Command());
        this.getCommandRegistry().registerCommand(new RedPos2Command());
        this.getCommandRegistry().registerCommand(new RedStartCommand());
        this.getCommandRegistry().registerCommand(new RedUndoCommand());
        this.getCommandRegistry().registerCommand(new NpcSpawnCommand());
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
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ExampleEvent::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, GameDoorInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, BasePlotInteractionHandler::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, RescueObjectiveManager.get()::onPlayerInteract);
        this.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, GameDoorInteractionHandler::onPlayerMouseButton);
    }

    @Override
    protected void start() {
        this.rescueInteractionPacketWatcher = new RescueInteractionPacketWatcher();
        this.rescueInteractionPacketWatcher.register();
        this.getEntityStoreRegistry().registerSystem(new GameRunDirectorSystem());
        this.getEntityStoreRegistry().registerSystem(new RescueObjectiveSystem());
        this.getEntityStoreRegistry().registerSystem(new BaseHousingSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWaveBlockSweepSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWoolDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWoolNpcDamageSystem());
    }

}
