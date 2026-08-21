package com.doc.docquery.controller;

import com.doc.docquery.dto.CreateCredentialDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.CredentialService;
import com.doc.docquery.vo.CreatedCredentialVO;
import com.doc.docquery.vo.CredentialVO;
import com.doc.docquery.vo.PageVO;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 租户应用凭证管理接口。
 *
 * <p>凭证密钥只在创建响应中返回一次；后续列表和详情仅暴露 Key ID 前缀及
 * 生命周期状态。</p>
 */
@RestController
@RequestMapping(
        "/api/admin/v1/tenants/{tenantId}/applications/{applicationId}/credentials"
)
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 创建凭证；完整密钥仅通过本次响应返回。 */
    @PostMapping
    public ResponseEntity<CreatedCredentialVO> createCredential(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @Valid @RequestBody CreateCredentialDTO dto
    ) {
        CreatedCredentialVO created = credentialService.createCredential(
                principal(authentication),
                tenantId,
                applicationId,
                dto
        );
        return ResponseEntity.created(URI.create(
                        "/api/admin/v1/tenants/" + tenantId
                                + "/applications/" + applicationId
                                + "/credentials/" + created.getId()
                ))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(created);
    }

    /** 分页列出凭证的非敏感元数据。 */
    @GetMapping
    public PageVO<CredentialVO> listCredentials(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return credentialService.listCredentials(
                principal(authentication),
                tenantId,
                applicationId,
                new PageQueryDTO(page, size)
        );
    }

    /** 幂等且不可逆地撤销凭证。 */
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> revokeCredential(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @PathVariable long credentialId
    ) {
        credentialService.revokeCredential(
                principal(authentication),
                tenantId,
                applicationId,
                credentialId
        );
        return ResponseEntity.noContent().build();
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
