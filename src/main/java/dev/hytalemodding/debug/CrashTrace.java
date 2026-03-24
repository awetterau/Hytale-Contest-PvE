package dev.hytalemodding.debug;

import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CrashTrace {
    private static final boolean ENABLED = false;
    private static final long TRACE_WINDOW_MS = 60_000L;
    private static final ConcurrentHashMap<UUID, Long> TRACE_UNTIL_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> COUNTERS = new ConcurrentHashMap<>();

    private CrashTrace() {
    }

    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        if (!ENABLED || event.getPlayer() == null || event.getPlayer().getUuid() == null) {
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(event.getPlayer().getUuid());
        if (playerRef == null) {
            return;
        }
        String worldName = null;
        if (event.getPlayer().getReference() != null && event.getPlayer().getReference().getStore() != null) {
            World world = event.getPlayer().getReference().getStore().getExternalData().getWorld();
            if (world != null) {
                worldName = world.getName();
            }
        }
        beginJoinTrace(playerRef, worldName);
    }

    public static void beginJoinTrace(@Nonnull PlayerRef playerRef, @Nullable String worldName) {
        if (!ENABLED || playerRef.getUuid() == null) {
            return;
        }
        long until = System.currentTimeMillis() + TRACE_WINDOW_MS;
        TRACE_UNTIL_MS.put(playerRef.getUuid(), until);
        COUNTERS.clear();
        log(playerRef, "join", "begin world=" + safe(worldName));
    }

    public static boolean isTracing(@Nonnull PlayerRef playerRef) {
        if (!ENABLED || playerRef.getUuid() == null) {
            return false;
        }
        Long until = TRACE_UNTIL_MS.get(playerRef.getUuid());
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            TRACE_UNTIL_MS.remove(playerRef.getUuid());
            return false;
        }
        return true;
    }

    public static void log(@Nonnull PlayerRef playerRef, @Nonnull String source, @Nonnull String message) {
        if (!isTracing(playerRef)) {
            return;
        }
        System.out.println("[CrashTrace][" + source + "][" + playerRef.getUuid() + "] " + message);
    }

    public static void logLimited(@Nonnull PlayerRef playerRef, @Nonnull String key, int maxCount, @Nonnull String source, @Nonnull String message) {
        if (!isTracing(playerRef)) {
            return;
        }
        int next = COUNTERS.merge(playerRef.getUuid() + ":" + key, 1, Integer::sum);
        if (next <= maxCount) {
            log(playerRef, source, message + " (#" + next + ")");
        }
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null ? "<null>" : value;
    }
}
