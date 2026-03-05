package dev.hytalemodding;

import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.commands.*;
import dev.hytalemodding.events.ExampleEvent;
import dev.hytalemodding.redwave.RedWaveBlockSweepSystem;
import dev.hytalemodding.redwave.RedWoolNpcDamageSystem;
import dev.hytalemodding.redwave.RedWoolDamageSystem;


import javax.annotation.Nonnull;

public class ExamplePlugin extends JavaPlugin {

    public ExamplePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new RedPos1Command());
        this.getCommandRegistry().registerCommand(new RedPos2Command());
        this.getCommandRegistry().registerCommand(new RedStartCommand());
        this.getCommandRegistry().registerCommand(new RedUndoCommand());
        this.getCommandRegistry().registerCommand(new NpcSpawnCommand());
        this.getCommandRegistry().registerCommand(new ExampleCommand("example", "An example command"));
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ExampleEvent::onPlayerReady);
    }

    @Override
    protected void start() {
        this.getEntityStoreRegistry().registerSystem(new RedWaveBlockSweepSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWoolDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new RedWoolNpcDamageSystem());
    }
}