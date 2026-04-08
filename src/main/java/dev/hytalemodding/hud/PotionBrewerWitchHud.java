package dev.hytalemodding.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PotionBrewerWitchHud extends CustomUIHud {
    private static final int MAX_SLOTS = 3;

    private boolean visible;
    @Nonnull
    private String statusText = "";
    @Nonnull
    private String healthText = "";
    private float healthRatio;
    @Nonnull
    private List<String> chargeTypes = List.of();
    private String timeString = "05:00";

    public PotionBrewerWitchHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    public void build(@Nonnull UICommandBuilder ui) {
        if (!this.visible) {
            return;
        }

        ui.append("PotionBrewerWitchHud.ui");
        ui.set("#TimerLabel.Text", this.timeString);
        ui.set("#StatusLabel.Text", this.statusText);
        ui.set("#HealthLabel.Text", this.healthText);
        ui.set("#HealthBar.Value", this.healthRatio);

        for (int i = 0; i < MAX_SLOTS; i++) {
            int slot = i + 1;
            boolean active = i < this.chargeTypes.size();
            ui.set("#ChargeRow" + slot + ".Visible", active);
            String potionType = active ? this.chargeTypes.get(i) : "";
            ui.set("#ChargeText" + slot + ".Text", active ? displayName(potionType) : "");
            setSwatch(ui, slot, potionType);
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setTime(@Nonnull String timeString) {
        this.timeString = timeString;
    }

    public void setStatusText(@Nonnull String statusText) {
        this.statusText = statusText;
    }

    public void setHealth(float currentHealth, float maxHealth) {
        float clampedMax = Math.max(1.0f, maxHealth);
        float clampedCurrent = Math.max(0.0f, currentHealth);
        this.healthRatio = Math.max(0.0f, Math.min(1.0f, clampedCurrent / clampedMax));
        this.healthText = String.format(Locale.ROOT, "Health %.0f / %.0f", clampedCurrent, clampedMax);
    }

    public void setCharges(@Nonnull List<String> chargeTypes) {
        this.chargeTypes = List.copyOf(new ArrayList<>(chargeTypes).subList(0, Math.min(MAX_SLOTS, chargeTypes.size())));
    }

    private static void setSwatch(@Nonnull UICommandBuilder ui, int slot, @Nonnull String potionType) {
        String[] ids = {"Poison", "Shadow", "Blood", "Holy", "Binding", "Healing"};
        for (String id : ids) {
            ui.set("#Charge" + slot + id + ".Visible", false);
        }

        String swatchId = null;
        switch (potionType) {
            case "poison potion" -> swatchId = "Poison";
            case "shadow bolt" -> swatchId = "Shadow";
            case "blood potion" -> swatchId = "Blood";
            case "holy potion" -> swatchId = "Holy";
            case "binding potion" -> swatchId = "Binding";
            case "healing draught" -> swatchId = "Healing";
            default -> {
            }
        }
        if (swatchId != null) {
            ui.set("#Charge" + slot + swatchId + ".Visible", true);
        }
    }

    @Nonnull
    private static String displayName(@Nonnull String potionType) {
        return switch (potionType) {
            case "poison potion" -> "Poison Potion";
            case "shadow bolt" -> "Shadow Bolt";
            case "blood potion" -> "Blood Potion";
            case "holy potion" -> "Holy Potion";
            case "binding potion" -> "Binding Potion";
            case "healing draught" -> "Healing Draught";
            default -> potionType;
        };
    }
}
