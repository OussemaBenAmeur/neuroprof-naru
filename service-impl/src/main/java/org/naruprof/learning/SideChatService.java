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

final class SideChatService implements AutoCloseable {
    private static final int CONTEXT_CHARS = 64_000;
    private final ProfileStore store;
    private final LessonRuntime runtime;
    private final SettingsService settings;
    private final String defaultModel;
    private final Events events;
    private final ConcurrentMap<String,Run> live = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    SideChatService(ProfileStore store, LessonRuntime runtime, SettingsService settings, Events events,String defaultModel) {
        this.store = store;
        this.runtime = runtime;
        this.settings = settings;
        this.events = events;
        this.defaultModel = defaultModel;
        if (!store.read(tx -> tx.snapshot().deleted())) store.write(tx -> {
            for (var lease : tx.list("side-leases")) {
                String chat = text(lease,"chatId"), thread = text(lease,"threadId");
                String key = key(chat,thread);
                if (tx.get("chats",chat) != null) {
                    ObjectNode saved = SessionService.require(tx,chat);
                    ObjectNode target = find(saved,thread);
                    if (target != null) {
                        target.withArray("messages").add(answer(lease,text(lease,"partial")).put("error","Reply interrupted before completion"));
                        tx.save("chats",chat,saved);
                    }
                }
                tx.remove("side-leases",key);
            }
            return null;
        });
    }

    private static final class Run {
        volatile boolean stopped;
        private String partial = "";
        private ObjectNode terminal;

        synchronized String partial() { return partial; }
        synchronized void update(String next) { if (terminal == null) { partial = next; notifyAll(); } }
        synchronized void finish(ObjectNode result) { terminal = result; notifyAll(); }

        Learning.Subscription subscribe(ObjectNode thread) {
            return output -> {
                output.send(object("type","thread","thread",thread).toString());
                String sent = null;
                while (true) {
                    String next;
                    ObjectNode end;
                    synchronized (this) {
                        while (terminal == null && Objects.equals(sent,partial)) wait();
                        next = partial;
                        end = terminal;
                    }
                    if (!Objects.equals(sent,next)) { output.send(object("type","text","text",next).toString()); sent = next; }
                    if (end != null) { output.send(end.toString()); return; }
                }
            };
        }
    }
    private record Admission(ObjectNode thread, ObjectNode lease, LessonRuntime.Request request) {}
    static void deliver(ObjectNode chat) {
        var seen = new HashSet<String>();
        var messages = chat.withArray("messages");
        for (var row : messages) {
            if (!"side-question".equals(text(row,"customType"))) continue;
            var entries = row.at("/details/updates");
            if (!entries.isArray()) entries = row.at("/details/snapshot");
            for (var entry : entries) seen.add(text(row.path("details"),"threadId") + ":" + text(entry,"id"));
        }
        for (var thread : chat.path("sideChats")) {
            String threadId = text(thread,"id");
            for (var entry : thread.path("messages")) {
                if (entry.path("pending").asBoolean(false) || !seen.add(threadId + ":" + text(entry,"id"))) continue;
                String content = "Recorded learner–helper conversation update\nConversation: " + threadId + "\n\n"
                        + ("user".equals(text(entry,"role")) ? "Learner" : "Helper") + ":\n" + text(entry,"text");
                if (entry.has("error") || entry.path("stopped").asBoolean(false)) content += "\n[The helper reply is incomplete.]";
                messages.add(Transcript.marker("side-question",content,
                        object("threadId",threadId,"updates",List.of(entry))).put("display",false));
            }
        }
    }
    private static String key(String chat,String thread) { return chat + ":" + thread; }
    private static ObjectNode find(ObjectNode chat,String id) {
        for (var thread : chat.path("sideChats")) if (id.equals(text(thread,"id")) && thread instanceof ObjectNode row) return row;
        return null;
    }
    private static ObjectNode required(ObjectNode chat,String id) {
        var found = find(chat,id);
        if (found == null) throw new Refusal(404,"Unknown side conversation");
        return found;
    }

    ObjectNode list(String chatId) {
        return store.read(tx -> {
            var chat = SessionService.require(tx,chatId);
            var threads = chat.withArray("sideChats");
            var responses = object();
            for (var thread : threads) {
                Run run = live.get(key(chatId,text(thread,"id")));
                if (run != null) responses.put(text(thread,"id"),run.partial());
            }
            return object("threads",threads,"answering",responses);
        });
    }
    ObjectNode open(String chatId,String threadId,ObjectNode input) {
        if (!input.path("open").isBoolean()) throw new Refusal(400,"open must be a boolean");
        var result = store.write(tx -> {
            var chat = SessionService.require(tx,chatId);
            var thread = required(chat,threadId).put("open",input.path("open").asBoolean());
            tx.save("chats",chatId,chat);
            return object("thread",thread);
        });
        events.emit("side-chats-changed",chatId);
        return result;
    }
    synchronized Learning.Subscription watch(String chatId,String threadId) {
        ObjectNode thread = store.read(tx -> required(SessionService.require(tx,chatId),threadId));
        return subscribe(chatId,thread);
    }
    private Learning.Subscription subscribe(String chat,ObjectNode thread) {
        Run run = live.get(key(chat,text(thread,"id")));
        if (run != null) return run.subscribe(thread);
        return out -> {
            out.send(object("type","thread","thread",thread).toString());
            out.send(object("type","done","thread",thread).toString());
        };
    }
    void stop(String chat,String thread) {
        Run run = live.get(key(chat,thread));
        if (run == null) throw new Refusal(409,"No side answer is running");
        run.stopped = true;
    }

