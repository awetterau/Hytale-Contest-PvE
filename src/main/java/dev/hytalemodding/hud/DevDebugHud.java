package dev.hytalemodding.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class DevDebugHud extends CustomUIHud {
    private boolean visible;
    @Nonnull
    private List<String> lines = List.of();

    public DevDebugHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    public void build(@Nonnull UICommandBuilder ui) {
        if (!this.visible) {
            return;
        }
        ui.append("DevDebugHud.ui");
        List<String> padded = new ArrayList<>(this.lines);
        while (padded.size() < 8) {
            padded.add("");
        }
        for (int i = 0; i < 8; i++) {
            ui.set("#Line" + (i + 1) + ".Text", padded.get(i));
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setLines(@Nonnull List<String> lines) {
        this.lines = List.copyOf(lines);
    }
}
