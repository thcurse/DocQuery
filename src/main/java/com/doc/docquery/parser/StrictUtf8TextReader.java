package com.doc.docquery.parser;

import com.doc.docquery.service.DocumentParseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** TXT 与 Markdown 共用的严格 UTF-8 解码器，不回退到系统默认编码。 */
final class StrictUtf8TextReader {

    private StrictUtf8TextReader() {
    }

    static String read(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new DocumentParseException(
                    "SOURCE_OBJECT_UNAVAILABLE",
                    "Source text could not be read",
                    true,
                    exception
            );
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String text = decoded.toString();
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            if (text.indexOf('\0') >= 0) {
                throw unsupportedEncoding(null);
            }
            return text.replace("\r\n", "\n").replace('\r', '\n');
        } catch (CharacterCodingException exception) {
            throw unsupportedEncoding(exception);
        }
    }

    private static DocumentParseException unsupportedEncoding(Throwable cause) {
        return new DocumentParseException(
                "TEXT_ENCODING_UNSUPPORTED",
                "Text document is not valid UTF-8",
                false,
                cause
        );
    }
}
