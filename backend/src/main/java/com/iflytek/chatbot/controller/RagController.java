package com.iflytek.chatbot.controller;

import com.iflytek.chatbot.dto.RagDocumentResponse;
import com.iflytek.chatbot.dto.Result;
import com.iflytek.chatbot.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG 知识库", description = "上传、查看、删除知识库文档")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/upload")
    @Operation(summary = "上传知识文档", description = "上传 PDF/TXT/MD 文件到用户知识库，系统自动分块向量化")
    public Result<RagDocumentResponse> upload(
            @RequestParam("userId") String userId,
            @RequestParam("file") MultipartFile file) {
        RagDocumentResponse response = ragService.uploadDocument(userId, file);

        log.info("========== [Controller] RAG 上传完成 | documentId={}, status={}, chunks={} ==========",
                response.id(), response.status(), response.chunkCount());
        return Result.success(response);
    }

    @GetMapping("/documents")
    @Operation(summary = "文档列表", description = "获取指定用户的所有知识库文档")
    public Result<List<RagDocumentResponse>> listDocuments(@RequestParam String userId) {
        log.info("========== [Controller] RAG 文档列表 | userId={} ==========", userId);
        List<RagDocumentResponse> docs = ragService.listDocuments(userId);
        log.info("========== [Controller] RAG 返回 {} 个文档 ==========", docs.size());
        return Result.success(docs);
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "删除知识文档", description = "删除指定文档及其所有向量数据")
    public Result<Void> deleteDocument(
            @RequestParam String userId,
            @PathVariable Long documentId) {
        log.info("========== [Controller] RAG 删除文档 | userId={}, documentId={} ==========",
                userId, documentId);
        ragService.deleteDocument(userId, documentId);
        return Result.success();
    }
}
