package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一个文档版本的 Elasticsearch Evidence/Navigation 双投影验收单。
 *
 * <p>该实体只保存可重建投影的血缘和校验结果，不保存原文、检索卡或向量。
 * 文档是否对查询可见仍由 {@code document.active_version_id} 决定。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSearchProjectionEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 可信 KnowledgeBase 范围。 */
    private Long knowledgeBaseId;
    /** 所属逻辑文档。 */
    private Long documentId;
    /** 一份不可变文档版本最多一条验收单。 */
    private Long documentVersionId;
    /** Evidence 投影来源 canonical 清单。 */
    private Long canonicalArtifactId;
    /** canonical JSONL 对象摘要。 */
    private String canonicalArtifactSha256;
    /** Navigation 投影来源 retrieval 清单。 */
    private Long retrievalArtifactId;
    /** retrieval JSONL 对象摘要。 */
    private String retrievalArtifactSha256;
    /** 完成校验时的 Elasticsearch Cluster UUID。 */
    private String clusterUuid;
    /** Evidence 物理索引名。 */
    private String evidenceIndexName;
    /** Evidence 物理索引 UUID。 */
    private String evidenceIndexUuid;
    /** Evidence Mapping 契约版本。 */
    private String evidenceMappingVersion;
    /** canonical 声明的 EvidenceBlock 数量。 */
    private Integer evidenceExpectedCount;
    /** 最终校验得到的 ES Evidence 数量。 */
    private Integer evidenceActualCount;
    /** Navigation 物理索引名。 */
    private String navigationIndexName;
    /** Navigation 物理索引 UUID。 */
    private String navigationIndexUuid;
    /** Navigation Mapping 契约版本。 */
    private String navigationMappingVersion;
    /** retrieval 声明的 Profile 加 Node 数量。 */
    private Integer navigationExpectedCount;
    /** 最终校验得到的 ES Navigation 数量。 */
    private Integer navigationActualCount;
    /** 两个 artifact 和两份 Mapping 的稳定 SHA-256 指纹。 */
    private String projectionFingerprint;
    /** 两个投影完成并通过校验的 UTC 时间。 */
    private LocalDateTime completedAt;
    /** 首次创建时间。 */
    private LocalDateTime createdAt;
    /** 最近一次受控重建时间。 */
    private LocalDateTime updatedAt;
}
