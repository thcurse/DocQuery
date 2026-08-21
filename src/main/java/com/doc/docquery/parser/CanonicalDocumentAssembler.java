package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.service.DocumentParseException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/**
 * 将各格式的中立 ParsedBlock 组装为稳定 EvidenceBlock 和真实标题树。
 *
 * <p>这里统一处理 ID、标题栈、章节范围、安全拆分和 canonical offset，避免四个
 * Adapter 各自形成不兼容规则。</p>
 */
@Component
public class CanonicalDocumentAssembler {

    public static final String PARSER_NAME = "docquery-canonical-parser";

    private final DocumentParsingProperties properties;

    public CanonicalDocumentAssembler(DocumentParsingProperties properties) {
        this.properties = properties;
    }

    /** 组装并计算标准化全文摘要；根标题来自可信逻辑文档名称。 */
    public CanonicalDocument assemble(
            long documentVersionId,
            String documentName,
            DocumentSourceFormat format,
            String sourceSha256,
            ParsedDocument parsed,
            Instant generatedAt
    ) {
        List<ParseWarning> warnings = new ArrayList<>(parsed.warnings());
        List<EvidenceBlock> blocks = new ArrayList<>();
        List<MutableHeading> headings = new ArrayList<>();
        Deque<MutableHeading> stack = new ArrayDeque<>();
        MessageDigest textDigest = sha256Digest();

        String rootId = documentVersionId + ":h:root";
        MutableHeading root = new MutableHeading(
                rootId, null, 0, 0, documentName,
                null, "DOCUMENT_ROOT", 0
        );
        headings.add(root);
        stack.push(root);

        long textLength = 0;
        int headingSequence = 0;
        int sourceElementSequence = 0;
        for (ParsedBlock sourceBlock : parsed.blocks()) {
            String normalized = normalize(sourceBlock.kind(), sourceBlock.text());
            if (normalized.isBlank()) {
                sourceElementSequence++;
                continue;
            }

            MutableHeading currentHeading = stack.peek();
            String headingNodeId = currentHeading.nodeId;
            if (sourceBlock.headingLevel() != null && !sourceBlock.documentTitle()) {
                int level = sourceBlock.headingLevel();
                if (level < 1 || level > 6) {
                    throw invalid("Heading level is outside 1-6");
                }
                while (stack.size() > 1 && stack.peek().declaredLevel >= level) {
                    close(stack.pop(), blocks.size());
                }
                MutableHeading parent = stack.peek();
                if (level > parent.declaredLevel + 1) {
                    warnings.add(new ParseWarning(
                            "HEADING_LEVEL_JUMP",
                            "Heading level jumps without a real intermediate heading",
                            sourceBlock.sourcePosition()
                    ));
                }
                headingSequence++;
                headingNodeId = documentVersionId + ":h:"
                        + String.format("%06d", headingSequence);
                MutableHeading heading = new MutableHeading(
                        headingNodeId,
                        parent.nodeId,
                        level,
                        parent.depth + 1,
                        normalized,
                        null,
                        sourceBlock.detectionSource(),
                        blocks.size()
                );
                headings.add(heading);
                stack.push(heading);
                currentHeading = heading;
            }

            List<String> pieces = splitSafely(normalized, properties.getMaxBlockChars());
            String continuationGroupId = pieces.size() > 1
                    ? documentVersionId + ":c:" + String.format(
                    "%06d", sourceElementSequence
            )
                    : null;
            for (String piece : pieces) {
                if (blocks.size() >= properties.getMaxBlocks()) {
                    throw new DocumentParseException(
                            "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                            "Document contains too many evidence blocks",
                            false
                    );
                }
                int ordinal = blocks.size();
                if (ordinal > 0) {
                    textDigest.update("\n\n".getBytes(StandardCharsets.UTF_8));
                    textLength += 2;
                }
                long start = textLength;
                textDigest.update(piece.getBytes(StandardCharsets.UTF_8));
                textLength += piece.length();
                String blockId = documentVersionId + ":b:"
                        + String.format("%06d", ordinal);
                EvidenceBlock block = new EvidenceBlock(
                        blockId,
                        ordinal,
                        sourceBlock.kind(),
                        piece,
                        start,
                        textLength,
                        headingNodeId,
                        continuationGroupId,
                        sourceBlock.sourcePosition()
                );
                blocks.add(block);
                if (sourceBlock.headingLevel() != null
                        && currentHeading.sourceHeadingBlockId == null) {
                    currentHeading.sourceHeadingBlockId = blockId;
                }
            }
            sourceElementSequence++;
        }

        while (stack.size() > 1) {
            close(stack.pop(), blocks.size());
        }
        close(root, blocks.size());
        if (blocks.isEmpty()) {
            throw new DocumentParseException(
                    format == DocumentSourceFormat.PDF
                            ? "PDF_NO_EXTRACTABLE_TEXT"
                            : "DOCUMENT_CORRUPT",
                    "Document contains no extractable text",
                    false
            );
        }
        if (headings.size() == 1) {
            warnings.add(new ParseWarning(
                    "NO_REAL_HEADINGS",
                    "Document contains no verifiable section headings",
                    null
            ));
        }

        List<HeadingNode> immutableHeadings = headings.stream()
                .map(MutableHeading::freeze)
                .toList();
        return new CanonicalDocument(
                properties.getSchemaVersion(),
                documentVersionId,
                format.name(),
                sourceSha256,
                PARSER_NAME,
                properties.getParserVersion(),
                generatedAt,
                blocks,
                immutableHeadings,
                warnings,
                parsed.pageCount(),
                textLength,
                HexFormat.of().formatHex(textDigest.digest())
        );
    }

