package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** multipart 首次上传中的 JSON metadata Part；幂等键必须来自 HTTP Header。 */
@Data
public class CreateDocumentUploadMetadataDTO {

    /** KnowledgeBase 内大小写敏感唯一的逻辑文档名称。 */
    @NotBlank(message = "文档名称不能为空")
    @Size(max = 200, message = "文档名称不能超过200个字符")
    private String documentName;
}
