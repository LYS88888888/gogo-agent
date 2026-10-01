package com.gogo.travel.config;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagKnowledgeConfig} 差旅政策/规范知识库语料护栏。
 *
 * <p>{@code knowledge.retrieve(...)} 依赖 DashScope embedding 真实网络与 API-Key，无法离线做召回断言。
 * 这里退一步守住数据源与解析链路（与 {@link IntentRouterSeedExamplesTest} 的离线护栏思路一致）：
 * 直接用 {@link FixedWordReader} 解析两个 docx 语料，防止"空文件被误提交"或"解析/分块退化"静默上线：</p>
 * <ul>
 *   <li>docx 资源存在且可被 FixedWordReader 解析出非空 chunk；</li>
 *   <li>每个 chunk 内容非空白；</li>
 *   <li>全文包含语料中已知的标志性关键词（按 chunk 顺序拼接后校验，不受分块边界影响）。</li>
 * </ul>
 *
 */
@DisplayName("差旅政策知识库 docx 语料解析护栏")
class RagKnowledgeCorpusTest {

    @Test
    @DisplayName("差旅管理制度 docx 可解析且包含标志性关键词")
    void shouldParsePolicyDocxWithKnownKeyword() {
        assertDocxReadable("/dataset/business_travel_policy.docx", "差旅管理制度");
    }

    @Test
    @DisplayName("差旅注意事项 docx 可解析且包含标志性关键词")
    void shouldParseGuidelinesDocxWithKnownKeyword() {
        assertDocxReadable("/dataset/business_travel_guidelines.docx", "出差申请单");
    }

    /**
     * 解析 classpath 下的 docx 并做三层断言：chunk 非空、内容非空白、全文含已知关键词。
     */
    private void assertDocxReadable(String classpathLocation, String expectedKeyword) {
        Path docxPath = resolveClasspathFile(classpathLocation);

        List<Document> docs = new FixedWordReader().read(ReaderInput.fromPath(docxPath)).block();
        assertNotNull(docs, classpathLocation + " 解析结果为 null");
        assertFalse(docs.isEmpty(), classpathLocation + " 解析出 0 个 chunk，语料可能是空文件");

        for (Document doc : docs) {
            String text = doc.getMetadata().getContentText();
            assertTrue(text != null && !text.isBlank(),
                    classpathLocation + " 存在空白 chunk（docId=" + doc.getMetadata().getDocId() + "）");
        }

        // 按 chunk 顺序拼接全文再查关键词，避免关键词恰好被分块边界切开导致误报
        String fullText = docs.stream()
                .map(doc -> doc.getMetadata().getContentText())
                .collect(Collectors.joining());
        assertTrue(fullText.contains(expectedKeyword),
                String.format("%s 全文（%d 个 chunk，共 %d 字）未找到关键词「%s」，语料内容可能被误替换",
                        classpathLocation, docs.size(), fullText.length(), expectedKeyword));
    }

    private Path resolveClasspathFile(String classpathLocation) {
        URL url = getClass().getResource(classpathLocation);
        assertNotNull(url, "未找到 " + classpathLocation + "，请确认文件存在于 src/main/resources/dataset/ 目录下");
        try {
            return Paths.get(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("解析 " + classpathLocation + " 路径失败", e);
        }
    }
}
