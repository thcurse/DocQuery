package com.doc.docquery.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改知识库请求。
 */
@Data
public class UpdateKnowledgeBaseDTO {

    /** 新知识库名称；不修改时不传。 */
    @Size(min = 1, max = 200, message = "知识库名称长度必须在1到200个字符之间")
    private String name;

    /** 新知识库描述；空字符串表示清除。 */
    @Size(max = 1000, message = "知识库描述不能超过1000个字符")
    private String description;

    /** 新知识库状态；不修改时不传。 */
    @Pattern(regexp = "1|2", message = "知识库状态必须是1（启用）或2（停用）")
    private String status;
}
