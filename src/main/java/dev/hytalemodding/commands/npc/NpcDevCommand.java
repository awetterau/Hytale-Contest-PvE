package dev.hytalemodding.commands.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.domain.housing.BaseHousingManager;
import dev.hytalemodding.game.DevDebugManager;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.state.run.RescueObjectiveManager;
import dev.hytalemodding.npc.NpcProgressManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NpcDevCommand extends AbstractPlayerCommand {
    public NpcDevCommand() {
        super("npcdev", "NPC development tools");
        this.setPermissionGroup(null);
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        List<String> args = getPositionalTokens(context);
        String action = args.isEmpty() ? "dump" : args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "hud" -> handleHud(context, playerRef, args);
            case "state" -> handleState(context, args);
            case "assign" -> handleAssign(context, args);
            case "unassign" -> handleUnassign(context, args);
            case "rescue" -> handleRescue(context, args);
            case "hubpos" -> handleHubPos(context, world, args);
            case "reset" -> {
                HubNpcManager.get().resetAll();
                context.sendMessage(Message.raw("Hub NPC dev state reset."));
            }
            case "dump" -> {
                context.sendMessage(Message.raw("NPC data:"));
                for (String line : HubNpcManager.get().describeAll()) {
                    context.sendMessage(Message.raw(" - " + line));
                }
            }
            default -> context.sendMessage(Message.raw("Usage: /npcdev <hud|state|assign|unassign|rescue|hubpos|dump|reset> ..."));
        }
    }

    private static void handleHud(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef, @Nonnull List<String> args) {
        String mode = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "toggle";
        boolean enabled;
        if ("on".equals(mode)) {
            DevDebugManager.get().setHudEnabled(playerRef.getUuid(), true);
            enabled = true;
        } else if ("off".equals(mode)) {
            DevDebugManager.get().setHudEnabled(playerRef.getUuid(), false);
            enabled = false;
        } else {
            enabled = DevDebugManager.get().toggleHud(playerRef.getUuid());
        }
        context.sendMessage(Message.raw("Dev HUD " + (enabled ? "enabled" : "disabled") + "."));
    }

    private static void handleState(@Nonnull CommandContext context, @Nonnull List<String> args) {
        if (args.size() < 3) {
            context.sendMessage(Message.raw("Usage: /npcdev state <profession> <wandering|moving|working>"));
            return;
        }
        String profession = args.get(1).toLowerCase(Locale.ROOT);
        HubNpcManager.HubNpcState state = parseState(args.get(2));
        if (state == null) {
            context.sendMessage(Message.raw("Invalid state. Use wandering, moving, or working."));
            return;
        }
        boolean ok = HubNpcManager.get().devSetState(profession, state);
        context.sendMessage(Message.raw(ok ? "Set " + profession + " state to " + state.name() + "." : "Failed to set state."));
    }

    private static void handleAssign(@Nonnull CommandContext context, @Nonnull List<String> args) {
        if (args.size() < 3) {
            context.sendMessage(Message.raw("Usage: /npcdev assign <profession> <plotId>"));
            return;
        }
        String profession = args.get(1).toLowerCase(Locale.ROOT);
        String plotId = args.get(2);
        BaseHousingManager.AssignmentResult result = BaseHousingManager.get().assignNpcToPlot(plotId, profession);
        context.sendMessage(Message.raw(result.message));
    }

    private static void handleUnassign(@Nonnull CommandContext context, @Nonnull List<String> args) {
        if (args.size() < 2) {
            context.sendMessage(Message.raw("Usage: /npcdev unassign <profession|plotId>"));
            return;
        }
        String raw = args.get(1);
        String plotId = raw;
        HubNpcManager.NpcData npc = HubNpcManager.get().getNpc(raw);
        if (npc != null && npc.assignedPlotId != null) {
            plotId = npc.assignedPlotId;
        }
        boolean ok = BaseHousingManager.get().clearAssignment(plotId);
        context.sendMessage(Message.raw(ok ? "Cleared assignment for " + plotId + "." : "Plot not found: " + plotId));
    }

    private static void handleRescue(@Nonnull CommandContext context, @Nonnull List<String> args) {
        if (args.size() < 3) {
            context.sendMessage(Message.raw("Usage: /npcdev rescue <npcKey> <true|false>"));
            return;
        }
        String npcKey = args.get(1).toLowerCase(Locale.ROOT);
        String raw = args.get(2).toLowerCase(Locale.ROOT);
        if (!"true".equals(raw) && !"false".equals(raw)) {
            context.sendMessage(Message.raw("Usage: /npcdev rescue <npcKey> <true|false>"));
            return;
        }
        boolean rescued = Boolean.parseBoolean(raw);
        NpcProgressManager.get().setNpcRescued(npcKey, rescued);
        RescueObjectiveManager.get().setNpcRescued(npcKey, rescued);
        context.sendMessage(Message.raw("Set rescued state for " + npcKey + " to " + rescued + "."));
    }

    private static void handleHubPos(@Nonnull CommandContext context, @Nonnull World world, @Nonnull List<String> args) {
        if (args.size() < 2) {
            context.sendMessage(Message.raw("Usage: /npcdev hubpos <npcKey>"));
            return;
        }
        String npcKey = args.get(1).toLowerCase(Locale.ROOT);
        var archetype = dev.hytalemodding.npc.NpcDefinitionRegistry.get().getArchetype(npcKey);
        if (archetype == null || archetype.hubRole == null || archetype.hubRole.isBlank()) {
            context.sendMessage(Message.raw("No hub role configured for " + npcKey + "."));
            return;
        }

        final Ref<EntityStore>[] found = new Ref[]{null};
        world.getEntityStore().getStore().forEachChunk(com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType(), (chunk, buffer) -> {
            if (found[0] != null) {
                return;
            }
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                var npc = chunk.getComponent(i, com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
                if (npc == null || !archetype.hubRole.equalsIgnoreCase(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef != null && npcRef.isValid()) {
                    found[0] = npcRef;
                    return;
                }
            }
        });

        if (found[0] == null || !found[0].isValid()) {
            context.sendMessage(Message.raw("No live hub NPC found for " + npcKey + "."));
            return;
        }
        TransformComponent transform = found[0].getStore().getComponent(found[0], TransformComponent.getComponentType());
        if (transform == null) {
            context.sendMessage(Message.raw("Hub NPC transform unavailable for " + npcKey + "."));
            return;
        }
        context.sendMessage(Message.raw("hubSpawn.x=" + transform.getPosition().getX()));
        context.sendMessage(Message.raw("hubSpawn.y=" + transform.getPosition().getY()));
        context.sendMessage(Message.raw("hubSpawn.z=" + transform.getPosition().getZ()));
        context.sendMessage(Message.raw("hubSpawn.pitch=" + transform.getRotation().getX()));
        context.sendMessage(Message.raw("hubSpawn.yaw=" + transform.getRotation().getY()));
        context.sendMessage(Message.raw("hubSpawn.roll=" + transform.getRotation().getZ()));
    }

    private static HubNpcManager.HubNpcState parseState(@Nonnull String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "wandering" -> HubNpcManager.HubNpcState.WANDERING;
            case "moving", "moving_to_workshop" -> HubNpcManager.HubNpcState.MOVING_TO_WORKSHOP;
            case "working" -> HubNpcManager.HubNpcState.WORKING;
            default -> null;
        };
    }

    @Nonnull
    private static List<String> getPositionalTokens(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] split = input.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < split.length; i++) {
            String token = split[i];
            if (token == null || token.isBlank() || token.startsWith("--")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}




