package org.naruprof.runtime;

import static org.naruprof.infra.Json.*;
import java.util.*;
import net.thevpc.naru.api.model.*;
import tools.jackson.databind.JsonNode;

final class NativeHistory {
    private static final String INTERRUPTED =
            "The question was interrupted — the learner never answered; continue without holding a result for it.";

    static List<NaruMessage> modelMessages(String prompt, JsonNode record) {
        List<NaruMessage> result = new ArrayList<>();
        result.add(NaruMessage.system(prompt));
        List<JsonNode> history = record.path("messages").valueStream().toList();
        JsonNode summary = null;
        for (var row : history) if ("compaction".equals(text(row,"customType"))) summary = row;
        int first = 0;
        if (summary != null && summary.at("/details/firstKeptMessageId").isString()) {
            String startId = summary.at("/details/firstKeptMessageId").asString();
            for (int i=1;i<history.size();i++) {
                if (!startId.equals(text(history.get(i),"id"))) continue;
                first = i;
                result.add(NaruMessage.user(content(summary.path("content"))));
                break;
            }
        }
        Map<String,JsonNode> results = new HashMap<>();
        for (int i=first;i<history.size();i++) {
            var row = history.get(i);
            if ("toolResult".equals(text(row,"role"))) results.putIfAbsent(text(row,"toolCallId"),row);
        }
        for (int i=first;i<history.size();i++) {
            JsonNode m = history.get(i);
            String prose = content(m.path("content"));
            switch (text(m,"role")) {
                case "user" -> result.add(NaruMessage.user(prose));
                case "custom" -> {
                    String type = text(m,"customType");
                    if (!Set.of("plan-block","problem-dismissed").contains(type)
                            && !(first > 0 && "compaction".equals(type))
                            && !("side-question".equals(type) && prose.isEmpty())) result.add(NaruMessage.user(prose));
                }
                case "assistant" -> {
                    if (Set.of("aborted","error").contains(text(m,"stopReason"))) continue;
                    var calls = new ArrayList<NaruToolCall>();
                    for (var part : m.path("content")) if ("toolCall".equals(text(part,"type")))
                        calls.add(new NaruToolCall(text(part,"id"),text(part,"name"),MAPPER.convertValue(part.path("arguments"), Map.class)));
                    result.add(NaruMessage.assistantWithToolCalls(prose,calls));
                    for (var call : calls) {
                        var answer = results.get(call.getId());
                        result.add(NaruMessage.tool(call.getName(),call.getId(),answer == null ? INTERRUPTED : content(answer.path("content"))));
                    }
                }
            }
        }
        return result;
    }
    private static String content(JsonNode content) {
        if (content.isString()) return content.asString();
        StringJoiner lines = new StringJoiner("\n");
        for (var part : content) if ("text".equals(text(part,"type"))) lines.add(text(part,"text"));
        return lines.toString();
    }
}
