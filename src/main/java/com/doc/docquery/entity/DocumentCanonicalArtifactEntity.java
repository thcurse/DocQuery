package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 已完整写入并通过校验的 canonical JSONL 对象清单。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentCanonicalArtifactEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 每个不可变文档版本第一版只有一个有效清单。 */
    private Long documentVersionId;
    /** canonical JSONL Schema 版本。 */
    private Integer schemaVersion;
    /** PDF、DOCX、TXT 或 Markdown 的稳定代码。 */
    private String sourceFormat;
    /** DocQuery 标准化解析器名称。 */
    private String parserName;
    /** DocQuery 自有解析规则版本。 */
    private String parserVersion;
    /** 派生对象所在私有 Bucket。 */
    private String canonicalBucket;
    /** 派生对象不可变 Key。 */
    private String canonicalObjectKey;
    /** 整个 JSONL 对象字节数。 */
    private Long canonicalSizeBytes;
    /** 整个 JSONL 对象 SHA-256。 */
    private String canonicalSha256;
    /** 标准化全文 SHA-256。 */
    private String canonicalTextSha256;
    /** 标准化全文 UTF-16 code unit 数量。 */
    private Long textLength;
    /** 正文证据块数量。 */
    private Integer blockCount;
    /** 包含根的真实标题节点数量。 */
    private Integer headingCount;
    /** 非致命解析警告数量。 */
    private Integer warningCount;
    /** PDF 物理页数，其他格式为空。 */
    private Integer pageCount;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
}
