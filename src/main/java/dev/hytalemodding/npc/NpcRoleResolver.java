package dev.hytalemodding.npc;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class NpcRoleResolver {
    private static final String[] FALLBACK_ROLES = {
            "Test_Interaction_Follow",
            "Kweebec_Sapling",
            "Tuluk_Fisherman",
            "Kweebec_Rootling",
            "Kweebec_Merchant"
    };

    private NpcRoleResolver() {
    }

    @Nullable
    public static String resolveSpawnableRole(@Nonnull String preferredRole) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        String[] candidates = new String[FALLBACK_ROLES.length + 1];
        candidates[0] = preferredRole;
        System.arraycopy(FALLBACK_ROLES, 0, candidates, 1, FALLBACK_ROLES.length);

        for (String roleName : candidates) {
            int roleIndex = npcPlugin.getIndex(roleName);
            BuilderInfo roleInfo = npcPlugin.getRoleBuilderInfo(roleIndex);
            if (roleInfo != null && roleInfo.getBuilder().isSpawnable()) {
                return roleName;
            }
        }
        return null;
    }
}


