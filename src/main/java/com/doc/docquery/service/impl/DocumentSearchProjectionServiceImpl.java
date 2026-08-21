package com.doc.docquery.service.impl;

import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import com.doc.docquery.entity.DocumentSearchProjectionEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentSearchProjectionMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.parser.SourcePosition;
import com.doc.docquery.retrieval.CanonicalArtifactReader;
import com.doc.docquery.retrieval.DocumentProfile;
import com.doc.docquery.retrieval.EmbeddingCodec;
import com.doc.docquery.retrieval.EmbeddingPayload;
import com.doc.docquery.retrieval.RetrievalArtifact;
import com.doc.docquery.retrieval.RetrievalJsonlReader;
import com.doc.docquery.retrieval.RetrievalNode;
import com.doc.docquery.search.SearchIndexDescriptor;
import com.doc.docquery.search.SearchProjectionDocument;
import com.doc.docquery.search.SearchProjectionException;
import com.doc.docquery.search.SearchProjectionFingerprint;
import com.doc.docquery.search.SearchProjectionScope;
import com.doc.docquery.search.SearchProjectionStore;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentCanonicalService;
import com.doc.docquery.service.DocumentRetrievalService;
import com.doc.docquery.service.DocumentSearchProjectionService;
import com.doc.docquery.service.RetrievalArtifactStore;
import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N2.5 双索引投影编排器。
 *
 * <p>该服务只消费经过读回校验的不可变派生对象。ES 是可重建副本，因此先
 * 写入并完整计数验收，再保存 MySQL 验收单；文档版本激活由后续独立事务完成。</p>
 */
@Service
@ConditionalOnProperty(prefix = "docquery.search", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
        prefix = "docquery.retrieval",
        name = "provider-enabled",
        havingValue = "true"
)
public class DocumentSearchProjectionServiceImpl implements DocumentSearchProjectionService {

    private static final String PROCESSING = DocumentVersionStatus.PROCESSING.getCode();

    private final DocumentCanonicalService canonicalService;
    private final DocumentRetrievalService retrievalService;
    private final CanonicalArtifactStore canonicalStore;
    private final RetrievalArtifactStore retrievalStore;
    private final CanonicalArtifactReader canonicalReader;
    private final RetrievalJsonlReader retrievalReader;
    private final EmbeddingCodec embeddingCodec;
    private final SearchProjectionStore searchStore;
    private final SearchProjectionFingerprint fingerprint;
    private final SearchProjectionProperties properties;
    private final DocumentVersionMapper versionMapper;
    private final DocumentMapper documentMapper;
    private final DocumentSearchProjectionMapper projectionMapper;

    public DocumentSearchProjectionServiceImpl(
            DocumentCanonicalService canonicalService,
            DocumentRetrievalService retrievalService,
            CanonicalArtifactStore canonicalStore,
            RetrievalArtifactStore retrievalStore,
            CanonicalArtifactReader canonicalReader,
            RetrievalJsonlReader retrievalReader,
            EmbeddingCodec embeddingCodec,
            SearchProjectionStore searchStore,
            SearchProjectionFingerprint fingerprint,
            SearchProjectionProperties properties,
            DocumentVersionMapper versionMapper,
            DocumentMapper documentMapper,
            DocumentSearchProjectionMapper projectionMapper
    ) {
        this.canonicalService = canonicalService;
        this.retrievalService = retrievalService;
        this.canonicalStore = canonicalStore;
        this.retrievalStore = retrievalStore;
        this.canonicalReader = canonicalReader;
        this.retrievalReader = retrievalReader;
        this.embeddingCodec = embeddingCodec;
        this.searchStore = searchStore;
        this.fingerprint = fingerprint;
        this.properties = properties;
        this.versionMapper = versionMapper;
        this.documentMapper = documentMapper;
        this.projectionMapper = projectionMapper;
    }

