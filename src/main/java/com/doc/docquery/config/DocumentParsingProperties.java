package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** N2.3 标准化解析的资源上限、对象前缀和安全回收配置。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.parsing")
public class DocumentParsingProperties {

    /** canonical JSONL Schema 版本。 */
    private int schemaVersion = 1;
    /** DocQuery 自有解析规则版本；不同于第三方依赖版本。 */
    private String parserVersion = "1";
    /** 派生对象只能写入的安全前缀。 */
    private String canonicalPrefix = "canonical/";
    /** 单份 PDF 最大物理页数。 */
    private int maxPdfPages = 2_000;
    /** DOCX ZIP 各 Entry 声明解压大小之和上限。 */
    private long maxDocxExpandedBytes = 200L * 1024L * 1024L;
    /** canonical JSONL 对象字节数上限。 */
    private long maxCanonicalBytes = 100L * 1024L * 1024L;
    /** 一份文档允许的最大证据块数量。 */
    private int maxBlocks = 200_000;
    /** 单个证据块允许的最大 UTF-16 code unit 数量。 */
    private int maxBlockChars = 65_536;
    /** 无引用派生对象进入清理候选前的安全宽限期。 */
    private Duration orphanGrace = Duration.ofHours(24);
    /** 派生对象孤立扫描周期。 */
    private Duration orphanScanDelay = Duration.ofMinutes(15);
    /** 单轮最多检查的派生对象数。 */
    private int orphanBatchSize = 100;
    /** 默认启用的 DeepDoc HTTP 四格式 Adapter。 */
    private DeepDoc deepdoc = new DeepDoc();

    @Getter
    @Setter
    public static class DeepDoc {

        /** 默认由 DeepDoc 接管四种格式；显式关闭才使用本地 Adapter。 */
        private boolean enabled = true;
        /** DeepDoc 内部服务根地址，不允许包含凭证。 */
        private String baseUrl = "http://127.0.0.1:18080";
        /** 建立 HTTP 连接的上限。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 包含排队与完整文档解析的单请求上限。 */
        private Duration requestTimeout = Duration.ofMinutes(20);
        /** Java 允许读取的最大 HTTP 响应字节数。 */
        private long maxResponseBytes = 100L * 1024L * 1024L;
    }
}
