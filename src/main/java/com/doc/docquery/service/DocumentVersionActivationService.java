package com.doc.docquery.service;

/** 在双索引验收完成后原子提交版本、文档指针和任务终态。 */
public interface DocumentVersionActivationService {

    /** 仅当前租约持有者可激活仍为 latest 的 PROCESSING 版本。 */
    void activate(DocumentIngestionProcessor.Context context);
}
