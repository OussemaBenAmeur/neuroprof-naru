package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import org.naruprof.ports.ProfileStore;
import org.naruprof.infra.Refusal;
import tools.jackson.databind.node.ObjectNode;

final class JobService {
    ObjectNode list(ProfileStore.View tx,String chatId) {
        if (chatId == null || chatId.isBlank()) throw new Refusal(400,"chatId query parameter is required");
        if (!chatId.matches("[A-Za-z0-9_-]+")) throw new Refusal(400,"Invalid chatId");
        return object("jobs",tx.list("jobs").stream().filter(j -> chatId.equals(text(j,"chatId"))).toList(),
                "turnInFlight",tx.get("leases",chatId) != null);
    }
    void recover(ProfileStore.Change tx) {
        for (var job : tx.list("jobs")) {
            if (!"running".equals(text(job,"status")) || job.has("plugin") || "plugin".equals(text(job,"kind"))) continue;
            String jobId = text(job,"id");
            if (jobId.isBlank() || tx.get("chats",text(job,"chatId")) == null) continue;
            if (job.hasNonNull("result")) {
                boolean failed = job.path("result").hasNonNull("error");
                job.put("status",failed ? "error" : "done").put("activity",failed ? "failed" : "finished");
                if (!job.hasNonNull("delivery")) job.set("delivery",object("kind","result","status","pending"));
            } else {
                job.put("status","error").put("activity","interrupted")
                        .put("error","The worker stopped before its outcome was saved.");
                job.set("delivery",object("kind","failure","status","pending"));
            }
            tx.save("jobs",jobId,job);
        }
    }

    void deliver(ProfileStore.Change tx,ObjectNode chat) {
        String chatId = text(chat,"id");
        if (!new Transcript(chat).pending.isEmpty()) return;
        for (var job : tx.list("jobs")) {
            if (!chatId.equals(text(job,"chatId")) || !"pending".equals(job.at("/delivery/status").asString("")) || job.has("plugin")) continue;
            String id = text(job,"id"); String kind = job.at("/delivery/kind").asString("");
            if (!java.util.Set.of("abort","result","failure").contains(kind) || (kind.equals("result") && !job.hasNonNull("result"))) continue;
            var messages = chat.withArray("messages");
            if (messages.valueStream().noneMatch(m -> id.equals(m.at("/details/jobId").asString("")) && text(m,"customType").startsWith("subagent-"))) {
                var details = object("jobId",id,"toolCallId",text(job,"toolCallId"),"kind",text(job,"kind"));
                String marker = switch (kind) {
                    case "abort" -> { details.put("aborted",true); yield "subagent-abort"; }
                    case "result" -> { details.set("result",job.get("result")); yield "subagent-steer"; }
                    case "failure" -> { details.set("error",job.get("error")); yield "subagent-failure"; }
                    default -> throw new IllegalStateException("Unknown delivery type");
                };
                messages.add(Transcript.marker(marker,details.toString(),details));
            }
            job.withObject("delivery").put("status","delivered");
            tx.save("jobs",id,job);
        }
    }
}
