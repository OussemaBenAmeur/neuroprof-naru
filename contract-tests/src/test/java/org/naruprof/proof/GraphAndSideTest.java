package org.naruprof.proof;

import static org.junit.jupiter.api.Assertions.*;
import static org.naruprof.infra.Json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.naruprof.infra.*;
import org.naruprof.learning.LearningModule;
import org.naruprof.ports.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class GraphAndSideTest {
    private static final String PROFILE = "f7933216-20db-49cb-90e0-3a9d5396966d";

    @Test void importDedupesCanonicalIdentityAndPreservesJointPremises() {
        var calls = new AtomicInteger();
        var model = fake(request -> {
            calls.incrementAndGet();
            if (request.prompt().startsWith("Check whether")) return assistant(object("links",object(),"rejected",List.of()).toString());
            return assistant(object("propositions",List.of(
                    object("id","p","name","p","statement","premise one"),
                    object("id","q","name","q","statement","premise two"),
                    object("id","r","name","r","statement","conclusion")),
                    "chains",List.of(object("premises",List.of("p","q"),"conclusion","r"))).toString());
        });
        try (var learning = new LearningModule(new TestStore(),model,"fixture")) {
            var doc = new NotesUpload("logic.md","course/logic.md","Two premises support a conclusion.".getBytes(StandardCharsets.UTF_8));
            assertEquals("completed",learning.importNotes(List.of(doc)).at("/results/0/outcome").asString());
            assertEquals("completed",learning.importNotes(List.of(doc)).at("/results/0/outcome").asString());
            assertEquals(4,calls.get(),"A deliberate reimport runs the pipeline again");
            var graph = learning.graph();
            assertEquals(2,graph.at("/chains/0/premises").size());
            assertEquals(1,graph.at("/chains/0/provenance").size());
            assertTrue(graph.path("fileSources").valueStream().anyMatch(value -> doc.path().equals(value.asString())));
            assertEquals(new Projection(PROFILE,1,0,0,3,1,false),learning.snapshot(PROFILE));
            assertEquals(1,learning.deleteGraphNode(text(graph.at("/chains/0"),"id")).at("/stats/chainsRemoved").asInt());
            assertEquals(new Projection(PROFILE,2,0,0,3,0,false),learning.snapshot(PROFILE));
            assertThrows(Refusal.class,() -> learning.importNotes(List.of(new NotesUpload("logic.md","../logic.md",doc.content()))));
            assertEquals(4,calls.get());
        }
    }

    @Test void sourceDeletionPreservesSharedProvenanceAndRemovesOnlyEmptyFolders() {
        var model = fake(request -> request.prompt().startsWith("Check whether")
                ? assistant(object("links",object(),"rejected",List.of()).toString())
                : assistant(object("propositions",List.of(object("id","p","name","p","statement","one shared proposition")),
                        "chains",List.of()).toString()));
        try (var learning = new LearningModule(new TestStore(),model,"fixture")) {
            learning.importNotes(List.of(new NotesUpload("one.md","course/one.md",new byte[]{'A'}),
                    new NotesUpload("two.md","course/two.md",new byte[]{'B'})));
            var sources = learning.sources();
            String folder = text(sources.at("/organization/folders/0"),"id");
            String first = text(sources.at("/sources/0"),"id"), second = text(sources.at("/sources/1"),"id");
            assertEquals(1,sources.at("/organization/folders").size());
            assertEquals(2,learning.graph().at("/propositions/0/provenance").size());
            learning.deleteSources(object("sourceIds",List.of(first),"folderIds",List.of(folder),"deleteSessions",false));
            assertEquals(second,text(learning.graph().at("/propositions/0/provenance/0"),"chatId"));
            assertEquals(1,learning.sources().at("/organization/folders").size());
            learning.deleteSources(object("sourceIds",List.of(second),"folderIds",List.of(folder),"deleteSessions",false));
            assertTrue(learning.graph().path("propositions").isEmpty());
            assertTrue(learning.sources().at("/organization/folders").isEmpty());
        }
    }

    @Test void sideChatWithholdsOpenAnswerAndReplaysWithoutChargingTwice() throws Exception {
        var calls = new AtomicInteger();
        var db = new TestStore();
        var model = fake(request -> {
            assertEquals(List.of(),request.tools());
            String context = request.record().path("messages").toString();
            assertTrue(context.contains("Which answer?"));
            assertFalse(context.contains("PRIVATE ANSWER"));
            assertFalse(context.contains("PRIVATE EXPLANATION"));
            calls.incrementAndGet();
            return assistant("Try applying the definition.");
        });
        try (var learning = new LearningModule(db,model,"fixture")) {
            String chat = text(learning.create(object("title","Lesson")),"id");
            db.write(tx -> {
                var saved = tx.get("chats",chat);
                saved.withArray("messages").add(object("id","saved","role","assistant","content",List.of(
                        object("type","text","text","This is the definition."),
                        object("type","toolCall","id","call_question","name","probe","arguments",object(
                                "question","Which answer?","correctAnswer","PRIVATE ANSWER","explanation","PRIVATE EXPLANATION")))));
                tx.save("chats",chat,saved);
                return null;
            });
            var input = object("requestId","request_1","text","Could you give a hint?",
                    "anchor",object("messageId","saved","partId","saved-t0","blockIndex",0,"blockText","not canonical"));
            var frames = new ArrayList<JsonNode>();
            learning.askSide(chat,input).consume(data -> frames.add(MAPPER.readTree(data)));
            assertEquals("thread",text(frames.getFirst(),"type"));
            assertEquals("done",text(frames.getLast(),"type"));
            assertEquals("Try applying the definition.",frames.getLast().at("/thread/messages/1/text").asString());
            assertEquals("This is the definition.",frames.getLast().at("/thread/anchor/blockText").asString());
            learning.askSide(chat,input).consume(data -> {});
            assertEquals(1,calls.get());
            assertEquals(2,learning.sideChats(chat).at("/threads/0/messages").size());
        }
    }

    @Test void recoveredJobDeliveryIsCommittedOnceAndWorkerIsNotRepeated() throws Exception {
        var db = new TestStore();
        String chatId;
        var calls = new AtomicInteger();
        var model = fake(request -> {
            calls.incrementAndGet();
            assertTrue(request.record().path("messages").valueStream().anyMatch(row -> "subagent-steer".equals(text(row,"customType"))));
            return assistant("Let's keep going.");
        });
        try (var initial = new LearningModule(db,model,"fixture")) { chatId = text(initial.create(object("title","Lesson")),"id"); }
        db.write(tx -> {
            tx.save("jobs","job_known",object("id","job_known","chatId",chatId,"toolCallId","call_research",
                    "kind","research","status","running","result",object("summary","A confirmed result.")));
            return null;
        });
        try (var reopened = new LearningModule(db,model,"fixture")) {
            assertEquals(0,calls.get());
            assertEquals("pending",reopened.jobs(chatId).at("/jobs/0/delivery/status").asString());
            reopened.submit(chatId,object("type","text","text","Continue the lesson."));
            reopened.stream(chatId).consume(frame -> {});
            assertEquals("delivered",reopened.jobs(chatId).at("/jobs/0/delivery/status").asString());
            assertEquals(1,calls.get());
            assertEquals(1,reopened.chat(chatId).path("messages").valueStream().filter(row -> "subagent-steer".equals(text(row,"customType"))).count());
        }
        try (var reopened = new LearningModule(db,model,"fixture")) {
            assertEquals(1,calls.get());
            assertEquals(1,reopened.chat(chatId).path("messages").valueStream().filter(row -> "subagent-steer".equals(text(row,"customType"))).count());
        }
    }

    private static ObjectNode assistant(String prose) {
        return message("assistant",List.of(object("type","text","text",prose))).put("stopReason","stop");
    }

    private static LessonRuntime fake(Function<LessonRuntime.Request,ObjectNode> reply) {
        return new LessonRuntime() {
            public boolean ready() { return true; }
            public boolean configured() { return true; }
            public ObjectNode respond(Request request,BooleanSupplier aborted,Consumer<ObjectNode> partial) {
                var result = reply.apply(request);
                partial.accept(result);
                return result;
            }
        };
    }

    static final class TestStore implements ProfileStore {
        private record Key(String collection,String id) {}
        private Map<Key,String> records = new LinkedHashMap<>();
        public synchronized <T> T read(Function<View,T> action) {
            return action.apply(new Rows(records));
        }
        public synchronized <T> T write(Function<Change,T> action) {
            var draft = new LinkedHashMap<>(records);
            var result = action.apply(new Rows(draft));
            records = draft;
            return result;
        }
        private static final class Rows extends ProfileLedger {
            private final Map<Key,String> draft;
            Rows(Map<Key,String> draft) { super(PROFILE); this.draft = draft; }
            public ObjectNode get(String collection,String id) {
                String row = draft.get(new Key(collection,id));
                return row == null ? null : (ObjectNode)MAPPER.readTree(row);
            }
            public List<ObjectNode> list(String collection) {
                return draft.keySet().stream().filter(key -> key.collection.equals(collection)).map(key -> get(collection,key.id)).toList();
            }
            public void save(String collection,String id,ObjectNode record) { draft.put(new Key(collection,id),record.toString()); }
            public void remove(String collection,String id) { draft.remove(new Key(collection,id)); }
        }
    }
}
