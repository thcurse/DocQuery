package com.doc.docquery.parser;

import com.doc.docquery.service.DocumentParseException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** 写对象前验证 Block、标题树、范围、计数和标准化全文摘要。 */
@Component
public class CanonicalDocumentValidator {

    public void validate(CanonicalDocument document) {
        if (document.blocks().isEmpty() || document.headings().isEmpty()) {
            fail("Canonical document has no evidence or root heading");
        }

        Map<String, EvidenceBlock> blocksById = new HashMap<>();
        MessageDigest digest = sha256Digest();
        long expectedStart = 0;
        for (int ordinal = 0; ordinal < document.blocks().size(); ordinal++) {
            EvidenceBlock block = document.blocks().get(ordinal);
            if (block.ordinal() != ordinal || block.sourcePosition() == null) {
                fail("Evidence block ordinal or source position is invalid");
            }
            if (ordinal > 0) {
                digest.update("\n\n".getBytes(StandardCharsets.UTF_8));
                expectedStart += 2;
            }
            if (block.canonicalStart() != expectedStart
                    || block.canonicalEnd() != expectedStart + block.text().length()) {
                fail("Evidence block canonical range is invalid");
            }
            if (blocksById.put(block.blockId(), block) != null) {
                fail("Evidence block ID is duplicated");
            }
            digest.update(block.text().getBytes(StandardCharsets.UTF_8));
            expectedStart = block.canonicalEnd();
        }
        if (expectedStart != document.textLength()
                || !HexFormat.of().formatHex(digest.digest())
                .equals(document.canonicalTextSha256())) {
            fail("Canonical text length or SHA-256 is invalid");
        }

        Map<String, HeadingNode> headingsById = new HashMap<>();
        int rootCount = 0;
        for (HeadingNode heading : document.headings()) {
            if (headingsById.put(heading.nodeId(), heading) != null) {
                fail("Heading node ID is duplicated");
            }
            if (heading.parentNodeId() == null) {
                rootCount++;
                if (heading.depth() != 0 || heading.declaredLevel() != 0) {
                    fail("Root heading shape is invalid");
                }
            } else if (!headingsById.containsKey(heading.parentNodeId())) {
                fail("Heading parent must appear before its child");
            }
            if (heading.sectionStartBlockOrdinal() < 0
                    || heading.sectionEndBlockOrdinalExclusive()
                    > document.blocks().size()
                    || heading.sectionStartBlockOrdinal()
                    > heading.sectionEndBlockOrdinalExclusive()) {
                fail("Heading section range is invalid");
            }
            if (heading.parentNodeId() != null) {
                EvidenceBlock source = blocksById.get(heading.sourceHeadingBlockId());
                if (source == null
                        || source.kind() != BlockKind.HEADING
                        || !heading.nodeId().equals(source.headingNodeId())) {
                    fail("Real heading has no matching source evidence block");
                }
            }
        }
        if (rootCount != 1) {
            fail("Canonical document must have exactly one root heading");
        }

        Set<String> headingIds = new HashSet<>(headingsById.keySet());
        for (EvidenceBlock block : document.blocks()) {
            if (!headingIds.contains(block.headingNodeId())) {
                fail("Evidence block references an unknown heading node");
            }
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void fail(String message) {
        throw new DocumentParseException(
                "CANONICAL_VALIDATION_FAILED",
                message,
                false
        );
    }
}
