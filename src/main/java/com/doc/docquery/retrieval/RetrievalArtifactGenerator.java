package com.doc.docquery.retrieval;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.parser.BlockKind;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.parser.SourcePosition;
import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * N2.4 核心算法：按真实标题树自底向上生成语义卡，再批量生成导航向量。
 * 临时长文本摘要只存在于内存，任何部分结果都不会写入对象存储或数据库。
 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.retrieval",
        name = "provider-enabled",
        havingValue = "true"
)
public class RetrievalArtifactGenerator {

    private static final String CORRECTION_HINT_PREFIX =
            "Previous output failed local schema validation: ";

    private final RetrievalCardChatGateway chatGateway;
    private final NavigationEmbeddingGateway embeddingGateway;
    private final RetrievalSemanticValidator semanticValidator;
    private final NavigationTextBuilder textBuilder;
    private final EmbeddingCodec embeddingCodec;
    private final RetrievalGenerationFingerprint fingerprint;
    private final DocumentRetrievalProperties properties;

    public RetrievalArtifactGenerator(
            RetrievalCardChatGateway chatGateway,
            NavigationEmbeddingGateway embeddingGateway,
            RetrievalSemanticValidator semanticValidator,
            NavigationTextBuilder textBuilder,
            EmbeddingCodec embeddingCodec,
            RetrievalGenerationFingerprint fingerprint,
            DocumentRetrievalProperties properties
    ) {
        this.chatGateway = chatGateway;
        this.embeddingGateway = embeddingGateway;
        this.semanticValidator = semanticValidator;
        this.textBuilder = textBuilder;
        this.embeddingCodec = embeddingCodec;
        this.fingerprint = fingerprint;
        this.properties = properties;
    }

