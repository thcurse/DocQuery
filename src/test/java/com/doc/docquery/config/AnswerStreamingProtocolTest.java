package com.doc.docquery.config;

import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.stream.AnswerExecution;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AnswerStreamingProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    @ParameterizedTest
    @CsvSource({"CHAT_COMPLETIONS,JSON_OBJECT,false", "RESPONSES,JSON_OBJECT,false", "ANTHROPIC_MESSAGES,PROMPT_ONLY,false",
            "CHAT_COMPLETIONS,JSON_SCHEMA,false", "RESPONSES,JSON_SCHEMA,false", "ANTHROPIC_MESSAGES,JSON_SCHEMA,false",
            "CHAT_COMPLETIONS,JSON_SCHEMA,true", "RESPONSES,JSON_SCHEMA,true", "ANTHROPIC_MESSAGES,JSON_SCHEMA,true"})
    void streamsUsingActualSdkAndFlushesBeforeProviderCompletion(String protocol, String outputMode, boolean truncated) throws Exception {
        var first = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var pool = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(request);
            if (!json.readTree(request).path("stream").asBoolean()) {
                exchange.sendResponseHeaders(400, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                if (protocol.equals("ANTHROPIC_MESSAGES")) {
                    out.write(sse("message_start", Map.of("type","message_start", "message", Map.of("id","m","type","message","role","assistant","model","test","content",List.of(),"usage",Map.of("input_tokens",1,"output_tokens",0)))));
                    out.write(sse("content_block_start",Map.of("type","content_block_start","index",0,"content_block",Map.of("type","text","text",""))));
                }
                String a = "{\"status\":\"ANSWERED\",\"answer\":\"测试";
                String b = "回答\",\"evidenceIds\":[\"E1\"]}";
                out.write(delta(protocol, a)); out.flush();
                try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write(delta(protocol,b));
                if (protocol.equals("CHAT_COMPLETIONS")) {
                    out.write(sse("", Map.of("id","r","model","test","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",truncated ? "length" : "stop")))));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } else if (protocol.equals("RESPONSES")) {
                    if (truncated) out.write(sse("response.incomplete", Map.of("type","response.incomplete","response",Map.of("id","r","status","incomplete","incomplete_details",Map.of("reason","max_output_tokens")))));
                    else out.write(sse("response.completed", Map.of("type","response.completed","response",Map.of("id","r","model","test","status","completed",
                            "output",List.of(Map.of("type","message","id","m","role","assistant","content",List.of(Map.of("type","output_text","text",a+b,"annotations",List.of())))),
                            "usage",Map.of("input_tokens",1,"output_tokens",10,"total_tokens",11)))));
                } else {
                    out.write(sse("content_block_stop",Map.of("type","content_block_stop","index",0)));
                    out.write(sse("message_delta",Map.of("type","message_delta","delta",Map.of("stop_reason",truncated ? "max_tokens" : "end_turn"),"usage",Map.of("output_tokens",10))));
                    out.write(sse("message_stop",Map.of("type","message_stop")));
                }
                out.flush();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var profile = new ChatProfilesProperties.Profile();
            profile.setModel("test");
            profile.setProtocol(protocol);
            profile.setFinalOutputMode(outputMode);
            if (protocol.equals("RESPONSES")) profile.setReasoningEffort("low");
            profile.setApiKey("test-key");
            profile.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            var profiles = new ChatProfilesProperties();
            profiles.setProfiles(Map.of("test",profile));
            var properties = new AnswerProperties();
            properties.setChatProfile("test");
            properties.setModelTimeout(Duration.ofSeconds(5));
            var config = new RetrievalProviderConfig();
            var gateway = config.answerAgentGateway(config.answerChatModel(profiles,properties), profiles, properties);
            List<String> text = new CopyOnWriteArrayList<>();
            var execution = new AnswerExecution(true, s -> {}, s -> { text.add(s); first.countDown(); });
            execution.begin(Duration.ofSeconds(10));
            var future = pool.submit(() -> gateway.streamFinalAnswer(
                    new AnswerAgentGateway.FinalizationRequest("Return JSON","question","{}"),
                    new AnswerAgentGateway.Observer() {
                        public void beforeModelCall() {}
                        public void toolRound(int count) {}
                        public void afterToolCall() {}
                    },execution,1024));
            assertThat(first.await(4,TimeUnit.SECONDS)).as(protocol + " first delta").isTrue();
            assertThat(future.isDone()).isFalse();
            finish.countDown();
            if (truncated) {
                var failure = catchThrowable(() -> future.get(5,TimeUnit.SECONDS));
                assertThat(failure).isInstanceOf(ExecutionException.class);
                assertThat(failure.getCause()).isInstanceOf(com.doc.docquery.service.AnswerException.class);
                assertThat(((com.doc.docquery.service.AnswerException)failure.getCause()).publicMessage()).contains("输出不完整");
            } else assertThat(future.get(5,TimeUnit.SECONDS)).contains("\"evidenceIds\"");
            assertThat(String.join("",text)).isEqualTo("测试回答");
            assertThat(requests).hasValue(1);
            var body = json.readTree(captured.get());
            if (outputMode.equals("JSON_SCHEMA")) {
                var format = protocol.equals("RESPONSES") ? body.path("text").path("format")
                        : protocol.equals("CHAT_COMPLETIONS") ? body.path("response_format").path("json_schema")
                        : body.path("output_config").path("format");
                assertThat(format.path("schema").path("required")).extracting(node -> node.asText())
                        .containsExactly("status", "answer", "evidenceIds");
                assertThat(format.path("schema").path("additionalProperties").asBoolean(true)).isFalse();
            }
            if (protocol.equals("RESPONSES"))
                assertThat(json.readTree(captured.get()).path("reasoning").path("effort").asText()).isEqualTo("low");
        } finally { finish.countDown(); server.stop(0); pool.shutdownNow(); }
    }
    private byte[] delta(String protocol, String text) {
        return switch(protocol) {
            case "CHAT_COMPLETIONS" -> sse("",Map.of("id","r","model","test","choices",List.of(Map.of("index",0,"delta",Map.of("content",text)))));
            case "RESPONSES" -> sse("response.output_text.delta",Map.of("type","response.output_text.delta","item_id","m","output_index",0,"content_index",0,"delta",text));
            default -> sse("content_block_delta",Map.of("type","content_block_delta","index",0,"delta",Map.of("type","text_delta","text",text)));
        };
    }
    private byte[] sse(String event, Object data) {
        return ((event.isEmpty() ? "" : "event: "+event+"\n")+"data: "+json.writeValueAsString(data)+"\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
