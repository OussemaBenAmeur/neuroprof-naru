package org.naruprof.infra;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private Json() {}
    public static ObjectNode object(Object... pairs) {
        ObjectNode obj = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.set((String) pairs[i], MAPPER.valueToTree(pairs[i + 1]));
        }
        return obj;
    }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }
    public static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    public static String text(JsonNode node, String field) { return node.path(field).asString(""); }
    public static ObjectNode message(String role, Object content) {
        return object("id", id("msg"), "role", role, "content", content, "timestamp", System.currentTimeMillis());
    }
}
