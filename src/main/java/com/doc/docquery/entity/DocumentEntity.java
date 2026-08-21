package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 逻辑文档持久化对象。
 *
 * <p>{@code activeVersionId} 是当前可检索版本，{@code latestVersionId}
 * 是最新受理版本；两者分离可保证新版本处理失败时旧版本继续服务。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 所属知识库 ID。 */
    private Long knowledgeBaseId;
    /** 知识库内大小写敏感唯一的逻辑文档名称。 */
    private String name;
    /** 未删除文档占用的名称；DELETED 后为空以允许名称复用。 */
    private String activeName;
    /** 逻辑文档生命周期状态码。 */
    private String status;
    /** 当前参与检索的 READY 版本；首次处理完成前为空。 */
    private Long activeVersionId;
    /** 最新受理的版本，不代表已经可检索。 */
    private Long latestVersionId;
    /** UTC 删除受理时间。 */
    private LocalDateTime deletionRequestedAt;
    /** UTC 全部内容完成物理清理的时间。 */
    private LocalDateTime deletedAt;
    /** 首次创建文档的租户管理员 ID。 */
    private Long createdByAdminId;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;
}
