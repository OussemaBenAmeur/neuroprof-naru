package org.naruprof.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.naruprof.infra.Json.*;
import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.naruprof.contract.learning.v1.*;
import org.naruprof.grpc.ProjectionServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class LearningApplicationIT {
    private static final String PROFILE = "f7933216-20db-49cb-90e0-3a9d5396966d";

    @Test void servletNaruAndJpaReopenWithGrpcProjection(@TempDir Path directory) throws Exception {
        System.setProperty("java.awt.headless","true");
        var exchanges = new AtomicInteger();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/v1/chat/completions", request -> {
            try (request) {
                var input = MAPPER.readTree(request.getRequestBody().readAllBytes());
                var history = input.path("messages");
                ObjectNode assistant;
                if (exchanges.incrementAndGet() == 1) {
                    assertEquals("Why halves?",text(history.get(1),"content"));
                    var arguments = object("question","Which fraction is equal to one half?",
                            "options",List.of(object("value","a","label","2/4"),object("value","b","label","1/4")),
                            "correctAnswer","a","explanation","Scale the numerator and denominator by two.");
                    assistant = object("role","assistant","tool_calls",List.of(object("id","call_fraction","type","function",
                            "function",object("name","probe","arguments",arguments.toString()))));
                } else {
                    assertEquals("call_fraction",text(history.get(history.size()-1),"tool_call_id"));
                    assistant = object("role","assistant","content","Both parts doubled.");
                }
                var reply = object("choices",List.of(object("message",assistant))).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type","application/json");
                request.sendResponseHeaders(200,reply.length);
                request.getResponseBody().write(reply);
            }
        });
        provider.start();
        try {
            String id;
            try (var first = open(directory,provider.getAddress().getPort())) {
                var client = HttpClient.newHttpClient();
                id = text(json(client,first,"/api/chats",object("title","Fractions")),"id");
                assertTrue(json(client,first,"/api/chat",object("chatId",id,"input",object("type","text","text","Why halves?"))).path("ok").asBoolean());
                awaitTurn(client,first,id);
                assertEquals("call_fraction",json(client,first,"/api/chats/"+id,null).at("/messages/1/content/0/id").asString());
                json(client,first,"/api/chat",object("chatId",id,"input",object("type","answer","toolCallId","call_fraction",
                        "answer",object("kind","quiz","correct",true))));
                awaitTurn(client,first,id);
                assertEquals(1,projection(first).getSummary().getTeachingSessions());
            }
            try (var reopened = open(directory,provider.getAddress().getPort())) {
                var chat = json(HttpClient.newHttpClient(),reopened,"/api/chats/"+id,null);
                assertTrue(chat.at("/messages/2/details/correct").asBoolean());
                assertEquals("Both parts doubled.",chat.at("/messages/3/content/0/text").asString());
                assertEquals(1,projection(reopened).getSequence());
                assertEquals(2,exchanges.get());
            }
        } finally { provider.stop(0); }
    }

    private static ServletWebServerApplicationContext open(Path directory,int providerPort) {
        return (ServletWebServerApplicationContext)new SpringApplicationBuilder(Application.class).run(
                "--server.address=127.0.0.1","--server.port=0","--spring.h2.console.enabled=false",
                "--VERTX_TO_NARU_TOKEN=fixture-vertx","--TWIN_TO_NARU_TOKEN=fixture-twin","--NARU_GRPC_TARGET=127.0.0.1:0",
                "--NARUPROF_PROFILE_ID="+PROFILE,"--NARUPROF_DATA_DIR="+directory,"--LH_AUTH_FILE="+directory.resolve("auth.json"),
                "--NARUPROF_MODEL_ID=fixture","--NARUPROF_MODEL_URL=http://127.0.0.1:"+providerPort+"/v1/chat/completions");
    }
    private static JsonNode json(HttpClient client,ServletWebServerApplicationContext app,String path,ObjectNode body) throws Exception {
        return MAPPER.readTree(call(client,app,path,body));
    }
    private static void awaitTurn(HttpClient client,ServletWebServerApplicationContext app,String id) throws Exception {
        assertTrue(call(client,app,"/api/chats/"+id+"/stream",null).endsWith("data: [DONE]\n\n"));
    }
    private static String call(HttpClient client,ServletWebServerApplicationContext app,String path,ObjectNode body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path))
                .timeout(Duration.ofSeconds(30)).header("Authorization","Bearer fixture-vertx");
        if (body != null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var result = client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode(),result.body());
        return result.body();
    }
    private static ProjectionState projection(ServletWebServerApplicationContext app) throws Exception {
        var transport = NettyChannelBuilder.forAddress("127.0.0.1",app.getBean(ProjectionServer.class).port()).usePlaintext().build();
        try {
            var auth = new Metadata();
            auth.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer fixture-twin");
            return LearningProjectionGrpc.newBlockingStub(transport)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(auth)).withDeadlineAfter(5,TimeUnit.SECONDS)
                    .getSnapshot(Profile.newBuilder().setProfileId(PROFILE).build());
        } finally { transport.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
    }
}
