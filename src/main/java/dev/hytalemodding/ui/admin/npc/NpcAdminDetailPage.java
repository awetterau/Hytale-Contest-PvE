package dev.hytalemodding.ui.admin.npc;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.npc.admin.NpcAdminService;
import dev.hytalemodding.npc.admin.NpcEconomyAdminService;
import dev.hytalemodding.npc.admin.NpcValidationService;
import dev.hytalemodding.npc.config.NpcUnifiedRegistry;
import dev.hytalemodding.npc.core.NpcDefinition;
import dev.hytalemodding.npc.economy.NpcEconomyDefinition;
import dev.hytalemodding.npc.runtime.NpcAvailabilityService;
import dev.hytalemodding.npc.state.NpcRuntimeState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcAdminDetailPage extends InteractiveCustomUIPage<NpcAdminDetailPage.Data> {
    private enum Section {
        OVERVIEW,
        STORY,
        PLACEMENT,
        WORKSTATION,
        QUESTS,
        TRADES,
        DEBUG
    }

    private static final ConcurrentHashMap<String, Integer> TRADE_INDEX_BY_PLAYER_NPC = new ConcurrentHashMap<>();

    private final String npcKey;
    private Section section;
    private boolean internalNavigation;

    public static final class Data {
        public String action;
        public String tradeTitle;
        public String tradeCostItemId;
        public String tradeRewardItemId;
        public Double tradeCostAmount;
        public Double tradeRewardAmount;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> ((Data) d).action = (String) v, d -> ((Data) d).action)
                .addField(new KeyedCodec("@TradeTitle", Codec.STRING), (d, v) -> ((Data) d).tradeTitle = (String) v, d -> ((Data) d).tradeTitle)
                .addField(new KeyedCodec("@TradeCostItemId", Codec.STRING), (d, v) -> ((Data) d).tradeCostItemId = (String) v, d -> ((Data) d).tradeCostItemId)
                .addField(new KeyedCodec("@TradeRewardItemId", Codec.STRING), (d, v) -> ((Data) d).tradeRewardItemId = (String) v, d -> ((Data) d).tradeRewardItemId)
                .addField(new KeyedCodec("@TradeCostAmount", Codec.DOUBLE), (d, v) -> ((Data) d).tradeCostAmount = (Double) v, d -> ((Data) d).tradeCostAmount)
                .addField(new KeyedCodec("@TradeRewardAmount", Codec.DOUBLE), (d, v) -> ((Data) d).tradeRewardAmount = (Double) v, d -> ((Data) d).tradeRewardAmount)
                .build();
    }

    public NpcAdminDetailPage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey) {
        this(playerRef, npcKey, Section.OVERVIEW);
    }

    public NpcAdminDetailPage(@Nonnull PlayerRef playerRef, @Nonnull String npcKey, @Nonnull Section section) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npcKey = npcKey;
        this.section = section;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(this.npcKey);
        NpcRuntimeState state = dev.hytalemodding.npc.state.NpcStateManager.get().getState(this.npcKey);
        NpcAvailabilityService.AvailabilitySnapshot availability = NpcAvailabilityService.get().getAvailability(this.npcKey);
        List<String> validationLines = definition == null ? List.of("Unknown NPC.") : NpcValidationService.get().validate(definition);
        List<NpcEconomyDefinition.OfferDefinition> tradeOffers = NpcEconomyAdminService.get().getTradeOffers(this.npcKey);
        normalizeTradeSelection(tradeOffers);
        NpcEconomyDefinition.OfferDefinition selectedTrade = getSelectedTrade(tradeOffers);

        ui.append("Pages/NpcAdminDetail.ui");
        ui.set("#Title.Text", definition == null ? "NPC Detail" : definition.displayName + " Admin");
        ui.set("#Subtitle.Text", definition == null
                ? "Unknown NPC"
                : definition.npcKey + "  |  " + definition.category.name() + "  |  roles: " + safe(definition.roles.hubRole) + " / " + safe(definition.roles.runRescueRole));
        ui.set("#SectionTitle.Text", getSectionTitle());
        ui.set("#SectionDescription.Text", getSectionDescription());
        ui.set("#OverviewTabLabel.Text", tabLabel("Overview", Section.OVERVIEW));
        ui.set("#StoryTabLabel.Text", tabLabel("Story", Section.STORY));
        ui.set("#PlacementTabLabel.Text", tabLabel("Placement", Section.PLACEMENT));
        ui.set("#WorkstationTabLabel.Text", tabLabel("Workstation", Section.WORKSTATION));
        ui.set("#QuestsTabLabel.Text", tabLabel("Quests", Section.QUESTS));
        ui.set("#TradesTabLabel.Text", tabLabel("Trading", Section.TRADES));
        ui.set("#DebugTabLabel.Text", tabLabel("Debug", Section.DEBUG));

        ui.set("#StatusLine.Text", "Rescued: " + yesNo(state.rescued)
                + "    Presence: " + pretty(state.presenceMode.name())
                + "    Behavior: " + pretty(state.hubBehavior.name()));
        ui.set("#ServiceLine.Text", "Talk " + yesNo(availability.canTalk())
                + "   Trade " + yesNo(availability.canTrade())
                + "   Quests " + yesNo(availability.canQuest())
                + "   Upgrades " + yesNo(availability.canUpgrade()));
        ui.set("#StoryText.Text", definition == null ? "No story data." : buildStoryText(definition));
        ui.set("#SpawnText.Text", buildSpawnText(state));
        ui.set("#WorkstationText.Text", definition == null ? "No workstation data." : buildWorkstationText(definition, state));
        ui.set("#QuestText.Text", definition == null ? "No quest data." : buildQuestText(definition, state));
        ui.set("#TradeText.Text", buildTradeText(selectedTrade, tradeOffers));
        ui.set("#TradeMeta.Text", selectedTrade == null
                ? "No trade offers configured."
                : selectedTrade.offerId + "  |  " + (getTradeIndex() + 1) + "/" + tradeOffers.size());
        ui.set("#TradeTitleInput.Value", selectedTrade == null ? "" : selectedTrade.title);
        ui.set("#TradeCostItemInput.Value", selectedTrade == null ? "" : firstItemId(selectedTrade.cost));
        ui.set("#TradeRewardItemInput.Value", selectedTrade == null ? "" : firstItemId(selectedTrade.reward));
        ui.set("#TradeCostAmountInput.Value", selectedTrade == null ? 1.0 : (double) firstItemAmount(selectedTrade.cost));
        ui.set("#TradeRewardAmountInput.Value", selectedTrade == null ? 1.0 : (double) firstItemAmount(selectedTrade.reward));
        ui.set("#StoryStateNote.Text", "Story state decides whether the campaign considers this NPC already rescued.");
        ui.set("#PresenceNote.Text", "Placement decides where the NPC appears right now.");
        ui.set("#BehaviorNote.Text", "Hub activity only matters while the NPC is shown in the hub.");
        ui.set("#SpawnNote.Text", "Save My Position stores your location as an override. Use Default removes the override.");
        ui.set("#WorkstationNote.Text", "Use this tab to test whether the NPC is still waiting for a workstation or already has one.");
        ui.set("#QuestNote.Text", "Use this tab to reset or fast-forward this NPC's quest state while testing.");
        ui.set("#TradeNote.Text", "Use this tab to add, remove, and tune shop offers. Trading edits are written back to the NPC data file.");
        ui.set("#ValidationText.Text", validationLines.isEmpty()
                ? "Validation: no issues."
                : "Validation:\n - " + String.join("\n - ", validationLines));

        boolean overview = this.section == Section.OVERVIEW;
        boolean story = this.section == Section.STORY;
        boolean placement = this.section == Section.PLACEMENT;
        boolean workstation = this.section == Section.WORKSTATION;
        boolean quests = this.section == Section.QUESTS;
        boolean trades = this.section == Section.TRADES;
        boolean debug = this.section == Section.DEBUG;
        boolean hasTradeSelection = selectedTrade != null;

        ui.set("#StateCard.Visible", overview || debug);
        ui.set("#StoryCard.Visible", overview || story);
        ui.set("#SpawnCard.Visible", overview || placement);
        ui.set("#WorkstationCard.Visible", workstation);
        ui.set("#QuestCard.Visible", quests);
        ui.set("#TradeCard.Visible", trades);
        ui.set("#StoryStateLabel.Visible", story);
        ui.set("#StoryStateNote.Visible", story);
        ui.set("#RescueActions.Visible", story);
        ui.set("#PresenceLabel.Visible", placement);
        ui.set("#PresenceNote.Visible", placement);
        ui.set("#PresenceActions.Visible", placement);
        ui.set("#BehaviorLabel.Visible", placement);
        ui.set("#BehaviorNote.Visible", placement);
        ui.set("#BehaviorActions.Visible", placement);
        ui.set("#SpawnLabel.Visible", placement);
        ui.set("#SpawnNote.Visible", placement);
        ui.set("#SpawnActions.Visible", placement);
        ui.set("#WorkstationLabel.Visible", workstation);
        ui.set("#WorkstationNote.Visible", workstation);
        ui.set("#WorkstationActions.Visible", workstation);
        ui.set("#QuestLabel.Visible", quests);
        ui.set("#QuestNote.Visible", quests);
        ui.set("#QuestActions.Visible", quests);
        ui.set("#TradeLabel.Visible", trades);
        ui.set("#TradeNote.Visible", trades);
        ui.set("#TradeMeta.Visible", trades);
        ui.set("#TradeNavigation.Visible", trades);
        ui.set("#TradeActionsPrimary.Visible", trades);
        ui.set("#TradeActionsItem.Visible", trades && hasTradeSelection);
        ui.set("#TradeActionsAmount.Visible", trades && hasTradeSelection);
        ui.set("#TradeEditorCard.Visible", trades && hasTradeSelection);
        ui.set("#ValidationCard.Visible", overview || debug);
        ui.set("#ResetBtn.Visible", debug);
        ui.set("#ValidateBtn.Visible", overview || debug);
        ui.set("#TradePrevBtn.Visible", trades && tradeOffers.size() > 1);
        ui.set("#TradeNextBtn.Visible", trades && tradeOffers.size() > 1);
        ui.set("#TradeDuplicateBtn.Visible", trades && hasTradeSelection);
        ui.set("#TradeRemoveBtn.Visible", trades && hasTradeSelection);

        bind(events, "#OverviewTab", "tab_overview");
        bind(events, "#StoryTab", "tab_story");
        bind(events, "#PlacementTab", "tab_placement");
        bind(events, "#WorkstationTab", "tab_workstation");
        bind(events, "#QuestsTab", "tab_quests");
        bind(events, "#TradesTab", "tab_trades");
        bind(events, "#DebugTab", "tab_debug");
        bind(events, "#RescueOnBtn", "rescue_on");
        bind(events, "#RescueOffBtn", "rescue_off");
        bind(events, "#PresenceHiddenBtn", "presence_hidden");
        bind(events, "#PresenceHubBtn", "presence_hub");
        bind(events, "#PresenceRunBtn", "presence_run");
        bind(events, "#BehaviorStandingBtn", "behavior_standing");
        bind(events, "#BehaviorWanderingBtn", "behavior_wandering");
        bind(events, "#BehaviorWaitingBtn", "behavior_waiting");
        bind(events, "#BehaviorWorkingBtn", "behavior_working");
        bind(events, "#CaptureHubSpawnBtn", "capture_hub");
        bind(events, "#ClearHubSpawnBtn", "clear_hub");
        bind(events, "#CaptureRescueSpawnBtn", "capture_rescue");
        bind(events, "#ClearRescueSpawnBtn", "clear_rescue");
        bind(events, "#WorkstationAssignBtn", "workstation_assign");
        bind(events, "#WorkstationClearBtn", "workstation_clear");
        bind(events, "#WorkstationLevelDownBtn", "workstation_level_down");
        bind(events, "#WorkstationLevelUpBtn", "workstation_level_up");
        bind(events, "#QuestResetBtn", "quest_reset");
        bind(events, "#QuestClearAcceptedBtn", "quest_clear_accepted");
        bind(events, "#QuestClearCompletedBtn", "quest_clear_completed");
        bind(events, "#QuestCompleteLinkedBtn", "quest_complete_linked");
        bind(events, "#TradePrevBtn", "trade_prev");
        bind(events, "#TradeNextBtn", "trade_next");
        bind(events, "#TradeAddBtn", "trade_add");
        bind(events, "#TradeDuplicateBtn", "trade_duplicate");
        bind(events, "#TradeRemoveBtn", "trade_remove");
        bind(events, "#TradeCostItemPrevBtn", "trade_cost_item_prev");
        bind(events, "#TradeCostItemNextBtn", "trade_cost_item_next");
        bind(events, "#TradeRewardItemPrevBtn", "trade_reward_item_prev");
        bind(events, "#TradeRewardItemNextBtn", "trade_reward_item_next");
        bind(events, "#TradeCostAmountDownBtn", "trade_cost_amount_down");
        bind(events, "#TradeCostAmountUpBtn", "trade_cost_amount_up");
        bind(events, "#TradeRewardAmountDownBtn", "trade_reward_amount_down");
        bind(events, "#TradeRewardAmountUpBtn", "trade_reward_amount_up");
        bind(events, "#ResetBtn", "reset");
        bind(events, "#ValidateBtn", "validate");
        bind(events, "#BackBtn", "back");
        bind(events, "#RefreshBtn", "refresh");
        bind(events, "#CloseBtn", "close");
        bindTradeEditorEvents(events);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        String action = data.action == null ? "" : data.action.trim().toLowerCase();
        List<NpcEconomyDefinition.OfferDefinition> trades = NpcEconomyAdminService.get().getTradeOffers(this.npcKey);
        normalizeTradeSelection(trades);
        NpcEconomyDefinition.OfferDefinition selectedTrade = getSelectedTrade(trades);
        switch (action) {
            case "tab_overview" -> navigate(ref, store, Section.OVERVIEW);
            case "tab_story" -> navigate(ref, store, Section.STORY);
            case "tab_placement" -> navigate(ref, store, Section.PLACEMENT);
            case "tab_workstation" -> navigate(ref, store, Section.WORKSTATION);
            case "tab_quests" -> navigate(ref, store, Section.QUESTS);
            case "tab_trades" -> navigate(ref, store, Section.TRADES);
            case "tab_debug" -> navigate(ref, store, Section.DEBUG);
            case "rescue_on" -> NpcAdminService.get().setRescued(this.npcKey, true);
            case "rescue_off" -> NpcAdminService.get().setRescued(this.npcKey, false);
            case "presence_hidden" -> NpcAdminService.get().setPresenceMode(this.npcKey, NpcRuntimeState.PresenceMode.HIDDEN);
            case "presence_hub" -> NpcAdminService.get().setPresenceMode(this.npcKey, NpcRuntimeState.PresenceMode.HUB);
            case "presence_run" -> NpcAdminService.get().setPresenceMode(this.npcKey, NpcRuntimeState.PresenceMode.RUN_RESCUE_OBJECTIVE);
            case "behavior_standing" -> NpcAdminService.get().setHubBehavior(this.npcKey, NpcDefinition.HubBehaviorMode.STANDING);
            case "behavior_wandering" -> NpcAdminService.get().setHubBehavior(this.npcKey, NpcDefinition.HubBehaviorMode.WANDERING);
            case "behavior_waiting" -> NpcAdminService.get().setHubBehavior(this.npcKey, NpcDefinition.HubBehaviorMode.WAITING_FOR_WORKSTATION);
            case "behavior_working" -> NpcAdminService.get().setHubBehavior(this.npcKey, NpcDefinition.HubBehaviorMode.WORKING);
            case "capture_hub" -> NpcAdminService.get().setHubSpawnOverride(this.npcKey, this.playerRef.getTransform());
            case "clear_hub" -> NpcAdminService.get().setHubSpawnOverride(this.npcKey, null);
            case "capture_rescue" -> NpcAdminService.get().setRescueSpawnOverride(this.npcKey, this.playerRef.getTransform());
            case "clear_rescue" -> NpcAdminService.get().setRescueSpawnOverride(this.npcKey, null);
            case "workstation_assign" -> NpcAdminService.get().setAssignedWorkstationId(this.npcKey, defaultAssignedWorkstationId());
            case "workstation_clear" -> NpcAdminService.get().setAssignedWorkstationId(this.npcKey, null);
            case "workstation_level_down" -> {
                NpcRuntimeState state = dev.hytalemodding.npc.state.NpcStateManager.get().getState(this.npcKey);
                NpcAdminService.get().setWorkstationLevel(this.npcKey, Math.max(0, state.workstationLevel - 1));
            }
            case "workstation_level_up" -> {
                NpcRuntimeState state = dev.hytalemodding.npc.state.NpcStateManager.get().getState(this.npcKey);
                NpcAdminService.get().setWorkstationLevel(this.npcKey, state.workstationLevel + 1);
            }
            case "quest_reset" -> {
                NpcAdminService.get().setAcceptedQuestIds(this.npcKey, Set.of());
                NpcAdminService.get().setCompletedQuestIds(this.npcKey, Set.of());
            }
            case "quest_clear_accepted" -> NpcAdminService.get().setAcceptedQuestIds(this.npcKey, Set.of());
            case "quest_clear_completed" -> NpcAdminService.get().setCompletedQuestIds(this.npcKey, Set.of());
            case "quest_complete_linked" -> {
                NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(this.npcKey);
                NpcAdminService.get().setCompletedQuestIds(this.npcKey, definition == null ? Set.of() : Set.copyOf(definition.story.linkedQuestIds));
            }
            case "trade_prev" -> setTradeIndex(getTradeIndex() <= 0 ? trades.size() - 1 : getTradeIndex() - 1);
            case "trade_next" -> setTradeIndex(trades.isEmpty() ? 0 : (getTradeIndex() + 1) % trades.size());
            case "trade_add" -> {
                if (NpcEconomyAdminService.get().addTradeOffer(this.npcKey)) {
                    setTradeIndex(Integer.MAX_VALUE);
                }
            }
            case "trade_duplicate" -> {
                if (selectedTrade != null && NpcEconomyAdminService.get().duplicateOffer(this.npcKey, selectedTrade.offerId)) {
                    setTradeIndex(Integer.MAX_VALUE);
                }
            }
            case "trade_remove" -> {
                if (selectedTrade != null) {
                    NpcEconomyAdminService.get().removeOffer(this.npcKey, selectedTrade.offerId);
                    setTradeIndex(Math.max(0, getTradeIndex() - 1));
                }
            }
            case "trade_cost_item_prev" -> editTradeItem(selectedTrade, false, -1);
            case "trade_cost_item_next" -> editTradeItem(selectedTrade, false, 1);
            case "trade_reward_item_prev" -> editTradeItem(selectedTrade, true, -1);
            case "trade_reward_item_next" -> editTradeItem(selectedTrade, true, 1);
            case "trade_cost_amount_down" -> editTradeAmount(selectedTrade, false, -1);
            case "trade_cost_amount_up" -> editTradeAmount(selectedTrade, false, 1);
            case "trade_reward_amount_down" -> editTradeAmount(selectedTrade, true, -1);
            case "trade_reward_amount_up" -> editTradeAmount(selectedTrade, true, 1);
            case "trade_apply_text" -> applyTradeTextEdits(selectedTrade, data);
            case "trade_apply_amounts" -> applyTradeAmountEdits(selectedTrade, data);
            case "reset" -> {
                NpcAdminService.get().resetNpcToMigratedDefaults(this.npcKey);
                this.playerRef.sendMessage(Message.raw("Reset " + this.npcKey + " to migrated defaults."));
            }
            case "validate" -> {
                List<String> issues = NpcAdminService.get().validateAll();
                for (String issue : issues) {
                    if (issue.startsWith(this.npcKey + ":") || "No NPC validation issues found.".equals(issue)) {
                        this.playerRef.sendMessage(Message.raw(issue));
                    }
                }
            }
            case "back" -> {
                this.internalNavigation = true;
                openPage(ref, store, new NpcAdminHomePage(this.playerRef));
                return;
            }
            case "close" -> {
                close();
                return;
            }
            default -> {
            }
        }
        refresh(ref, store);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (this.internalNavigation) {
            return;
        }
    }

    private void editTradeItem(@Nullable NpcEconomyDefinition.OfferDefinition selectedTrade, boolean reward, int step) {
        if (selectedTrade == null) {
            return;
        }
        NpcEconomyAdminService.get().cycleOfferItem(this.npcKey, selectedTrade.offerId, reward, step);
    }

    private void editTradeAmount(@Nullable NpcEconomyDefinition.OfferDefinition selectedTrade, boolean reward, int delta) {
        if (selectedTrade == null) {
            return;
        }
        NpcEconomyAdminService.get().adjustOfferAmount(this.npcKey, selectedTrade.offerId, reward, delta);
    }

    private void applyTradeTextEdits(@Nullable NpcEconomyDefinition.OfferDefinition selectedTrade, @Nonnull Data data) {
        if (selectedTrade == null) {
            return;
        }
        NpcEconomyAdminService.get().setOfferTitle(this.npcKey, selectedTrade.offerId, data.tradeTitle);
        NpcEconomyAdminService.get().setOfferItemId(this.npcKey, selectedTrade.offerId, false, data.tradeCostItemId);
        NpcEconomyAdminService.get().setOfferItemId(this.npcKey, selectedTrade.offerId, true, data.tradeRewardItemId);
    }

    private void applyTradeAmountEdits(@Nullable NpcEconomyDefinition.OfferDefinition selectedTrade, @Nonnull Data data) {
        if (selectedTrade == null) {
            return;
        }
        if (data.tradeCostAmount != null) {
            NpcEconomyAdminService.get().setOfferAmount(this.npcKey, selectedTrade.offerId, false, (int) Math.round(data.tradeCostAmount));
        }
        if (data.tradeRewardAmount != null) {
            NpcEconomyAdminService.get().setOfferAmount(this.npcKey, selectedTrade.offerId, true, (int) Math.round(data.tradeRewardAmount));
        }
    }

    private void navigate(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Section nextSection) {
        this.section = nextSection;
        refresh(ref, store);
    }

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        build(ref, commandBuilder, eventBuilder, store);
        sendUpdate(commandBuilder, eventBuilder, true);
    }

    private static void openPage(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull InteractiveCustomUIPage<?> nextPage
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, nextPage);
    }

    private static void bind(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action), false);
    }

    private static void bindTradeEditorEvents(@Nonnull UIEventBuilder events) {
        EventData applyText = new EventData()
                .append("Action", "trade_apply_text")
                .append("@TradeTitle", "#TradeTitleInput.Value")
                .append("@TradeCostItemId", "#TradeCostItemInput.Value")
                .append("@TradeRewardItemId", "#TradeRewardItemInput.Value");
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TradeTitleInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#TradeTitleInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#TradeTitleInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TradeCostItemInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#TradeCostItemInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#TradeCostItemInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TradeRewardItemInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#TradeRewardItemInput", applyText, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#TradeRewardItemInput", applyText, false);

        EventData applyAmounts = new EventData()
                .append("Action", "trade_apply_amounts")
                .append("@TradeCostAmount", "#TradeCostAmountInput.Value")
                .append("@TradeRewardAmount", "#TradeRewardAmountInput.Value");
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TradeCostAmountInput", applyAmounts, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#TradeCostAmountInput", applyAmounts, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#TradeCostAmountInput", applyAmounts, false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TradeRewardAmountInput", applyAmounts, false);
        events.addEventBinding(CustomUIEventBindingType.FocusLost, "#TradeRewardAmountInput", applyAmounts, false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#TradeRewardAmountInput", applyAmounts, false);
    }

    @Nonnull
    private static String buildStoryText(@Nonnull NpcDefinition definition) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Workstation required: " + yesNo(definition.workstation.required));
        lines.add("Workstation type: " + safe(definition.workstation.workstationType));
        lines.add("Unlock quest before workstation: " + safe(definition.workstation.preUnlockQuestId));
        lines.add("Linked quests: " + (definition.story.linkedQuestIds.isEmpty() ? "<none>" : String.join(", ", definition.story.linkedQuestIds)));
        return String.join("\n", lines);
    }

    @Nonnull
    private static String buildSpawnText(@Nonnull NpcRuntimeState state) {
        return "Hub spawn override: " + formatTransform(state.hubSpawnOverride) + "\n"
                + "Rescue spawn override: " + formatTransform(state.rescueSpawnOverride);
    }

    @Nonnull
    private static String buildWorkstationText(@Nonnull NpcDefinition definition, @Nonnull NpcRuntimeState state) {
        return "Required: " + yesNo(definition.workstation.required) + "\n"
                + "Type: " + safe(definition.workstation.workstationType) + "\n"
                + "Home template: " + safe(definition.workstation.homeTemplateId) + "\n"
                + "Unlock quest: " + safe(definition.workstation.preUnlockQuestId) + "\n"
                + "Assigned workstation: " + safe(state.assignedWorkstationId) + "\n"
                + "Workstation level: " + state.workstationLevel;
    }

    @Nonnull
    private static String buildQuestText(@Nonnull NpcDefinition definition, @Nonnull NpcRuntimeState state) {
        return "Linked quests: " + join(definition.story.linkedQuestIds) + "\n"
                + "Required completed quests: " + join(definition.dependencies.requiredCompletedQuests) + "\n"
                + "Accepted now: " + join(new ArrayList<>(state.acceptedQuestIds)) + "\n"
                + "Completed now: " + join(new ArrayList<>(state.completedQuestIds));
    }

    @Nonnull
    private static String buildTradeText(@Nullable NpcEconomyDefinition.OfferDefinition selectedTrade, @Nonnull List<NpcEconomyDefinition.OfferDefinition> allTrades) {
        if (selectedTrade == null) {
            return allTrades.isEmpty()
                    ? "No trade offers yet.\nUse Add Trade to create one."
                    : "Select a trade offer.";
        }
        return "Title: " + selectedTrade.title + "\n"
                + "Sold item: " + describeFirstItem(selectedTrade.reward) + "\n"
                + "Cost item: " + describeFirstItem(selectedTrade.cost) + "\n"
                + "Required tier: " + selectedTrade.requiredTier + "\n"
                + "Required flags: " + join(new ArrayList<>(selectedTrade.requiredFlags));
    }

    @Nonnull
    private static String describeFirstItem(@Nonnull List<NpcEconomyDefinition.ItemAmount> items) {
        if (items.isEmpty()) {
            return "<none>";
        }
        NpcEconomyDefinition.ItemAmount item = items.get(0);
        return item.itemId + " x" + item.amount;
    }

    @Nonnull
    private static String firstItemId(@Nonnull List<NpcEconomyDefinition.ItemAmount> items) {
        return items.isEmpty() ? "" : items.get(0).itemId;
    }

    private static int firstItemAmount(@Nonnull List<NpcEconomyDefinition.ItemAmount> items) {
        return items.isEmpty() ? 1 : items.get(0).amount;
    }

    @Nonnull
    private static String formatTransform(@Nullable Transform transform) {
        if (transform == null) {
            return "<none>";
        }
        return String.format(
                "x %.1f y %.1f z %.1f  yaw %.2f",
                transform.getPosition().getX(),
                transform.getPosition().getY(),
                transform.getPosition().getZ(),
                transform.getRotation().getY()
        );
    }

    @Nonnull
    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    @Nonnull
    private static String pretty(@Nonnull String raw) {
        return raw.toLowerCase().replace('_', ' ');
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    @Nonnull
    private static String join(@Nonnull List<String> values) {
        return values.isEmpty() ? "<none>" : String.join(", ", values);
    }

    @Nonnull
    private String tabLabel(@Nonnull String label, @Nonnull Section tabSection) {
        return this.section == tabSection ? "[" + label + "]" : label;
    }

    @Nonnull
    private String getSectionTitle() {
        return switch (this.section) {
            case OVERVIEW -> "Overview";
            case STORY -> "Story";
            case PLACEMENT -> "Placement";
            case WORKSTATION -> "Workstation";
            case QUESTS -> "Quests";
            case TRADES -> "Trading";
            case DEBUG -> "Debug";
        };
    }

    @Nonnull
    private String getSectionDescription() {
        return switch (this.section) {
            case OVERVIEW -> "Quick read of story state, current presence, services, and validation.";
            case STORY -> "Control rescue progression and review story dependencies.";
            case PLACEMENT -> "Control where this NPC appears and save spawn overrides.";
            case WORKSTATION -> "Test workstation assignment and level gating for this NPC.";
            case QUESTS -> "Inspect and reset the quest state attached to this NPC.";
            case TRADES -> "Edit what this NPC sells and what each offer costs.";
            case DEBUG -> "Validation, refresh, and reset tools.";
        };
    }

    private void normalizeTradeSelection(@Nonnull List<NpcEconomyDefinition.OfferDefinition> trades) {
        if (trades.isEmpty()) {
            setTradeIndex(0);
            return;
        }
        int index = getTradeIndex();
        if (index < 0) {
            setTradeIndex(0);
        } else if (index >= trades.size()) {
            setTradeIndex(trades.size() - 1);
        }
    }

    @Nullable
    private NpcEconomyDefinition.OfferDefinition getSelectedTrade(@Nonnull List<NpcEconomyDefinition.OfferDefinition> trades) {
        if (trades.isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(getTradeIndex(), trades.size() - 1));
        return trades.get(index);
    }

    private int getTradeIndex() {
        return TRADE_INDEX_BY_PLAYER_NPC.getOrDefault(tradeSelectionKey(), 0);
    }

    private void setTradeIndex(int index) {
        TRADE_INDEX_BY_PLAYER_NPC.put(tradeSelectionKey(), Math.max(0, index));
    }

    @Nonnull
    private String tradeSelectionKey() {
        UUID playerId = this.playerRef.getUuid();
        return playerId + "|" + this.npcKey;
    }

    @Nonnull
    private String defaultAssignedWorkstationId() {
        NpcDefinition definition = NpcUnifiedRegistry.get().getNpc(this.npcKey);
        if (definition == null) {
            return "assigned-workstation";
        }
        if (definition.workstation.workstationType != null && !definition.workstation.workstationType.isBlank()) {
            return definition.workstation.workstationType + "-station";
        }
        return definition.npcKey + "-station";
    }
}
