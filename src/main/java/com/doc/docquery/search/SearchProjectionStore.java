package com.doc.docquery.search;

import java.util.List;

/** N2.5 对 Elasticsearch 的最小供应商边界，业务服务不直接拼接请求。 */
public interface SearchProjectionStore {

    /** 幂等创建索引并严格校验既有 Mapping，返回当前集群和索引身份。 */
    SearchIndexDescriptor ensureIndices();

    /** 删除同一文档版本的未提交双投影，供至少一次重放安全重建。 */
    void deleteScope(SearchProjectionScope scope);

    /** 删除文档版本的全部双投影，不限定某次生成指纹。 */
    void deleteVersion(SearchProjectionScope scope);

    /** 使用确定性 blockId 覆盖写入 Evidence 文档。 */
    void indexEvidence(List<SearchProjectionDocument> documents);

    /** 使用确定性 cardId 覆盖写入 Navigation 文档。 */
    void indexNavigation(List<SearchProjectionDocument> documents);

    /** 仅在两份批量写入完成后刷新，避免逐批刷新放大开销。 */
    void refresh();

    /** 按完整范围和本次投影指纹统计 Evidence 数量。 */
    long countEvidence(SearchProjectionScope scope);

    /** 按完整范围和本次投影指纹统计 Navigation 数量。 */
    long countNavigation(SearchProjectionScope scope);

    /** 统计该版本的全部 Evidence，防止失败重建遗留旧指纹记录。 */
    long countEvidenceVersion(SearchProjectionScope scope);

    /** 统计该版本的全部 Navigation，防止失败重建遗留旧指纹记录。 */
    long countNavigationVersion(SearchProjectionScope scope);
}
