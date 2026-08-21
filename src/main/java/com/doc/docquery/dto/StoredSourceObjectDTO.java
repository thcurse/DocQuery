package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 已经持久保存的原文件描述。
 *
 * <p>N2.1 只消费该描述，不连接或校验对象存储；N2.2 负责产生可信描述。</p>
 */
@Data
public class StoredSourceObjectDTO {

    /** 上传时的原始文件名。 */
    @NotBlank(message = "原始文件名不能为空")
    @Size(max = 512, message = "原始文件名不能超过512个字符")
    private String originalFilename;

    /** 文件格式字符串数字代码。 */
    @NotBlank(message = "文件格式不能为空")
    private String sourceFormat;

    /** 持久对象所在 Bucket。 */
    @NotBlank(message = "对象存储Bucket不能为空")
    @Size(max = 63, message = "对象存储Bucket不能超过63个字符")
    private String sourceBucket;

    /** 持久对象 Key。 */
    @NotBlank(message = "对象存储Key不能为空")
    @Size(max = 1024, message = "对象存储Key不能超过1024个字符")
    private String sourceObjectKey;

    /** 原文件字节数。 */
    @Positive(message = "原文件大小必须大于0")
    private long sourceSizeBytes;

    /** 原文件小写十六进制 SHA-256。 */
    @NotBlank(message = "原文件SHA-256不能为空")
    @Size(min = 64, max = 64, message = "原文件SHA-256必须为64个字符")
    private String sourceSha256;

    /** Content-Type 元数据，可为空。 */
    @Size(max = 255, message = "Content-Type不能超过255个字符")
    private String sourceContentType;
}
