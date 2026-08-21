package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** N2.4 检索卡生成、模型调用和派生对象的显式资源边界。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.retrieval")
public class DocumentRetrievalProperties {

    /** 真实供应商总开关；默认 false，避免启动和测试产生费用。 */
    private boolean providerEnabled;
    /** retrieval JSONL Schema 版本。 */
    private int schemaVersion = 1;
    /** 检索派生对象只能写入的安全前缀。 */
    private String retrievalPrefix = "retrieval/";
    /** 单个 retrieval JSONL 最大字节数。 */
    private long maxRetrievalBytes = 64L * 1024L * 1024L;
    /** 单次 DeepSeek 请求允许的原文 UTF-16 code unit 数。 */
    private int maxSourceCharsPerChatCall = 64_000;
    /** 单份文档允许处理的原始文本总字符数。 */
    private long maxTotalSourceChars = 4_000_000L;
    /** Profile 加真实标题卡的总数上限。 */
    private int maxCards = 2_000;
    /** 单份文档所有真实 Chat 请求总数上限。 */
    private int maxChatCalls = 128;
    /** 一个 JSON Output 请求最多携带的卡片项数。 */
    private int maxItemsPerChatCall = 16;
    /** 检索卡 JSON 单次最大输出 token，避免多卡片响应被供应商默认上限截断。 */
    private int chatMaxOutputTokens = 8_192;
    /** 第一版固定导航向量维度。 */
    private int embeddingDimension = 2_560;
    /** 百炼一次请求最多携带的导航文本数。 */
    private int embeddingBatchSize = 20;
    /** SDK 重试次数不含第一次调用，所以 2 表示最多尝试 3 次。 */
    private int providerMaxRetries = 2;
    /** 单次 Chat 请求超时。 */
    private Duration chatTimeout = Duration.ofSeconds(180);
    /** 单次 Embedding 请求超时。 */
    private Duration embeddingTimeout = Duration.ofSeconds(60);
    /** 无引用对象进入回收候选前的安全宽限期。 */
    private Duration orphanGrace = Duration.ofHours(24);
    /** retrieval 孤立对象扫描周期。 */
    private Duration orphanScanDelay = Duration.ofMinutes(15);
    /** 单轮最多检查的 retrieval 对象数。 */
    private int orphanBatchSize = 100;
    /** DeepSeek 官方 OpenAI 兼容 Base URL。 */
    private String chatBaseUrl = "https://api.deepseek.com";
    /** 只从环境注入的 DeepSeek Key。 */
    private String chatApiKey = "";
    /** 检索卡生成模型。 */
    private String chatModel = "deepseek-v4-flash";
    /** 服务端 Prompt 版本。 */
    private String chatPromptVersion = "retrieval-card-v1";
    /** 百炼北京地域、可含 Workspace ID 的 OpenAI 兼容 Base URL。 */
    private String embeddingBaseUrl = "";
    /** 只从环境注入的百炼 Key。 */
    private String embeddingApiKey = "";
    /** 导航文本向量模型。 */
    private String embeddingModel = "qwen3.7-text-embedding";
    /** 稳定导航文本的模板版本。 */
    private String embeddingTemplateVersion = "retrieval-embedding-v1";
}
