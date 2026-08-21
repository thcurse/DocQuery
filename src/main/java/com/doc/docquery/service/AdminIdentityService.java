package com.doc.docquery.service;

import com.doc.docquery.security.AdminPrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;

/** 管理员登录身份加载与 Session 持续有效性校验边界。 */
public interface AdminIdentityService extends UserDetailsService {

    /** 根据当前数据库状态确认已登录身份快照是否仍可继续使用。 */
    boolean isSessionStillValid(AdminPrincipal principal);
}
