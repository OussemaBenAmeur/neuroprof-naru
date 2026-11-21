package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.naruprof.api.Learning;
import org.naruprof.infra.Refusal;
import org.naruprof.ports.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

final class SessionService implements AutoCloseable {
    private final ProfileStore store;
    final Events events = new Events();
    private final LessonRuntime runtime;
    private final String defaultModel;
    private final SettingsService settings;
    private final JobService jobs;
    private final GraphService graph;
    private final ConcurrentMap<String,TurnStream> live = new ConcurrentHashMap<>();
    private final ConcurrentMap<String,String> failures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final String INTERRUPTED = "The previous request was interrupted. Its provider outcome is unknown. Retry explicitly.";
    private static final String SAVE_FAILED = "The reply could not be saved. Reopen the session to recover the accepted input.";

    SessionService(ProfileStore store, LessonRuntime runtime, String defaultModel, SettingsService settings, JobService jobs, GraphService graph) {
        this.store = store; this.runtime = runtime; this.defaultModel = defaultModel; this.settings = settings; this.jobs = jobs; this.graph = graph;
        if (!store.read(v -> v.snapshot().deleted())) store.write(tx -> {
            // A file-backed profile has one process owner. A lease surviving startup is orphaned.
            for (var lease : tx.list("leases")) recover(tx,text(lease,"chatId"),lease);
            jobs.recover(tx);
            return null;
        });
    }
    private static void recover(ProfileStore.Change tx,String id,ObjectNode lease) {
        var chat = tx.get("chats",id);
        if (chat != null) {
            ObjectNode reply = lease.get("partial") instanceof ObjectNode partial ? partial : message("assistant",array());
            reply.put("stopReason","error").put("errorMessage",INTERRUPTED);
            chat.withArray("messages").add(reply);
            UsageAccounting.track(chat,text(lease,"owner"),text(reply,"id"),reply);
            for (var steer : lease.path("steers")) chat.withArray("queuedSteers").add(steer);
            applyDismissals(chat,lease);
            tx.save("chats",id,chat);
        }
        tx.remove("leases",id);
    }
    static ObjectNode require(ProfileStore.View v,String id) {
        var chat = v.get("chats",id);
        if (chat == null) throw new Refusal(404,"Not found");
        return chat;
    }
    ObjectNode create(ObjectNode input) {
        String title = text(input,"title").replaceAll("\\s+"," ").strip();
        if (title.isEmpty()) title = "Untitled session";
        if (title.length() > 72) {
            int boundary = title.substring(0,72).lastIndexOf(' ');
            title = title.substring(0,boundary < 0 ? 72 : boundary)+"…";
        }
        String now = Instant.now().toString(), id = id("chat");
        var chat = object("id",id,"title",title,"version",1,"createdAt",now,"updatedAt",now,"messages",array());
        if ("practice".equals(text(input,"kind"))) chat.put("kind","practice");
        return store.write(tx -> {
            var strong = ModelSelection.tier(tx,"strong",defaultModel);
            if (strong != null) chat.set("settings",strong);
            var picks = input.at("/graphContext/propositions").valueStream()
                    .filter(p -> p.path("id").isString() && p.path("name").isString() && p.path("statement").isString())
                    .limit(64).map(p -> object("id",p.get("id"),"name",p.get("name"),"statement",p.get("statement"))).toList();
            if (!picks.isEmpty()) {
                var lines = new StringJoiner("\n");
                lines.add("[Session context] — picked from the learner's knowledge map when starting this session:");
                var names = new HashMap<String,String>();
                picks.forEach(p -> { names.put(text(p,"id"),text(p,"name")); lines.add("- ["+text(p,"id")+"] "+text(p,"name")+": "+text(p,"statement")); });
                var chains = new ArrayList<String>();
                for (var c : tx.list("chains")) {
                    var ps = c.path("premises").valueStream().map(JsonNode::asString).toList();
                    if (names.containsKey(text(c,"conclusion")) && names.keySet().containsAll(ps))
                        chains.add(String.join(" + ",ps.stream().map(names::get).toList())+" → "+names.get(text(c,"conclusion")));
                }
                chains.forEach(lines::add);
                chat.withArray("messages").add(Transcript.marker("graph-context",lines.toString(),object("propositions",picks,"chains",chains)));
            }
            tx.save("chats",id,chat);
            tx.project(false);
            return object("id",id,"title",chat.get("title"));
        });
    }

