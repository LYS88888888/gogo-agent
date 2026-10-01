package com.gogo.travel.config;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.reader.AbstractChunkingReader;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.ReaderUtils;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TableFormat;
import io.agentscope.core.rag.reader.TextChunker;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import reactor.core.publisher.Mono;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 修复版 WordReader —— 解决原版空段落被丢弃导致 PARAGRAPH 策略退化为 CHARACTER 的问题。
 *
 * <p>原版 {@link io.agentscope.core.rag.reader.WordReader} 在合并段落时，
 * 会跳过空段落（{@code !text.isEmpty()} 过滤），导致文档中的空行（Word 中的空 {@code <w:p>} 元素）
 * 被完全丢弃。最终合并出的 TextBlock 中段落间只有单个 {@code \n}，而 {@link TextChunker} 的
 * PARAGRAPH 策略依赖 {@code \n\s*\n}（双换行）来识别段落边界，因此永远匹配不到，
 * 退化为按字符硬切。
 *
 * <p><b>修复点</b>：空段落不再跳过，而是向当前 TextBlock 追加一个 {@code \n}，
 * 使得相邻非空段落之间产生 {@code \n\n}，让 PARAGRAPH 策略能正确识别段落边界。
 *
 * <p>相比原版的简化：
 * <ul>
 *   <li>移除图片提取（政策文档不需要）</li>
 *   <li>保留表格提取与 Markdown/JSON 格式化</li>
 *   <li>保留 separateTable 选项</li>
 * </ul>
 *
 */
public class FixedWordReader extends AbstractChunkingReader {

    private final boolean separateTable;
    private final TableFormat tableFormat;

