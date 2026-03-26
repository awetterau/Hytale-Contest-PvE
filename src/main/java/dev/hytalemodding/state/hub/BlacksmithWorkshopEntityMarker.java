package dev.hytalemodding.state.hub;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class BlacksmithWorkshopEntityMarker implements Component<EntityStore> {
    public static final BlacksmithWorkshopEntityMarker INSTANCE = new BlacksmithWorkshopEntityMarker();
    public static final BuilderCodec<BlacksmithWorkshopEntityMarker> CODEC =
            BuilderCodec.builder(BlacksmithWorkshopEntityMarker.class, () -> INSTANCE).build();

    private static ComponentType<EntityStore, BlacksmithWorkshopEntityMarker> TYPE;

    private BlacksmithWorkshopEntityMarker() {
    }

    public static void setComponentType(ComponentType<EntityStore, BlacksmithWorkshopEntityMarker> type) {
        TYPE = type;
    }

    public static ComponentType<EntityStore, BlacksmithWorkshopEntityMarker> getComponentType() {
        return TYPE;
    }

    @Override
    public BlacksmithWorkshopEntityMarker clone() {
        return INSTANCE;
    }
}
