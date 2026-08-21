package com.doc.docquery.messaging;

/** RabbitMQ 中只携带稳定 ID 的文档删除 v1 消息。 */
public record DocumentDeletionMessage(
        int schemaVersion,
        long eventId,
        String eventType,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long documentDeletionJobId
) {
}
