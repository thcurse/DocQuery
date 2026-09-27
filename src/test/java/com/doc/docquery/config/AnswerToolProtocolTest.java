package com.doc.docquery.config;

import com.doc.docquery.service.AnswerAgentGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Locked SDK serialization preserves the autonomous multi-tool conversation across protocols. */
class AnswerToolProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String BODY_A = "原文A完整关系".repeat(160);
    private static final String BODY_B = "原文B表头单位与脚注".repeat(160);

    @ParameterizedTest
    @ValueSource(strings = {"RESPONSES", "CHAT_COMPLETIONS", "ANTHROPIC_MESSAGES"})
    void preservesToolPairsAndCompleteEvidenceAcrossAutonomousCalls(String protocol) throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            int turn = requests.size();
            List<Map<String,Object>> calls = turn == 1 ? List.of(
                    call("c1", "search", Map.of("query","comparison")),
                    call("c2", "open", Map.of("ref","R2")))
                    : turn == 2 ? List.of(call("c3", "open", Map.of("ref","R1")))
                    : List.of(call("c4", "submit_evidence", Map.of("evidenceIds",List.of("E1"),"readRefs",List.of("R1"))));
            byte[] bytes = json.writeValueAsBytes(response(protocol,calls));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try (var out=exchange.getResponseBody()) { out.write(bytes); } finally { exchange.close(); }
        });
        server.start();
        try {
            var profile = new ChatProfilesProperties.Profile();
            profile.setModel("test"); profile.setProtocol(protocol); profile.setApiKey("test-key");
            profile.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            var profiles = new ChatProfilesProperties(); profiles.setProfiles(Map.of("test",profile));
            var props = new AnswerProperties(); props.setChatProfile("test"); props.setModelTimeout(Duration.ofSeconds(5));
            var config = new RetrievalProviderConfig();
            var gateway = config.answerAgentGateway(config.answerChatModel(profiles,props),profiles,props);
            var observer = new AnswerAgentGateway.Observer() {
                public void beforeModelCall() {} public void toolRound(int n) {} public void afterToolCall() {}
            };
            String result = gateway.start(new AnswerAgentGateway.Request("Only canonical evidence",6),
                    (name,args) -> {
                        if (name.equals("search")) return json.writeValueAsString(Map.of("status","OK","candidates",List.of(packageValue("R1","E1",BODY_A,0))));
                        String ref = json.readTree(args).path("ref").asText();
                        return json.writeValueAsString(packageValue(ref,ref.equals("R1") ? "E1" : "E2",ref.equals("R1") ? BODY_A : BODY_B,ref.equals("R1") ? 0 : 10000));
                    },observer).next(json.writeValueAsString(Map.of("question","Compare A and B", "initialSearch",
                            Map.of("status","OK","candidates",List.of(packageValue("R1","E1",BODY_A,0))))));
            assertThat(result).contains("E1","R1");
            assertThat(requests).hasSize(3);
            String second = json.writeValueAsString(requests.get(1));
            assertThat(occurrences(second,BODY_A)).as(protocol+" initial and search evidence A").isEqualTo(2);
            assertThat(occurrences(second,BODY_B)).as(protocol+" unique B").isEqualTo(1);
            String third = json.writeValueAsString(requests.get(2));
            assertThat(occurrences(third,BODY_A)).as(protocol+" full evidence A retained across reads").isEqualTo(3);
            assertThat(occurrences(third,BODY_B)).as(protocol+" full evidence B retained").isEqualTo(1);
            assertPairs(requests.get(1),protocol,List.of("c1","c2"));
            assertPairs(requests.get(2),protocol,List.of("c1","c2","c3"));
            JsonNode first = requests.get(0);
            assertThat(first.path("response_format").path("type").asText()).isNotEqualTo("json_schema");
            assertThat(first.path("text").path("format").path("type").asText()).isNotEqualTo("json_schema");
        } finally { server.stop(0); }
    }
    private Map<String,Object> packageValue(String readRef,String id,String text,int start) {
        return Map.of("status","OK","documentRef","D1","readRef",readRef,"readScope","PAGE",
                "evidence",List.of(Map.of("evidenceId",id,"canonicalStart",start,"canonicalEnd",start+text.length(),
                        "text",text,"pageNumber",start==0 ? 1 : 2,"headingPath",List.of("Comparison"))));
    }
    private Map<String,Object> call(String id,String name,Map<String,Object> args) { return Map.of("id",id,"name",name,"args",args); }
    private Object response(String protocol,List<Map<String,Object>> calls) {
        if(protocol.equals("RESPONSES")) return Map.of("id","r","model","test","status","completed","output",calls.stream().map(c->
                Map.of("type","function_call","id","f-"+c.get("id"),"call_id",c.get("id"),"name",c.get("name"),"arguments",json.writeValueAsString(c.get("args")))).toList());
        if(protocol.equals("CHAT_COMPLETIONS")) return Map.of("id","r","model","test","choices",List.of(Map.of("index",0,"finish_reason","tool_calls","message",
                Map.of("role","assistant","tool_calls",calls.stream().map(c->Map.of("id",c.get("id"),"type","function","function",Map.of("name",c.get("name"),"arguments",json.writeValueAsString(c.get("args"))))).toList()))));
        return Map.of("id","m","type","message","role","assistant","model","test","content",calls.stream().map(c->
                Map.of("type","tool_use","id",c.get("id"),"name",c.get("name"),"input",c.get("args"))).toList(),
                "stop_reason","tool_use","usage",Map.of("input_tokens",20,"output_tokens",20));
    }
    private void assertPairs(JsonNode request,String protocol,List<String> expected) {
        List<String> calls = new ArrayList<>(), results = new ArrayList<>();
        collectPairs(request,protocol,calls,results);
        assertThat(calls).containsExactlyElementsOf(expected);
        assertThat(results).containsExactlyElementsOf(expected);
    }
    private void collectPairs(JsonNode n,String protocol,List<String> calls,List<String> results) {
        if(n.isObject()) {
            String type=n.path("type").asText();
            if(protocol.equals("RESPONSES")) {
                if(type.equals("function_call")) calls.add(n.path("call_id").asText());
                if(type.equals("function_call_output")) results.add(n.path("call_id").asText());
            } else if(protocol.equals("ANTHROPIC_MESSAGES")) {
                if(type.equals("tool_use")) calls.add(n.path("id").asText());
                if(type.equals("tool_result")) results.add(n.path("tool_use_id").asText());
            } else {
                if(type.equals("function") && n.has("id") && n.has("function")) calls.add(n.path("id").asText());
                if(n.path("role").asText().equals("tool")) results.add(n.path("tool_call_id").asText());
            }
        }
        if(n.isObject() || n.isArray()) for(JsonNode child:n) collectPairs(child,protocol,calls,results);
    }
    private int occurrences(String source,String value) {
        int count=0,offset=0;
        while((offset=source.indexOf(value,offset))>=0){count++;offset+=value.length();}
        return count;
    }
}
