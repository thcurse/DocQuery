package com.doc.docquery.config;

import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.RetrievalArtifactStore;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.service.impl.S3CanonicalArtifactStore;
import com.doc.docquery.service.impl.S3RetrievalArtifactStore;
import com.doc.docquery.service.impl.S3SourceObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/** 创建 AWS SDK v2 S3 Client，并把协议实现封装在对象存储端口之后。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ObjectStorageConfig {

    /**
     * Client 可以指向 SeaweedFS、OSS 或其他 S3 Endpoint；Region 仍参与 SigV4
     * 签名，即使自托管服务端不使用 AWS Region 概念也不能省略。
     */
    @Bean(destroyMethod = "close")
    S3Client sourceS3Client(ObjectStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                properties.getAccessKey(),
                                properties.getSecretKey()
                        )
                ))
                .httpClientBuilder(Apache5HttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build())
                .build();
    }

    /** 首个端口实现只使用标准 S3 对象操作。 */
    @Bean
    SourceObjectStore sourceObjectStore(
            S3Client sourceS3Client,
            ObjectStorageProperties properties
    ) {
        return new S3SourceObjectStore(sourceS3Client, properties);
    }

    /** canonical 派生对象复用同一 S3 Client，但保持独立业务端口。 */
    @Bean
    CanonicalArtifactStore canonicalArtifactStore(
            S3Client sourceS3Client,
            ObjectStorageProperties properties
    ) {
        return new S3CanonicalArtifactStore(sourceS3Client, properties);
    }

    /** retrieval 派生对象与 canonical 共用基础设施，但保持独立端口和生命周期。 */
    @Bean
    RetrievalArtifactStore retrievalArtifactStore(
            S3Client sourceS3Client,
            ObjectStorageProperties properties
    ) {
        return new S3RetrievalArtifactStore(sourceS3Client, properties);
    }
}
