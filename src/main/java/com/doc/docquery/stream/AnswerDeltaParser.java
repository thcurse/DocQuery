package com.doc.docquery.stream;

import java.util.function.Consumer;

/** Incremental JSON string decoder. Final JSON validity remains the existing validator's job. */
public final class AnswerDeltaParser {
    private final StringBuilder raw = new StringBuilder();
    private final StringBuilder token = new StringBuilder();
    private final StringBuilder pendingWord = new StringBuilder();
    private final StringBuilder bracket = new StringBuilder();
    private final StringBuilder outgoing = new StringBuilder();
    private final Consumer<String> sink;
    private final int maxBytes;
    private boolean previousHigh;
    private int bytes, depth, unicodeDigits, unicodeValue;
    private boolean inString, escaped, keyString, expectingKey, answerString, answerSeen;
    private String field = "";
    private char highSurrogate;

    public AnswerDeltaParser(int maxBytes, Consumer<String> sink) {
        this.maxBytes = maxBytes;
        this.sink = sink;
    }
    public void accept(String fragment) {
        for (char c : fragment.toCharArray()) {
            bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isLowSurrogate(c) && previousHigh ? 1 : 3;
            previousHigh = Character.isHighSurrogate(c);
        }
        if (bytes > maxBytes) throw invalid();
        raw.append(fragment);
        for (int i = 0; i < fragment.length(); i++) consume(fragment.charAt(i));
        flush();
    }
    public String raw() { return raw.toString(); }
    private void consume(char c) {
        if (!inString) {
            if (c == '{' || c == '[') { depth++; if (depth == 1) expectingKey = true; }
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 1) expectingKey = true;
            else if (c == ':' && depth == 1) expectingKey = false;
            else if (c == '"') {
                inString = true;
                keyString = depth == 1 && expectingKey;
                answerString = depth == 1 && !keyString && "answer".equals(field);
                if (answerString && answerSeen) throw invalid();
                if (answerString) answerSeen = true;
                token.setLength(0);
            }
            return;
        }
        if (unicodeDigits > 0) {
            int digit = Character.digit(c, 16);
            if (digit < 0) throw invalid();
            unicodeValue = unicodeValue * 16 + digit;
            if (--unicodeDigits == 0) decoded((char) unicodeValue);
        } else if (escaped) {
            escaped = false;
            switch (c) {
                case 'u' -> { unicodeDigits = 4; unicodeValue = 0; }
                case '"', '\\', '/' -> decoded(c);
                case 'n' -> decoded('\n');
                case 'r' -> decoded('\r');
                case 't' -> decoded('\t');
                case 'b' -> decoded('\b');
                case 'f' -> decoded('\f');
                default -> throw invalid();
            }
        } else if (c == '\\') escaped = true;
        else if (c == '"') {
            inString = false;
            if (highSurrogate != 0) throw invalid();
            if (keyString) field = token.toString();
            if (answerString) {
                if (!bracket.isEmpty()) { appendFiltered(bracket.toString()); bracket.setLength(0); }
                flushWord();
            }
            answerString = false;
        } else {
            if (c < 0x20) throw invalid();
            decoded(c);
        }
    }
    private void decoded(char c) {
        if (Character.isHighSurrogate(c)) {
            if (highSurrogate != 0) throw invalid();
            highSurrogate = c;
            return;
        }
        if (highSurrogate != 0) {
            if (!Character.isLowSurrogate(c)) throw invalid();
            decodedText(new String(new char[]{highSurrogate, c}));
            highSurrogate = 0;
        } else {
            if (Character.isLowSurrogate(c)) throw invalid();
            decodedText(String.valueOf(c));
        }
    }
    private void decodedText(String text) {
        if (keyString) token.append(text);
        if (!answerString) return;
        for (char c : text.toCharArray()) {
            if (c == '[' || !bracket.isEmpty()) {
                bracket.append(c);
                if (bracket.length() > 4096) throw invalid();
                if (c == ']') {
                    String group = bracket.toString();
                    bracket.setLength(0);
                    if (!group.matches("(?i)\\[\\s*E[1-9][0-9]*(?:\\s*[,，]\\s*E[1-9][0-9]*)*\\s*]"))
                        appendFiltered(group);
                }
            } else appendFiltered(String.valueOf(c));
        }
    }
    private void appendFiltered(String text) {
        for (char c : text.toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) pendingWord.append(c);
            else { flushWord(); outgoing.append(c); }
        }
    }
    private void flushWord() {
        if (!pendingWord.toString().matches("(?i)[ERS][1-9][0-9]*")) outgoing.append(pendingWord);
        pendingWord.setLength(0);
    }
    private void flush() {
        if (!outgoing.isEmpty()) { sink.accept(outgoing.toString()); outgoing.setLength(0); }
    }
    private AnswerStreamException invalid() {
        return new AnswerStreamException("ANSWER_OUTPUT_INVALID", "回答格式无效，请重试", true);
    }
}
