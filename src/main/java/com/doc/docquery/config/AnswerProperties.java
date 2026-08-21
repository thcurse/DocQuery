package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** N3.3 `answer-policy-v1` 的同步 Agent 成本与安全硬上限。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.query.answer")
public class AnswerProperties {

    private String promptVersion = "answer-agent-v1";
    private String policyVersion = "answer-policy-v1";
    private int maxToolRounds = 3;
    private int maxToolCalls = 6;
    private int maxParallelToolCalls = 2;
    private int maxModelCalls = 4;
    private int maxRepairCalls = 1;
    private int maxToolQueryCodePoints = 2_000;
    private int maxSearchLimit = 5;
    private int maxReadBlocks = 3;
    private int maxReadCharacters = 12_000;
    private int maxCanonicalCharacters = 60_000;
    private int maxOutlineNodes = 200;
    private int maxOutlineNodesPerCall = 100;
    private int maxAnswerCodePoints = 4_000;
    private int maxCitations = 8;
    private int maxCitationCharacters = 16_000;
    private int maxOutputTokens = 4_096;
    private Duration modelTimeout = Duration.ofSeconds(45);
    private Duration totalTimeout = Duration.ofSeconds(120);
}
