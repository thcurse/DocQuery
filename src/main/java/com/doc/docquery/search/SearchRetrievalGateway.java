package com.doc.docquery.search;

import com.doc.docquery.security.QueryAccessContext;

import java.util.List;

/** N3.2 Elasticsearch 双路查询端口；实现必须在召回阶段应用可信范围过滤。 */
public interface SearchRetrievalGateway {

    List<KeywordHit> searchKeyword(
            QueryAccessContext context,
            String query,
            int candidates
    );

    List<SemanticHit> searchSemantic(
            QueryAccessContext context,
            float[] queryVector,
            int k,
            int numCandidates
    );

    record KeywordHit(
            int rank,
            long documentId,
            long documentVersionId,
            String blockId,
            String headingNodeId,
            int ordinal,
            long canonicalStart,
            long canonicalEnd,
            List<HighlightFragment> highlights
    ) {
        public KeywordHit {
            highlights = List.copyOf(highlights);
        }
    }

    record SemanticHit(
            int rank,
            long documentId,
            long documentVersionId,
            String cardId,
            String cardType,
            String headingNodeId,
            Integer sectionStartBlockOrdinal,
            Integer sectionEndBlockOrdinalExclusive,
            Long canonicalStart,
            Long canonicalEnd
    ) {
    }

    record HighlightFragment(List<HighlightSegment> segments) {
        public HighlightFragment {
            segments = List.copyOf(segments);
        }
    }

    record HighlightSegment(String text, boolean matched) {
    }
}
