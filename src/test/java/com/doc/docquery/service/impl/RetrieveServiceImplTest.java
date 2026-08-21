package com.doc.docquery.service.impl;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.config.RetrieveProperties;
import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.parser.BlockKind;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.CanonicalJsonlWriter;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.parser.SourcePosition;
import com.doc.docquery.retrieval.CanonicalArtifactReader;
import com.doc.docquery.search.SearchRetrievalGateway;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.QueryAccessService;
import com.doc.docquery.service.QueryEmbeddingGateway;
import com.doc.docquery.service.QueryIdempotencyService;
import com.doc.docquery.service.RetrieveException;
import com.doc.docquery.vo.RetrieveResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 不依赖容器验证 N3.2 并行、RRF、canonical 举证、降级和成功重放。 */
class RetrieveServiceImplTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void hybridRunsBranchesInParallelReturnsCanonicalEvidenceAndReplays() {
        Fixture fixture = fixture();
        CountDownLatch parallel = new CountDownLatch(2);
        AtomicInteger keywordCalls = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger embeddingCalls = new AtomicInteger();

        SearchRetrievalGateway search = new SearchRetrievalGateway() {
            @Override
            public List<KeywordHit> searchKeyword(
                    QueryAccessContext context,
                    String query,
                    int candidates
            ) {
                keywordCalls.incrementAndGet();
                awaitTogether(parallel);
                return List.of(new KeywordHit(
                        1, 31L, 41L, "b-1", "h-1", 1,
                        fixture.blocks().get(1).canonicalStart(),
                        fixture.blocks().get(1).canonicalEnd(),
                        List.of(new HighlightFragment(List.of(
                                new HighlightSegment("Refunds", true),
                                new HighlightSegment(" are allowed", false)
                        )))
                ));
            }

            @Override
            public List<SemanticHit> searchSemantic(
                    QueryAccessContext context,
                    float[] queryVector,
                    int k,
                    int numCandidates
            ) {
                semanticCalls.incrementAndGet();
                return List.of(new SemanticHit(
                        1, 31L, 41L, "card-h-1", "HEADING_NODE", "h-1",
                        0, 2,
                        fixture.blocks().get(0).canonicalStart(),
                        fixture.blocks().get(1).canonicalEnd()
                ));
            }
        };
        QueryEmbeddingGateway embedding = query -> {
            embeddingCalls.incrementAndGet();
            awaitTogether(parallel);
            float[] vector = new float[2560];
            java.util.Arrays.fill(vector, 1.0f);
            return vector;
        };
        StatefulIdempotency idempotency = new StatefulIdempotency();
        RetrieveServiceImpl service = service(fixture, search, embedding, idempotency);

        RetrieveRequestDTO request = request("refund requirement", "HYBRID", 1);
        RetrieveResponseVO first = service.retrieve(
                "Bearer token", "unit-idempotency-key", 21L, request
        );
        RetrieveResponseVO replay = service.retrieve(
                "Bearer token", "unit-idempotency-key", 21L, request
        );

