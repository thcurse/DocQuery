package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.service.DocumentParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 将四种显式输入交给项目内 DeepDoc HTTP 服务，再映射为中立 ParsedDocument。
 *
 * <p>RabbitMQ、任务状态和重试仍由上层处理；本 Adapter 不创建第二套任务。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.parsing.deepdoc",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DeepDocDocumentParser implements DocumentFormatParser {

    static final String SCHEMA_VERSION = "docquery-deepdoc-http-v1";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DeepDocDocumentParser.class
    );

    private final DocumentParsingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI parseUri;

    public DeepDocDocumentParser(
            DocumentParsingProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = buildClient(properties);
        this.parseUri = parseUri(properties.getDeepdoc().getBaseUrl());
        validateLimits(properties.getDeepdoc());
    }

    @Override
    public boolean supports(DocumentSourceFormat format) {
        return format != null;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        long started = System.nanoTime();
        validateSource(source);
        String boundary = "docquery-" + UUID.randomUUID();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(parseUri)
                    .timeout(properties.getDeepdoc().getRequestTimeout())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .header("X-Source-SHA256", source.sourceSha256())
                    .header("X-Source-Format", source.format().name())
                    .header("X-Request-ID", "parse-" + source.documentVersionId())
                    .POST(multipartBody(source.path(), source.format(), boundary))
                    .build();
        } catch (IOException exception) {
            throw retryable(
                    "DEEPDOC_SOURCE_UNAVAILABLE",
                    "Document source could not be sent to DeepDoc",
                    exception
            );
        }

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(
                        input,
                        properties.getDeepdoc().getMaxResponseBytes()
                );
            }
            JsonNode root = parseJson(body, response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw remoteError(root, response.statusCode());
            }
            ParsedDocument parsed = mapSuccess(
                    root,
                    source.sourceSha256(),
                    source.format()
            );
            LOGGER.info(
                    "docquery_parser_usage provider=DEEPDOC operation=PARSE "
                            + "documentVersionId={} status=SUCCESS elapsedMillis={} "
                            + "serviceElapsedMillis={} sourceFormat={} pageCount={} "
                            + "blockCount={} warningCount={}",
                    source.documentVersionId(),
                    elapsedMillis(started),
                    nullableLong(root.get("elapsedMillis")),
                    source.format(),
                    parsed.pageCount(),
                    parsed.blocks().size(),
                    parsed.warnings().size()
            );
            return parsed;
        } catch (DocumentParseException exception) {
            LOGGER.warn(
                    "docquery_parser_usage provider=DEEPDOC operation=PARSE "
                            + "documentVersionId={} status=FAILED elapsedMillis={} "
                            + "sourceFormat={} failureCode={} retryable={}",
                    source.documentVersionId(),
                    elapsedMillis(started),
                    source.format(),
                    exception.code(),
                    exception.retryable()
            );
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw retryable(
                    "DEEPDOC_REQUEST_INTERRUPTED",
                    "DeepDoc request was interrupted",
                    exception
            );
        } catch (IOException exception) {
            throw retryable(
                    "DEEPDOC_SERVICE_UNAVAILABLE",
                    "DeepDoc service is unavailable",
                    exception
            );
        }
    }

    private Long nullableLong(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.longValue() : null;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private ParsedDocument mapSuccess(
            JsonNode root,
            String expectedSha256,
            DocumentSourceFormat expectedFormat
    ) {
        if (!SCHEMA_VERSION.equals(text(root, "schemaVersion"))) {
            throw invalidResponse("DeepDoc response schema is unsupported", false);
        }
        if (!expectedSha256.equals(text(root, "sourceSha256"))) {
            throw invalidResponse("DeepDoc response source digest is invalid", false);
        }
        if (!expectedFormat.name().equals(text(root, "sourceFormat"))) {
            throw invalidResponse("DeepDoc response source format is invalid", false);
        }
        Integer pageCount = nullableInteger(root.get("pageCount"));
        if (expectedFormat == DocumentSourceFormat.PDF) {
            if (pageCount == null
                    || pageCount < 1
                    || pageCount > properties.getMaxPdfPages()) {
                throw invalidResponse("DeepDoc response page count is invalid", false);
            }
        } else if (pageCount != null) {
            throw invalidResponse("DeepDoc non-PDF response has a page count", false);
        }

        JsonNode blockNodes = root.get("blocks");
        if (blockNodes == null || !blockNodes.isArray() || blockNodes.isEmpty()) {
            throw new DocumentParseException(
                    "PDF_NO_EXTRACTABLE_TEXT",
                    "DeepDoc returned no nonblank blocks",
                    false
            );
        }
        if (blockNodes.size() > properties.getMaxBlocks()) {
            throw new DocumentParseException(
                    "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                    "DeepDoc returned too many blocks",
                    false
            );
        }

        List<ParsedBlock> blocks = new ArrayList<>(blockNodes.size());
        for (JsonNode node : blockNodes) {
            blocks.add(mapBlock(node, expectedFormat, pageCount));
        }

        List<ParseWarning> warnings = new ArrayList<>();
        JsonNode warningNodes = root.get("warnings");
        if (warningNodes != null && warningNodes.isArray()) {
            for (JsonNode warning : warningNodes) {
                String code = text(warning, "code");
                String message = text(warning, "message");
                if (code.isBlank() || message.isBlank()) {
                    throw invalidResponse("DeepDoc warning is invalid", false);
                }
                Integer pageNumber = nullableInteger(warning.get("pageNumber"));
                if (pageNumber != null
                        && (expectedFormat != DocumentSourceFormat.PDF
                        || pageCount == null
                        || pageNumber < 1
                        || pageNumber > pageCount)) {
                    throw invalidResponse("DeepDoc warning page is invalid", false);
                }
                warnings.add(new ParseWarning(
                        code,
                        message,
                        pageNumber == null
                                ? null
                                : SourcePosition.pdf(pageNumber, 0, 0, 0)
                ));
            }
        }
        return new ParsedDocument(blocks, warnings, pageCount);
    }

    private ParsedBlock mapBlock(
            JsonNode node,
            DocumentSourceFormat expectedFormat,
            Integer pageCount
    ) {
        BlockKind kind;
        try {
            kind = BlockKind.valueOf(text(node, "kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalidResponse("DeepDoc block kind is invalid", false);
        }
        String text = text(node, "text");
        if (text.isBlank()) {
            throw invalidResponse("DeepDoc block text is blank", false);
        }
        SourcePosition sourcePosition = mapPosition(
                node,
                expectedFormat,
                pageCount,
                text,
                kind
        );

        Integer headingLevel = nullableInteger(node.get("headingLevel"));
        String detectionSource = nullableText(node.get("detectionSource"));
        boolean documentTitle = node.path("documentTitle").asBoolean(false);
        if (documentTitle) {
            if (kind != BlockKind.TITLE || headingLevel != null) {
                throw invalidResponse("DeepDoc document title is invalid", false);
            }
        } else if (kind == BlockKind.HEADING) {
            if (headingLevel == null || headingLevel < 1 || headingLevel > 6) {
                throw invalidResponse("DeepDoc heading level is invalid", false);
            }
        } else if (headingLevel != null) {
            throw invalidResponse("DeepDoc non-heading block has a heading level", false);
        }
        return new ParsedBlock(
                kind,
                text,
                sourcePosition,
                headingLevel,
                detectionSource,
                documentTitle
        );
    }

    private DocumentParseException remoteError(JsonNode root, int status) {
        JsonNode error = root.get("error");
        if (error == null || !error.isObject()) {
            return invalidResponse(
                    "DeepDoc returned an invalid error response",
                    status >= 500
            );
        }
        String code = text(error, "code");
        String message = text(error, "message");
        if (code.isBlank() || message.isBlank()) {
            return invalidResponse(
                    "DeepDoc returned an invalid error response",
                    status >= 500
            );
        }
        return new DocumentParseException(
                code,
                message,
                error.path("retryable").asBoolean(status >= 500)
        );
    }

    private JsonNode parseJson(byte[] body, int status) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IOException("JSON root is not an object");
            }
            return root;
        } catch (IOException exception) {
            throw invalidResponse(
                    "DeepDoc returned malformed JSON",
                    status >= 500,
                    exception
            );
        }
    }

    private SourcePosition mapPosition(
            JsonNode node,
            DocumentSourceFormat expectedFormat,
            Integer pageCount,
            String blockText,
            BlockKind kind
    ) {
        if (!expectedFormat.name().equals(text(node, "sourceType"))) {
            throw invalidResponse("DeepDoc block source type is invalid", false);
        }
        return switch (expectedFormat) {
            case PDF -> {
                int pageNumber = integer(node, "pageNumber");
                int pageOrdinal = integer(node, "pageBlockOrdinal");
                int start = integer(node, "pageCharacterStart");
                int end = integer(node, "pageCharacterEnd");
                if (pageCount == null
                        || pageNumber < 1
                        || pageNumber > pageCount
                        || pageOrdinal < 0
                        || start < 0
                        || end < start
                        || end - start != blockText.length()) {
                    throw invalidResponse("DeepDoc PDF block position is invalid", false);
                }
                Integer tableRow = nullableInteger(node.get("tableRow"));
                Integer tableColumn = nullableInteger(node.get("tableColumn"));
                Integer tableRowSpan = nullableInteger(node.get("tableRowSpan"));
                Integer tableColumnSpan = nullableInteger(node.get("tableColumnSpan"));
                String tableId = nullableText(node.get("tableId"));
                String tableColumnHeader = nullableText(node.get("tableColumnHeader"));
                String tableCaption = nullableText(node.get("tableCaption"));
                String tableRowHeader = nullableText(node.get("tableRowHeader"));
                String tableCellHeading = nullableText(node.get("tableCellHeading"));
                String tableGroupId = nullableText(node.get("tableGroupId"));
                String tableContinuationOf = nullableText(
                        node.get("tableContinuationOf")
                );
                if ((tableRow == null) != (tableColumn == null)
                        || tableRow != null && (kind != BlockKind.TABLE_CELL
                        || tableRow < 0 || tableColumn < 0
                        || tableRowSpan != null && tableRowSpan < 1
                        || tableColumnSpan != null && tableColumnSpan < 1
                        || tableId != null && tableId.isBlank()
                        || tableColumnHeader != null && tableColumnHeader.isBlank()
                        || tableCaption != null && tableCaption.isBlank()
                        || tableRowHeader != null && tableRowHeader.isBlank()
                        || tableCellHeading != null && tableCellHeading.isBlank()
                        || tableGroupId != null && tableGroupId.isBlank()
                        || tableContinuationOf != null
                        && tableContinuationOf.isBlank())
                        || tableRow == null && (tableRowSpan != null
                        || tableColumnSpan != null || tableId != null
                        || tableColumnHeader != null || tableCaption != null
                        || tableRowHeader != null || tableCellHeading != null
                        || tableGroupId != null || tableContinuationOf != null)) {
                    throw invalidResponse("DeepDoc PDF table position is invalid", false);
                }
                yield tableRow == null
                        ? SourcePosition.pdf(pageNumber, pageOrdinal, start, end)
                        : SourcePosition.pdfTable(
                                pageNumber,
                                pageOrdinal,
                                start,
                                end,
                                tableRow,
                                tableColumn,
                                tableRowSpan == null ? 1 : tableRowSpan,
                                tableColumnSpan == null ? 1 : tableColumnSpan,
                                tableId,
                                tableColumnHeader,
                                tableCaption,
                                tableRowHeader,
                                tableCellHeading,
                                tableGroupId,
                                tableContinuationOf
                        );
            }
            case DOCX -> {
                int bodyElementIndex = integer(node, "bodyElementIndex");
                if (bodyElementIndex < 0) {
                    throw invalidResponse("DeepDoc DOCX block position is invalid", false);
                }
                yield SourcePosition.docxParagraph(bodyElementIndex);
            }
            case TXT -> {
                int startLine = integer(node, "startLine");
                int endLine = integer(node, "endLine");
                if (startLine < 1 || endLine < startLine) {
                    throw invalidResponse("DeepDoc TXT block position is invalid", false);
                }
                yield SourcePosition.text(startLine, endLine);
            }
            case MARKDOWN -> {
                int startLine = integer(node, "startLine");
                int startColumn = integer(node, "startColumn");
                int endLine = integer(node, "endLine");
                int endColumn = integer(node, "endColumn");
                if (startLine < 1
                        || startColumn < 1
                        || endLine < startLine
                        || endColumn < 1
                        || (endLine == startLine && endColumn < startColumn)) {
                    throw invalidResponse("DeepDoc Markdown block position is invalid", false);
                }
                yield SourcePosition.markdown(
                        startLine,
                        startColumn,
                        endLine,
                        endColumn
                );
            }
        };
    }

    private HttpRequest.BodyPublisher multipartBody(
            Path path,
            DocumentSourceFormat format,
            String boundary
    )
            throws IOException {
        String filename = switch (format) {
            case PDF -> "document.pdf";
            case DOCX -> "document.docx";
            case TXT -> "document.txt";
            case MARKDOWN -> "document.md";
        };
        String contentType = switch (format) {
            case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case TXT, MARKDOWN -> "text/plain; charset=utf-8";
        };
        String prefixValue = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        byte[] prefix = prefixValue
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix),
                HttpRequest.BodyPublishers.ofFile(path),
                HttpRequest.BodyPublishers.ofByteArray(suffix)
        );
    }

    private byte[] readLimited(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            count += read;
            if (count > maxBytes) {
                throw new DocumentParseException(
                        "DEEPDOC_RESPONSE_LIMIT_EXCEEDED",
                        "DeepDoc response exceeds the configured limit",
                        false
                );
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void validateSource(ParseSource source) {
        if (source == null
                || source.format() == null
                || source.path() == null
                || !Files.isRegularFile(source.path())
                || source.sourceSha256() == null
                || !source.sourceSha256().matches("[0-9a-f]{64}")) {
            throw new DocumentParseException(
                    "DOCUMENT_FORMAT_MISMATCH",
                    "DeepDoc requires a verified supported source",
                    false
            );
        }
    }

    private static HttpClient buildClient(DocumentParsingProperties properties) {
        Duration timeout = properties.getDeepdoc().getConnectTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("DeepDoc connect timeout must be positive");
        }
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private URI parseUri(String configured) {
        try {
            String base = configured.endsWith("/") ? configured : configured + "/";
            URI uri = URI.create(base).resolve("v1/parse");
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("DeepDoc base URL is invalid");
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("DeepDoc base URL is invalid", exception);
        }
    }

    private void validateLimits(DocumentParsingProperties.DeepDoc configured) {
        if (configured.getRequestTimeout() == null
                || configured.getRequestTimeout().isZero()
                || configured.getRequestTimeout().isNegative()
                || configured.getMaxResponseBytes() < 1) {
            throw new IllegalArgumentException("DeepDoc request limits must be positive");
        }
    }

    private int integer(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidResponse("DeepDoc numeric field is invalid", false);
        }
        return value.intValue();
    }

    private Integer nullableInteger(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidResponse("DeepDoc nullable numeric field is invalid", false);
        }
        return value.intValue();
    }

    private String text(JsonNode parent, String field) {
        return nullableText(parent.get(field)) == null ? "" : nullableText(parent.get(field));
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() || !value.isTextual()
                ? null
                : value.textValue();
    }

    private DocumentParseException invalidResponse(String message, boolean retryable) {
        return new DocumentParseException("DEEPDOC_INVALID_RESPONSE", message, retryable);
    }

    private DocumentParseException invalidResponse(
            String message,
            boolean retryable,
            Throwable cause
    ) {
        return new DocumentParseException(
                "DEEPDOC_INVALID_RESPONSE",
                message,
                retryable,
                cause
        );
    }

    private DocumentParseException retryable(
            String code,
            String message,
            Throwable cause
    ) {
        return new DocumentParseException(code, message, true, cause);
    }
}
