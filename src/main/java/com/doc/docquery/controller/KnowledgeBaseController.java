package com.doc.docquery.controller;

import com.doc.docquery.dto.CreateKnowledgeBaseDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateKnowledgeBaseDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.KnowledgeBaseService;
import com.doc.docquery.vo.KnowledgeBaseVO;
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
 * 租户知识库管理接口。
 *
 * <p>本接口只管理知识库元数据和启停状态，不承担文档上传、解析或检索。</p>
 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 在路径租户内创建知识库。 */
    @PostMapping
    public ResponseEntity<KnowledgeBaseVO> createKnowledgeBase(
            Authentication authentication,
            @PathVariable long tenantId,
            @Valid @RequestBody CreateKnowledgeBaseDTO dto
    ) {
        KnowledgeBaseVO created = knowledgeBaseService.createKnowledgeBase(
                principal(authentication),
                tenantId,
                dto
        );
        return ResponseEntity.created(URI.create(
                "/api/admin/v1/tenants/" + tenantId
                        + "/knowledge-bases/" + created.getId()
        )).body(created);
    }

    /** 分页列出路径租户内的知识库。 */
    @GetMapping
    public PageVO<KnowledgeBaseVO> listKnowledgeBases(
            Authentication authentication,
            @PathVariable long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return knowledgeBaseService.listKnowledgeBases(
                principal(authentication),
                tenantId,
                new PageQueryDTO(page, size)
        );
    }

    /** 查询路径租户内的知识库详情。 */
    @GetMapping("/{knowledgeBaseId}")
    public KnowledgeBaseVO getKnowledgeBase(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId
    ) {
        return knowledgeBaseService.getKnowledgeBase(
                principal(authentication),
                tenantId,
                knowledgeBaseId
        );
    }

    /** 修改知识库可变属性或启停状态。 */
    @PatchMapping("/{knowledgeBaseId}")
    public KnowledgeBaseVO updateKnowledgeBase(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @Valid @RequestBody UpdateKnowledgeBaseDTO dto
    ) {
        return knowledgeBaseService.updateKnowledgeBase(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                dto
        );
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
