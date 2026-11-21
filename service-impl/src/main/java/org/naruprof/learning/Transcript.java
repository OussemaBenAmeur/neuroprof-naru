package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.*;
import org.naruprof.infra.Refusal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

final class Transcript {
    static final Set<String> QUESTIONS = Set.of("probe","recall","ask_user_question","explain","quiz");
    record Call(String messageId, int index, JsonNode block) {
        String id() { return text(block,"id"); }
        String name() { return text(block,"name"); }
        boolean matches(JsonNode target) {
            return messageId.equals(text(target,"messageId")) && index == target.path("contentIndex").asInt(-1)
                    && id().equals(text(target,"toolCallId")) && name().equals(text(target,"toolName"));
        }
    }

    final Map<String,Call> pending = new LinkedHashMap<>();
    final Set<String> used = new HashSet<>();
    String phase = "probe";
    Call problem;
    boolean concluded;
    Transcript(ObjectNode chat) {
        Map<String,Call> calls = new HashMap<>();
        for (JsonNode m : chat.path("messages")) {
            for (var p : m.path("content")) if ("toolCall".equals(text(p,"type"))) used.add(text(p,"id"));
            if ("assistant".equals(text(m,"role")) && !Set.of("aborted","error").contains(text(m,"stopReason"))) {
                int index = 0;
                for (var part : m.path("content")) {
                    if ("toolCall".equals(text(part,"type"))) {
                        var c = new Call(text(m,"id"),index,part);
                        calls.put(c.id(),c);
                        if (QUESTIONS.contains(c.name())) pending.put(c.id(),c);
                        if (!phase.equals("probe") && Set.of("quiz","explain").contains(c.name())
                                && part.path("arguments").hasNonNull("nodeId")) phase = "teach";
                    }
                    index++;
                }
            }
            if ("toolResult".equals(text(m,"role"))) {
                String callId = text(m,"toolCallId");
                pending.remove(callId);
                var c = calls.get(callId);
                if (c != null && !m.path("isError").asBoolean(false)) {
                    if ("probe_done".equals(c.name()) && m.at("/details/ok").asBoolean(false) && phase.equals("probe")) phase = "plan";
                    if ("problem".equals(c.name())) { problem = c; concluded = false; }
                    if ("conclude".equals(c.name())) { problem = null; concluded = true; }
                }
            }
            if ("problem-dismissed".equals(text(m,"customType")) && problem != null
                    && problem.id().equals(m.at("/details/problemCallId").asString(""))) { problem = null; concluded = true; }
            if ("practice-next".equals(text(m,"customType"))) concluded = false;
        }
    }

