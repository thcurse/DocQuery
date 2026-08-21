package com.doc.docquery.service;

/** 首个平台管理员的一次性初始化用例。 */
public interface AdminBootstrapService {

    /** 在系统尚无管理员时创建初始平台管理员，并返回规范化登录名。 */
    String createInitialPlatformAdmin(String requestedLoginName, char[] password);
}