    ObjectNode list() {
        return store.read(tx -> {
            var list = tx.list("chats").stream().sorted(Comparator.comparing((ObjectNode c) -> text(c,"updatedAt")).reversed())
                    .map(c -> {
                        var t = new Transcript(c);
                        var row = c.deepCopy();
                        row.removeAll();
                        for (String key : List.of("id","title","createdAt","updatedAt","settings")) if (c.has(key)) row.set(key,c.get(key));
                        row.put("kind",c.path("kind").asString("teach")).put("phase",t.phase).put("pendingQuestion",!t.pending.isEmpty())
                                .put("pendingProblem",t.problem != null).put("messageCount",c.path("messages").size())
                                .put("busy",live.containsKey(text(c,"id"))).put("compacting",false);
                        return row;
                    }).toList();
            return object("chats",list);
        });
    }
    ObjectNode get(String id) {
        return store.read(tx -> {
            var chat = require(tx,id);
            chat.remove(List.of("imageSnapshots","queuedSteers"));
            chat.put("turnInFlight",live.containsKey(id)).put("compacting",false);
            if (failures.containsKey(id)) chat.put("turnError",failures.get(id));
            var costs = object(); int unknown = 0;
            for (var turn : chat.path("turns")) { costs.putNull(text(turn,"id")); unknown++; }
            chat.set("pricing",object("cost",0,"unpriced",unknown,"turnCosts",costs));
            return chat;
        });
    }
    ObjectNode rename(String id,ObjectNode input) {
        return store.write(tx -> {
            var chat = require(tx,id);
            String title = text(input,"title").strip();
            if (title.isBlank()) throw new Refusal(400,"title must be a non-empty string");
            chat.put("title",title);
            tx.save("chats",id,chat);
            return object("title",title);
        });
    }
    ObjectNode delete(String id,String mode) {
        if (!"preserve-graph".equals(mode) && !"delete-nodes".equals(mode)) throw new Refusal(400,object("error","mode is required and must be \"preserve-graph\" or \"delete-nodes\"","code","bad_mode"));
        return store.write(tx -> {
            require(tx,id);
            if (tx.get("leases",id) != null) throw new Refusal(409,"Turn in progress");
            var owned = tx.list("jobs").stream().filter(j -> id.equals(text(j,"chatId"))).toList();
            if (owned.stream().anyMatch(j -> Set.of("running","pending").contains(text(j,"status")))) throw new Refusal(409,"Job in progress");
            if (tx.get("extraction-leases",id) != null || (mode.equals("delete-nodes") && !tx.list("extraction-leases").isEmpty()))
                throw new Refusal(409,object("error","Extraction is in progress","code","extraction_running"));
            if (tx.list("side-leases").stream().anyMatch(lease -> id.equals(text(lease,"chatId")))) throw new Refusal(409,"Side answer in progress");
            tx.remove("chats",id);
            owned.forEach(j -> tx.remove("jobs",text(j,"id")));
            if (mode.equals("delete-nodes")) {
                GraphService.deleteSource(tx,id);
                GraphService.bumpRevision(tx);
            }
            tx.project(false);
            return object("ok",true,"mode",mode,"stats",object(),"jobsRemoved",owned.size(),"graphRevision",GraphService.revision(tx));
        });
    }

