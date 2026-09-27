package com.doc.docquery.stream;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AnswerDeltaParserTest {
    @Test void streamsBeforeStringClosesAndDecodesArbitraryBoundaries() {
        String json = "{\"ignored\":{\"answer\":\"wrong\"},\"evidenceIds\":[\"E1\"],\"answer\":\"中文\\n\\\"hi\\\" \\\\ 😀 \\uD83D\\uDE00 [E1, E22] E5 正文\",\"status\":\"ANSWERED\"}";
        for (int size = 1; size < 30; size++) {
            List<String> output = new ArrayList<>();
            AnswerDeltaParser parser = new AnswerDeltaParser(4096, output::add);
            int answerEnd = json.indexOf("\",\"status");
            for (int i = 0; i < answerEnd; i += size)
                parser.accept(json.substring(i, Math.min(answerEnd, i + size)));
            assertThat(String.join("", output)).contains("中文", "😀").doesNotContain("wrong", "E1", "E22", "E5");
            parser.accept(json.substring(answerEnd));
            assertThat(parser.raw()).isEqualTo(json);
            assertThat(String.join("", output)).contains("正文");
        }
    }
    @Test void doesNotExposeSplitOrUnclosedInternalReferences() {
        List<String> output = new ArrayList<>();
        AnswerDeltaParser parser = new AnswerDeltaParser(4096, output::add);
        parser.accept("{\"answer\":\"答案[E");
        parser.accept("12");
        assertThat(String.join("", output)).isEqualTo("答案");
        parser.accept("] 完成\"}");
        assertThat(String.join("", output)).isEqualTo("答案 完成");
    }
    @Test void rejectsBadEscapesAndOversizedOutput() {
        assertThatThrownBy(() -> new AnswerDeltaParser(1024, s -> {}).accept("{\"answer\":\"\\x"))
                .isInstanceOf(AnswerStreamException.class);
        assertThatThrownBy(() -> new AnswerDeltaParser(8, s -> {}).accept("{\"answer\":\"中文"))
                .isInstanceOf(AnswerStreamException.class);
    }
    @Test void refusesDuplicateAnswerFields() {
        assertThatThrownBy(() -> new AnswerDeltaParser(1024, s -> {}).accept("{\"answer\":\"a\",\"answer\":\"b\"}"))
                .isInstanceOf(AnswerStreamException.class);
    }
}