    @Override
    public DocumentSearchProjectionEntity ensureProjection(long documentVersionId) {
        DocumentVersionEntity version = requiredVersion(documentVersionId);
        DocumentEntity document = requiredDocument(version);
        DocumentCanonicalArtifactEntity canonicalManifest =
                canonicalService.ensureCanonical(documentVersionId);
        DocumentRetrievalArtifactEntity retrievalManifest =
                retrievalService.ensureRetrieval(documentVersionId);
        validateLineage(version, canonicalManifest, retrievalManifest);

        CanonicalDocument canonical = readCanonical(canonicalManifest);
        RetrievalArtifact retrieval = readRetrieval(retrievalManifest);
        validateArtifacts(version, canonicalManifest, retrievalManifest, canonical, retrieval);

        String projectionFingerprint = fingerprint.calculate(
                version.getId(),
                canonicalManifest,
                retrievalManifest,
                properties
        );
        SearchProjectionScope scope = new SearchProjectionScope(
                version.getTenantId(),
                document.getKnowledgeBaseId(),
                document.getId(),
                version.getId(),
                projectionFingerprint
        );
        SearchIndexDescriptor indices = searchStore.ensureIndices();
        int evidenceExpected = canonical.blocks().size();
        int navigationExpected = retrieval.nodes().size() + 1;

        DocumentSearchProjectionEntity existing =
                projectionMapper.findByDocumentVersionId(version.getId());
        if (isReusable(
                existing,
                canonicalManifest,
                retrievalManifest,
                indices,
                projectionFingerprint,
                evidenceExpected,
                navigationExpected,
                scope
        )) {
            return existing;
        }

        // 清理范围不包含指纹，确保上一次半成品或配置回滚不会留下幽灵记录。
        searchStore.deleteScope(scope);
        searchStore.indexEvidence(evidenceDocuments(
                document,
                version,
                canonicalManifest,
                canonical,
                projectionFingerprint
        ));
        searchStore.indexNavigation(navigationDocuments(
                document,
                version,
                canonicalManifest,
                retrievalManifest,
                retrieval,
                projectionFingerprint
        ));
        searchStore.refresh();

        int evidenceActual = exactCount(
                searchStore.countEvidence(scope),
                searchStore.countEvidenceVersion(scope),
                evidenceExpected
        );
        int navigationActual = exactCount(
                searchStore.countNavigation(scope),
                searchStore.countNavigationVersion(scope),
                navigationExpected
        );
        DocumentSearchProjectionEntity completed = entity(
                existing,
                version,
                document,
                canonicalManifest,
                retrievalManifest,
                indices,
                projectionFingerprint,
                evidenceExpected,
                evidenceActual,
                navigationExpected,
                navigationActual
        );
        try {
            // MySQL 对 INSERT 返回 1，对 ON DUPLICATE KEY UPDATE 通常返回 2。
            if (projectionMapper.upsert(completed) < 1) {
                throw unavailable("Search projection receipt was not persisted", null);
            }
            return projectionMapper.findByDocumentVersionId(version.getId());
        } catch (SearchProjectionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("Search projection receipt could not be persisted", exception);
        }
    }

    private DocumentVersionEntity requiredVersion(long versionId) {
        DocumentVersionEntity version = versionMapper.findById(versionId);
        if (version == null || !PROCESSING.equals(version.getStatus())) {
            throw invalid("Document version is not PROCESSING");
        }
        return version;
    }

    private DocumentEntity requiredDocument(DocumentVersionEntity version) {
        DocumentEntity document = documentMapper.findByTenantAndId(
                version.getTenantId(),
                version.getDocumentId()
        );
        if (document == null) {
            throw invalid("Document facts are missing");
        }
        return document;
    }

