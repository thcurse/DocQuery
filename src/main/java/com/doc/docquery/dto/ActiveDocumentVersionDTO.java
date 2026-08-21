package com.doc.docquery.dto;

import lombok.Data;

/**
 * 一次知识库查询快照中的当前生效文档版本投影。
 *
 * <p>该类型只由带 Tenant、KnowledgeBase 和状态过滤的单条 MyBatis 查询填充，
 * 不是 HTTP 请求或响应模型。</p>
 */
@Data
public class ActiveDocumentVersionDTO {

    /** 逻辑文档 ID。 */
    private Long documentId;
    /** 本次查询唯一允许读取的当前生效版本 ID。 */
    private Long documentVersionId;
    /** 面向引用展示的文档版本号。 */
    private Integer versionNo;
    /** 面向引用展示的逻辑文档名称。 */
    private String documentName;
}
