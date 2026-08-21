package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** N3.2 `retrieve-ranking-v1` 的服务端资源与排名基线。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.query.retrieve")
public class RetrieveProperties {

    private int maxQueryCodePoints = 2_000;
    private int defaultTopK = 5;
    private int maxTopK = 20;
    private int keywordCandidates = 50;
    private int knnK = 20;
    private int knnNumCandidates = 100;
    private int rrfK = 60;
    private int maxEvidenceBlocksPerResult = 3;
    private int maxEvidenceCharsPerResult = 12_000;
    private int maxTotalEvidenceChars = 80_000;
    private int executorThreads = 8;
}
