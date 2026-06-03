package com.iflytek.chatbot.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 双 Milvus 向量存储配置
 *
 * <p>提供两个独立的 VectorStore：</p>
 * <ul>
 *   <li>{@code userMemoryVectorStore}（@Primary）—— 用户对话记忆，collection: user_memory</li>
 *   <li>{@code ragVectorStore} —— RAG 知识库，collection: rag_knowledge</li>
 * </ul>
 *
 * <p>两个 collection 共享同一 Milvus 实例和 EmbeddingModel，通过不同的 collection 实现数据隔离。</p>
 */
@Configuration
public class RagVectorStoreConfig {

    // ======================== Milvus 连接参数 ========================

    @Value("${spring.ai.vectorstore.milvus.client.host}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.client.port}")
    private int port;

    @Value("${spring.ai.vectorstore.milvus.databaseName}")
    private String databaseName;

    // ======================== 用户记忆配置 ========================

    @Value("${spring.ai.vectorstore.milvus.collectionName}")
    private String userMemoryCollection;

    @Value("${spring.ai.vectorstore.milvus.embeddingDimension}")
    private int embeddingDimension;

    // ======================== RAG 知识库配置 ========================

    @Value("${spring.ai.rag.vectorstore.milvus.collectionName}")
    private String ragCollection;

    @Value("${spring.ai.rag.vectorstore.milvus.embeddingDimension}")
    private int ragEmbeddingDimension;

    // ======================== Milvus 客户端 ========================

    /** 用户记忆 Milvus 客户端 */
    @Bean
    public MilvusServiceClient userMemoryMilvusClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withDatabaseName(databaseName)
                        .build()
        );
    }

    /** RAG 知识库 Milvus 客户端 */
    @Bean
    public MilvusServiceClient ragMilvusClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withDatabaseName(databaseName)
                        .build()
        );
    }

    // ======================== VectorStore ========================

    /**
     * 用户记忆向量存储（默认 VectorStore）
     * <p>用于存储对话片段和用户事实，支持语义检索历史对话</p>
     */
    @Primary
    @Bean
    public VectorStore userMemoryVectorStore(
            @Qualifier("userMemoryMilvusClient") MilvusServiceClient userMemoryMilvusClient,
            EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(userMemoryMilvusClient, embeddingModel)
                .collectionName(userMemoryCollection)
                .databaseName(databaseName)
                .initializeSchema(true)
                .build();
    }

    /**
     * RAG 知识库向量存储
     * <p>用于存储外部知识文档，支持检索增强生成（RAG）</p>
     */
    @Bean
    public VectorStore ragVectorStore(
            @Qualifier("ragMilvusClient") MilvusServiceClient ragMilvusClient,
            EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(ragMilvusClient, embeddingModel)
                .collectionName(ragCollection)
                .databaseName(databaseName)
                .initializeSchema(true)
                .build();
    }
}
