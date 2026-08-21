package com.doc.docquery.retrieval;

/** retrieval JSONL 中可独立校验的 float32 小端向量载荷。 */
public record EmbeddingPayload(
        String encoding,
        int dimension,
        String inputSha256,
        String valueSha256,
        String value
) {
}