    static void stableToolIds(ObjectNode history,ObjectNode output) {
        var used = new Transcript(history).used;
        int index = 0;
        for (var part : output.path("content")) {
            if (part instanceof ObjectNode call && "toolCall".equals(text(call,"type"))) {
                String candidate = text(call,"id");
                if (candidate.isBlank() || !used.add(candidate)) {
                    String seed = text(output,"id")+":"+index;
                    String next;
                    int salt = 0;
                    do {
                        next = "call_" + UUID.nameUUIDFromBytes((seed+":"+salt++).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","");
                    } while (!used.add(next));
                    call.put("id",next);
                }
            }
            index++;
        }
    }

    /** Returns true when an answered sibling still owns the question dock. */
    boolean accept(ObjectNode chat, ObjectNode input) {
        var messages = chat.withArray("messages");
        String type = text(input,"type");
        switch (type) {
            case "text" -> {
                String value = text(input,"text");
                if (value.isBlank() || input.has("images")) throw new Refusal(400,"Text input requires text without attachments");
                messages.add(message("user",value));
            }
            case "answer" -> {
                Call call = pending.values().stream().findFirst().orElse(null);
                if (call == null || !call.id().equals(text(input,"toolCallId"))) throw new Refusal(400,"answer input targets no pending question call");
                if (input.has("occurrence") && !call.matches(input.get("occurrence"))) throw new Refusal(400,"Submission target is no longer active. Refresh the session.");
                if (!(input.get("answer") instanceof ObjectNode answer) || answer.has("images")) throw new Refusal(400,"Invalid graded answer");
                messages.add(result(call.id(),call.name(),compactAnswer(answer).toString(),answer,false));
                return pending.size() > 1;
            }
            case "retry" -> {
                if (messages.isEmpty() || !"error".equals(text(messages.get(messages.size()-1),"stopReason"))) throw new Refusal(400,"retry input requires a failed turn to retry");
            }
            case "continue" -> {
                if (!pending.isEmpty() || messages.isEmpty() || !"toolResult".equals(text(messages.get(messages.size()-1),"role")))
                    throw new Refusal(400,"No answered question to continue");
            }
            case "thinking" -> {
                if (problem == null || !problem.id().equals(text(input,"problemCallId")) || text(input,"text").isBlank()
                        || (input.has("occurrence") && !problem.matches(input.path("occurrence")))) throw new Refusal(400,"No matching active problem");
                messages.add(marker("practice-thinking","Your thinking:\n"+text(input,"text"),
                        object("problemCallId",problem.id(),"text",text(input,"text"))));
            }
            case "next" -> {
                if (!"practice".equals(text(chat,"kind")) || problem != null) throw new Refusal(400,"next input requires a practice session with no active problem");
                messages.add(marker("practice-next","[NEXT PROBLEM] — the learner's explicit go-ahead: pose the next problem now per the selection rules, or wrap the session in text if the arc is complete.",object()));
            }
            default -> throw new Refusal(400,"unknown input type: "+type);
        }
        return false;
    }
    void dismiss(ObjectNode chat,ObjectNode input) {
        var messages = chat.withArray("messages");
        if ("dismiss-problem".equals(text(input,"type"))) {
            if (problem == null || !problem.id().equals(text(input,"problemCallId"))) throw new Refusal(400,"dismiss-problem input targets no active problem");
            messages.add(marker("problem-dismissed","",object("problemCallId",problem.id())));
            return;
        }
        Call call = pending.values().stream().findFirst().orElse(null);
        if (call == null || !call.id().equals(text(input,"toolCallId"))) throw new Refusal(400,"dismiss input targets no pending question call");
        var answer = (ObjectNode)input.get("answer");
        String kind = switch(call.name()) { case "probe","quiz" -> "quiz"; case "explain","recall" -> "explain"; default -> "ask"; };
        if (!kind.equals(text(answer,"kind"))) throw new Refusal(400,"dismiss input's answer kind does not match the question");
        messages.add(result(call.id(),call.name(),compactAnswer(answer).toString(),answer,false));
    }
    static ObjectNode result(String id, String name, String line, JsonNode details, boolean error) {
        var m = message("toolResult",List.of(object("type","text","text",line)));
        m.put("toolCallId",id).put("toolName",name).put("isError",error);
        if (details != null) m.set("details",details);
        return m;
    }
    static ObjectNode marker(String type, String line, ObjectNode details) {
        var m = message("custom",line);
        m.put("customType",type).put("display",true);
        m.set("details",details);
        return m;
    }
    static ObjectNode compactAnswer(ObjectNode answer) {
        var out = object();
        for (String field : List.of("kind","dismissed","dontKnow","text","other","correct","confidence","thinking"))
            if (answer.has(field)) out.set(field,answer.get(field));
        if (answer.path("dismissed").asBoolean(false)) { out.removeAll(); out.set("kind",answer.get("kind")); out.put("dismissed",true); return out; }
        if (answer.has("readingThinking")) out.set("thinkingWhileReading",answer.get("readingThinking"));
        if (!out.has("thinking") && answer.has("note")) out.set("thinking",answer.get("note"));
        if (out.path("dontKnow").asBoolean(false)) out.remove("correct");
        if (answer.path("answers").isArray() && !answer.path("answers").isEmpty()) {
            var choices = out.putArray("answers");
            for (var a : answer.path("answers")) choices.add(object("index",a.path("index"),"label",a.path("label")));
        }
        return out;
    }
}
