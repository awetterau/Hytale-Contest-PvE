package dev.hytalemodding.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public class GameTimerHud extends CustomUIHud {
    private String timeString = "05:00";
    private String extractionTimeString = "";
    private boolean visible = false;
    private boolean extractionVisible = false;

    public GameTimerHud(PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    public void build(@Nonnull UICommandBuilder ui) {
        if (visible) {
            ui.append("GameTimerHud.ui");
            ui.set("#TimerLabel.Text", timeString);
            ui.set("#ExtractionTimerLabel.Visible", extractionVisible);
            ui.set("#ExtractionTimerLabel.Text", extractionTimeString);
        }
    }

    public void setTime(String timeString) {
        this.timeString = timeString;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setExtractionTimer(@Nonnull String timeString, boolean visible) {
        this.extractionTimeString = timeString;
        this.extractionVisible = visible;
    }
}

