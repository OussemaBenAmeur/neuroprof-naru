package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.naruprof.infra.Refusal;
import org.naruprof.ports.ProfileStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

/** Offline maintenance. File bytes are staged by digest. Publishing canonical records,
 * the import receipt and its projection is a single transaction. */
public final class LegacyProfile {
    private static final List<String> LEDGERS = List.of("nodes","chains","sessions","coverage");
    private static final List<String> GRAPH_DOCS = List.of("sources","positions","source-organization","render-settings","format");
    private static boolean accepted(String name) {
        return name.equals("settings.json") || name.matches("(chats|jobs|themes)/[A-Za-z0-9_-]+\\.json")
                || name.matches("graph/(nodes|chains|sessions|coverage)\\.jsonl")
                || name.matches("graph/(sources|positions|source-organization|render-settings|format)\\.json")
                || name.matches("prompts/([a-z-]+/)?[A-Za-z0-9-]+\\.md")
                || name.startsWith("core-plugin-data/") || name.matches("plugins/[a-zA-Z0-9_.-]+/data/.+");
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static JsonNode parsed(String data) {
        try { return MAPPER.readTree(data); }
        catch (RuntimeException malformed) { return null; }
    }
    public record Manifest(Map<String,String> files,String sha256) {}
    public static Manifest preview(Path source) throws IOException {
        if (!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Legacy directory missing");
        var files = new TreeMap<String,String>();
        try (var walk = Files.walk(source)) {
            for (var file : walk.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                String key = source.relativize(file).toString().replace(File.separatorChar,'/');
                if (accepted(key)) files.put(key,hash(Files.readAllBytes(file)));
            }
        }
        if (files.isEmpty()) throw new IOException("No supported legacy files");
        if (files.containsKey("graph/format.json")) {
            var format = parsed(Files.readString(source.resolve("graph/format.json")));
            if (format != null && format.path("version").isIntegralNumber() && format.path("version").asLong(0)>1)
                throw new IOException("Graph format is newer than v1");
        }
        return new Manifest(Collections.unmodifiableMap(files),hash(MAPPER.writeValueAsBytes(files)));
    }
    public static ObjectNode importProfile(Path source,ProfileStore store,String expectedHash) throws IOException {
        var manifest = preview(source);
        if (!manifest.sha256.equals(expectedHash)) throw new IOException("Input differs from preview");
        var done = store.read(v -> v.get("imports",expectedHash));
        if (done != null) return done;
        for (var entry : manifest.files.entrySet()) {
            String digest = entry.getValue();
            if (store.read(v -> v.get("staged",digest) != null)) continue;
            byte[] bytes = Files.readAllBytes(source.resolve(entry.getKey()));
            if (!hash(bytes).equals(digest)) throw new IOException("File changed during staging");
            store.write(tx -> { tx.save("staged",digest,object("bytes",Base64.getEncoder().encodeToString(bytes))); return null; });
        }
        return store.write(tx -> {
            var receipt = tx.get("imports",expectedHash);
            if (receipt != null) return receipt;
            if (tx.snapshot().sequence() != 0 || tx.get("settings","current") != null || !tx.list("imports").isEmpty())
                throw new Refusal(409,"Import requires an empty destination profile");
            var nodes = array(); var chains = array(); var skipped = array();
            // Paths, including JSONL, are visited in a stable order. Graph publication follows all reads.
            manifest.files.forEach((key,digest) -> {
                var staged = tx.get("staged",digest);
                byte[] bytes = Base64.getDecoder().decode(text(staged,"bytes"));
                String raw = new String(bytes,StandardCharsets.UTF_8);
                if (key.startsWith("core-plugin-data/") || key.startsWith("plugins/")) {
                    tx.save("assets",key,object("path",key,"bytes",staged.get("bytes"))); return;
                }
                if (key.startsWith("prompts/")) {
                    tx.save("prompt-overrides",key.substring(8),object("path",key.substring(8),"text",raw)); return;
                }
                if (key.endsWith(".jsonl")) {
                    String family = key.substring(6,key.length()-6); int lineNo = 0;
                    for (String line : raw.split("\n")) {
                        lineNo++;
                        if (line.isBlank()) continue;
                        var row = parsed(line);
                        if (!(row instanceof ObjectNode r) || !validLine(family,r)) { skipped.add(key+"#"+lineNo); continue; }
                        switch (family) {
                            case "nodes" -> nodes.add(r);
                            case "chains" -> chains.add(r);
                            default -> {
                                String rowId = String.format(Locale.ROOT,"%016d",lineNo);
                                tx.save(family,rowId,r.put("recordId",rowId));
                            }
                        }
                    }
                    return;
                }
                var node = parsed(raw);
                if (!(node instanceof ObjectNode record)) { skipped.add(key); return; }
                if (key.equals("settings.json")) {
                    if (record.path("version").asInt(1) == 1) tx.save("settings","current",record);
                    else skipped.add(key);
                } else if (key.startsWith("graph/")) tx.save("graph",key.substring(6,key.length()-5),record);
                else {
                    String[] parts = key.split("/");
                    String id = parts[1].substring(0,parts[1].length()-5);
                    if (parts[0].equals("chats") && (record.path("version").asLong(1)>1 || !record.path("messages").isArray()
                            || !id.equals(text(record,"id")))) { skipped.add(key); return; }
                    if (parts[0].equals("themes")) record.put("id",id);
                    if (parts[0].equals("jobs") && !record.has("id")) record.put("id",id);
                    tx.save(parts[0],id,record);
                }
            });
            GraphService.merge(tx,object("nodes",nodes,"chains",chains));
            tx.project(false);
            var result = object("sha256",expectedHash,"files",manifest.files.size(),"chats",tx.list("chats").size(),
                    "jobs",tx.list("jobs").size(),"nodeLines",nodes.size(),"chainLines",chains.size(),
                    "projection",tx.snapshot(),"provenancePoints",tx.list("nodes").stream().mapToLong(n->n.path("provenance").size()).sum(),
                    "skipped",skipped);
            tx.save("imports",expectedHash,result);
            manifest.files.values().stream().distinct().forEach(d -> tx.remove("staged",d));
            return result;
        });
    }
    private static boolean validLine(String family,ObjectNode r) {
        if ("coverage".equals(family)) return strings(r,"chatId","fp","foldedAt") && r.path("tokens").isNumber();
        if ("sessions".equals(family)) return strings(r,"chatId","foldedAt");
        if (!strings(r,"id","createdAt") || !r.path("provenance").isArray()
                || !r.path("provenance").valueStream().allMatch(p -> strings(p,"chatId","messageId"))) return false;
        if ("nodes".equals(family)) return strings(r,"name","statement") && stringArray(r.path("aliases"));
        return strings(r,"conclusion") && stringArray(r.path("premises")) && !r.path("premises").isEmpty();
    }
    private static boolean strings(JsonNode n,String... fields) {
        return Arrays.stream(fields).allMatch(f -> n.path(f).isString());
    }
    private static boolean stringArray(JsonNode n) { return n.isArray() && n.valueStream().allMatch(JsonNode::isString); }

    static void archive(ProfileStore store,java.io.OutputStream destination) throws IOException {
        Path scratch = Files.createTempDirectory("naruprof-export-");
        try {
            Path directory = scratch.resolve("profile");
            export(store,directory);
            var nonClosing = new FilterOutputStream(destination) {
                @Override public void close() throws IOException { flush(); }
            };
            try (var files = Files.walk(directory); var zip = new java.util.zip.ZipOutputStream(nonClosing)) {
                for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = directory.relativize(file).toString().replace(java.io.File.separatorChar,'/');
                    zip.putNextEntry(new java.util.zip.ZipEntry(name));
                    Files.copy(file,zip);
                    zip.closeEntry();
                }
            }
        } finally {
            try (var files = Files.walk(scratch)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    public static void export(ProfileStore store,Path destination) throws IOException {
        var output = destination.toAbsolutePath().normalize();
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        Files.createDirectories(output.getParent());
        var scratch = Files.createTempDirectory(output.getParent(),".learning-export-");
        try {
            store.read(tx -> {
                try {
                    for (String group : List.of("chats","jobs","themes")) {
                        Files.createDirectories(scratch.resolve(group));
                        for (ObjectNode row : tx.list(group)) {
                            String id = text(row,"id");
                            if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid stored identity");
                            write(scratch,group+"/"+id+".json",MAPPER.writeValueAsBytes(row));
                        }
                    }
                    var settings = tx.get("settings","current");
                    if (settings != null) write(scratch,"settings.json",MAPPER.writeValueAsBytes(settings));
                    for (String name : LEDGERS) {
                        StringBuilder lines = new StringBuilder();
                        tx.list(name).forEach(row -> {
                            row.remove("recordId");
                            lines.append(row).append('\n');
                        });
                        write(scratch,"graph/"+name+".jsonl",lines.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    for (String name : GRAPH_DOCS) {
                        var row = tx.get("graph",name);
                        if (row == null && name.equals("format")) row = object("version",1);
                        if (row != null) write(scratch,"graph/"+name+".json",MAPPER.writeValueAsBytes(row));
                    }
                    for (var asset : tx.list("assets")) write(scratch,text(asset,"path"),Base64.getDecoder().decode(text(asset,"bytes")));
                    for (var prompt : tx.list("prompt-overrides"))
                        write(scratch,"prompts/"+text(prompt,"path"),text(prompt,"text").getBytes(StandardCharsets.UTF_8));
                    return null;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            Files.move(scratch,output,StandardCopyOption.ATOMIC_MOVE);
        } catch (UncheckedIOException e) { throw e.getCause(); }
        finally {
            if (Files.exists(scratch)) try (var walk = Files.walk(scratch)) {
                for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
    private static void write(Path root,String relative,byte[] bytes) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !accepted(relative)) throw new IOException("Invalid export path");
        Files.createDirectories(file.getParent());
        Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
    }
}