    private void validateLineage(
            DocumentVersionEntity version,
            DocumentCanonicalArtifactEntity canonical,
            DocumentRetrievalArtifactEntity retrieval
    ) {
        if (canonical == null
                || retrieval == null
                || !version.getId().equals(canonical.getDocumentVersionId())
                || !version.getId().equals(retrieval.getDocumentVersionId())
                || !version.getTenantId().equals(canonical.getTenantId())
                || !version.getTenantId().equals(retrieval.getTenantId())
                || !canonical.getId().equals(retrieval.getCanonicalArtifactId())
                || !canonicalStore.bucketName().equals(canonical.getCanonicalBucket())
                || !retrievalStore.bucketName().equals(retrieval.getRetrievalBucket())) {
            throw invalid("Search projection artifact lineage does not match");
        }
    }

    private CanonicalDocument readCanonical(DocumentCanonicalArtifactEntity manifest) {
        try (MeasuredInputStream input = new MeasuredInputStream(
                canonicalStore.open(manifest.getCanonicalObjectKey()))) {
            CanonicalDocument document = canonicalReader.read(input);
            requireObjectIdentity(
                    input,
                    manifest.getCanonicalSizeBytes(),
                    manifest.getCanonicalSha256(),
                    "Canonical artifact bytes do not match its receipt"
            );
            return document;
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw unavailable("Canonical artifact is unavailable", exception);
        }
    }

    private RetrievalArtifact readRetrieval(DocumentRetrievalArtifactEntity manifest) {
        try (MeasuredInputStream input = new MeasuredInputStream(
                retrievalStore.open(manifest.getRetrievalObjectKey()))) {
            RetrievalArtifact artifact = retrievalReader.read(input).artifact();
            requireObjectIdentity(
                    input,
                    manifest.getRetrievalSizeBytes(),
                    manifest.getRetrievalSha256(),
                    "Retrieval artifact bytes do not match its receipt"
            );
            return artifact;
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw unavailable("Retrieval artifact is unavailable", exception);
        }
    }

    private void requireObjectIdentity(
            MeasuredInputStream input,
            long expectedSize,
            String expectedSha256,
            String message
    ) {
        if (input.count() != expectedSize || !expectedSha256.equals(input.sha256())) {
            throw invalid(message);
        }
    }

    private void validateArtifacts(
            DocumentVersionEntity version,
            DocumentCanonicalArtifactEntity canonicalManifest,
            DocumentRetrievalArtifactEntity retrievalManifest,
            CanonicalDocument canonical,
            RetrievalArtifact retrieval
    ) {
        if (canonical.documentVersionId() != version.getId()
                || !canonical.sourceSha256().equals(version.getSourceSha256())
                || canonical.blocks().size() != canonicalManifest.getBlockCount()
                || !canonical.canonicalTextSha256().equals(
                canonicalManifest.getCanonicalTextSha256())
                || retrieval.documentVersionId() != version.getId()
                || !retrieval.canonicalSha256().equals(canonicalManifest.getCanonicalSha256())
                || retrieval.embeddingDimension() != properties.getEmbeddingDimension()
                || retrieval.embeddingDimension() != retrievalManifest.getEmbeddingDimension()
                || retrieval.nodes().size() != retrievalManifest.getNodeCount()
                || retrieval.nodes().size() + 1 != retrievalManifest.getVectorCount()
                || !retrieval.generationFingerprint().equals(
                retrievalManifest.getGenerationFingerprint())) {
            throw invalid("Search projection artifact content does not match receipts");
        }
    }

    private boolean isReusable(
            DocumentSearchProjectionEntity existing,
            DocumentCanonicalArtifactEntity canonical,
            DocumentRetrievalArtifactEntity retrieval,
            SearchIndexDescriptor indices,
            String projectionFingerprint,
            int evidenceExpected,
            int navigationExpected,
            SearchProjectionScope scope
    ) {
        if (existing == null
                || !canonical.getId().equals(existing.getCanonicalArtifactId())
                || !retrieval.getId().equals(existing.getRetrievalArtifactId())
                || !indices.clusterUuid().equals(existing.getClusterUuid())
                || !indices.evidence().name().equals(existing.getEvidenceIndexName())
                || !indices.evidence().uuid().equals(existing.getEvidenceIndexUuid())
                || !indices.navigation().name().equals(existing.getNavigationIndexName())
                || !indices.navigation().uuid().equals(existing.getNavigationIndexUuid())
                || !projectionFingerprint.equals(existing.getProjectionFingerprint())
                || existing.getEvidenceExpectedCount() != evidenceExpected
                || existing.getNavigationExpectedCount() != navigationExpected) {
            return false;
        }
        return searchStore.countEvidence(scope) == evidenceExpected
                && searchStore.countEvidenceVersion(scope) == evidenceExpected
                && searchStore.countNavigation(scope) == navigationExpected
                && searchStore.countNavigationVersion(scope) == navigationExpected;
    }

