package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.service.DocumentParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepDocDocumentParserTest {

    private HttpServer server;
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<String> receivedDigest = new AtomicReference<>();
    private final AtomicReference<String> receivedFormat = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private DocumentParsingProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        Files.createDirectories(Path.of("target", "test-data", "deepdoc-parser"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/parse", this::respond);
        server.start();
        properties = new DocumentParsingProperties();
        properties.getDeepdoc().setEnabled(true);
        properties.getDeepdoc().setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        properties.getDeepdoc().setConnectTimeout(Duration.ofSeconds(2));
        properties.getDeepdoc().setRequestTimeout(Duration.ofSeconds(5));
        properties.getDeepdoc().setMaxResponseBytes(1024 * 1024);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void defaultConfigurationRegistersDeepDocForAllFormats() {
        parserContext().run(context -> {
            assertThat(context).hasSingleBean(DeepDocDocumentParser.class);
            assertThat(context).doesNotHaveBean(PdfDocumentParser.class);
            assertThat(context).doesNotHaveBean(DocxDocumentParser.class);
            assertThat(context).doesNotHaveBean(TextDocumentParser.class);
            assertThat(context).doesNotHaveBean(MarkdownDocumentParser.class);
            assertThat(DocumentSourceFormat.values())
                    .allSatisfy(format -> assertThat(
                            context.getBean(DeepDocDocumentParser.class)
                                    .supports(format)
                    ).isTrue());
        });
    }

    @Test
    void disabledConfigurationRestoresAllLocalAdapters() {
        parserContext()
                .withPropertyValues("docquery.parsing.deepdoc.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DeepDocDocumentParser.class);
                    assertThat(context).hasSingleBean(PdfDocumentParser.class);
                    assertThat(context).hasSingleBean(DocxDocumentParser.class);
                    assertThat(context).hasSingleBean(TextDocumentParser.class);
                    assertThat(context).hasSingleBean(MarkdownDocumentParser.class);
                });
    }

    @Test
    void mapsVerifiedHttpResponseToNeutralParsedDocument() throws Exception {
        Path source = source();
        String digest = sha256(source);
        reply.set(new Reply(200, """
                {
                  "schemaVersion":"docquery-deepdoc-http-v1",
                  "sourceSha256":"%s",
                  "sourceFormat":"PDF",
                  "pageCount":2,
                  "blocks":[
                    {
                      "kind":"TITLE",
                      "text":"Manual",
                      "sourceType":"PDF",
                      "pageNumber":1,
                      "pageBlockOrdinal":0,
                      "pageCharacterStart":0,
                      "pageCharacterEnd":6,
                      "headingLevel":null,
                      "detectionSource":"DEEPDOC_LAYOUT_TITLE",
                      "documentTitle":true
                    },
                    {
                      "kind":"TABLE_CELL",
                      "text":"A | B",
                      "sourceType":"PDF",
                      "pageNumber":2,
                      "pageBlockOrdinal":0,
                      "pageCharacterStart":0,
                      "pageCharacterEnd":5,
                      "headingLevel":null,
                      "detectionSource":null,
                      "documentTitle":false
                    }
                  ],
                  "warnings":[{
                    "code":"DEEPDOC_TABLE_STRUCTURE_FLATTENED",
                    "message":"Table text is flat",
                    "pageNumber":2
                  }]
                }
                """.formatted(digest)));

        ParsedDocument parsed = parser().parse(parseSource(source, digest));

        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.blocks()).hasSize(2);
        assertThat(parsed.blocks().get(0).kind()).isEqualTo(BlockKind.TITLE);
        assertThat(parsed.blocks().get(0).documentTitle()).isTrue();
        assertThat(parsed.blocks().get(1).kind()).isEqualTo(BlockKind.TABLE_CELL);
        assertThat(parsed.blocks().get(1).sourcePosition().pageNumber()).isEqualTo(2);
        assertThat(parsed.warnings()).extracting(ParseWarning::code)
                .containsExactly("DEEPDOC_TABLE_STRUCTURE_FLATTENED");
        assertThat(receivedDigest.get()).isEqualTo(digest);
        assertThat(receivedFormat.get()).isEqualTo("PDF");
        assertThat(new String(receivedBody.get(), StandardCharsets.ISO_8859_1))
                .contains("name=\"file\"")
                .contains("%PDF-");
    }

    @Test
    void preservesStableRemoteFailureAndRetryability() throws Exception {
        Path source = source();
        String digest = sha256(source);
        reply.set(new Reply(422, """
                {
                  "schemaVersion":"docquery-deepdoc-http-v1",
                  "error":{
                    "code":"ENCRYPTED_DOCUMENT_UNSUPPORTED",
                    "message":"Encrypted PDF is unsupported",
                    "retryable":false
                  }
                }
                """));

        assertThatThrownBy(() -> parser().parse(parseSource(source, digest)))
                .isInstanceOfSatisfying(DocumentParseException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo("ENCRYPTED_DOCUMENT_UNSUPPORTED");
                    assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void rejectsSuccessBoundToAnotherSourceDigest() throws Exception {
        Path source = source();
        String digest = sha256(source);
        reply.set(new Reply(200, """
                {
                  "schemaVersion":"docquery-deepdoc-http-v1",
                  "sourceSha256":"%s",
                  "sourceFormat":"PDF",
                  "pageCount":1,
                  "blocks":[{
                    "kind":"PARAGRAPH",
                    "text":"text",
                    "sourceType":"PDF",
                    "pageNumber":1,
                    "pageBlockOrdinal":0,
                    "pageCharacterStart":0,
                    "pageCharacterEnd":4,
                    "headingLevel":null,
                    "detectionSource":null,
                    "documentTitle":false
                  }],
                  "warnings":[]
                }
                """.formatted("0".repeat(64))));

        assertThatThrownBy(() -> parser().parse(parseSource(source, digest)))
                .isInstanceOfSatisfying(DocumentParseException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("DEEPDOC_INVALID_RESPONSE");
                    assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void mapsDocxTxtAndMarkdownPositions() throws Exception {
        assertNonPdfMapping(
                DocumentSourceFormat.DOCX,
                "\"bodyElementIndex\":3",
                position -> assertThat(position.bodyElementIndex()).isEqualTo(3)
        );
        assertNonPdfMapping(
                DocumentSourceFormat.TXT,
                "\"startLine\":2,\"endLine\":4",
                position -> {
                    assertThat(position.startLine()).isEqualTo(2);
                    assertThat(position.endLine()).isEqualTo(4);
                }
        );
        assertNonPdfMapping(
                DocumentSourceFormat.MARKDOWN,
                "\"startLine\":2,\"startColumn\":1,"
                        + "\"endLine\":3,\"endColumn\":8",
                position -> {
                    assertThat(position.startLine()).isEqualTo(2);
                    assertThat(position.endColumn()).isEqualTo(8);
                }
        );
    }

    private void assertNonPdfMapping(
            DocumentSourceFormat format,
            String positionJson,
            java.util.function.Consumer<SourcePosition> assertion
    ) throws Exception {
        Path source = source();
        String digest = sha256(source);
        reply.set(new Reply(200, """
                {
                  "schemaVersion":"docquery-deepdoc-http-v1",
                  "sourceSha256":"%s",
                  "sourceFormat":"%s",
                  "pageCount":null,
                  "blocks":[{
                    "kind":"PARAGRAPH",
                    "text":"text",
                    "sourceType":"%s",
                    %s,
                    "headingLevel":null,
                    "detectionSource":null,
                    "documentTitle":false
                  }],
                  "warnings":[]
                }
                """.formatted(digest, format.name(), format.name(), positionJson)));

        ParsedDocument parsed = parser().parse(parseSource(source, digest, format));

        assertThat(parsed.pageCount()).isNull();
        assertion.accept(parsed.blocks().get(0).sourcePosition());
        assertThat(receivedFormat.get()).isEqualTo(format.name());
    }

    private DeepDocDocumentParser parser() {
        return new DeepDocDocumentParser(properties, new ObjectMapper());
    }

    private ApplicationContextRunner parserContext() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(ParserConfiguration.class);
    }

    private Path source() throws IOException {
        Path source = Path.of(
                "target",
                "test-data",
                "deepdoc-parser",
                UUID.randomUUID() + ".pdf"
        );
        Files.write(source, "%PDF-test".getBytes(StandardCharsets.UTF_8));
        return source;
    }

    private DocumentFormatParser.ParseSource parseSource(Path source, String digest) {
        return parseSource(source, digest, DocumentSourceFormat.PDF);
    }

    private DocumentFormatParser.ParseSource parseSource(
            Path source,
            String digest,
            DocumentSourceFormat format
    ) {
        return new DocumentFormatParser.ParseSource(
                source,
                7L,
                "Document",
                format,
                digest
        );
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedDigest.set(exchange.getRequestHeaders().getFirst("X-Source-SHA256"));
        receivedFormat.set(exchange.getRequestHeaders().getFirst("X-Source-Format"));
        receivedBody.set(exchange.getRequestBody().readAllBytes());
        Reply selected = reply.get();
        byte[] body = selected.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(selected.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Reply(int status, String body) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentParsingProperties.class)
    @Import({
            PdfDocumentParser.class,
            DocxDocumentParser.class,
            TextDocumentParser.class,
            MarkdownDocumentParser.class,
            DeepDocDocumentParser.class,
            ObjectMapper.class
    })
    static class ParserConfiguration {
    }
}
