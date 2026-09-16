package com.doc.docquery.service;

import com.doc.docquery.retrieval.DocumentProfileSemantic;
import com.doc.docquery.retrieval.RetrievalNodeSemantic;

import java.util.List;

/** 隔离 DeepSeek/LangChain4j 的语义卡片生成端口，单测可零成本替换。 */
public interface RetrievalCardChatGateway {

    List<GeneratedNode> generateNodes(List<NodeInput> inputs, String correctionHint);

    DocumentProfileSemantic generateProfile(ProfileInput input, String correctionHint);

    default List<BoundaryCandidate> generateBoundaryCandidates(
            BoundaryInput input,
            String correctionHint
    ) {
        throw new UnsupportedOperationException("Boundary generation is not implemented");
    }

    record NodeInput(String requestId, String titlePath, String sourceText) {
    }

    record GeneratedNode(String requestId, RetrievalNodeSemantic semantic) {
    }

    record ProfileInput(String requestId, String documentTitle, String sourceText) {
    }

    record BoundaryInput(
            String requestId,
            String documentName,
            String titlePath,
            int sectionStartBlockOrdinal,
            int sectionEndBlockOrdinalExclusive,
            int estimatedSectionTokens,
            int minimumPartitions,
            int maximumPartitions,
            int minimumCandidates,
            int maximumCandidates,
            List<BoundaryBlock> blocks
    ) {
        public BoundaryInput {
            blocks = List.copyOf(blocks);
        }
    }

    record BoundaryBlock(int ordinal, Integer page, String kind, String text) {
    }

    record BoundaryCandidate(
            String title,
            int startBlockOrdinal,
            int boundaryStrength,
            List<String> topics
    ) {
        public BoundaryCandidate {
            topics = topics == null ? null : List.copyOf(topics);
        }
    }
}
