package com.doc.docquery.service.impl;

import com.doc.docquery.search.SearchProjectionException;
import com.doc.docquery.service.DocumentCanonicalService;
import com.doc.docquery.service.DocumentIngestionProcessor;
import com.doc.docquery.service.DocumentParseException;
import com.doc.docquery.service.DocumentProcessingException;
import com.doc.docquery.service.DocumentRetrievalService;
import com.doc.docquery.service.DocumentSearchProjectionService;
import com.doc.docquery.service.DocumentVersionActivationService;
import com.doc.docquery.service.RetrievalGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * N2.5 完整入库处理器：标准化、生成导航卡、双索引投影，最后原子激活。
 *
 * <p>前三步都必须幂等并在 MySQL 事务外执行；只有最终状态切换进入短事务。
 * 这样重试不会把模型调用、对象存储或 ES 网络等待带入数据库锁区间。</p>
 */
@Service
@ConditionalOnProperty(prefix = "docquery.search", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
        prefix = "docquery.retrieval",
        name = "provider-enabled",
        havingValue = "true"
)
public class DocumentIngestionProcessorImpl implements DocumentIngestionProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(
            DocumentIngestionProcessorImpl.class
    );

    private final DocumentCanonicalService canonicalService;
    private final DocumentRetrievalService retrievalService;
    private final DocumentSearchProjectionService projectionService;
    private final DocumentVersionActivationService activationService;

    public DocumentIngestionProcessorImpl(
            DocumentCanonicalService canonicalService,
            DocumentRetrievalService retrievalService,
            DocumentSearchProjectionService projectionService,
            DocumentVersionActivationService activationService
    ) {
        this.canonicalService = canonicalService;
        this.retrievalService = retrievalService;
        this.projectionService = projectionService;
        this.activationService = activationService;
    }

    @Override
    public void process(Context context) {
        long versionId = context.version().getId();
        try {
            canonicalService.ensureCanonical(versionId);
            retrievalService.ensureRetrieval(versionId);
            projectionService.ensureProjection(versionId);
            activationService.activate(context);
        } catch (DocumentProcessingException exception) {
            throw exception;
        } catch (DocumentParseException exception) {
            throw processing(exception.code(), exception.retryable(), exception);
        } catch (RetrievalGenerationException exception) {
            // RetrievalGenerationException 只包含本地稳定错误和安全校验摘要；
            // 不记录供应商原始响应、canonical 正文、对象地址或任何凭证。
            LOG.warn(
                    "Document retrieval generation failed: documentVersionId={}, code={}, detail={}",
                    versionId,
                    exception.code(),
                    exception.getMessage()
            );
            throw processing(exception.code(), exception.retryable(), exception);
        } catch (SearchProjectionException exception) {
            throw processing(exception.code(), exception.retryable(), exception);
        }
    }

    private DocumentProcessingException processing(
            String code,
            boolean retryable,
            RuntimeException cause
    ) {
        // 上层只记录稳定通用消息，cause 仅保留在进程内用于诊断。
        return new DocumentProcessingException(
                code,
                "Document ingestion stage failed",
                retryable,
                cause
        );
    }
}