    public RetrievalArtifact generate(
            CanonicalDocument document,
            String documentTitle,
            String canonicalSha256
    ) {
        validateLimits(document);
        GenerationBudget budget = new GenerationBudget();
        Map<String, HeadingNode> byId = document.headings().stream()
                .collect(Collectors.toMap(
                        HeadingNode::nodeId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        HeadingNode root = document.headings().stream()
                .filter(heading -> heading.parentNodeId() == null)
                .findFirst()
                .orElseThrow(() -> limit("Canonical heading root is missing"));
        Map<String, List<HeadingNode>> children = childrenByParent(document.headings());
        Map<String, List<EvidenceBlock>> directBlocks = blocksByHeading(document.blocks());
        Map<String, RetrievalNodeSemantic> semantics = new HashMap<>();

        int maximumDepth = document.headings().stream()
                .mapToInt(HeadingNode::depth).max().orElse(0);
        for (int depth = maximumDepth; depth >= 1; depth--) {
            int currentDepth = depth;
            List<HeadingNode> level = document.headings().stream()
                    .filter(heading -> heading.depth() == currentDepth)
                    .toList();
            List<RetrievalCardChatGateway.NodeInput> inputs = new ArrayList<>();
            for (HeadingNode heading : level) {
                String source = sectionSource(heading, directBlocks, children, semantics);
                source = reduceSourceIfNeeded(
                        source,
                        titlePath(heading, byId),
                        budget
                );
                inputs.add(new RetrievalCardChatGateway.NodeInput(
                        heading.nodeId(),
                        titlePath(heading, byId),
                        source
                ));
            }
            for (RetrievalCardChatGateway.GeneratedNode generated
                    : invokeNodeInputs(inputs, budget)) {
                semantics.put(generated.requestId(), generated.semantic());
            }
        }

        String profileSource = sectionSource(root, directBlocks, children, semantics);
        profileSource = reduceSourceIfNeeded(profileSource, documentTitle, budget);
        DocumentProfileSemantic profileSemantic = invokeProfile(
                new RetrievalCardChatGateway.ProfileInput(
                        document.documentVersionId() + ":profile",
                        documentTitle,
                        profileSource
                ),
                budget
        );

        return attachEmbeddings(
                document,
                documentTitle,
                canonicalSha256,
                byId,
                semantics,
                profileSemantic
        );
    }

    private void validateLimits(CanonicalDocument document) {
        long rawChars = document.blocks().stream().mapToLong(block -> block.text().length()).sum();
        if (rawChars > properties.getMaxTotalSourceChars()
                || document.headings().size() > properties.getMaxCards()) {
            throw limit("Document exceeds N2.4 generation limits");
        }
    }

    private Map<String, List<HeadingNode>> childrenByParent(List<HeadingNode> headings) {
        Map<String, List<HeadingNode>> result = new HashMap<>();
        for (HeadingNode heading : headings) {
            if (heading.parentNodeId() != null) {
                result.computeIfAbsent(heading.parentNodeId(), ignored -> new ArrayList<>())
                        .add(heading);
            }
        }
        return result;
    }

    private Map<String, List<EvidenceBlock>> blocksByHeading(List<EvidenceBlock> blocks) {
        Map<String, List<EvidenceBlock>> result = new HashMap<>();
        for (EvidenceBlock block : blocks) {
            result.computeIfAbsent(block.headingNodeId(), ignored -> new ArrayList<>())
                    .add(block);
        }
        return result;
    }

    /** 当前节点只读自己的直接正文；子树信息来自已经校验过的直接子卡。 */
    private String sectionSource(
            HeadingNode heading,
            Map<String, List<EvidenceBlock>> directBlocks,
            Map<String, List<HeadingNode>> children,
            Map<String, RetrievalNodeSemantic> semantics
    ) {
        StringBuilder source = new StringBuilder();
        for (EvidenceBlock block : directBlocks.getOrDefault(heading.nodeId(), List.of())) {
            if (block.kind() != BlockKind.HEADING) {
                appendPart(source, block.text());
            }
        }
        for (HeadingNode child : children.getOrDefault(heading.nodeId(), List.of())) {
            RetrievalNodeSemantic childSemantic = semantics.get(child.nodeId());
            if (childSemantic == null) {
                throw new IllegalStateException("Child card must be generated bottom-up");
            }
            appendPart(source, renderTemporary(child.title(), childSemantic));
        }
        // 空章节仍携带真实标题，避免向模型发送空输入。
        if (source.isEmpty()) {
            source.append("Section title: ").append(heading.title());
        }
        return source.toString();
    }

    private void appendPart(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append("\n\n");
        }
        target.append(value);
    }

    /** 超长源文本先生成内存临时卡，并递归合并到单次调用上限以内。 */
    private String reduceSourceIfNeeded(
            String source,
            String titlePath,
            GenerationBudget budget
    ) {
        int limit = properties.getMaxSourceCharsPerChatCall();
        String current = source;
        int round = 0;
        while (current.length() > limit) {
            List<String> chunks = splitSafely(current, limit);
            List<RetrievalCardChatGateway.NodeInput> inputs = new ArrayList<>();
            for (String chunk : chunks) {
                inputs.add(new RetrievalCardChatGateway.NodeInput(
                        "temporary-" + budget.nextSequence(),
                        titlePath,
                        chunk
                ));
            }
            StringBuilder reduced = new StringBuilder();
            for (RetrievalCardChatGateway.GeneratedNode generated
                    : invokeNodeInputs(inputs, budget)) {
                appendPart(reduced, renderTemporary(titlePath, generated.semantic()));
            }
            if (reduced.length() >= current.length() || ++round > 8) {
                throw limit("Long section could not be reduced within model input limit");
            }
            current = reduced.toString();
        }
        return current;
    }

    private List<String> splitSafely(String value, int limit) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(start + limit, value.length());
            if (end < value.length()
                    && Character.isHighSurrogate(value.charAt(end - 1))
                    && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private List<RetrievalCardChatGateway.GeneratedNode> invokeNodeInputs(
            List<RetrievalCardChatGateway.NodeInput> inputs,
            GenerationBudget budget
    ) {
        List<RetrievalCardChatGateway.GeneratedNode> result = new ArrayList<>();
        List<RetrievalCardChatGateway.NodeInput> batch = new ArrayList<>();
        int batchChars = 0;
        for (RetrievalCardChatGateway.NodeInput input : inputs) {
            if (input.sourceText().length() > properties.getMaxSourceCharsPerChatCall()) {
                throw limit("A chat item exceeds the configured input limit");
            }
            if (!batch.isEmpty() && (batch.size() >= properties.getMaxItemsPerChatCall()
                    || batchChars + input.sourceText().length()
                    > properties.getMaxSourceCharsPerChatCall())) {
                result.addAll(invokeNodeBatch(batch, budget));
                batch = new ArrayList<>();
                batchChars = 0;
            }
            batch.add(input);
            batchChars += input.sourceText().length();
        }
        if (!batch.isEmpty()) {
            result.addAll(invokeNodeBatch(batch, budget));
        }
        return result;
    }

    private List<RetrievalCardChatGateway.GeneratedNode> invokeNodeBatch(
            List<RetrievalCardChatGateway.NodeInput> inputs,
            GenerationBudget budget
    ) {
        try {
            return callAndValidateNodes(inputs, null, budget);
        } catch (RetrievalGenerationException exception) {
            if (!"RETRIEVAL_MODEL_OUTPUT_INVALID".equals(exception.code())) {
                throw exception;
            }
            try {
                return callAndValidateNodes(inputs, correctionHint(exception), budget);
            } catch (RetrievalGenerationException correctedFailure) {
                if (!"RETRIEVAL_MODEL_OUTPUT_INVALID".equals(correctedFailure.code())
                        || inputs.size() <= 1) {
                    throw correctedFailure;
                }
                // 多项 JSON 即使纠正后仍可能漏项或截断；只对异常批次二分，
                // 正常批次不增加调用，单项仍失败则保留稳定失败语义。
                int middle = inputs.size() / 2;
                List<RetrievalCardChatGateway.GeneratedNode> split = new ArrayList<>();
                split.addAll(invokeNodeBatch(
                        List.copyOf(inputs.subList(0, middle)),
                        budget
                ));
                split.addAll(invokeNodeBatch(
                        List.copyOf(inputs.subList(middle, inputs.size())),
                        budget
                ));
                return split;
            }
        }
    }

    private List<RetrievalCardChatGateway.GeneratedNode> callAndValidateNodes(
            List<RetrievalCardChatGateway.NodeInput> inputs,
            String correctionHint,
            GenerationBudget budget
    ) {
        budget.consumeCall();
        List<RetrievalCardChatGateway.GeneratedNode> generated;
        try {
            generated = chatGateway.generateNodes(List.copyOf(inputs), correctionHint);
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RetrievalGenerationException(
                    "RETRIEVAL_MODEL_UNAVAILABLE",
                    "Chat model request failed",
                    true,
                    exception
            );
        }
        if (generated == null || generated.size() != inputs.size()) {
            throw invalidOutput("Chat model returned a different item count", null);
        }
        Set<String> expected = inputs.stream()
                .map(RetrievalCardChatGateway.NodeInput::requestId)
                .collect(Collectors.toSet());
        Set<String> actual = generated.stream()
                .map(RetrievalCardChatGateway.GeneratedNode::requestId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (actual.size() != generated.size() || !actual.equals(expected)) {
            throw invalidOutput("Chat model returned missing or duplicate request IDs", null);
        }
        for (RetrievalCardChatGateway.GeneratedNode item : generated) {
            semanticValidator.validate(item.semantic());
        }
        Map<String, RetrievalCardChatGateway.GeneratedNode> byRequest = generated.stream()
                .collect(Collectors.toMap(
                        RetrievalCardChatGateway.GeneratedNode::requestId,
                        Function.identity()
                ));
        // 恢复请求顺序，供应商返回数组顺序不参与稳定对象定义。
        return inputs.stream().map(input -> byRequest.get(input.requestId())).toList();
    }

    private DocumentProfileSemantic invokeProfile(
            RetrievalCardChatGateway.ProfileInput input,
            GenerationBudget budget
    ) {
        try {
            return callAndValidateProfile(input, null, budget);
        } catch (RetrievalGenerationException exception) {
            if (!"RETRIEVAL_MODEL_OUTPUT_INVALID".equals(exception.code())) {
                throw exception;
            }
            return callAndValidateProfile(input, correctionHint(exception), budget);
        }
    }

    /** 只把本地校验器的安全摘要反馈给修复调用，不包含原始模型响应或正文。 */
    private String correctionHint(RetrievalGenerationException exception) {
        return CORRECTION_HINT_PREFIX + exception.getMessage()
                + "; return complete valid JSON only and satisfy every stated limit.";
    }

    private DocumentProfileSemantic callAndValidateProfile(
            RetrievalCardChatGateway.ProfileInput input,
            String correctionHint,
            GenerationBudget budget
    ) {
        budget.consumeCall();
        try {
            DocumentProfileSemantic semantic = chatGateway.generateProfile(
                    input,
                    correctionHint
            );
            semanticValidator.validate(semantic);
            return semantic;
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RetrievalGenerationException(
                    "RETRIEVAL_MODEL_UNAVAILABLE",
                    "Chat model request failed",
                    true,
                    exception
            );
        }
    }

    private RetrievalArtifact attachEmbeddings(
            CanonicalDocument document,
            String documentTitle,
            String canonicalSha256,
            Map<String, HeadingNode> byId,
            Map<String, RetrievalNodeSemantic> semantics,
            DocumentProfileSemantic profileSemantic
    ) {
        List<HeadingNode> nonRoot = document.headings().stream()
                .filter(heading -> heading.parentNodeId() != null)
                .toList();
        List<String> texts = new ArrayList<>();
        texts.add(textBuilder.profile(documentTitle, profileSemantic));
        for (HeadingNode heading : nonRoot) {
            texts.add(textBuilder.node(
                    documentTitle,
                    titlePath(heading, byId),
                    semantics.get(heading.nodeId())
            ));
        }
        List<float[]> vectors = embedInBatches(texts);
        DocumentProfile profile = new DocumentProfile(
                document.documentVersionId() + ":rp:document",
                document.documentVersionId(),
                documentTitle,
                profileSemantic.purpose(),
                profileSemantic.topics(),
                profileSemantic.aliases(),
                profileSemantic.answerableQuestions(),
                texts.get(0),
                embeddingCodec.encode(texts.get(0), vectors.get(0),
                        properties.getEmbeddingDimension())
        );

        Map<String, Integer> siblingCounters = new HashMap<>();
        List<RetrievalNode> nodes = new ArrayList<>();
        for (int index = 0; index < nonRoot.size(); index++) {
            HeadingNode heading = nonRoot.get(index);
            RetrievalNodeSemantic semantic = semantics.get(heading.nodeId());
            Range range = range(document, heading);
            int siblingOrder = siblingCounters.merge(
                    heading.parentNodeId(), 1, Integer::sum
            ) - 1;
            nodes.add(new RetrievalNode(
                    document.documentVersionId() + ":rn:" + "%06d".formatted(index + 1),
                    heading.nodeId(),
                    heading.parentNodeId(),
                    siblingOrder,
                    heading.depth(),
                    heading.title(),
                    titlePath(heading, byId),
                    heading.sectionStartBlockOrdinal(),
                    heading.sectionEndBlockOrdinalExclusive(),
                    range.canonicalStart(),
                    range.canonicalEnd(),
                    range.sourceStart(),
                    range.sourceEnd(),
                    semantic.summary(),
                    semantic.topics(),
                    semantic.aliases(),
                    semantic.answerableQuestions(),
                    texts.get(index + 1),
                    embeddingCodec.encode(texts.get(index + 1), vectors.get(index + 1),
                            properties.getEmbeddingDimension())
            ));
        }
        return new RetrievalArtifact(
                properties.getSchemaVersion(),
                document.documentVersionId(),
                canonicalSha256,
                properties.getChatModel(),
                properties.getChatPromptVersion(),
                properties.getEmbeddingModel(),
                properties.getEmbeddingDimension(),
                properties.getEmbeddingTemplateVersion(),
                fingerprint.calculate(canonicalSha256),
                Instant.now(),
                profile,
                nodes
        );
    }

    private List<float[]> embedInBatches(List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        int batchSize = properties.getEmbeddingBatchSize();
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            List<float[]> response;
            try {
                response = embeddingGateway.embedDocuments(List.copyOf(batch));
            } catch (RetrievalGenerationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new RetrievalGenerationException(
                        "EMBEDDING_MODEL_UNAVAILABLE",
                        "Embedding model request failed",
                        true,
                        exception
                );
            }
            if (response == null || response.size() != batch.size()) {
                throw new RetrievalGenerationException(
                        "EMBEDDING_RESPONSE_INVALID",
                        "Embedding response count does not match input",
                        false
                );
            }
            vectors.addAll(response);
        }
        return vectors;
    }

    private Range range(CanonicalDocument document, HeadingNode heading) {
        int start = heading.sectionStartBlockOrdinal();
        int end = heading.sectionEndBlockOrdinalExclusive();
        if (start >= end) {
            return new Range(0, 0, null, null);
        }
        EvidenceBlock first = document.blocks().get(start);
        EvidenceBlock last = document.blocks().get(end - 1);
        return new Range(
                first.canonicalStart(),
                last.canonicalEnd(),
                first.sourcePosition(),
                last.sourcePosition()
        );
    }

    private String titlePath(HeadingNode heading, Map<String, HeadingNode> byId) {
        List<String> parts = new ArrayList<>();
        HeadingNode current = heading;
        while (current != null) {
            parts.add(current.title());
            current = current.parentNodeId() == null ? null : byId.get(current.parentNodeId());
        }
        java.util.Collections.reverse(parts);
        return String.join(" > ", parts);
    }

    private String renderTemporary(String title, RetrievalNodeSemantic semantic) {
        return "Section: " + title + '\n'
                + "Summary: " + semantic.summary() + '\n'
                + "Topics: " + String.join(" | ", semantic.topics()) + '\n'
                + "Questions: " + String.join(" | ", semantic.answerableQuestions());
    }

    private RetrievalGenerationException invalidOutput(String message, Throwable cause) {
        return new RetrievalGenerationException(
                "RETRIEVAL_MODEL_OUTPUT_INVALID",
                message,
                false,
                cause
        );
    }

    private RetrievalGenerationException limit(String message) {
        return new RetrievalGenerationException(
                "RETRIEVAL_GENERATION_LIMIT_EXCEEDED",
                message,
                false
        );
    }

    private record Range(
            long canonicalStart,
            long canonicalEnd,
            SourcePosition sourceStart,
            SourcePosition sourceEnd
    ) {
    }

    /** 每次真实 Chat 请求（包括一次纠错）都从同一文档预算扣减。 */
    private final class GenerationBudget {
        private int calls;
        private long sequence;

        private void consumeCall() {
            if (++calls > properties.getMaxChatCalls()) {
                throw limit("Document exceeds the maximum number of chat calls");
            }
        }

        private long nextSequence() {
            return ++sequence;
        }
    }
}
