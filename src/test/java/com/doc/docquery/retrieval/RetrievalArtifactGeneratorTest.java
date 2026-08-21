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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalArtifactGeneratorTest {

    @Test
    void generatesExactlyOneNodePerRealHeadingInCanonicalOrderAndBottomUp() {
        FakeChat chat = new FakeChat();
        RetrievalArtifact artifact = generator(chat, validEmbedding(), properties())
                .generate(nestedDocument("child evidence"), "Manual", "a".repeat(64));

        assertThat(artifact.profile()).isNotNull();
        assertThat(artifact.nodes()).extracting(RetrievalNode::headingNodeId)
                .containsExactly("7:h:000001", "7:h:000002");
        assertThat(artifact.nodes()).extracting(RetrievalNode::parentHeadingNodeId)
                .containsExactly("7:h:root", "7:h:000001");
        assertThat(artifact.nodes()).extracting(RetrievalNode::titlePath)
                .containsExactly("Manual > Parent", "Manual > Parent > Child");
        assertThat(chat.nodeRequests.get(0).get(0).requestId()).isEqualTo("7:h:000002");
        assertThat(chat.nodeRequests.get(1).get(0).sourceText())
                .contains("Summary: summary Manual > Parent > Child");
        assertThat(artifact.nodes()).allSatisfy(node -> {
            assertThat(node.embedding().dimension()).isEqualTo(4);
            assertThat(node.embedding().encoding()).isEqualTo("FLOAT32_LE_BASE64");
        });
    }

    @Test
    void documentWithoutRealHeadingProducesOnlyProfile() {
        DocumentRetrievalProperties properties = properties();
        RetrievalArtifact artifact = generator(new FakeChat(), validEmbedding(), properties)
                .generate(rootOnlyDocument(), "Notes", "b".repeat(64));

        assertThat(artifact.nodes()).isEmpty();
        assertThat(artifact.profile().cardId()).isEqualTo("8:rp:document");
    }

    @Test
    void invalidModelOutputGetsOneCorrectionAttempt() {
        FakeChat chat = new FakeChat();
        chat.invalidFirstNodeOutput = true;

        RetrievalArtifact artifact = generator(chat, validEmbedding(), properties())
                .generate(nestedDocument("short"), "Manual", "c".repeat(64));

        assertThat(artifact.nodes()).hasSize(2);
        assertThat(chat.corrections).containsExactly(false, true, false);
        assertThat(chat.correctionHints.get(1))
                .contains("summary is missing or too long");
    }

    @Test
    void invalidMultiItemBatchAfterCorrectionFallsBackToSplitBatches() {
        FakeChat chat = new FakeChat();
        chat.dropLastItemFromMultiItemBatch = true;

        RetrievalArtifact artifact = generator(chat, validEmbedding(), properties())
                .generate(siblingDocument(), "Manual", "9".repeat(64));

        assertThat(artifact.nodes()).hasSize(2);
        assertThat(chat.nodeRequests).extracting(List::size)
                .containsExactly(2, 2, 1, 1);
        assertThat(chat.corrections).containsExactly(false, true, false, false);
    }

    @Test
    void longSectionUsesTemporaryCardsButPersistsOneRealNode() {
        DocumentRetrievalProperties properties = properties();
        properties.setMaxSourceCharsPerChatCall(1_000);
        String longText = "x".repeat(2_500);
        FakeChat chat = new FakeChat();

        RetrievalArtifact artifact = generator(chat, validEmbedding(), properties)
                .generate(nestedDocument(longText), "Manual", "d".repeat(64));

        assertThat(chat.nodeRequests.stream().flatMap(List::stream)
                .filter(input -> input.requestId().startsWith("temporary-")))
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(artifact.nodes()).hasSize(2);
        assertThat(artifact.nodes()).extracting(RetrievalNode::headingNodeId)
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsWrongEmbeddingDimensionWithoutArtifact() {
        NavigationEmbeddingGateway invalidEmbedding = texts -> texts.stream()
                .map(ignored -> new float[]{1.0f, 2.0f})
                .toList();

        assertThatThrownBy(() -> generator(new FakeChat(), invalidEmbedding, properties())
                .generate(rootOnlyDocument(), "Notes", "e".repeat(64)))
                .isInstanceOfSatisfying(RetrievalGenerationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("EMBEDDING_RESPONSE_INVALID"));
    }

    @Test
    void semanticFieldsStripWhitespaceAndRemoveBlankOrExactDuplicateItems() {
        RetrievalNodeSemantic node = new RetrievalNodeSemantic(
                "  concise summary  ",
                List.of(" topic ", "topic", " "),
                List.of(" alias ", "alias"),
                List.of(" question? ", "question?")
        );

        assertThat(node.summary()).isEqualTo("concise summary");
        assertThat(node.topics()).containsExactly("topic");
        assertThat(node.aliases()).containsExactly("alias");
        assertThat(node.answerableQuestions()).containsExactly("question?");
    }

    @Test
    void semanticFieldsAreDeterministicallyBoundedBeforeValidation() {
        RetrievalNodeSemantic node = new RetrievalNodeSemantic(
                "x".repeat(601),
                java.util.stream.IntStream.range(0, 14)
                        .mapToObj(index -> ("topic-" + index).repeat(20))
                        .toList(),
                List.of("alias"),
                List.of("question".repeat(30))
        );

        assertThat(node.summary()).hasSize(600);
        assertThat(node.topics()).hasSize(12).allSatisfy(
                topic -> assertThat(topic.length()).isLessThanOrEqualTo(80)
        );
        assertThat(node.answerableQuestions()).singleElement().satisfies(
                question -> assertThat(question.length()).isLessThanOrEqualTo(200)
        );
        new RetrievalSemanticValidator().validate(node);
    }

    @Test
    void headingCardMayHaveNoSafeQuestionButDocumentProfileStillRequiresOne() {
        RetrievalSemanticValidator validator = new RetrievalSemanticValidator();

        validator.validate(new RetrievalNodeSemantic(
                "transition section",
                List.of("transition"),
                List.of(),
                List.of()
        ));
        assertThatThrownBy(() -> validator.validate(new DocumentProfileSemantic(
                "document purpose",
                List.of("topic"),
                List.of(),
                List.of()
        ))).isInstanceOfSatisfying(RetrievalGenerationException.class,
                exception -> assertThat(exception.getMessage())
                        .isEqualTo("answerableQuestions count is invalid"));
    }

    private RetrievalArtifactGenerator generator(
            RetrievalCardChatGateway chat,
            NavigationEmbeddingGateway embedding,
            DocumentRetrievalProperties properties
    ) {
        EmbeddingCodec codec = new EmbeddingCodec();
        return new RetrievalArtifactGenerator(
                chat,
                embedding,
                new RetrievalSemanticValidator(),
                new NavigationTextBuilder(),
                codec,
                new RetrievalGenerationFingerprint(properties, codec),
                properties
        );
    }

    private DocumentRetrievalProperties properties() {
        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setEmbeddingDimension(4);
        return properties;
    }

    private NavigationEmbeddingGateway validEmbedding() {
        return texts -> texts.stream()
                .map(text -> new float[]{1.0f, 2.0f, 3.0f, text.length() + 1.0f})
                .toList();
    }

    private CanonicalDocument nestedDocument(String childText) {
        List<String> texts = List.of("Parent", "parent evidence", "Child", childText);
        List<EvidenceBlock> blocks = blocks(
                texts,
                List.of(BlockKind.HEADING, BlockKind.PARAGRAPH,
                        BlockKind.HEADING, BlockKind.PARAGRAPH),
                List.of("7:h:000001", "7:h:000001", "7:h:000002", "7:h:000002")
        );
        List<HeadingNode> headings = List.of(
                new HeadingNode("7:h:root", null, 0, 0, "Manual", null,
                        "DOCUMENT_ROOT", 0, 4),
                new HeadingNode("7:h:000001", "7:h:root", 1, 1, "Parent",
                        "7:b:000000", "MARKDOWN", 0, 4),
                new HeadingNode("7:h:000002", "7:h:000001", 2, 2, "Child",
                        "7:b:000002", "MARKDOWN", 2, 4)
        );
        return document(7, blocks, headings);
    }

    private CanonicalDocument siblingDocument() {
        List<EvidenceBlock> blocks = blocks(
                List.of("First", "first evidence", "Second", "second evidence"),
                List.of(BlockKind.HEADING, BlockKind.PARAGRAPH,
                        BlockKind.HEADING, BlockKind.PARAGRAPH),
                List.of("7:h:000001", "7:h:000001", "7:h:000002", "7:h:000002")
        );
        return document(7, blocks, List.of(
                new HeadingNode("7:h:root", null, 0, 0, "Manual", null,
                        "DOCUMENT_ROOT", 0, 4),
                new HeadingNode("7:h:000001", "7:h:root", 1, 1, "First",
                        "7:b:000000", "MARKDOWN", 0, 2),
                new HeadingNode("7:h:000002", "7:h:root", 1, 1, "Second",
                        "7:b:000002", "MARKDOWN", 2, 4)
        ));
    }

    private CanonicalDocument rootOnlyDocument() {
        List<EvidenceBlock> blocks = blocks(
                List.of("plain text"),
                List.of(BlockKind.RAW_TEXT),
                List.of("8:h:root")
        );
        return document(8, blocks, List.of(
                new HeadingNode("8:h:root", null, 0, 0, "Notes", null,
                        "DOCUMENT_ROOT", 0, 1)
        ));
    }

    private List<EvidenceBlock> blocks(
            List<String> texts,
            List<BlockKind> kinds,
            List<String> headings
    ) {
        List<EvidenceBlock> blocks = new ArrayList<>();
        long offset = 0;
        for (int index = 0; index < texts.size(); index++) {
            if (index > 0) {
                offset += 2;
            }
            String text = texts.get(index);
            blocks.add(new EvidenceBlock(
                    (headings.get(index).startsWith("7") ? "7" : "8")
                            + ":b:" + "%06d".formatted(index),
                    index,
                    kinds.get(index),
                    text,
                    offset,
                    offset + text.length(),
                    headings.get(index),
                    null,
                    SourcePosition.text(index + 1, index + 1)
            ));
            offset += text.length();
        }
        return blocks;
    }

    private CanonicalDocument document(
            long versionId,
            List<EvidenceBlock> blocks,
            List<HeadingNode> headings
    ) {
        String canonicalText = String.join("\n\n", blocks.stream()
                .map(EvidenceBlock::text).toList());
        return new CanonicalDocument(
                1,
                versionId,
                "4",
                "f".repeat(64),
                "test-parser",
                "1",
                Instant.parse("2026-08-06T00:00:00Z"),
                blocks,
                headings,
                List.of(),
                null,
                canonicalText.length(),
                sha256(canonicalText)
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class FakeChat implements RetrievalCardChatGateway {
        private final List<List<NodeInput>> nodeRequests = new ArrayList<>();
        private final List<Boolean> corrections = new ArrayList<>();
        private final List<String> correctionHints = new ArrayList<>();
        private boolean invalidFirstNodeOutput;
        private boolean dropLastItemFromMultiItemBatch;

        @Override
        public List<GeneratedNode> generateNodes(
                List<NodeInput> inputs,
                String correctionHint
        ) {
            nodeRequests.add(inputs);
            corrections.add(correctionHint != null);
            correctionHints.add(correctionHint);
            if (dropLastItemFromMultiItemBatch && inputs.size() > 1) {
                return inputs.subList(0, inputs.size() - 1).stream()
                        .map(input -> new GeneratedNode(
                                input.requestId(),
                                new RetrievalNodeSemantic(
                                        "summary " + input.titlePath(),
                                        List.of("topic"),
                                        List.of(),
                                        List.of("question")
                                )
                        )).toList();
            }
            if (invalidFirstNodeOutput && correctionHint == null) {
                invalidFirstNodeOutput = false;
                return inputs.stream().map(input -> new GeneratedNode(
                        input.requestId(),
                        new RetrievalNodeSemantic("", List.of("topic"), List.of(),
                                List.of("question"))
                )).toList();
            }
            return inputs.stream().map(input -> new GeneratedNode(
                    input.requestId(),
                    new RetrievalNodeSemantic(
                            "summary " + input.titlePath(),
                            List.of("topic"),
                            List.of(),
                            List.of("question")
                    )
            )).toList();
        }

        @Override
        public DocumentProfileSemantic generateProfile(
                ProfileInput input,
                String correctionHint
        ) {
            return new DocumentProfileSemantic(
                    "purpose",
                    List.of("topic"),
                    List.of(),
                    List.of("question")
            );
        }
    }
}