        assertThat(first.getExecutedMode()).isEqualTo("HYBRID");
        assertThat(first.isDegraded()).isFalse();
        assertThat(first.getResults()).singleElement().satisfies(result -> {
            assertThat(result.getChannels()).containsExactly("KEYWORD", "SEMANTIC");
            assertThat(result.getHeadingPath()).containsExactly("Manual", "Refund Policy");
            assertThat(result.getEvidence()).extracting(RetrieveResponseVO.Evidence::getText)
                    .contains("Refunds are allowed within seven days.");
            assertThat(result.getEvidence().stream()
                    .flatMap(evidence -> evidence.getKeywordHighlights().stream()))
                    .isNotEmpty();
        });
        assertThat(replay.getQueryExecutionId()).isEqualTo(first.getQueryExecutionId());
        assertThat(keywordCalls).hasValue(1);
        assertThat(semanticCalls).hasValue(1);
        assertThat(embeddingCalls).hasValue(1);
        assertThat(idempotency.completedJson).contains("Refunds are allowed");
    }

    @Test
    void hybridExplicitlyDegradesWhileSemanticModeFailsClosed() {
        Fixture fixture = fixture();
        SearchRetrievalGateway search = keywordOnlySearch(fixture);
        QueryEmbeddingGateway unavailable = query -> {
            throw new IllegalStateException("provider unavailable");
        };

        RetrieveResponseVO degraded = service(
                fixture,
                search,
                unavailable,
                new StatefulIdempotency()
        ).retrieve(
                "Bearer token",
                "hybrid-degrade-key",
                21L,
                request("refund", "HYBRID", 1)
        );
        assertThat(degraded.getExecutedMode()).isEqualTo("KEYWORD");
        assertThat(degraded.isDegraded()).isTrue();
        assertThat(degraded.getDegradationReason())
                .isEqualTo("QUERY_EMBEDDING_UNAVAILABLE");

        RetrieveException failure = catchThrowableOfType(
                RetrieveException.class,
                () -> service(
                        fixture,
                        search,
                        unavailable,
                        new StatefulIdempotency()
                ).retrieve(
                        "Bearer token",
                        "semantic-failure-key",
                        21L,
                        request("refund", "SEMANTIC", 1)
                )
        );
        assertThat(failure.reason())
                .isEqualTo(RetrieveException.Reason.QUERY_EMBEDDING_UNAVAILABLE);
    }

    @Test
    void emptySnapshotCompletesWithoutSearchEmbeddingOrCanonicalRead() {
        Fixture fixture = fixture();
        AtomicInteger accessCalls = new AtomicInteger();
        QueryAccessService emptyAccess = (header, knowledgeBaseId) -> {
            accessCalls.incrementAndGet();
            return new QueryAccessContext(
                    1L, 11L, 10L, 21L, "1", List.of(), "a".repeat(64)
            );
        };
        StatefulIdempotency idempotency = new StatefulIdempotency();
        RetrieveServiceImpl service = service(
                fixture,
                emptyAccess,
                Optional.empty(),
                Optional.empty(),
                idempotency
        );

        RetrieveResponseVO response = service.retrieve(
                "Bearer token", "empty-key", 21L, request("anything", null, null)
        );

        assertThat(response.getRequestedMode()).isEqualTo("HYBRID");
        assertThat(response.getResults()).isEmpty();
        assertThat(accessCalls).hasValue(1);
        assertThat(idempotency.completedJson).contains("\"results\":[]");
    }

    private RetrieveServiceImpl service(
            Fixture fixture,
            SearchRetrievalGateway search,
            QueryEmbeddingGateway embedding,
            StatefulIdempotency idempotency
    ) {
        return service(
                fixture,
                (header, knowledgeBaseId) -> context(),
                Optional.of(search),
                Optional.of(embedding),
                idempotency
        );
    }

    private RetrieveServiceImpl service(
            Fixture fixture,
            QueryAccessService access,
            Optional<SearchRetrievalGateway> search,
            Optional<QueryEmbeddingGateway> embedding,
            StatefulIdempotency idempotency
    ) {
        DocumentCanonicalArtifactMapper mapper = mock(DocumentCanonicalArtifactMapper.class);
        when(mapper.findByDocumentVersionId(41L)).thenReturn(fixture.manifest());
        ObjectMapper objectMapper = new ObjectMapper();
        SearchProjectionProperties searchProperties = new SearchProjectionProperties();
        return new RetrieveServiceImpl(
                access,
                idempotency,
                search,
                embedding,
                Optional.of(new BytesCanonicalStore(fixture.bytes())),
                mapper,
                new CanonicalArtifactReader(objectMapper, new CanonicalDocumentValidator()),
                new RetrieveProperties(),
                searchProperties,
                objectMapper,
                executor
        );
    }

    private SearchRetrievalGateway keywordOnlySearch(Fixture fixture) {
        return new SearchRetrievalGateway() {
            @Override
            public List<KeywordHit> searchKeyword(
                    QueryAccessContext context,
                    String query,
                    int candidates
            ) {
                EvidenceBlock block = fixture.blocks().get(1);
                return List.of(new KeywordHit(
                        1, 31L, 41L, block.blockId(), "h-1", block.ordinal(),
                        block.canonicalStart(), block.canonicalEnd(), List.of()
                ));
            }

            @Override
            public List<SemanticHit> searchSemantic(
                    QueryAccessContext context,
                    float[] queryVector,
                    int k,
                    int numCandidates
            ) {
                throw new AssertionError("Semantic search must not run after embedding failure");
            }
        };
    }

    private QueryAccessContext context() {
        return new QueryAccessContext(
                1L,
                11L,
                10L,
                21L,
                "1",
                List.of(new ActiveDocumentVersionSnapshot(31L, 41L, 1, "Manual.md")),
                "a".repeat(64)
        );
    }

    private RetrieveRequestDTO request(String query, String mode, Integer topK) {
        RetrieveRequestDTO request = new RetrieveRequestDTO();
        request.setQuery(query);
        request.setMode(mode);
        request.setTopK(topK);
        return request;
    }

    private Fixture fixture() {
        String headingText = "Refund Policy";
        String bodyText = "Refunds are allowed within seven days.";
        EvidenceBlock headingBlock = new EvidenceBlock(
                "b-0", 0, BlockKind.HEADING, headingText,
                0, headingText.length(), "h-1", null,
                SourcePosition.markdown(1, 1, 1, headingText.length())
        );
        long bodyStart = headingBlock.canonicalEnd() + 2;
        EvidenceBlock bodyBlock = new EvidenceBlock(
                "b-1", 1, BlockKind.PARAGRAPH, bodyText,
                bodyStart, bodyStart + bodyText.length(), "h-1", null,
                SourcePosition.markdown(3, 1, 3, bodyText.length())
        );
        List<EvidenceBlock> blocks = List.of(headingBlock, bodyBlock);
        String canonicalText = headingText + "\n\n" + bodyText;
        CanonicalDocument canonical = new CanonicalDocument(
                1,
                41L,
                "MARKDOWN",
                sha256("source"),
                "markdown",
                "1",
                Instant.parse("2026-08-11T00:00:00Z"),
                blocks,
                List.of(
                        new HeadingNode(
                                "root", null, 0, 0, "Manual", null,
                                "DOCUMENT_ROOT", 0, 2
                        ),
                        new HeadingNode(
                                "h-1", "root", 1, 1, headingText, "b-0",
                                "MARKDOWN", 0, 2
                        )
                ),
                List.of(),
                null,
                canonicalText.length(),
                sha256(canonicalText)
        );
        new CanonicalDocumentValidator().validate(canonical);

        ObjectMapper objectMapper = new ObjectMapper();
        DocumentParsingProperties parsing = new DocumentParsingProperties();
        CanonicalJsonlWriter.WrittenArtifact written = new CanonicalJsonlWriter(
                objectMapper,
                parsing
        ).write(canonical);
        try {
            byte[] bytes = Files.readAllBytes(written.path());
            DocumentCanonicalArtifactEntity manifest = new DocumentCanonicalArtifactEntity(
                    51L, 10L, 41L, 1, "MARKDOWN", "markdown", "1",
                    "bucket", "canonical/41.jsonl", written.sizeBytes(),
                    written.sha256(), canonical.canonicalTextSha256(),
                    canonical.textLength(), canonical.blocks().size(),
                    canonical.headings().size(), 0, null, LocalDateTime.now()
            );
            return new Fixture(bytes, manifest, blocks);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } finally {
            try {
                Files.deleteIfExists(written.path());
            } catch (IOException ignored) {
            }
        }
    }

    private void awaitTogether(CountDownLatch latch) {
        latch.countDown();
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Hybrid branches did not overlap");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            byte[] bytes,
            DocumentCanonicalArtifactEntity manifest,
            List<EvidenceBlock> blocks
    ) {
    }

    private static final class BytesCanonicalStore implements CanonicalArtifactStore {
        private final byte[] bytes;

        private BytesCanonicalStore(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public String bucketName() {
            return "bucket";
        }

        @Override
        public InputStream open(String objectKey) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean exists(String objectKey) {
            return true;
        }

        @Override
        public WriteResult put(String key, InputStream input, long size, long max) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ObjectSummary> list(String prefix, int limit) {
            return List.of();
        }
    }

    private static final class StatefulIdempotency implements QueryIdempotencyService {
        private String completedJson;

        @Override
        public QueryIdempotencyClaim claim(
                QueryAccessContext context,
                QueryOperation operation,
                String key,
                String requestFingerprint
        ) {
            if (completedJson != null) {
                return QueryIdempotencyClaim.replay(
                        "storage", requestFingerprint,
                        context.getSnapshotFingerprint(), completedJson
                );
            }
            return QueryIdempotencyClaim.owner(
                    "storage", "owner", requestFingerprint,
                    context.getSnapshotFingerprint()
            );
        }

        @Override
        public boolean renew(QueryIdempotencyClaim claim) {
            return true;
        }

        @Override
        public void complete(QueryIdempotencyClaim claim, String responseJson) {
            completedJson = responseJson;
        }

        @Override
        public void release(QueryIdempotencyClaim claim) {
        }
    }
}
