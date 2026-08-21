package com.doc.docquery.service.impl;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryIdempotencyException;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.audit.QueryIdempotencyDisposition;
import com.doc.docquery.config.RetrieveProperties;
import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.enums.RetrievalMode;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.parser.BlockKind;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.retrieval.CanonicalArtifactReader;
import com.doc.docquery.search.SearchRetrievalException;
import com.doc.docquery.search.SearchRetrievalGateway;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.QueryAccessService;
import com.doc.docquery.service.QueryEmbeddingGateway;
import com.doc.docquery.service.QueryIdempotencyService;
import com.doc.docquery.service.RetrievalGenerationException;
import com.doc.docquery.service.RetrieveException;
import com.doc.docquery.service.RetrieveService;
import com.doc.docquery.service.ScopedRetrievalService;
import com.doc.docquery.vo.RetrieveResponseVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import static com.doc.docquery.cache.QueryIdempotencyException.Reason.CORRUPTED_STATE;
import static com.doc.docquery.service.RetrieveException.Reason.EVIDENCE_UNAVAILABLE;
import static com.doc.docquery.service.RetrieveException.Reason.INVALID_REQUEST;
import static com.doc.docquery.service.RetrieveException.Reason.QUERY_EMBEDDING_UNAVAILABLE;
import static com.doc.docquery.service.RetrieveException.Reason.REQUEST_IN_PROGRESS;
import static com.doc.docquery.service.RetrieveException.Reason.SEARCH_UNAVAILABLE;

/** N3.2 固定检索流水线：授权快照、幂等、双路召回、RRF 和 canonical 举证。 */
@Service
public class RetrieveServiceImpl implements RetrieveService, ScopedRetrievalService {

    private static final String REQUEST_VERSION = "retrieve-request-v1";
    private static final String RANKING_VERSION = "retrieve-ranking-v1";
    private static final String ROOT_PLACEHOLDER = "@document-profile-root";

    private final QueryAccessService accessService;
    private final QueryIdempotencyService idempotencyService;
    private final Optional<SearchRetrievalGateway> searchGateway;
    private final Optional<QueryEmbeddingGateway> embeddingGateway;
    private final Optional<CanonicalArtifactStore> canonicalStore;
    private final DocumentCanonicalArtifactMapper canonicalMapper;
    private final CanonicalArtifactReader canonicalReader;
    private final RetrieveProperties properties;
    private final SearchProjectionProperties searchProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public RetrieveServiceImpl(
            QueryAccessService accessService,
            QueryIdempotencyService idempotencyService,
            Optional<SearchRetrievalGateway> searchGateway,
            Optional<QueryEmbeddingGateway> embeddingGateway,
            Optional<CanonicalArtifactStore> canonicalStore,
            DocumentCanonicalArtifactMapper canonicalMapper,
            CanonicalArtifactReader canonicalReader,
            RetrieveProperties properties,
            SearchProjectionProperties searchProperties,
            ObjectMapper objectMapper,
            @Qualifier("retrieveExecutor") ExecutorService executor
    ) {
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.searchGateway = searchGateway;
        this.embeddingGateway = embeddingGateway;
        this.canonicalStore = canonicalStore;
        this.canonicalMapper = canonicalMapper;
        this.canonicalReader = canonicalReader;
        this.properties = properties;
        this.searchProperties = searchProperties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        validateProperties();
    }

    @Override
    public RetrieveResponseVO retrieve(
            String authorizationHeader,
            String idempotencyKey,
            long knowledgeBaseId,
            RetrieveRequestDTO request
    ) {
        return retrieve(
                authorizationHeader,
                idempotencyKey,
                knowledgeBaseId,
                request,
                new QueryExecutionTelemetry()
        );
    }

