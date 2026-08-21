package com.doc.docquery.controller;

import com.doc.docquery.dto.CreateTenantDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateTenantDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.TenantManagementService;
import com.doc.docquery.vo.CreatedTenantVO;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.TenantVO;
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
 * 管理面租户资源接口。
 *
 * <p>Controller 只完成 HTTP 参数绑定和当前管理员身份传递；平台管理员与
 * 租户管理员的可见范围由 {@code TenantManagementService} 统一执行。</p>
 */
@RestController
@RequestMapping("/api/admin/v1/tenants")
public class AdminTenantController {

    private final TenantManagementService tenantManagementService;

    public AdminTenantController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    /** 创建租户及其首个租户管理员。 */
    @PostMapping
    public ResponseEntity<CreatedTenantVO> createTenant(
            Authentication authentication,
            @Valid @RequestBody CreateTenantDTO dto
    ) {
        CreatedTenantVO created = tenantManagementService.createTenant(
                principal(authentication),
                dto
        );
        return ResponseEntity.created(
                URI.create("/api/admin/v1/tenants/" + created.getTenant().getId())
        ).body(created);
    }

    /** 分页列出当前管理员可见的租户。 */
    @GetMapping
    public PageVO<TenantVO> listTenants(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return tenantManagementService.listTenants(
                principal(authentication),
                new PageQueryDTO(page, size)
        );
    }

    /** 查询调用者范围内的租户详情。 */
    @GetMapping("/{tenantId}")
    public TenantVO getTenant(
            Authentication authentication,
            @PathVariable long tenantId
    ) {
        return tenantManagementService.getTenant(principal(authentication), tenantId);
    }

    /** 修改租户名称或启停状态。 */
    @PatchMapping("/{tenantId}")
    public TenantVO updateTenant(
            Authentication authentication,
            @PathVariable long tenantId,
            @Valid @RequestBody UpdateTenantDTO dto
    ) {
        return tenantManagementService.updateTenant(
                principal(authentication),
                tenantId,
                dto
        );
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
