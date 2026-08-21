package com.doc.docquery.service;

import com.doc.docquery.security.QueryAccessContext;

/** 为正式服务请求完成 Bearer 认证、READ 授权和 activeVersion 快照读取。 */
public interface QueryAccessService {

    /**
     * 返回后续查询唯一允许使用的可信范围；调用方不能另行声明 Tenant。
     *
     * @param authorizationHeader 完整 Authorization Header
     * @param knowledgeBaseId 路径中由业务系统明确配置的知识库 ID
     */
    QueryAccessContext authorizeAndSnapshot(
            String authorizationHeader,
            long knowledgeBaseId
    );
}