    @Override
    public RetrieveResponseVO retrieve(
            String authorizationHeader,
            String idempotencyKey,
            long knowledgeBaseId,
            RetrieveRequestDTO request,
            QueryExecutionTelemetry telemetry
    ) {
        NormalizedRequest normalized = normalize(knowledgeBaseId, request);
        QueryAccessContext context = accessService.authorizeAndSnapshot(
                authorizationHeader,
                knowledgeBaseId
        );
        telemetry.snapshot(context);
        QueryIdempotencyClaim claim;
        try {
            claim = idempotencyService.claim(
                    context,
                    QueryOperation.RETRIEVE,
                    idempotencyKey,
                    requestFingerprint(normalized)
            );
        } catch (QueryIdempotencyException exception) {
            if (exception.reason() == QueryIdempotencyException.Reason.IDEMPOTENCY_CONFLICT
                    || exception.reason() == QueryIdempotencyException.Reason.CONTEXT_CHANGED) {
                telemetry.idempotency(QueryIdempotencyDisposition.CONFLICT);
            }
            throw exception;
        }
        if (claim.getStatus() == QueryIdempotencyClaim.Status.IN_PROGRESS) {
            telemetry.idempotency(QueryIdempotencyDisposition.IN_PROGRESS);
            throw new RetrieveException(REQUEST_IN_PROGRESS, "Retrieve request is in progress");
        }
        if (claim.getStatus() == QueryIdempotencyClaim.Status.REPLAY) {
            telemetry.idempotency(QueryIdempotencyDisposition.REPLAY);
            RetrieveResponseVO response = replay(claim.getReplayResult());
            telemetry.capture(response);
            return response;
        }
        telemetry.idempotency(QueryIdempotencyDisposition.OWNER);

        try {
            RetrieveResponseVO response = execute(context, normalized);
            telemetry.capture(response);
            String json = objectMapper.writeValueAsString(response);
            idempotencyService.complete(claim, json);
            return response;
        } catch (RuntimeException exception) {
            try {
                idempotencyService.release(claim);
            } catch (RuntimeException releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }
            throw exception;
        }
    }

    @Override
    public RetrieveResponseVO retrieve(
            QueryAccessContext context,
            RetrieveRequestDTO request
    ) {
        if (context == null || context.getKnowledgeBaseId() == null) {
            throw invalidRequest();
        }
        NormalizedRequest normalized = normalize(context.getKnowledgeBaseId(), request);
        return execute(context, normalized);
    }

    @Override
    public ScopedDocument loadDocument(QueryAccessContext context, long documentId) {
        if (context == null || documentId < 1) {
            throw evidenceUnavailable(null);
        }
        ActiveDocumentVersionSnapshot version = context.getActiveVersions().stream()
                .filter(candidate -> candidate.documentId() == documentId)
                .findFirst()
                .orElseThrow(() -> evidenceUnavailable(null));
        return new ScopedDocument(version, loadCanonical(context, version));
    }

    private RetrieveResponseVO execute(
            QueryAccessContext context,
            NormalizedRequest request
    ) {
        if (context.getActiveVersions().isEmpty()) {
            return response(context, request, request.mode(), false, null, List.of());
        }

        RetrievalChannels channels = switch (request.mode()) {
            case KEYWORD -> new RetrievalChannels(
                    keyword(context, request.query()),
                    List.of(),
                    RetrievalMode.KEYWORD,
                    false,
                    null
            );
            case SEMANTIC -> semanticOnly(context, request.query());
            case HYBRID -> hybrid(context, request.query());
        };

        List<SectionCandidate> ranked = rank(
                context,
                channels.keywordHits(),
                channels.semanticHits()
        );
        List<RetrieveResponseVO.Result> results = evidence(
                context,
                ranked,
                request.topK()
        );
        return response(
                context,
                request,
                channels.executedMode(),
                channels.degraded(),
                channels.degradationReason(),
                results
        );
    }

    private RetrievalChannels hybrid(QueryAccessContext context, String query) {
        CompletableFuture<List<SearchRetrievalGateway.KeywordHit>> keyword =
                CompletableFuture.supplyAsync(() -> keyword(context, query), executor);
        CompletableFuture<List<SearchRetrievalGateway.SemanticHit>> semantic =
                CompletableFuture.supplyAsync(() -> semantic(context, query), executor);

        List<SearchRetrievalGateway.KeywordHit> keywordHits;
        try {
            keywordHits = keyword.join();
        } catch (CompletionException exception) {
            semantic.cancel(true);
            throw searchUnavailable(rootCause(exception));
        }

        try {
            return new RetrievalChannels(
                    keywordHits,
                    semantic.join(),
                    RetrievalMode.HYBRID,
                    false,
                    null
            );
        } catch (CompletionException exception) {
            Throwable failure = rootCause(exception);
            String reason = failure instanceof SemanticBranchException branch
                    ? branch.degradationReason
                    : "SEMANTIC_SEARCH_UNAVAILABLE";
            return new RetrievalChannels(
                    keywordHits,
                    List.of(),
                    RetrievalMode.KEYWORD,
                    true,
                    reason
            );
        }
    }

