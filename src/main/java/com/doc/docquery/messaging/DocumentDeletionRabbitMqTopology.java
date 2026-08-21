package com.doc.docquery.messaging;

/** N4.2 文档删除的独立 durable RabbitMQ 拓扑。 */
public final class DocumentDeletionRabbitMqTopology {

    public static final String MAIN_EXCHANGE = "docquery.document.deletion.v1";
    public static final String MAIN_ROUTING_KEY = "document.delete.requested.v1";
    public static final String MAIN_QUEUE = "docquery.document.deletion.v1.q";
    public static final String RETRY_EXCHANGE = "docquery.document.deletion.retry.v1";
    public static final String RETRY_QUEUE_1 = "docquery.document.deletion.retry.1.v1.q";
    public static final String RETRY_QUEUE_2 = "docquery.document.deletion.retry.2.v1.q";
    public static final String RETRY_QUEUE_3 = "docquery.document.deletion.retry.3.v1.q";
    public static final String RETRY_ROUTING_1 = "retry.1.v1";
    public static final String RETRY_ROUTING_2 = "retry.2.v1";
    public static final String RETRY_ROUTING_3 = "retry.3.v1";
    public static final String DLX_EXCHANGE = "docquery.document.deletion.dlx.v1";
    public static final String DLQ = "docquery.document.deletion.dlq.v1.q";
    public static final String DLQ_ROUTING_KEY = "document.delete.failed.v1";
    public static final String RETRY_HEADER = "x-docquery-deletion-retry-count";

    private DocumentDeletionRabbitMqTopology() {
    }
}
