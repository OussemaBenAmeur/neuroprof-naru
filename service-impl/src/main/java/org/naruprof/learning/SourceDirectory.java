package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.time.Instant;
import java.util.*;
import org.naruprof.infra.Refusal;
import org.naruprof.ports.ProfileStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

final class SourceDirectory {
    private static ObjectNode organization(ProfileStore.View tx) {
        var state = tx.get("graph","source-organization");
        if (state == null) return object("version",1,"folders",array(),"placements",object());
        if (state.path("version").asInt() != 1 || !state.path("folders").isArray() || !state.path("placements").isObject())
            throw new Refusal(500,"Could not read source organization. Its data is invalid or newer than this app.");
        return state;
    }
    private static Map<String,ObjectNode> sources(ProfileStore.View tx, ObjectNode state) {
        var entries = new LinkedHashMap<String,ObjectNode>();
        for (var chat : tx.list("chats")) {
            String id = text(chat,"id");
            entries.put(id,object("id",id,"kind","chat","chatKind",chat.path("kind").asString("teach"),"label",chat.get("title"),"createdAt",chat.get("createdAt")));
        }
        var files = tx.get("graph","sources");
        if (files != null) for (var file : files.properties()) {
            String id = file.getKey(), path = text(file.getValue(),"filePath");
            String label = state.path("placements").path(id).path("name").asString(path.substring(path.lastIndexOf('/')+1));
            entries.put(id,object("id",id,"kind","notes","label",label,"filePath",path,"createdAt",file.getValue().get("importedAt")));
        }
        for (var node : tx.list("nodes")) for (var p : node.path("provenance")) {
            String id = text(p,"chatId");
            if (!id.isEmpty()) entries.putIfAbsent(id,object("id",id,"kind","missing","label",null,"createdAt",null));
        }
        return entries;
    }
    static ObjectNode view(ProfileStore.View tx) {
        var state = organization(tx);
        return object("organization",state,"sources",sources(tx,state).values());
    }
    static void register(ProfileStore.Change tx,String source,String path,boolean existed) {
        var state = organization(tx);
        var placements = (ObjectNode)state.path("placements");
        if (placements.has(source)) return;
        String parent = null;
        if (!existed) {
            var dirs = Arrays.asList(path.split("/"));
            var folders = state.withArray("folders");
            int start = 0;
            for (int i = dirs.size()-1; i > 0; i--) {
                String original = String.join("/",dirs.subList(0,i));
                var known = folders.valueStream().filter(f -> original.equals(text(f,"importPath"))).findFirst();
                if (known.isPresent()) { parent = text(known.orElseThrow(),"id"); start = i; break; }
            }
            for (int i = start; i < dirs.size()-1; i++) {
                String id = folderId();
                folders.add(object("id",id,"name",dirs.get(i),"parentId",parent,"createdAt",Instant.now().toString(),
                        "importPath",String.join("/",dirs.subList(0,i+1))));
                parent = id;
            }
        }
        placements.set(source,object("folderId",parent));
        tx.save("graph","source-organization",state);
    }
    private static String folderId() { return "folder_" + UUID.randomUUID(); }

    static ObjectNode change(ProfileStore.Change tx,ObjectNode input) {
        var state = organization(tx);
        var entries = sources(tx,state);
        var folders = state.withArray("folders");
        var placements = (ObjectNode)state.path("placements");
        String action = text(input,"action"), id = text(input,"id");
        String parent = null;
        if (action.equals("create-folder") || action.equals("move")) {
            var field = input.path("parentId");
            if (!field.isNull() && !field.isString()) throw new Refusal(400,"Invalid parent folder.");
            if (field.isString()) {
                parent = field.asString();
                if (folder(folders,parent) == null) throw new Refusal(400,"Parent folder no longer exists.");
            }
        }
        String created = null;
        switch (action) {
            case "create-folder" -> {
                created = folderId();
                folders.add(object("id",created,"name",name(input),"parentId",parent,"createdAt",Instant.now().toString()));
            }
            case "rename-folder" -> requiredFolder(folders,id).put("name",name(input));
            case "rename-source" -> {
                var entry = entries.get(id);
                if (entry == null || "missing".equals(text(entry,"kind"))) throw new Refusal(400,"Source no longer exists.");
                String name = name(input);
                if ("chat".equals(text(entry,"kind"))) tx.save("chats",id,SessionService.require(tx,id).put("title",name));
                else placement(placements,id).put("name",name);
            }
            case "move" -> {
                switch (text(input,"kind")) {
                    case "source" -> {
                        if (!entries.containsKey(id)) throw new Refusal(400,"Source no longer exists.");
                        placement(placements,id).put("folderId",parent);
                    }
                    case "folder" -> {
                        var target = requiredFolder(folders,id);
                        Set<String> visited = new HashSet<>(Set.of(id));
                        for (String current = parent; current != null;) {
                            if (!visited.add(current)) throw new Refusal(400,"A folder cannot be moved into itself or its descendants.");
                            current = requiredFolder(folders,current).path("parentId").asString(null);
                        }
                        target.put("parentId",parent);
                    }
                    default -> throw new Refusal(400,"Invalid move kind.");
                }
            }
            case "delete-folder" -> {
                requiredFolder(folders,id);
                if (!removeFolder(folders,placements,entries.keySet(),id)) throw new Refusal(400,"Only empty folders can be removed. Move their entries out first.");
            }
            default -> throw new Refusal(400,"Unknown source organization action.");
        }
        tx.save("graph","source-organization",state);
        var result = view(tx);
        if (created != null) result.put("createdFolderId",created);
        return result;
    }

