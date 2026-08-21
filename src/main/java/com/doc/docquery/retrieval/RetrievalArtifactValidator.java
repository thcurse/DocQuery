package com.doc.docquery.retrieval;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 写对象前统一验证卡片、向量元数据和两个内容级摘要。 */
@Component
public class RetrievalArtifactValidator {

    private final ObjectMapper objectMapper;
    private final EmbeddingCodec codec;
    private final NavigationTextBuilder textBuilder;
    private final RetrievalSemanticValidator semanticValidator;
    private final DocumentRetrievalProperties properties;

    public RetrievalArtifactValidator(
            ObjectMapper objectMapper,
            EmbeddingCodec codec,
            NavigationTextBuilder textBuilder,
            RetrievalSemanticValidator semanticValidator,
            DocumentRetrievalProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.codec = codec;
        this.textBuilder = textBuilder;
        this.semanticValidator = semanticValidator;
        this.properties = properties;
    }

    public ValidationResult validate(RetrievalArtifact artifact) {
        try {
            if (artifact.profile() == null
                    || artifact.schemaVersion() != properties.getSchemaVersion()
                    || artifact.embeddingDimension() != properties.getEmbeddingDimension()
                    || artifact.generationFingerprint() == null
                    || artifact.generationFingerprint().length() != 64
                    || artifact.nodes().size() + 1 > properties.getMaxCards()) {
                fail("Retrieval artifact header or counts are invalid");
            }
            MessageDigest semanticDigest = sha256Digest();
            MessageDigest vectorDigest = sha256Digest();
            Set<String> cardIds = new HashSet<>();
            Set<String> headingIds = new HashSet<>();

            if (artifact.profile().documentVersionId() != artifact.documentVersionId()
                    || !artifact.profile().cardId().equals(
                    artifact.documentVersionId() + ":rp:document")) {
                fail("Document profile identity does not match artifact header");
            }
            DocumentProfileSemantic profileSemantic = new DocumentProfileSemantic(
                    artifact.profile().purpose(),
                    artifact.profile().topics(),
                    artifact.profile().aliases(),
                    artifact.profile().answerableQuestions()
            );
            semanticValidator.validate(profileSemantic);
            if (!artifact.profile().embeddingText().equals(textBuilder.profile(
                    artifact.profile().documentTitle(),
                    profileSemantic
            ))) {
                fail("Document profile embedding text does not match stable template");
            }

            validateEmbedding(
                    artifact.profile().cardId(),
                    artifact.profile().embeddingText(),
                    artifact.profile().embedding(),
                    cardIds,
                    vectorDigest
            );
            updateSemantic(semanticDigest, profileSemantic(artifact.profile()));
            String rootHeadingId = null;
            for (int index = 0; index < artifact.nodes().size(); index++) {
                RetrievalNode node = artifact.nodes().get(index);
                validateEmbedding(
                        node.cardId(),
                        node.embeddingText(),
                        node.embedding(),
                        cardIds,
                        vectorDigest
                );
                if (!node.cardId().equals(artifact.documentVersionId()
                        + ":rn:" + "%06d".formatted(index + 1))
                        || node.headingNodeId() == null
                        || !headingIds.add(node.headingNodeId())
                        || node.parentHeadingNodeId() == null
                        || node.depth() < 1
                        || node.siblingOrder() < 0
                        || node.title() == null || node.title().isBlank()
                        || node.titlePath() == null || node.titlePath().isBlank()
                        || node.sectionStartBlockOrdinal()
                        >= node.sectionEndBlockOrdinalExclusive()
                        || node.canonicalStart() > node.canonicalEnd()) {
                    fail("Retrieval node structural fields are invalid");
                }
                if (node.depth() == 1) {
                    if (rootHeadingId == null) {
                        rootHeadingId = node.parentHeadingNodeId();
                    } else if (!rootHeadingId.equals(node.parentHeadingNodeId())) {
                        fail("Top-level retrieval nodes do not share one canonical root");
                    }
                } else if (!headingIds.contains(node.parentHeadingNodeId())) {
                    fail("Retrieval node parent must precede its child");
                }
                RetrievalNodeSemantic nodeSemantic = new RetrievalNodeSemantic(
                        node.summary(), node.topics(), node.aliases(),
                        node.answerableQuestions()
                );
                semanticValidator.validate(nodeSemantic);
                if (!node.embeddingText().equals(textBuilder.node(
                        artifact.profile().documentTitle(),
                        node.titlePath(),
                        nodeSemantic
                ))) {
                    fail("Retrieval node embedding text does not match stable template");
                }
                updateSemantic(semanticDigest, nodeSemantic(node));
            }
            return new ValidationResult(
                    HexFormat.of().formatHex(semanticDigest.digest()),
                    HexFormat.of().formatHex(vectorDigest.digest())
            );
        } catch (RetrievalGenerationException exception) {
            if ("RETRIEVAL_ARTIFACT_VALIDATION_FAILED".equals(exception.code())) {
                throw exception;
            }
            throw new RetrievalGenerationException(
                    "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                    "Retrieval artifact contains an invalid embedding",
                    false,
                    exception
            );
        }
    }

    private void validateEmbedding(
            String cardId,
            String embeddingText,
            EmbeddingPayload embedding,
            Set<String> cardIds,
            MessageDigest vectorDigest
    ) {
        if (cardId == null || !cardIds.add(cardId) || embeddingText == null) {
            fail("Retrieval card identity is invalid");
        }
        String inputSha = codec.sha256(embeddingText.getBytes(StandardCharsets.UTF_8));
        if (!inputSha.equals(embedding.inputSha256())) {
            fail("Embedding input digest does not match navigation text");
        }
        vectorDigest.update(codec.decode(embedding, properties.getEmbeddingDimension()));
    }

    private void updateSemantic(MessageDigest digest, Map<String, Object> semantic) {
        digest.update(objectMapper.writeValueAsBytes(semantic));
        digest.update((byte) '\n');
    }

    private Map<String, Object> profileSemantic(DocumentProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cardId", profile.cardId());
        value.put("purpose", profile.purpose());
        value.put("topics", profile.topics());
        value.put("aliases", profile.aliases());
        value.put("answerableQuestions", profile.answerableQuestions());
        return value;
    }

    private Map<String, Object> nodeSemantic(RetrievalNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cardId", node.cardId());
        value.put("headingNodeId", node.headingNodeId());
        value.put("summary", node.summary());
        value.put("topics", node.topics());
        value.put("aliases", node.aliases());
        value.put("answerableQuestions", node.answerableQuestions());
        return value;
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void fail(String message) {
        throw new RetrievalGenerationException(
                "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                message,
                false
        );
    }

    public record ValidationResult(String semanticSha256, String vectorSha256) {
    }
}
