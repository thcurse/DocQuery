package com.doc.docquery.search;

import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 不依赖 Docker 验证 N2.5 Mapping 的关键冻结项和配置防注入边界。 */
class SearchIndexMappingsTest {

    @Test
    void freezesStrictCjkEvidenceAndCosine2560NavigationMapping() {
        SearchProjectionProperties properties = new SearchProjectionProperties();

        String evidence = SearchIndexMappings.evidence(properties);
        String navigation = SearchIndexMappings.navigation(properties);

        assertThat(evidence)
                .contains("\"dynamic\":\"strict\"")
                .contains("\"docquery_mapping_version\":\"evidence-v1\"")
                .contains("\"canonical_artifact_id\":{\"type\":\"long\"}")
                .contains("\"text\":{\"type\":\"text\",\"analyzer\":\"cjk\"")
                .contains("\"standard\":{\"type\":\"text\",\"analyzer\":\"standard\"}");
        assertThat(navigation)
                .contains("\"docquery_mapping_version\":\"navigation-v1\"")
                .contains("\"retrieval_artifact_id\":{\"type\":\"long\"}")
                .contains("\"embedding_input_sha256\":{\"type\":\"keyword\"}")
                .contains("\"embedding\":{\"type\":\"dense_vector\",\"dims\":2560")
                .contains("\"similarity\":\"cosine\"");
    }

    @Test
    void rejectsUnsafeIndexNameBeforeItCanEnterJson() {
        SearchProjectionProperties properties = new SearchProjectionProperties();
        properties.setIndexPrefix("docquery\"}");

        assertThatThrownBy(() -> SearchIndexMappings.evidence(properties))
                .isInstanceOf(SearchProjectionException.class)
                .extracting("code")
                .isEqualTo("SEARCH_INDEX_SCHEMA_MISMATCH");
    }

    @Test
    void projectionFingerprintChangesWithArtifactOrMappingIdentity() {
        SearchProjectionProperties properties = new SearchProjectionProperties();
        SearchProjectionFingerprint fingerprint = new SearchProjectionFingerprint();
        DocumentCanonicalArtifactEntity canonical = canonical(10L, "a".repeat(64));
        DocumentRetrievalArtifactEntity retrieval = retrieval(20L, "b".repeat(64));
        String first = fingerprint.calculate(30L, canonical, retrieval, properties);

        properties.setNavigationMappingVersion("navigation-v2");
        String mappingChanged = fingerprint.calculate(30L, canonical, retrieval, properties);
        properties.setNavigationMappingVersion("navigation-v1");
        canonical.setCanonicalSha256("c".repeat(64));
        String artifactChanged = fingerprint.calculate(30L, canonical, retrieval, properties);
        canonical.setCanonicalSha256("a".repeat(64));
        properties.setIndexPrefix("docquery-test");
        String prefixChanged = fingerprint.calculate(30L, canonical, retrieval, properties);

        assertThat(first).hasSize(64);
        assertThat(mappingChanged).isNotEqualTo(first);
        assertThat(artifactChanged).isNotEqualTo(first);
        assertThat(prefixChanged).isNotEqualTo(first);
    }

    /** 构造投影指纹所需的最小 canonical 清单，避免单元测试依赖数据库。 */
    private DocumentCanonicalArtifactEntity canonical(long id, String sha256) {
        DocumentCanonicalArtifactEntity entity = new DocumentCanonicalArtifactEntity();
        entity.setId(id);
        entity.setCanonicalSha256(sha256);
        return entity;
    }

    /** 构造能完整表达 Embedding 血缘的最小 retrieval 清单。 */
    private DocumentRetrievalArtifactEntity retrieval(long id, String sha256) {
        DocumentRetrievalArtifactEntity entity = new DocumentRetrievalArtifactEntity();
        entity.setId(id);
        entity.setRetrievalSha256(sha256);
        entity.setEmbeddingProvider("ALIBABA_MODEL_STUDIO");
        entity.setEmbeddingModel("text-embedding-v4");
        entity.setEmbeddingDimension(2560);
        entity.setEmbeddingTemplateVersion("navigation-text-v1");
        return entity;
    }
}
