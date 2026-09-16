package com.doc.docquery.service.impl;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.config.AnswerProperties;
import com.doc.docquery.config.RetrieveProperties;
import com.doc.docquery.dto.AnswerRequestDTO;
import com.doc.docquery.parser.BlockKind;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.parser.SourcePosition;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.service.AnswerChatGateway;
import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.AnswerException;
import com.doc.docquery.service.QueryAccessService;
import com.doc.docquery.service.QueryIdempotencyService;
import com.doc.docquery.service.SearchRerankGateway;
import com.doc.docquery.service.ScopedRetrievalService;
import com.doc.docquery.vo.AnswerResponseVO;
import com.doc.docquery.vo.RetrieveResponseVO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 不依赖供应商或容器，验证 N3.3 Agent 控制面、工具边界、引用和幂等。 */
class AnswerServiceImplTest {

    @Test
    void answersFromCanonicalEvidenceAndReplaysWithoutSecondModelCall() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "search", "{\"query\":\"退款期限验证\"}"),
                answered("七天内可以退款。", "E1")
        );
        StatefulIdempotency idempotency = new StatefulIdempotency();
        AnswerServiceImpl service = service(fixture, gateway, idempotency);

        AnswerResponseVO first = service.answer(
                "Bearer token", "answer-key", 21L,
                request("退款期限是什么？", "HYBRID", 2)
        );
        AnswerResponseVO replay = service.answer(
                "Bearer token", "answer-key", 21L,
                request("退款期限是什么？", "HYBRID", 2)
        );

        assertThat(first.getStatus()).isEqualTo("ANSWERED");
        assertThat(first.getAnswer()).isEqualTo("七天内可以退款。\n\n参考依据：[1]");
        assertThat(first.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getCitationIndex()).isEqualTo(1);
            assertThat(citation.getDocumentId()).isEqualTo(31L);
            assertThat(citation.getDocumentVersionId()).isEqualTo(41L);
            assertThat(citation.getHeadingPath()).containsExactly("Manual", "Refund Policy");
            assertThat(citation.getText()).isEqualTo("Refunds are allowed within seven days.");
            assertThat(citation.getSourcePosition().getStartLine()).isEqualTo(3);
        });
        assertThat(replay.getQueryExecutionId()).isEqualTo(first.getQueryExecutionId());
        assertThat(replay.getCitations()).usingRecursiveComparison()
                .isEqualTo(first.getCitations());
        assertThat(gateway.calls).isEqualTo(2);
        assertThat(fixture.retrieval.calls).isEqualTo(2);
        assertThat(idempotency.operation).isEqualTo(QueryOperation.ANSWER);
        assertThat(idempotency.completedJson).contains("七天内可以退款");
    }

    @Test
    void boundedToolsUseFixedSnapshotRenewLeaseAndRegisterReadEvidence() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"D1\"}"),
                toolTurn("c2", "open", "{\"ref\":\"S1\"}"),
                toolTurn("c3", "search", "{\"query\":\"退款条款验证\"}"),
                answered("可在七天内退款。", "E2")
        );
        StatefulIdempotency idempotency = new StatefulIdempotency();
        AnswerResponseVO response = service(fixture, gateway, idempotency).answer(
                "Bearer token", "tool-key", 21L,
                request("请读取退款条款", null, null)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getCitations()).singleElement()
                .extracting(AnswerResponseVO.Citation::getBlockId)
                .isEqualTo("b-0");
        assertThat(idempotency.renewals).isEqualTo(4);
        assertThat(fixture.retrieval.loadDocumentIds).containsExactly(31L);
        assertThat(gateway.toolsEnabled).containsExactly(true, true, true, true);
        AnswerChatGateway.ToolResultContent readResult = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .filter(result -> result.resultJson().contains("\"evidenceId\":\"E2\""))
                .findFirst()
                .orElseThrow();
        assertThat(readResult.resultJson())
                .contains("E2", "Refund Policy", "Refunds are allowed within seven days.")
                .doesNotContain("blockId", "b-0", "b-1");
        assertThat(readResult.resultJson())
                .doesNotContain("bucket", "objectKey", "tenantId");
    }

    @Test
    void readsCompleteRealSectionAndGroupsConsecutivePdfBlocksByPage() {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"S1\"}"),
                toolTurn("c2", "search", "{\"query\":\"全部要求验证\"}"),
                answered("三项要求齐全。", "E3")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "complete-section-key", 21L,
                request("全部要求", "HYBRID", 1)
        );

        assertThat(response.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getHeadingPath())
                    .containsExactly("Manual", "Complete Section");
            assertThat(citation.getSourcePosition().getPageNumber()).isEqualTo(2);
            assertThat(citation.getPageNumber()).isEqualTo(2);
            assertThat(citation.getText()).contains("First item", "Second item", "Third item");
        });
        AnswerChatGateway.ToolResultContent readResult = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .findFirst()
                .orElseThrow();
        assertThat(readResult.resultJson())
                .contains("First item", "Second item", "Third item", "\"pageNumber\":2")
                .doesNotContain("blockId", "p2-1", "p2-2", "p2-3");
    }

    @Test
    void openEvidenceReadsTheOrderedDigitalTableParentColumn() {
        Fixture fixture = pdfTableFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                answeredWithReadRefs(
                        "Use Google search.", List.of("E1"), List.of("R1")
                )
        ).withFinalization(answered("Use Google search.", "E1").text());

        service(fixture, gateway, new StatefulIdempotency()).answer(
                "Bearer token", "pdf-table-key", 21L,
                request("Quarter 3", "HYBRID", 1)
        );

        AnswerChatGateway.ToolResultContent opened = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .findFirst()
                .orElseThrow();
        var evidence = new ObjectMapper().readTree(opened.resultJson()).get("evidence");
        assertThat(evidence.size()).isEqualTo(3);
        assertThat(evidence.get(0).get("text").asText()).contains("Use Google search.");
        assertThat(evidence.get(1).get("text").asText()).contains("Pick a small business.");
        assertThat(evidence.get(2).get("text").asText()).contains("Quarter 4 body.");
        assertThat(opened.resultJson())
                .contains("\"readScope\":\"TABLE\"");
        var finalizationPayload = new ObjectMapper().readTree(
                gateway.finalizationRequest.userPayload()
        );
        assertThat(finalizationPayload.get("evidencePackages")).singleElement()
                .satisfies(item -> {
                    assertThat(item.get("readScope").asText()).isEqualTo("PAGE");
                    assertThat(item.get("evidence").toString())
                            .contains(
                                    "Use Google search.",
                                    "Pick a small business.",
                                    "Quarter 4 body."
                            );
                });
    }

    @Test
    void searchReturnsOnlyTheMatchedStructuredTableItem() {
        Fixture fixture = pdfTableFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                answered("Use Google search.", "E1")
        );

        service(fixture, gateway, new StatefulIdempotency()).answer(
                "Bearer token", "pdf-table-search-key", 21L,
                request("Preparing for Tomorrow's Workplace Skills", "HYBRID", 1)
        );

        AnswerChatGateway.UserContent initial = gateway.messages.get(0).stream()
                .filter(AnswerChatGateway.UserContent.class::isInstance)
                .map(AnswerChatGateway.UserContent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(initial.text())
                .contains("Use Google search.", "\"tableColumnHeader\":\"QUARTER 3:\"")
                .doesNotContain(
                        "Pick a small business.",
                        "Pick a specific product.",
                        "Quarter 4 body."
                );
    }

    @Test
    void searchReturnsOnlyDirectHitAndOpenEvidenceReadsItsCanonicalPage() {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                toolTurn("c2", "search", "{\"query\":\"complete page verification\"}"),
                answered("三项要求齐全。", "E2")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "evidence-page-key", 21L,
                request("完整页面要求", "HYBRID", 1)
        );

        assertThat(response.getCitations()).singleElement().satisfies(citation ->
                assertThat(citation.getText())
                        .contains("First item", "Second item", "Third item"));
        AnswerChatGateway.UserContent initial = gateway.messages.get(0).stream()
                .filter(AnswerChatGateway.UserContent.class::isInstance)
                .map(AnswerChatGateway.UserContent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(initial.text())
                .contains("First item")
                .doesNotContain("Second item", "Third item");
        AnswerChatGateway.ToolResultContent opened = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .findFirst()
                .orElseThrow();
        assertThat(opened.resultJson())
                .contains("\"readScope\":\"EVIDENCE_PAGE\"",
                        "First item", "Second item", "Third item");
    }

    @Test
    void openEvidenceReturnsOpaqueAdjacentPageCursorsAndReadsNextPage() {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                toolTurn("c2", "open", "{\"ref\":\"R3\"}"),
                answeredWithReadRefs("Final note", List.of("E3"), List.of("R3"))
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "adjacent-page-key", 21L,
                request("读取下一页", "HYBRID", 1)
        );

        assertThat(response.getAnswer()).isEqualTo("Final note\n\n参考依据：[1]");
        assertThat(response.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getPageNumber()).isEqualTo(3);
            assertThat(citation.getText()).isEqualTo("Final note");
        });
        List<AnswerChatGateway.ToolResultContent> opened = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .distinct()
                .toList();
        assertThat(opened).hasSize(2);
        assertThat(opened.get(0).resultJson())
                .contains("\"pageNumber\":2", "\"readRef\":\"R1\"",
                        "\"previousRef\":\"R2\"", "\"nextRef\":\"R3\"");
        assertThat(opened.get(1).resultJson())
                .contains("\"readRef\":\"R3\"", "\"readScope\":\"ADJACENT_PAGE\"",
                        "\"pageNumber\":3", "Final note");
    }

    @Test
    void openEvidenceNavigatesAdjacentCanonicalWindowWithoutPageNumbers() {
        Fixture fixture = markdownWindowFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                toolTurn("c2", "open", "{\"ref\":\"R3\"}"),
                answeredWithReadRefs(
                        "Blocks 8 through 11", List.of("E3"), List.of("R3")
                )
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "adjacent-window-key", 21L,
                request("读取后续正文", "HYBRID", 1)
        );

        assertThat(response.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getPageNumber()).isNull();
            assertThat(citation.getText()).contains("Block 8", "Block 11");
        });
        List<AnswerChatGateway.ToolResultContent> opened = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .distinct()
                .toList();
        assertThat(opened).hasSize(2);
        assertThat(opened.get(0).resultJson())
                .contains("\"readRef\":\"R1\"", "\"previousRef\":\"R2\"",
                        "\"nextRef\":\"R3\"");
        assertThat(opened.get(1).resultJson())
                .contains("\"readScope\":\"ADJACENT_CANONICAL_WINDOW\"",
                        "Block 8", "Block 11");
    }

    @Test
    void isolatedFinalAnswerPassReceivesOnlyQuestionAndAgentSelectedEvidence() throws Exception {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                answeredWithReadRefs("First item only", List.of("E1"), List.of("R1"))
        ).withFinalization(answered("三项要求齐全。", "E1").text());

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "isolated-final-answer-key", 21L,
                request("列出全部三项要求", "HYBRID", 1)
        );

        assertThat(response.getAnswer()).isEqualTo("三项要求齐全。\n\n参考依据：[1]");
        assertThat(gateway.finalizationRequest).isNotNull();
        var payload = new ObjectMapper().readTree(
                gateway.finalizationRequest.userPayload()
        );
        assertThat(payload.get("question").asText()).isEqualTo("列出全部三项要求");
        assertThat(payload.get("evidencePackages")).singleElement().satisfies(item -> {
            assertThat(item.get("readRef").asText()).isEqualTo("R1");
            assertThat(item.get("anchorEvidenceIds")).singleElement().satisfies(anchor ->
                    assertThat(anchor.asText()).isEqualTo("E1")
            );
            assertThat(item.get("evidence").toString())
                    .contains("First item", "Second item", "Third item");
        });
        assertThat(payload.has("agentDraft")).isFalse();
        assertThat(payload.has("initialSearch")).isFalse();
        assertThat(gateway.finalizationRequest.systemPrompt())
                .contains("isolated final answer pass");
    }

    @Test
    void shortSubmittedEvidenceAutomaticallyExpandsToItsCompletePage() throws Exception {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                answered("First item only", "E1")
        ).withFinalization(answered("三项要求齐全。", "E1").text());

        service(fixture, gateway, new StatefulIdempotency()).answer(
                "Bearer token", "automatic-page-package-key", 21L,
                request("列出全部三项要求", "HYBRID", 1)
        );

        var payload = new ObjectMapper().readTree(
                gateway.finalizationRequest.userPayload()
        );
        assertThat(payload.get("evidencePackages")).singleElement().satisfies(item -> {
            assertThat(item.get("readScope").asText()).isEqualTo("PAGE");
            assertThat(item.get("pageNumber").asInt()).isEqualTo(2);
            assertThat(item.get("evidence").toString())
                    .contains("First item", "Second item", "Third item")
                    .doesNotContain("Final note");
        });
    }

    @Test
    void completePageUsesIndependentFinalizationBudgetAtItsExactLimit() {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                answered("First item only", "E1")
        ).withFinalization(answered("全部三项。", "E2").text());
        AnswerProperties properties = new AnswerProperties();
        properties.setMaxCanonicalSourceTokens(3); // The initial ten-character anchor fills it.
        properties.setMaxFinalizationSourceTokens(12); // Anchor 3 + complete page 9.

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency(), Optional.empty(), properties
        ).answer(
                "Bearer token", "independent-finalization-budget-key", 21L,
                request("列出全部三项要求", "HYBRID", 1)
        );

        var payload = new ObjectMapper().readTree(gateway.finalizationRequest.userPayload());
        assertThat(payload.get("budgetExhausted").asBoolean()).isFalse();
        assertThat(payload.get("evidencePackages")).singleElement().satisfies(item -> {
            assertThat(item.get("readScope").asText()).isEqualTo("PAGE");
            assertThat(item.get("truncated").asBoolean()).isFalse();
            assertThat(item.get("evidence").toString())
                    .contains("First item", "Second item", "Third item");
        });
        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getCitations()).singleElement().satisfies(citation ->
                assertThat(citation.getText())
                        .isEqualTo("First item\n\nSecond item\n\nThird item")
        );
    }

    @Test
    void finalizationRejectsWholePackageOneTokenOverBudgetAndCannotCiteItsAnchor() {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                answered("First item only", "E1")
        ).withFinalization(answered("不完整答案。", "E1").text());
        AnswerProperties properties = new AnswerProperties();
        properties.setMaxCanonicalSourceTokens(3);
        properties.setMaxFinalizationSourceTokens(11);
        QueryExecutionTelemetry telemetry = new QueryExecutionTelemetry();

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency(), Optional.empty(), properties
        ).answer(
                "Bearer token", "over-finalization-budget-key", 21L,
                request("列出全部三项要求", "HYBRID", 1), telemetry
        );

        var payload = new ObjectMapper().readTree(gateway.finalizationRequest.userPayload());
        assertThat(payload.get("budgetExhausted").asBoolean()).isTrue();
        assertThat(payload.get("evidencePackages")).isEmpty();
        assertThat(response.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitations()).isEmpty();
        assertThat(telemetry.getCanonicalCharacters()).isEqualTo("First item".length());
    }

    @Test
    void finalizationFallsBackToMarkedNeighborhoodWithoutRegisteringRejectedFullPage() {
        Fixture fixture = pdfSectionFixture(
                List.of("Section", "Item 1", "Item 2", "Item 3", "Item 4",
                        "Item 5", "Item 6", "Item 7", "Item 8"),
                new int[]{1, 2, 2, 2, 2, 2, 2, 2, 2}
        );
        ScriptedGateway gateway = new ScriptedGateway(
                answered("Item 1 only", "E1")
        ).withFinalization(answered("第一项及相邻内容。", "E2").text());
        AnswerProperties properties = new AnswerProperties();
        properties.setMaxCanonicalSourceTokens(2);
        properties.setMaxFinalizationSourceTokens(10); // Anchor 2 + four-item neighborhood 8.
        QueryExecutionTelemetry telemetry = new QueryExecutionTelemetry();

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency(), Optional.empty(), properties
        ).answer(
                "Bearer token", "finalization-neighborhood-budget-key", 21L,
                request("第一项附近有哪些内容？", "HYBRID", 1), telemetry
        );

        var payload = new ObjectMapper().readTree(gateway.finalizationRequest.userPayload());
        assertThat(payload.get("budgetExhausted").asBoolean()).isTrue();
        assertThat(payload.get("evidencePackages")).singleElement().satisfies(item -> {
            assertThat(item.get("readScope").asText()).isEqualTo("ANCHOR_NEIGHBORHOOD");
            assertThat(item.get("readRef").isNull()).isTrue();
            assertThat(item.get("truncated").asBoolean()).isTrue();
            assertThat(item.get("evidence").toString())
                    .contains("Item 1", "Item 2", "Item 3", "Item 4")
                    .doesNotContain("Item 5", "Item 8");
        });
        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getCitations()).singleElement().satisfies(citation ->
                assertThat(citation.getText())
                        .isEqualTo("Item 1\n\nItem 2\n\nItem 3\n\nItem 4")
        );
        assertThat(telemetry.getCanonicalCharacters()).isEqualTo(36);
    }

    @Test
    void disjointFinalizationNeighborhoodsCannotReuseAnAlreadyRegisteredFullPage() {
        List<String> texts = new ArrayList<>(List.of("Section"));
        for (int item = 1; item <= 20; item++) {
            texts.add("Item %02d".formatted(item));
        }
        int[] pages = new int[texts.size()];
        java.util.Arrays.fill(pages, 2);
        pages[0] = 1;
        Fixture fixture = pdfSectionFixture(texts, pages);
        EvidenceBlock last = fixture.retrieval.document.canonical().blocks().get(20);
        RetrieveResponseVO.Result initial = fixture.retrieval.result.getResults().get(0);
        initial.setEvidence(List.of(
                initial.getEvidence().get(0),
                new RetrieveResponseVO.Evidence(
                        last.blockId(), last.kind().name(), last.text(), false,
                        last.canonicalStart(), last.canonicalEnd(),
                        new RetrieveResponseVO.SourcePosition(
                                "PDF", 2, 21, 0, last.text().length(),
                                null, null, null, null, null, null, null, null
                        ),
                        List.of()
                )
        ));
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                answered("页首和页尾。", "E1", "E2")
        ).withFinalization(answered("页首和页尾附近的内容。", "E4", "E5").text());
        AnswerProperties properties = new AnswerProperties();
        properties.setMaxCanonicalSourceTokens(64); // Retrieval can register the full page as E3.
        properties.setMaxFinalizationSourceTokens(22); // Two anchors 4 + two neighborhoods 18.

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency(), Optional.empty(), properties
        ).answer(
                "Bearer token", "disjoint-finalization-neighborhoods-key", 21L,
                request("页首和页尾附近有哪些内容？", "HYBRID", 1)
        );

        assertThat(gateway.messages.get(1).stream()
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .map(AnswerChatGateway.ToolResultContent::resultJson))
                .singleElement().asString().contains("\"evidenceId\":\"E3\"", "Item 10");
        var payload = new ObjectMapper().readTree(gateway.finalizationRequest.userPayload());
        assertThat(payload.get("budgetExhausted").asBoolean()).isTrue();
        assertThat(payload.get("evidencePackages")).singleElement().satisfies(item -> {
            assertThat(item.get("readScope").asText()).isEqualTo("ANCHOR_NEIGHBORHOOD");
            assertThat(item.get("truncated").asBoolean()).isTrue();
            assertThat(item.get("evidence")).hasSize(4);
            int transmittedTokens = 0;
            for (var evidence : item.get("evidence")) {
                transmittedTokens += (evidence.get("text").asText().length() + 3) / 4;
            }
            assertThat(transmittedTokens).isEqualTo(properties.getMaxFinalizationSourceTokens());
            assertThat(item.get("evidence").toString())
                    .contains("Item 01", "Item 04", "Item 17", "Item 20")
                    .doesNotContain("Item 05", "Item 10", "Item 16");
        });
        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getCitations()).extracting(AnswerResponseVO.Citation::getText)
                .containsExactly("Item 01\n\nItem 02\n\nItem 03\n\nItem 04",
                        "Item 17\n\nItem 18\n\nItem 19\n\nItem 20");
    }

    @Test
    void finalizerCannotCiteRegisteredEvidenceOutsideTheHandoffPackages() {
        Fixture fixture = pdfSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"E1\"}"),
                toolTurn("c2", "open", "{\"ref\":\"R3\"}"),
                answered("First item only", "E1")
        ).withFinalization(answered("越权引用下一页。", "E5").text());

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "finalizer-handoff-boundary-key", 21L,
                request("列出第二页要求", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitations()).isEmpty();
    }

    @Test
    void searchPreservesRetrievalOrderAndContinuesWithOpaqueCursorWithoutAnotherRetrieve() {
        Fixture fixture = multiPageRetrievalFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn(
                        "c1",
                        "search",
                        "{\"query\":\"重复标题\",\"cursor\":\"C1\"}"
                ),
                answered("第三页答案。", "E4")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "cursor-key", 21L,
                request("重复标题", "HYBRID", 2)
        );

        assertThat(response.getAnswer()).isEqualTo("第三页答案。\n\n参考依据：[1]");
        assertThat(response.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getCitationIndex()).isEqualTo(1);
            assertThat(citation.getPageNumber()).isEqualTo(3);
        });
        assertThat(fixture.retrieval.calls).isOne();
        assertThat(fixture.retrieval.answerCalls).isOne();
        AnswerChatGateway.UserContent initial = gateway.messages.get(0).stream()
                .filter(AnswerChatGateway.UserContent.class::isInstance)
                .map(AnswerChatGateway.UserContent.class::cast)
                .findFirst()
                .orElseThrow();
        var initialJson = new ObjectMapper().readTree(initial.text()).get("initialSearch");
        assertThat(initialJson.get("nextCursor").asText()).isEqualTo("C1");
        assertThat(initialJson.get("coverage").get("candidateCount").asInt()).isEqualTo(2);
        assertThat(initialJson.get("coverage").get("sectionFamilyCount").asInt())
                .isOne();
        assertThat(initialJson.get("coverage").get("concentrated").asBoolean()).isFalse();
        assertThat(initialJson.get("candidates"))
                .extracting(candidate -> candidate.get("evidence").get(0)
                        .get("pageNumber").asInt())
                .containsExactly(1, 1);
        AnswerChatGateway.ToolResultContent continued = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("search"))
                .findFirst()
                .orElseThrow();
        var continuedJson = new ObjectMapper().readTree(continued.resultJson());
        assertThat(continuedJson.get("nextCursor").isNull()).isTrue();
        assertThat(continuedJson.get("coverage").get("pageCount").asInt()).isEqualTo(2);
        assertThat(continuedJson.get("candidates"))
                .extracting(candidate -> candidate.get("evidence").get(0)
                        .get("pageNumber").asInt())
                .containsExactly(2, 3);
    }

    @Test
    void searchReranksTheCandidatePoolBeforePagingAndReusesThatOrderForCursor() {
        Fixture fixture = multiPageRetrievalFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn(
                        "c1",
                        "search",
                        "{\"query\":\"重复标题\",\"cursor\":\"C1\"}"
                ),
                answered("第一页答案。", "E4")
        );
        AnswerProperties properties = new AnswerProperties();
        properties.getRerank().setEnabled(true);
        List<String> rerankDocuments = new ArrayList<>();
        SearchRerankGateway reranker = (query, documents) -> {
            assertThat(query).isEqualTo("重复标题");
            rerankDocuments.addAll(documents);
            return List.of(
                    new SearchRerankGateway.Score(0, 0.1),
                    new SearchRerankGateway.Score(1, 0.2),
                    new SearchRerankGateway.Score(2, 0.8),
                    new SearchRerankGateway.Score(3, 0.9)
            );
        };

        AnswerResponseVO response = service(
                fixture,
                gateway,
                new StatefulIdempotency(),
                Optional.of(reranker),
                properties
        ).answer(
                "Bearer token", "rerank-cursor-key", 21L,
                request("重复标题", "HYBRID", 2)
        );

        assertThat(response.getCitations()).singleElement().satisfies(citation ->
                assertThat(citation.getPageNumber()).isEqualTo(1));
        assertThat(rerankDocuments).hasSize(4);
        assertThat(fixture.retrieval.lastTopK).isEqualTo(20);
        AnswerChatGateway.UserContent initial = gateway.messages.get(0).stream()
                .filter(AnswerChatGateway.UserContent.class::isInstance)
                .map(AnswerChatGateway.UserContent.class::cast)
                .findFirst()
                .orElseThrow();
        var initialJson = new ObjectMapper().readTree(initial.text()).get("initialSearch");
        assertThat(initialJson.get("candidates"))
                .extracting(candidate -> candidate.get("evidence").get(0)
                        .get("pageNumber").asInt())
                .containsExactly(3, 2);
        AnswerChatGateway.ToolResultContent continued = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("search"))
                .findFirst()
                .orElseThrow();
        var continuedJson = new ObjectMapper().readTree(continued.resultJson());
        assertThat(continuedJson.get("candidates"))
                .extracting(candidate -> candidate.get("evidence").get(0)
                        .get("pageNumber").asInt())
                .containsExactly(1, 1);
    }

    @Test
    void searchFallsBackToRetrievalOrderWhenRerankIsUnavailable() {
        Fixture fixture = multiPageRetrievalFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                answered("原始首条。", "E1")
        );
        AnswerProperties properties = new AnswerProperties();
        properties.getRerank().setEnabled(true);
        SearchRerankGateway unavailable = (query, documents) -> {
            throw new IllegalStateException("stub unavailable");
        };

        AnswerResponseVO response = service(
                fixture,
                gateway,
                new StatefulIdempotency(),
                Optional.of(unavailable),
                properties
        ).answer(
                "Bearer token", "rerank-fallback-key", 21L,
                request("重复标题", "HYBRID", 2)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getCitations()).singleElement().satisfies(citation ->
                assertThat(citation.getPageNumber()).isEqualTo(1));
    }

    @Test
    void parentHeadingReadsOnlyDirectTextAndReturnsChildSectionRefs() {
        Fixture fixture = parentSectionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"D1\"}"),
                toolTurn("c2", "open", "{\"ref\":\"S1\"}"),
                toolTurn("c3", "search", "{\"query\":\"父章节范围验证\"}"),
                answered("父章节说明。", "E1")
        );

        service(fixture, gateway, new StatefulIdempotency()).answer(
                "Bearer token", "parent-section-key", 21L,
                request("父章节说明", "HYBRID", 1)
        );

        AnswerChatGateway.ToolResultContent readResult = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .filter(result -> result.resultJson().contains("Parent introduction"))
                .findFirst()
                .orElseThrow();
        assertThat(readResult.resultJson())
                .contains("Parent introduction", "Child Section", "\"sectionRef\":\"S3\"",
                        "\"readScope\":\"MINIMAL_REAL_HEADING\"")
                .doesNotContain("Child-only answer");
    }

    @Test
    void navigationSectionRefReadsOnlyExactSubpartitionRange() {
        Fixture fixture = navigationPartitionFixture();
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"S1\"}"),
                toolTurn("c2", "search", "{\"query\":\"中间三项验证\"}"),
                answered("三项齐全。", "E4")
        );

        service(fixture, gateway, new StatefulIdempotency()).answer(
                "Bearer token", "partition-section-key", 21L,
                request("中间三项", "HYBRID", 1)
        );

        AnswerChatGateway.ToolResultContent readResult = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("open"))
                .findFirst()
                .orElseThrow();
        assertThat(readResult.resultJson())
                .contains("First item", "Second item", "Third item",
                        "\"readScope\":\"NAVIGATION_SUBPARTITION\"")
                .doesNotContain("\"text\":\"Complete Section\"", "Final note");
    }

    @Test
    void snapshotRejectsForeignDocumentAndLetsModelCorrectRepeatedInvalidArguments() {
        Fixture fixture = fixture(false);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"S999\"}"),
                toolTurn("c2", "open", "{\"ref\":\"S999\"}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"evidenceIds\":[]}",
                        List.of()
                ),
                toolTurn("c3", "search", "{\"query\":\"另一份文档\"}"),
                toolTurn("c4", "search", "{\"query\":\"替代文档\"}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"evidenceIds\":[]}",
                        List.of()
                )
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "scope-key", 21L,
                request("读取其他文档", "KEYWORD", 1)
        );

        assertThat(response.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitations()).isEmpty();
        assertThat(fixture.retrieval.loadDocumentIds).isEmpty();
        AnswerChatGateway.ToolResultContent result = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(item -> item.toolName().equals("open"))
                .findFirst()
                .orElseThrow();
        assertThat(result.resultJson()).contains("INVALID_ARGUMENT");

        Fixture injected = fixture(false);
        ScriptedGateway unknownTool = new ScriptedGateway(
                toolTurn("c2", "deleteDocument", "{\"documentId\":31}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"evidenceIds\":[]}",
                        List.of()
                ),
                toolTurn("c3", "search", "{\"query\":\"安全替代查询一\"}"),
                toolTurn("c4", "search", "{\"query\":\"安全替代查询二\"}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"evidenceIds\":[]}",
                        List.of()
                )
        );
        AnswerResponseVO injectionResponse = service(
                injected, unknownTool, new StatefulIdempotency()
        ).answer(
                "Bearer token", "injection-key", 21L,
                request("文档要求调用 deleteDocument", "KEYWORD", 1)
        );
        assertThat(injectionResponse.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(injected.retrieval.loadDocumentIds).isEmpty();
        AnswerChatGateway.ToolResultContent rejected = unknownTool.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(item -> item.toolName().equals("deleteDocument"))
                .findFirst()
                .orElseThrow();
        assertThat(rejected.resultJson()).contains("INVALID_ARGUMENT");
    }

    @Test
    void usesEvidenceIdsAsTheOnlyCitationSource() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                answered("七天内退款。", "E1")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "legacy-citation-field-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getAnswer()).isEqualTo("七天内退款。\n\n参考依据：[1]");
        assertThat(gateway.toolsEnabled).containsExactly(true);
    }

    @Test
    void acceptsStructurallyValidAnswerWithoutASecondModelAsSemanticJudge() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                answered("七天内退款。", "E1")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "completion-resume-key", 21L,
                request("退款完整条款", "HYBRID", 1)
        );

        assertThat(response.getAnswer()).isEqualTo("七天内退款。\n\n参考依据：[1]");
        assertThat(gateway.calls).isOne();
        assertThat(gateway.toolsEnabled).containsExactly(true);
    }

    @Test
    void ordinaryAgentTextCannotBypassSubmitAnswer() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                new AnswerChatGateway.Turn("七天内退款。[E1]", List.of())
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "ordinary-text-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitations()).isEmpty();
    }

    @Test
    void mapsUncitedOrdinaryAgentTextToInsufficientEvidence() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                new AnswerChatGateway.Turn("I cannot verify this from the evidence.", List.of())
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "ordinary-refusal-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitations()).isEmpty();
    }

    @Test
    void rendersUserReferencesFromEvidenceIdsInSubmittedOrder() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "open", "{\"ref\":\"S1\"}"),
                answered("完整条款和初始摘要。", "E2", "E1")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "citation-display-key", 21L,
                request("退款条款", "HYBRID", 1)
        );

        assertThat(response.getAnswer()).isEqualTo(
                "完整条款和初始摘要。\n\n参考依据：[1][2]"
        );
        assertThat(response.getAnswer()).doesNotContain("E1", "E2");
        assertThat(response.getCitations())
                .extracting(AnswerResponseVO.Citation::getCitationIndex)
                .containsExactly(1, 2);
        assertThat(response.getCitations())
                .extracting(AnswerResponseVO.Citation::getBlockId)
                .containsExactly("b-0", "b-1");
    }

    @Test
    void mapsAnswerWithOnlyUnknownEvidenceToInsufficientEvidence() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                invalidUnknownCitation()
        );
        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "unknown-only-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitations()).isEmpty();
    }

    @Test
    void dropsUnknownEvidenceIdsButKeepsRegisteredCitations() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                answered("七天内退款。", "E1", "E99")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "mixed-evidence-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getAnswer()).isEqualTo("七天内退款。\n\n参考依据：[1]");
        assertThat(response.getCitations()).hasSize(1);
    }

    @Test
    void removesSingleAndCombinedInternalMarkersWithoutUsingThemAsCitations() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                answered("七天内退款。[E1] 补充说明。[E1, E99]", "E1", "E1", "E99")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "combined-marker-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getAnswer())
                .isEqualTo("七天内退款。 补充说明。\n\n参考依据：[1]")
                .doesNotContain("E1", "E99");
        assertThat(response.getCitations()).hasSize(1);
    }

    @Test
    void executesMultipleToolsFromOneModelResponseSequentially() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                new AnswerChatGateway.Turn(
                        null,
                        List.of(
                                new AnswerChatGateway.ToolCall(
                                        "c1", "search", "{\"query\":\"a\"}"
                                ),
                                new AnswerChatGateway.ToolCall(
                                        "c2", "search", "{\"query\":\"b\"}"
                                ),
                                new AnswerChatGateway.ToolCall(
                                        "c3", "search", "{\"query\":\"c\"}"
                                )
                        )
                ),
                answered("有依据。", "E1")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "multi-tool-key", 21L,
                request("test", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(fixture.retrieval.calls).isEqualTo(4);
    }

    @Test
    void modelCanRewriteSearchBeyondThreeRoundsAndCiteOneMultilineParagraph() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "search", "{\"query\":\"退款\"}"),
                toolTurn("c2", "search", "{\"query\":\"退款期限\"}"),
                toolTurn("c3", "search", "{\"query\":\"七天退款规则\"}"),
                toolTurn("c4", "search", "{\"query\":\"refund period\"}"),
                answered("退款规则如下：\n七天内可以退款。", "E1")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "rewrite-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(fixture.retrieval.calls).isEqualTo(4);
        assertThat(gateway.calls).isEqualTo(5);
    }

    @Test
    void reusesSameToolResultEvenWhenJsonFieldOrderDiffers() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "search", "{\"query\":\"退款\",\"documentRef\":\"D1\"}"),
                toolTurn("c2", "search", "{\"documentRef\":\"D1\",\"query\":\"退款\"}"),
                answered("七天内退款。", "E1")
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "tool-cache-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(fixture.retrieval.calls).isEqualTo(2);
        List<AnswerChatGateway.ToolResultContent> results = gateway.messages.stream()
                .flatMap(List::stream)
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("search"))
                .toList();
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(results.size() - 1).resultJson())
                .isEqualTo(results.get(results.size() - 2).resultJson());
    }

    @Test
    void acceptsListWhenAtLeastOneSupportingCitationIsValid() {
        Fixture fixture = fixture(true);
        AnswerChatGateway.Turn invalid = answered(
                "- 七天内退款。\n- 需要原始凭证。", "E1"
        );
        ScriptedGateway gateway = new ScriptedGateway(invalid);

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "list-citation-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getAnswer()).isEqualTo(
                "- 七天内退款。\n- 需要原始凭证。\n\n参考依据：[1]"
        );
        assertThat(gateway.toolsEnabled).containsExactly(true);
    }

    @Test
    void preservesCitationPositionsForAllFourAcceptedDocumentFormats() {
        List<RetrieveResponseVO.SourcePosition> positions = List.of(
                new RetrieveResponseVO.SourcePosition(
                        "PDF", 2, 1, 10, 48,
                        null, null, null, null,
                        null, null, null, null
                ),
                new RetrieveResponseVO.SourcePosition(
                        "DOCX", null, null, null, null,
                        4, 1, 2, 0,
                        null, null, null, null
                ),
                new RetrieveResponseVO.SourcePosition(
                        "TXT", null, null, null, null,
                        null, null, null, null,
                        3, null, 4, null
                ),
                new RetrieveResponseVO.SourcePosition(
                        "MARKDOWN", null, null, null, null,
                        null, null, null, null,
                        3, 1, 3, 40
                )
        );

        for (RetrieveResponseVO.SourcePosition position : positions) {
            Fixture fixture = fixture(true, position);
            ScriptedGateway gateway = new ScriptedGateway(
                    toolTurn("c1", "search", "{\"query\":\"fact verification\"}"),
                    answered("fact", "E1")
            );
            AnswerResponseVO response = service(
                    fixture, gateway, new StatefulIdempotency()
            ).answer(
                    "Bearer token", "format-" + position.getSourceType(), 21L,
                    request("fact", "KEYWORD", 1)
            );
            assertThat(response.getCitations()).singleElement().satisfies(citation ->
                    assertThat(citation.getSourcePosition())
                            .usingRecursiveComparison()
                            .isEqualTo(position));
        }
    }

    @Test
    void internalReadTargetNeverSerializesIntoExternalRetrieveResponse() throws Exception {
        Fixture base = pdfSectionFixture();
        EvidenceBlock body = base.retrieval.document.canonical().blocks().get(1);
        RetrieveResponseVO response = retrievalWithEvidence(
                body,
                new RetrieveResponseVO.SourcePosition(
                        "PDF", 2, 2, 0, body.text().length(),
                        null, null, null, null,
                        null, null, null, null
                ),
                new RetrieveResponseVO.InternalReadTarget(
                        "private-card", "HEADING_SUBPARTITION", 1, 4
                )
        );

        assertThat(new ObjectMapper().writeValueAsString(response))
                .doesNotContain("internalReadTarget", "private-card",
                        "sectionStartBlockOrdinal", "sectionEndBlockOrdinalExclusive");
    }

    private AnswerServiceImpl service(
            Fixture fixture,
            ScriptedGateway gateway,
            StatefulIdempotency idempotency
    ) {
        return service(
                fixture,
                gateway,
                idempotency,
                Optional.empty(),
                new AnswerProperties()
        );
    }

    private AnswerServiceImpl service(
            Fixture fixture,
            ScriptedGateway gateway,
            StatefulIdempotency idempotency,
            Optional<SearchRerankGateway> reranker,
            AnswerProperties properties
    ) {
        QueryAccessService access = (header, knowledgeBaseId) -> fixture.context;
        return new AnswerServiceImpl(
                access,
                idempotency,
                fixture.retrieval,
                Optional.of(gateway),
                reranker,
                properties,
                new RetrieveProperties(),
                new ObjectMapper()
        );
    }

    private Fixture fixture(boolean initialEvidence) {
        return fixture(initialEvidence, new RetrieveResponseVO.SourcePosition(
                "MARKDOWN", null, null, null, null,
                null, null, null, null, 3, 1, 3, 40
        ));
    }

    private Fixture fixture(
            boolean initialEvidence,
            RetrieveResponseVO.SourcePosition sourcePosition
    ) {
        String headingText = "Refund Policy";
        String bodyText = "Refunds are allowed within seven days.";
        EvidenceBlock heading = new EvidenceBlock(
                "b-0", 0, BlockKind.HEADING, headingText,
                0, headingText.length(), "active-section", null,
                SourcePosition.markdown(1, 1, 1, headingText.length())
        );
        long bodyStart = heading.canonicalEnd() + 2;
        EvidenceBlock body = new EvidenceBlock(
                "b-1", 1, BlockKind.PARAGRAPH, bodyText,
                bodyStart, bodyStart + bodyText.length(), "active-section", null,
                SourcePosition.markdown(3, 1, 3, bodyText.length())
        );
        ActiveDocumentVersionSnapshot version = new ActiveDocumentVersionSnapshot(
                31L, 41L, 1, "Manual.md"
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 11L, 10L, 21L, "1", List.of(version), "a".repeat(64)
        );
        CanonicalDocument canonical = new CanonicalDocument(
                1, 41L, "MARKDOWN", "b".repeat(64), "markdown", "1",
                Instant.parse("2026-08-11T00:00:00Z"),
                List.of(heading, body),
                List.of(
                        new HeadingNode(
                                "root", null, 0, 0, "Manual", null,
                                "DOCUMENT_ROOT", 0, 2
                        ),
                        new HeadingNode(
                                "active-section", "root", 1, 1, headingText,
                                heading.blockId(), "MARKDOWN", 0, 2
                        )
                ),
                List.of(), null,
                headingText.length() + 2L + bodyText.length(), "c".repeat(64)
        );
        RetrieveResponseVO retrieval = initialEvidence
                ? retrievalWithEvidence(body, sourcePosition)
                : emptyRetrieval();
        FakeRetrieval scoped = new FakeRetrieval(
                retrieval,
                new ScopedRetrievalService.ScopedDocument(version, canonical)
        );
        return new Fixture(context, scoped);
    }

    private Fixture pdfSectionFixture() {
        return pdfSectionFixture(
                List.of("Complete Section", "First item", "Second item", "Third item",
                        "Final note"),
                new int[]{1, 2, 2, 2, 3}
        );
    }

    private Fixture pdfSectionFixture(List<String> texts, int[] pages) {
        List<EvidenceBlock> blocks = new ArrayList<>();
        Map<Integer, Integer> pageBlockOrdinals = new java.util.HashMap<>();
        long offset = 0;
        for (int index = 0; index < texts.size(); index++) {
            String value = texts.get(index);
            String blockId = index == 0 ? "p1-heading"
                    : "p" + pages[index] + "-" + pageBlockOrdinals.merge(pages[index], 1, Integer::sum);
            blocks.add(new EvidenceBlock(
                    blockId, index,
                    index == 0 ? BlockKind.HEADING : BlockKind.PARAGRAPH,
                    value, offset, offset + value.length(), "active-section", null,
                    SourcePosition.pdf(pages[index], index + 1, 0, value.length())
            ));
            offset += value.length() + 2;
        }
        ActiveDocumentVersionSnapshot version = new ActiveDocumentVersionSnapshot(
                31L, 41L, 1, "Manual.pdf"
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 11L, 10L, 21L, "1", List.of(version), "a".repeat(64)
        );
        CanonicalDocument canonical = new CanonicalDocument(
                1, 41L, "PDF", "b".repeat(64), "deepdoc", "1",
                Instant.parse("2026-08-11T00:00:00Z"), blocks,
                List.of(
                        new HeadingNode(
                                "root", null, 0, 0, "Manual", null,
                                "DOCUMENT_ROOT", 0, blocks.size()
                        ),
                        new HeadingNode(
                                "active-section", "root", 1, 1, texts.get(0), blocks.get(0).blockId(),
                                "PDF", 0, blocks.size()
                        )
                ),
                List.of(), null, offset, "c".repeat(64)
        );
        EvidenceBlock firstItem = blocks.get(1);
        FakeRetrieval scoped = new FakeRetrieval(
                retrievalWithEvidence(
                        firstItem,
                        new RetrieveResponseVO.SourcePosition(
                                "PDF", 2, 2, 0, firstItem.text().length(),
                                null, null, null, null,
                                null, null, null, null
                        ),
                        null,
                        "Manual.pdf",
                        "active-section",
                        List.of("Manual", "Complete Section")
                ),
                new ScopedRetrievalService.ScopedDocument(version, canonical)
        );
        return new Fixture(context, scoped);
    }

    private Fixture pdfTableFixture() {
        List<String> texts = List.of(
                "QUARTER 3:\nPreparing for Tomorrow's Workplace Skills\n"
                        + "Use Google search.",
                "QUARTER 3:\nPreparing for Tomorrow's Workplace Skills\n"
                        + "Pick a small business.",
                "QUARTER 4:\nQuarter 4 body."
        );
        List<EvidenceBlock> blocks = new ArrayList<>();
        long offset = 0;
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            boolean quarterThree = index < 2;
            SourcePosition position = SourcePosition.pdfTable(
                    16, index, 0, text.length(), 1, quarterThree ? 0 : 1, 1, 1,
                    "pdf:p16:t0", quarterThree ? "QUARTER 3:" : "QUARTER 4:",
                    null, null, null, "pdf:p16:t0", null
            );
            blocks.add(new EvidenceBlock(
                    "table-" + index,
                    index,
                    BlockKind.TABLE_CELL,
                    text,
                    offset,
                    offset + text.length(),
                    "root",
                    null,
                    position
            ));
            offset += text.length() + 2;
        }
        ActiveDocumentVersionSnapshot version = new ActiveDocumentVersionSnapshot(
                31L, 41L, 1, "Manual.pdf"
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 11L, 10L, 21L, "1", List.of(version), "a".repeat(64)
        );
        CanonicalDocument canonical = new CanonicalDocument(
                1, 41L, "PDF", "b".repeat(64), "deepdoc", "1",
                Instant.parse("2026-08-11T00:00:00Z"), blocks,
                List.of(new HeadingNode(
                        "root", null, 0, 0, "Manual", null,
                        "DOCUMENT_ROOT", 0, blocks.size()
                )),
                List.of(), 16, offset, "c".repeat(64)
        );
        EvidenceBlock anchor = blocks.get(0);
        FakeRetrieval scoped = new FakeRetrieval(
                retrievalWithEvidence(
                        anchor,
                        new RetrieveResponseVO.SourcePosition(
                                "PDF", 16, 0, 0, anchor.text().length(),
                                null, 1, 0, null,
                                null, null, null, null,
                                "pdf:p16:t0", 1, 1, "QUARTER 3:"
                        ),
                        null,
                        "Manual.pdf",
                        "root",
                        List.of("Manual")
                ),
                new ScopedRetrievalService.ScopedDocument(version, canonical)
        );
        return new Fixture(context, scoped);
    }

    private Fixture markdownWindowFixture() {
        List<EvidenceBlock> blocks = new ArrayList<>();
        long offset = 0;
        for (int index = 0; index < 12; index++) {
            String text = "Block " + index;
            blocks.add(new EvidenceBlock(
                    "m-" + index,
                    index,
                    index == 0 ? BlockKind.HEADING : BlockKind.PARAGRAPH,
                    text,
                    offset,
                    offset + text.length(),
                    "active-section",
                    null,
                    SourcePosition.markdown(index + 1, 1, index + 1, text.length())
            ));
            offset += text.length() + 2;
        }
        ActiveDocumentVersionSnapshot version = new ActiveDocumentVersionSnapshot(
                31L, 41L, 1, "Manual.md"
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 11L, 10L, 21L, "1", List.of(version), "a".repeat(64)
        );
        CanonicalDocument canonical = new CanonicalDocument(
                1, 41L, "MARKDOWN", "b".repeat(64), "markdown", "1",
                Instant.parse("2026-08-11T00:00:00Z"), blocks,
                List.of(
                        new HeadingNode(
                                "root", null, 0, 0, "Manual", null,
                                "DOCUMENT_ROOT", 0, blocks.size()
                        ),
                        new HeadingNode(
                                "active-section", "root", 1, 1, "Block 0", "m-0",
                                "MARKDOWN", 0, blocks.size()
                        )
                ),
                List.of(), null, offset, "c".repeat(64)
        );
        EvidenceBlock anchor = blocks.get(4);
        FakeRetrieval scoped = new FakeRetrieval(
                retrievalWithEvidence(
                        anchor,
                        new RetrieveResponseVO.SourcePosition(
                                "MARKDOWN", null, null, null, null,
                                null, null, null, null,
                                5, 1, 5, anchor.text().length()
                        ),
                        null,
                        "Manual.md",
                        "active-section",
                        List.of("Manual", "Block 0")
                ),
                new ScopedRetrievalService.ScopedDocument(version, canonical)
        );
        return new Fixture(context, scoped);
    }

    private Fixture navigationPartitionFixture() {
        Fixture base = pdfSectionFixture();
        EvidenceBlock firstPartitionBlock = base.retrieval.document.canonical().blocks().get(1);
        RetrieveResponseVO retrieval = retrievalWithEvidence(
                firstPartitionBlock,
                new RetrieveResponseVO.SourcePosition(
                        "PDF", 2, 2, 0, firstPartitionBlock.text().length(),
                        null, null, null, null,
                        null, null, null, null
                ),
                new RetrieveResponseVO.InternalReadTarget(
                        "partition-card", "HEADING_SUBPARTITION", 1, 4
                )
        );
        return new Fixture(
                base.context,
                new FakeRetrieval(retrieval, base.retrieval.document)
        );
    }

    private Fixture multiPageRetrievalFixture() {
        Fixture base = fixture(true);
        List<RetrieveResponseVO.Result> results = new ArrayList<>();
        int[] pages = {1, 1, 2, 3};
        for (int index = 0; index < pages.length; index++) {
            String text = "Candidate page " + pages[index] + " rank " + (index + 1);
            RetrieveResponseVO.Evidence evidence = new RetrieveResponseVO.Evidence(
                    "multi-" + index,
                    "PARAGRAPH",
                    text,
                    false,
                    index * 100L,
                    index * 100L + text.length(),
                    new RetrieveResponseVO.SourcePosition(
                            "PDF", pages[index], index, 0, text.length(),
                            null, null, null, null,
                            null, null, null, null
                    ),
                    List.of()
            );
            results.add(new RetrieveResponseVO.Result(
                    index + 1,
                    31L,
                    41L,
                    1,
                    "Manual.pdf",
                    "active-section",
                    List.of("Manual", "Repeated"),
                    List.of("HYBRID"),
                    index + 1,
                    index + 1,
                    List.of(evidence),
                    null
            ));
        }
        RetrieveResponseVO retrieval = new RetrieveResponseVO(
                "query-execution-pages",
                21L,
                "HYBRID",
                "HYBRID",
                false,
                null,
                List.copyOf(results)
        );
        return new Fixture(
                base.context,
                new FakeRetrieval(retrieval, base.retrieval.document)
        );
    }

    private Fixture parentSectionFixture() {
        List<String> texts = List.of(
                "Parent Section", "Parent introduction", "Child Section", "Child-only answer"
        );
        List<EvidenceBlock> blocks = new ArrayList<>();
        long offset = 0;
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String headingId = index < 2 ? "parent" : "child";
            blocks.add(new EvidenceBlock(
                    "parent-b-" + index,
                    index,
                    index == 0 || index == 2 ? BlockKind.HEADING : BlockKind.PARAGRAPH,
                    text,
                    offset,
                    offset + text.length(),
                    headingId,
                    null,
                    SourcePosition.markdown(index + 1, 1, index + 1, text.length())
            ));
            offset += text.length() + 2;
        }
        ActiveDocumentVersionSnapshot version = new ActiveDocumentVersionSnapshot(
                31L, 41L, 1, "Parent.md"
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 11L, 10L, 21L, "1", List.of(version), "a".repeat(64)
        );
        CanonicalDocument canonical = new CanonicalDocument(
                1, 41L, "MARKDOWN", "b".repeat(64), "markdown", "1",
                Instant.parse("2026-08-11T00:00:00Z"), blocks,
                List.of(
                        new HeadingNode(
                                "root", null, 0, 0, "Parent Manual", null,
                                "DOCUMENT_ROOT", 0, 4
                        ),
                        new HeadingNode(
                                "parent", "root", 1, 1, texts.get(0), "parent-b-0",
                                "MARKDOWN", 0, 4
                        ),
                        new HeadingNode(
                                "child", "parent", 2, 2, texts.get(2), "parent-b-2",
                                "MARKDOWN", 2, 4
                        )
                ),
                List.of(), null, offset, "c".repeat(64)
        );
        return new Fixture(
                context,
                new FakeRetrieval(
                        retrievalWithEvidence(
                                blocks.get(1),
                                new RetrieveResponseVO.SourcePosition(
                                        "MARKDOWN", null, null, null, null,
                                        null, null, null, null,
                                        2, 1, 2, texts.get(1).length()
                                ),
                                null,
                                "Parent.md",
                                "parent",
                                List.of("Parent Manual", "Parent Section")
                        ),
                        new ScopedRetrievalService.ScopedDocument(version, canonical)
                )
        );
    }

    private RetrieveResponseVO retrievalWithEvidence(
            EvidenceBlock body,
            RetrieveResponseVO.SourcePosition sourcePosition
    ) {
        return retrievalWithEvidence(body, sourcePosition, null);
    }

    private RetrieveResponseVO retrievalWithEvidence(
            EvidenceBlock body,
            RetrieveResponseVO.SourcePosition sourcePosition,
            RetrieveResponseVO.InternalReadTarget internalReadTarget
    ) {
        return retrievalWithEvidence(
                body,
                sourcePosition,
                internalReadTarget,
                "Manual.md",
                "active-section",
                List.of("Manual", "Refund Policy")
        );
    }

    private RetrieveResponseVO retrievalWithEvidence(
            EvidenceBlock body,
            RetrieveResponseVO.SourcePosition sourcePosition,
            RetrieveResponseVO.InternalReadTarget internalReadTarget,
            String documentName,
            String headingNodeId,
            List<String> headingPath
    ) {
        RetrieveResponseVO.Evidence evidence = new RetrieveResponseVO.Evidence(
                body.blockId(), body.kind().name(), body.text(), false,
                body.canonicalStart(), body.canonicalEnd(),
                sourcePosition,
                List.of()
        );
        RetrieveResponseVO.Result result = new RetrieveResponseVO.Result(
                1, 31L, 41L, 1, documentName, headingNodeId,
                headingPath, List.of("KEYWORD"),
                1, null, List.of(evidence), internalReadTarget
        );
        return new RetrieveResponseVO(
                "query-execution-1", 21L, "HYBRID", "HYBRID",
                false, null, List.of(result)
        );
    }

    private RetrieveResponseVO emptyRetrieval() {
        return new RetrieveResponseVO(
                "query-execution-1", 21L, "HYBRID", "HYBRID",
                false, null, List.of()
        );
    }

    private AnswerRequestDTO request(String query, String mode, Integer topK) {
        AnswerRequestDTO request = new AnswerRequestDTO();
        request.setQuery(query);
        request.setMode(mode);
        request.setTopK(topK);
        return request;
    }

    private AnswerChatGateway.Turn toolTurn(String id, String name, String arguments) {
        return new AnswerChatGateway.Turn(
                null,
                List.of(new AnswerChatGateway.ToolCall(id, name, arguments))
        );
    }

    private AnswerChatGateway.Turn answered(String answer, String... evidenceIds) {
        return new AnswerChatGateway.Turn(
                new ObjectMapper().writeValueAsString(Map.of(
                        "status", "ANSWERED",
                        "answer", answer,
                        "evidenceIds", List.of(evidenceIds)
                )),
                List.of()
        );
    }

    private AnswerChatGateway.Turn answeredWithReadRefs(
            String answer,
            List<String> evidenceIds,
            List<String> readRefs
    ) {
        return new AnswerChatGateway.Turn(
                new ObjectMapper().writeValueAsString(Map.of(
                        "status", "ANSWERED",
                        "answer", answer,
                        "evidenceIds", evidenceIds,
                        "readRefs", readRefs
                )),
                List.of()
        );
    }

    private AnswerChatGateway.Turn invalidUnknownCitation() {
        return answered("错误引用。", "E99");
    }

    private record Fixture(QueryAccessContext context, FakeRetrieval retrieval) {
    }

    private static final class FakeRetrieval implements ScopedRetrievalService {
        private final RetrieveResponseVO result;
        private final ScopedDocument document;
        private final List<Long> loadDocumentIds = new ArrayList<>();
        private int calls;
        private Integer lastTopK;
        private int answerCalls;

        private FakeRetrieval(RetrieveResponseVO result, ScopedDocument document) {
            this.result = result;
            this.document = document;
        }

        @Override
        public RetrieveResponseVO retrieve(
                QueryAccessContext context,
                com.doc.docquery.dto.RetrieveRequestDTO request
        ) {
            calls++;
            lastTopK = request.getTopK();
            return result;
        }

        @Override
        public RetrieveResponseVO retrieveForAnswer(
                QueryAccessContext context,
                com.doc.docquery.dto.RetrieveRequestDTO request
        ) {
            answerCalls++;
            return retrieve(context, request);
        }

        @Override
        public ScopedDocument loadDocument(QueryAccessContext context, long documentId) {
            loadDocumentIds.add(documentId);
            return document;
        }
    }

    private static final class ScriptedGateway
            implements AnswerChatGateway, AnswerAgentGateway {
        private final Deque<Turn> turns = new ArrayDeque<>();
        private final List<Boolean> toolsEnabled = new ArrayList<>();
        private final List<List<Message>> messages = new ArrayList<>();
        private String finalizationOutput;
        private FinalizationRequest finalizationRequest;
        private int calls;

        private ScriptedGateway(Turn... turns) {
            this.turns.addAll(List.of(turns));
        }

        private ScriptedGateway withFinalization(String output) {
            this.finalizationOutput = output;
            return this;
        }

        @Override
        public Turn chat(List<Message> messages, boolean toolsEnabled) {
            calls++;
            this.toolsEnabled.add(toolsEnabled);
            this.messages.add(List.copyOf(messages));
            return turns.removeFirst();
        }

        @Override
        public AgentRun start(Request request, ToolHandler tools, Observer observer) {
            List<Message> conversation = new ArrayList<>();
            conversation.add(new SystemPrompt(request.systemPrompt()));
            return userMessage -> {
                conversation.add(new UserContent(userMessage));
                while (true) {
                    observer.beforeModelCall();
                    Turn turn = chat(List.copyOf(conversation), true);
                    if (!turn.hasToolCalls()) {
                        conversation.add(new AssistantContent(turn.text(), List.of()));
                        return turn.text();
                    }
                    observer.toolRound(turn.toolCalls().size());
                    conversation.add(new AssistantContent(turn.text(), turn.toolCalls()));
                    for (ToolCall call : turn.toolCalls()) {
                        String result = tools.execute(call.name(), call.argumentsJson());
                        conversation.add(new ToolResultContent(
                                call.id(), call.name(), result
                        ));
                        observer.afterToolCall();
                    }
                }
            };
        }

        @Override
        public String finalizeAnswer(FinalizationRequest request, Observer observer) {
            finalizationRequest = request;
            if (finalizationOutput == null) {
                return AnswerAgentGateway.super.finalizeAnswer(request, observer);
            }
            observer.beforeModelCall();
            return finalizationOutput;
        }

    }

    private static final class StatefulIdempotency implements QueryIdempotencyService {
        private QueryOperation operation;
        private String completedJson;
        private int renewals;
        private int releases;

        @Override
        public QueryIdempotencyClaim claim(
                QueryAccessContext context,
                QueryOperation operation,
                String key,
                String requestFingerprint
        ) {
            this.operation = operation;
            if (completedJson != null) {
                return QueryIdempotencyClaim.replay(
                        "storage", requestFingerprint,
                        context.getSnapshotFingerprint(), completedJson
                );
            }
            return QueryIdempotencyClaim.owner(
                    "storage", "owner", requestFingerprint,
                    context.getSnapshotFingerprint()
            );
        }

        @Override
        public boolean renew(QueryIdempotencyClaim claim) {
            renewals++;
            return true;
        }

        @Override
        public void complete(QueryIdempotencyClaim claim, String responseJson) {
            completedJson = responseJson;
        }

        @Override
        public void release(QueryIdempotencyClaim claim) {
            releases++;
        }
    }
}
