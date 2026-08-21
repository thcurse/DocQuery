package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建应用请求。
 */
@Data
public class CreateApplicationDTO {

    /** 应用编码；Service 会去除首尾空格并转换为小写。 */
    @NotBlank(message = "应用编码不能为空")
    private String code;

    /** 应用名称。 */
    @NotBlank(message = "应用名称不能为空")
    @Size(max = 200, message = "应用名称不能超过200个字符")
    private String name;

    /** 应用环境。 */
    @NotBlank(message = "应用环境不能为空")
    @Pattern(
            regexp = "DEVELOPMENT|TESTING|PRODUCTION",
            message = "应用环境必须是DEVELOPMENT、TESTING或PRODUCTION"
    )
    private String environment;

    /** 应用描述。 */
    @Size(max = 1000, message = "应用描述不能超过1000个字符")
    private String description;
}