    private String normalize(BlockKind kind, String text) {
        if (text == null) {
            return "";
        }
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\uFEFF", "");
        if (kind == BlockKind.CODE_BLOCK) {
            int start = 0;
            int end = normalized.length();
            while (start < end && normalized.charAt(start) == '\n') {
                start++;
            }
            while (end > start && normalized.charAt(end - 1) == '\n') {
                end--;
            }
            return normalized.substring(start, end);
        }
        return normalized.strip();
    }

    /**
     * 超大源元素优先从最后四分之一内的自然边界拆分；不重叠、不改变章节。
     */
    private List<String> splitSafely(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(text.length(), start + maxChars);
            if (hardEnd < text.length() && Character.isHighSurrogate(text.charAt(hardEnd - 1))) {
                hardEnd--;
            }
            int split = hardEnd;
            int naturalFloor = start + (hardEnd - start) * 3 / 4;
            for (int cursor = hardEnd - 1; cursor >= naturalFloor; cursor--) {
                char value = text.charAt(cursor);
                if (value == '\n'
                        || value == '。'
                        || value == '！'
                        || value == '？'
                        || value == '.'
                        || value == '!'
                        || value == '?') {
                    split = cursor + 1;
                    break;
                }
            }
            String piece = text.substring(start, split).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            start = split;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return pieces;
    }

    private void close(MutableHeading heading, int blockCount) {
        heading.sectionEndBlockOrdinalExclusive = blockCount;
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private DocumentParseException invalid(String message) {
        return new DocumentParseException(
                "CANONICAL_VALIDATION_FAILED",
                message,
                false
        );
    }

    /** 标题范围只有读完后才确定，因此组装阶段使用私有可变状态。 */
    private static final class MutableHeading {

        private final String nodeId;
        private final String parentNodeId;
        private final int declaredLevel;
        private final int depth;
        private final String title;
        private String sourceHeadingBlockId;
        private final String detectionSource;
        private final int sectionStartBlockOrdinal;
        private int sectionEndBlockOrdinalExclusive;

        private MutableHeading(
                String nodeId,
                String parentNodeId,
                int declaredLevel,
                int depth,
                String title,
                String sourceHeadingBlockId,
                String detectionSource,
                int sectionStartBlockOrdinal
        ) {
            this.nodeId = nodeId;
            this.parentNodeId = parentNodeId;
            this.declaredLevel = declaredLevel;
            this.depth = depth;
            this.title = title;
            this.sourceHeadingBlockId = sourceHeadingBlockId;
            this.detectionSource = detectionSource;
            this.sectionStartBlockOrdinal = sectionStartBlockOrdinal;
        }

        private HeadingNode freeze() {
            return new HeadingNode(
                    nodeId,
                    parentNodeId,
                    declaredLevel,
                    depth,
                    title,
                    sourceHeadingBlockId,
                    detectionSource,
                    sectionStartBlockOrdinal,
                    sectionEndBlockOrdinalExclusive
            );
        }
    }
}
