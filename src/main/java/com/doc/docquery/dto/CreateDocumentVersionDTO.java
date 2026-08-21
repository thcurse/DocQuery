package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 为已有逻辑文档上传新版本的管理面元数据。
 */
@Data
public class CreateDocumentVersionDTO {

    /** 管理面上传幂等键，原值不得持久化或记录到日志。 */
    @NotBlank(message = "幂等键不能为空")
    @Size(max = 128, message = "幂等键不能超过128个字符")
    private String idempotencyKey;
}
