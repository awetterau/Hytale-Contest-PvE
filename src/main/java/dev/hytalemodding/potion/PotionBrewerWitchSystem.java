package dev.hytalemodding.potion;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PotionBrewerWitchSystem extends TickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, NPCEntity> NPC = NPCEntity.getComponentType();
    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final ComponentType<EntityStore, EntityStatMap> STATS = EntityStatMap.getComponentType();
    private static final ComponentType<EntityStore, EffectControllerComponent> EFFECTS = EffectControllerComponent.getComponentType();

    private static final String ROLE_NAME = "Potion_Brewer_Witch";
    private static final String CAULDRON_BLOCK_ID = "Alchemy_Cauldron";
    private static final String COMBAT_PREFIX = "Combat";
    private static final String COMBAT_DEFAULT = "Combat.Default";
    private static final String COMBAT_RETURN = "Combat.ReturnToCauldron";
    private static final String COMBAT_BREWING = "Combat.Brewing";
    private static final String DEFAULT_SUBSTATE = "Default";
    private static final String RETURN_SUBSTATE = "ReturnToCauldron";
    private static final String BREWING_SUBSTATE = "Brewing";
    private static final String LOADED_SUBSTATE_PREFIX = "Loaded_";
    private static final String COMBAT_LOADED_PREFIX = "Combat." + LOADED_SUBSTATE_PREFIX;
    private static final String HEALING_DRAUGHT = "healing draught";
    private static final String POISON_POTION = "poison potion";
    private static final String SHADOW_BOLT = "shadow bolt";
    private static final String BLOOD_POTION = "blood potion";
    private static final String HOLY_POTION = "holy potion";
    private static final String BINDING_POTION = "binding potion";
    private static final String HEALING_SYMBOL = "H";
    private static final String POISON_SYMBOL = "P";
    private static final String SHADOW_SYMBOL = "S";
    private static final String BLOOD_SYMBOL = "B";
    private static final String HOLY_SYMBOL = "L";
    private static final String BINDING_SYMBOL = "N";
    private static final String HEALING_ROOT_INTERACTION = "Potion_Brewer_Witch_Heal_Self_Attack";
    private static final String HEAL_THROWN_ROOT_INTERACTION = "Potion_Brewer_Witch_Heal_Thrown_Attack";
    private static final String POISON_ROOT_INTERACTION = "Potion_Brewer_Witch_Poison_Potion_Attack";
    private static final String SHADOW_ROOT_INTERACTION = "Potion_Brewer_Witch_Shadow_Bolt_Attack";
    private static final String BLOOD_ROOT_INTERACTION = "Potion_Brewer_Witch_Blood_Potion_Attack";
    private static final String HOLY_ROOT_INTERACTION = "Potion_Brewer_Witch_Holy_Potion_Attack";
    private static final String BINDING_ROOT_INTERACTION = "Potion_Brewer_Witch_Binding_Potion_Attack";
    private static final String COMBAT_SHADOW_BOLT = LOADED_SUBSTATE_PREFIX + SHADOW_SYMBOL;
    private static final String COMBAT_SHADOW_ASSASSIN = LOADED_SUBSTATE_PREFIX + SHADOW_SYMBOL + "_Assassin";
    private static final String SHADOW_HASTE_EFFECT_ID = "Potion_Brewer_Witch_Shadow_Haste";
    private static final String SHADOW_STUN_EFFECT_ID = "Potion_Brewer_Witch_Shadow_Stun";
    private static final String REACTIVE_POISON_EFFECT_ID = "Potion_Brewer_Witch_Reactive_Poison";
    private static final String AUTO_BREW_SUBSTATE = "__AUTO_BREW__";
    private static final int PHASE_ONE_BREW_CHARGES = 3;
    private static final int PHASE_TWO_BREW_CHARGES = 4;
    private static final int PHASE_THREE_BREW_CHARGES = 4;
    private static final long PHASE_ONE_BREW_DURATION_MS = 6000L;
    private static final long PHASE_TWO_BREW_DURATION_MS = 6000L;
    private static final long PHASE_THREE_BREW_DURATION_MS = 0L;
    private static final long PHASE_ONE_ACTION_RECOVERY_MS = 1000L;
    private static final long PHASE_TWO_ACTION_RECOVERY_MS = 450L;
    private static final long PHASE_THREE_ACTION_RECOVERY_MS = 150L;
    private static final long PHASE_ONE_EMPTY_ACTION_RECOVERY_MS = 1000L;
    private static final long PHASE_TWO_EMPTY_ACTION_RECOVERY_MS = 450L;
    private static final long PHASE_THREE_EMPTY_ACTION_RECOVERY_MS = 150L;
    private static final long DEBUG_POSITION_INTERVAL_MS = 2000L;
    private static final long SELF_POISON_DURATION_MS = 4000L;
    private static final long SHADOW_BUFF_DURATION_MS = 6500L;
    private static final long SHADOW_ASSASSIN_DURATION_MS = 6500L;
    private static final long SHADOW_ASSASSIN_APPROACH_DELAY_MS = 1000L;
    private static final boolean ENABLE_SHADOW_ASSASSIN_APPROACH = false;
    private static final double SHADOW_ASSASSIN_APPROACH_DISTANCE = 0.8d;
    private static final double SELF_POISON_TRIGGER_RANGE = 5.0d;
    private static final double SHADOW_STUN_RANGE = 16.0d;
    private static final double SHADOW_REPOSITION_DISTANCE = 10.0d;
    private static final double CLOSE_RANGE_SELF_USE_DISTANCE = 5.0d;
    private static final double DESPAWN_NO_TARGET_RANGE = 96.0d;
    private static final float BREWING_LOOK_DOWN_PITCH = -0.75f;
    private static final float MAX_HEALTH = 1000.0f;
    private static final float PHASE_HEALTH = MAX_HEALTH / 3.0f;
    private static final float PHASE_TWO_TRIGGER_HEALTH = PHASE_HEALTH * 2.0f;
    private static final float PHASE_THREE_TRIGGER_HEALTH = PHASE_HEALTH;
    private static final float HEAL_FORMULA_BASE = 100.0f;
    private static final float HEAL_FORMULA_EXPONENT_BASE = 1.5f;
    private static final float HEAL_FORMULA_FLOOR = 1.0f;
    private static final float DRINK_HEALTH_THRESHOLD_PERCENT = 0.6f;
    private static final int HEALTH_STAT_INDEX = DefaultEntityStatTypes.getHealth();
    private static final Random RANDOM = new Random();

    private static final Map<UUID, Map<UUID, LoadoutState>> LOADOUTS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, Phase>> PHASES_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, PendingSuppression>> PENDING_SUPPRESSIONS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Map<UUID, HudSnapshot>> HUD_SNAPSHOTS_BY_WORLD = new HashMap<>();
    private static final Map<UUID, Long> LAST_DUPLICATE_WITCH_LOG_BY_WORLD = new HashMap<>();
    private static final Set<UUID> PENDING_DUPLICATE_WITCH_DESPAWNS = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Map<UUID, Runtime>> runtimesByWorld = new HashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        UUID worldId = world.getWorldConfig().getUuid();
        ComponentType<EntityStore, InteractionManager> interactionManagerType = InteractionModule.get().getInteractionManagerComponent();
        List<PlayerSnapshot> players = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<StateCmd> stateCmds = new ArrayList<>();
        List<EffectCmd> effectCmds = new ArrayList<>();
        List<RotationCmd> rotationCmds = new ArrayList<>();
        List<StealthCmd> stealthCmds = new ArrayList<>();
        List<TeleportCmd> teleportCmds = new ArrayList<>();
        List<HealCmd> healCmds = new ArrayList<>();
        List<DespawnCmd> despawnCmds = new ArrayList<>();
        Map<UUID, Runtime> runtimes;

        store.forEachChunk(PLAYER, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
                UUID playerId = getEntityUuid(store, playerRef);
                if (transform == null || playerRef == null || !playerRef.isValid() || playerId == null) {
                    continue;
                }
                players.add(new PlayerSnapshot(playerRef, playerId, new Vector3d(transform.getPosition())));
            }
        });

        synchronized (this.runtimesByWorld) {
            runtimes = this.runtimesByWorld.computeIfAbsent(worldId, ignored -> new HashMap<>());
        }

        synchronized (runtimes) {
            UUID activeBossId = findPreferredWitchBossId(store);
            int duplicateWitches = countDuplicateWitches(store, activeBossId);
            if (duplicateWitches > 0) {
                logDuplicateWitchSummary(world, worldId, activeBossId, duplicateWitches);
            }
            store.forEachChunk(NPC, (chunk, ignored) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    NPCEntity npc = chunk.getComponent(i, NPC);
                    TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                    EntityStatMap stats = chunk.getComponent(i, STATS);
                    InteractionManager interactionManager = chunk.getComponent(i, interactionManagerType);
                    if (npc == null || transform == null || stats == null || !ROLE_NAME.equals(npc.getRoleName())) {
                        continue;
                    }

                    Ref<EntityStore> bossRef = chunk.getReferenceTo(i);
                    UUID bossId = getEntityUuid(store, bossRef);
                    if (bossRef == null || !bossRef.isValid() || bossId == null) {
                        continue;
                    }
                    String roleState = getNpcStateName(npc);
                    if (activeBossId != null && !bossId.equals(activeBossId)) {
                        clearLoadout(worldId, bossId);
                        clearPhase(worldId, bossId);
                        clearPendingSuppressions(worldId, bossId);
                        clearHudSnapshot(worldId, bossId);
                        queueDuplicateWitchDespawn(world, bossRef, bossId, roleState, activeBossId);
                        continue;
                    }

                    seen.add(bossId);
                    TransformComponent initialTransform = transform;
                    Runtime runtime = runtimes.computeIfAbsent(bossId, id -> createRuntime(world, initialTransform, stats, id, bossRef));
                    runtime.bossRef = bossRef;
                    runtime.currentPosition = new Vector3d(transform.getPosition());
                    if (shouldDespawnForNoPlayers(runtime, players)) {
                        queueBossCleanupAndDespawn(world, runtime, roleState, despawnCmds);
                        continue;
                    }
                    syncShadowStealth(world, store, npc, runtime, players, System.currentTimeMillis(), stealthCmds, teleportCmds, stateCmds);
                    updateHudSnapshot(worldId, runtime, roleState, stats);
                    syncBrewingPose(bossRef, transform, roleState, runtime, rotationCmds);
                    logRoleStateChange(world, bossId, runtime, roleState);
                    updateHealthMessages(world, runtime, transform, stats, players, effectCmds);
                    if (queueStageTransitionIfNeeded(world, bossRef, npc, roleState, stats, runtime, stealthCmds, stateCmds)) {
                        continue;
                    }
                    syncAbilityExecutions(world, bossId, interactionManager, runtime, roleState, players, stateCmds, effectCmds, healCmds);
                    drive(world, bossId, bossRef, stats, roleState, runtime, stateCmds, stealthCmds);
                    debugTick(world, transform, runtime, bossId, roleState);
                }
            });

            List<UUID> removed = new ArrayList<>();
            for (Map.Entry<UUID, Runtime> entry : runtimes.entrySet()) {
                if (cleanupIfMissing(world, entry.getKey(), entry.getValue(), seen)) {
                    removed.add(entry.getKey());
                }
            }
            for (UUID bossId : removed) {
                runtimes.remove(bossId);
            }
        }

        for (StateCmd cmd : stateCmds) {
            world.execute(() -> applyState(world, cmd));
        }
        for (EffectCmd cmd : effectCmds) {
            world.execute(() -> applyEffect(world, cmd));
        }
        for (RotationCmd cmd : rotationCmds) {
            world.execute(() -> applyRotation(world, cmd));
        }
        for (StealthCmd cmd : stealthCmds) {
            world.execute(() -> applyStealth(world, cmd));
        }
        for (TeleportCmd cmd : teleportCmds) {
            world.execute(() -> applyTeleport(world, cmd));
        }
        for (HealCmd cmd : healCmds) {
            world.execute(() -> applyHeal(world, cmd));
        }
        for (DespawnCmd cmd : despawnCmds) {
            world.execute(() -> applyBossCleanupAndDespawn(world, cmd));
        }

        synchronized (this.runtimesByWorld) {
            if (runtimes.isEmpty()) {
                this.runtimesByWorld.remove(worldId);
            }
        }
    }

    @Nonnull
    private Runtime createRuntime(
            @Nonnull World world,
            @Nonnull TransformComponent transform,
            @Nonnull EntityStatMap stats,
            @Nonnull UUID bossId,
            @Nonnull Ref<EntityStore> bossRef
    ) {
        Vector3d spawn = new Vector3d(transform.getPosition());
        Runtime runtime = new Runtime(bossId, bossRef, spawn);
        runtime.lastHealth = readHealth(stats);
        setPhase(world.getWorldConfig().getUuid(), bossId, runtime.phase);
        setLoadout(world.getWorldConfig().getUuid(), bossId, Collections.emptyList());
        debug(world, bossId, "createRuntime spawn=" + fmt(spawn));
        return runtime;
    }

    private void drive(
            @Nonnull World world,
            @Nonnull UUID bossId,
            @Nonnull Ref<EntityStore> bossRef,
            @Nonnull EntityStatMap stats,
            @Nullable String roleState,
            @Nonnull Runtime runtime,
            @Nonnull List<StateCmd> stateCmds,
            @Nonnull List<StealthCmd> stealthCmds
    ) {
        UUID worldId = world.getWorldConfig().getUuid();
        long now = System.currentTimeMillis();
        int charges = getRemainingCharges(worldId, bossId);
        boolean engaged = roleState != null && roleState.startsWith(COMBAT_PREFIX);

        if (!engaged) {
            if (runtime.phase == Phase.BREWING && runtime.brewAnnounced && runtime.brewingStartedAt > 0L) {
                if (!COMBAT_BREWING.equals(roleState)) {
                    stateCmds.add(new StateCmd(bossRef, bossId, BREWING_SUBSTATE));
                    debug(world, bossId, "restoreBrewingState roleState=" + roleState);
                }
                return;
            }
            clearCycleSelfBuffs(world, runtime, stealthCmds);
            runtime.returnQueued = false;
            runtime.brewAnnounced = false;
            runtime.brewingStartedAt = 0L;
            runtime.actionRecoveryUntil = 0L;
            runtime.pendingRecoverySubState = null;
            runtime.waitForChainClearDuringRecovery = true;
            removeActiveCauldron(world, runtime);
            if (runtime.phase != Phase.IDLE) {
                setPhase(world, runtime, Phase.IDLE, "combat_disengaged");
            }
            return;
        }

        if (runtime.pendingRecoverySubState != null) {
            if (runtime.waitForChainClearDuringRecovery && !runtime.activeAbilityChains.isEmpty()) {
                return;
            }
            if (runtime.shadowBuffUntil > now) {
                return;
            }
            if (now < runtime.actionRecoveryUntil) {
                return;
            }
            String nextSubState = runtime.pendingRecoverySubState;
            runtime.pendingRecoverySubState = null;
            runtime.waitForChainClearDuringRecovery = true;
            if (AUTO_BREW_SUBSTATE.equals(nextSubState)) {
                beginAutomaticBrew(world, runtime, stats, stateCmds, "phase_three_auto_brew");
            } else if (BREWING_SUBSTATE.equals(nextSubState)) {
                runtime.returnQueued = true;
                setPhase(world, runtime, Phase.BREWING, "post_action_recovery");
                stateCmds.add(new StateCmd(bossRef, bossId, nextSubState));
                debug(world, bossId, "recoverAction nextState=Combat." + nextSubState);
            } else {
                runtime.returnQueued = false;
                setPhase(world, runtime, Phase.LOADED, "post_action_recovery");
                stateCmds.add(new StateCmd(bossRef, bossId, nextSubState));
                debug(world, bossId, "recoverAction nextState=Combat." + nextSubState);
            }
            return;
        }

        if (COMBAT_RETURN.equals(roleState)) {
            runtime.returnQueued = false;
            runtime.brewAnnounced = false;
            runtime.brewingStartedAt = 0L;
            runtime.actionRecoveryUntil = 0L;
            runtime.pendingRecoverySubState = null;
            runtime.waitForChainClearDuringRecovery = true;
            removeActiveCauldron(world, runtime);
            if (runtime.phase != Phase.RETURNING) {
                setPhase(world, runtime, Phase.RETURNING, "entered_return_state");
            }
            return;
        }

        if (COMBAT_BREWING.equals(roleState)) {
            runtime.pendingRecoverySubState = null;
            runtime.actionRecoveryUntil = 0L;
            runtime.waitForChainClearDuringRecovery = true;
            runtime.returnQueued = false;
            if (!runtime.brewAnnounced) {
                clearCycleSelfBuffs(world, runtime, stealthCmds);
                placeActiveCauldron(world, runtime);
                List<String> brewed = brewLoadout(stats, runtime, getBrewCharges(runtime));
                setLoadout(worldId, bossId, brewed);
                runtime.brewAnnounced = true;
                runtime.brewingStartedAt = System.currentTimeMillis();
                setPhase(world, runtime, Phase.BREWING, "entered_brewing_state");
                broadcastToWorld(world, "Potion Brewer Witch slams down her cauldron and starts brewing.");
                broadcastToWorld(world, "Potion Brewer Witch brewed " + describeBrewList(brewed) + ".");
                debug(world, bossId, "brewLoadout charges=" + brewed);
            } else if (runtime.brewingStartedAt > 0L
                    && System.currentTimeMillis() - runtime.brewingStartedAt >= getBrewDurationMs(runtime)) {
                String loadedSubState = getLoadedSubState(worldId, bossId);
                if (loadedSubState != null) {
                    stateCmds.add(new StateCmd(bossRef, bossId, loadedSubState));
                    debug(world, bossId, "forceLeaveBrewing nextState=Combat." + loadedSubState + " charges=" + charges);
                }
                runtime.brewingStartedAt = 0L;
            }
            return;
        }

        if (isLoadedRoleState(roleState)) {
            if (runtime.phase == Phase.BREWING && runtime.brewAnnounced) {
                runtime.brewAnnounced = false;
                runtime.brewingStartedAt = 0L;
                removeActiveCauldron(world, runtime);
                setPhase(world, runtime, Phase.LOADED, "brewing_finished");
                broadcastToWorld(world, "Potion Brewer Witch snatches her cauldron back up and resumes fighting with " + charges + " brewed charges.");
            } else if (charges > 0 && runtime.phase != Phase.LOADED) {
                setPhase(world, runtime, Phase.LOADED, "default_with_charges");
            }

            if (runtime.phase == Phase.LOADED
                    && runtime.pendingRecoverySubState == null
                    && runtime.activeAbilityChains.isEmpty()
                    && isAtFullHealth(stats, runtime.bossStage)) {
                LoadoutState state = getLoadoutState(worldId, bossId);
                if (state != null && spendBrewedCharge(state, HEALING_DRAUGHT)) {
                    broadcastToWorld(world, "Potion Brewer Witch tosses aside a useless healing draught.");
                    debug(world, bossId, "discardHealingAtFullHealth remaining=" + state.brewedCharges);
                    queueStateForRemainingCharges(world, runtime, stateCmds, state, now, false);
                    return;
                }
            }

            if (charges <= 0 && !runtime.returnQueued) {
                if (runtime.bossStage == BossStage.PHASE_THREE) {
                    runtime.actionRecoveryUntil = now + getEmptyActionRecoveryMs(runtime);
                    runtime.pendingRecoverySubState = AUTO_BREW_SUBSTATE;
                    debug(world, bossId, "queueAutoBrew");
                    return;
                }
                runtime.returnQueued = true;
                setPhase(world, runtime, Phase.BREWING, "charges_empty");
                stateCmds.add(new StateCmd(bossRef, bossId, BREWING_SUBSTATE));
                broadcastToWorld(world, "Potion Brewer Witch pauses to set down her cauldron and brew again.");
                debug(world, bossId, "queueBrewing");
            }
            return;
        }

        if (charges <= 0 && !runtime.returnQueued) {
            if (runtime.bossStage == BossStage.PHASE_THREE) {
                runtime.actionRecoveryUntil = now + getEmptyActionRecoveryMs(runtime);
                runtime.pendingRecoverySubState = AUTO_BREW_SUBSTATE;
                debug(world, bossId, "queueAutoBrew fallback roleState=" + roleState);
                return;
            }
            runtime.returnQueued = true;
            setPhase(world, runtime, Phase.BREWING, "combat_state_without_charges");
            stateCmds.add(new StateCmd(bossRef, bossId, BREWING_SUBSTATE));
            debug(world, bossId, "queueBrewing fallback roleState=" + roleState);
            return;
        }

        if (charges > 0 && runtime.phase == Phase.LOADED && !isLoadedRoleState(roleState)) {
            String loadedSubState = getLoadedSubState(worldId, bossId);
            if (loadedSubState != null) {
                stateCmds.add(new StateCmd(bossRef, bossId, loadedSubState));
                debug(world, bossId, "restoreLoadedState roleState=" + roleState + " target=Combat." + loadedSubState);
            }
        }
    }

    private void syncAbilityExecutions(
            @Nonnull World world,
            @Nonnull UUID bossId,
            @Nullable InteractionManager interactionManager,
            @Nonnull Runtime runtime,
            @Nullable String roleState,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull List<StateCmd> stateCmds,
            @Nonnull List<EffectCmd> effectCmds,
            @Nonnull List<HealCmd> healCmds
    ) {
        if (interactionManager == null) {
            runtime.activeAbilityChains.clear();
            return;
        }

        Set<Integer> activeChainIds = new HashSet<>();
        for (InteractionChain chain : interactionManager.getChains().values()) {
            if (chain == null) {
                continue;
            }
            RootInteraction rootInteraction = chain.getInitialRootInteraction();
            String brewedAbility = rootInteraction == null ? null : abilityForRootInteraction(rootInteraction.getId());
            if (brewedAbility == null) {
                continue;
            }

            int chainId = chain.getChainId();
            activeChainIds.add(chainId);
            if (!runtime.activeAbilityChains.add(chainId)) {
                continue;
            }

            if (!isLoadedRoleState(roleState) || runtime.phase != Phase.LOADED) {
                debug(world, bossId, "skipAbilityExecution roleState=" + roleState + " ability=" + brewedAbility);
                continue;
            }

            LoadoutState state = getLoadoutState(world.getWorldConfig().getUuid(), bossId);
            if (state == null) {
                continue;
            }

            if (!spendBrewedCharge(state, brewedAbility)) {
                debug(world, bossId, "blockedUnbrewedAbility ability=" + brewedAbility + " remaining=" + state.brewedCharges);
                continue;
            }

            long now = System.currentTimeMillis();
            Vector3d modeOrigin = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
            Mode mode = chooseModeForAbility(brewedAbility, modeOrigin, players, runtime);
            if (HEALING_DRAUGHT.equals(brewedAbility)) {
                debug(world, bossId, "healingDraughtMode mode=" + mode + " health=" + runtime.lastHealth + "/" + MAX_HEALTH);
            }
            if (mode == Mode.SELF) {
                applySelfMode(world, runtime, brewedAbility, now, players, effectCmds);
            } else if (HEALING_DRAUGHT.equals(brewedAbility)) {
                PotionBrewerWitchProjectileSystem.queueThrownHealProjectile(world.getWorldConfig().getUuid(), bossId);
            } else if (BLOOD_POTION.equals(brewedAbility)) {
                PotionBrewerWitchBloodSystem.queueThrownBloodProjectile(world.getWorldConfig().getUuid(), bossId);
            } else if (HOLY_POTION.equals(brewedAbility)) {
                PlayerSnapshot target = findNearestPlayer(
                        runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition,
                        players,
                        24.0d
                );
                if (target != null) {
                    PotionBrewerWitchHolySystem.queueThrownHolyMarker(world.getWorldConfig().getUuid(), bossId, target.playerId);
                }
            } else if (BINDING_POTION.equals(brewedAbility)) {
                PotionBrewerWitchBindingSystem.queueThrownBindingProjectile(world.getWorldConfig().getUuid(), bossId);
            }

            if (runtime.shadowStunCharged && runtime.shadowBuffUntil > now && mode == Mode.THROWN && !HEALING_DRAUGHT.equals(brewedAbility)) {
                PlayerSnapshot target = findNearestPlayer(runtime.spawnPosition, players, SHADOW_STUN_RANGE);
                if (target != null) {
                    effectCmds.add(new EffectCmd(target.playerRef, target.playerId, SHADOW_STUN_EFFECT_ID));
                    runtime.shadowStunCharged = false;
                    broadcastToWorld(world, "Potion Brewer Witch's shadow brew crackles and briefly stuns its target.");
                    debug(world, bossId, "shadowEmpowerTriggered target=" + target.playerId);
                }
            }

            if (HEALING_DRAUGHT.equals(brewedAbility) && mode == Mode.SELF && runtime.bossRef != null && runtime.bossRef.isValid()) {
                float healAmount = (float) (HEAL_FORMULA_BASE / Math.pow(HEAL_FORMULA_EXPONENT_BASE, runtime.healCount));
                if (healAmount < HEAL_FORMULA_FLOOR) {
                    healAmount = HEAL_FORMULA_FLOOR;
                }
                healCmds.add(new HealCmd(runtime.bossRef, runtime.bossId, healAmount));
                runtime.healCount++;
                debug(world, bossId, "selfHeal amount=" + healAmount + " nextCount=" + runtime.healCount);
            }

            broadcastToWorld(world, "Potion Brewer Witch spends one brewed charge on " + brewedAbility + ".");
            debug(world, bossId, "consumeAbilityCharge ability=" + brewedAbility + " remaining=" + state.brewedCharges);
            queueStateForRemainingCharges(world, runtime, stateCmds, state, now, !HEALING_DRAUGHT.equals(brewedAbility));
        }

        runtime.activeAbilityChains.retainAll(activeChainIds);
    }

    private void queueStateForRemainingCharges(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nonnull List<StateCmd> stateCmds,
            @Nonnull LoadoutState state,
            long now,
            boolean waitForChainClear
    ) {
        if (runtime.bossRef == null || !runtime.bossRef.isValid()) {
            return;
        }
        stateCmds.add(new StateCmd(runtime.bossRef, runtime.bossId, DEFAULT_SUBSTATE));
        runtime.waitForChainClearDuringRecovery = waitForChainClear;

        if (state.brewedCharges.isEmpty()) {
            runtime.actionRecoveryUntil = now + getEmptyActionRecoveryMs(runtime);
            if (runtime.bossStage == BossStage.PHASE_THREE) {
                runtime.pendingRecoverySubState = AUTO_BREW_SUBSTATE;
                runtime.returnQueued = false;
                debug(world, runtime.bossId, "queueRecovery nextState=AutoBrew remaining=[]");
            } else {
                runtime.pendingRecoverySubState = BREWING_SUBSTATE;
                runtime.returnQueued = true;
                debug(world, runtime.bossId, "queueRecovery nextState=Combat.Brewing remaining=[]");
            }
            return;
        }

        String nextSubState = loadedSubStateForCharges(state.brewedCharges);
        if (nextSubState == null) {
            return;
        }
        runtime.actionRecoveryUntil = now + getActionRecoveryMs(runtime);
        runtime.pendingRecoverySubState = nextSubState;
        debug(world, runtime.bossId, "queueRecovery nextState=Combat." + nextSubState + " remaining=" + state.brewedCharges);
    }

    private void beginAutomaticBrew(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nonnull EntityStatMap stats,
            @Nonnull List<StateCmd> stateCmds,
            @Nonnull String reason
    ) {
        if (runtime.bossRef == null || !runtime.bossRef.isValid()) {
            return;
        }
        clearCycleSelfBuffs(world, runtime, new ArrayList<>());
        removeActiveCauldron(world, runtime);
        List<String> brewed = brewLoadout(stats, runtime, getBrewCharges(runtime));
        setLoadout(world.getWorldConfig().getUuid(), runtime.bossId, brewed);
        runtime.brewAnnounced = false;
        runtime.brewingStartedAt = 0L;
        runtime.pendingRecoverySubState = null;
        runtime.returnQueued = false;
        setPhase(world, runtime, Phase.LOADED, reason);
        String loadedSubState = getLoadedSubState(world.getWorldConfig().getUuid(), runtime.bossId);
        if (loadedSubState != null) {
            stateCmds.add(new StateCmd(runtime.bossRef, runtime.bossId, loadedSubState));
        }
        debug(world, runtime.bossId, "autoBrew charges=" + brewed + " reason=" + reason);
    }

    private boolean queueStageTransitionIfNeeded(
            @Nonnull World world,
            @Nonnull Ref<EntityStore> bossRef,
            @Nonnull NPCEntity npc,
            @Nullable String roleState,
            @Nonnull EntityStatMap stats,
            @Nonnull Runtime runtime,
            @Nonnull List<StealthCmd> stealthCmds,
            @Nonnull List<StateCmd> stateCmds
    ) {
        if (runtime.bossStage == BossStage.PHASE_THREE) {
            return false;
        }
        float currentHealth = readHealth(stats);
        if (currentHealth < 0.0f || npc.isDespawning()) {
            return false;
        }

        BossStage targetStage = null;
        if (runtime.bossStage == BossStage.PHASE_ONE && currentHealth <= PHASE_TWO_TRIGGER_HEALTH) {
            targetStage = BossStage.PHASE_TWO;
        } else if (runtime.bossStage == BossStage.PHASE_TWO && currentHealth <= PHASE_THREE_TRIGGER_HEALTH) {
            targetStage = BossStage.PHASE_THREE;
        }
        if (targetStage == null) {
            return false;
        }

        runtime.bossStage = targetStage;
        runtime.returnQueued = false;
        runtime.brewAnnounced = false;
        runtime.brewingStartedAt = 0L;
        runtime.actionRecoveryUntil = 0L;
        runtime.pendingRecoverySubState = null;
        runtime.waitForChainClearDuringRecovery = true;
        runtime.activeAbilityChains.clear();
        clearLoadout(world.getWorldConfig().getUuid(), runtime.bossId);
        clearCycleSelfBuffs(world, runtime, stealthCmds);
        removeActiveCauldron(world, runtime);
        if (targetStage == BossStage.PHASE_THREE) {
            beginAutomaticBrew(world, runtime, stats, stateCmds, "phase_three_transition");
            debug(world, runtime.bossId, "phaseThreeTransition queued");
        } else {
            setPhase(world, runtime, Phase.BREWING, "phase_two_transition");
            debug(world, runtime.bossId, "phaseTwoTransition queued");
            if (!COMBAT_BREWING.equals(roleState)) {
                stateCmds.add(new StateCmd(bossRef, runtime.bossId, BREWING_SUBSTATE));
            }
        }
        return true;
    }

    private void applyState(@Nonnull World world, @Nonnull StateCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(cmd.bossRef, NPC);
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return;
        }
        npc.getRole().getStateSupport().setSubState(cmd.subState);
        WorldSupport worldSupport = npc.getRole().getWorldSupport();
        if (worldSupport != null) {
            worldSupport.requestNewPath();
        }
        store.putComponent(cmd.bossRef, NPC, npc);
        debug(world, cmd.bossId, "applyState target=Combat." + cmd.subState + " actual=" + getNpcStateName(npc));
    }

    private void updateHealthMessages(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nonnull TransformComponent transform,
            @Nonnull EntityStatMap stats,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull List<EffectCmd> effectCmds
    ) {
        float currentHealth = readHealth(stats);
        if (runtime.lastHealth < 0.0f) {
            runtime.lastHealth = currentHealth;
            return;
        }
        float delta = currentHealth - runtime.lastHealth;
        if (Math.abs(delta) < 0.01f) {
            return;
        }
        if (delta < 0.0f) {
            long now = System.currentTimeMillis();
            if (runtime.selfPoisonUntil > now) {
                applyReactivePoison(world, transform.getPosition(), players, now, effectCmds);
            }
            broadcastToWorld(world, String.format(Locale.ROOT, "Potion Brewer Witch takes %.1f damage. Health: %.1f", -delta, currentHealth));
        } else {
            broadcastToWorld(world, String.format(Locale.ROOT, "Potion Brewer Witch heals %.1f health. Health: %.1f", delta, currentHealth));
        }
        runtime.lastHealth = currentHealth;
    }

    private boolean cleanupIfMissing(@Nonnull World world, @Nonnull UUID bossId, @Nonnull Runtime runtime, @Nonnull Set<UUID> seen) {
        if (seen.contains(bossId)) {
            runtime.missingTicks = 0;
            return false;
        }
        runtime.missingTicks++;
        if (runtime.missingTicks <= 10) {
            return false;
        }
        removeActiveCauldron(world, runtime);
        clearLoadout(world.getWorldConfig().getUuid(), bossId);
        clearPhase(world.getWorldConfig().getUuid(), bossId);
        clearPendingSuppressions(world.getWorldConfig().getUuid(), bossId);
        clearHudSnapshot(world.getWorldConfig().getUuid(), bossId);
        return true;
    }

    @Nullable
    public static HudSnapshot getNearestHudSnapshot(@Nonnull UUID worldId, @Nonnull Vector3d playerPosition) {
        synchronized (HUD_SNAPSHOTS_BY_WORLD) {
            Map<UUID, HudSnapshot> snapshots = HUD_SNAPSHOTS_BY_WORLD.get(worldId);
            if (snapshots == null || snapshots.isEmpty()) {
                return null;
            }
            HudSnapshot nearest = null;
            double nearestDistanceSq = Double.MAX_VALUE;
            for (HudSnapshot snapshot : snapshots.values()) {
                double dx = snapshot.position.getX() - playerPosition.getX();
                double dy = snapshot.position.getY() - playerPosition.getY();
                double dz = snapshot.position.getZ() - playerPosition.getZ();
                double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (nearest == null || distanceSq < nearestDistanceSq) {
                    nearest = snapshot;
                    nearestDistanceSq = distanceSq;
                }
            }
            return nearest;
        }
    }

    private void withRuntime(@Nonnull World world, @Nullable UUID bossId, @Nonnull RuntimeOp op) {
        if (bossId == null) {
            return;
        }
        synchronized (this.runtimesByWorld) {
            Map<UUID, Runtime> runtimes = this.runtimesByWorld.get(world.getWorldConfig().getUuid());
            if (runtimes == null) {
                return;
            }
            Runtime runtime = runtimes.get(bossId);
            if (runtime != null) {
                op.apply(runtime);
            }
        }
    }

    @Nullable
    private static UUID findPreferredWitchBossId(@Nonnull Store<EntityStore> store) {
        final UUID[] fallback = {null};
        final UUID[] combat = {null};
        store.forEachChunk(NPC, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPC);
                TransformComponent transform = chunk.getComponent(i, TRANSFORM);
                EntityStatMap stats = chunk.getComponent(i, STATS);
                if (npc == null || transform == null || stats == null || !ROLE_NAME.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                UUID bossId = getEntityUuid(store, ref);
                if (ref == null || !ref.isValid() || bossId == null || npc.isDespawning()) {
                    continue;
                }
                if (fallback[0] == null) {
                    fallback[0] = bossId;
                }
                String state = getNpcStateName(npc);
                if (combat[0] == null && state != null && state.startsWith(COMBAT_PREFIX)) {
                    combat[0] = bossId;
                }
            }
        });
        return combat[0] == null ? fallback[0] : combat[0];
    }

    private static int countDuplicateWitches(@Nonnull Store<EntityStore> store, @Nullable UUID activeBossId) {
        if (activeBossId == null) {
            return 0;
        }
        final int[] count = {0};
        store.forEachChunk(NPC, (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPC);
                if (npc == null || !ROLE_NAME.equals(npc.getRoleName())) {
                    continue;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                UUID bossId = getEntityUuid(store, ref);
                if (ref != null && ref.isValid() && bossId != null && !activeBossId.equals(bossId) && !npc.isDespawning()) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    private static void logDuplicateWitchSummary(
            @Nonnull World world,
            @Nonnull UUID worldId,
            @Nullable UUID activeBossId,
            int duplicateWitches
    ) {
        long now = System.currentTimeMillis();
        synchronized (LAST_DUPLICATE_WITCH_LOG_BY_WORLD) {
            long last = LAST_DUPLICATE_WITCH_LOG_BY_WORLD.getOrDefault(worldId, 0L);
            if (now - last < 1000L) {
                return;
            }
            LAST_DUPLICATE_WITCH_LOG_BY_WORLD.put(worldId, now);
        }
        System.out.println("[PotionWitch][" + world.getName() + "] duplicateWitches=" + duplicateWitches + " activeBoss=" + activeBossId);
    }

    private static void queueDuplicateWitchDespawn(
            @Nonnull World world,
            @Nonnull Ref<EntityStore> bossRef,
            @Nonnull UUID bossId,
            @Nullable String roleState,
            @Nullable UUID activeBossId
    ) {
        if (!PENDING_DUPLICATE_WITCH_DESPAWNS.add(bossId)) {
            return;
        }
        System.out.println("[PotionWitch][" + world.getName() + "][" + bossId + "] queueDuplicateDespawn activeBoss=" + activeBossId + " roleState=" + roleState);
        world.execute(() -> {
            try {
                if (!bossRef.isValid()) {
                    return;
                }
                Store<EntityStore> store = world.getEntityStore().getStore();
                NPCEntity npc = store.getComponent(bossRef, NPC);
                if (npc == null || npc.isDespawning() || !ROLE_NAME.equals(npc.getRoleName())) {
                    return;
                }
                npc.setToDespawn();
                store.putComponent(bossRef, NPC, npc);
                System.out.println("[PotionWitch][" + world.getName() + "][" + bossId + "] duplicateDespawnMarked");
            } finally {
                PENDING_DUPLICATE_WITCH_DESPAWNS.remove(bossId);
            }
        });
    }

    private void logRoleStateChange(@Nonnull World world, @Nonnull UUID bossId, @Nonnull Runtime runtime, @Nullable String roleState) {
        if (sameState(runtime.lastRoleState, roleState)) {
            return;
        }
        debug(world, bossId, "roleStateChange " + runtime.lastRoleState + " -> " + roleState + " charges=" + getRemainingCharges(world.getWorldConfig().getUuid(), bossId));
        runtime.lastRoleState = roleState;
    }

    private void setPhase(@Nonnull World world, @Nonnull Runtime runtime, @Nonnull Phase phase, @Nonnull String reason) {
        if (runtime.phase == phase) {
            return;
        }
        runtime.phase = phase;
        setPhase(world.getWorldConfig().getUuid(), runtime.bossId, phase);
        debug(world, runtime.bossId, "phaseChange -> " + phase + " reason=" + reason);
    }

    @Nullable
    private static String getNpcStateName(@Nonnull NPCEntity npc) {
        if (npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        return npc.getRole().getStateSupport().getStateName();
    }

    private static boolean sameState(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean sameRotation(@Nonnull Vector3f a, @Nonnull Vector3f b) {
        return Float.compare(a.getX(), b.getX()) == 0
                && Float.compare(a.getY(), b.getY()) == 0
                && Float.compare(a.getZ(), b.getZ()) == 0;
    }

    private static float readHealth(@Nullable EntityStatMap stats) {
        if (stats == null || HEALTH_STAT_INDEX < 0 || stats.get(HEALTH_STAT_INDEX) == null) {
            return -1.0f;
        }
        return stats.get(HEALTH_STAT_INDEX).get();
    }

    private static boolean isAtFullHealth(@Nullable EntityStatMap stats, @Nonnull BossStage bossStage) {
        float current = readHealth(stats);
        return current >= getHealCeilingForStage(bossStage) - 0.01f;
    }

    @Nonnull
    private static List<String> brewLoadout(@Nullable EntityStatMap stats, @Nonnull Runtime runtime, int brewCharges) {
        List<String> brewed = new ArrayList<>(brewCharges);
        float current = readHealth(stats);
        if (current > 0.0f && current < getHealCeilingForStage(runtime.bossStage)) {
            brewed.add(HEALING_DRAUGHT);
        }
        while (brewed.size() < brewCharges) {
            brewed.add(randomAttackBrew());
        }
        return brewed;
    }

    @Nonnull
    private static String randomAttackBrew() {
        int roll = RANDOM.nextInt(5);
        if (roll == 0) {
            return POISON_POTION;
        }
        if (roll == 1) {
            return SHADOW_BOLT;
        }
        if (roll == 2) {
            return BLOOD_POTION;
        }
        if (roll == 3) {
            return HOLY_POTION;
        }
        return BINDING_POTION;
    }

    @Nonnull
    private static String describeBrewList(@Nonnull List<String> brewed) {
        if (brewed.size() == 1) {
            return brewed.get(0);
        }
        if (brewed.size() == 2) {
            return brewed.get(0) + " and " + brewed.get(1);
        }
        return brewed.get(0) + ", " + brewed.get(1) + ", and " + brewed.get(2);
    }

    static boolean consumeAbilityCharge(@Nonnull UUID worldId, @Nonnull UUID bossId, @Nonnull String brewedAbility) {
        synchronized (LOADOUTS_BY_WORLD) {
            if (!isLoaded(worldId, bossId)) {
                return false;
            }
            LoadoutState state = getLoadoutState(worldId, bossId);
            if (state == null || state.brewedCharges.isEmpty()) {
                return false;
            }
            boolean removed = spendBrewedCharge(state, brewedAbility);
            System.out.println("[PotionWitch][" + bossId + "] consumeAbilityCharge ability=" + brewedAbility + " remaining=" + state.brewedCharges);
            return removed;
        }
    }

    static int getRemainingCharges(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (LOADOUTS_BY_WORLD) {
            LoadoutState state = getLoadoutState(worldId, bossId);
            return state == null ? 0 : state.brewedCharges.size();
        }
    }

    private static boolean isLoaded(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PHASES_BY_WORLD) {
            Map<UUID, Phase> phases = PHASES_BY_WORLD.get(worldId);
            return phases != null && phases.get(bossId) == Phase.LOADED;
        }
    }

    private static void setPhase(@Nonnull UUID worldId, @Nonnull UUID bossId, @Nonnull Phase phase) {
        synchronized (PHASES_BY_WORLD) {
            PHASES_BY_WORLD.computeIfAbsent(worldId, ignored -> new HashMap<>()).put(bossId, phase);
        }
    }

    private static void clearPhase(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PHASES_BY_WORLD) {
            Map<UUID, Phase> phases = PHASES_BY_WORLD.get(worldId);
            if (phases == null) {
                return;
            }
            phases.remove(bossId);
            if (phases.isEmpty()) {
                PHASES_BY_WORLD.remove(worldId);
            }
        }
    }

    private static void setLoadout(@Nonnull UUID worldId, @Nonnull UUID bossId, @Nonnull List<String> brewed) {
        synchronized (LOADOUTS_BY_WORLD) {
            LOADOUTS_BY_WORLD.computeIfAbsent(worldId, ignored -> new HashMap<>()).put(bossId, new LoadoutState(brewed));
        }
    }

    @Nullable
    private static String getLoadedSubState(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (LOADOUTS_BY_WORLD) {
            LoadoutState state = getLoadoutState(worldId, bossId);
            return state == null ? null : loadedSubStateForCharges(state.brewedCharges);
        }
    }

    private static boolean spendBrewedCharge(@Nonnull LoadoutState state, @Nonnull String brewedAbility) {
        return state.brewedCharges.removeFirstOccurrence(brewedAbility);
    }

    @Nullable
    private static String loadedSubStateForCharges(@Nonnull Deque<String> brewedCharges) {
        if (brewedCharges.isEmpty()) {
            return null;
        }
        StringBuilder key = new StringBuilder(LOADED_SUBSTATE_PREFIX);
        appendSymbolIfPresent(key, brewedCharges, HEALING_DRAUGHT, HEALING_SYMBOL);
        appendSymbolIfPresent(key, brewedCharges, POISON_POTION, POISON_SYMBOL);
        appendSymbolIfPresent(key, brewedCharges, SHADOW_BOLT, SHADOW_SYMBOL);
        appendSymbolIfPresent(key, brewedCharges, BLOOD_POTION, BLOOD_SYMBOL);
        appendSymbolIfPresent(key, brewedCharges, HOLY_POTION, HOLY_SYMBOL);
        appendSymbolIfPresent(key, brewedCharges, BINDING_POTION, BINDING_SYMBOL);
        return key.toString();
    }

    private static void appendSymbolIfPresent(
            @Nonnull StringBuilder key,
            @Nonnull Deque<String> brewedCharges,
            @Nonnull String brewedAbility,
            @Nonnull String symbol
    ) {
        for (String entry : brewedCharges) {
            if (brewedAbility.equals(entry)) {
                key.append(symbol);
                break;
            }
        }
    }

    private static boolean isLoadedRoleState(@Nullable String roleState) {
        return roleState != null && roleState.startsWith(COMBAT_LOADED_PREFIX);
    }

    @Nullable
    private static String abilityForRootInteraction(@Nullable String rootInteractionId) {
        if (rootInteractionId == null) {
            return null;
        }
        if (HEALING_ROOT_INTERACTION.equals(rootInteractionId) || HEAL_THROWN_ROOT_INTERACTION.equals(rootInteractionId)) {
            return HEALING_DRAUGHT;
        }
        if (POISON_ROOT_INTERACTION.equals(rootInteractionId)) {
            return POISON_POTION;
        }
        if (SHADOW_ROOT_INTERACTION.equals(rootInteractionId)) {
            return SHADOW_BOLT;
        }
        if (BLOOD_ROOT_INTERACTION.equals(rootInteractionId)) {
            return BLOOD_POTION;
        }
        if (HOLY_ROOT_INTERACTION.equals(rootInteractionId)) {
            return HOLY_POTION;
        }
        if (BINDING_ROOT_INTERACTION.equals(rootInteractionId)) {
            return BINDING_POTION;
        }
        return null;
    }

    private static void clearLoadout(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (LOADOUTS_BY_WORLD) {
            Map<UUID, LoadoutState> loadouts = LOADOUTS_BY_WORLD.get(worldId);
            if (loadouts == null) {
                return;
            }
            loadouts.remove(bossId);
            if (loadouts.isEmpty()) {
                LOADOUTS_BY_WORLD.remove(worldId);
            }
        }
    }

    private static void updateHudSnapshot(
            @Nonnull UUID worldId,
            @Nonnull Runtime runtime,
            @Nullable String roleState,
            @Nonnull EntityStatMap stats
    ) {
        Vector3d position = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
        List<String> charges = getBrewedCharges(worldId, runtime.bossId);
        HudSnapshot snapshot = new HudSnapshot(
                runtime.bossId,
                new Vector3d(position),
                readHealth(stats),
                MAX_HEALTH,
                runtime.bossStage.name(),
                runtime.phase.name(),
                roleState == null ? "" : roleState,
                charges
        );
        synchronized (HUD_SNAPSHOTS_BY_WORLD) {
            HUD_SNAPSHOTS_BY_WORLD.computeIfAbsent(worldId, ignored -> new HashMap<>()).put(runtime.bossId, snapshot);
        }
    }

    private static void clearHudSnapshot(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (HUD_SNAPSHOTS_BY_WORLD) {
            Map<UUID, HudSnapshot> snapshots = HUD_SNAPSHOTS_BY_WORLD.get(worldId);
            if (snapshots == null) {
                return;
            }
            snapshots.remove(bossId);
            if (snapshots.isEmpty()) {
                HUD_SNAPSHOTS_BY_WORLD.remove(worldId);
            }
        }
    }

    @Nonnull
    private static List<String> getBrewedCharges(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (LOADOUTS_BY_WORLD) {
            LoadoutState state = getLoadoutState(worldId, bossId);
            if (state == null || state.brewedCharges.isEmpty()) {
                return List.of();
            }
            return List.copyOf(state.brewedCharges);
        }
    }

    @Nullable
    private static LoadoutState getLoadoutState(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        Map<UUID, LoadoutState> loadouts = LOADOUTS_BY_WORLD.get(worldId);
        return loadouts == null ? null : loadouts.get(bossId);
    }

    @Nullable
    private static UUID getEntityUuid(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent component = store.getComponent(ref, UUIDComponent.getComponentType());
        return component == null ? null : component.getUuid();
    }

    private static void broadcastToWorld(@Nonnull World world, @Nonnull String text) {
    }

    private static int getBrewCharges(@Nonnull Runtime runtime) {
        return switch (runtime.bossStage) {
            case PHASE_THREE -> PHASE_THREE_BREW_CHARGES;
            case PHASE_TWO -> PHASE_TWO_BREW_CHARGES;
            default -> PHASE_ONE_BREW_CHARGES;
        };
    }

    private static long getBrewDurationMs(@Nonnull Runtime runtime) {
        return switch (runtime.bossStage) {
            case PHASE_THREE -> PHASE_THREE_BREW_DURATION_MS;
            case PHASE_TWO -> PHASE_TWO_BREW_DURATION_MS;
            default -> PHASE_ONE_BREW_DURATION_MS;
        };
    }

    private static long getActionRecoveryMs(@Nonnull Runtime runtime) {
        return switch (runtime.bossStage) {
            case PHASE_THREE -> PHASE_THREE_ACTION_RECOVERY_MS;
            case PHASE_TWO -> PHASE_TWO_ACTION_RECOVERY_MS;
            default -> PHASE_ONE_ACTION_RECOVERY_MS;
        };
    }

    private static long getEmptyActionRecoveryMs(@Nonnull Runtime runtime) {
        return switch (runtime.bossStage) {
            case PHASE_THREE -> PHASE_THREE_EMPTY_ACTION_RECOVERY_MS;
            case PHASE_TWO -> PHASE_TWO_EMPTY_ACTION_RECOVERY_MS;
            default -> PHASE_ONE_EMPTY_ACTION_RECOVERY_MS;
        };
    }

    public static float getConfiguredMaxHealth() {
        return MAX_HEALTH;
    }

    public static float getPhaseHealth() {
        return PHASE_HEALTH;
    }

    public static float getHealCeilingForCurrentHealth(float currentHealth) {
        if (currentHealth <= PHASE_HEALTH + 0.01f) {
            return PHASE_HEALTH;
        }
        if (currentHealth <= PHASE_TWO_TRIGGER_HEALTH + 0.01f) {
            return PHASE_TWO_TRIGGER_HEALTH;
        }
        return MAX_HEALTH;
    }

    public static float getHealCeilingForStageName(@Nullable String bossStageName) {
        if (bossStageName == null) {
            return MAX_HEALTH;
        }
        try {
            return getHealCeilingForStage(BossStage.valueOf(bossStageName));
        } catch (IllegalArgumentException ignored) {
            return MAX_HEALTH;
        }
    }

    private static float getHealCeilingForStage(@Nonnull BossStage bossStage) {
        return switch (bossStage) {
            case PHASE_THREE -> PHASE_HEALTH;
            case PHASE_TWO -> PHASE_TWO_TRIGGER_HEALTH;
            default -> MAX_HEALTH;
        };
    }

    private static void debugTick(@Nonnull World world, @Nonnull TransformComponent transform, @Nonnull Runtime runtime, @Nonnull UUID bossId, @Nullable String roleState) {
        long now = System.currentTimeMillis();
        if (now - runtime.lastDebugPositionAt < DEBUG_POSITION_INTERVAL_MS) {
            return;
        }
        runtime.lastDebugPositionAt = now;
        Vector3d brewAnchor = runtime.currentPosition;
        if (runtime.cauldronPlaced && runtime.cauldronX != null && runtime.cauldronY != null && runtime.cauldronZ != null) {
            brewAnchor = new Vector3d(runtime.cauldronX + 0.5d, runtime.cauldronY, runtime.cauldronZ + 0.5d);
        } else if (brewAnchor == null) {
            brewAnchor = runtime.spawnPosition;
        }
        debug(
                world,
                bossId,
                "positionTick phase=" + runtime.phase
                        + " roleState=" + roleState
                        + " pos=" + fmt(transform.getPosition())
                        + " distFromBrewAnchor=" + fmt(distance(transform.getPosition(), brewAnchor))
                        + " charges=" + getRemainingCharges(world.getWorldConfig().getUuid(), bossId)
        );
    }

    private static void debug(@Nonnull World world, @Nullable UUID bossId, @Nonnull String text) {
        System.out.println("[PotionWitch][" + world.getName() + "][" + (bossId == null ? "unknown" : bossId) + "] " + text);
    }

    private static boolean shouldDespawnForNoPlayers(
            @Nonnull Runtime runtime,
            @Nonnull List<PlayerSnapshot> players
    ) {
        if (players.isEmpty()) {
            return true;
        }
        Vector3d origin = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
        return findNearestPlayer(origin, players, DESPAWN_NO_TARGET_RANGE) == null;
    }

    private static void queueBossCleanupAndDespawn(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nullable String roleState,
            @Nonnull List<DespawnCmd> despawnCmds
    ) {
        if (runtime.despawnQueued || runtime.bossRef == null || !runtime.bossRef.isValid()) {
            return;
        }
        runtime.despawnQueued = true;
        runtime.returnQueued = false;
        runtime.brewAnnounced = false;
        runtime.brewingStartedAt = 0L;
        runtime.actionRecoveryUntil = 0L;
        runtime.pendingRecoverySubState = null;
        runtime.waitForChainClearDuringRecovery = true;
        runtime.activeAbilityChains.clear();
        despawnCmds.add(new DespawnCmd(runtime.bossRef, runtime.bossId));
        debug(world, runtime.bossId, "queueCleanupDespawn roleState=" + roleState);
    }

    private void applyBossCleanupAndDespawn(@Nonnull World world, @Nonnull DespawnCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        UUID worldId = world.getWorldConfig().getUuid();
        Runtime runtime = getRuntime(worldId, cmd.bossId);
        if (runtime != null) {
            removeActiveCauldron(world, runtime);
        }
        clearLoadout(worldId, cmd.bossId);
        clearPhase(worldId, cmd.bossId);
        clearPendingSuppressions(worldId, cmd.bossId);
        clearHudSnapshot(worldId, cmd.bossId);
        PotionBrewerWitchBindingSystem.clearWorld(worldId);
        PotionBrewerWitchBloodSystem.clearWorld(worldId);
        PotionBrewerWitchHolySystem.clearSelfHolyShield(worldId, cmd.bossId);
        PotionBrewerWitchHolySystem.clearWorld(worldId);
        PotionBrewerWitchProjectileSystem.clearWorld(worldId);
        PotionBrewerWitchPoisonRuntime.clearWorld(worldId);
        PotionBrewerWitchReactivePoisonRuntime.clearWorld(worldId);
        PotionBrewerWitchShockwaveRuntime.clearWorld(worldId);
        PotionBrewerWitchHealZoneRuntime.clearWorld(worldId);
        PotionBrewerWitchCrimsonPatchRuntime.clearWorld(worldId);

        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(cmd.bossRef, NPC);
        if (npc == null || npc.isDespawning() || !ROLE_NAME.equals(npc.getRoleName())) {
            return;
        }
        store.removeEntity(cmd.bossRef, RemoveReason.REMOVE);
        debug(world, cmd.bossId, "cleanupDespawn applied");
    }

    @Nullable
    private Runtime getRuntime(@Nonnull UUID worldId, @Nullable UUID bossId) {
        if (bossId == null) {
            return null;
        }
        synchronized (this.runtimesByWorld) {
            Map<UUID, Runtime> runtimes = this.runtimesByWorld.get(worldId);
            if (runtimes == null) {
                return null;
            }
            synchronized (runtimes) {
                return runtimes.get(bossId);
            }
        }
    }

    private void applyReactivePoison(
            @Nonnull World world,
            @Nonnull Vector3d bossPosition,
            @Nonnull List<PlayerSnapshot> players,
            long now,
            @Nonnull List<EffectCmd> effectCmds
    ) {
        UUID worldId = world.getWorldConfig().getUuid();
        for (PlayerSnapshot player : players) {
            if (distance(player.position, bossPosition) <= SELF_POISON_TRIGGER_RANGE) {
                if (PotionBrewerWitchReactivePoisonRuntime.markPoisoned(worldId, player.playerId, now + SELF_POISON_DURATION_MS)) {
                    effectCmds.add(new EffectCmd(player.playerRef, player.playerId, REACTIVE_POISON_EFFECT_ID));
                }
            }
        }
    }

    private void applySelfMode(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nonnull String brewedAbility,
            long now,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull List<EffectCmd> effectCmds
    ) {
        UUID worldId = world.getWorldConfig().getUuid();
        if (POISON_POTION.equals(brewedAbility)) {
            queueProjectileSuppression(worldId, runtime.bossId, brewedAbility);
            runtime.selfPoisonUntil = now + SELF_POISON_DURATION_MS;
            runtime.cycleSelfBuffAbility = brewedAbility;
            broadcastToWorld(world, "Potion Brewer Witch drinks a poison brew and turns spiteful.");
            debug(world, runtime.bossId, "selfUse poison until=" + runtime.selfPoisonUntil);
            return;
        }
        if (SHADOW_BOLT.equals(brewedAbility)) {
            queueProjectileSuppression(worldId, runtime.bossId, brewedAbility);
            // Clear any lingering effects before starting assassin mode
            world.execute(() -> PotionBrewerWitchBindingSystem.clearAllBindingZones(worldId, runtime.bossId));
            PotionBrewerWitchHolySystem.clearSelfHolyShield(worldId, runtime.bossId);

            runtime.shadowBuffUntil = now + SHADOW_ASSASSIN_DURATION_MS;
            runtime.shadowAssassinActive = ENABLE_SHADOW_ASSASSIN_APPROACH;
            runtime.shadowAssassinApproachAt = ENABLE_SHADOW_ASSASSIN_APPROACH ? now + SHADOW_ASSASSIN_APPROACH_DELAY_MS : 0L;
            runtime.shadowRepositionPending = false;
            runtime.cycleSelfBuffAbility = brewedAbility;
            if (runtime.bossRef != null && runtime.bossRef.isValid()) {
                effectCmds.add(new EffectCmd(runtime.bossRef, runtime.bossId, SHADOW_HASTE_EFFECT_ID));
            }
            if (ENABLE_SHADOW_ASSASSIN_APPROACH) {
                broadcastToWorld(world, "Potion Brewer Witch drinks a shadow brew and vanishes!");
                System.out.println("[PotionWitch][ASSASSIN] TRIGGERED for bossId=" + runtime.bossId + ", duration=" + SHADOW_ASSASSIN_DURATION_MS + "ms, approachAt=" + runtime.shadowAssassinApproachAt);
                debug(world, runtime.bossId, "selfUse shadowAssassin until=" + runtime.shadowBuffUntil);
            } else {
                broadcastToWorld(world, "Potion Brewer Witch drinks a shadow brew and surges with speed.");
                System.out.println("[PotionWitch][ASSASSIN] DISABLED approach/stealth for bossId=" + runtime.bossId + " hasteUntil=" + runtime.shadowBuffUntil);
                debug(world, runtime.bossId, "selfUse shadowHasteOnly until=" + runtime.shadowBuffUntil);
            }
            return;
        }
        if (BLOOD_POTION.equals(brewedAbility)) {
            queueProjectileSuppression(worldId, runtime.bossId, brewedAbility);
            Vector3d origin = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
            PlayerSnapshot target = findNearestPlayer(origin, players, 24.0d);
            PotionBrewerWitchBloodSystem.triggerSelfBloodBurst(
                    world,
                    runtime.bossId,
                    runtime.bossRef,
                    origin,
                    target == null ? null : target.position
            );
            debug(world, runtime.bossId, "selfUse blood origin=" + fmt(origin) + " target=" + (target == null ? "none" : fmt(target.position)));
            return;
        }
        if (HOLY_POTION.equals(brewedAbility)) {
            PotionBrewerWitchHolySystem.triggerSelfHolyShield(world, runtime.bossId, runtime.bossRef);
            runtime.cycleSelfBuffAbility = brewedAbility;
            debug(world, runtime.bossId, "selfUse holy shieldApplied=true");
            return;
        }
        if (BINDING_POTION.equals(brewedAbility)) {
            queueProjectileSuppression(worldId, runtime.bossId, brewedAbility);
            PotionBrewerWitchBindingSystem.triggerSelfBindingZone(
                    world,
                    runtime.bossId,
                    runtime.bossRef,
                    runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition
            );
            debug(world, runtime.bossId, "selfUse binding origin=" + fmt(runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition));
        }
    }

    private static void applyEffect(@Nonnull World world, @Nonnull EffectCmd cmd) {
        if (cmd.entityRef == null || !cmd.entityRef.isValid()) {
            return;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(cmd.effectId);
        if (effect == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        EffectControllerComponent controller = store.getComponent(cmd.entityRef, EFFECTS);
        if (controller == null) {
            controller = new EffectControllerComponent();
        } else {
            controller = controller.clone();
        }
        controller.addEffect(cmd.entityRef, effect, store);
        store.putComponent(cmd.entityRef, EFFECTS, controller);
        debug(world, cmd.entityId, "applyEffect " + cmd.effectId);
    }

    private static void syncBrewingPose(
            @Nonnull Ref<EntityStore> bossRef,
            @Nonnull TransformComponent transform,
            @Nullable String roleState,
            @Nonnull Runtime runtime,
            @Nonnull List<RotationCmd> rotationCmds
    ) {
        boolean brewing = COMBAT_BREWING.equals(roleState);
        if (brewing) {
            Vector3f current = new Vector3f(transform.getRotation());
            if (!runtime.brewingLookApplied) {
                runtime.preBrewingRotation = new Vector3f(current);
            }
            Vector3f next = new Vector3f(current);
            next.setPitch(BREWING_LOOK_DOWN_PITCH);
            if (!sameRotation(current, next)) {
                rotationCmds.add(new RotationCmd(bossRef, next));
            }
            runtime.brewingLookApplied = true;
            return;
        }

        if (!runtime.brewingLookApplied || runtime.preBrewingRotation == null) {
            return;
        }
        if (!sameRotation(transform.getRotation(), runtime.preBrewingRotation)) {
            rotationCmds.add(new RotationCmd(bossRef, new Vector3f(runtime.preBrewingRotation)));
        }
        runtime.brewingLookApplied = false;
        runtime.preBrewingRotation = null;
    }

    private static void applyRotation(@Nonnull World world, @Nonnull RotationCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(cmd.bossRef, TRANSFORM);
        if (transform == null) {
            return;
        }
        TransformComponent updated = transform.clone();
        updated.teleportRotation(new Vector3f(cmd.rotation));
        store.putComponent(cmd.bossRef, TRANSFORM, updated);
    }

    private static void applyStealth(@Nonnull World world, @Nonnull StealthCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (cmd.hidden) {
            store.putComponent(cmd.bossRef, HiddenFromAdventurePlayers.getComponentType(), HiddenFromAdventurePlayers.INSTANCE);
            return;
        }
        store.removeComponentIfExists(cmd.bossRef, HiddenFromAdventurePlayers.getComponentType());
    }

    private static void applyTeleport(@Nonnull World world, @Nonnull TeleportCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(cmd.bossRef, TRANSFORM);
        if (transform == null) {
            return;
        }
        TransformComponent updated = transform.clone();
        updated.teleportPosition(new Vector3d(cmd.position));
        if (cmd.rotation != null) {
            updated.teleportRotation(new Vector3f(cmd.rotation));
        }
        store.putComponent(cmd.bossRef, TRANSFORM, updated);
        NPCEntity npc = store.getComponent(cmd.bossRef, NPC);
        WorldSupport worldSupport = npc == null || npc.getRole() == null ? null : npc.getRole().getWorldSupport();
        if (worldSupport != null) {
            worldSupport.requestNewPath();
        }
    }

    private static void applyHeal(@Nonnull World world, @Nonnull HealCmd cmd) {
        if (cmd.bossRef == null || !cmd.bossRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        EntityStatMap stats = store.getComponent(cmd.bossRef, STATS);
        if (stats == null) {
            return;
        }
        float currentHealth = readHealth(stats);
        float healCeiling = getHealCeilingForCurrentHealth(currentHealth);
        if (currentHealth < 0.0f || currentHealth >= healCeiling) {
            return;
        }
        EntityStatMap updated = stats.clone();
        updated.addStatValue(HEALTH_STAT_INDEX, Math.min(cmd.amount, healCeiling - currentHealth));
        store.putComponent(cmd.bossRef, STATS, updated);
    }

    private static void placeActiveCauldron(@Nonnull World world, @Nonnull Runtime runtime) {
        Vector3d anchor = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
        int x = (int) Math.floor(anchor.getX());
        int y = (int) Math.floor(anchor.getY());
        int z = (int) Math.floor(anchor.getZ());
        if (runtime.cauldronPlaced && runtime.cauldronX != null && runtime.cauldronY != null && runtime.cauldronZ != null) {
            if (runtime.cauldronX == x && runtime.cauldronY == y && runtime.cauldronZ == z) {
                return;
            }
            removeActiveCauldron(world, runtime);
        }
        runtime.cauldronOriginalBlockId = null;
        runtime.cauldronX = x;
        runtime.cauldronY = y;
        runtime.cauldronZ = z;
        runtime.cauldronPlaced = true;
        scrubBrokenStairs(world, x, y, z);
        Vector3d shockwaveOrigin = new Vector3d(x + 0.5d, y, z + 0.5d);
        PotionBrewerWitchShockwaveSystem.triggerRipple(world, runtime.bossId, shockwaveOrigin);
        debug(world, runtime.bossId, "cauldronShockwave pos=(" + x + ", " + y + ", " + z + ")");
    }

    private static final int BROKEN_STAIRS_SCRUB_RADIUS_HORIZONTAL = 24;
    private static final int BROKEN_STAIRS_SCRUB_RADIUS_VERTICAL = 1;
    private static final String[] BROKEN_STAIRS_ID_FRAGMENTS = {"_Stairs"};
    private static final String BROKEN_STAIRS_REPLACEMENT_BLOCK_ID = "Rock_Stone_Cobble";

    private static void scrubBrokenStairs(@Nonnull World world, int centerX, int centerY, int centerZ) {
        int visited = 0;
        int matched = 0;
        int replaced = 0;
        int replaceFailed = 0;
        java.util.HashSet<String> seenStairsIds = new java.util.HashSet<>();
        System.out.println("[scrub] start center=(" + centerX + "," + centerY + "," + centerZ + ") radiusH=" + BROKEN_STAIRS_SCRUB_RADIUS_HORIZONTAL + " radiusV=" + BROKEN_STAIRS_SCRUB_RADIUS_VERTICAL);
        for (int dx = -BROKEN_STAIRS_SCRUB_RADIUS_HORIZONTAL; dx <= BROKEN_STAIRS_SCRUB_RADIUS_HORIZONTAL; dx++) {
            for (int dy = -BROKEN_STAIRS_SCRUB_RADIUS_VERTICAL; dy <= BROKEN_STAIRS_SCRUB_RADIUS_VERTICAL; dy++) {
                for (int dz = -BROKEN_STAIRS_SCRUB_RADIUS_HORIZONTAL; dz <= BROKEN_STAIRS_SCRUB_RADIUS_HORIZONTAL; dz++) {
                    int x = centerX + dx;
                    int y = centerY + dy;
                    int z = centerZ + dz;
                    BlockType existing;
                    try {
                        existing = world.getBlockType(x, y, z);
                    } catch (Exception ex) {
                        continue;
                    }
                    visited++;
                    if (existing == null) {
                        continue;
                    }
                    String id = existing.getId();
                    if (id == null) {
                        continue;
                    }
                    boolean match = false;
                    for (String frag : BROKEN_STAIRS_ID_FRAGMENTS) {
                        if (id.contains(frag)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    matched++;
                    if (seenStairsIds.add(id)) {
                        System.out.println("[scrub] match id=" + id + " at (" + x + "," + y + "," + z + ")");
                    }
                    try {
                        world.setBlock(x, y, z, BROKEN_STAIRS_REPLACEMENT_BLOCK_ID);
                        replaced++;
                    } catch (Exception ex) {
                        replaceFailed++;
                        System.out.println("[scrub] setBlock FAILED at (" + x + "," + y + "," + z + ") id=" + id + " err=" + ex.getMessage());
                    }
                    try {
                        BlockType after = world.getBlockType(x, y, z);
                        String afterId = after == null ? "null" : after.getId();
                        if (!BROKEN_STAIRS_REPLACEMENT_BLOCK_ID.equals(afterId)) {
                            System.out.println("[scrub] verify FAIL at (" + x + "," + y + "," + z + ") expected=" + BROKEN_STAIRS_REPLACEMENT_BLOCK_ID + " actual=" + afterId);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        System.out.println("[scrub] done visited=" + visited + " matched=" + matched + " replaced=" + replaced + " replaceFailed=" + replaceFailed + " uniqueIds=" + seenStairsIds.size());
    }

    private static void removeActiveCauldron(@Nonnull World world, @Nonnull Runtime runtime) {
        if (!runtime.cauldronPlaced || runtime.cauldronX == null || runtime.cauldronY == null || runtime.cauldronZ == null) {
            return;
        }
        runtime.cauldronPlaced = false;
        runtime.cauldronX = null;
        runtime.cauldronY = null;
        runtime.cauldronZ = null;
        runtime.cauldronOriginalBlockId = null;
    }

    @Nonnull
    private static Mode chooseModeForAbility(
            @Nonnull String brewedAbility,
            @Nonnull Vector3d origin,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull Runtime runtime
    ) {
        if (HEALING_DRAUGHT.equals(brewedAbility)) {
            if (runtime.lastHealth < getHealCeilingForStage(runtime.bossStage) * DRINK_HEALTH_THRESHOLD_PERCENT) {
                return Mode.SELF;
            } else {
                return Mode.THROWN;
            }
        }
        if (isCycleLimitedSelfBuffAbility(brewedAbility) && runtime.cycleSelfBuffAbility != null) {
            return Mode.THROWN;
        }
        if (POISON_POTION.equals(brewedAbility)
                || BLOOD_POTION.equals(brewedAbility)
                || BINDING_POTION.equals(brewedAbility)) {
            PlayerSnapshot nearest = findNearestPlayer(origin, players, CLOSE_RANGE_SELF_USE_DISTANCE);
            if (nearest != null) {
                return Mode.SELF;
            }
        }
        return RANDOM.nextBoolean() ? Mode.SELF : Mode.THROWN;
    }

    private static boolean isCycleLimitedSelfBuffAbility(@Nonnull String brewedAbility) {
        return POISON_POTION.equals(brewedAbility)
                || SHADOW_BOLT.equals(brewedAbility)
                || HOLY_POTION.equals(brewedAbility);
    }

    private static void clearCycleSelfBuffs(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nonnull List<StealthCmd> stealthCmds
    ) {
        runtime.selfPoisonUntil = 0L;
        runtime.shadowBuffUntil = 0L;
        runtime.shadowStunCharged = false;
        runtime.shadowRepositionPending = false;
        runtime.shadowAssassinActive = false;
        runtime.shadowAssassinApproachAt = 0L;
        runtime.cycleSelfBuffAbility = null;
        queueShadowStealthRemoval(runtime, stealthCmds);
        PotionBrewerWitchHolySystem.clearSelfHolyShield(world.getWorldConfig().getUuid(), runtime.bossId);
    }

    private static void syncShadowStealth(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCEntity npc,
            @Nonnull Runtime runtime,
            @Nonnull List<PlayerSnapshot> players,
            long now,
            @Nonnull List<StealthCmd> stealthCmds,
            @Nonnull List<TeleportCmd> teleportCmds,
            @Nonnull List<StateCmd> stateCmds
    ) {
        if (runtime.bossRef == null || !runtime.bossRef.isValid()) {
            return;
        }
        boolean active = runtime.shadowBuffUntil > now && runtime.shadowAssassinActive && runtime.shadowAssassinApproachAt > 0L;
        if (active && !runtime.shadowHiddenApplied) {
            stealthCmds.add(new StealthCmd(runtime.bossRef, true));
            runtime.shadowHiddenApplied = true;
            System.out.println("[PotionWitch][ASSASSIN] Invisibility ON for bossId=" + runtime.bossId + " (will approach in " + (runtime.shadowAssassinApproachAt - now) + "ms)");
        } else if (!active && runtime.shadowHiddenApplied) {
            stealthCmds.add(new StealthCmd(runtime.bossRef, false));
            runtime.shadowHiddenApplied = false;
            String reason = runtime.shadowBuffUntil <= now ? "Time Expired" : "Manual/Teleport Reveal";
            System.out.println("[PotionWitch][ASSASSIN] Invisibility OFF for bossId=" + runtime.bossId + ", reason=" + reason);
            // Also clean up assassin state if we are revealing due to time/manual reveal
            if (runtime.shadowBuffUntil <= now) {
                runtime.shadowAssassinActive = false;
                runtime.shadowAssassinApproachAt = 0L;
            }
        }

        // Delayed approach for assassin mode (after invisibility)
        if (runtime.shadowAssassinActive && now >= runtime.shadowAssassinApproachAt && runtime.shadowAssassinApproachAt > 0L) {
            System.out.println("[PotionWitch][ASSASSIN] Triggering approach at " + now + " (scheduled for " + runtime.shadowAssassinApproachAt + ")");
            queueAssassinApproach(world, runtime, players, teleportCmds, stealthCmds, now);
            runtime.shadowAssassinApproachAt = 0L;
            // Transition to the native assassin melee state
            stateCmds.add(new StateCmd(runtime.bossRef, runtime.bossId, COMBAT_SHADOW_ASSASSIN));
        }

        if (runtime.shadowAssassinActive) {
            tickAssassinStrike(world, store, runtime, players, now, stealthCmds);
        } else {
            // Revert back to standard shadow bolt state if we were in assassin mode
            String currentState = getNpcStateName(npc);
            if (currentState != null && currentState.endsWith("_Assassin")) {
                stateCmds.add(new StateCmd(runtime.bossRef, runtime.bossId, COMBAT_SHADOW_BOLT));
            }
        }
    }

    private static void tickAssassinStrike(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Runtime runtime,
            @Nonnull List<PlayerSnapshot> players,
            long now,
            @Nonnull List<StealthCmd> stealthCmds
    ) {
        // Assassin window expires - end the mode and set recovery
        if (runtime.shadowBuffUntil <= now && runtime.shadowAssassinActive) {
            runtime.shadowAssassinActive = false;
            runtime.shadowStunCharged = false;
            runtime.actionRecoveryUntil = now + 2000L;
            System.out.println("[PotionWitch][ASSASSIN] Ending assassin mode for bossId=" + runtime.bossId + ", recovery until " + runtime.actionRecoveryUntil);
        }
    }

    private static void queueShadowStealthRemoval(
            @Nonnull Runtime runtime,
            @Nonnull List<StealthCmd> stealthCmds
    ) {
        if (!runtime.shadowHiddenApplied || runtime.bossRef == null || !runtime.bossRef.isValid()) {
            runtime.shadowHiddenApplied = false;
            return;
        }
        stealthCmds.add(new StealthCmd(runtime.bossRef, false));
        runtime.shadowHiddenApplied = false;
    }

    private static void queueAssassinApproach(
            @Nonnull World world,
            @Nonnull Runtime runtime,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull List<TeleportCmd> teleportCmds,
            @Nonnull List<StealthCmd> stealthCmds,
            long now
    ) {
        if (runtime.bossRef == null || !runtime.bossRef.isValid()) {
            return;
        }
        Vector3d origin = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
        PlayerSnapshot target = findNearestPlayer(origin, players, 48.0d);
        if (target == null) {
            System.out.println("[PotionWitch][ASSASSIN] Approach failed: No target player within 48 blocks.");
            return;
        }

        // Instantly remove invisibility on teleport
        queueShadowStealthRemoval(runtime, stealthCmds);

        // Teleport to a point just behind the player (continuing past them from the witch's approach angle)
        double[] dir = flatDirectionTo(origin, target.position); // witch → player
        Vector3d dest = new Vector3d(
                target.position.getX() + dir[0] * SHADOW_ASSASSIN_APPROACH_DISTANCE,
                origin.getY(),
                target.position.getZ() + dir[1] * SHADOW_ASSASSIN_APPROACH_DISTANCE
        );

        // Calculate rotation to face the target upon arrival
        double dx = target.position.getX() - dest.getX();
        double dz = target.position.getZ() - dest.getZ();
        float yaw = (float) Math.atan2(-dx, dz);
        Vector3f rot = new Vector3f(0, yaw, 0);

        teleportCmds.add(new TeleportCmd(runtime.bossRef, runtime.bossId, dest, rot));
        runtime.currentPosition = new Vector3d(dest);

        System.out.println("[PotionWitch][ASSASSIN] Teleport: origin=" + fmt(origin) + ", target=" + fmt(target.position) + ", dest=" + fmt(dest) + ", distance=" + SHADOW_ASSASSIN_APPROACH_DISTANCE);

        broadcastToWorld(world, "Potion Brewer Witch emerges from the shadows with an Assassin's Strike!");
        System.out.println("[PotionWitch][ASSASSIN] APPROACH Teleport: Target=" + target.playerId + " (" + fmt(target.position) + "), Destination=" + fmt(dest) + ")");
        debug(world, runtime.bossId, "assassinApproach target=" + fmt(target.position) + " dest=" + fmt(dest));
    }

    private static void queueShadowReposition(
            @Nonnull World world,
            @Nonnull NPCEntity npc,
            @Nonnull Runtime runtime,
            @Nonnull List<PlayerSnapshot> players,
            @Nonnull List<TeleportCmd> teleportCmds
    ) {
        if (runtime.bossRef == null || !runtime.bossRef.isValid()) {
            return;
        }
        Vector3d origin = runtime.currentPosition == null ? runtime.spawnPosition : runtime.currentPosition;
        PlayerSnapshot target = findNearestPlayer(origin, players, 24.0d);
        if (target == null) {
            return;
        }

        double baseAngle = Math.atan2(origin.getZ() - target.position.getZ(), origin.getX() - target.position.getX());
        double[] candidateAngles = {
                baseAngle + Math.PI,
                baseAngle + (Math.PI * 0.75d),
                baseAngle - (Math.PI * 0.75d)
        };

        Vector3d best = null;
        double bestDistanceSq = -1.0d;
        for (double angle : candidateAngles) {
            Vector3d candidate = new Vector3d(
                    target.position.getX() + (Math.cos(angle) * SHADOW_REPOSITION_DISTANCE),
                    origin.getY(),
                    target.position.getZ() + (Math.sin(angle) * SHADOW_REPOSITION_DISTANCE)
            );
            double dx = candidate.getX() - origin.getX();
            double dz = candidate.getZ() - origin.getZ();
            double distanceSq = (dx * dx) + (dz * dz);
            if (distanceSq > bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        if (best == null) {
            return;
        }

        teleportCmds.add(new TeleportCmd(runtime.bossRef, runtime.bossId, best));
        runtime.currentPosition = new Vector3d(best);
        debug(world, runtime.bossId, "shadowReposition target=" + fmt(target.position) + " dest=" + fmt(best));
    }

    @Nullable
    private static PlayerSnapshot findNearestPlayer(
            @Nonnull Vector3d origin,
            @Nonnull List<PlayerSnapshot> players,
            double maxDistance
    ) {
        PlayerSnapshot best = null;
        double bestDistance = maxDistance;
        for (PlayerSnapshot player : players) {
            double distance = distance(origin, player.position);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    static boolean consumePendingProjectileSuppression(@Nonnull UUID worldId, @Nonnull UUID bossId, @Nonnull String brewedAbility) {
        synchronized (PENDING_SUPPRESSIONS_BY_WORLD) {
            Map<UUID, PendingSuppression> worldSuppressions = PENDING_SUPPRESSIONS_BY_WORLD.get(worldId);
            if (worldSuppressions == null) {
                return false;
            }
            PendingSuppression suppression = worldSuppressions.get(bossId);
            if (suppression == null) {
                return false;
            }
            boolean consumed = suppression.consume(brewedAbility);
            if (suppression.isEmpty()) {
                worldSuppressions.remove(bossId);
                if (worldSuppressions.isEmpty()) {
                    PENDING_SUPPRESSIONS_BY_WORLD.remove(worldId);
                }
            }
            return consumed;
        }
    }

    private static void queueProjectileSuppression(@Nonnull UUID worldId, @Nonnull UUID bossId, @Nonnull String brewedAbility) {
        synchronized (PENDING_SUPPRESSIONS_BY_WORLD) {
            PENDING_SUPPRESSIONS_BY_WORLD
                    .computeIfAbsent(worldId, ignored -> new HashMap<>())
                    .computeIfAbsent(bossId, ignored -> new PendingSuppression())
                    .add(brewedAbility);
        }
    }

    private static void clearPendingSuppressions(@Nonnull UUID worldId, @Nonnull UUID bossId) {
        synchronized (PENDING_SUPPRESSIONS_BY_WORLD) {
            Map<UUID, PendingSuppression> worldSuppressions = PENDING_SUPPRESSIONS_BY_WORLD.get(worldId);
            if (worldSuppressions == null) {
                return;
            }
            worldSuppressions.remove(bossId);
            if (worldSuppressions.isEmpty()) {
                PENDING_SUPPRESSIONS_BY_WORLD.remove(worldId);
            }
        }
    }

    private static double distance(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Returns a normalized [dx, dz] flat direction from a to b. Returns [0, 0] if they coincide.
    private static double[] flatDirectionTo(@Nonnull Vector3d from, @Nonnull Vector3d to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001d) {
            return new double[]{0.0d, 0.0d};
        }
        return new double[]{dx / len, dz / len};
    }

    @Nonnull
    private static String fmt(@Nonnull Vector3d vector) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", vector.getX(), vector.getY(), vector.getZ());
    }

    @Nonnull
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private interface RuntimeOp {
        void apply(@Nonnull Runtime runtime);
    }

    private enum Phase {
        IDLE,
        RETURNING,
        BREWING,
        LOADED
    }

    private enum BossStage {
        PHASE_ONE,
        PHASE_TWO,
        PHASE_THREE
    }

    private enum Mode {
        SELF,
        THROWN
    }

    private static final class Runtime {
        private final UUID bossId;
        private final Vector3d spawnPosition;
        private Ref<EntityStore> bossRef;
        private float lastHealth = -1.0f;
        private int missingTicks;
        private Phase phase = Phase.IDLE;
        private BossStage bossStage = BossStage.PHASE_ONE;
        private boolean returnQueued;
        private boolean brewAnnounced;
        private boolean brewingLookApplied;
        private boolean cauldronPlaced;
        private long brewingStartedAt;
        private long lastDebugPositionAt;
        private long selfPoisonUntil;
        private long shadowBuffUntil;
        private boolean despawnQueued;
        private boolean shadowStunCharged;
        private boolean shadowHiddenApplied;
        private boolean shadowRepositionPending;
        private boolean shadowAssassinActive;
        private long shadowAssassinApproachAt;
        private long actionRecoveryUntil;
        private int healCount;
        private String lastRoleState;
        private String pendingRecoverySubState;
        private boolean waitForChainClearDuringRecovery = true;
        private String cycleSelfBuffAbility;
        private Vector3d currentPosition;
        private Integer cauldronX;
        private Integer cauldronY;
        private Integer cauldronZ;
        private String cauldronOriginalBlockId;
        private Vector3f preBrewingRotation;
        private final Set<Integer> activeAbilityChains = new HashSet<>();

        private Runtime(
                UUID bossId,
                Ref<EntityStore> bossRef,
                Vector3d spawnPosition
        ) {
            this.bossId = bossId;
            this.bossRef = bossRef;
            this.spawnPosition = spawnPosition;
        }
    }

    public static final class HudSnapshot {
        public final UUID bossId;
        public final Vector3d position;
        public final float currentHealth;
        public final float maxHealth;
        public final String bossStage;
        public final String phase;
        public final String roleState;
        public final List<String> brewedCharges;

        private HudSnapshot(
                @Nonnull UUID bossId,
                @Nonnull Vector3d position,
                float currentHealth,
                float maxHealth,
                @Nonnull String bossStage,
                @Nonnull String phase,
                @Nonnull String roleState,
                @Nonnull List<String> brewedCharges
        ) {
            this.bossId = bossId;
            this.position = position;
            this.currentHealth = currentHealth;
            this.maxHealth = maxHealth;
            this.bossStage = bossStage;
            this.phase = phase;
            this.roleState = roleState;
            this.brewedCharges = List.copyOf(brewedCharges);
        }
    }

    private static final class LoadoutState {
        private final Deque<String> brewedCharges;

        private LoadoutState(List<String> brewed) {
            this.brewedCharges = new LinkedList<>(brewed);
        }
    }

    private static final class StateCmd {
        private final Ref<EntityStore> bossRef;
        private final UUID bossId;
        private final String subState;

        private StateCmd(Ref<EntityStore> bossRef, UUID bossId, String subState) {
            this.bossRef = bossRef;
            this.bossId = bossId;
            this.subState = subState;
        }
    }

    private static final class EffectCmd {
        private final Ref<EntityStore> entityRef;
        private final UUID entityId;
        private final String effectId;

        private EffectCmd(Ref<EntityStore> entityRef, UUID entityId, String effectId) {
            this.entityRef = entityRef;
            this.entityId = entityId;
            this.effectId = effectId;
        }
    }

    private static final class StealthCmd {
        private final Ref<EntityStore> bossRef;
        private final boolean hidden;

        private StealthCmd(@Nonnull Ref<EntityStore> bossRef, boolean hidden) {
            this.bossRef = bossRef;
            this.hidden = hidden;
        }
    }

    private static final class TeleportCmd {
        private final Ref<EntityStore> bossRef;
        private final UUID bossId;
        private final Vector3d position;
        private final Vector3f rotation;

        private TeleportCmd(@Nonnull Ref<EntityStore> bossRef, @Nonnull UUID bossId, @Nonnull Vector3d position) {
            this(bossRef, bossId, position, null);
        }

        private TeleportCmd(@Nonnull Ref<EntityStore> bossRef, @Nonnull UUID bossId, @Nonnull Vector3d position, Vector3f rotation) {
            this.bossRef = bossRef;
            this.bossId = bossId;
            this.position = position;
            this.rotation = rotation;
        }
    }

    private static final class RotationCmd {
        private final Ref<EntityStore> bossRef;
        private final UUID bossId;
        private final Vector3f rotation;

        private RotationCmd(@Nonnull Ref<EntityStore> bossRef, @Nonnull Vector3f rotation) {
            this(bossRef, null, rotation);
        }

        private RotationCmd(@Nonnull Ref<EntityStore> bossRef, @Nullable UUID bossId, @Nonnull Vector3f rotation) {
            this.bossRef = bossRef;
            this.bossId = bossId;
            this.rotation = rotation;
        }
    }

    private static final class HealCmd {
        private final Ref<EntityStore> bossRef;
        private final UUID bossId;
        private final float amount;

        private HealCmd(@Nonnull Ref<EntityStore> bossRef, @Nonnull UUID bossId, float amount) {
            this.bossRef = bossRef;
            this.bossId = bossId;
            this.amount = amount;
        }
    }

    private static final class DespawnCmd {
        private final Ref<EntityStore> bossRef;
        private final UUID bossId;

        private DespawnCmd(@Nonnull Ref<EntityStore> bossRef, @Nonnull UUID bossId) {
            this.bossRef = bossRef;
            this.bossId = bossId;
        }
    }

    private static final class PlayerSnapshot {
        private final Ref<EntityStore> playerRef;
        private final UUID playerId;
        private final Vector3d position;

        private PlayerSnapshot(Ref<EntityStore> playerRef, UUID playerId, Vector3d position) {
            this.playerRef = playerRef;
            this.playerId = playerId;
            this.position = position;
        }
    }

    private static final class PendingSuppression {
        private int poisonCount;
        private int shadowCount;
        private int bloodCount;
        private int bindingCount;

        private void add(@Nonnull String brewedAbility) {
            if (POISON_POTION.equals(brewedAbility)) {
                this.poisonCount++;
            } else if (SHADOW_BOLT.equals(brewedAbility)) {
                this.shadowCount++;
            } else if (BLOOD_POTION.equals(brewedAbility)) {
                this.bloodCount++;
            } else if (BINDING_POTION.equals(brewedAbility)) {
                this.bindingCount++;
            }
        }

        private boolean consume(@Nonnull String brewedAbility) {
            if (POISON_POTION.equals(brewedAbility) && this.poisonCount > 0) {
                this.poisonCount--;
                return true;
            }
            if (SHADOW_BOLT.equals(brewedAbility) && this.shadowCount > 0) {
                this.shadowCount--;
                return true;
            }
            if (BLOOD_POTION.equals(brewedAbility) && this.bloodCount > 0) {
                this.bloodCount--;
                return true;
            }
            if (BINDING_POTION.equals(brewedAbility) && this.bindingCount > 0) {
                this.bindingCount--;
                return true;
            }
            return false;
        }

        private boolean isEmpty() {
            return this.poisonCount <= 0
                    && this.shadowCount <= 0
                    && this.bloodCount <= 0
                    && this.bindingCount <= 0;
        }
    }
}
