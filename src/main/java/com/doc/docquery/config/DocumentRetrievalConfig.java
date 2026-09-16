package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 注册 N2.4 配置；真实供应商适配器由单独配置按开关创建。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentRetrievalProperties.class)
public class DocumentRetrievalConfig {

    /** 同层检索卡批次共享该有界线程池，线程数同时充当供应商并发隔离上限。 */
    @Bean(name = "retrievalChatExecutor", destroyMethod = "shutdown")
    ExecutorService retrievalChatExecutor(DocumentRetrievalProperties properties) {
        if (properties.getMaxConcurrentChatCalls() < 1) {
            throw new IllegalStateException(
                    "Retrieval Chat executor requires at least one thread"
            );
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "docquery-retrieval-chat-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(
                properties.getMaxConcurrentChatCalls(),
                factory
        );
    }
}
