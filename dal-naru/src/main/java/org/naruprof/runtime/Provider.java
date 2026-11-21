package org.naruprof.runtime;

import static org.naruprof.infra.Json.*;
import java.util.*;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

final class Provider extends AbstractNaruModelProvider implements NaruModelProtocol {
    private final ModelTransport transport;
    private final String model;
    private final java.util.function.Consumer<String> partial;
    private final java.util.function.BooleanSupplier aborted;
    Provider(ModelTransport transport, String model, java.util.function.Consumer<String> partial, java.util.function.BooleanSupplier aborted) {
        super("learning", new String[0]);
        this.transport = transport;
        this.model = model;
        this.partial = partial;
        this.aborted = aborted;
    }
    public List<String> findModelIds(NaruSession session) { return List.of(model); }
    public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig config, NaruSession session) {
        return NOptional.of(this);
    }
    public NaruModelCapabilities getCapabilities() {
        return new NaruModelCapabilities() {
            public long contextLength() { return -1; }
            public boolean isVision() { return false; }
            public boolean isTools() { return true; }
            public boolean isThinking() { return false; }
            public boolean isEmbedding() { return false; }
            public boolean isTextOnly() { return false; }
            public boolean isStreaming() { return false; }
            public Set<String> keys() { return Set.of("tools"); }
            public NElement toElement() { return NElement.ofObjectBuilder().set("tools", true).build(); }
        };
    }
    public NaruResponse chat(NaruModelRequest request, NaruTask task) {
        var messages = array();
        for (var msg : request.messages()) {
            var row = object("role", msg.getRole().name(), "content", msg.getContent());
            if (msg.getToolCallId() != null) row.put("tool_call_id", msg.getToolCallId());
            if (msg.hasToolCalls()) {
                var calls = row.putArray("tool_calls");
                for (var call : msg.getToolCalls()) calls.add(object("type", "function", "id", call.getId(),
                        "function", object("name", call.getName(), "arguments", MAPPER.writeValueAsString(call.getArguments()))));
            }
            messages.add(row);
        }
        var tools = array();
        for (var definition : request.tools()) tools.add(object("type", "function", "function",
                object("name", definition.getName(), "description", definition.getDescription(),
                        "parameters", ToolSchemas.forTool(definition.getName()))));
        ObjectNode body = object("model", model, "messages", messages);
        if (!tools.isEmpty()) body.set("tools", tools);
        try {
            ObjectNode result = transport.stream(body,partial,aborted);
            JsonNode choice = result.path("choices").path(0);
            JsonNode msg = choice.path("message");
            if (!msg.isObject()) throw new IllegalStateException("Missing assistant message");
            List<NaruToolCall> calls = new ArrayList<>();
            for (var call : msg.path("tool_calls")) {
                var fn = call.path("function");
                var args = MAPPER.readTree(text(fn,"arguments"));
                if (!args.isObject() || text(call,"id").isBlank()) throw new IllegalStateException("Invalid tool call");
                calls.add(new NaruToolCall(text(call,"id"), text(fn,"name"), MAPPER.convertValue(args, Map.class)));
            }
            var usage = result.path("usage");
            return new NaruResponse(NaruMessage.assistantWithToolCalls(text(msg,"content"), calls), true,
                    text(choice,"finish_reason"), usage.path("total_tokens").asInt(0),
                    usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0))
                    .setCacheReadTokens(usage.path("prompt_tokens_details").path("cached_tokens").asInt(0));
        } catch (Exception ex) {
            throw new IllegalStateException("Model exchange failed");
        }
    }
}
