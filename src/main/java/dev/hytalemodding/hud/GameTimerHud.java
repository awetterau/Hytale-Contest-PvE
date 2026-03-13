package dev.hytalemodding.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public class GameTimerHud extends CustomUIHud {
    private String timeString = "05:00";
    private boolean visible = false;

    public GameTimerHud(PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    public void build(@Nonnull UICommandBuilder ui) {
        if (visible) {
            ui.append("GameTimerHud.ui");
            ui.appendInline(
                    "#GameTimerHud",
                    "Label #TimerLabel {\n"
                            + "  Text: \"" + timeString + "\";\n"
                            + "  Style: (\n"
                            + "    FontSize: 24,\n"
                            + "    TextColor: #ffffff,\n"
                            + "    HorizontalAlignment: Center,\n"
                            + "    VerticalAlignment: Center,\n"
                            + "    RenderBold: true\n"
                            + "  );\n"
                            + "}"
            );
        }
    }

    public void setTime(String timeString) {
        this.timeString = timeString;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
