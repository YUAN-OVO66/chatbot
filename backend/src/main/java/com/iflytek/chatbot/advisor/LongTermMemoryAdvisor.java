package com.iflytek.chatbot.advisor;

import com.iflytek.chatbot.entity.UserMemoryFact;
import com.iflytek.chatbot.service.LongTermMemoryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆 Advisor（order=2）：从 Milvus 检索与当前消息相关的用户事实/偏好，注入 SystemMessage。
 */
@Component
public class LongTermMemoryAdvisor extends AbstractContextInjectingAdvisor {

    private final LongTermMemoryService memoryService;

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    protected String headerLabel() {
        return "User Memory";
    }

    @Override
    protected String retrieveContext(String userId, String userMessage) {
        List<UserMemoryFact> facts = memoryService.retrieveRelevantFacts(userId, userMessage, 5);
        if (facts.isEmpty()) {
            return null;
        }
        return facts.stream()
                .map(f -> "- [" + f.getCategory() + "] " + f.getFactText())
                .collect(Collectors.joining("\n"));
    }
}
