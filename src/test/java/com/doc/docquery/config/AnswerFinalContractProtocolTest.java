package com.doc.docquery.config;

import com.doc.docquery.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnswerFinalContractProtocolTest {
    final ObjectMapper json = new ObjectMapper();
    static final String ANSWER = "{\"status\":\"ANSWERED\",\"answer\":\"A\",\"evidenceIds\":[\"E1\"]}";
    @ParameterizedTest
    @CsvSource({"RESPONSES,valid", "CHAT_COMPLETIONS,valid", "ANTHROPIC_MESSAGES,valid",
            "RESPONSES,length", "CHAT_COMPLETIONS,length", "ANTHROPIC_MESSAGES,length",
            "RESPONSES,invalid", "CHAT_COMPLETIONS,invalid", "ANTHROPIC_MESSAGES,invalid",
            "RESPONSES,unavailable", "CHAT_COMPLETIONS,unavailable", "ANTHROPIC_MESSAGES,unavailable"})
    void sdkSeparatesToolAndFinalFormatsAndDoesNotRetry(String protocol, String scenario) throws Exception {
        var captured = new ArrayList<tools.jackson.databind.JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            var body = json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            captured.add(body);
            boolean tool = body.path("tools").isArray() && !body.path("tools").isEmpty();
            int code = scenario.equals("invalid") ? 400 : scenario.equals("unavailable") ? 503 : 200;
            Object payload = code == 200 ? response(protocol,tool,scenario.equals("length"))
                    : Map.of("type","error","error",Map.of("type",code == 400 ? "invalid_request_error" : "overloaded_error",
                    "message","max_output_tokens limit incompatible; PRIVATE_PROVIDER_BODY"));
            byte[] bytes = json.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(code,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);} finally {exchange.close();}
        });
        server.start();
        try {
            var profile=new ChatProfilesProperties.Profile();profile.setModel("test");profile.setProtocol(protocol);
            profile.setFinalOutputMode("JSON_SCHEMA");profile.setMaxRetries(2);
            profile.setApiKey("test");profile.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            var profiles=new ChatProfilesProperties();profiles.setProfiles(Map.of("test",profile));
            var properties=new AnswerProperties();properties.setChatProfile("test");properties.setModelTimeout(Duration.ofSeconds(5));
            var config=new RetrievalProviderConfig();
            var gateway=config.answerAgentGateway(config.answerChatModel(profiles,properties),profiles,properties);
            var observer=new AnswerAgentGateway.Observer(){public void beforeModelCall(){}public void toolRound(int n){}public void afterToolCall(){}};
            if(scenario.equals("valid")) {
                gateway.start(new AnswerAgentGateway.Request("tools",3),(name,args)->"{}",observer).next("question");
                var tools=captured.get(0);
                assertThat(tools.path("text").path("format").path("type").asText()).isNotEqualTo("json_schema");
                assertThat(tools.path("response_format").path("type").asText()).isNotEqualTo("json_schema");
                assertThat(tools.path("output_config").path("format").isMissingNode()).isTrue();
                assertThat(gateway.finalizeAnswer(new AnswerAgentGateway.FinalizationRequest("JSON","question","{}"),observer)).isEqualTo(ANSWER);
                var finalBody=captured.get(captured.size()-1);
                var format=protocol.equals("RESPONSES") ? finalBody.path("text").path("format")
                        : protocol.equals("CHAT_COMPLETIONS") ? finalBody.path("response_format").path("json_schema")
                        : finalBody.path("output_config").path("format");
                assertThat(format.path("schema").path("required")).extracting(n->n.asText()).containsExactly("status","answer","evidenceIds");
                assertThat(captured).hasSize(2);
            } else {
                var failure=catchThrowableOfType(AnswerException.class,()->gateway.finalizeAnswer(
                        new AnswerAgentGateway.FinalizationRequest("JSON","question","{}"),observer));
                assertThat(failure.reason()).isEqualTo(scenario.equals("length") ? AnswerException.Reason.OUTPUT_INVALID
                        : scenario.equals("invalid") ? AnswerException.Reason.MODEL_REQUEST_INVALID : AnswerException.Reason.MODEL_UNAVAILABLE);
                if(scenario.equals("length")) assertThat(failure.publicMessage()).contains("输出不完整");
                assertThat(failure.getMessage()).doesNotContain("PRIVATE_PROVIDER_BODY");
                assertThat(captured).hasSize(1);
            }
        } finally {server.stop(0);}
    }
    Object response(String protocol, boolean tool, boolean length) {
        var selection=Map.of("evidenceIds",List.of("E1"),"readRefs",List.of());
        if(protocol.equals("RESPONSES")) {
            Object output=tool ? Map.of("type","function_call","id","f1","call_id","c1","name","submit_evidence","arguments",json.writeValueAsString(selection))
                    : Map.of("type","message","id","m1","role","assistant","content",List.of(Map.of("type","output_text","text",ANSWER,"annotations",List.of())));
            var body=new LinkedHashMap<String,Object>();body.put("id","r");body.put("model","test");body.put("status",length ? "incomplete" : "completed");body.put("output",List.of(output));
            if(length)body.put("incomplete_details",Map.of("reason","max_output_tokens"));
            return body;
        }
        if(protocol.equals("CHAT_COMPLETIONS")) {
            Object message=tool ? Map.of("role","assistant","tool_calls",List.of(Map.of("id","c1","type","function","function",Map.of("name","submit_evidence","arguments",json.writeValueAsString(selection)))))
                    : Map.of("role","assistant","content",ANSWER);
            return Map.of("id","r","model","test","choices",List.of(Map.of("index",0,"message",message,"finish_reason",length ? "length" : tool ? "tool_calls" : "stop")));
        }
        return Map.of("id","m1","type","message","role","assistant","model","test","content",List.of(tool ? Map.of("type","tool_use","id","c1","name","submit_evidence","input",selection)
                : Map.of("type","text","text",ANSWER)),"stop_reason",length ? "max_tokens" : tool ? "tool_use" : "end_turn","usage",Map.of("input_tokens",1,"output_tokens",10));
    }
}
