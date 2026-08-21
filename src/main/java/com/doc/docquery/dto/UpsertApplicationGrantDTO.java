package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 新增、恢复或修改应用知识库授权请求。
 */
@Data
public class UpsertApplicationGrantDTO {

    /** 权限代码：1=只读，2=只写，3=读写。 */
    @NotBlank(message = "权限不能为空")
    @Pattern(regexp = "1|2|3", message = "权限必须是1（只读）、2（只写）或3（读写）")
    private String permission;
}
