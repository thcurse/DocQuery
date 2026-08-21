package com.doc.docquery.security;

/** 一次服务查询内固定的不可变 activeVersion 条目。 */
public record ActiveDocumentVersionSnapshot(
        Long documentId,
        Long documentVersionId,
        Integer versionNo,
        String documentName
) {
}
