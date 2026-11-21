package org.naruprof.learning;

import java.util.List;
import org.naruprof.api.Learning;
import org.naruprof.infra.*;
import org.naruprof.ports.*;
import tools.jackson.databind.node.ObjectNode;

public final class LearningModule implements Learning, AutoCloseable {
    private final ProfileStore store;
    private final LessonRuntime runtime;
    private final String defaultModel;
    private final KeyService keys;
    private final Dictation dictation;
    private final ThemeService themes = new ThemeService();
    private final GraphService graph = new GraphService();
    private final SettingsService settings = new SettingsService();
    private final JobService jobs = new JobService();
    private final SessionService sessions;
    private final GraphExtraction extraction;
    private final SideChatService side;
    public LearningModule(ProfileStore store, LessonRuntime runtime, String defaultModel) {
        this(store,runtime,defaultModel,null);
    }
    public LearningModule(ProfileStore store,LessonRuntime runtime,String defaultModel,KeyStore keyStore) {
        this(store,runtime,defaultModel,keyStore,input -> { throw new Refusal(503,"PDF converter unavailable"); });
    }
    public LearningModule(ProfileStore store,LessonRuntime runtime,String defaultModel,KeyStore keyStore,NotesPdf pdf) {
        this(store,runtime,defaultModel,keyStore,pdf,() -> {
            throw new Refusal(503,Json.object("message","No DEEPGRAM_API_KEY — add it to auth.json (desktop) or .env.local (dev)."));
        });
    }
    public LearningModule(ProfileStore store,LessonRuntime runtime,String defaultModel,KeyStore keyStore,NotesPdf pdf,Dictation dictation) {
        this.dictation = dictation;
        this.keys = keyStore == null ? null : new KeyService(keyStore);
        this.store = store; this.runtime = runtime; this.defaultModel = defaultModel;
        sessions = new SessionService(store,runtime,defaultModel,settings,jobs,graph);
        extraction = new GraphExtraction(store,runtime,defaultModel,pdf,sessions.events);
        side = new SideChatService(store,runtime,settings,sessions.events,defaultModel);
    }
    public ObjectNode create(ObjectNode body) { var result = sessions.create(body); sessions.events.emit("chats-changed",null); return result; }
    public ObjectNode chats() { return sessions.list(); }
    public ObjectNode chat(String id) { return sessions.get(id); }
    public ObjectNode rename(String id,ObjectNode body) { var result = sessions.rename(id,body); sessions.events.emit("chats-changed",null); return result; }
    public ObjectNode delete(String id,String mode) { var result = sessions.delete(id,mode); sessions.events.emit("chats-changed",null); return result; }
    public boolean submit(String id,ObjectNode input) { return sessions.submit(id,input); }
    public void abort(String id) { sessions.abort(id); }
    public boolean dismiss(String id,ObjectNode input) { return sessions.dismiss(id,input); }
    public void steer(String id,ObjectNode input) { sessions.steer(id,input); }
    public Subscription stream(String id) { return sessions.stream(id); }
    public Subscription events() { return sessions.events.subscribe(); }
    public ObjectNode sideChats(String chatId) { return side.list(chatId); }
    public Subscription askSide(String chatId,ObjectNode input) { return side.ask(chatId,input); }
    public Subscription watchSide(String chatId,String threadId) { return side.watch(chatId,threadId); }
    public ObjectNode openSide(String chatId,String threadId,ObjectNode input) { return side.open(chatId,threadId,input); }
    public void stopSide(String chatId,String threadId) { side.stop(chatId,threadId); }
    public void exportProfile(java.io.OutputStream destination) throws java.io.IOException {
        LegacyProfile.archive(store,destination);
    }
    public ObjectNode markdown(String id) {
        return store.read(tx -> {
            var chat = SessionService.require(tx,id);
            String title = Json.text(chat,"title");
            String slug = java.text.Normalizer.normalize(title.toLowerCase(java.util.Locale.ROOT),java.text.Normalizer.Form.NFKD)
                    .replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$","");
            return Json.object("filename",(slug.isEmpty() ? "session" : slug)+".md","body","# "+title+"\n\n"+SideChatService.lesson(chat));
        });
    }
    public ObjectNode dictationToken() {
        try { return Json.object("token",dictation.token()); }
        catch (Refusal expected) { throw expected; }
        catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Refusal(502,Json.object("message","Deepgram unreachable"));
        }
    }
    public ObjectNode keys() { return keyService().list(); }
    public ObjectNode key(String id,ObjectNode input) { return keyService().change(id,input); }
    private KeyService keyService() {
        if (keys == null) throw new Refusal(503,"Auth storage unavailable");
        return keys;
    }
    public ObjectNode prompts() { return store.read(settings::segments); }
    public ObjectNode override(String path,ObjectNode input) { return store.write(tx -> settings.override(tx,path,input)); }
    public ObjectNode themes() { return store.read(themes::list); }
    public ObjectNode createTheme(ObjectNode input) { return store.write(tx -> themes.create(tx,input)); }
    public ObjectNode deleteTheme(String id) { return store.write(tx -> themes.delete(tx,id)); }
    public ObjectNode image(String chat,String snapshotId) {
        return store.read(tx -> {
            if (snapshotId.matches("[a-f0-9]{64}") && SessionService.require(tx,chat).path("imageSnapshots").get(snapshotId) instanceof ObjectNode img) return img;
            throw new Refusal(404,"Not found");
        });
    }
    public ObjectNode chatSettings(String id,ObjectNode input) {
        return store.write(tx -> {
            var chat = SessionService.require(tx,id);
            if (input.has("model") && (!input.at("/model/provider").isString() || !input.at("/model/id").isString()))
                throw new Refusal(400,"Invalid model");
            var allowed = Json.object();
            for (String key : List.of("model","thinkingLevel")) if (input.has(key)) allowed.set(key,input.get(key));
            chat.set("settings",allowed); tx.save("chats",id,chat);
            return Json.object("settings",allowed);
        });
    }
    public ObjectNode positions(ObjectNode input) {
        return store.write(tx -> {
            var valid = Json.object();
            for (var id : input.propertyNames()) {
                var value = input.path(id); var chain = tx.get("chains",id);
                if (tx.get("nodes",id) == null && (chain == null || chain.path("premises").size() < 2)) continue;
                if (!value.path("x").isNumber() || !value.path("y").isNumber()) continue;
                double x = value.path("x").asDouble(), y = value.path("y").asDouble();
                if (Double.isFinite(x) && Double.isFinite(y)) valid.set(id,Json.object("x",Math.clamp(x,-1e6,1e6),"y",Math.clamp(y,-1e6,1e6)));
            }
            if (!valid.isEmpty()) tx.save("graph","positions",valid);
            return Json.object("positions",valid);
        });
    }
    public ObjectNode jobs(String id) { return store.read(v -> jobs.list(v,id)); }
    public ObjectNode models() { return store.read(tx -> ModelSelection.catalog(tx,runtime,defaultModel)); }
    public ObjectNode settings() { return store.read(settings::get); }
    public ObjectNode settings(String section,ObjectNode body) { return store.write(tx -> settings.set(tx,section,body)); }
    public ObjectNode importNotes(List<NotesUpload> files) { return extraction.importNotes(files); }
    public void extractChat(String id) { extraction.extractChat(id); }
    public void abortExtraction(String id) { extraction.abort(id); }
    public ObjectNode extractionStatus() { return Json.object("runs",extraction.status()); }
    public ObjectNode activity() {
        return store.read(tx -> Json.object("jobs",tx.list("jobs").stream()
                .filter(job -> "running".equals(Json.text(job,"status"))).toList(),
                "extractions",extraction.status(),"graphRevision",GraphService.revision(tx),"pluginWorkers",List.of()));
    }
    public ObjectNode deleteGraphNode(String id) {
        var result = store.write(tx -> graph.deleteNode(tx,id));
        sessions.events.emit("activity-changed",null);
        return result;
    }
    public ObjectNode sources() { return store.read(SourceDirectory::view); }
    public ObjectNode organizeSources(ObjectNode input) {
        var result = store.write(tx -> SourceDirectory.change(tx,input));
        sessions.events.emit("sources-changed",null);
        sessions.events.emit("chats-changed",null);
        return result;
    }
    public ObjectNode deleteSources(ObjectNode input) {
        var result = store.write(tx -> SourceDirectory.delete(tx,input));
        sessions.events.emit("sources-changed",null);
        sessions.events.emit("chats-changed",null);
        sessions.events.emit("activity-changed",null);
        return result;
    }
    public ObjectNode renderSettings(ObjectNode input) {
        if (input == null) return store.read(tx -> {
            var row = tx.get("graph","render-settings");
            return row == null ? null : RenderSettings.sanitize(row);
        });
        return store.write(tx -> {
            var result = RenderSettings.sanitize(input);
            tx.save("graph","render-settings",result);
            return result;
        });
    }
    public ObjectNode graph() { return store.read(graph::view); }
    public ObjectNode mergeGraph(ObjectNode batch) {
        return store.write(tx -> { GraphService.merge(tx,batch); tx.project(false); GraphService.bumpRevision(tx); return graph.view(tx); });
    }
    public Projection snapshot(String profileId) { return store.read(v -> authorize(v.snapshot(),profileId)); }
    public List<Projection> replay(String profileId,long after,int limit) {
        return store.read(v -> { authorize(v.snapshot(),profileId); return v.after(after,limit); });
    }
    private static Projection authorize(Projection head,String id) {
        if (!head.profileId().equals(id)) throw new Refusal(404,"Unknown profile");
        return head;
    }
    public boolean ready() { return runtime.ready() && store.read(v -> v.snapshot() != null); }
    public void close() { side.close(); extraction.close(); sessions.close(); }
}
