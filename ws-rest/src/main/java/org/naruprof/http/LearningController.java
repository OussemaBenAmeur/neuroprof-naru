package org.naruprof.http;

import static org.naruprof.infra.Json.*;
import org.naruprof.api.Learning;
import org.naruprof.infra.Refusal;
import org.naruprof.infra.NotesUpload;
import java.io.IOException;
import java.util.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.node.ObjectNode;

@RestController
public final class LearningController {
    private final Learning learning;
    public LearningController(Learning learning) { this.learning = learning; }
    @ExceptionHandler(Refusal.class)
    public ResponseEntity<ObjectNode> refuse(Refusal refusal) { return ResponseEntity.status(refusal.status).body(refusal.body); }
    @GetMapping("/internal/health/live") public ObjectNode live() { return object("status","UP"); }
    @GetMapping("/internal/health/ready") public ResponseEntity<?> ready() {
        try { if (learning.ready()) return ResponseEntity.ok(live()); } catch (RuntimeException unavailable) { /* fail closed */ }
        return ResponseEntity.status(503).body(object("status","DOWN"));
    }
    @GetMapping("/internal/v1/profile/export")
    public ResponseEntity<StreamingResponseBody> export() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore()).body(learning::exportProfile);
    }
    @PostMapping("/api/chats") public ObjectNode create(@RequestBody(required=false) ObjectNode body) { return learning.create(body == null ? object() : body); }
    @GetMapping("/api/chats") public ObjectNode chats() { return learning.chats(); }
    @GetMapping("/api/chats/{id}") public ObjectNode chat(@PathVariable String id) { return learning.chat(id); }
    @PutMapping("/api/chats/{id}") public ObjectNode rename(@PathVariable String id,@RequestBody ObjectNode body) { return learning.rename(id,body); }
    @DeleteMapping("/api/chats/{id}") public ObjectNode delete(@PathVariable String id,@RequestParam(required=false) String mode) { return learning.delete(id,mode); }
    @PostMapping("/api/chat") public ResponseEntity<?> submit(@RequestBody ObjectNode body) {
        if (text(body,"chatId").isBlank() || !(body.get("input") instanceof ObjectNode input)) throw new Refusal(400,"Expected chatId and input");
        String id = text(body,"chatId");
        if (learning.submit(id,input)) return ResponseEntity.ok(object("ok",true));
        return sse(out -> { out.send("{\"type\":\"turn-settled\"}"); out.send("[DONE]"); });
    }
    @PostMapping("/api/activity/abort") public ObjectNode abort(@RequestBody ObjectNode body) {
        String id = text(body,"id");
        if (!id.matches("[A-Za-z0-9:_-]+")) throw new Refusal(400,"Invalid id");
        switch (text(body,"kind")) {
            case "turn" -> learning.abort(id);
            case "extraction" -> learning.abortExtraction(id);
            default -> throw new Refusal(400,"Unsupported activity kind");
        }
        return object("ok",true);
    }
    @PostMapping("/api/chats/{id}/dismiss")
    public ResponseEntity<ObjectNode> dismiss(@PathVariable String id,@RequestBody ObjectNode body) {
        if (!(body.path("input") instanceof ObjectNode input)) throw new Refusal(400,"input must be an object");
        if (learning.dismiss(id,input)) return ResponseEntity.accepted().body(object("ok",true,"parked",true));
        return ResponseEntity.ok(object("ok",true));
    }
    @PostMapping("/api/chats/{id}/steer")
    public ObjectNode steer(@PathVariable String id,@RequestBody ObjectNode body) {
        if (!(body.path("input") instanceof ObjectNode input)) throw new Refusal(400,"input must be an object");
        learning.steer(id,input);
        return object("ok",true);
    }
    @GetMapping("/api/chats/{id}/stream")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String id) { return sse(learning.stream(id)); }
    private ResponseEntity<StreamingResponseBody> sse(Learning.Subscription subscription) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("X-Accel-Buffering","no")
                .header("Cache-Control","no-cache, no-transform").body(out -> {
                    try {
                        subscription.consume(data -> {
                            out.write(("data: "+data+"\n\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        });
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
    }
    @GetMapping("/api/chats/{id}/side-chats") public ObjectNode sideChats(@PathVariable String id) { return learning.sideChats(id); }
    @PostMapping("/api/chats/{id}/side-chats")
    public ResponseEntity<StreamingResponseBody> askSide(@PathVariable String id,@RequestBody ObjectNode input) { return ndjson(learning.askSide(id,input)); }
    @GetMapping("/api/chats/{id}/side-chats/{threadId}")
    public ResponseEntity<StreamingResponseBody> watchSide(@PathVariable String id,@PathVariable String threadId) { return ndjson(learning.watchSide(id,threadId)); }
    @PatchMapping("/api/chats/{id}/side-chats/{threadId}")
    public ObjectNode openSide(@PathVariable String id,@PathVariable String threadId,@RequestBody ObjectNode input) { return learning.openSide(id,threadId,input); }
    @PostMapping("/api/chats/{id}/side-chats/{threadId}/stop")
    public ObjectNode stopSide(@PathVariable String id,@PathVariable String threadId) { learning.stopSide(id,threadId); return object("ok",true); }
    private ResponseEntity<StreamingResponseBody> ndjson(Learning.Subscription subscription) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson; charset=utf-8"))
                .header("Cache-Control","no-cache, no-transform").header("X-Accel-Buffering","no").body(out -> {
                    try {
                        subscription.consume(data -> {
                            out.write((data + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        });
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
    }
    @GetMapping("/api/models") public ResponseEntity<ObjectNode> models() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(learning.models());
    }
    @GetMapping("/api/settings") public ObjectNode settings() { return learning.settings(); }
    @PutMapping("/api/settings/{section}") public ObjectNode settings(@PathVariable String section,@RequestBody ObjectNode body) { return learning.settings(section,body); }
    @GetMapping("/api/events") public ResponseEntity<StreamingResponseBody> events() {
        var events = learning.events();
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("X-Accel-Buffering","no")
                .header("Cache-Control","no-cache, no-transform").body(out -> {
                    try { events.consume(frame -> { out.write(frame.getBytes(StandardCharsets.UTF_8)); out.flush(); }); }
                    catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                });
    }
    @GetMapping("/api/settings/prompts") public ObjectNode prompts() { return learning.prompts(); }
    @PutMapping("/api/settings/prompts/{*path}") public ObjectNode prompt(@PathVariable String path,@RequestBody ObjectNode body) { return learning.override(path.substring(1),body); }
    @DeleteMapping("/api/settings/prompts/{*path}") public ObjectNode removePrompt(@PathVariable String path) { return learning.override(path.substring(1),null); }
    @GetMapping("/api/chats/{id}/markdown")
    public ResponseEntity<String> markdown(@PathVariable String id) {
        var document = learning.markdown(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+text(document,"filename")+"\"")
                .body(text(document,"body"));
    }
    @GetMapping("/api/stt/token")
    public ResponseEntity<ObjectNode> dictationToken() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(learning.dictationToken());
    }
    @GetMapping("/api/keys") public ObjectNode keys() { return learning.keys(); }
    @PutMapping("/api/keys/{id}") public ObjectNode key(@PathVariable String id,@RequestBody ObjectNode body) { return learning.key(id,body); }
    @DeleteMapping("/api/keys/{id}") public ObjectNode deleteKey(@PathVariable String id) { return learning.key(id,null); }
    @GetMapping("/api/themes") public ObjectNode themes() { return learning.themes(); }
    @PostMapping("/api/themes") @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode createTheme(@RequestBody ObjectNode body) { return learning.createTheme(body); }
    @DeleteMapping("/api/themes/{id}") public ObjectNode deleteTheme(@PathVariable String id) { return learning.deleteTheme(id); }
    @GetMapping("/api/chats/{id}/images/{snapshotId}") public ResponseEntity<ObjectNode> image(@PathVariable String id,@PathVariable String snapshotId) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(learning.image(id,snapshotId));
    }
    @PutMapping("/api/chats/{id}/settings") public ObjectNode chatSettings(@PathVariable String id,@RequestBody ObjectNode body) { return learning.chatSettings(id,body); }
    @PutMapping("/api/graph/positions") public ObjectNode positions(@RequestBody ObjectNode body) { return learning.positions(body); }
    @GetMapping("/api/jobs") public ObjectNode jobs(@RequestParam(required=false) String chatId) { return learning.jobs(chatId); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ObjectNode> uploadLimit() {
        return ResponseEntity.status(413).body(object("results",List.of(),"error","Import request is too large. Select fewer files."));
    }
    @GetMapping("/api/activity") public ObjectNode activity() { return learning.activity(); }
    @GetMapping("/api/graph/status") public ObjectNode extractionStatus() { return learning.extractionStatus(); }
    @DeleteMapping("/api/graph/nodes/{id}") public ObjectNode deleteNode(@PathVariable String id) { return learning.deleteGraphNode(id); }
    @PostMapping("/api/graph/coverage/extract") @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode extractChat(@RequestBody ObjectNode input) {
        String id = text(input,"chatId");
        if (id.isBlank()) throw new Refusal(400,"Body must be {chatId: string}");
        learning.extractChat(id);
        return object("started",true);
    }
    @PostMapping(path="/api/graph/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectNode importNotes(@RequestParam(name="files",required=false) List<MultipartFile> parts,
                                  @RequestParam(name="paths",required=false) String[] paths) throws IOException {
        List<MultipartFile> files = parts == null ? List.of() : parts;
        if (paths != null && paths.length != files.size()) throw new Refusal(400,object("results",List.of(),"error","Invalid import paths"));
        if (files.stream().mapToLong(MultipartFile::getSize).sum() > NotesUpload.MAX_CONTENT_BYTES)
            throw new Refusal(413,object("results",List.of(),"error","Too big. Max 25 MB."));
        List<NotesUpload> uploads = new ArrayList<>();
        for (int i=0;i<files.size();i++) {
            var file = files.get(i);
            String name = Objects.toString(file.getOriginalFilename(),"");
            uploads.add(new NotesUpload(name,paths == null ? name : paths[i],file.getBytes()));
        }
        return learning.importNotes(uploads);
    }
    @GetMapping("/api/graph/sources") public ObjectNode sources() { return learning.sources(); }
    @PostMapping("/api/graph/sources") public ObjectNode organizeSources(@RequestBody ObjectNode input) { return learning.organizeSources(input); }
    @DeleteMapping("/api/graph/sources") public ObjectNode deleteSources(@RequestBody ObjectNode input) { return learning.deleteSources(input); }
    @GetMapping("/api/graph/render-settings")
    public ResponseEntity<String> renderSettings() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Objects.toString(learning.renderSettings(null),"null"));
    }
    @PutMapping("/api/graph/render-settings")
    public ObjectNode renderSettings(@RequestBody ObjectNode input) { return learning.renderSettings(input); }
    @GetMapping("/api/graph") public ObjectNode graph() { return learning.graph(); }
}
