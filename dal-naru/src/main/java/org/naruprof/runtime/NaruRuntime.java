package org.naruprof.runtime;

import static org.naruprof.infra.Json.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.*;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.naruprof.ports.LessonRuntime;
import tools.jackson.databind.node.ObjectNode;

public final class NaruRuntime implements LessonRuntime, AutoCloseable {
    private final NWorkspace workspace;
    private final NaruAgentImpl agent;
    private final Path root;
    private final ModelTransport transport;
    private org.naruprof.ports.KeyStore keys;
    private final Map<String,ModelTransport> providers = new ConcurrentHashMap<>();


    public NaruRuntime(Path root,URI endpoint,String key,org.naruprof.ports.KeyStore keys) throws Exception {
        this(root,endpoint,key);
        this.keys = keys;
    }
    private String keyName(String provider) {
        return switch (provider) { case "openrouter" -> "OPENROUTER_API_KEY"; case "openai" -> "OPENAI_API_KEY"; default -> null; };
    }
    public boolean configured(String provider) {
        if ("learning".equals(provider)) return transport != null;
        return keys != null && keyName(provider) != null && keys.resolve(keyName(provider)) != null;
    }
    private ModelTransport binding(String provider) {
        if ("learning".equals(provider)) return Objects.requireNonNull(transport);
        if (!configured(provider)) throw new IllegalStateException("No configured model transport");
        return providers.computeIfAbsent(provider,id -> ModelTransport.http(
                URI.create(id.equals("openai") ? "https://api.openai.com/v1/chat/completions" : "https://openrouter.ai/api/v1/chat/completions"),
                () -> keys.resolve(keyName(id))));
    }
    public NaruRuntime(Path root, URI endpoint, String key) throws Exception {
        this(root, endpoint == null ? null : ModelTransport.http(endpoint, key));
    }
    NaruRuntime(Path root, ModelTransport transport) throws Exception {
        this.root = root.toAbsolutePath();
        this.transport = transport;
        Files.createDirectories(root);
        workspace = Objects.requireNonNull(Nuts.openWorkspace("--workspace=" + root.resolve("nuts").toAbsolutePath(),
                "--standalone","--embedded","--offline","-y"));
        workspace.share();
        agent = new NaruAgentImpl();
        agent.logger(line -> {});
        agent.projectDirectory(NPath.of(this.root.toString()));
        agent.directiveFilter(d -> d.name().equals("learning-turn"));
        agent.toolFilter(t -> t instanceof RoleTool);
    }
    public boolean ready() { return workspace != null; }
    public boolean configured() { return transport != null; }
    public void close() { workspace.close(); }

    public ObjectNode respond(Request req, BooleanSupplier aborted, Consumer<ObjectNode> partial) throws Exception {
        Path directory = root.resolve("tasks").resolve(UUID.nameUUIDFromBytes(
                (req.chatId()+"\n"+req.turnId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        Files.createDirectories(directory);
        var session = agent.newSession().directory(NPath.of(directory.toString()))
                .interaction(new NaruStreamInteraction(out -> {}, input -> {})).build();
        var result = new CompletableFuture<ObjectNode>();
        try {
            var registry = session.registry();
            var template = message("assistant",array());
            template.put("model",req.model()).put("provider",req.provider()).put("api","openai-completions");
            registry.registerModelProvider(new Provider(binding(req.provider()),req.model(),text -> {
                if (!aborted.getAsBoolean()) partial.accept(template.deepCopy()
                        .set("content",array().add(object("type","text","text",text))));
            },aborted));
            ((NaruRegistryImpl) registry).registerToolset(new RoleTools(req.tools()));
            registry.registerDirectiveProvider(new NaruDirectiveProviderBase("learning") {{
                registerDirective(new NaruDirectiveBase("learning-turn","learning","Run one admitted model request") {
                    public NaruStmtResult execute(NaruDirectiveCallContext context) {
                        try {
                            if (aborted.getAsBoolean()) throw new CancellationException();
                            var task = context.task();
                            var definitions = req.tools().stream().map(n -> registry.findTool(n).get().getDefinition(task)).toList();
                            var request = new NaruModelRequest(NativeHistory.modelMessages(req.prompt(),req.record()), definitions, Map.of());
                            var protocol = registry.protocol(task.model(), session).get();
                            NaruResponse response = protocol.chat(request, task);
                            var content = array();
                            var msg = template.deepCopy();
                            String text = response.getMessage().getContent();
                            if (text != null && !text.isEmpty()) content.add(object("type","text","text",text));
                            for (var call : response.getMessage().getToolCalls()) {
                                if (!req.tools().contains(call.getName())) throw new IllegalStateException("Tool outside role");
                                String verdict = registry.dispatch(call,task);
                                if (!"accepted".equals(verdict)) throw new IllegalStateException("Invalid learning tool input");
                                content.add(object("type","toolCall","id",call.getId(),"name",call.getName(),"arguments",call.getArguments()));
                            }
                            msg.set("content",content);
                            msg.set("usage",object("input",response.getPromptTokens()-response.getCacheReadTokens(),"output",response.getEvalTokens(),
                                    "cacheRead",response.getCacheReadTokens(),"cacheWrite",0,"totalTokens",response.getTotalTokens(),
                                    "cost",object("input",0,"output",0,"cacheRead",0,"cacheWrite",0,"total",0)));
                            msg.put("stopReason",response.hasToolCalls() ? "toolUse" : "stop");
                            if (!aborted.getAsBoolean()) partial.accept(msg.deepCopy());
                            result.complete(msg);
                            return NaruStmtResult.ofSuccess("settled");
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                            return NaruStmtResult.ofError("Learning turn failed");
                        }
                    }
                });
            }});
            var task = session.newTask(NaruTaskSpec.of().model("learning/"+req.model()).name(req.turnId()).statements("/learning-turn"));
            task.fg().unhold();
            session.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            while (!aborted.getAsBoolean() && System.nanoTime() < deadline) {
                try { return result.get(100,TimeUnit.MILLISECONDS); } catch (TimeoutException waiting) { /* check cancellation */ }
            }
            task.cancel("Turn stopped");
            if (aborted.getAsBoolean()) throw new CancellationException();
            throw new TimeoutException("Model request outcome unknown");
        } finally {
            session.stop();
        }
    }

    private record RoleTools(List<String> names) implements NaruToolset {
        public String id() { return "learning"; }
        public void open(NaruSession session) {}
        public void close() {}
        public List<NaruTool> tools() { return names.stream().<NaruTool>map(RoleTool::new).toList(); }
    }
    private record RoleTool(String name) implements NaruTool {
        public Set<String> tags() { return Set.of("learning"); }
        public String getDescription(NaruTask t) { return "Lesson tool " + name; }
        public NaruToolDefinition getDefinition(NaruTask t) { return new NaruToolDefinition(name,getDescription(t)); }
        public String execute(NaruToolCallContext ctx) {
            for (var key : ToolSchemas.forTool(name).path("required"))
                if (!ctx.arguments().containsKey(key.asString())) return "missing required field";
            return "accepted";
        }
    }
}
