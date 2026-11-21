package org.naruprof.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.naruprof.infra.Json.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.naruprof.ports.LessonRuntime;

class NaruRuntimeTest {
    @Test void admittedHistoryReachesModelAndProbeIsDispatched(@TempDir Path directory) throws Exception {
        var calls = new AtomicInteger();
        try (var runtime = new NaruRuntime(directory, body -> {
            assertEquals("Why halves?",body.at("/messages/1/content").asString());
            assertEquals(1,body.path("tools").size());
            assertEquals("probe",body.at("/tools/0/function/name").asString());
            calls.incrementAndGet();
            var args = object("question","Which fraction is equal?","options",List.of(object("value","a","label","2/4")),
                    "explanation","Scale both by two","correctAnswer","a");
            return object("choices", List.of(object("message",object("role","assistant","tool_calls",List.of(
                    object("id","call_probe","function",object("name","probe","arguments",args.toString())))))));
        })) {
            var req = new LessonRuntime.Request("chat_a","turn_a","fixture","Teach",List.of("probe"),
                    object("messages",List.of(message("user","Why halves?"))));
            var response = runtime.respond(req, () -> false, partial -> {});
            assertEquals(1,calls.get());
            assertEquals("call_probe",response.at("/content/0/id").asString());
            assertEquals("toolUse",text(response,"stopReason"));
        }
    }
    @Test void providerEventStreamAssemblesTextToolsAndUsage() throws Exception {
        var partial = new java.util.ArrayList<String>();
        var wire = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world","tool_calls":[{"index":0,"id":"call_1","function":{"name":"probe","arguments":"{"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}],"usage":{"total_tokens":16}}

                data: [DONE]

                """;
        var result = ModelTransport.readEvents(new java.io.ByteArrayInputStream(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8)),partial::add);
        assertEquals(List.of("Hello","Hello world"),partial);
        assertEquals("call_1",result.at("/choices/0/message/tool_calls/0/id").asString());
        assertEquals("{}",result.at("/choices/0/message/tool_calls/0/function/arguments").asString());
        assertEquals(16,result.at("/usage/total_tokens").asInt());
        assertThrows(java.io.IOException.class,() -> ModelTransport.readEvents(new java.io.ByteArrayInputStream(
                wire.substring(0,wire.indexOf("data: [DONE]")).getBytes(java.nio.charset.StandardCharsets.UTF_8)),value -> {}));
    }
}
