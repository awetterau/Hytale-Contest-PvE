package dev.hytalemodding.commands.npc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.game.HubNpcManager;
import dev.hytalemodding.map.BlacksmithSharedMarkerManager;
import dev.hytalemodding.npc.NpcProgressManager;
import dev.hytalemodding.quest.QuestFlagManager;
import dev.hytalemodding.quest.QuestProgressManager;
import dev.hytalemodding.state.run.RescueObjectiveManager;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

public final class BlacksmithDevCommand extends AbstractPlayerCommand {
    private static final String BLACKSMITH = "blacksmith";
    private static final String BLACKSMITH_WORKSHOP_ASSIGNMENT = "blacksmith_workshop_fixed";
    private static final String BLACKSMITH_TEMPERED_FLAG = "blacksmith_tempered_unlocked";

    public BlacksmithDevCommand() {
        super("blacksmithdev", "Quickly set the blacksmith's progression state.");
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
            case "dump" -> context.sendMessage(Message.raw(describe()));
            case "rescued" -> {
                applyRescuedState();
                context.sendMessage(Message.raw("Blacksmith set to rescued with no workshop progress."));
            }
            case "moving" -> {
                applyMovingState();
                context.sendMessage(Message.raw("Blacksmith set to moving-to-workshop."));
            }
            case "working" -> {
                applyWorkingState();
                context.sendMessage(Message.raw("Blacksmith set to working with workshop built."));
            }
            case "reset" -> {
                applyResetState();
                context.sendMessage(Message.raw("Blacksmith reset to rescued with no workshop progress."));
            }
            case "unrescued" -> {
                applyUnrescuedState();
                context.sendMessage(Message.raw("Blacksmith fully reset to unrescued."));
            }
            default -> context.sendMessage(Message.raw("Usage: /blacksmithdev <dump|rescued|moving|working|reset|unrescued>"));
        }
    }

    @Nonnull
    private static String describe() {
        HubNpcManager.NpcData hub = HubNpcManager.get().getOrCreate(BLACKSMITH);
        NpcProgressManager.NpcProgress progress = NpcProgressManager.get().getOrCreate(BLACKSMITH);
        return "Blacksmith: rescued=" + progress.rescued
                + ", progressState=" + progress.state.name()
                + ", tier=" + progress.upgradeTier
                + ", level=" + progress.level
                + ", progressAssigned=" + (progress.assignedPlotId == null ? "<none>" : progress.assignedPlotId)
                + ", hubAssigned=" + (hub.assignedPlotId == null ? "<none>" : hub.assignedPlotId)
                + ", hubState=" + hub.state.name();
    }

    private static void applyRescuedState() {
        RescueObjectiveManager.get().setNpcRescued(BLACKSMITH, true);
        HubNpcManager.get().devAssign(BLACKSMITH, null, HubNpcManager.HubNpcState.WANDERING);
        NpcProgressManager.get().devOverwriteProgress(
                BLACKSMITH,
                true,
                NpcProgressManager.NpcProgressState.RESCUED_UNASSIGNED,
                null,
                1,
                0,
                List.of(),
                List.of()
        );
        BlacksmithSharedMarkerManager.sync();
    }

    private static void applyMovingState() {
        RescueObjectiveManager.get().setNpcRescued(BLACKSMITH, true);
        HubNpcManager.get().devAssign(BLACKSMITH, BLACKSMITH_WORKSHOP_ASSIGNMENT, HubNpcManager.HubNpcState.MOVING_TO_WORKSHOP);
        NpcProgressManager.get().devOverwriteProgress(
                BLACKSMITH,
                true,
                NpcProgressManager.NpcProgressState.MOVING_TO_HOME,
                BLACKSMITH_WORKSHOP_ASSIGNMENT,
                2,
                1,
                List.of("iron_sword"),
                List.of("basic_armor_trade")
        );
        BlacksmithSharedMarkerManager.sync();
    }

    private static void applyWorkingState() {
        RescueObjectiveManager.get().setNpcRescued(BLACKSMITH, true);
        HubNpcManager.get().devAssign(BLACKSMITH, BLACKSMITH_WORKSHOP_ASSIGNMENT, HubNpcManager.HubNpcState.WORKING);
        NpcProgressManager.get().devOverwriteProgress(
                BLACKSMITH,
                true,
                NpcProgressManager.NpcProgressState.ACTIVE_AT_HOME,
                BLACKSMITH_WORKSHOP_ASSIGNMENT,
                2,
                1,
                List.of("iron_sword"),
                List.of("basic_armor_trade")
        );
        BlacksmithSharedMarkerManager.sync();
    }

    private static void applyResetState() {
        applyRescuedState();
        QuestProgressManager.get().resetBySource("npc", BLACKSMITH);
        QuestFlagManager.get().removeFlag(BLACKSMITH_TEMPERED_FLAG);
    }

    private static void applyUnrescuedState() {
        RescueObjectiveManager.get().setNpcRescued(BLACKSMITH, false);
        HubNpcManager.get().devAssign(BLACKSMITH, null, HubNpcManager.HubNpcState.WANDERING);
        NpcProgressManager.get().devOverwriteProgress(
                BLACKSMITH,
                false,
                NpcProgressManager.NpcProgressState.UNRESCUED,
                null,
                1,
                0,
                List.of(),
                List.of()
        );
        BlacksmithSharedMarkerManager.sync();
    }

    @Nonnull
    private static List<String> getPositionalTokens(@Nonnull CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] split = input.trim().split("\\s+");
        java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
        for (int i = 1; i < split.length; i++) {
            String token = split[i];
            if (token == null || token.isBlank() || token.startsWith("--")) {
                continue;
            }
            tokens.add(token);
        }
        return List.copyOf(tokens);
    }
}
