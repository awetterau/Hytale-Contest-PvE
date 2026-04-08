package dev.hytalemodding.npc.config;

import dev.hytalemodding.npc.core.NpcDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcUnifiedRegistry {
    private static final NpcUnifiedRegistry INSTANCE = new NpcUnifiedRegistry();

    private final ConcurrentHashMap<String, NpcDefinition> byNpcKey = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private NpcUnifiedRegistry() {
    }

    @Nonnull
    public static NpcUnifiedRegistry get() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        reload();
    }

    public synchronized void reload() {
        this.byNpcKey.clear();
        for (NpcDefinition definition : NpcMigrationService.get().buildAllDefinitions()) {
            if (!definition.npcKey.isEmpty()) {
                this.byNpcKey.put(definition.npcKey, definition);
            }
        }
        System.out.println("[NpcUnifiedRegistry] Loaded unified NPC definitions: " + this.byNpcKey.size());
    }

    @Nullable
    public NpcDefinition getNpc(@Nonnull String npcKey) {
        initialize();
        return this.byNpcKey.get(NpcDefinition.normalizeKey(npcKey));
    }

    @Nonnull
    public Collection<NpcDefinition> getAll() {
        initialize();
        List<NpcDefinition> definitions = new ArrayList<>(this.byNpcKey.values());
        definitions.sort((a, b) -> a.npcKey.compareToIgnoreCase(b.npcKey));
        return Collections.unmodifiableList(definitions);
    }
}
