package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.*;
import org.naruprof.ports.ProfileStore;
import org.naruprof.infra.Refusal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

final class GraphService {
    private static List<String> premises(JsonNode c) {
        return c.path("premises").valueStream().map(JsonNode::asString).toList();
    }
    private record ChainKey(List<String> premises, String conclusion) {
        ChainKey(JsonNode c) { this(GraphService.premises(c).stream().sorted().distinct().toList(),text(c,"conclusion")); }
    }
    private static void union(ArrayNode to, JsonNode values) {
        for (var v : values) if (to.valueStream().noneMatch(v::equals)) to.add(v);
    }
    static void merge(ProfileStore.Change tx, ObjectNode batch) {
        for (JsonNode value : batch.path("nodes")) {
            if (!(value instanceof ObjectNode node) || text(node,"id").isBlank() || text(node,"statement").isBlank())
                throw new Refusal(400,"Invalid proposition");
            String id = text(node,"id");
            var existing = tx.get("nodes",id);
            if (existing == null) existing = node.deepCopy();
            else {
                var aliases = existing.withArray("aliases");
                union(aliases,array().add(text(node,"statement")));
                union(aliases,node.path("aliases"));
                union(existing.withArray("provenance"),node.path("provenance"));
            }
            tx.save("nodes",id,existing);
        }
        var byKey = new HashMap<ChainKey,ObjectNode>();
        tx.list("chains").forEach(c -> byKey.put(new ChainKey(c),c));
        for (var value : batch.path("chains")) {
            if (!(value instanceof ObjectNode chain) || text(chain,"id").isBlank()) throw new Refusal(400,"Invalid chain");
            var key = new ChainKey(chain);
            if (key.premises.isEmpty() || key.premises.contains(key.conclusion) || tx.get("nodes",key.conclusion) == null
                    || key.premises.stream().anyMatch(p -> tx.get("nodes",p) == null)) throw new Refusal(400,"Invalid chain endpoints");
            var stored = byKey.computeIfAbsent(key,k -> chain.deepCopy());
            var sameId = tx.get("chains",text(stored,"id"));
            if (sameId != null && !key.equals(new ChainKey(sameId))) throw new Refusal(409,"Conflicting chain identity");
            union(stored.withArray("provenance"),chain.path("provenance"));
            tx.save("chains",text(stored,"id"),stored);
        }
    }
    static void deleteSource(ProfileStore.Change tx, String source) {
        var removed = new HashSet<String>();
        for (String group : List.of("nodes","chains")) for (var row : tx.list(group)) {
            var refs = row.withArray("provenance");
            int before = refs.size();
            refs.removeIf(p -> source.equals(text(p,"chatId")));
            boolean dangling = group.equals("chains") && (tx.get("nodes",text(row,"conclusion")) == null
                    || premises(row).stream().anyMatch(p -> tx.get("nodes",p) == null));
            if ((before > 0 && refs.isEmpty()) || dangling) {
                String id = text(row,"id");
                removed.add(id);
                tx.remove(group,id);
            }
            else if (refs.size() != before) tx.save(group,text(row,"id"),row);
        }
        for (String group : List.of("coverage","sessions")) for (var row : tx.list(group)) {
            if (source.equals(text(row,"chatId"))) tx.remove(group,text(row,"recordId"));
        }
        var positions = tx.get("graph","positions");
        if (!removed.isEmpty() && positions != null) {
            positions.remove(removed);
            tx.save("graph","positions",positions);
        }
    }
    static long revision(ProfileStore.View tx) {
        var state = tx.get("graph","revision");
        return state == null ? 0 : state.path("value").asLong(0);
    }
    static long bumpRevision(ProfileStore.Change tx) {
        long next = Math.max(System.currentTimeMillis(),revision(tx)+1);
        tx.save("graph","revision",object("value",next));
        return next;
    }
    ObjectNode deleteNode(ProfileStore.Change tx,String id) {
        if (!tx.list("extraction-leases").isEmpty())
            throw new Refusal(409,object("error","Extraction is in progress","code","extraction_running"));
        boolean argument = id.startsWith("arg_");
        String collection = argument ? "chains" : "nodes";
        if (tx.get(collection,id) == null) throw new Refusal(404,object("error","Not found","code","not_found"));
        var removed = new HashSet<String>();
        removed.add(id);
        for (var chain : tx.list("chains")) {
            if (!argument && (id.equals(text(chain,"conclusion")) || premises(chain).contains(id)))
                removed.add(text(chain,"id"));
        }
        tx.remove(collection,id);
        for (String member : removed) if (!member.equals(id)) tx.remove("chains",member);
        var positions = tx.get("graph","positions");
        if (positions != null) { positions.remove(removed); tx.save("graph","positions",positions); }
        tx.project(false);
        return object("stats",object("chainsRemoved",argument ? 1 : removed.size()-1),"graphRevision",bumpRevision(tx));
    }

    ObjectNode tool(ProfileStore.View tx,String name,JsonNode args) {
        if (name.equals("list_sessions")) return object("sessions",tx.list("chats").stream()
                .map(c -> object("id",text(c,"id"),"title",text(c,"title"))).toList());
        String session = text(args,"session");
        return object("propositions",tx.list("nodes").stream().filter(n -> n.path("provenance").valueStream()
                .anyMatch(p -> session.equals(text(p,"chatId")))).toList());
    }
    ObjectNode view(ProfileStore.View tx) {
        var props = tx.list("nodes"); var chains = tx.list("chains");
        var nodes = new ArrayList<>(props);
        var edges = array(); var logical = new LinkedHashSet<ObjectNode>(); var links = array();
        var sessions = object(); var files = object(); var memberships = object();
        var sources = tx.get("graph","sources"); var names = new HashMap<String,String>();
        props.forEach(n -> names.put(text(n,"id"),text(n,"name")));
        for (var c : chains) {
            var ps = premises(c); String end = text(c,"conclusion"), id = text(c,"id");
            for (String p : ps) {
                logical.add(object("from",p,"to",end));
                edges.add(object("from",p,"to",ps.size() == 1 ? end : id));
            }
            if (ps.size() > 1) {
                nodes.add(object("id",id,"name","⟹","statement",String.join(" + ",ps.stream().map(names::get).toList())+" → "+names.get(end),
                        "createdAt",c.get("createdAt"),"provenance",c.get("provenance"),"aliases",array()));
                links.add(object("id",id,"premises",ps,"conclusion",end));
                edges.add(object("from",id,"to",end));
            }
        }
        for (var n : nodes) {
            var ids = new LinkedHashSet<String>();
            for (var p : n.path("provenance")) {
                String id = text(p,"chatId"); var chat = tx.get("chats",id);
                if (chat != null) { sessions.put(id,text(chat,"title")); ids.add(id); }
                else if (sources != null && sources.has(id)) { files.put(id,text(sources.get(id),"filePath")); ids.add(id); }
            }
            if (!ids.isEmpty()) memberships.set(text(n,"id"),MAPPER.valueToTree(ids));
        }
        var graph = object("nodes",nodes,"propositions",props,"edges",edges,"logicalEdges",logical,"impLinks",links,
                "chains",chains,"sessions",sessions,"fileSources",files,"nodeSessions",memberships,"clusters",array());
        var positions = tx.get("graph","positions");
        if (positions != null && !positions.isEmpty()) graph.set("positions",positions);
        return graph;
    }
}
