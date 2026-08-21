package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 首次上传并创建逻辑文档的管理面元数据。
 */
@Data
public class CreateDocumentUploadDTO {

    /** 逻辑文档名称。 */
    @NotBlank(message = "文档名称不能为空")
    @Size(max = 200, message = "文档名称不能超过200个字符")
    private String documentName;

    /** 管理面上传幂等键，原值不得持久化或记录到日志。 */
    @NotBlank(message = "幂等键不能为空")
    @Size(max = 128, message = "幂等键不能超过128个字符")
    private String idempotencyKey;
}