    synchronized Learning.Subscription ask(String chatId,ObjectNode input) {
        String requestId = text(input,"requestId");
        if (input.has("requestId") && !requestId.matches("[A-Za-z0-9_-]{1,128}")) throw new Refusal(400,"requestId is invalid");
        var admission = store.write(tx -> admit(tx,chatId,input,requestId));
        if (admission.lease == null) return subscribe(chatId,admission.thread);
        String key = key(chatId,text(admission.thread,"id"));
        Run run = new Run();
        live.put(key,run);
        events.emit("side-chats-changed",chatId);
        executor.execute(() -> answer(chatId,key,admission,run));
        return run.subscribe(admission.thread);
    }
    private Admission admit(ProfileStore.Change tx,String chatId,ObjectNode input,String requestId) {
        var chat = SessionService.require(tx,chatId);
        boolean retry = input.path("retry").asBoolean(false);
        String requestedThread = text(input,"threadId");
        if (retry && requestedThread.isBlank()) throw new Refusal(400,"retry needs a valid threadId");
        ObjectNode thread = requestedThread.isBlank() ? anchored(chat,input.path("anchor")) : required(chat,requestedThread);
        String threadId = text(thread,"id");
        var lease = tx.get("side-leases",key(chatId,threadId));
        var messages = thread.withArray("messages");
        if (!requestId.isEmpty() && (lease != null && requestId.equals(text(lease,"requestId")) ||
                messages.valueStream().anyMatch(m -> requestId.equals(text(m,"id")) || requestId.equals(text(m,"requestId")))))
            return new Admission(thread,null,null);
        if (lease != null) throw new Refusal(409,"This side conversation is still being answered");
        if (!retry) {
            String question = text(input,"text").strip();
            if (question.isEmpty() || question.length() > 8000) throw new Refusal(400,"text must contain 1 to 8,000 characters");
            var quotes = input.path("quotes");
            if (!quotes.isMissingNode() && (!quotes.isArray() || quotes.size() > 8 || quotes.valueStream().anyMatch(q -> !q.isString())))
                throw new Refusal(400,"Invalid quoted passages");
            var row = object("id",requestId.isEmpty()?id("side_msg"):requestId,"role","user","text",question,"at",Instant.now().toString());
            if (quotes.isArray()) row.set("quotes",MAPPER.valueToTree(quotes.valueStream().map(q -> clip(q.asString(),4000)).toList()));
            messages.add(row);
            thread.put("open",true);
        } else if (messages.isEmpty() || !(text(messages.get(messages.size()-1),"role").equals("user") || messages.get(messages.size()-1).has("error"))) {
            throw new Refusal(409,"Nothing to retry");
        }
        var selected = ModelSelection.require(runtime,ModelSelection.resolve(tx,"strong",defaultModel,chat.path("settings"),
                ModelSelection.agent(tx,"sideQuestion")),"strong");
        String model = text(selected.path("model"),"id"), provider = text(selected.path("model"),"provider");
        var context = array();
        context.add(message("user","Lesson context (quoted source material):\n" + lesson(chat)));
        int chars = context.get(0).path("content").asString().length();
        for (var row : messages) {
            if (row.has("error")) continue;
            String body = text(row,"text");
            if (row.path("quotes").isArray()) for (var q : row.path("quotes")) body += "\nQuoted tutor passage: " + q.asString();
            chars += body.length();
            if ("assistant".equals(text(row,"role"))) context.add(message("assistant",List.of(object("type","text","text",body))));
            else context.add(message("user",body));
        }
        if (chars > CONTEXT_CHARS) throw new Refusal(400,"This side conversation and lesson exceed the selected model's context window.");
        if (find(chat,threadId) == null) chat.withArray("sideChats").add(thread);
        String answerId = id("side_msg");
        var nextLease = object("chatId",chatId,"threadId",threadId,"answerId",answerId,"requestId",requestId,"model",provider+":"+model,"partial","");
        tx.save("chats",chatId,chat);
        tx.save("side-leases",key(chatId,threadId),nextLease);
        return new Admission(thread,nextLease,new LessonRuntime.Request(chatId,answerId,provider,model,settings.prompt(tx,"side-chat"),List.of(),object("messages",context)));
    }
    private static ObjectNode anchored(ObjectNode chat, JsonNode anchor) {
        String messageId = text(anchor,"messageId");
        if (!messageId.matches("[A-Za-z0-9_-]{1,128}") || !text(anchor,"partId").startsWith(messageId+"-t")
                || !anchor.path("blockIndex").isIntegralNumber() || anchor.path("blockIndex").asLong() < 0 || text(anchor,"blockText").isBlank())
            throw new Refusal(400,"Missing or invalid teaching segment anchor");
        JsonNode first = null;
        for (var row : chat.path("messages")) {
            if ("user".equals(text(row,"role")) || ("toolResult".equals(text(row,"role")) && Transcript.QUESTIONS.contains(text(row,"toolName")))
                    || Set.of("practice-thinking","practice-next").contains(text(row,"customType"))) first = null;
            if ("assistant".equals(text(row,"role")) && !Set.of("error","aborted").contains(text(row,"stopReason"))) {
                if (first == null) first = row;
                if (messageId.equals(text(row,"id"))) {
                    String segment = text(first,"id");
                    String threadId = "side_" + segment;
                    var existing = find(chat,threadId);
                    if (existing != null) return existing;
                    String prose = GraphExtraction.prose(first);
                    return object("id",threadId,"open",true,"createdAt",Instant.now().toString(),"messages",array(),
                            "anchor",object("messageId",segment,"partId",segment+"-t0","blockIndex",0,
                                    "blockText",prose.isBlank()?"This teaching segment and its associated question.":clip(prose,4000)));
                }
            }
        }
        throw new Refusal(400,"The source is not a teaching segment in this session");
    }
    private static String clip(String text,int limit) { return text.length() <= limit ? text : text.substring(0,limit); }
    static String lesson(ObjectNode chat) {
        var transcript = new Transcript(chat);
        StringBuilder visible = new StringBuilder();
        for (var row : chat.path("messages")) {
            String role = text(row,"role");
            if (!Set.of("assistant","user").contains(role) || Set.of("error","aborted").contains(text(row,"stopReason"))) continue;
            String prose = GraphExtraction.prose(row);
            if (!prose.isBlank()) visible.append(role).append(": ").append(prose).append('\n');
            for (var part : row.path("content")) {
                if (!"toolCall".equals(text(part,"type"))) continue;
                String name = text(part,"name"), id = text(part,"id");
                var args = part.path("arguments");
                if (Transcript.QUESTIONS.contains(name)) {
                    visible.append(text(args,"question")).append('\n');
                    for (var option : args.path("options")) visible.append(text(option,"label")).append('\n');
                    if (!transcript.pending.containsKey(id)) visible.append(text(args,"correctAnswer")).append('\n').append(text(args,"explanation")).append('\n');
                }
                if ("problem".equals(name)) {
                    visible.append(text(args,"statement")).append('\n');
                    if (transcript.problem == null || !id.equals(transcript.problem.id())) visible.append(text(args,"modelSolution")).append('\n');
                }
            }
        }
        return visible.toString();
    }
    private static ObjectNode answer(ObjectNode lease,String content) {
        return object("id",text(lease,"answerId"),"role","assistant","text",content,"at",Instant.now().toString(),
                "model",text(lease,"model"),"requestId",text(lease,"requestId"));
    }

