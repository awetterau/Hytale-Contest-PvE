package dev.hytalemodding.ui.quest;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.quest.QuestDefinition;
import dev.hytalemodding.quest.QuestProgressManager;

import javax.annotation.Nonnull;
import java.util.List;

public final class QuestLogPage extends InteractiveCustomUIPage<QuestLogPage.Data> {
    public static final class Data {
        public String action;

        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .addField(new KeyedCodec("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
                .build();
    }

    public QuestLogPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        List<QuestDefinition> quests = QuestProgressManager.get().getActiveQuestDefinitions();
        ui.append("Pages/QuestLog.ui");
        ui.set("#QuestLogStatus.Text", "Active quests: " + quests.size());
        ui.set("#QuestLogEmptyState.Visible", quests.isEmpty());

        for (int i = 0; i < 5; i++) {
            boolean visible = i < quests.size();
            String row = "#QuestRow" + (i + 1);
            ui.set(row + ".Visible", visible);
            if (!visible) {
                continue;
            }

            QuestDefinition quest = quests.get(i);
            String summary = quest.journalSummary == null || quest.journalSummary.isBlank()
                    ? quest.summary
                    : quest.journalSummary;
            List<String> progressLines = QuestProgressManager.get().getProgressLines(quest);
            float overall = (float) QuestProgressManager.get().getOverallProgressRatio(quest);

            ui.set(row + "Title.Text", quest.title);
            ui.set(row + "Summary.Text", summary);
            ui.set(row + "Progress.Text", progressLines.isEmpty() ? "" : String.join("\n", progressLines));
        }

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn", EventData.of("Action", "close"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        close();
    }
}
