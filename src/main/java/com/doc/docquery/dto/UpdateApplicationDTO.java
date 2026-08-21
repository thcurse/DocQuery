package com.doc.docquery.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改应用请求。
 */
@Data
public class UpdateApplicationDTO {

    /** 应用编码不可修改；非空时由 Service 返回明确业务错误。 */
    private String code;

    /** 新应用名称；不修改时不传。 */
    @Size(min = 1, max = 200, message = "应用名称长度必须在1到200个字符之间")
    private String name;

    /** 新应用环境；不修改时不传。 */
    @Pattern(
            regexp = "DEVELOPMENT|TESTING|PRODUCTION",
            message = "应用环境必须是DEVELOPMENT、TESTING或PRODUCTION"
    )
    private String environment;

    /** 新应用描述；空字符串表示清除。 */
    @Size(max = 1000, message = "应用描述不能超过1000个字符")
    private String description;

    /** 新应用状态；不修改时不传。 */
    @Pattern(regexp = "1|2", message = "应用状态必须是1（启用）或2（停用）")
    private String status;
}
