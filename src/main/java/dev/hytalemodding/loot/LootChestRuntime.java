package dev.hytalemodding.loot;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LootChestRuntime {
    private static final LootChestRuntime INSTANCE = new LootChestRuntime();

    private final Set<LifeEssenceContainerKey> processedChests = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, QuestChestState> questChestByWorld = new ConcurrentHashMap<>();

    private LootChestRuntime() {
    }

    @Nonnull
    public static LootChestRuntime get() {
        return INSTANCE;
    }

    @Nonnull
    public Set<LifeEssenceContainerKey> processedChests() {
        return this.processedChests;
    }

    public void registerQuestChest(@Nonnull QuestChestState state) {
        this.questChestByWorld.put(state.worldUuid(), state);
    }

    @Nullable
    public QuestChestState getQuestChest(@Nonnull UUID worldUuid) {
        return this.questChestByWorld.get(worldUuid);
    }

    @Nullable
    public QuestChestState getQuestChest(@Nonnull UUID worldUuid, @Nonnull Vector3i pos) {
        QuestChestState state = this.questChestByWorld.get(worldUuid);
        if (state == null) {
            return null;
        }
        return state.matches(pos) ? state : null;
    }

    public void clearWorld(@Nonnull UUID worldUuid) {
        this.questChestByWorld.remove(worldUuid);
        this.processedChests.removeIf(key -> key.worldUuid().equals(worldUuid));
    }

    public record QuestChestState(
            @Nonnull UUID worldUuid,
            @Nonnull Vector3i position,
            @Nonnull String markerId,
            @Nonnull String markerName,
            @Nonnull String markerIcon,
            @Nonnull String markerColor,
            boolean markerEnabled
    ) {
        public QuestChestState {
            position = new Vector3i(position);
        }

        public boolean matches(@Nonnull Vector3i other) {
            return this.position.x == other.x && this.position.y == other.y && this.position.z == other.z;
        }
    }
}