    private RetrievalChannels semanticOnly(QueryAccessContext context, String query) {
        try {
            return new RetrievalChannels(
                    List.of(),
                    semantic(context, query),
                    RetrievalMode.SEMANTIC,
                    false,
                    null
            );
        } catch (SemanticBranchException exception) {
            if ("QUERY_EMBEDDING_UNAVAILABLE".equals(exception.degradationReason)) {
                throw new RetrieveException(
                        QUERY_EMBEDDING_UNAVAILABLE,
                        "Query embedding is unavailable",
                        exception
                );
            }
            throw searchUnavailable(exception);
        }
    }

    private List<SearchRetrievalGateway.KeywordHit> keyword(
            QueryAccessContext context,
            String query
    ) {
        SearchRetrievalGateway gateway = searchGateway.orElseThrow(
                () -> searchUnavailable(null)
        );
        try {
            return gateway.searchKeyword(context, query, properties.getKeywordCandidates());
        } catch (SearchRetrievalException exception) {
            throw searchUnavailable(exception);
        }
    }

    private List<SearchRetrievalGateway.SemanticHit> semantic(
            QueryAccessContext context,
            String query
    ) {
        QueryEmbeddingGateway embedder = embeddingGateway.orElseThrow(
                () -> new SemanticBranchException(
                        "QUERY_EMBEDDING_UNAVAILABLE",
                        new RetrieveException(
                                QUERY_EMBEDDING_UNAVAILABLE,
                                "Query embedding is unavailable"
                        )
                )
        );
        float[] vector;
        try {
            vector = embedder.embedQuery(query);
            validateVector(vector);
        } catch (SemanticBranchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SemanticBranchException("QUERY_EMBEDDING_UNAVAILABLE", exception);
        }

        SearchRetrievalGateway gateway = searchGateway.orElseThrow(
                () -> new SemanticBranchException("SEMANTIC_SEARCH_UNAVAILABLE", null)
        );
        try {
            return gateway.searchSemantic(
                    context,
                    vector,
                    properties.getKnnK(),
                    properties.getKnnNumCandidates()
            );
        } catch (SearchRetrievalException exception) {
            throw new SemanticBranchException("SEMANTIC_SEARCH_UNAVAILABLE", exception);
        }
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != searchProperties.getEmbeddingDimension()) {
            throw new SemanticBranchException("QUERY_EMBEDDING_UNAVAILABLE", null);
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new SemanticBranchException("QUERY_EMBEDDING_UNAVAILABLE", null);
            }
            nonZero |= value != 0.0f;
        }
        if (!nonZero) {
            throw new SemanticBranchException("QUERY_EMBEDDING_UNAVAILABLE", null);
        }
    }

    private List<SectionCandidate> rank(
            QueryAccessContext context,
            List<SearchRetrievalGateway.KeywordHit> keywordHits,
            List<SearchRetrievalGateway.SemanticHit> semanticHits
    ) {
        Map<Long, ActiveDocumentVersionSnapshot> versions = snapshotByVersion(context);
        Map<SectionKey, SectionCandidate> candidates = new LinkedHashMap<>();
        for (SearchRetrievalGateway.KeywordHit hit : keywordHits) {
            requireScope(versions, hit.documentId(), hit.documentVersionId());
            SectionKey key = new SectionKey(hit.documentVersionId(), hit.headingNodeId());
            SectionCandidate candidate = candidates.computeIfAbsent(
                    key,
                    ignored -> new SectionCandidate(key, hit.documentId())
            );
            candidate.requireDocument(hit.documentId());
            candidate.keywordRank = minimum(candidate.keywordRank, hit.rank());
            candidate.keywordHits.add(hit);
        }

        List<SearchRetrievalGateway.SemanticHit> profiles = new ArrayList<>();
        for (SearchRetrievalGateway.SemanticHit hit : semanticHits) {
            requireScope(versions, hit.documentId(), hit.documentVersionId());
            if ("DOCUMENT_PROFILE".equals(hit.cardType())) {
                profiles.add(hit);
                continue;
            }
            if (!"HEADING_NODE".equals(hit.cardType())
                    || hit.headingNodeId() == null
                    || hit.headingNodeId().isBlank()) {
                throw searchUnavailable(null);
            }
            SectionKey key = new SectionKey(hit.documentVersionId(), hit.headingNodeId());
            SectionCandidate candidate = candidates.computeIfAbsent(
                    key,
                    ignored -> new SectionCandidate(key, hit.documentId())
            );
            candidate.requireDocument(hit.documentId());
            if (candidate.semanticRank == null || hit.rank() < candidate.semanticRank) {
                candidate.semanticRank = hit.rank();
                candidate.semanticHit = hit;
            }
        }

        for (SearchRetrievalGateway.SemanticHit profile : profiles) {
            SectionCandidate target = candidates.values().stream()
                    .filter(candidate -> candidate.key.documentVersionId()
                            == profile.documentVersionId())
                    .min(Comparator
                            .comparingInt(SectionCandidate::bestRank)
                            .thenComparing(candidate -> candidate.key.headingNodeId()))
                    .orElse(null);
            if (target == null) {
                SectionKey key = new SectionKey(
                        profile.documentVersionId(),
                        ROOT_PLACEHOLDER
                );
                target = candidates.computeIfAbsent(
                        key,
                        ignored -> new SectionCandidate(key, profile.documentId())
                );
            }
            if (target.semanticRank == null || profile.rank() < target.semanticRank) {
                target.semanticRank = profile.rank();
                target.semanticHit = profile;
            }
        }

        List<SectionCandidate> ranked = new ArrayList<>(candidates.values());
        for (SectionCandidate candidate : ranked) {
            candidate.score = rrf(candidate.keywordRank) + rrf(candidate.semanticRank);
        }
        ranked.sort(Comparator
                .comparingDouble((SectionCandidate candidate) -> candidate.score)
                .reversed()
                .thenComparingInt(SectionCandidate::bestRank)
                .thenComparingLong(candidate -> candidate.key.documentVersionId())
                .thenComparing(candidate -> candidate.key.headingNodeId()));
        return List.copyOf(ranked);
    }

    private double rrf(Integer rank) {
        return rank == null ? 0.0d : 1.0d / (properties.getRrfK() + rank);
    }

    private List<RetrieveResponseVO.Result> evidence(
            QueryAccessContext context,
            List<SectionCandidate> ranked,
            int topK
    ) {
        Map<Long, ActiveDocumentVersionSnapshot> versions = snapshotByVersion(context);
        Map<Long, CanonicalDocument> canonicalCache = new HashMap<>();
        List<RetrieveResponseVO.Result> results = new ArrayList<>();
        int remainingTotalCharacters = properties.getMaxTotalEvidenceChars();

        for (SectionCandidate candidate : ranked) {
            if (results.size() >= topK || remainingTotalCharacters < 1) {
                break;
            }
            ActiveDocumentVersionSnapshot version = versions.get(
                    candidate.key.documentVersionId()
            );
            CanonicalDocument canonical = canonicalCache.computeIfAbsent(
                    candidate.key.documentVersionId(),
                    ignored -> loadCanonical(context, version)
            );
            Materialized materialized = materialize(
                    candidate,
                    version,
                    canonical,
                    remainingTotalCharacters
            );
            if (materialized == null || materialized.evidence().isEmpty()) {
                continue;
            }
            remainingTotalCharacters -= materialized.characters();
            results.add(new RetrieveResponseVO.Result(
                    results.size() + 1,
                    version.documentId(),
                    version.documentVersionId(),
                    version.versionNo(),
                    version.documentName(),
                    materialized.heading().nodeId(),
                    headingPath(materialized.heading(), canonical.headings()),
                    channels(candidate),
                    candidate.keywordRank,
                    candidate.semanticRank,
                    materialized.evidence()
            ));
        }
        return List.copyOf(results);
    }

    private Materialized materialize(
            SectionCandidate candidate,
            ActiveDocumentVersionSnapshot version,
            CanonicalDocument canonical,
            int remainingTotalCharacters
    ) {
        if (canonical.documentVersionId() != version.documentVersionId()) {
            throw evidenceUnavailable(null);
        }
        Map<String, HeadingNode> headings = new HashMap<>();
        for (HeadingNode heading : canonical.headings()) {
            headings.put(heading.nodeId(), heading);
        }

        HeadingNode heading;
        if (ROOT_PLACEHOLDER.equals(candidate.key.headingNodeId())) {
            if (canonical.headings().size() != 1) {
                return null;
            }
            heading = canonical.headings().get(0);
        } else {
            heading = headings.get(candidate.key.headingNodeId());
        }
        if (heading == null) {
            throw evidenceUnavailable(null);
        }

        Map<String, EvidenceBlock> blocksById = new HashMap<>();
        for (EvidenceBlock block : canonical.blocks()) {
            blocksById.put(block.blockId(), block);
        }
        validateSemanticAddress(candidate.semanticHit, heading, canonical.blocks());

        List<EvidenceBlock> keywordBlocks = new ArrayList<>();
        Map<String, List<SearchRetrievalGateway.HighlightFragment>> highlights =
                new HashMap<>();
        candidate.keywordHits.stream()
                .sorted(Comparator.comparingInt(SearchRetrievalGateway.KeywordHit::rank))
                .forEach(hit -> {
                    EvidenceBlock block = blocksById.get(hit.blockId());
                    if (block == null
                            || !heading.nodeId().equals(block.headingNodeId())
                            || block.ordinal() != hit.ordinal()
                            || block.canonicalStart() != hit.canonicalStart()
                            || block.canonicalEnd() != hit.canonicalEnd()) {
                        throw evidenceUnavailable(null);
                    }
                    keywordBlocks.add(block);
                    highlights.computeIfAbsent(block.blockId(), ignored -> new ArrayList<>())
                            .addAll(hit.highlights());
                });

        List<EvidenceBlock> selected = selectBlocks(
                heading,
                canonical.blocks(),
                keywordBlocks
        );
        int resultBudget = Math.min(
                properties.getMaxEvidenceCharsPerResult(),
                remainingTotalCharacters
        );
        int used = 0;
        List<RetrieveResponseVO.Evidence> evidence = new ArrayList<>();
        for (EvidenceBlock block : selected) {
            if (evidence.size() >= properties.getMaxEvidenceBlocksPerResult()
                    || used >= resultBudget) {
                break;
            }
            int available = resultBudget - used;
            String text = prefixByCodeUnits(block.text(), available);
            if (text.isEmpty()) {
                continue;
            }
            boolean truncated = text.length() < block.text().length();
            evidence.add(new RetrieveResponseVO.Evidence(
                    block.blockId(),
                    block.kind().name(),
                    text,
                    truncated,
                    block.canonicalStart(),
                    block.canonicalEnd(),
                    sourcePosition(block.sourcePosition()),
                    truncated
                            ? List.of()
                            : safeHighlights(block.text(), highlights.get(block.blockId()))
            ));
            used += text.length();
        }
        return new Materialized(heading, List.copyOf(evidence), used);
    }

    private List<EvidenceBlock> selectBlocks(
            HeadingNode heading,
            List<EvidenceBlock> allBlocks,
            List<EvidenceBlock> keywordBlocks
    ) {
        Set<String> selectedIds = new LinkedHashSet<>();
        List<EvidenceBlock> selected = new ArrayList<>();
        for (EvidenceBlock block : keywordBlocks) {
            addSelected(selected, selectedIds, block);
        }

        List<EvidenceBlock> sectionBlocks = allBlocks.stream()
                .filter(block -> keywordBlocks.isEmpty()
                        ? block.ordinal() >= heading.sectionStartBlockOrdinal()
                        && block.ordinal() < heading.sectionEndBlockOrdinalExclusive()
                        : heading.nodeId().equals(block.headingNodeId()))
                .filter(block -> block.text() != null && !block.text().isBlank())
                .toList();
        Comparator<EvidenceBlock> byRelevance;
        if (keywordBlocks.isEmpty()) {
            byRelevance = Comparator
                    .comparingInt((EvidenceBlock block) -> structural(block.kind()))
                    .thenComparingInt(EvidenceBlock::ordinal);
        } else {
            byRelevance = Comparator
                    .comparingInt((EvidenceBlock block) -> distance(block, keywordBlocks))
                    .thenComparingInt(block -> structural(block.kind()))
                    .thenComparingInt(EvidenceBlock::ordinal);
        }
        sectionBlocks.stream().sorted(byRelevance).forEach(block ->
                addSelected(selected, selectedIds, block));
        return selected;
    }

    private void addSelected(
            List<EvidenceBlock> selected,
            Set<String> selectedIds,
            EvidenceBlock block
    ) {
        if (selected.size() < properties.getMaxEvidenceBlocksPerResult()
                && selectedIds.add(block.blockId())) {
            selected.add(block);
        }
    }

    private int structural(BlockKind kind) {
        return kind == BlockKind.TITLE || kind == BlockKind.HEADING ? 1 : 0;
    }

    private int distance(EvidenceBlock block, List<EvidenceBlock> keywordBlocks) {
        return keywordBlocks.stream()
                .mapToInt(hit -> Math.abs(block.ordinal() - hit.ordinal()))
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private void validateSemanticAddress(
            SearchRetrievalGateway.SemanticHit hit,
            HeadingNode heading,
            List<EvidenceBlock> blocks
    ) {
        if (hit == null || "DOCUMENT_PROFILE".equals(hit.cardType())) {
            return;
        }
        if (!heading.nodeId().equals(hit.headingNodeId())
                || hit.sectionStartBlockOrdinal() == null
                || hit.sectionEndBlockOrdinalExclusive() == null
                || hit.sectionStartBlockOrdinal() != heading.sectionStartBlockOrdinal()
                || hit.sectionEndBlockOrdinalExclusive()
                != heading.sectionEndBlockOrdinalExclusive()) {
            throw evidenceUnavailable(null);
        }
        EvidenceBlock first = blockAtOrdinal(blocks, heading.sectionStartBlockOrdinal());
        EvidenceBlock last = blockAtOrdinal(
                blocks,
                heading.sectionEndBlockOrdinalExclusive() - 1
        );
        if (hit.canonicalStart() != null
                && first != null
                && hit.canonicalStart() != first.canonicalStart()) {
            throw evidenceUnavailable(null);
        }
        if (hit.canonicalEnd() != null
                && last != null
                && hit.canonicalEnd() != last.canonicalEnd()) {
            throw evidenceUnavailable(null);
        }
    }

    private EvidenceBlock blockAtOrdinal(List<EvidenceBlock> blocks, int ordinal) {
        return blocks.stream()
                .filter(block -> block.ordinal() == ordinal)
                .findFirst()
                .orElse(null);
    }

    private CanonicalDocument loadCanonical(
            QueryAccessContext context,
            ActiveDocumentVersionSnapshot version
    ) {
        if (version == null) {
            throw evidenceUnavailable(null);
        }
        try {
            DocumentCanonicalArtifactEntity manifest = canonicalMapper
                    .findByDocumentVersionId(version.documentVersionId());
            CanonicalArtifactStore store = canonicalStore.orElseThrow(
                    () -> evidenceUnavailable(null)
            );
            if (manifest == null
                    || !context.getTenantId().equals(manifest.getTenantId())
                    || !version.documentVersionId().equals(manifest.getDocumentVersionId())
                    || !store.bucketName().equals(manifest.getCanonicalBucket())) {
                throw evidenceUnavailable(null);
            }
            try (MeasuredInputStream input = new MeasuredInputStream(
                    store.open(manifest.getCanonicalObjectKey())
            )) {
                CanonicalDocument canonical = canonicalReader.read(input);
                String objectSha256 = input.sha256();
                if (!manifest.getCanonicalSizeBytes().equals(input.count())
                        || !manifest.getCanonicalSha256().equals(objectSha256)
                        || canonical.documentVersionId() != version.documentVersionId()
                        || canonical.blocks().size() != manifest.getBlockCount()
                        || canonical.headings().size() != manifest.getHeadingCount()
                        || canonical.warnings().size() != manifest.getWarningCount()
                        || canonical.textLength() != manifest.getTextLength()
                        || !canonical.canonicalTextSha256().equals(
                        manifest.getCanonicalTextSha256())) {
                    throw evidenceUnavailable(null);
                }
                return canonical;
            }
        } catch (RetrieveException exception) {
            throw exception;
        } catch (RetrievalGenerationException | ObjectStorageException
                 | DataAccessException | IOException exception) {
            throw evidenceUnavailable(exception);
        } catch (RuntimeException exception) {
            throw evidenceUnavailable(exception);
        }
    }

    private List<String> headingPath(HeadingNode heading, List<HeadingNode> headings) {
        Map<String, HeadingNode> byId = new HashMap<>();
        for (HeadingNode candidate : headings) {
            byId.put(candidate.nodeId(), candidate);
        }
        List<String> reversed = new ArrayList<>();
        HeadingNode current = heading;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null) {
            if (!visited.add(current.nodeId())) {
                throw evidenceUnavailable(null);
            }
            reversed.add(current.title());
            current = current.parentNodeId() == null
                    ? null
                    : byId.get(current.parentNodeId());
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private List<String> channels(SectionCandidate candidate) {
        List<String> result = new ArrayList<>(2);
        if (candidate.keywordRank != null) {
            result.add("KEYWORD");
        }
        if (candidate.semanticRank != null) {
            result.add("SEMANTIC");
        }
        return List.copyOf(result);
    }

    private RetrieveResponseVO.SourcePosition sourcePosition(
            com.doc.docquery.parser.SourcePosition position
    ) {
        if (position == null) {
            return null;
        }
        return new RetrieveResponseVO.SourcePosition(
                position.sourceType(),
                position.pageNumber(),
                position.pageBlockOrdinal(),
                position.pageCharacterStart(),
                position.pageCharacterEnd(),
                position.bodyElementIndex(),
                position.tableRow(),
                position.tableColumn(),
                position.cellParagraphIndex(),
                position.startLine(),
                position.startColumn(),
                position.endLine(),
                position.endColumn()
        );
    }

    private List<RetrieveResponseVO.HighlightFragment> safeHighlights(
            String canonicalText,
            List<SearchRetrievalGateway.HighlightFragment> fragments
    ) {
        if (fragments == null) {
            return List.of();
        }
        List<RetrieveResponseVO.HighlightFragment> result = new ArrayList<>();
        for (SearchRetrievalGateway.HighlightFragment fragment : fragments) {
            String plain = fragment.segments().stream()
                    .map(SearchRetrievalGateway.HighlightSegment::text)
                    .reduce("", String::concat);
            if (!plain.isEmpty() && canonicalText.contains(plain)) {
                result.add(new RetrieveResponseVO.HighlightFragment(
                        fragment.segments().stream()
                                .map(segment -> new RetrieveResponseVO.HighlightSegment(
                                        segment.text(),
                                        segment.matched()
                                ))
                                .toList()
                ));
            }
        }
        return List.copyOf(result);
    }

    private RetrieveResponseVO response(
            QueryAccessContext context,
            NormalizedRequest request,
            RetrievalMode executedMode,
            boolean degraded,
            String degradationReason,
            List<RetrieveResponseVO.Result> results
    ) {
        return new RetrieveResponseVO(
                UUID.randomUUID().toString(),
                context.getKnowledgeBaseId(),
                request.mode().name(),
                executedMode.name(),
                degraded,
                degradationReason,
                List.copyOf(results)
        );
    }

    private NormalizedRequest normalize(long knowledgeBaseId, RetrieveRequestDTO request) {
        if (knowledgeBaseId < 1 || request == null || request.getQuery() == null) {
            throw invalidRequest();
        }
        String query = request.getQuery().strip();
        if (query.isEmpty()
                || query.codePointCount(0, query.length()) > properties.getMaxQueryCodePoints()) {
            throw invalidRequest();
        }
        RetrievalMode mode;
        try {
            mode = request.getMode() == null || request.getMode().isBlank()
                    ? RetrievalMode.HYBRID
                    : RetrievalMode.valueOf(request.getMode().strip().toUpperCase(
                    java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
        int topK = request.getTopK() == null
                ? properties.getDefaultTopK()
                : request.getTopK();
        if (topK < 1 || topK > properties.getMaxTopK()) {
            throw invalidRequest();
        }
        return new NormalizedRequest(knowledgeBaseId, query, mode, topK);
    }

    private String requestFingerprint(NormalizedRequest request) {
        return sha256(REQUEST_VERSION + '\n'
                + request.knowledgeBaseId() + '\n'
                + request.query() + '\n'
                + request.mode().name() + '\n'
                + request.topK() + '\n'
                + RANKING_VERSION + '\n');
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private RetrieveResponseVO replay(String json) {
        try {
            return objectMapper.readValue(json, RetrieveResponseVO.class);
        } catch (RuntimeException exception) {
            throw new QueryIdempotencyException(
                    CORRUPTED_STATE,
                    "Query idempotency result cannot be decoded",
                    exception
            );
        }
    }

    private Map<Long, ActiveDocumentVersionSnapshot> snapshotByVersion(
            QueryAccessContext context
    ) {
        Map<Long, ActiveDocumentVersionSnapshot> result = new HashMap<>();
        for (ActiveDocumentVersionSnapshot version : context.getActiveVersions()) {
            if (result.put(version.documentVersionId(), version) != null) {
                throw searchUnavailable(null);
            }
        }
        return result;
    }

    private void requireScope(
            Map<Long, ActiveDocumentVersionSnapshot> versions,
            long documentId,
            long documentVersionId
    ) {
        ActiveDocumentVersionSnapshot version = versions.get(documentVersionId);
        if (version == null || version.documentId() != documentId) {
            throw searchUnavailable(null);
        }
    }

    private Integer minimum(Integer current, int candidate) {
        return current == null || candidate < current ? candidate : current;
    }

    private String prefixByCodeUnits(String text, int maxCodeUnits) {
        if (text == null || maxCodeUnits < 1) {
            return "";
        }
        if (text.length() <= maxCodeUnits) {
            return text;
        }
        int end = maxCodeUnits;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private RetrieveException invalidRequest() {
        return new RetrieveException(INVALID_REQUEST, "Retrieve request is invalid");
    }

    private RetrieveException searchUnavailable(Throwable cause) {
        return new RetrieveException(
                SEARCH_UNAVAILABLE,
                "Search is unavailable",
                cause
        );
    }

    private RetrieveException evidenceUnavailable(Throwable cause) {
        return new RetrieveException(
                EVIDENCE_UNAVAILABLE,
                "Canonical evidence is unavailable",
                cause
        );
    }

    private void validateProperties() {
        if (properties.getMaxQueryCodePoints() < 1
                || properties.getDefaultTopK() < 1
                || properties.getDefaultTopK() > properties.getMaxTopK()
                || properties.getKeywordCandidates() < properties.getMaxTopK()
                || properties.getKnnK() < properties.getMaxTopK()
                || properties.getKnnNumCandidates() < properties.getKnnK()
                || properties.getRrfK() < 1
                || properties.getMaxEvidenceBlocksPerResult() < 1
                || properties.getMaxEvidenceCharsPerResult() < 1
                || properties.getMaxTotalEvidenceChars()
                < properties.getMaxEvidenceCharsPerResult()) {
            throw new IllegalStateException("Retrieve configuration is invalid");
        }
    }

    private record NormalizedRequest(
            long knowledgeBaseId,
            String query,
            RetrievalMode mode,
            int topK
    ) {
    }

    private record RetrievalChannels(
            List<SearchRetrievalGateway.KeywordHit> keywordHits,
            List<SearchRetrievalGateway.SemanticHit> semanticHits,
            RetrievalMode executedMode,
            boolean degraded,
            String degradationReason
    ) {
    }

    private record SectionKey(long documentVersionId, String headingNodeId) {
    }

    private static final class SectionCandidate {
        private final SectionKey key;
        private final long documentId;
        private final List<SearchRetrievalGateway.KeywordHit> keywordHits =
                new ArrayList<>();
        private Integer keywordRank;
        private Integer semanticRank;
        private SearchRetrievalGateway.SemanticHit semanticHit;
        private double score;

        private SectionCandidate(SectionKey key, long documentId) {
            this.key = key;
            this.documentId = documentId;
        }

        private void requireDocument(long expectedDocumentId) {
            if (documentId != expectedDocumentId) {
                throw new RetrieveException(SEARCH_UNAVAILABLE, "Search is unavailable");
            }
        }

        private int bestRank() {
            return Math.min(
                    keywordRank == null ? Integer.MAX_VALUE : keywordRank,
                    semanticRank == null ? Integer.MAX_VALUE : semanticRank
            );
        }
    }

    private record Materialized(
            HeadingNode heading,
            List<RetrieveResponseVO.Evidence> evidence,
            int characters
    ) {
    }

    /** HYBRID 内部保留可公开的降级原因；显式 SEMANTIC 会被上层映射为 503。 */
    private static final class SemanticBranchException extends RuntimeException {
        private final String degradationReason;

        private SemanticBranchException(String degradationReason, Throwable cause) {
            super(degradationReason, cause);
            this.degradationReason = degradationReason;
        }
    }

    /** 调用方完整读到 EOF 后才能比较对象清单。 */
    private static final class MeasuredInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;
        private String completedSha256;

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
            if (completedSha256 == null) {
                completedSha256 = HexFormat.of().formatHex(digest.digest());
            }
            return completedSha256;
        }
    }
}
