package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

final class UsageAccounting {
    private static final Map<String,String> FIELDS = Map.of(
            "input","inputTokens","output","outputTokens","cacheRead","cacheReadTokens",
            "cacheWrite","cacheWriteTokens","cacheWrite1h","cacheWrite1hTokens","reasoning","reasoningTokens");

    static void track(ObjectNode chat,String turnId,String messageId,ObjectNode reply) {
        if (!(reply.path("usage") instanceof ObjectNode usage)) return;
        var step = object();
        FIELDS.forEach((nativeName,persisted) -> {
            var count = usage.path(nativeName);
            if (count.isIntegralNumber() && count.asLong(-1) >= 0) step.set(persisted,count);
        });
        if (step.isEmpty()) return;
        var rows = chat.withArray("turns");
        for (var row : rows) {
            if (turnId.equals(text(row,"id"))) {
                ((ObjectNode)row).withArray("steps").add(step);
                return;
            }
        }
        rows.add(object("id",turnId,"messageId",messageId,"at",Instant.now().toString(),
                "model",text(reply,"provider")+":"+text(reply,"model"),"steps",array().add(step)));
    }
}
