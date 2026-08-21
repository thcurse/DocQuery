package com.doc.docquery.messaging;

/** RabbitMQ v1 名称和路由键的单一事实源。 */
public final class RabbitMqTopology {

    public static final String MAIN_EXCHANGE = "docquery.document.processing.v1";
    public static final String MAIN_ROUTING_KEY =
            "document.version.process.requested.v1";
    public static final String MAIN_QUEUE = "docquery.document.processing.v1.q";
    public static final String RETRY_EXCHANGE =
            "docquery.document.processing.retry.v1";
    public static final String RETRY_QUEUE_1 =
            "docquery.document.processing.retry.1.v1.q";
    public static final String RETRY_QUEUE_2 =
            "docquery.document.processing.retry.2.v1.q";
    public static final String RETRY_QUEUE_3 =
            "docquery.document.processing.retry.3.v1.q";
    public static final String RETRY_ROUTING_1 = "retry.1.v1";
    public static final String RETRY_ROUTING_2 = "retry.2.v1";
    public static final String RETRY_ROUTING_3 = "retry.3.v1";
    public static final String DLX_EXCHANGE =
            "docquery.document.processing.dlx.v1";
    public static final String DLQ = "docquery.document.processing.dlq.v1.q";
    public static final String DLQ_ROUTING_KEY = "document.version.process.failed.v1";
    public static final String RETRY_HEADER = "x-docquery-retry-count";

    private RabbitMqTopology() {
    }
}
