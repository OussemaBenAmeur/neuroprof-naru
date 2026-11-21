package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

final class LessonTools {
    static final Map<String,List<String>> ROLES = Map.of(
            "prober",List.of("probe","recall","ask_user_question","probe_done","list_sessions","list_propositions"),
            "teacher",List.of("plan_init","quiz","explain","ask_user_question"),
            "practice",List.of("problem","conclude","ask_user_question"), "side-chat",List.of());

    static String role(ObjectNode chat) {
        if ("practice".equals(text(chat,"kind"))) return "practice";
        return new Transcript(chat).phase.equals("probe") ? "prober" : "teacher";
    }

    /** Apply calls and results in the same fenced commit. Deferred question results arrive via learner input. */
    static boolean apply(ObjectNode chat, ObjectNode assistant, GraphService graph, org.naruprof.ports.ProfileStore.Change tx) {
        var before = new Transcript(chat);
        var messages = chat.withArray("messages");
        var approved = ROLES.get(role(chat));
        messages.add(assistant);
        if (Set.of("aborted","error").contains(text(assistant,"stopReason"))) return false;
        boolean follow = false;
        boolean pending = false;
        for (var call : assistant.path("content")) {
            if (!"toolCall".equals(text(call,"type"))) continue;
            String tool = text(call,"name"), id = text(call,"id");
            JsonNode args = call.path("arguments");
            try {
                if (!approved.contains(tool)) throw new IllegalArgumentException("Tool outside role");
                if (Transcript.QUESTIONS.contains(tool)) {
                    if (text(args,"question").isBlank()) throw new IllegalArgumentException("Question text required");
                    if (Set.of("quiz","explain").contains(tool) && chat.at("/tree/nodes").valueStream()
                            .noneMatch(n -> text(n,"id").equals(text(args,"nodeId")))) throw new IllegalArgumentException("Unknown plan node");
                    pending = true;
                    continue;
                }
                ObjectNode details = object("ok",true);
                String ack;
                switch (tool) {
                    case "probe_done" -> {
                        if (!before.pending.isEmpty() || pending || args.at("/assessment/strands").isEmpty())
                            throw new IllegalArgumentException("Resolve probing before handoff");
                        ack = "Assessment recorded — it is shown to the learner now and the teacher plans from it.";
                    }
                    case "plan_init" -> {
                        var tree = PlanTree.parse(text(args,"mermaid"),chat.at("/tree/version").asInt(0)+1);
                        chat.set("tree",tree);
                        details.set("tree",tree);
                        ack = "Plan saved";
                    }
                    case "problem" -> {
                        if (before.problem != null || before.concluded || text(args,"modelSolution").isBlank() || text(args,"statement").isBlank())
                            throw new IllegalArgumentException("A new problem needs a free slot and a committed solution");
                        before.problem = new Transcript.Call(text(assistant,"id"),0,call);
                        ack = "Problem posed. Wait for learner thinking.";
                        pending = true;
                    }
                    case "conclude" -> {
                        if (before.problem == null) throw new IllegalArgumentException("No active problem");
                        details.put("problemCallId",before.problem.id());
                        details.set("outcome",args.path("outcome"));
                        before.problem = null; before.concluded = true;
                        ack = "Problem concluded. Wait for the learner's explicit Next.";
                        pending = true;
                    }
                    case "list_sessions", "list_propositions" -> {
                        details = graph.tool(tx,tool,args);
                        ack = details.toString();
                    }
                    default -> throw new IllegalArgumentException("Unknown tool");
                }
                messages.add(Transcript.result(id,tool,ack,details,false));
                if (tool.equals("probe_done")) messages.add(Transcript.marker("probe-handoff","Plan the lesson from the recorded assessment.",object("chatId",text(chat,"id"))));
                follow = true;
            } catch (IllegalArgumentException error) {
                messages.add(Transcript.result(id,tool,error.getMessage(),object("ok",false,"error",error.getMessage()),true));
                follow = true;
            }
        }
        return follow && !pending;
    }
}
