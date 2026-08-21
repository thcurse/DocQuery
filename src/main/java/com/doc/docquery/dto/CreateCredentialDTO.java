package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建应用凭证请求。
 */
@Data
public class CreateCredentialDTO {

    /** 便于管理员识别用途的凭证名称。 */
    @NotBlank(message = "凭证名称不能为空")
    @Size(max = 200, message = "凭证名称不能超过200个字符")
    private String name;
}
