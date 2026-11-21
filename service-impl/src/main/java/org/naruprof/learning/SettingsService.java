package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.naruprof.ports.ProfileStore;
import org.naruprof.infra.Refusal;
import tools.jackson.databind.node.ObjectNode;

final class SettingsService {
    static final List<String> SECTIONS = List.of("general","chat","agent","appearance","graph","compaction","models","dictation","claudeCode");
    private final Map<String,String> segments = new LinkedHashMap<>();
    SettingsService() {
        for (var key : read("segments.txt").lines().toList()) segments.put(key, read(key));
    }
    private static String read(String name) {
        try (var stream = Objects.requireNonNull(SettingsService.class.getResourceAsStream("/prompts/"+name))) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    ObjectNode get(ProfileStore.View tx) { return object("settings",tx.get("settings","current")); }
    ObjectNode set(ProfileStore.Change tx,String section,ObjectNode body) {
        if (!SECTIONS.contains(section)) throw new Refusal(404,object("error","unknown settings section \""+section+"\"","supported",SECTIONS));
        if (section.equals("models")) for (var tier : body.propertyNames()) {
            if (!Set.of("strong","cheap","vision").contains(tier) || !body.get(tier).isObject())
                throw new Refusal(400,"Invalid model slot");
            var model = body.get(tier).get("model");
            if (model != null && !model.isNull() && (text(model,"id").isBlank() || text(model,"provider").isBlank())) throw new Refusal(400,"Invalid model selection");
        }
        var settings = tx.get("settings","current");
        if (settings == null) settings = object("version",1);
        settings.set(section,body);
        tx.save("settings","current",settings);
        return object("settings",settings);
    }
    private List<String> paths(String role) {
        List<String> paths = new ArrayList<>();
        if (!role.equals("practice") && !role.equals("side-chat")) paths.add("system-prompt.md");
        segments.keySet().stream().filter(k -> k.startsWith(role+"/")).forEach(paths::add);
        segments.keySet().stream().filter(k -> k.startsWith("shared/")).filter(k -> !role.equals("practice") ||
                k.matches("shared/(10|15|16|35|40)-.*")).forEach(paths::add);
        return paths;
    }
    ObjectNode segments(ProfileStore.View tx) {
        var out = object(); var settings = tx.get("settings","current");
        for (String role : List.of("prober","teacher","practice")) {
            var rows = out.putArray(role);
            for (String key : paths(role)) {
                var override = tx.get("prompt-overrides",key);
                rows.add(object("path",key,"sourceText",segments.get(key),"overrideText",override == null ? null : override.get("text"),
                        "enabled",override != null && settings != null && settings.at("/agent/prompts").path(key).asBoolean(false),
                        "shared",key.startsWith("shared/") || key.equals("system-prompt.md")));
            }
        }
        return out;
    }
    ObjectNode override(ProfileStore.Change tx,String key,ObjectNode input) {
        if (!segments.containsKey(key) || key.startsWith("side-chat/")) throw new Refusal(400,"invalid segment path");
        if (input != null) {
            if (!input.path("text").isString()) throw new Refusal(400,"body must be {text: string}");
            var result = object("path",key,"text",input.get("text"));
            tx.save("prompt-overrides",key,result);
            return result;
        }
        var settings = tx.get("settings","current");
        boolean cleared = false;
        if (settings != null && settings.at("/agent/prompts") instanceof ObjectNode switches) {
            cleared = switches.path(key).asBoolean(false);
            switches.remove(key); tx.save("settings","current",settings);
        }
        tx.remove("prompt-overrides",key);
        return object("path",key,"deleted",true,"switchCleared",cleared);
    }
    String prompt(ProfileStore.View tx, String role) {
        var settings = tx.get("settings","current"); var parts = new StringJoiner("\n\n");
        for (String key : paths(role)) {
            var saved = tx.get("prompt-overrides",key);
            boolean enabled = settings != null && settings.at("/agent/prompts").path(key).asBoolean(false);
            parts.add(enabled && saved != null ? text(saved,"text") : segments.get(key));
        }
        return parts.toString();
    }
}
