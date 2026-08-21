package com.doc.docquery.retrieval;

import java.time.Instant;
import java.util.List;

/** 写入单一 retrieval.jsonl 前的完整、不可变内存清单。 */
public record RetrievalArtifact(
        int schemaVersion,
        long documentVersionId,
        String canonicalSha256,
        String chatModel,
        String chatPromptVersion,
        String embeddingModel,
        int embeddingDimension,
        String embeddingTemplateVersion,
        String generationFingerprint,
        Instant generatedAt,
        DocumentProfile profile,
        List<RetrievalNode> nodes
) {
    public RetrievalArtifact {
        nodes = List.copyOf(nodes);
    }
}
