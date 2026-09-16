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
    private int schemaVersion = 2;
    /** 检索派生对象只能写入的安全前缀。 */
    private String retrievalPrefix = "retrieval/";
    /** 单个 retrieval JSONL 最大字节数。 */
    private long maxRetrievalBytes = 64L * 1024L * 1024L;
    /** 单次 PackyAPI Chat 请求允许的估算原文 token 数；为提示词、工具和输出预留空间。 */
    private int maxSourceTokensPerChatCall = 180_000;
    /** 单份文档允许处理的原始文本总字符数。 */
    private long maxTotalSourceChars = 4_000_000L;
    /** Profile 加真实标题卡的总数上限。 */
    private int maxCards = 2_000;
    /** 单份文档所有真实 Chat 请求总数上限。 */
    private int maxChatCalls = 128;
    /** 一个 JSON Output 请求最多携带的卡片项数；按 GLM 实测延迟控制输出规模。 */
    private int maxItemsPerChatCall = 4;
    /** 同一时刻最多执行的检索卡 Chat 请求数；所有文档共享同一个有界线程池。 */
    private int maxConcurrentChatCalls = 4;
    /** 实验性导航分区开关；正式链路默认直接使用模型长上下文。 */
    private boolean navigationPartitionEnabled;
    /** 估算 token 超过此值才生成派生导航分区。 */
    private int navigationPartitionThresholdTokens = 64_000;
    /** 最终单个派生导航分区的估算 token 下限。 */
    private int navigationPartitionMinimumTokens = 8_000;
    /** 单个超长章节最终最多生成的导航分区数。 */
    private int navigationPartitionMaxCount = 6;
    /** 模型候选的响应安全上限；未入选候选不会持久化。 */
    private int navigationPartitionMaxCandidates = 256;
    /** 多语言确定性 token 估算规则版本。 */
    private String navigationPartitionEstimatorVersion = "mixed-char-v1";
    /** 检索卡 JSON 单次最大输出 token，避免多卡片响应被供应商默认上限截断。 */
    private int chatMaxOutputTokens = 8_192;
    /** 离线检索卡使用的 Chat profile；名称必须等于真实模型名。 */
    private String chatProfile = "glm-5.3-flash";
    /** 第一版固定导航向量维度。 */
    private int embeddingDimension = 2_560;
    /** 百炼一次请求最多携带的导航文本数。 */
    private int embeddingBatchSize = 20;
    /** SDK 重试次数不含第一次调用，所以 2 表示最多尝试 3 次。 */
    private int providerMaxRetries = 2;
    /** 单次检索卡 Chat 请求超时。 */
    private Duration chatTimeout = Duration.ofSeconds(90);
    /** 单次 Embedding 请求超时。 */
    private Duration embeddingTimeout = Duration.ofSeconds(60);
    /** 无引用对象进入回收候选前的安全宽限期。 */
    private Duration orphanGrace = Duration.ofHours(24);
    /** retrieval 孤立对象扫描周期。 */
    private Duration orphanScanDelay = Duration.ofMinutes(15);
    /** 单轮最多检查的 retrieval 对象数。 */
    private int orphanBatchSize = 100;
    /** 服务端 Prompt 版本。 */
    private String chatPromptVersion = "retrieval-card-v2";
    /** 百炼北京地域、可含 Workspace ID 的 OpenAI 兼容 Base URL。 */
    private String embeddingBaseUrl = "";
    /** 只从环境注入的百炼 Key。 */
    private String embeddingApiKey = "";
    /** 导航文本向量模型。 */
    private String embeddingModel = "qwen3.7-text-embedding";
    /** 稳定导航文本的模板版本。 */
    private String embeddingTemplateVersion = "retrieval-embedding-v1";
}