    synchronized boolean submit(String id,ObjectNode input) {
        var run = new TurnStream();
        if (live.containsKey(id)) throw new Refusal(409,"Turn already in progress");
        var request = store.write(tx -> {
            var chat = require(tx,id);
            var lease = tx.get("leases",id);
            if (lease != null) {
                if (!failures.containsKey(id)) throw new Refusal(409,"Turn already in progress");
                recover(tx,id,lease);
                chat = require(tx,id);
            }
            boolean sibling = new Transcript(chat).accept(chat,input);
            if (!sibling) ModelSelection.require(runtime,ModelSelection.resolve(tx,"strong",defaultModel,chat.path("settings")),"strong");
            chat.put("updatedAt",Instant.now().toString());
            jobs.deliver(tx,chat);
            if (!sibling) {
                var queued = chat.remove("queuedSteers");
                if (queued != null) queued.forEach(chat.withArray("messages")::add);
                SideChatService.deliver(chat);
            }
            tx.save("chats",id,chat);
            if (sibling) return null;
            tx.save("leases",id,object("chatId",id,"owner",run.owner,"acceptedAt",Instant.now().toString()));
            return request(tx,chat,run);
        });
        failures.remove(id);
        events.emit("record-changed",id);
        if (request == null) return false;
        live.put(id,run);
        events.emit("turn-started",id);
        run.emit("agent_start",null);
        executor.execute(() -> execute(run,request));
        return true;
    }

    private LessonRuntime.Request request(ProfileStore.View tx,ObjectNode chat,TurnStream run) {
        String role = LessonTools.role(chat);
        var conf = tx.get("settings","current");
        var tools = LessonTools.ROLES.get(role).stream().filter(n -> conf == null || conf.at("/agent/tools").path(n).asBoolean(true)).toList();
        var selected = ModelSelection.require(runtime,ModelSelection.resolve(tx,"strong",defaultModel,chat.path("settings")),"strong");
        return new LessonRuntime.Request(text(chat,"id"),run.owner,text(selected.path("model"),"provider"),text(selected.path("model"),"id"),settings.prompt(tx,role),tools,chat);
    }

    private void execute(TurnStream run,LessonRuntime.Request first) {
        String id = first.chatId(); var next = first;
        String notice = null;
        try {
            for (int step = 1; next != null; step++) {
                ObjectNode assistant;
                try {
                    if (step > 12) throw new IllegalStateException("Step budget exhausted");
                    assistant = runtime.respond(next,() -> run.aborted, partial -> {
                        store.write(tx -> {
                            var lease = fence(tx,id,run);
                            Transcript.stableToolIds(require(tx,id),partial);
                            lease.set("partial",partial); tx.save("leases",id,lease); return null;
                        });
                        run.emit("message_update",partial);
                    });
                } catch (Exception failed) {
                    assistant = store.read(tx -> {
                        var partial = fence(tx,id,run).get("partial");
                        return partial instanceof ObjectNode p ? p : message("assistant",array());
                    });
                    assistant.put("stopReason",run.aborted ? "aborted" : "error");
                    if (!run.aborted) assistant.put("errorMessage",INTERRUPTED);
                }
                final ObjectNode settled = assistant;
                next = store.write(tx -> {
                    var lease = fence(tx,id,run);
                    var chat = require(tx,id);
                    if (run.aborted) settled.put("stopReason","aborted");
                    Transcript.stableToolIds(chat,settled);
                    boolean follow = LessonTools.apply(chat,settled,graph,tx);
                    UsageAccounting.track(chat,run.owner,text(settled,"id"),settled);
                    applyDismissals(chat,lease);
                    var steers = lease.remove("steers");
                    if (steers != null && !steers.isEmpty()) {
                        if (!run.aborted && new Transcript(chat).pending.isEmpty()) {
                            steers.forEach(chat.withArray("messages")::add);
                            follow = true;
                        } else steers.forEach(chat.withArray("queuedSteers")::add);
                    }
                    lease.remove("dismissals");
                    chat.put("updatedAt",Instant.now().toString());
                    tx.save("chats",id,chat);
                    if (follow && !run.aborted) {
                        lease.remove("partial");
                        tx.save("leases",id,lease);
                        return request(tx,chat,run);
                    }
                    tx.remove("leases",id);
                    return null;
                });
                run.emit("message_end",settled);
            }
        } catch (RuntimeException failure) {
            notice = SAVE_FAILED;
            failures.put(id,notice);
        } finally {
            synchronized(this) { run.finish(notice); live.remove(id,run); }
            events.emit("turn-settled",id);
        }
    }
    private static ObjectNode fence(ProfileStore.View tx,String id,TurnStream run) {
        var lease = tx.get("leases",id);
        if (lease == null || !run.owner.equals(text(lease,"owner"))) throw new Refusal(409,"Turn ownership changed");
        return lease;
    }
    synchronized Learning.Subscription stream(String id) {
        var run = live.get(id);
        if (run != null) return run;
        store.read(tx -> require(tx,id));
        String notice = failures.get(id);
        return out -> {
            if (notice != null) out.send(object("type","error","errorText",notice).toString());
            out.send(object("type","turn-settled").toString());
            out.send("[DONE]");
        };
    }
    private static void applyDismissals(ObjectNode chat,ObjectNode lease) {
        for (var input : lease.path("dismissals")) {
            try { new Transcript(chat).dismiss(chat,(ObjectNode)input); }
            catch (Refusal stale) { /* Target did not survive the provider response. */ }
        }
    }

