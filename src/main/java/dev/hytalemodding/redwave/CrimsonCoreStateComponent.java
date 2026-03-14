package dev.hytalemodding.redwave;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

public class CrimsonCoreStateComponent implements Component<ChunkStore> {
    public static final BuilderCodec<CrimsonCoreStateComponent> CODEC = BuilderCodec.builder(CrimsonCoreStateComponent.class, CrimsonCoreStateComponent::new)
            .append(new KeyedCodec<>("Active", Codec.BOOLEAN), CrimsonCoreStateComponent::setActive, CrimsonCoreStateComponent::isActive)
            .add()
            .build();

    private static ComponentType<ChunkStore, CrimsonCoreStateComponent> TYPE;

    private boolean active = true;

    public boolean isActive() {
        return this.active;
    }

    private void setActive(boolean active) {
        this.active = active;
    }

    public static void setComponentType(ComponentType<ChunkStore, CrimsonCoreStateComponent> type) {
        TYPE = type;
    }

    public static ComponentType<ChunkStore, CrimsonCoreStateComponent> getComponentType() {
        return TYPE;
    }

    @Override
    public CrimsonCoreStateComponent clone() {
        CrimsonCoreStateComponent copy = new CrimsonCoreStateComponent();
        copy.active = this.active;
        return copy;
    }
}

