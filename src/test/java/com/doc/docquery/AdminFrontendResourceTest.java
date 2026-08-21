package com.doc.docquery;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Maven 已将管理后台与 N4.4 外部 API 交付物装入 Spring Boot Classpath。 */
class AdminFrontendResourceTest {

    @Test
    void packagedAdminIndexReferencesAdminScopedAssets() throws IOException {
        try (var input = getClass().getResourceAsStream("/static/admin/index.html")) {
            assertThat(input).as("admin index resource").isNotNull();
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(html)
                    .contains("<div id=\"root\"></div>")
                    .contains("/admin/assets/")
                    .contains("DocQuery 管理后台");
        }
    }

    @Test
    void packagedAdminContainsOpenApiAndPostmanDownloads() throws IOException {
        try (var openApi = getClass().getResourceAsStream(
                "/static/admin/docs/docquery-service-api.openapi.yaml");
             var postman = getClass().getResourceAsStream(
                     "/static/admin/docs/docquery-service-api.postman_collection.json")) {
            assertThat(openApi).as("OpenAPI resource").isNotNull();
            assertThat(postman).as("Postman resource").isNotNull();
            String yaml = new String(openApi.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml)
                    .contains("openapi: 3.1.0")
                    .contains("/{knowledgeBaseId}/retrieve:")
                    .contains("/{knowledgeBaseId}/answer:")
                    .contains("applicationCredential:")
                    .contains("Idempotency-Key");
            var collection = new ObjectMapper().readTree(postman);
            assertThat(collection.path("item").size()).isEqualTo(2);
            assertThat(collection.path("variable").toString())
                    .contains("knowledgeBaseId")
                    .contains("credential");
        }
    }
}
