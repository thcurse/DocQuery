package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次不可变原文件受理形成的文档版本事实。
 *
 * <p>版本创建后以 PROCESSING 开始，后续阶段只能将其推进到 READY 或 FAILED；
 * 原文件引用、内容摘要和请求指纹用于可靠重放与追溯。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersionEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 所属逻辑文档 ID。 */
    private Long documentId;
    /** 文档内从 1 开始递增的版本号。 */
    private Integer versionNo;
    /** PROCESSING、READY 或 FAILED 状态码。 */
    private String status;
    /** PDF、DOCX、TXT 或 Markdown 格式码。 */
    private String sourceFormat;
    /** 上传时的安全化原始文件名，不包含目录。 */
    private String originalFilename;
    /** 原文件所在对象存储 Bucket。 */
    private String sourceBucket;
    /** 原文件不可变对象键。 */
    private String sourceObjectKey;
    /** 原文件字节数。 */
    private Long sourceSizeBytes;
    /** 原文件内容 SHA-256，小写十六进制。 */
    private String sourceSha256;
    /** 可选的原文件 MIME 类型。 */
    private String sourceContentType;
    /** 原始幂等键的 SHA-256；原始 Key 不入库。 */
    private String idempotencyKeyHash;
    /** 规范化受理参数的稳定指纹，用于识别同 Key 不同请求。 */
    private String requestFingerprint;
    /** 受理上传的租户管理员 ID。 */
    private Long acceptedByAdminId;
    /** 处理失败的稳定错误码。 */
    private String failureCode;
    /** 处理失败说明，不保存敏感原文。 */
    private String failureMessage;
    /** UTC 就绪时间。 */
    private LocalDateTime readyAt;
    /** UTC 失败时间。 */
    private LocalDateTime failedAt;
    /** UTC 原文件、派生对象和检索投影完成物理清理的时间。 */
    private LocalDateTime contentDeletedAt;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;
}
