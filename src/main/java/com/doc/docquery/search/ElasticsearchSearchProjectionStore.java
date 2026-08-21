package com.doc.docquery.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import com.doc.docquery.config.SearchProjectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 使用官方 Java Client 实现双索引创建、覆盖写、清理和计数验收。 */
@Component
@ConditionalOnProperty(prefix = "docquery.search", name = "enabled", havingValue = "true")
public class ElasticsearchSearchProjectionStore implements SearchProjectionStore {

    private final ElasticsearchClient client;
    private final SearchProjectionProperties properties;
    private final ObjectMapper objectMapper;

    public ElasticsearchSearchProjectionStore(
            ElasticsearchClient client,
            SearchProjectionProperties properties,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchIndexDescriptor ensureIndices() {
        try {
            ensureIndex(
                    properties.evidencePhysicalIndex(),
                    SearchIndexMappings.evidence(properties)
            );
            ensureIndex(
                    properties.navigationPhysicalIndex(),
                    SearchIndexMappings.navigation(properties)
            );
            IndexState evidence = indexState(properties.evidencePhysicalIndex());
            IndexState navigation = indexState(properties.navigationPhysicalIndex());
            validateEvidence(evidence);
            validateNavigation(navigation);
            validateAlias(properties.evidenceAlias(), properties.evidencePhysicalIndex());
            validateAlias(
                    properties.navigationAlias(),
                    properties.navigationPhysicalIndex()
            );
            return new SearchIndexDescriptor(
                    client.info().clusterUuid(),
                    indexRef(properties.evidencePhysicalIndex(), evidence,
                            properties.getEvidenceMappingVersion()),
                    indexRef(properties.navigationPhysicalIndex(), navigation,
                            properties.getNavigationMappingVersion())
            );
        } catch (SearchProjectionException exception) {
            throw exception;
        } catch (ElasticsearchException exception) {
            throw transportFailure(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void deleteScope(SearchProjectionScope scope) {
        try {
            deleteScope(properties.evidenceAlias(), scope, true);
            deleteScope(properties.navigationAlias(), scope, true);
        } catch (ElasticsearchException exception) {
            throw transportFailure(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void deleteVersion(SearchProjectionScope scope) {
        try {
            deleteScope(properties.evidenceAlias(), scope, false);
            deleteScope(properties.navigationAlias(), scope, false);
        } catch (ElasticsearchException exception) {
            throw transportFailure(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void indexEvidence(List<SearchProjectionDocument> documents) {
        bulk(properties.evidenceAlias(), documents);
    }

    @Override
    public void indexNavigation(List<SearchProjectionDocument> documents) {
        bulk(properties.navigationAlias(), documents);
    }

    @Override
    public void refresh() {
        try {
            client.indices().refresh(refresh -> refresh.index(
                    properties.evidenceAlias(),
                    properties.navigationAlias()
            ));
        } catch (ElasticsearchException exception) {
            throw transportFailure(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public long countEvidence(SearchProjectionScope scope) {
        return count(properties.evidenceAlias(), scope, true);
    }

    @Override
    public long countNavigation(SearchProjectionScope scope) {
        return count(properties.navigationAlias(), scope, true);
    }

    @Override
    public long countEvidenceVersion(SearchProjectionScope scope) {
        return count(properties.evidenceAlias(), scope, false);
    }

    @Override
    public long countNavigationVersion(SearchProjectionScope scope) {
        return count(properties.navigationAlias(), scope, false);
    }

    private void ensureIndex(String name, String definition) throws IOException {
        if (client.indices().exists(exists -> exists.index(name)).value()) {
            return;
        }
        try {
            client.indices().create(create -> create
                    .index(name)
                    .withJson(new StringReader(definition))
            );
        } catch (ElasticsearchException exception) {
            // 两个实例并发首次启动时，另一方可能已经完成创建；只在确实存在时吞掉竞争异常。
            if (!client.indices().exists(exists -> exists.index(name)).value()) {
                throw exception;
            }
        }
    }

    private IndexState indexState(String name) throws IOException {
        IndexState state = client.indices().get(get -> get.index(name)).get(name);
        if (state == null) {
            throw new SearchProjectionException(
                    "SEARCH_INDEX_SCHEMA_MISMATCH",
                    "Search index state is missing",
                    false
            );
        }
        return state;
    }

    private SearchIndexDescriptor.IndexRef indexRef(
            String name,
            IndexState state,
            String mappingVersion
    ) {
        String uuid = state.settings() == null || state.settings().index() == null
                ? null
                : state.settings().index().uuid();
        if (uuid == null || uuid.isBlank()) {
            throw schemaMismatch("Search index UUID is missing");
        }
        return new SearchIndexDescriptor.IndexRef(name, uuid, mappingVersion);
    }

    private void validateEvidence(IndexState state) {
        validateBase(
                state,
                properties.evidenceAlias(),
                properties.getEvidenceMappingVersion()
        );
        requireText(state.mappings(), "text", "cjk");
        requireText(state.mappings(), "heading_path", "cjk");
        requireKind(state.mappings(), "block_id", Property.Kind.Keyword);
        requireKind(state.mappings(), "document_version_id", Property.Kind.Long);
    }

    private void validateNavigation(IndexState state) {
        validateBase(
                state,
                properties.navigationAlias(),
                properties.getNavigationMappingVersion()
        );
        requireText(state.mappings(), "title_path", "cjk");
        requireText(state.mappings(), "summary", "cjk");
        requireKind(state.mappings(), "card_id", Property.Kind.Keyword);
        Property embedding = requiredProperty(state.mappings(), "embedding");
        if (!embedding.isDenseVector()
                || !Integer.valueOf(properties.getEmbeddingDimension()).equals(
                embedding.denseVector().dims())
                || !Boolean.TRUE.equals(embedding.denseVector().index())
                || embedding.denseVector().similarity() != DenseVectorSimilarity.Cosine) {
            throw schemaMismatch("Navigation vector Mapping does not match");
        }
    }

    private void validateBase(IndexState state, String alias, String mappingVersion) {
        TypeMapping mapping = state.mappings();
        String actualVersion = mapping == null
                || mapping.meta().get("docquery_mapping_version") == null
                ? null
                : mapping.meta().get("docquery_mapping_version").to(String.class);
        if (mapping == null
                || !state.aliases().containsKey(alias)
                || !mappingVersion.equals(actualVersion)
                || mapping.dynamic() == null
                || !"strict".equals(mapping.dynamic().jsonValue())) {
            throw schemaMismatch("Search index Mapping identity does not match");
        }
    }

    private void validateAlias(String alias, String expectedPhysicalIndex)
            throws IOException {
        var targets = client.indices().getAlias(get -> get.name(alias)).aliases().keySet();
        if (targets.size() != 1 || !targets.contains(expectedPhysicalIndex)) {
            throw schemaMismatch("Search alias does not target exactly one physical index");
        }
    }

    private void requireText(TypeMapping mapping, String field, String analyzer) {
        Property property = requiredProperty(mapping, field);
        if (!property.isText() || !analyzer.equals(property.text().analyzer())) {
            throw schemaMismatch("Search text Mapping does not match");
        }
        Property standard = property.text().fields().get("standard");
        if (standard == null || !standard.isText()
                || !"standard".equals(standard.text().analyzer())) {
            throw schemaMismatch("Search standard multi-field is missing");
        }
    }

    private void requireKind(TypeMapping mapping, String field, Property.Kind kind) {
        if (requiredProperty(mapping, field)._kind() != kind) {
            throw schemaMismatch("Search field Mapping does not match");
        }
    }

    private Property requiredProperty(TypeMapping mapping, String field) {
        Property property = mapping == null ? null : mapping.properties().get(field);
        if (property == null) {
            throw schemaMismatch("Required search field is missing");
        }
        return property;
    }

    private void deleteScope(
            String index,
            SearchProjectionScope scope,
            boolean fingerprint
    ) throws IOException {
        client.deleteByQuery(delete -> delete
                .index(index)
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .query(query -> scopeQuery(query, scope, fingerprint))
        );
    }

    private long count(String index, SearchProjectionScope scope, boolean fingerprint) {
        try {
            return client.count(count -> count
                    .index(index)
                    .query(query -> scopeQuery(query, scope, fingerprint))
            ).count();
        } catch (ElasticsearchException exception) {
            throw transportFailure(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>
    scopeQuery(
            co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder query,
            SearchProjectionScope scope
    ) {
        return scopeQuery(query, scope, true);
    }

    private co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>
    scopeQuery(
            co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder query,
            SearchProjectionScope scope,
            boolean fingerprint
    ) {
        var bool = new co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder()
                .filter(filter -> filter.term(term -> term.field("tenant_id").value(scope.tenantId())))
                .filter(filter -> filter.term(term -> term.field("knowledge_base_id").value(scope.knowledgeBaseId())))
                .filter(filter -> filter.term(term -> term.field("document_id").value(scope.documentId())))
                .filter(filter -> filter.term(term -> term.field("document_version_id").value(scope.documentVersionId())));
        if (fingerprint) {
            bool.filter(filter -> filter.term(term -> term
                    .field("projection_fingerprint")
                    .value(scope.projectionFingerprint())));
        }
        return query.bool(bool.build());
    }

    private void bulk(String index, List<SearchProjectionDocument> documents) {
        List<SearchProjectionDocument> batch = new ArrayList<>();
        long batchBytes = 0;
        for (SearchProjectionDocument document : documents) {
            long documentBytes = serializedSize(document.source());
            if (!batch.isEmpty() && (batch.size() >= properties.getBulkMaxActions()
                    || batchBytes + documentBytes > properties.getBulkMaxBytes())) {
                sendBatch(index, batch);
                batch.clear();
                batchBytes = 0;
            }
            batch.add(document);
            batchBytes += documentBytes;
        }
        if (!batch.isEmpty()) {
            sendBatch(index, batch);
        }
    }

    private void sendBatch(String index, List<SearchProjectionDocument> documents) {
        try {
            BulkResponse response = client.bulk(bulk -> {
                bulk.index(index);
                for (SearchProjectionDocument document : documents) {
                    bulk.operations(operation -> operation.index(item -> item
                            .id(document.id())
                            .document(document.source())
                    ));
                }
                return bulk;
            });
            if (response.errors()) {
                boolean retryable = response.items().stream()
                        .anyMatch(item -> item.status() == 429 || item.status() >= 500);
                throw new SearchProjectionException(
                        "SEARCH_BULK_REJECTED",
                        "Elasticsearch rejected one or more projection documents",
                        retryable
                );
            }
        } catch (SearchProjectionException exception) {
            throw exception;
        } catch (ElasticsearchException exception) {
            throw transportFailure(exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private long serializedSize(Map<String, Object> source) {
        try {
            return objectMapper.writeValueAsBytes(source).length;
        } catch (RuntimeException exception) {
            throw new SearchProjectionException(
                    "SEARCH_PROJECTION_VALIDATION_FAILED",
                    "Search projection document cannot be serialized",
                    false,
                    exception
            );
        }
    }

    private SearchProjectionException transportFailure(ElasticsearchException exception) {
        if (exception.status() == 401 || exception.status() == 403) {
            return new SearchProjectionException(
                    "SEARCH_AUTH_FAILED",
                    "Elasticsearch authentication failed",
                    false,
                    exception
            );
        }
        return new SearchProjectionException(
                "SEARCH_INDEX_UNAVAILABLE",
                "Elasticsearch request failed",
                exception.status() == 429 || exception.status() >= 500,
                exception
        );
    }

    private SearchProjectionException unavailable(Exception exception) {
        return new SearchProjectionException(
                "SEARCH_INDEX_UNAVAILABLE",
                "Elasticsearch is unavailable",
                true,
                exception
        );
    }

    private SearchProjectionException schemaMismatch(String message) {
        return new SearchProjectionException(
                "SEARCH_INDEX_SCHEMA_MISMATCH",
                message,
                false
        );
    }
}
