package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 已完整上传、读回校验并由唯一约束保护的 retrieval JSONL 清单。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRetrievalArtifactEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 一份不可变文档版本唯一对应一条清单。 */
    private Long documentVersionId;
    /** 生成输入对应的 canonical 清单主键。 */
    private Long canonicalArtifactId;
    /** retrieval JSONL Schema 版本。 */
    private Integer schemaVersion;
    /** Chat 供应商稳定代码。 */
    private String chatProvider;
    /** Chat 模型标识。 */
    private String chatModel;
    /** 检索卡 Prompt 版本。 */
    private String chatPromptVersion;
    /** 思考模式，第一版固定为 DISABLED。 */
    private String thinkingMode;
    /** Embedding 供应商稳定代码。 */
    private String embeddingProvider;
    /** Embedding 模型标识。 */
    private String embeddingModel;
    /** 所有导航向量的固定维度。 */
    private Integer embeddingDimension;
    /** 稳定导航文本模板版本。 */
    private String embeddingTemplateVersion;
    /** canonical 血缘和生成配置的 SHA-256 指纹。 */
    private String generationFingerprint;
    /** 派生对象所在私有 Bucket。 */
    private String retrievalBucket;
    /** 派生对象不可变 Key。 */
    private String retrievalObjectKey;
    /** 整个 retrieval JSONL 的实际字节数。 */
    private Long retrievalSizeBytes;
    /** 整个 retrieval JSONL 的 SHA-256。 */
    private String retrievalSha256;
    /** 按稳定顺序计算的语义字段摘要。 */
    private String semanticSha256;
    /** 按稳定顺序连接的 float32 原始字节摘要。 */
    private String vectorSha256;
    /** 文档级检索卡数量，第一版固定为 1。 */
    private Integer profileCount;
    /** 非根真实标题检索卡数量。 */
    private Integer nodeCount;
    /** 向量总数，等于 Profile 与 Node 数量之和。 */
    private Integer vectorCount;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间，第一版等于创建时间。 */
    private LocalDateTime updatedAt;
}
