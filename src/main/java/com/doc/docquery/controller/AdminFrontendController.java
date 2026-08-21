package com.doc.docquery.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 将固定管理后台入口转发到随 JAR 打包的 SPA 首页。 */
@Controller
public class AdminFrontendController {

    @GetMapping({"/admin", "/admin/"})
    public String adminIndex() {
        return "forward:/admin/index.html";
    }
}
