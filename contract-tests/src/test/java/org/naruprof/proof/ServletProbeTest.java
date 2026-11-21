package org.naruprof.proof;

import static org.naruprof.infra.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.naruprof.http.*;
import org.naruprof.learning.LearningModule;
import org.naruprof.runtime.FixtureRuntime;
import org.naruprof.ports.ProfileStore;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.ObjectNode;

class ServletProbeTest {
    private static MockMvc client(LearningModule service) {
        return MockMvcBuilders.standaloneSetup(new LearningController(service)).addFilters(new ServiceAuth("test-only")).build();
    }
    private static ObjectNode send(MockMvc client,String path,ObjectNode body,int expected) throws Exception {
        var res = client.perform(post(path).header("Authorization","Bearer test-only")
                .contentType("application/json").content(body.toString())).andReturn().getResponse();
        assertEquals(expected,res.getStatus());
        return (ObjectNode) MAPPER.readTree(res.getContentAsByteArray());
    }
    private static ObjectNode read(MockMvc client,String id) throws Exception {
        var res = client.perform(get("/api/chats/"+id).header("Authorization","Bearer test-only")).andReturn().getResponse();
        assertEquals(200,res.getStatus());
        return (ObjectNode) MAPPER.readTree(res.getContentAsByteArray());
    }
    static void settled(LearningModule module,String id) throws Exception {
        var frames = new ArrayList<String>();
        module.stream(id).consume(frames::add);
        assertEquals("[DONE]",frames.getLast());
    }
    @Test void secondClientReopensAnsweredProbeAfterRestart(@TempDir Path dir) throws Exception {
        scenario(dir, () -> new DurableFixture(dir), store -> ((DurableFixture)store).close());
    }
    @Test void secondClientReopensAnsweredProbeThroughServlet(@TempDir Path dir) throws Exception {
        var db = new GraphAndSideTest.TestStore();
        scenario(dir, () -> db, store -> {});
    }
    @FunctionalInterface private interface StoreFactory { ProfileStore open() throws Exception; }
    @FunctionalInterface private interface StoreClose { void close(ProfileStore store) throws Exception; }

    private void scenario(Path dir,StoreFactory factory,StoreClose close) throws Exception {
        var count = new AtomicInteger();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var runtime = FixtureRuntime.create(dir.resolve("runtime"), body -> {
            var msgs = body.path("messages");
            if (count.incrementAndGet() == 1) {
                started.countDown();
                try { assertTrue(release.await(8,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                assertEquals("Why halves?",text(msgs.get(1),"content"));
                var args = object("question","Which is one half?","correctAnswer","a","options",List.of(
                        object("label","2/4","value","a"),object("label","1/4","value","b")),"explanation","Scale numerator and denominator.");
                return object("choices",List.of(object("message",object("role","assistant","tool_calls",
                        List.of(object("id","call_fraction","function",object("name","probe","arguments",args.toString())))))));
            }
            assertEquals("call_fraction",text(msgs.get(msgs.size()-1),"tool_call_id"));
            assertTrue(msgs.get(msgs.size()-1).path("content").asString().contains("correct"));
            return object("choices",List.of(object("message",object("role","assistant","content","Both parts doubled."))));
        })) {
            var db = factory.open(); String id;
            try (var module = new LearningModule(db,runtime,"fixture")) {
                var a = client(module); var b = client(module);
                assertEquals(401,a.perform(get("/api/chats")).andReturn().getResponse().getStatus());
                var created = send(a,"/api/chats",object("title","Fractions"),200);
                id = text(created,"id"); var topic = object("chatId",id,"input",object("type","text","text","Why halves?"));
                send(a,"/api/chat",topic,200);
                assertTrue(started.await(6,TimeUnit.SECONDS));
                assertEquals("Why halves?",read(b,id).at("/messages/0/content").asString());
                send(b,"/api/chat",topic,409);
                release.countDown();
                settled(module,id);
                var question = read(b,id).at("/messages/1"); assertEquals("call_fraction",question.at("/content/0/id").asString());
                var answer = object("type","answer","toolCallId","call_fraction","answer",object("kind","quiz","correct",true));
                answer.set("occurrence",object("messageId","stale","toolName","probe","toolCallId","call_fraction","contentIndex",0));
                send(b,"/api/chat",object("chatId",id,"input",answer),400);
                ((ObjectNode)answer.get("occurrence")).put("messageId",text(question,"id"));
                send(b,"/api/chat",object("chatId",id,"input",answer),200);
                settled(module,id);
            } finally { release.countDown(); close.close(db); }
            var reopened = factory.open();
            try (var module = new LearningModule(reopened,runtime,"fixture")) {
                var chat = read(client(module),id);
                assertEquals(4,chat.path("messages").size());
                assertEquals("call_fraction",chat.at("/messages/2/toolCallId").asString());
                assertTrue(chat.at("/messages/2/details/correct").asBoolean());
                assertEquals("Both parts doubled.",chat.at("/messages/3/content/0/text").asString());
                assertEquals(2,count.get());
            } finally { close.close(reopened); }
        }
    }
}
