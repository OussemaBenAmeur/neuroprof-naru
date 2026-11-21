package org.naruprof.runtime;

import static org.naruprof.infra.Json.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import tools.jackson.databind.node.*;

/** One provider exchange per call. A failed or timed-out POST has an unknown billing outcome. */
@FunctionalInterface
interface ModelTransport {
    ObjectNode exchange(ObjectNode request) throws Exception;

    default ObjectNode stream(ObjectNode request, Consumer<String> partial, BooleanSupplier aborted) throws Exception {
        return exchange(request);
    }

    static ModelTransport http(URI endpoint, String key) {
        return http(endpoint,() -> key);
    }
    static ModelTransport http(URI endpoint,Supplier<String> keySource) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var failures = new AtomicInteger();
        var reopen = new AtomicLong();
        return new ModelTransport() {
            public ObjectNode exchange(ObjectNode request) throws Exception {
                return stream(request,ignored -> {},() -> false);
            }
            public ObjectNode stream(ObjectNode request,Consumer<String> partial,BooleanSupplier aborted) throws Exception {
                if (System.nanoTime() < reopen.get()) throw new IllegalStateException("Model circuit open");
                if (aborted.getAsBoolean()) throw new CancellationException();
                var wire = request.deepCopy().put("stream",true).set("stream_options",object("include_usage",true));
                var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");
                String key = keySource.get();
                if (key != null && !key.isBlank()) builder.header("Authorization","Bearer "+key);
                var response = client.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(wire.toString())).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                long end = System.nanoTime() + Duration.ofSeconds(65).toNanos();
                Thread guard = Thread.ofVirtual().start(() -> {
                    try {
                        while (!aborted.getAsBoolean() && System.nanoTime() < end) Thread.sleep(50);
                        if (!response.cancel(true) && !response.isCompletedExceptionally()) {
                            var received = response.getNow(null);
                            if (received != null) received.body().close();
                        }
                    } catch (IOException ignored) {
                        // Only the consuming thread decides whether the response completed.
                    } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                });
                try {
                    var received = response.get();
                    try (var source = received.body()) {
                        if (received.statusCode()/100 != 2) throw new IllegalStateException("Model request refused");
                        ObjectNode result = received.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")
                                ? readEvents(source,partial) : readJson(source);
                        failures.set(0);
                        return result;
                    }
                } catch (Exception failure) {
                    if (!aborted.getAsBoolean() && failures.incrementAndGet() >= 3)
                        reopen.set(System.nanoTime() + Duration.ofSeconds(20).toNanos());
                    throw failure;
                } finally { guard.interrupt(); }
            }
        };
    }

    private static ObjectNode readJson(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(8_388_609);
        if (bytes.length > 8_388_608) throw new IOException("Model response too large");
        return (ObjectNode)MAPPER.readTree(bytes);
    }

    static ObjectNode readEvents(InputStream input,Consumer<String> partial) throws IOException {
        // This OpenAI-compatible stream uses one JSON value per data line.
        var body = new BufferedInputStream(input);
        var content = new StringBuilder();
        var calls = new TreeMap<Integer,ObjectNode>();
        var arguments = new HashMap<Integer,StringBuilder>();
        var line = new ByteArrayOutputStream();
        ObjectNode usage = object();
        String finish = "";
        int size = 0;
        boolean done = false;
        for (int b; (b = body.read()) >= 0;) {
            if (++size > 8_388_608) throw new IOException("Model response too large");
            if (b != '\n') { line.write(b); continue; }
            String event = line.toString(java.nio.charset.StandardCharsets.UTF_8);
            line.reset();
            if (!event.startsWith("data:")) continue;
            String data = event.substring(5).strip();
            if (data.equals("[DONE]")) { done = true; break; }
            var frame = MAPPER.readTree(data);
            if (frame.hasNonNull("error")) throw new IOException("Model streaming error");
            if (frame.path("usage") instanceof ObjectNode next) usage = next;
            var choice = frame.at("/choices/0");
            if (choice.path("finish_reason").isString()) finish = text(choice,"finish_reason");
            var delta = choice.path("delta");
            String textDelta = text(delta,"content");
            if (!textDelta.isEmpty()) { content.append(textDelta); partial.accept(content.toString()); }
            for (var part : delta.path("tool_calls")) {
                int index = part.path("index").asInt(-1);
                if (index < 0 || index >= 128) throw new IOException("Invalid streaming tool index");
                var call = calls.computeIfAbsent(index,ignored -> object("type","function","function",object()));
                if (part.path("id").isString()) call.put("id",text(part,"id"));
                var fn = part.path("function");
                if (fn.path("name").isString()) call.withObject("function").put("name",text(fn,"name"));
                arguments.computeIfAbsent(index,ignored -> new StringBuilder()).append(text(fn,"arguments"));
            }
        }
        if (!done || finish.isBlank()) throw new IOException("Model stream ended before settlement");
        calls.forEach((index, call) -> call.withObject("function").put("arguments",arguments.get(index).toString()));
        return object("choices",List.of(object("finish_reason",finish,"message",
                object("role","assistant","content",content.toString(),"tool_calls",calls.values()))),"usage",usage);
    }
}