    private List<SearchProjectionDocument> evidenceDocuments(
            DocumentEntity document,
            DocumentVersionEntity version,
            DocumentCanonicalArtifactEntity manifest,
            CanonicalDocument canonical,
            String projectionFingerprint
    ) {
        Map<String, HeadingNode> headings = new HashMap<>();
        for (HeadingNode heading : canonical.headings()) {
            headings.put(heading.nodeId(), heading);
        }
        List<SearchProjectionDocument> documents = new ArrayList<>(canonical.blocks().size());
        for (EvidenceBlock block : canonical.blocks()) {
            Map<String, Object> source = common(document, version, projectionFingerprint);
            put(source, "block_id", block.blockId());
            put(source, "ordinal", block.ordinal());
            put(source, "kind", block.kind().name());
            put(source, "document_title", document.getName());
            put(source, "heading_node_id", block.headingNodeId());
            put(source, "heading_path", titlePath(block.headingNodeId(), headings));
            put(source, "text", block.text());
            put(source, "canonical_start", block.canonicalStart());
            put(source, "canonical_end", block.canonicalEnd());
            put(source, "continuation_group_id", block.continuationGroupId());
            putSourcePosition(source, block.sourcePosition());
            put(source, "canonical_artifact_id", manifest.getId());
            put(source, "canonical_artifact_sha256", manifest.getCanonicalSha256());
            put(source, "mapping_version", properties.getEvidenceMappingVersion());
            documents.add(new SearchProjectionDocument(block.blockId(), source));
        }
        return documents;
    }

    private List<SearchProjectionDocument> navigationDocuments(
            DocumentEntity document,
            DocumentVersionEntity version,
            DocumentCanonicalArtifactEntity canonical,
            DocumentRetrievalArtifactEntity retrievalManifest,
            RetrievalArtifact retrieval,
            String projectionFingerprint
    ) {
        List<SearchProjectionDocument> documents = new ArrayList<>(
                retrieval.nodes().size() + 1
        );
        DocumentProfile profile = retrieval.profile();
        Map<String, Object> profileSource = common(document, version, projectionFingerprint);
        put(profileSource, "card_id", profile.cardId());
        put(profileSource, "card_type", "DOCUMENT_PROFILE");
        put(profileSource, "document_title", profile.documentTitle());
        put(profileSource, "purpose", profile.purpose());
        put(profileSource, "topics", profile.topics());
        put(profileSource, "aliases", profile.aliases());
        put(profileSource, "answerable_questions", profile.answerableQuestions());
        putEmbedding(profileSource, profile.embedding(), retrieval, retrievalManifest);
        putLineage(profileSource, canonical, retrievalManifest);
        documents.add(new SearchProjectionDocument(profile.cardId(), profileSource));

        for (RetrievalNode node : retrieval.nodes()) {
            Map<String, Object> source = common(document, version, projectionFingerprint);
            put(source, "card_id", node.cardId());
            put(source, "card_type", "HEADING_NODE");
            put(source, "document_title", profile.documentTitle());
            put(source, "heading_node_id", node.headingNodeId());
            put(source, "parent_heading_node_id", node.parentHeadingNodeId());
            put(source, "sibling_order", node.siblingOrder());
            put(source, "depth", node.depth());
            put(source, "title", node.title());
            put(source, "title_path", node.titlePath());
            put(source, "section_start_block_ordinal", node.sectionStartBlockOrdinal());
            put(source, "section_end_block_ordinal_exclusive",
                    node.sectionEndBlockOrdinalExclusive());
            put(source, "canonical_start", node.canonicalStart());
            put(source, "canonical_end", node.canonicalEnd());
            put(source, "source_start", sourcePosition(node.sourceStart()));
            put(source, "source_end", sourcePosition(node.sourceEnd()));
            put(source, "summary", node.summary());
            put(source, "topics", node.topics());
            put(source, "aliases", node.aliases());
            put(source, "answerable_questions", node.answerableQuestions());
            putEmbedding(source, node.embedding(), retrieval, retrievalManifest);
            putLineage(source, canonical, retrievalManifest);
            documents.add(new SearchProjectionDocument(node.cardId(), source));
        }
        return documents;
    }

