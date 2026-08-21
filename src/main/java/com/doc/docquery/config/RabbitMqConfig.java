package com.doc.docquery.config;

import com.doc.docquery.messaging.RabbitMqTopology;
import com.doc.docquery.messaging.DocumentDeletionRabbitMqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

import java.util.Map;

/** 声明 durable v1 Exchange、quorum 主队列、三级 TTL 重试队列和 DLQ。 */
@Configuration(proxyBeanMethods = false)
@EnableRabbit
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "infrastructure-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RabbitMqConfig {

    @Bean
    DirectExchange documentProcessingExchange() {
        return new DirectExchange(RabbitMqTopology.MAIN_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange documentProcessingRetryExchange() {
        return new DirectExchange(RabbitMqTopology.RETRY_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange documentProcessingDlxExchange() {
        return new DirectExchange(RabbitMqTopology.DLX_EXCHANGE, true, false);
    }

    @Bean
    Queue documentProcessingQueue() {
        return quorumQueue(RabbitMqTopology.MAIN_QUEUE, Map.of());
    }

    @Bean
    Queue documentProcessingRetryQueue1(MessagingProperties properties) {
        return retryQueue(
                RabbitMqTopology.RETRY_QUEUE_1,
                properties.getRetryDelay1().toMillis()
        );
    }

    @Bean
    Queue documentProcessingRetryQueue2(MessagingProperties properties) {
        return retryQueue(
                RabbitMqTopology.RETRY_QUEUE_2,
                properties.getRetryDelay2().toMillis()
        );
    }

    @Bean
    Queue documentProcessingRetryQueue3(MessagingProperties properties) {
        return retryQueue(
                RabbitMqTopology.RETRY_QUEUE_3,
                properties.getRetryDelay3().toMillis()
        );
    }

    @Bean
    Queue documentProcessingDeadLetterQueue() {
        return quorumQueue(RabbitMqTopology.DLQ, Map.of());
    }

    @Bean
    Binding documentProcessingBinding(
            Queue documentProcessingQueue,
            DirectExchange documentProcessingExchange
    ) {
        return BindingBuilder.bind(documentProcessingQueue)
                .to(documentProcessingExchange)
                .with(RabbitMqTopology.MAIN_ROUTING_KEY);
    }

    @Bean
    Binding documentProcessingRetryBinding1(
            Queue documentProcessingRetryQueue1,
            DirectExchange documentProcessingRetryExchange
    ) {
        return BindingBuilder.bind(documentProcessingRetryQueue1)
                .to(documentProcessingRetryExchange)
                .with(RabbitMqTopology.RETRY_ROUTING_1);
    }

    @Bean
    Binding documentProcessingRetryBinding2(
            Queue documentProcessingRetryQueue2,
            DirectExchange documentProcessingRetryExchange
    ) {
        return BindingBuilder.bind(documentProcessingRetryQueue2)
                .to(documentProcessingRetryExchange)
                .with(RabbitMqTopology.RETRY_ROUTING_2);
    }

    @Bean
    Binding documentProcessingRetryBinding3(
            Queue documentProcessingRetryQueue3,
            DirectExchange documentProcessingRetryExchange
    ) {
        return BindingBuilder.bind(documentProcessingRetryQueue3)
                .to(documentProcessingRetryExchange)
                .with(RabbitMqTopology.RETRY_ROUTING_3);
    }

    @Bean
    Binding documentProcessingDeadLetterBinding(
            Queue documentProcessingDeadLetterQueue,
            DirectExchange documentProcessingDlxExchange
    ) {
        return BindingBuilder.bind(documentProcessingDeadLetterQueue)
                .to(documentProcessingDlxExchange)
                .with(RabbitMqTopology.DLQ_ROUTING_KEY);
    }

    @Bean
    DirectExchange documentDeletionExchange() {
        return new DirectExchange(
                DocumentDeletionRabbitMqTopology.MAIN_EXCHANGE, true, false
        );
    }

    @Bean
    DirectExchange documentDeletionRetryExchange() {
        return new DirectExchange(
                DocumentDeletionRabbitMqTopology.RETRY_EXCHANGE, true, false
        );
    }

    @Bean
    DirectExchange documentDeletionDlxExchange() {
        return new DirectExchange(
                DocumentDeletionRabbitMqTopology.DLX_EXCHANGE, true, false
        );
    }

    @Bean
    Queue documentDeletionQueue() {
        return quorumQueue(DocumentDeletionRabbitMqTopology.MAIN_QUEUE, Map.of());
    }

    @Bean
    Queue documentDeletionRetryQueue1(MessagingProperties properties) {
        return retryQueue(
                DocumentDeletionRabbitMqTopology.RETRY_QUEUE_1,
                properties.getRetryDelay1().toMillis(),
                DocumentDeletionRabbitMqTopology.MAIN_EXCHANGE,
                DocumentDeletionRabbitMqTopology.MAIN_ROUTING_KEY
        );
    }

    @Bean
    Queue documentDeletionRetryQueue2(MessagingProperties properties) {
        return retryQueue(
                DocumentDeletionRabbitMqTopology.RETRY_QUEUE_2,
                properties.getRetryDelay2().toMillis(),
                DocumentDeletionRabbitMqTopology.MAIN_EXCHANGE,
                DocumentDeletionRabbitMqTopology.MAIN_ROUTING_KEY
        );
    }

    @Bean
    Queue documentDeletionRetryQueue3(MessagingProperties properties) {
        return retryQueue(
                DocumentDeletionRabbitMqTopology.RETRY_QUEUE_3,
                properties.getRetryDelay3().toMillis(),
                DocumentDeletionRabbitMqTopology.MAIN_EXCHANGE,
                DocumentDeletionRabbitMqTopology.MAIN_ROUTING_KEY
        );
    }

    @Bean
    Queue documentDeletionDeadLetterQueue() {
        return quorumQueue(DocumentDeletionRabbitMqTopology.DLQ, Map.of());
    }

    @Bean
    Binding documentDeletionBinding(
            Queue documentDeletionQueue,
            DirectExchange documentDeletionExchange
    ) {
        return BindingBuilder.bind(documentDeletionQueue)
                .to(documentDeletionExchange)
                .with(DocumentDeletionRabbitMqTopology.MAIN_ROUTING_KEY);
    }

    @Bean
    Binding documentDeletionRetryBinding1(
            Queue documentDeletionRetryQueue1,
            DirectExchange documentDeletionRetryExchange
    ) {
        return BindingBuilder.bind(documentDeletionRetryQueue1)
                .to(documentDeletionRetryExchange)
                .with(DocumentDeletionRabbitMqTopology.RETRY_ROUTING_1);
    }

    @Bean
    Binding documentDeletionRetryBinding2(
            Queue documentDeletionRetryQueue2,
            DirectExchange documentDeletionRetryExchange
    ) {
        return BindingBuilder.bind(documentDeletionRetryQueue2)
                .to(documentDeletionRetryExchange)
                .with(DocumentDeletionRabbitMqTopology.RETRY_ROUTING_2);
    }

    @Bean
    Binding documentDeletionRetryBinding3(
            Queue documentDeletionRetryQueue3,
            DirectExchange documentDeletionRetryExchange
    ) {
        return BindingBuilder.bind(documentDeletionRetryQueue3)
                .to(documentDeletionRetryExchange)
                .with(DocumentDeletionRabbitMqTopology.RETRY_ROUTING_3);
    }

    @Bean
    Binding documentDeletionDeadLetterBinding(
            Queue documentDeletionDeadLetterQueue,
            DirectExchange documentDeletionDlxExchange
    ) {
        return BindingBuilder.bind(documentDeletionDeadLetterQueue)
                .to(documentDeletionDlxExchange)
                .with(DocumentDeletionRabbitMqTopology.DLQ_ROUTING_KEY);
    }

    private Queue retryQueue(String name, long ttlMillis) {
        return retryQueue(
                name,
                ttlMillis,
                RabbitMqTopology.MAIN_EXCHANGE,
                RabbitMqTopology.MAIN_ROUTING_KEY
        );
    }

    private Queue retryQueue(
            String name,
            long ttlMillis,
            String deadLetterExchange,
            String deadLetterRoutingKey
    ) {
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("Retry queue TTL must be positive");
        }
        return quorumQueue(name, Map.of(
                "x-message-ttl", ttlMillis,
                "x-dead-letter-exchange", deadLetterExchange,
                "x-dead-letter-routing-key", deadLetterRoutingKey,
                "x-dead-letter-strategy", "at-least-once",
                "x-overflow", "reject-publish",
                "x-max-length", 10_000L
        ));
    }

    private Queue quorumQueue(String name, Map<String, Object> extraArguments) {
        QueueBuilder builder = QueueBuilder.durable(name)
                .withArgument("x-queue-type", "quorum");
        extraArguments.forEach(builder::withArgument);
        return builder.build();
    }
}
