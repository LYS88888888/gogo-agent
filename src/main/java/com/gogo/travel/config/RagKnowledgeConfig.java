package com.gogo.travel.config;

import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TableFormat;
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.rag.store.InMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.File;
import java.util.List;

/**
 * 景点 RAG 知识库装配配置。
 *
 */
@Configuration
public class RagKnowledgeConfig {

    /**
     * text-embedding-v4 默认输出 1024 维向量；显式声明并同时传给 embedding 与 InMemoryStore，
     * 避免将来升级 embedding 模型/维度时两端静默错位（与 {@link IntentRouterKnowledgeConfig} 同约定）。
     */
    private static final int EMBEDDING_DIM = 1024;

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${agentscope.bailian.access-key-id}")
    private String accessKeyId;

    @Value("${agentscope.bailian.access-key-secret}")
    private String accessKeySecret;

    @Value("${agentscope.bailian.workspace-id}")
    private String workspaceId;

    @Value("${agentscope.bailian.index-id}")
    private String indexId;

    @Bean(name = "attractionKnowledge")
    public Knowledge attractionKnowledge() {
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .build();

        return BailianKnowledge.builder()
                .config(config)
                .indexId(indexId)
                .build();
    }

    @Bean(name = "corporateTravelPolicyKnowledge")
    public Knowledge corporateTravelPolicyKnowledge(@Value("classpath:dataset/business_travel_policy.docx") Resource policyResource) {
        return buildDocxKnowledge(policyResource, "dataset/business_travel_policy.docx", 400);
    }

    @Bean(name = "corporateTravelGuidelinesKnowledge")
    public Knowledge corporateTravelGuidelinesKnowledge(@Value("classpath:dataset/business_travel_guidelines.docx") Resource fileResource) {
        return buildDocxKnowledge(fileResource, "dataset/business_travel_guidelines.docx", 500);
    }

    /**
     * 用单个 docx 语料构建内存版 RAG 知识库：DashScope embedding + InMemoryStore，
     * 启动期同步解析并写入向量库（语料小，fail-fast 优于运行期降级）。
     *
     * @param resource classpath 下的 docx 资源
     * @param label    资源相对路径，仅用于报错提示
     */
    private Knowledge buildDocxKnowledge(Resource resource, String label, Integer chunkSize) {
        DashScopeTextEmbedding embedding = DashScopeTextEmbedding.builder()
                .modelName("text-embedding-v4")
                .dimensions(EMBEDDING_DIM)
                .apiKey(apiKey)
                .build();

        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingStore(InMemoryStore.builder().dimensions(EMBEDDING_DIM).build())
                .embeddingModel(embedding)
                .build();

        FixedWordReader reader = new FixedWordReader(chunkSize, SplitStrategy.PARAGRAPH, 0, true, TableFormat.MARKDOWN);
        try {
            File docxFile = resource.getFile();
            List<Document> docs = reader.read(ReaderInput.fromPath(docxFile.toPath())).block();
            knowledge.addDocuments(docs).block();
        } catch (Exception e) {
            // 资源缺失或解析失败时主动报错，提示开发者补齐语料
            throw new IllegalStateException(
                    "从 classpath 读取 [" + label + "] 失败，请确认文件存在于 src/main/resources/dataset/ 目录下。", e);
        }

        return knowledge;
    }

}
