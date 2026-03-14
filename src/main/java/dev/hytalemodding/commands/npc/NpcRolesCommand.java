package dev.hytalemodding.commands.npc;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class NpcRolesCommand extends AbstractCommand {
    private static final String TARGET_ROLE = "Blacksmith_Escort_Base";
    @Nonnull
    private final OptionalArg<String> filterArg = this.withOptionalArg("filter", "Optional contains filter", ArgTypes.STRING);

    public NpcRolesCommand() {
        super("npcroles", "List loaded spawnable NPC role names.");
        this.setPermissionGroup(null);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        String filter = this.filterArg.provided(context) ? this.filterArg.get(context).toLowerCase() : "";
        NPCPlugin npc = NPCPlugin.get();
        List<String> roles = npc.getRoleTemplateNames(false);
        long spawnableCount = roles.stream().filter(role -> {
            int roleIndex = npc.getIndex(role);
            BuilderInfo info = npc.getRoleBuilderInfo(roleIndex);
            return info != null && info.getBuilder().isSpawnable();
        }).count();
        int targetIndex = npc.getIndex(TARGET_ROLE);
        BuilderInfo targetInfo = npc.getRoleBuilderInfo(targetIndex);
        boolean targetLoaded = targetInfo != null;
        boolean targetSpawnable = targetLoaded && targetInfo.getBuilder().isSpawnable();

        List<String> filtered = roles.stream()
                .filter(name -> filter.isEmpty() || name.toLowerCase().contains(filter))
                .limit(50)
                .collect(Collectors.toList());

        context.sendMessage(Message.raw("Loaded roles total=" + roles.size() + ", spawnable=" + spawnableCount + " (showing up to 50 matches)."));
        context.sendMessage(Message.raw("Target role '" + TARGET_ROLE + "': loaded=" + targetLoaded + ", spawnable=" + targetSpawnable + ", index=" + targetIndex));
        for (String role : filtered) {
            context.sendMessage(Message.raw(" - " + role));
        }
        if (filtered.isEmpty()) {
            context.sendMessage(Message.raw("No roles matched filter: '" + filter + "'"));
        }

        return CompletableFuture.completedFuture(null);
    }
}



