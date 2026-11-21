package org.naruprof.proof;

import static org.junit.jupiter.api.Assertions.*;
import static org.naruprof.infra.Json.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.naruprof.ports.LessonRuntime;
import org.naruprof.infra.*;
import org.naruprof.learning.*;
import tools.jackson.databind.node.ObjectNode;

class RecoveryTest {
    @TempDir Path dir;
    DurableFixture db;
    @BeforeEach void open() throws Exception { db = new DurableFixture(dir); }
    @AfterEach void close() throws Exception { db.close(); }
    static abstract class Runtime implements LessonRuntime {
        public boolean ready() { return true; }
        public boolean configured() { return true; }
    }
    @Test void failedAdmissionStartsNoRequestAndUnknownOutcomeIsNeverAutoRetried() throws Exception {
        var calls = new AtomicInteger();
        var runtime = new Runtime() {
            public ObjectNode respond(Request req,BooleanSupplier aborted,Consumer<ObjectNode> partial) {
                calls.incrementAndGet(); return message("assistant",List.of(object("type","text","text","Answer"))).put("stopReason","stop");
            }
        };
        String id;
        try (var module = new LearningModule(db,runtime,"fixture")) {
            id = text(module.create(object()),"id");
            db.failOn = "leases";
            assertThrows(IllegalStateException.class,() -> module.submit(id,object("type","text","text","draft")));
            assertEquals(0,calls.get());
            assertEquals(0,module.chat(id).path("messages").size());
            db.write(tx -> {
                var chat = tx.get("chats",id); chat.withArray("messages").add(message("user","My accepted answer"));
                tx.save("chats",id,chat);
                tx.save("leases",id,object("chatId",id,"owner","crashed-owner"));
                return null;
            });
        }
        try (var module = new LearningModule(db,runtime,"fixture")) {
            assertEquals(0,calls.get());
            assertEquals("error",module.chat(id).at("/messages/1/stopReason").asString());
            module.submit(id,object("type","retry"));
            ServletProbeTest.settled(module,id);
            assertEquals(1,calls.get());
            assertEquals(3,module.chat(id).path("messages").size());
        }
    }
    @Test void twoChatsRunInParallelDisconnectIsPassiveAndAbortKeepsPartial() throws Exception {
        var started = new CountDownLatch(2);
        var runtime = new Runtime() {
            public ObjectNode respond(Request req,BooleanSupplier aborted,Consumer<ObjectNode> partial) throws Exception {
                var msg = message("assistant",List.of(object("type","text","text","Partial")));
                partial.accept(msg); started.countDown();
                for (int i=0; !aborted.getAsBoolean() && i<1000;i++) Thread.sleep(10);
                return msg;
            }
        };
        try (var module = new LearningModule(db,runtime,"fixture")) {
            String first = text(module.create(object()),"id"), second = text(module.create(object()),"id");
            module.submit(first,object("type","text","text","A"));
            module.submit(second,object("type","text","text","B"));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class,() -> module.stream(first).consume(f -> {throw new java.io.IOException("detached");}));
            assertEquals(409,assertThrows(Refusal.class,() -> module.submit(first,object("type","continue"))).status);
            for (String id : List.of(first,second)) {
                module.abort(id); ServletProbeTest.settled(module,id);
                assertEquals("aborted",module.chat(id).at("/messages/1/stopReason").asString());
                assertEquals("Partial",module.chat(id).at("/messages/1/content/0/text").asString());
            }
        }
    }
    @Test void failedSettlementProducesNoticeAndCommittedInputSurvives() throws Exception {
        var runtime = new Runtime() {
            public ObjectNode respond(Request req,BooleanSupplier aborted,Consumer<ObjectNode> partial) {
                db.failOn = "chats"; return message("assistant",array());
            }
        };
        try (var module = new LearningModule(db,runtime,"fixture")) {
            String id = text(module.create(object()),"id");
            module.submit(id,object("type","text","text","Saved"));
            ServletProbeTest.settled(module,id);
            var frames = new ArrayList<String>(); module.stream(id).consume(frames::add);
            assertEquals("error",text(MAPPER.readTree(frames.getFirst()),"type"));
            assertEquals(1,module.chat(id).path("messages").size());
        }
    }
    @Test void outboxContractAndRollback() {
        var runtime = new Runtime() { public ObjectNode respond(Request r,BooleanSupplier a,Consumer<ObjectNode> p) { throw new AssertionError(); } };
        try (var module = new LearningModule(db,runtime,null)) {
            String profile = DurableFixture.PROFILE;
            assertEquals(new Projection(profile,0,0,0,0,0,false),module.snapshot(profile));
            module.create(object("title","Fractions"));
            module.create(object("title","Practice","kind","practice"));
            var batch = object("nodes",List.of(object("id","p","statement","Half","name","Half"),object("id","q","statement","Quarters","name","Quarters")),
                    "chains",List.of(object("id","arg_fraction","premises",List.of("p"),"conclusion","q")));
            db.failOn = "outbox";
            assertThrows(IllegalStateException.class,() -> module.mergeGraph(batch));
            assertTrue(module.graph().path("propositions").isEmpty());
            module.mergeGraph(batch); module.mergeGraph(batch);
            assertEquals(new Projection(profile,3,1,1,2,1,false),module.snapshot(profile));
            assertEquals(List.of(1L,2L,3L),module.replay(profile,0,16).stream().map(Projection::sequence).toList());
            assertTrue(module.replay(profile,3,16).isEmpty());
            assertEquals(416,assertThrows(Refusal.class,()->module.replay(profile,-1L,1)).status);
            assertEquals(404,assertThrows(Refusal.class,()->module.snapshot("other")).status);
            db.write(tx->{tx.project(true); return null;});
            assertTrue(module.snapshot(profile).deleted());
        }
    }
}
