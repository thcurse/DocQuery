package com.doc.docquery.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.QueryAccessContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用官方 Java Client 执行带 activeVersion 前置过滤的 BM25 与 KNN。 */
@Component
@ConditionalOnProperty(prefix = "docquery.search", name = "enabled", havingValue = "true")
public class ElasticsearchSearchRetrievalGateway implements SearchRetrievalGateway {

    private static final String HIGHLIGHT_START = "\uE000";
    private static final String HIGHLIGHT_END = "\uE001";

    private final ElasticsearchClient client;
    private final SearchProjectionProperties properties;

    public ElasticsearchSearchRetrievalGateway(
            ElasticsearchClient client,
            SearchProjectionProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<KeywordHit> searchKeyword(
            QueryAccessContext context,
            String queryText,
            int candidates
    ) {
        try {
            List<Query> filters = scopeFilters(context);
            SearchResponse<Map> response = client.search(search -> search
                            .index(properties.evidenceAlias())
                            .allowPartialSearchResults(false)
                            .size(candidates)
                            .source(source -> source.filter(filter -> filter.includes(
                                    "document_id",
                                    "document_version_id",
                                    "block_id",
                                    "heading_node_id",
                                    "ordinal",
                                    "canonical_start",
                                    "canonical_end"
                            )))
                            .query(query -> query.bool(bool -> bool
                                    .filter(filters)
                                    .must(must -> must.multiMatch(multi -> multi
                                            .query(queryText)
                                            .type(TextQueryType.BestFields)
                                            .operator(Operator.Or)
                                            .fields(
                                                    "text^4.0",
                                                    "text.standard^3.0",
                                                    "heading_path^2.0",
                                                    "heading_path.standard^1.5",
                                                    "document_title^1.2",
                                                    "document_title.standard^1.0"
                                            )
                                    ))
                            ))
                            .highlight(highlight -> highlight
                                    .preTags(HIGHLIGHT_START)
                                    .postTags(HIGHLIGHT_END)
                                    .requireFieldMatch(false)
                                    .fields(List.of(NamedValue.of(
                                            "text",
                                            HighlightField.of(field -> field
                                                    .fragmentSize(160)
                                                    .numberOfFragments(3)
                                            )
                                    )))
                            ),
                    Map.class
            );
            List<KeywordHit> hits = new ArrayList<>();
            int rank = 0;
            for (Hit<Map> hit : response.hits().hits()) {
                rank++;
                Map<String, Object> source = requiredSource(hit);
                hits.add(new KeywordHit(
                        rank,
                        requiredLong(source, "document_id"),
                        requiredLong(source, "document_version_id"),
                        requiredString(source, "block_id"),
                        requiredString(source, "heading_node_id"),
                        requiredInt(source, "ordinal"),
                        requiredLong(source, "canonical_start"),
                        requiredLong(source, "canonical_end"),
                        parseHighlights(hit.highlight().get("text"))
                ));
            }
            return List.copyOf(hits);
        } catch (SearchRetrievalException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public List<SemanticHit> searchSemantic(
            QueryAccessContext context,
            float[] queryVector,
            int k,
            int numCandidates
    ) {
        try {
            List<Float> vector = new ArrayList<>(queryVector.length);
            for (float value : queryVector) {
                vector.add(value);
            }
            SearchResponse<Map> response = client.search(search -> search
                            .index(properties.navigationAlias())
                            .allowPartialSearchResults(false)
                            .size(k)
                            .source(source -> source.filter(filter -> filter.includes(
                                    "document_id",
                                    "document_version_id",
                                    "card_id",
                                    "card_type",
                                    "heading_node_id",
                                    "section_start_block_ordinal",
                                    "section_end_block_ordinal_exclusive",
                                    "canonical_start",
                                    "canonical_end"
                            )))
                            .knn(knn -> knn
                                    .field("embedding")
                                    .queryVector(vector)
                                    .k(k)
                                    .numCandidates(numCandidates)
                                    .filter(scopeFilters(context))
                            ),
                    Map.class
            );
            List<SemanticHit> hits = new ArrayList<>();
            int rank = 0;
            for (Hit<Map> hit : response.hits().hits()) {
                rank++;
                Map<String, Object> source = requiredSource(hit);
                hits.add(new SemanticHit(
                        rank,
                        requiredLong(source, "document_id"),
                        requiredLong(source, "document_version_id"),
                        requiredString(source, "card_id"),
                        requiredString(source, "card_type"),
                        optionalString(source, "heading_node_id"),
                        optionalInt(source, "section_start_block_ordinal"),
                        optionalInt(source, "section_end_block_ordinal_exclusive"),
                        optionalLong(source, "canonical_start"),
                        optionalLong(source, "canonical_end")
                ));
            }
            return List.copyOf(hits);
        } catch (SearchRetrievalException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private List<Query> scopeFilters(QueryAccessContext context) {
        List<FieldValue> versions = context.getActiveVersions().stream()
                .map(ActiveDocumentVersionSnapshot::documentVersionId)
                .map(FieldValue::of)
                .toList();
        if (versions.isEmpty()) {
            throw new SearchRetrievalException("Active version filter is empty", null);
        }
        return List.of(
                Query.of(query -> query.term(term -> term
                        .field("tenant_id")
                        .value(context.getTenantId())
                )),
                Query.of(query -> query.term(term -> term
                        .field("knowledge_base_id")
                        .value(context.getKnowledgeBaseId())
                )),
                Query.of(query -> query.terms(terms -> terms
                        .field("document_version_id")
                        .terms(values -> values.value(versions))
                ))
        );
    }

    private Map<String, Object> requiredSource(Hit<Map> hit) {
        if (hit.source() == null) {
            throw new SearchRetrievalException("Search hit source is missing", null);
        }
        return hit.source();
    }

    private long requiredLong(Map<String, Object> source, String field) {
        Long value = optionalLong(source, field);
        if (value == null) {
            throw invalidHit();
        }
        return value;
    }

    private int requiredInt(Map<String, Object> source, String field) {
        Integer value = optionalInt(source, field);
        if (value == null) {
            throw invalidHit();
        }
        return value;
    }

    private String requiredString(Map<String, Object> source, String field) {
        String value = optionalString(source, field);
        if (value == null || value.isBlank()) {
            throw invalidHit();
        }
        return value;
    }

    private Long optionalLong(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer optionalInt(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value instanceof Number number ? number.intValue() : null;
    }

    private String optionalString(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value instanceof String text ? text : null;
    }

    private List<HighlightFragment> parseHighlights(List<String> rawFragments) {
        if (rawFragments == null || rawFragments.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>(rawFragments);
        List<HighlightFragment> result = new ArrayList<>(unique.size());
        for (String raw : unique) {
            List<HighlightSegment> segments = new ArrayList<>();
            int cursor = 0;
            boolean matched = false;
            while (cursor < raw.length()) {
                String marker = matched ? HIGHLIGHT_END : HIGHLIGHT_START;
                int markerIndex = raw.indexOf(marker, cursor);
                int end = markerIndex < 0 ? raw.length() : markerIndex;
                if (end > cursor) {
                    segments.add(new HighlightSegment(raw.substring(cursor, end), matched));
                }
                if (markerIndex < 0) {
                    break;
                }
                matched = !matched;
                cursor = markerIndex + marker.length();
            }
            if (matched) {
                throw invalidHit();
            }
            if (!segments.isEmpty()) {
                result.add(new HighlightFragment(segments));
            }
        }
        return List.copyOf(result);
    }

    private SearchRetrievalException invalidHit() {
        return new SearchRetrievalException("Search hit does not match the query contract", null);
    }

    private SearchRetrievalException unavailable(Throwable cause) {
        return new SearchRetrievalException("Elasticsearch query is unavailable", cause);
    }
}
