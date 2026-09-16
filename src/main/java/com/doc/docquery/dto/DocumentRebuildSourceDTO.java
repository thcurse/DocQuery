package com.doc.docquery.dto;

/** 同文件受控重建在对象复制阶段需要的可信原文件快照。 */
public record DocumentRebuildSourceDTO(
        long sourceVersionId,
        String originalFilename,
        String sourceFormat,
        String sourceBucket,
        String sourceObjectKey,
        long sourceSizeBytes,
        String sourceSha256,
        String sourceContentType
) {
}
