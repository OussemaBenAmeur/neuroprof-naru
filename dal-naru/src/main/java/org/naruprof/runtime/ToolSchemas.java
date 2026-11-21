package org.naruprof.runtime;

import static org.naruprof.infra.Json.*;
import java.util.*;
import tools.jackson.databind.node.ObjectNode;

final class ToolSchemas {
    static ObjectNode forTool(String name) {
        List<String> fields = switch (name) {
            case "probe", "quiz" -> List.of("question","options","explanation","correctAnswer");
            case "recall","explain" -> List.of("question","correctAnswer");
            case "ask_user_question" -> List.of("question");
            case "probe_done" -> List.of("assessment");
            case "plan_init" -> List.of("mermaid");
            case "problem" -> List.of("statement","modelSolution","mode");
            case "conclude" -> List.of("outcome","verdict");
            default -> List.of();
        };
        var required = new ArrayList<>(fields);
        if (Set.of("quiz","explain").contains(name)) { required.add("nodeId"); required.add("type"); }
        var properties = object();
        for (String field : required) properties.set(field, switch (field) {
            case "options" -> object("type","array","items", object("type","object","properties",
                    object("label",object("type","string"),"value",object("type","string")), "required",List.of("label","value")));
            case "assessment" -> object("type","object","properties",object("strands",object("type","array","items",
                    object("type","object","properties",object("strand",object("type","string"),"assessment",object("type","string")))),
                    "summary",object("type","string")), "required",List.of("strands"));
            // Closed vocabularies carried over from the LearnHarness schemas: without the enum and
            // description a real model cannot know these required values (only the fixture sent them).
            case "mode" -> choice("REQUIRED — the demand mode: recall, discrimination, or composition (bare words, no quotes).",
                    "recall","discrimination","composition");
            case "outcome" -> choice("REQUIRED — the outcome: solved, solvedWithHints, movedOn, or surrendered — bare words, no quotes.",
                    "solved","solvedWithHints","movedOn","surrendered");
            case "type" -> choice("REQUIRED — the question's role in the teaching cycle: predict = posed BEFORE you teach the step; "
                    + "check = posed AFTER the step's teaching is complete.", "predict","check");
            case "nodeId" -> object("type","string","description",
                    "REQUIRED plan-tree node id this question is about — must EXACTLY match one of this session's plan_init node slugs.");
            default -> object("type","string");
        });
        return object("type","object","properties",properties,"required",required);
    }
    private static ObjectNode choice(String description, String... values) {
        return object("type","string","enum",List.of(values),"description",description);
    }
}