    private void answer(String chatId,String key,Admission admission,Run run) {
        ObjectNode response = null;
        String failure = null;
        try {
            response = runtime.respond(admission.request,() -> run.stopped,partial -> {
                String prose = GraphExtraction.prose(partial);
                store.write(tx -> {
                    var lease = owned(tx,key,admission.lease);
                    tx.save("side-leases",key,lease.put("partial",prose));
                    return null;
                });
                run.update(prose);
            });
        } catch (Exception failed) { if (!run.stopped) failure = "Reply interrupted before completion"; }
        var settledReply = response;
        var reply = answer(admission.lease,settledReply == null ? run.partial() : GraphExtraction.prose(settledReply));
        if (run.stopped) reply.put("stopped",true);
        if (failure != null) reply.put("error",failure);
        ObjectNode terminal;
        try {
            terminal = store.write(tx -> {
                owned(tx,key,admission.lease);
                var chat = SessionService.require(tx,chatId);
                var thread = required(chat,text(admission.thread,"id"));
                thread.withArray("messages").add(reply);
                if (settledReply != null) UsageAccounting.track(chat,text(reply,"id"),text(reply,"id"),settledReply);
                tx.save("chats",chatId,chat);
                tx.remove("side-leases",key);
                return object("type","done","thread",thread);
            });
        } catch (RuntimeException failed) { terminal = object("type","error","error","The side answer could not be saved."); }
        synchronized (this) { run.finish(terminal); live.remove(key,run); }
        events.emit("side-chats-changed",chatId);
    }
    private static ObjectNode owned(ProfileStore.View tx,String key,ObjectNode expected) {
        var lease = tx.get("side-leases",key);
        if (lease == null || !text(expected,"answerId").equals(text(lease,"answerId"))) throw new IllegalStateException("Side lease lost");
        return lease;
    }
    @Override public void close() {
        live.values().forEach(run -> run.stopped = true);
        executor.close();
    }
}