    /**
     * 完整版构造函数。
     *
     * @param chunkSize       每个 chunk 的最大字符数
     * @param splitStrategy   切分策略（推荐 PARAGRAPH）
     * @param overlapSize     相邻 chunk 的重叠字符数
     * @param separateTable   表格是否独立成块（不与上下文合并）
     * @param tableFormat     表格输出格式（MARKDOWN 或 JSON）
     */
    public FixedWordReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize,
                           boolean separateTable, TableFormat tableFormat) {
        super(chunkSize, splitStrategy, overlapSize);
        if (tableFormat == null) {
            throw new IllegalArgumentException("Table format cannot be null");
        }
        this.separateTable = separateTable;
        this.tableFormat = tableFormat;
    }

    /**
     * 默认配置：chunkSize=512, PARAGRAPH, overlap=50, 表格独立, Markdown 格式。
     */
    public FixedWordReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, true, TableFormat.MARKDOWN);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new IllegalArgumentException("Input cannot be null"));
        }

        return Mono.fromCallable(() -> {
            try {
                String wordPath = input.asString();
                List<TextBlock> blocks = extractBlocks(wordPath);
                String docId = ReaderUtils.generateDocIdMD5(wordPath);

                List<Document> documents = new ArrayList<>();
                for (TextBlock block : blocks) {
                    String text = block.getText();
                    List<String> chunks = TextChunker.chunkText(text, chunkSize, splitStrategy, overlapSize);
                    for (String chunk : chunks) {
                        TextBlock contentBlock = TextBlock.builder().text(chunk).build();
                        DocumentMetadata metadata = new DocumentMetadata(contentBlock, docId, "0");
                        documents.add(new Document(metadata));
                    }
                }

                // 重新编号 chunkId
                List<Document> result = new ArrayList<>();
                for (int i = 0; i < documents.size(); i++) {
                    Document doc = documents.get(i);
                    DocumentMetadata old = doc.getMetadata();
                    DocumentMetadata updated = new DocumentMetadata(old.getContent(), old.getDocId(), String.valueOf(i));
                    result.add(new Document(updated));
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException("Failed to read Word document: " + input, e);
            }
        });
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("docx");
    }

    // ==================== 核心修复：段落提取 ====================

    /**
     * 从 docx 提取内容块（文本 + 表格）。
     *
     * <p><b>与原版 WordReader 的关键差异</b>：
     * 空段落不再跳过，而是向当前 TextBlock 追加 {@code \n}，保证相邻非空段落间产生
     * {@code \n\n}，让 {@link TextChunker} 的 PARAGRAPH 策略能正确识别段落边界。
     */
    private List<TextBlock> extractBlocks(String wordPath) throws IOException {
        Path path = Paths.get(wordPath);
        if (!Files.exists(path)) {
            throw new IOException("Word document not found: " + wordPath);
        }

        try (FileInputStream fis = new FileInputStream(wordPath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            List<TextBlock> blocks = new ArrayList<>();
            String lastType = null;  // "text" | "table"

            for (IBodyElement element : doc.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph para = (XWPFParagraph) element;
                    String text = extractText(para);

                    if (!text.isEmpty()) {
                        // 非空段落：正常合并逻辑
                        if ("text".equals(lastType) || ("table".equals(lastType) && !separateTable)) {
                            int idx = blocks.size() - 1;
                            TextBlock last = blocks.get(idx);
                            blocks.set(idx, TextBlock.builder().text(last.getText() + "\n" + text).build());
                        } else {
                            blocks.add(TextBlock.builder().text(text).build());
                        }
                        lastType = "text";
                    } else {
                        // ★ 修复点：空段落不跳过，追加 \n 以保留段落边界
                        if (!blocks.isEmpty() && "text".equals(lastType)) {
                            int idx = blocks.size() - 1;
                            TextBlock last = blocks.get(idx);
                            blocks.set(idx, TextBlock.builder().text(last.getText() + "\n").build());
                        }
                    }

                } else if (element.getElementType() == BodyElementType.TABLE) {
                    XWPFTable table = (XWPFTable) element;
                    List<List<String>> tableData = extractTableData(table);
                    String tableText = (tableFormat == TableFormat.MARKDOWN)
                            ? tableToMarkdown(tableData)
                            : tableToJson(tableData);

                    if (!separateTable && ("text".equals(lastType) || "table".equals(lastType))) {
                        int idx = blocks.size() - 1;
                        TextBlock last = blocks.get(idx);
                        blocks.set(idx, TextBlock.builder().text(last.getText() + "\n" + tableText).build());
                    } else {
                        blocks.add(TextBlock.builder().text(tableText).build());
                    }
                    lastType = "table";
                }
            }

            return blocks;
        }
    }

    // ==================== 文本提取 ====================

    private String extractText(XWPFParagraph para) {
        if (para == null) {
            return "";
        }
        String text = para.getText();
        if (text != null && !text.isEmpty()) {
            return text.trim();
        }
        // 回退：从 Run 中逐段拼接
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String runText = run.getText(0);
            if (runText != null) {
                sb.append(runText);
            }
        }
        return sb.toString().trim();
    }

    // ==================== 表格提取 ====================

    private List<List<String>> extractTableData(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                StringBuilder cellText = new StringBuilder();
                for (XWPFParagraph para : cell.getParagraphs()) {
                    String t = para.getText();
                    if (t != null && !t.isEmpty()) {
                        if (cellText.length() > 0) {
                            cellText.append("\n");
                        }
                        cellText.append(t);
                    }
                }
                cells.add(cellText.toString());
            }
            rows.add(cells);
        }
        return rows;
    }

    private String tableToMarkdown(List<List<String>> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder();
        int cols = data.get(0).size();

        // 表头
        md.append("| ").append(String.join(" | ", data.get(0))).append(" |\n");
        // 分隔行
        md.append("| ");
        for (int i = 0; i < cols; i++) {
            md.append("---");
            if (i < cols - 1) md.append(" | ");
        }
        md.append(" |\n");
        // 数据行
        for (int i = 1; i < data.size(); i++) {
            md.append("| ").append(String.join(" | ", data.get(i))).append(" |\n");
        }
        return md.toString();
    }

    private String tableToJson(List<List<String>> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        StringBuilder json = new StringBuilder();
        json.append("<system-info>A table loaded as a JSON array:</system-info>\n");
        for (List<String> row : data) {
            json.append("[");
            for (int i = 0; i < row.size(); i++) {
                json.append("\"").append(escapeJson(row.get(i))).append("\"");
                if (i < row.size() - 1) json.append(", ");
            }
            json.append("]\n");
        }
        return json.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
