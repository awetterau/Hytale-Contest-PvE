package dev.hytalemodding.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class PotionBrewerWitchHud extends CustomUIHud {
    private static final int MAX_SLOTS = 4;
    private static final float HEALTH_ANIMATION_SPEED_PER_SECOND = 1.0f;

    private boolean visible;
    private float targetRedRatio = 1.0f;
    private float displayedRedRatio = 1.0f;
    private float targetPurpleRatio = 1.0f;
    private float displayedPurpleRatio = 1.0f;
    private float targetBlueRatio = 1.0f;
    private float displayedBlueRatio = 1.0f;
    private long lastAnimationAtMs;
    @Nonnull
    private List<String> chargeTypes = List.of();

    public PotionBrewerWitchHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    public void build(@Nonnull UICommandBuilder ui) {
        if (!this.visible) {
            return;
        }

        ui.append("PotionBrewerWitchHud.ui");
        advanceDisplayedHealthRatios();
        ui.set("#PhaseThreeBar.Value", this.displayedBlueRatio);
        ui.set("#PhaseTwoBar.Value", this.displayedPurpleRatio);
        ui.set("#HealthBar.Value", this.displayedRedRatio);

        for (int i = 0; i < MAX_SLOTS; i++) {
            int slot = i + 1;
            boolean active = i < this.chargeTypes.size();
            String potionType = active ? this.chargeTypes.get(i) : "";
            ui.set("#ChargeSlot" + slot + ".Visible", active);
            setSwatch(ui, slot, potionType);
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setHealth(float currentHealth, float maxHealth, @Nonnull String bossStage) {
        float clampedCurrent = Math.max(0.0f, currentHealth);
        float phaseHealth = Math.max(1.0f, maxHealth / 3.0f);
        if ("PHASE_THREE".equals(bossStage)) {
            this.targetRedRatio = 0.0f;
            this.targetPurpleRatio = 0.0f;
            this.targetBlueRatio = Math.max(0.0f, Math.min(1.0f, Math.min(phaseHealth, clampedCurrent) / phaseHealth));
        } else if ("PHASE_TWO".equals(bossStage)) {
            this.targetRedRatio = 0.0f;
            this.targetPurpleRatio = Math.max(0.0f, Math.min(1.0f, (clampedCurrent - phaseHealth) / phaseHealth));
            this.targetBlueRatio = 1.0f;
        } else {
            this.targetRedRatio = Math.max(0.0f, Math.min(1.0f, (clampedCurrent - (phaseHealth * 2.0f)) / phaseHealth));
            this.targetPurpleRatio = 1.0f;
            this.targetBlueRatio = 1.0f;
        }
        if (this.lastAnimationAtMs == 0L) {
            this.displayedRedRatio = this.targetRedRatio;
            this.displayedPurpleRatio = this.targetPurpleRatio;
            this.displayedBlueRatio = this.targetBlueRatio;
            return;
        }
        if (this.targetRedRatio >= this.displayedRedRatio) {
            this.displayedRedRatio = this.targetRedRatio;
        }
        if (this.targetPurpleRatio >= this.displayedPurpleRatio) {
            this.displayedPurpleRatio = this.targetPurpleRatio;
        }
        if (this.targetBlueRatio >= this.displayedBlueRatio) {
            this.displayedBlueRatio = this.targetBlueRatio;
        }
    }

    public void setCharges(@Nonnull List<String> chargeTypes) {
        this.chargeTypes = List.copyOf(new ArrayList<>(chargeTypes).subList(0, Math.min(MAX_SLOTS, chargeTypes.size())));
    }

    private void advanceDisplayedHealthRatios() {
        long now = System.currentTimeMillis();
        if (this.lastAnimationAtMs == 0L) {
            this.lastAnimationAtMs = now;
            return;
        }
        float dt = Math.max(0.0f, (now - this.lastAnimationAtMs) / 1000.0f);
        this.lastAnimationAtMs = now;
        if (this.displayedRedRatio > this.targetRedRatio) {
            float maxStep = HEALTH_ANIMATION_SPEED_PER_SECOND * dt;
            this.displayedRedRatio = Math.max(this.targetRedRatio, this.displayedRedRatio - maxStep);
        }
        if (this.displayedPurpleRatio > this.targetPurpleRatio) {
            float maxStep = HEALTH_ANIMATION_SPEED_PER_SECOND * dt;
            this.displayedPurpleRatio = Math.max(this.targetPurpleRatio, this.displayedPurpleRatio - maxStep);
        }
        if (this.displayedBlueRatio > this.targetBlueRatio) {
            float maxStep = HEALTH_ANIMATION_SPEED_PER_SECOND * dt;
            this.displayedBlueRatio = Math.max(this.targetBlueRatio, this.displayedBlueRatio - maxStep);
        }
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

}
