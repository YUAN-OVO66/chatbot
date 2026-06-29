package com.iflytek.chatbot.advisor;

import com.iflytek.chatbot.service.RagService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 知识检索 Advisor（order=3）：从用户知识库检索相关文档片段，注入 SystemMessage。
 */
@Component
public class RagAdvisor extends AbstractContextInjectingAdvisor {

    private final RagService ragService;

    public RagAdvisor(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    protected String headerLabel() {
        return "Relevant Knowledge";
    }

    @Override
    protected String retrieveContext(String userId, String userMessage) {
        List<Document> chunks = ragService.searchRelevantChunks(userId, userMessage, 5);
        if (chunks.isEmpty()) {
            return null;
        }
        return chunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
