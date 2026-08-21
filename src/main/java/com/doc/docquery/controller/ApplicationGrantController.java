package com.doc.docquery.controller;

import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpsertApplicationGrantDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.ApplicationGrantService;
import com.doc.docquery.vo.ApplicationGrantVO;
import com.doc.docquery.vo.PageVO;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用访问知识库的授权管理接口。
 *
 * <p>授权属于可信应用而非最终业务用户；业务系统仍负责自己的终端用户和
 * 业务数据权限。</p>
 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}")
public class ApplicationGrantController {

    private final ApplicationGrantService applicationGrantService;

    public ApplicationGrantController(ApplicationGrantService applicationGrantService) {
        this.applicationGrantService = applicationGrantService;
    }

    /** 幂等新增、恢复或修改应用对知识库的授权。 */
    @PutMapping("/applications/{applicationId}/grants/{knowledgeBaseId}")
    public ApplicationGrantVO upsertGrant(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @PathVariable long knowledgeBaseId,
            @Valid @RequestBody UpsertApplicationGrantDTO dto
    ) {
        return applicationGrantService.upsertGrant(
                principal(authentication),
                tenantId,
                applicationId,
                knowledgeBaseId,
                dto
        );
    }

    /** 按应用分页查看授权。 */
    @GetMapping("/applications/{applicationId}/grants")
    public PageVO<ApplicationGrantVO> listByApplication(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return applicationGrantService.listByApplication(
                principal(authentication),
                tenantId,
                applicationId,
                new PageQueryDTO(page, size)
        );
    }

    /** 按知识库分页查看授权。 */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/grants")
    public PageVO<ApplicationGrantVO> listByKnowledgeBase(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return applicationGrantService.listByKnowledgeBase(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                new PageQueryDTO(page, size)
        );
    }

    /** 幂等撤销应用对知识库的授权。 */
    @DeleteMapping("/applications/{applicationId}/grants/{knowledgeBaseId}")
    public ResponseEntity<Void> revokeGrant(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long applicationId,
            @PathVariable long knowledgeBaseId
    ) {
        applicationGrantService.revokeGrant(
                principal(authentication),
                tenantId,
                applicationId,
                knowledgeBaseId
        );
        return ResponseEntity.noContent().build();
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
