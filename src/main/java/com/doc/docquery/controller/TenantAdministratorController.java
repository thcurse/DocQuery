package com.doc.docquery.controller;

import com.doc.docquery.dto.CreateTenantAdministratorDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.ResetTenantAdministratorPasswordDTO;
import com.doc.docquery.dto.UpdateTenantAdministratorDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.TenantAdministratorManagementService;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.TenantAdministratorVO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 平台管理面的租户管理员账号接口。 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}/administrators")
public class TenantAdministratorController {

    private final TenantAdministratorManagementService service;

    public TenantAdministratorController(TenantAdministratorManagementService service) {
        this.service = service;
    }

    @GetMapping
    public PageVO<TenantAdministratorVO> listAdministrators(
            Authentication authentication,
            @PathVariable long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listAdministrators(
                principal(authentication),
                tenantId,
                new PageQueryDTO(page, size)
        );
    }

    @PostMapping
    public ResponseEntity<TenantAdministratorVO> createAdministrator(
            Authentication authentication,
            @PathVariable long tenantId,
            @Valid @RequestBody CreateTenantAdministratorDTO dto
    ) {
        TenantAdministratorVO created = service.createAdministrator(
                principal(authentication),
                tenantId,
                dto
        );
        return ResponseEntity.created(URI.create(
                "/api/admin/v1/tenants/" + tenantId + "/administrators/" + created.getId()
        )).body(created);
    }

    @PatchMapping("/{administratorId}")
    public TenantAdministratorVO updateAdministrator(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long administratorId,
            @Valid @RequestBody UpdateTenantAdministratorDTO dto
    ) {
        return service.updateAdministrator(
                principal(authentication),
                tenantId,
                administratorId,
                dto
        );
    }

    @PutMapping("/{administratorId}/password")
    public ResponseEntity<Void> resetPassword(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long administratorId,
            @Valid @RequestBody ResetTenantAdministratorPasswordDTO dto
    ) {
        service.resetPassword(principal(authentication), tenantId, administratorId, dto);
        return ResponseEntity.noContent().build();
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
