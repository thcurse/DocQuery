package com.doc.docquery.service.impl;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryOperation;
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
import com.doc.docquery.service.AnswerException;
import com.doc.docquery.service.QueryAccessService;
import com.doc.docquery.service.QueryIdempotencyService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 不依赖供应商或容器，验证 N3.3 Agent 控制面、工具边界、引用和幂等。 */
class AnswerServiceImplTest {

    @Test
    void answersFromCanonicalEvidenceAndReplaysWithoutSecondModelCall() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(new AnswerChatGateway.Turn(
                "{\"status\":\"ANSWERED\",\"answer\":\"七天内可以退款。[E1]\","
                        + "\"citedEvidenceIds\":[\"E1\"]}",
                List.of()
        ));
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
        assertThat(first.getAnswer()).isEqualTo("七天内可以退款。[E1]");
        assertThat(first.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getEvidenceId()).isEqualTo("E1");
            assertThat(citation.getDocumentId()).isEqualTo(31L);
            assertThat(citation.getDocumentVersionId()).isEqualTo(41L);
            assertThat(citation.getHeadingPath()).containsExactly("Manual", "Refund Policy");
            assertThat(citation.getText()).isEqualTo("Refunds are allowed within seven days.");
            assertThat(citation.getSourcePosition().getStartLine()).isEqualTo(3);
        });
        assertThat(replay.getQueryExecutionId()).isEqualTo(first.getQueryExecutionId());
        assertThat(replay.getCitations()).usingRecursiveComparison()
                .isEqualTo(first.getCitations());
        assertThat(gateway.calls).isOne();
        assertThat(idempotency.operation).isEqualTo(QueryOperation.ANSWER);
        assertThat(idempotency.completedJson).contains("七天内可以退款");
    }

    @Test
    void boundedToolsUseFixedSnapshotRenewLeaseAndRegisterReadEvidence() {
        Fixture fixture = fixture(false);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "getDocumentOutline", "{\"documentId\":31}"),
                toolTurn("c2", "readDocument", """
                        {"documentId":31,"startBlockId":"b-1","direction":"FORWARD",
                         "maxBlocks":1}
                        """),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"ANSWERED\",\"answer\":\"可在七天内退款。[E1]\","
                                + "\"citedEvidenceIds\":[\"E1\"]}",
                        List.of()
                )
        );
        StatefulIdempotency idempotency = new StatefulIdempotency();
        AnswerResponseVO response = service(fixture, gateway, idempotency).answer(
                "Bearer token", "tool-key", 21L,
                request("请读取退款条款", null, null)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(response.getCitations()).singleElement()
                .extracting(AnswerResponseVO.Citation::getBlockId)
                .isEqualTo("b-1");
        assertThat(idempotency.renewals).isEqualTo(2);
        assertThat(fixture.retrieval.loadDocumentIds).containsExactly(31L);
        assertThat(gateway.toolsEnabled).containsExactly(true, true, true);
        AnswerChatGateway.ToolResultContent readResult = gateway.messages.get(2).stream()
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .filter(result -> result.toolName().equals("readDocument"))
                .findFirst()
                .orElseThrow();
        assertThat(readResult.resultJson()).contains("E1", "b-1");
        assertThat(readResult.resultJson())
                .doesNotContain("bucket", "objectKey", "tenantId");
    }

    @Test
    void snapshotRejectsForeignDocumentAndAllowsOneArgumentCorrection() {
        Fixture fixture = fixture(false);
        ScriptedGateway gateway = new ScriptedGateway(
                toolTurn("c1", "readDocument", """
                        {"documentId":999,"startBlockId":"outside","maxBlocks":1}
                        """),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"citedEvidenceIds\":[]}",
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
        AnswerChatGateway.ToolResultContent result = gateway.messages.get(1).stream()
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(result.resultJson()).contains("INVALID_ARGUMENT");

        Fixture injected = fixture(false);
        ScriptedGateway unknownTool = new ScriptedGateway(
                toolTurn("c2", "deleteDocument", "{\"documentId\":31}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"citedEvidenceIds\":[]}",
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
        AnswerChatGateway.ToolResultContent rejected = unknownTool.messages.get(1).stream()
                .filter(AnswerChatGateway.ToolResultContent.class::isInstance)
                .map(AnswerChatGateway.ToolResultContent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(rejected.resultJson()).contains("INVALID_ARGUMENT");
    }

    @Test
    void repairsOneInvalidFinalOutputWithToolsDisabled() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                new AnswerChatGateway.Turn(
                        "{\"status\":\"ANSWERED\",\"answer\":\"无引用\","
                                + "\"citedEvidenceIds\":[]}",
                        List.of()
                ),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"ANSWERED\",\"answer\":\"七天内退款。[E1]\","
                                + "\"citedEvidenceIds\":[\"E1\"]}",
                        List.of()
                )
        );

        AnswerResponseVO response = service(
                fixture, gateway, new StatefulIdempotency()
        ).answer(
                "Bearer token", "repair-key", 21L,
                request("退款期限", "HYBRID", 1)
        );

        assertThat(response.getStatus()).isEqualTo("ANSWERED");
        assertThat(gateway.toolsEnabled).containsExactly(true, false);
        assertThat(gateway.messages.get(1)).last().isInstanceOf(
                AnswerChatGateway.UserContent.class
        );
    }

    @Test
    void failsClosedAfterRepairStillUsesUnknownEvidence() {
        Fixture fixture = fixture(true);
        ScriptedGateway gateway = new ScriptedGateway(
                invalidUnknownCitation(),
                invalidUnknownCitation()
        );
        StatefulIdempotency idempotency = new StatefulIdempotency();

        AnswerException failure = catchThrowableOfType(
                AnswerException.class,
                () -> service(fixture, gateway, idempotency).answer(
                        "Bearer token", "invalid-output-key", 21L,
                        request("退款期限", "HYBRID", 1)
                )
        );

        assertThat(failure.reason()).isEqualTo(AnswerException.Reason.OUTPUT_INVALID);
        assertThat(idempotency.completedJson).isNull();
        assertThat(idempotency.releases).isOne();
    }

    @Test
    void rejectsMoreThanTwoToolCallsInOneRoundBeforeExecution() {
        Fixture fixture = fixture(false);
        ScriptedGateway gateway = new ScriptedGateway(new AnswerChatGateway.Turn(
                null,
                List.of(
                        new AnswerChatGateway.ToolCall("c1", "searchDocuments", "{\"query\":\"a\"}"),
                        new AnswerChatGateway.ToolCall("c2", "searchDocuments", "{\"query\":\"b\"}"),
                        new AnswerChatGateway.ToolCall("c3", "searchDocuments", "{\"query\":\"c\"}")
                )
        ));

        AnswerException failure = catchThrowableOfType(
                AnswerException.class,
                () -> service(fixture, gateway, new StatefulIdempotency()).answer(
                        "Bearer token", "limit-key", 21L,
                        request("test", "HYBRID", 1)
                )
        );

        assertThat(failure.reason())
                .isEqualTo(AnswerException.Reason.EXECUTION_LIMIT_EXCEEDED);
        assertThat(fixture.retrieval.calls).isOne();
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
                    new AnswerChatGateway.Turn(
                            "{\"status\":\"ANSWERED\",\"answer\":\"fact [E1]\","
                                    + "\"citedEvidenceIds\":[\"E1\"]}",
                            List.of()
                    )
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

    private AnswerServiceImpl service(
            Fixture fixture,
            AnswerChatGateway gateway,
            StatefulIdempotency idempotency
    ) {
        QueryAccessService access = (header, knowledgeBaseId) -> fixture.context;
        return new AnswerServiceImpl(
                access,
                idempotency,
                fixture.retrieval,
                Optional.of(gateway),
                new AnswerProperties(),
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

    private RetrieveResponseVO retrievalWithEvidence(
            EvidenceBlock body,
            RetrieveResponseVO.SourcePosition sourcePosition
    ) {
        RetrieveResponseVO.Evidence evidence = new RetrieveResponseVO.Evidence(
                body.blockId(), body.kind().name(), body.text(), false,
                body.canonicalStart(), body.canonicalEnd(),
                sourcePosition,
                List.of()
        );
        RetrieveResponseVO.Result result = new RetrieveResponseVO.Result(
                1, 31L, 41L, 1, "Manual.md", "active-section",
                List.of("Manual", "Refund Policy"), List.of("KEYWORD"),
                1, null, List.of(evidence)
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

    private AnswerChatGateway.Turn invalidUnknownCitation() {
        return new AnswerChatGateway.Turn(
                "{\"status\":\"ANSWERED\",\"answer\":\"错误引用。[E99]\","
                        + "\"citedEvidenceIds\":[\"E99\"]}",
                List.of()
        );
    }

    private record Fixture(QueryAccessContext context, FakeRetrieval retrieval) {
    }

    private static final class FakeRetrieval implements ScopedRetrievalService {
        private final RetrieveResponseVO result;
        private final ScopedDocument document;
        private final List<Long> loadDocumentIds = new ArrayList<>();
        private int calls;

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
            return result;
        }

        @Override
        public ScopedDocument loadDocument(QueryAccessContext context, long documentId) {
            loadDocumentIds.add(documentId);
            return document;
        }
    }

    private static final class ScriptedGateway implements AnswerChatGateway {
        private final Deque<Turn> turns = new ArrayDeque<>();
        private final List<Boolean> toolsEnabled = new ArrayList<>();
        private final List<List<Message>> messages = new ArrayList<>();
        private int calls;

        private ScriptedGateway(Turn... turns) {
            this.turns.addAll(List.of(turns));
        }

        @Override
        public Turn chat(List<Message> messages, boolean toolsEnabled) {
            calls++;
            this.toolsEnabled.add(toolsEnabled);
            this.messages.add(List.copyOf(messages));
            return turns.removeFirst();
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