    private Map<String, Object> common(
            DocumentEntity document,
            DocumentVersionEntity version,
            String projectionFingerprint
    ) {
        Map<String, Object> source = new LinkedHashMap<>();
        put(source, "tenant_id", version.getTenantId());
        put(source, "knowledge_base_id", document.getKnowledgeBaseId());
        put(source, "document_id", document.getId());
        put(source, "document_version_id", version.getId());
        put(source, "projection_fingerprint", projectionFingerprint);
        return source;
    }

    private void putLineage(
            Map<String, Object> source,
            DocumentCanonicalArtifactEntity canonical,
            DocumentRetrievalArtifactEntity retrieval
    ) {
        put(source, "canonical_artifact_id", canonical.getId());
        put(source, "canonical_artifact_sha256", canonical.getCanonicalSha256());
        put(source, "retrieval_artifact_id", retrieval.getId());
        put(source, "retrieval_artifact_sha256", retrieval.getRetrievalSha256());
        put(source, "mapping_version", properties.getNavigationMappingVersion());
    }

    private void putEmbedding(
            Map<String, Object> source,
            EmbeddingPayload payload,
            RetrievalArtifact retrieval,
            DocumentRetrievalArtifactEntity manifest
    ) {
        byte[] raw;
        try {
            raw = embeddingCodec.decode(payload, properties.getEmbeddingDimension());
        } catch (RetrievalGenerationException exception) {
            throw new SearchProjectionException(
                    "SEARCH_VECTOR_DIMENSION_MISMATCH",
                    "Navigation embedding is incompatible with the index",
                    false,
                    exception
            );
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<Float> vector = new ArrayList<>(properties.getEmbeddingDimension());
        while (buffer.hasRemaining()) {
            vector.add(buffer.getFloat());
        }
        put(source, "embedding_model", retrieval.embeddingModel());
        put(source, "embedding_dimension", retrieval.embeddingDimension());
        put(source, "embedding_template_version", retrieval.embeddingTemplateVersion());
        put(source, "embedding_provider", manifest.getEmbeddingProvider());
        put(source, "embedding_input_sha256", payload.inputSha256());
        put(source, "embedding_value_sha256", payload.valueSha256());
        put(source, "embedding", vector);
        if (!retrieval.embeddingModel().equals(manifest.getEmbeddingModel())
                || !retrieval.embeddingTemplateVersion().equals(
                manifest.getEmbeddingTemplateVersion())) {
            throw invalid("Navigation embedding metadata does not match its receipt");
        }
    }

    private String titlePath(String headingId, Map<String, HeadingNode> headings) {
        List<String> parts = new ArrayList<>();
        HeadingNode current = headings.get(headingId);
        while (current != null) {
            parts.add(current.title());
            current = current.parentNodeId() == null
                    ? null
                    : headings.get(current.parentNodeId());
        }
        if (parts.isEmpty()) {
            throw invalid("Evidence block heading is missing");
        }
        Collections.reverse(parts);
        return String.join(" > ", parts);
    }

    private void putSourcePosition(Map<String, Object> target, SourcePosition position) {
        if (position == null) {
            return;
        }
        put(target, "source_type", position.sourceType());
        put(target, "page_number", position.pageNumber());
        put(target, "page_block_ordinal", position.pageBlockOrdinal());
        put(target, "page_character_start", position.pageCharacterStart());
        put(target, "page_character_end", position.pageCharacterEnd());
        put(target, "body_element_index", position.bodyElementIndex());
        put(target, "table_row", position.tableRow());
        put(target, "table_column", position.tableColumn());
        put(target, "cell_paragraph_index", position.cellParagraphIndex());
        put(target, "start_line", position.startLine());
        put(target, "start_column", position.startColumn());
        put(target, "end_line", position.endLine());
        put(target, "end_column", position.endColumn());
    }

    private Map<String, Object> sourcePosition(SourcePosition position) {
        if (position == null) {
            return null;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        putSourcePosition(source, position);
        return source;
    }

    private void put(Map<String, Object> target, String field, Object value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private int exactCount(long fingerprintCount, long versionCount, int expected) {
        if (fingerprintCount != expected || versionCount != expected) {
            throw new SearchProjectionException(
                    "SEARCH_PROJECTION_VALIDATION_FAILED",
                    "Elasticsearch projection count does not match its artifact",
                    false
            );
        }
        return Math.toIntExact(fingerprintCount);
    }

    private DocumentSearchProjectionEntity entity(
            DocumentSearchProjectionEntity existing,
            DocumentVersionEntity version,
            DocumentEntity document,
            DocumentCanonicalArtifactEntity canonical,
            DocumentRetrievalArtifactEntity retrieval,
            SearchIndexDescriptor indices,
            String projectionFingerprint,
            int evidenceExpected,
            int evidenceActual,
            int navigationExpected,
            int navigationActual
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DocumentSearchProjectionEntity result = new DocumentSearchProjectionEntity();
        result.setTenantId(version.getTenantId());
        result.setKnowledgeBaseId(document.getKnowledgeBaseId());
        result.setDocumentId(document.getId());
        result.setDocumentVersionId(version.getId());
        result.setCanonicalArtifactId(canonical.getId());
        result.setCanonicalArtifactSha256(canonical.getCanonicalSha256());
        result.setRetrievalArtifactId(retrieval.getId());
        result.setRetrievalArtifactSha256(retrieval.getRetrievalSha256());
        result.setClusterUuid(indices.clusterUuid());
        result.setEvidenceIndexName(indices.evidence().name());
        result.setEvidenceIndexUuid(indices.evidence().uuid());
        result.setEvidenceMappingVersion(indices.evidence().mappingVersion());
        result.setEvidenceExpectedCount(evidenceExpected);
        result.setEvidenceActualCount(evidenceActual);
        result.setNavigationIndexName(indices.navigation().name());
        result.setNavigationIndexUuid(indices.navigation().uuid());
        result.setNavigationMappingVersion(indices.navigation().mappingVersion());
        result.setNavigationExpectedCount(navigationExpected);
        result.setNavigationActualCount(navigationActual);
        result.setProjectionFingerprint(projectionFingerprint);
        result.setCompletedAt(now);
        result.setCreatedAt(existing == null ? now : existing.getCreatedAt());
        result.setUpdatedAt(now);
        return result;
    }

    private SearchProjectionException invalid(String message) {
        return new SearchProjectionException(
                "SEARCH_PROJECTION_VALIDATION_FAILED",
                message,
                false
        );
    }

    private SearchProjectionException unavailable(String message, Throwable cause) {
        return new SearchProjectionException(
                "SEARCH_INDEX_UNAVAILABLE",
                message,
                true,
                cause
        );
    }

    /** 调用方只有完整读到 EOF 后才能取得最终字节数与 SHA-256。 */
    private static final class MeasuredInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;

        private MeasuredInputStream(InputStream input) {
            super(input);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
                digest.update((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                digest.update(buffer, offset, read);
            }
            return read;
        }

        private long count() {
            return count;
        }

        private String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
