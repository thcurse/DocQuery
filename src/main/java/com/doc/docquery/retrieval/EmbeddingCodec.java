package com.doc.docquery.retrieval;

import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** 把内存 float[] 固定编码为可跨语言读取的 FLOAT32_LE_BASE64。 */
@Component
public class EmbeddingCodec {

    public EmbeddingPayload encode(String input, float[] vector, int expectedDimension) {
        if (vector == null || vector.length != expectedDimension) {
            fail("Embedding dimension does not match configuration");
        }
        boolean nonZero = false;
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                fail("Embedding contains a non-finite number");
            }
            nonZero |= value != 0.0f;
            bytes.putFloat(value);
        }
        if (!nonZero) {
            fail("Embedding is an all-zero vector");
        }
        byte[] raw = bytes.array();
        return new EmbeddingPayload(
                "FLOAT32_LE_BASE64",
                vector.length,
                sha256(input.getBytes(StandardCharsets.UTF_8)),
                sha256(raw),
                Base64.getEncoder().encodeToString(raw)
        );
    }

    public byte[] decode(EmbeddingPayload payload, int expectedDimension) {
        try {
            if (!"FLOAT32_LE_BASE64".equals(payload.encoding())
                    || payload.dimension() != expectedDimension) {
                fail("Embedding encoding metadata is invalid");
            }
            byte[] raw = Base64.getDecoder().decode(payload.value());
            if (raw.length != expectedDimension * Float.BYTES
                    || !sha256(raw).equals(payload.valueSha256())) {
                fail("Embedding bytes do not match metadata");
            }
            ByteBuffer values = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            boolean nonZero = false;
            while (values.hasRemaining()) {
                float value = values.getFloat();
                if (!Float.isFinite(value)) {
                    fail("Decoded embedding contains a non-finite number");
                }
                nonZero |= value != 0.0f;
            }
            if (!nonZero) {
                fail("Decoded embedding is an all-zero vector");
            }
            return raw;
        } catch (IllegalArgumentException exception) {
            throw new RetrievalGenerationException(
                    "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                    "Embedding Base64 is invalid",
                    false,
                    exception
            );
        }
    }

    public String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void fail(String message) {
        throw new RetrievalGenerationException(
                "EMBEDDING_RESPONSE_INVALID",
                message,
                false
        );
    }
}