    static ObjectNode delete(ProfileStore.Change tx,ObjectNode input) {
        if (!input.path("deleteSessions").isBoolean()) throw deletionError("Session deletion must be explicitly selected or declined.");
        Set<String> selected = ids(input.path("sourceIds")), folderIds = ids(input.path("folderIds"));
        if (selected.isEmpty() && folderIds.isEmpty()) throw deletionError("Select at least one source or folder.");
        var state = organization(tx);
        var entries = sources(tx,state);
        boolean deleteSessions = input.path("deleteSessions").asBoolean();
        if (selected.stream().anyMatch(entries::containsKey) && !tx.list("extraction-leases").isEmpty())
            throw new Refusal(409,object("error","Extraction is running","code","extraction_running"));
        var placements = (ObjectNode)state.path("placements");
        var files = tx.get("graph","sources");
        var side = tx.list("side-leases");
        for (String id : selected) {
            if (entries.containsKey(id)) GraphService.deleteSource(tx,id);
            if (deleteSessions && tx.get("chats",id) != null) {
                if (tx.get("leases",id) != null || side.stream().anyMatch(r -> id.equals(text(r,"chatId"))))
                    throw new Refusal(409,object("error","Session is still active","code","turn_running"));
                var jobs = tx.list("jobs").stream().filter(j -> id.equals(text(j,"chatId"))).toList();
                if (jobs.stream().anyMatch(j -> Set.of("pending","running").contains(text(j,"status"))))
                    throw new Refusal(409,object("error","A session job is still running","code","jobs_running"));
                for (var job : jobs) tx.remove("jobs",text(job,"id"));
                tx.remove("chats",id);
            }
            if (tx.get("chats",id) == null) placements.remove(id);
            if (files != null) files.remove(id);
        }
        if (files != null) tx.save("graph","sources",files);
        var remaining = sources(tx,state).keySet();
        boolean changed;
        do {
            changed = false;
            for (String id : folderIds) changed |= removeFolder(state.withArray("folders"),placements,remaining,id);
        } while (changed);
        tx.save("graph","source-organization",state);
        tx.project(false);
        return view(tx).put("graphRevision",selected.isEmpty() ? GraphService.revision(tx) : GraphService.bumpRevision(tx));
    }
    private static Refusal deletionError(String message) { return new Refusal(400,object("error",message,"code","invalid_source_deletion")); }
    private static Set<String> ids(JsonNode values) {
        var ids = new LinkedHashSet<String>();
        if (!values.isArray()) throw deletionError("Source and folder IDs must be arrays of non-empty strings.");
        for (var item : values) {
            if (!item.isString() || item.asString().isBlank()) throw deletionError("Source and folder IDs must be arrays of non-empty strings.");
            ids.add(item.asString());
        }
        return ids;
    }
    private static boolean removeFolder(ArrayNode folders,ObjectNode placements,Set<String> sources,String id) {
        if (folder(folders,id) == null || folders.valueStream().anyMatch(f -> id.equals(text(f,"parentId")))
                || sources.stream().anyMatch(source -> id.equals(text(placements.path(source),"folderId")))) return false;
        folders.removeIf(f -> id.equals(text(f,"id")));
        for (var value : placements) if (value instanceof ObjectNode row && id.equals(text(row,"folderId"))) row.putNull("folderId");
        return true;
    }
    private static ObjectNode placement(ObjectNode placements,String id) {
        if (!(placements.path(id) instanceof ObjectNode)) placements.set(id,object("folderId",null));
        return (ObjectNode)placements.path(id);
    }
    private static ObjectNode folder(ArrayNode folders,String id) {
        return (ObjectNode)folders.valueStream().filter(f -> id.equals(text(f,"id"))).findFirst().orElse(null);
    }
    private static ObjectNode requiredFolder(ArrayNode folders,String id) {
        var found = folder(folders,id);
        if (found == null) throw new Refusal(400,"Folder no longer exists.");
        return found;
    }
    private static String name(ObjectNode input) {
        String value = text(input,"name").strip();
        if (value.isEmpty()) throw new Refusal(400,"Name must be a non-empty string.");
        return value;
    }
}
