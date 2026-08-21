package com.doc.docquery.parser;

import com.doc.docquery.enums.DocumentSourceFormat;

import java.nio.file.Path;

/** 从本地受控原文件副本生成标准块的格式专属 Parser。 */
public interface DocumentFormatParser {

    /** 当前 Adapter 是否负责该显式格式。 */
    boolean supports(DocumentSourceFormat format);

    /** 解析原文件，不访问数据库、对象存储、模型或 Elasticsearch。 */
    ParsedDocument parse(ParseSource source);

    /** 已从 MySQL 和对象存储交叉校验的只读解析输入。 */
    record ParseSource(
            Path path,
            long documentVersionId,
            String documentName,
            DocumentSourceFormat format,
            String sourceSha256
    ) {
    }
}
