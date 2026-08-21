package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库请求。
 */
@Data
public class CreateKnowledgeBaseDTO {

    /** 知识库名称。 */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 200, message = "知识库名称不能超过200个字符")
    private String name;

    /** 知识库描述。 */
    @Size(max = 1000, message = "知识库描述不能超过1000个字符")
    private String description;
}
