package com.doc.docquery.job;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.mapper.DocumentRetrievalArtifactMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.impl.S3CanonicalArtifactStore;
import com.doc.docquery.service.impl.S3RetrievalArtifactStore;
import com.doc.docquery.service.impl.S3SourceObjectStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 三类回收器经真实适配器验证分页、删除和保守重试的共同契约。 */
class OrphanObjectReaperTest {

    enum Kind { SOURCE, CANONICAL, RETRIEVAL }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void referencedFirstPageDoesNotStarveLaterOrphans(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.addOld("a", "b", "c");
        fixture.referenced.addAll(List.of("scan/a", "scan/b"));

        fixture.run.run();
        assertThat(fixture.objects).hasSize(3);
        assertThat(fixture.checked).containsExactly("scan/a", "scan/b");

        fixture.run.run();
        assertThat(fixture.objects.keySet()).containsExactly("scan/a", "scan/b");
        assertThat(fixture.requestTokens).containsExactly(null, token("scan/b"));
        assertThat(fixture.checked).containsExactly("scan/a", "scan/b", "scan/c");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void deletingEarlierPagesDoesNotSkipObjectsAndCompletedScanRestarts(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.addOld("a", "b", "c", "d", "e");

        fixture.run.run();
        assertThat(fixture.objects.keySet()).containsExactly("scan/c", "scan/d", "scan/e");
        fixture.run.run();
        assertThat(fixture.objects.keySet()).containsExactly("scan/e");
        fixture.run.run();
        assertThat(fixture.objects).isEmpty();

        fixture.addOld("a-new");
        fixture.run.run();
        assertThat(fixture.objects).isEmpty();
        assertThat(fixture.requestTokens).containsExactly(
                null, token("scan/b"), token("scan/d"), null
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void uncertainReferencesAndFailedDeletesAreRetriedOnTheNextFullScan(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.addOld("a", "b", "c", "d");
        fixture.referenceFailures.add("scan/a");
        fixture.deleteFailures.add("scan/b");

        fixture.run.run();
        assertThat(fixture.objects).hasSize(4);
        fixture.run.run();
        assertThat(fixture.objects.keySet()).containsExactly("scan/a", "scan/b");

        fixture.referenceFailures.clear();
        fixture.deleteFailures.clear();
        fixture.run.run();
        assertThat(fixture.objects).isEmpty();
        assertThat(fixture.checked).containsExactly(
                "scan/a", "scan/b", "scan/c", "scan/d", "scan/a", "scan/b"
        );
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void youngAndUnknownAgeObjectsAreRetainedWhileScanAdvances(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.objects.put("scan/a", Instant.now());
        fixture.objects.put("scan/b", null);
        fixture.addOld("c");

        fixture.run.run();
        fixture.run.run();

        assertThat(fixture.objects.keySet()).containsExactly("scan/a", "scan/b");
        assertThat(fixture.checked).containsExactly("scan/c");
        assertThat(fixture.requestTokens).containsExactly(null, token("scan/b"));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void listingFailureKeepsTheCursorForRetry(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.addOld("a", "b", "c");
        fixture.run.run();

        fixture.failNextList = true;
        assertThatThrownBy(fixture.run::run).isInstanceOf(ObjectStorageException.class);
        assertThat(fixture.objects.keySet()).containsExactly("scan/c");
        fixture.run.run();

        assertThat(fixture.objects).isEmpty();
        assertThat(fixture.requestTokens).containsExactly(null, token("scan/b"), token("scan/b"));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void emptyTruncatedPageStillAdvancesAndEndOfScanClearsTheCursor(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.addOld("a");
        doReturn(ListObjectsV2Response.builder()
                        .isTruncated(true).nextContinuationToken("opaque-empty-page").build())
                .doAnswer(invocation -> {
                    ListObjectsV2Request request = invocation.getArgument(0);
                    assertThat(request.continuationToken()).isEqualTo("opaque-empty-page");
                    return ListObjectsV2Response.builder().isTruncated(false)
                            .contents(S3Object.builder().key("scan/a")
                                    .lastModified(Instant.now().minus(Duration.ofDays(2))).build())
                            .build();
                })
                .doAnswer(invocation -> {
                    ListObjectsV2Request request = invocation.getArgument(0);
                    assertThat(request.continuationToken()).isNull();
                    return ListObjectsV2Response.builder().isTruncated(false).build();
                }).when(fixture.s3).listObjectsV2(any(ListObjectsV2Request.class));

        fixture.run.run();
        assertThat(fixture.objects).hasSize(1);
        fixture.run.run();
        assertThat(fixture.objects).isEmpty();
        fixture.run.run();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void malformedTruncatedPageFailsClosed(Kind kind) {
        Fixture fixture = new Fixture(kind);
        fixture.addOld("a");
        doReturn(ListObjectsV2Response.builder().isTruncated(true)
                        .contents(S3Object.builder().key("scan/a")
                                .lastModified(Instant.now().minus(Duration.ofDays(2))).build())
                        .build()).when(fixture.s3).listObjectsV2(any(ListObjectsV2Request.class));

        assertThatThrownBy(fixture.run::run).isInstanceOf(ObjectStorageException.class);
        assertThat(fixture.objects).hasSize(1);
        assertThat(fixture.checked).isEmpty();
    }

    private static String token(String key) {
        return Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Fixture {
        private final S3Client s3 = mock(S3Client.class);
        private final TreeMap<String, Instant> objects = new TreeMap<>();
        private final Set<String> referenced = new HashSet<>();
        private final Set<String> referenceFailures = new HashSet<>();
        private final Set<String> deleteFailures = new HashSet<>();
        private final List<String> checked = new ArrayList<>();
        private final List<String> requestTokens = new ArrayList<>();
        private final Runnable run;
        private boolean failNextList;

        private Fixture(Kind kind) {
            when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenAnswer(invocation -> list(invocation.getArgument(0)));
            when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
                DeleteObjectRequest request = invocation.getArgument(0);
                if (deleteFailures.contains(request.key())) {
                    throw S3Exception.builder().statusCode(503).message("unavailable").build();
                }
                objects.remove(request.key());
                return DeleteObjectResponse.builder().build();
            });
            ObjectStorageProperties storage = new ObjectStorageProperties();
            storage.setBucket("bucket");
            storage.setOrphanPrefix("scan/");
            storage.setOrphanBatchSize(2);
            storage.setOrphanGrace(Duration.ofHours(1));
            run = switch (kind) {
                case SOURCE -> {
                    DocumentVersionMapper mapper = mock(DocumentVersionMapper.class);
                    when(mapper.countBySourceObject(eq("bucket"), anyString()))
                            .thenAnswer(invocation -> countReferences(invocation.getArgument(1)));
                    yield new OrphanSourceObjectReaper(
                            new S3SourceObjectStore(s3, storage), mapper, storage
                    )::runOnce;
                }
                case CANONICAL -> {
                    DocumentCanonicalArtifactMapper mapper = mock(DocumentCanonicalArtifactMapper.class);
                    when(mapper.countByCanonicalObject(eq("bucket"), anyString()))
                            .thenAnswer(invocation -> countReferences(invocation.getArgument(1)));
                    DocumentParsingProperties parsing = new DocumentParsingProperties();
                    parsing.setCanonicalPrefix("scan/");
                    parsing.setOrphanBatchSize(2);
                    parsing.setOrphanGrace(Duration.ofHours(1));
                    yield new OrphanCanonicalArtifactReaper(
                            new S3CanonicalArtifactStore(s3, storage), mapper, parsing
                    )::runOnce;
                }
                case RETRIEVAL -> {
                    DocumentRetrievalArtifactMapper mapper = mock(DocumentRetrievalArtifactMapper.class);
                    when(mapper.countByRetrievalObject(eq("bucket"), anyString()))
                            .thenAnswer(invocation -> countReferences(invocation.getArgument(1)));
                    DocumentRetrievalProperties retrieval = new DocumentRetrievalProperties();
                    retrieval.setRetrievalPrefix("scan/");
                    retrieval.setOrphanBatchSize(2);
                    retrieval.setOrphanGrace(Duration.ofHours(1));
                    yield new OrphanRetrievalArtifactReaper(
                            new S3RetrievalArtifactStore(s3, storage), mapper, retrieval
                    )::runOnce;
                }
            };
        }

        private void addOld(String... suffixes) {
            for (String suffix : suffixes) {
                objects.put("scan/" + suffix, Instant.now().minus(Duration.ofDays(2)));
            }
        }

        private long countReferences(String key) {
            checked.add(key);
            if (referenceFailures.contains(key)) {
                throw new IllegalStateException("database unavailable");
            }
            return referenced.contains(key) ? 1 : 0;
        }

        private ListObjectsV2Response list(ListObjectsV2Request request) {
            assertThat(request.bucket()).isEqualTo("bucket");
            assertThat(request.prefix()).isEqualTo("scan/");
            assertThat(request.maxKeys()).isEqualTo(2);
            requestTokens.add(request.continuationToken());
            if (failNextList) {
                failNextList = false;
                throw S3Exception.builder().statusCode(503).message("unavailable").build();
            }
            String lastKey = request.continuationToken() == null ? ""
                    : new String(Base64.getDecoder().decode(request.continuationToken()), StandardCharsets.UTF_8);
            List<String> remaining = objects.tailMap(lastKey, false).keySet().stream().toList();
            List<String> keys = remaining.stream().limit(request.maxKeys()).toList();
            boolean truncated = remaining.size() > keys.size();
            return ListObjectsV2Response.builder()
                    .contents(keys.stream().map(key -> S3Object.builder().key(key)
                            .lastModified(objects.get(key)).build()).toList())
                    .isTruncated(truncated)
                    .nextContinuationToken(truncated ? token(keys.get(keys.size() - 1)) : null)
                    .build();
        }
    }
}
