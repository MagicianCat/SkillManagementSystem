package com.company.skillplatform.auth.infrastructure;

import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.company.skillplatform.agent.application.FeishuBotMessageService;
import com.company.skillplatform.collabproject.application.CollabApprovalCardService;
import com.company.skillplatform.collabproject.application.CollabStagePromptCardService;
import com.company.skillplatform.collabproject.asdm.application.CollabAsdmPublishCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FeishuCardActionDispatcher {
    private static final Logger log = LoggerFactory.getLogger(FeishuCardActionDispatcher.class);
    private final FeishuBotMessageService botMessages;
    private final CollabApprovalCardService approvalCards;
    private final CollabStagePromptCardService stagePromptCards;
    private final CollabAsdmPublishCardService asdmPublishCards;

    public FeishuCardActionDispatcher(FeishuBotMessageService botMessages, CollabApprovalCardService approvalCards,
            CollabStagePromptCardService stagePromptCards, CollabAsdmPublishCardService asdmPublishCards) {
        this.botMessages = botMessages; this.approvalCards = approvalCards;
        this.stagePromptCards = stagePromptCards; this.asdmPublishCards = asdmPublishCards;
    }

    public P2CardActionTriggerResponse dispatch(P2CardActionTrigger event) {
        if (approvalCards.handles(event)) return approvalCards.handle(event);
        if (stagePromptCards.handles(event)) return stagePromptCards.handle(event);
        if (asdmPublishCards.handles(event)) return asdmPublishCards.handle(event);
        log.info("event=feishu.card_action.unmatched route=agent_fallback");
        return botMessages.handleCardAction(event);
    }
}
