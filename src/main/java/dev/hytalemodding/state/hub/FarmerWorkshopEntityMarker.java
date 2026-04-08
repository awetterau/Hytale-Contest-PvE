package dev.hytalemodding.state.hub;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class FarmerWorkshopEntityMarker implements Component<EntityStore> {
    public static final FarmerWorkshopEntityMarker INSTANCE = new FarmerWorkshopEntityMarker();
    public static final BuilderCodec<FarmerWorkshopEntityMarker> CODEC =
            BuilderCodec.builder(FarmerWorkshopEntityMarker.class, () -> INSTANCE).build();

    private static ComponentType<EntityStore, FarmerWorkshopEntityMarker> TYPE;

    private FarmerWorkshopEntityMarker() {
    }

    public static void setComponentType(ComponentType<EntityStore, FarmerWorkshopEntityMarker> type) {
        TYPE = type;
    }

    public static ComponentType<EntityStore, FarmerWorkshopEntityMarker> getComponentType() {
        return TYPE;
    }

    @Override
    public FarmerWorkshopEntityMarker clone() {
        return INSTANCE;
    }
}
