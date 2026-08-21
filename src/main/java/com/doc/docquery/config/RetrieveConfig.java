package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** N3.2 双路查询使用独立有界线程池，不占用 RabbitMQ Consumer 线程。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrieveProperties.class)
public class RetrieveConfig {

    @Bean(name = "retrieveExecutor", destroyMethod = "shutdown")
    ExecutorService retrieveExecutor(RetrieveProperties properties) {
        if (properties.getExecutorThreads() < 2) {
            throw new IllegalStateException("Retrieve executor requires at least two threads");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "docquery-retrieve-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(properties.getExecutorThreads(), factory);
    }
}
