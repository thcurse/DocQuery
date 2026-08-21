package com.doc.docquery.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改租户请求。
 */
@Data
public class UpdateTenantDTO {

    /** 新租户名称；不修改时不传。 */
    @Size(min = 1, max = 200, message = "租户名称长度必须在1到200个字符之间")
    private String name;

    /** 新状态；不修改时不传。 */
    @Pattern(regexp = "1|2", message = "租户状态必须是1（启用）或2（停用）")
    private String status;
}
