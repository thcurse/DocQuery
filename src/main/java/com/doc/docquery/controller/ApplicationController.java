package com.doc.docquery.controller;

import com.doc.docquery.dto.CreateApplicationDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateApplicationDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.ApplicationService;
import com.doc.docquery.vo.ApplicationVO;
import com.doc.docquery.vo.PageVO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 租户应用管理接口。
 *
 * <p>所有操作都携带路径 Tenant ID，并由 Service 再次校验登录管理员的租户
 * 范围，不能仅依赖客户端提交的路径。</p>
 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /** 在路径租户内创建应用。 */
    @PostMapping
    public ResponseEntity<ApplicationVO> createApplication(
            Authentication authentication,
            @PathVariable long tenantId,
            @Valid @RequestBody CreateApplicationDTO dto
    ) {
        ApplicationVO created = applicationService.createApplication(
                principal(authentication),
                tenantId,
                dto
        );
        return ResponseEntity.created(URI.create(
                "/api/admin/v1/tenants/" + tenantId + "/applications/" + created.getId()
        )).body(created);
    }

    /** 分页列出路径租户内的应用。 */
    @GetMapping
    public PageVO<ApplicationVO> listApplications(
            Authentication authentication,
            @PathVariable long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return applicationService.listApplications(
                principal(authentication),
                tenantId,
                new PageQueryDTO(page, size)
        );
    }

    /** 查询路径租户内的应用详情。 */
    @GetMapping("/{applicationId}")
    public ApplicationVO getApplication(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId
    ) {
        return applicationService.getApplication(
                principal(authentication),
                tenantId,
                applicationId
        );
    }

    /** 修改应用可变属性或启停状态。 */
    @PatchMapping("/{applicationId}")
    public ApplicationVO updateApplication(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @Valid @RequestBody UpdateApplicationDTO dto
    ) {
        return applicationService.updateApplication(
                principal(authentication),
                tenantId,
                applicationId,
                dto
        );
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