    synchronized boolean dismiss(String id,ObjectNode input) {
        String type = text(input,"type");
        if (type.equals("dismiss-problem")) {
            if (text(input,"problemCallId").isBlank()) throw new Refusal(400,"dismiss-problem input requires problemCallId");
        } else if (!type.equals("dismiss") || text(input,"toolCallId").isBlank()
                || !input.at("/answer/dismissed").isBoolean() || !input.at("/answer/dismissed").asBoolean()) {
            throw new Refusal(400,"Invalid dismissal input");
        }
        RunDecision decision = store.write(tx -> {
            var chat = require(tx,id);
            var run = live.get(id);
            var lease = tx.get("leases",id);
            if (run != null && lease != null && run.owner.equals(text(lease,"owner"))) {
                lease.withArray("dismissals").add(input);
                tx.save("leases",id,lease);
                return RunDecision.PARKED;
            }
            new Transcript(chat).dismiss(chat,input);
            tx.save("chats",id,chat);
            return RunDecision.SAVED;
        });
        if (decision == RunDecision.SAVED) events.emit("record-changed",id);
        return decision == RunDecision.PARKED;
    }
    private enum RunDecision { SAVED, PARKED }

    synchronized void steer(String id,ObjectNode input) {
        String clientId = text(input,"id");
        if (!"steer".equals(text(input,"type")) || clientId.isBlank() || text(input,"text").isBlank() || input.has("images"))
            throw new Refusal(400,"Steer requires text and a client-minted id");
        var run = live.get(id);
        store.write(tx -> {
            var chat = require(tx,id);
            if (run == null) throw new Refusal(409,"no live turn to steer");
            var lease = tx.get("leases",id);
            if (lease == null || !run.owner.equals(text(lease,"owner"))) throw new Refusal(409,"no live turn to steer");
            var pending = lease.withArray("steers");
            if (pending.valueStream().anyMatch(m -> clientId.equals(text(m,"id")))
                    || chat.path("messages").valueStream().anyMatch(m -> clientId.equals(text(m,"id")))) return null;
            pending.add(message("user",text(input,"text")).put("id",clientId));
            tx.save("leases",id,lease);
            return null;
        });
    }

    synchronized void abort(String id) {
        var run = live.get(id);
        if (run == null) throw new Refusal(409,object("ok",false,"reason","not_running"));
        run.aborted = true;
    }
    public void close() { live.values().forEach(r -> r.aborted = true); executor.close(); }
}
