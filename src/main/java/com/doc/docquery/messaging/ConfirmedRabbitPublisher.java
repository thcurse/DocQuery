package com.doc.docquery.messaging;

import com.doc.docquery.config.MessagingProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 统一等待 Publisher Confirm 和 Return 的同步发布边界。
 *
 * <p>只有 Broker ACK 且没有 Return 才返回成功；调用方随后才能更新 MySQL
 * 事实或 ACK 原消息。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "infrastructure-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public ConfirmedRabbitPublisher(
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.rabbitTemplate.setMandatory(true);
    }

    /** 发布持久 JSON 消息并等待相关 Confirm。 */
    public boolean publish(
            String exchange,
            String routingKey,
            byte[] body,
            String messageId,
            Map<String, Object> headers
    ) {
        MessageBuilder builder = MessageBuilder.withBody(body);
        builder.setContentType("application/json");
        builder.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        builder.setMessageId(messageId);
        builder.setCorrelationId(messageId);
        headers.forEach(builder::setHeader);
        Message message = builder.build();
        CorrelationData correlation = new CorrelationData(messageId);
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.getConfirmTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return confirm.ack() && correlation.getReturned() == null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        }
    }
}
