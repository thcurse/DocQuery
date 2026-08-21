package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Outbox Publisher 和文档处理 Consumer 的可覆盖运行参数。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.messaging")
public class MessagingProperties {

    /** 是否声明 RabbitMQ 拓扑并创建 Confirm Publisher。 */
    private boolean infrastructureEnabled = true;
    /** 是否启动 Outbox 定时发布。 */
    private boolean publisherEnabled = true;
    /** Publisher 轮询间隔。 */
    private Duration publisherDelay = Duration.ofSeconds(1);
    /** 单轮领取事件上限。 */
    private int publisherBatchSize = 50;
    /** Publisher 数据库租约时长。 */
    private Duration publisherLease = Duration.ofSeconds(30);
    /** 单条消息等待 Broker Confirm 的上限。 */
    private Duration confirmTimeout = Duration.ofSeconds(5);
    /** 第一次自动重试前的延迟。 */
    private Duration retryDelay1 = Duration.ofSeconds(5);
    /** 第二次自动重试前的延迟。 */
    private Duration retryDelay2 = Duration.ofSeconds(30);
    /** 第三次自动重试前的延迟。 */
    private Duration retryDelay3 = Duration.ofSeconds(300);
    /** 完整处理器存在前保持 false，避免 no-op ACK。 */
    private boolean listenerEnabled;
    /** 是否启动独立文档删除 Consumer。 */
    private boolean deletionListenerEnabled;
    /** 同一应用实例的 Consumer 并发数。 */
    private int consumerConcurrency = 1;
    /** ProcessingJob 的单次租约时长。 */
    private Duration jobLease = Duration.ofMinutes(15);
    /** 长任务续租周期。 */
    private Duration jobHeartbeat = Duration.ofMinutes(1);
}
