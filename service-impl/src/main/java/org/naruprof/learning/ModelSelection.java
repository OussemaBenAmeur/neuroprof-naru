package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.*;
import org.naruprof.ports.*;
import org.naruprof.infra.Refusal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ModelSelection {
    static final Map<String,String> ROLE_SLOTS = Map.of(
            "researcher","cheap","demoBuilder","cheap","extractor","cheap","auditor","cheap",
            "visualMaker","vision","sideQuestion","strong");
    static ObjectNode tier(ProfileStore.View tx,String tier,String fixture) {
        var settings = tx.get("settings","current");
        var selected = settings == null ? null : settings.path("models").path(tier);
        if (selected instanceof ObjectNode spec && spec.path("model").isObject()) return spec;
        return fixture == null || fixture.isBlank() ? null : object("model",object("provider","learning","id",fixture));
    }
    static ObjectNode resolve(ProfileStore.View tx,String tier,String fixture,JsonNode... overrides) {
        var base = tier(tx,tier,fixture);
        var selected = base == null ? null : base.deepCopy();
        for (JsonNode override : overrides) {
            if (!override.isObject()) continue;
            if (selected == null) selected = object();
            for (var key : List.of("model","thinkingLevel","cacheRetention")) {
                if (override.hasNonNull(key)) selected.set(key,override.get(key));
            }
        }
        return selected;
    }
    static JsonNode agent(ProfileStore.View tx,String role) {
        var settings = tx.get("settings","current");
        return settings == null ? object() : settings.at("/agent/models").path(role);
    }
    static ObjectNode require(LessonRuntime runtime,ObjectNode selection,String tier) {
        if (selection == null || text(selection.path("model"),"id").isBlank())
            throw new Refusal(503,object("error","No model configured — set up your models first","code","models_not_configured","missing",List.of(tier)));
        String provider = text(selection.path("model"),"provider");
        if (!runtime.configured(provider)) throw new Refusal(503,object("error","No "+provider+" key is set — add one in Settings → Keys",
                "code","provider_key_missing","provider",provider,"slots",List.of(tier)));
        return selection;
    }
    static ObjectNode catalog(ProfileStore.View tx,LessonRuntime runtime,String fixture) {
        var slots = object();
        var roles = object();
        var choices = new LinkedHashMap<String,ObjectNode>();
        for (String tier : List.of("strong","cheap","vision")) {
            var spec = tier(tx,tier,fixture);
            slots.set(tier,spec);
            add(choices,runtime,spec);
        }
        ROLE_SLOTS.forEach((role,tier) -> {
            var spec = resolve(tx,tier,fixture,agent(tx,role));
            roles.set(role,spec == null || !spec.hasNonNull("model") ? null : spec);
            add(choices,runtime,spec);
        });
        for (var chat : tx.list("chats")) if (chat.path("settings") instanceof ObjectNode spec) add(choices,runtime,spec);
        var providers = List.of(
                object("id","openai","envVar","OPENAI_API_KEY","keyPresent",runtime.configured("openai")),
                object("id","openrouter","envVar","OPENROUTER_API_KEY","keyPresent",runtime.configured("openrouter")),
                object("id","learning","envVar","","keyPresent",runtime.configured("learning")));
        return object("models",choices.values(),"defaults",slots.get("strong"),"rolePins",roles,"slots",slots,"providers",providers);
    }
    private static void add(Map<String,ObjectNode> choices,LessonRuntime runtime,ObjectNode spec) {
        if (spec == null) return;
        String provider = text(spec.path("model"),"provider"), id = text(spec.path("model"),"id");
        if (!id.isBlank() && runtime.configured(provider)) choices.putIfAbsent(provider+":"+id,
                object("id",id,"name",id,"provider",provider,"reasoning",false,"input",List.of("text"),
                        "supportedLevels",List.of("off"),"cost",null,"contextWindow",null,"maxTokens",null));
    }
}
