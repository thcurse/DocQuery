package com.doc.docquery.messaging;

/** RabbitMQ 中只携带稳定 ID 的文档处理 v1 消息。 */
public record DocumentProcessingMessage(
        int schemaVersion,
        long eventId,
        String eventType,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long documentVersionId,
        long processingJobId
) {
}
