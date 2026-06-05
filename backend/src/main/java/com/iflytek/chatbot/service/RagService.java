package com.iflytek.chatbot.service;

import com.iflytek.chatbot.dto.RagDocumentResponse;
import com.iflytek.chatbot.entity.RagDocument;
import com.iflytek.chatbot.repository.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final long MAX_FILE_SIZE = 30 * 1024 * 1024; // 20MB
    private static final Set<String> ALLOWED_TYPES = Set.of("pdf", "txt", "md");

    private final RagDocumentRepository ragDocumentRepository;
    private final VectorStore ragVectorStore;
    private final TokenTextSplitter textSplitter;

    public RagService(RagDocumentRepository ragDocumentRepository,
                      @Qualifier("ragVectorStore") VectorStore ragVectorStore) {
        this.ragDocumentRepository = ragDocumentRepository;
        this.ragVectorStore = ragVectorStore;
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(5)
                .withKeepSeparator(true)
                .build();
    }

    /**
     * 上传文档：验证 → 读取 → 分块 → 向量化 → 持久化
     */
    public RagDocumentResponse uploadDocument(String userId, MultipartFile file) {
        log.info("[RagService] 开始上传文档 | userId={}, fileName={}, fileSize={}",
                userId, file.getOriginalFilename(), file.getSize());

        // 1. 验证
        validateFile(file);
        String fileName = file.getOriginalFilename();
        String fileType = extractFileType(fileName);
        long fileSize = file.getSize();

        // 2. 创建 MySQL 记录
        RagDocument ragDocument = new RagDocument();
        ragDocument.setUserId(userId);
        ragDocument.setFileName(fileName);
        ragDocument.setFileType(fileType);
        ragDocument.setFileSize(fileSize);
        ragDocument.setStatus("processing");
        ragDocument = ragDocumentRepository.save(ragDocument);
        log.info("[RagService] MySQL 记录已创建 | documentId={}", ragDocument.getId());

        try {
            // 3. 读取文件内容
            List<Document> sourceDocs = readFileContent(file, fileType);
            log.info("[RagService] 文件读取完成 | pages={}, fileType={}", sourceDocs.size(), fileType);

            // 4. 分块
            List<Document> rawChunks = textSplitter.apply(sourceDocs);
            log.info("[RagService] 分块完成 | chunkCount={}", rawChunks.size());

            // 5. 添加 RAG 元数据
            List<Document> ragChunks = new ArrayList<>();
            for (int i = 0; i < rawChunks.size(); i++) {
                ragChunks.add(new Document(
                        rawChunks.get(i).getText(),
                        Map.of(
                                "userId", userId,
                                "documentId", String.valueOf(ragDocument.getId()),
                                "fileName", fileName,
                                "chunkIndex", String.valueOf(i),
                                "type", "rag"
                        )
                ));
            }

            // 6. 分批向量化并存储到 Milvus（DashScope embedding 限制单次 batch <= 10）
            int batchSize = 10;
            for (int i = 0; i < ragChunks.size(); i += batchSize) {
                List<Document> batch = ragChunks.subList(i, Math.min(i + batchSize, ragChunks.size()));
                ragVectorStore.add(batch);
                log.info("[RagService] 向量存储批次 {}/{} | batchSize={}",
                        i / batchSize + 1, (ragChunks.size() + batchSize - 1) / batchSize, batch.size());
            }
            log.info("[RagService] 向量存储完成 | chunkCount={}", ragChunks.size());

            // 7. 更新 MySQL 记录
            ragDocument.setStatus("completed");
            ragDocument.setChunkCount(ragChunks.size());
            ragDocument = ragDocumentRepository.save(ragDocument);
            log.info("[RagService] 文档上传完成 | documentId={}, chunks={}",
                    ragDocument.getId(), ragChunks.size());

            return toResponse(ragDocument);

        } catch (Exception e) {
            log.error("[RagService] 文档处理失败 | documentId={}, error={}",
                    ragDocument.getId(), e.getMessage(), e);
            ragDocument.setStatus("failed");
            ragDocument.setErrorMessage(e.getMessage());
            ragDocumentRepository.save(ragDocument);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询用户文档列表
     */
    public List<RagDocumentResponse> listDocuments(String userId) {
        log.info("[RagService] 查询用户文档列表 | userId={}", userId);
        List<RagDocumentResponse> docs = ragDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toResponse).toList();
        log.info("[RagService] 返回 {} 个文档 | userId={}", docs.size(), userId);
        return docs;
    }

    /**
     * 删除文档：删除 Milvus 向量 + MySQL 记录
     */
    public void deleteDocument(String userId, Long documentId) {
        log.info("[RagService] 删除文档 | userId={}, documentId={}", userId, documentId);

        RagDocument doc = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在: " + documentId));

        if (!doc.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除该文档");
        }

        // 删除 Milvus 向量
        ragVectorStore.delete("userId == '" + userId + "' && documentId == '" + documentId + "'");
        log.info("[RagService] Milvus 向量已删除 | documentId={}", documentId);

        // 删除 MySQL 记录
        ragDocumentRepository.deleteById(documentId);
        log.info("[RagService] 文档删除完成 | documentId={}", documentId);
    }

    /**
     * 语义检索相关文本块（供 RagAdvisor 调用）
     */
    public List<Document> searchRelevantChunks(String userId, String query, int topK) {
        log.info("[RagService] 检索相关文本块 | userId={}, topK={}, query={}",
                userId, topK, query.length() > 40 ? query.substring(0, 40) + "..." : query);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("userId == '" + userId + "' && type == 'rag'")
                .build();
        List<Document> results = ragVectorStore.similaritySearch(request);

        log.info("[RagService] 检索到 {} 个相关文本块 | userId={}", results.size(), userId);
        return results;
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("文件大小超过20MB限制");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new RuntimeException("文件名不能为空");
        }
        String fileType = extractFileType(fileName);
        if (!ALLOWED_TYPES.contains(fileType)) {
            throw new RuntimeException("不支持的文件类型: " + fileType + "，仅支持 PDF/TXT/MD");
        }
    }

    private String extractFileType(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase();
    }

    private List<Document> readFileContent(MultipartFile file, String fileType) throws Exception {
        var resource = new InputStreamResource(file.getInputStream());

        if ("pdf".equals(fileType)) {
            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
            return pdfReader.get();
        } else {
            // txt / md：作为纯文本读取
            org.springframework.ai.reader.TextReader textReader = new org.springframework.ai.reader.TextReader(resource);
            return textReader.get();
        }
    }

    private RagDocumentResponse toResponse(RagDocument entity) {
        return new RagDocumentResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getFileName(),
                entity.getFileType(),
                entity.getFileSize(),
                entity.getStatus(),
                entity.getChunkCount(),
                entity.getCreatedAt()
        );
    }
}
