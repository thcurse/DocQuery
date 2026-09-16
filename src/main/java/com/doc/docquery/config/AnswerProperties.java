package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 同步 Answer Agent 的成本与安全熔断上限，不是正常工具循环轮次。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.query.answer")
public class AnswerProperties {

    /** 在线 Answer 使用的 Chat profile；名称必须等于真实模型名。 */
    private String chatProfile = "claude-sonnet-5";
    private String promptVersion = "answer-agent-v22";
    private String policyVersion = "answer-policy-v22";
    /** 预留最后一轮给 submit_evidence；此前的正常轮次由 Agent 在 AUTO 下自主结束。 */
    private int maxToolRounds = 12;
    private int maxToolCalls = 200;
    private int maxModelCalls = 120;
    private int maxToolQueryCodePoints = 2_000;
    private int maxSearchLimit = 5;
    /** 单次真实章节和整轮 Agent 可登记的估算原文 token 安全预算。 */
    private int maxCanonicalSourceTokens = 180_000;
    /** 干净最终作答阶段可接收的 Agent 已选原始证据 token 上限。 */
    private int maxFinalizationSourceTokens = 32_000;
    private int maxOutlineNodes = 200;
    private int maxCitationCharacters = 16_000;
    private int maxOutputTokens = 4_096;
    private Duration modelTimeout = Duration.ofSeconds(120);
    private Duration totalTimeout = Duration.ofSeconds(300);

    /** Answer Search 的查询相关性重排；不负责证据去重或正确性判断。 */
    private Rerank rerank = new Rerank();

    @Getter
    @Setter
    public static class Rerank {
        private boolean enabled;
        private String model = "qwen3-rerank";
        /** 可选完整接口地址；留空时复用百炼 Embedding 的 Workspace Host。 */
        private String endpoint;
        /** 可选独立 Key；留空时复用百炼 Embedding API Key。 */
        private String apiKey;
        private int candidatePoolSize = 20;
        private Duration timeout = Duration.ofSeconds(15);
        private int maxRetries = 1;
        private String instruct = "Rank canonical knowledge-base sections by how directly and "
                + "completely they support answering the user's question.";
    }
}
