package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 原文件对象存储的外部化配置。
 *
 * <p>业务代码只认识 S3 语义，不依赖 SeaweedFS、OSS 或其他服务端品牌。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.object-storage")
public class ObjectStorageProperties {

    /** 是否创建真实对象存储适配器。 */
    private boolean enabled = true;
    /** S3 兼容 HTTP Endpoint。 */
    private String endpoint = "http://localhost:8333";
    /** SigV4 签名所需 Region。 */
    private String region = "us-east-1";
    /** 对象存储访问键。 */
    private String accessKey = "docquery-local";
    /** 对象存储密钥。 */
    private String secretKey = "local-seaweedfs-only";
    /** 私有原文件 Bucket。 */
    private String bucket = "docquery-source";
    /** 本地 SeaweedFS 是否使用 path-style Bucket 地址。 */
    private boolean pathStyleAccess = true;
    /** 单文件实际字节数上限。 */
    private long uploadMaxBytes = 50L * 1024L * 1024L;
    /** 只允许回收器扫描的对象键前缀。 */
    private String orphanPrefix = "source/";
    /** 新对象进入回收候选前的安全宽限期。 */
    private Duration orphanGrace = Duration.ofHours(24);
    /** 孤立对象扫描周期。 */
    private Duration orphanScanDelay = Duration.ofMinutes(15);
    /** 单轮最多检查对象数。 */
    private int orphanBatchSize = 100;
}
