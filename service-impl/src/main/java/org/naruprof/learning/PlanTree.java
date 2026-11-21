package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ObjectNode;

final class PlanTree {
    private static final Pattern END = Pattern.compile("([a-z0-9]+(?:[-_][a-z0-9]+)*)(?:\\s*\\[([^\\[\\]]*)\\]|\\s*\\(([^()]*)\\))?");
    static ObjectNode parse(String mermaid, int version) {
        List<String> lines = mermaid.lines().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("%%")).toList();
        if (lines.size() < 2 || !lines.getFirst().matches("(?i)(graph|flowchart)\\s+(TD|TB|LR|RL|BT)"))
            throw new IllegalArgumentException("Expected Mermaid graph header and nodes");
        var labels = new LinkedHashMap<String,String>();
        var outgoing = new LinkedHashMap<String,Set<String>>();
        var incoming = new LinkedHashMap<String,Integer>();
        for (var line : lines.subList(1,lines.size())) {
            String previous = null;
            for (String term : line.split("-->|==>|-\\.->",-1)) {
                var match = END.matcher(term.trim());
                if (!match.matches()) throw new IllegalArgumentException("Invalid plan node or edge");
                String id = match.group(1);
                String title = Objects.toString(match.group(2),Objects.toString(match.group(3),id)).strip();
                if (title.length() >= 2 && title.startsWith("\"") && title.endsWith("\"")) title = title.substring(1,title.length()-1);
                if (!labels.containsKey(id) || labels.get(id).equals(id)) labels.put(id,title.isBlank()?id:title);
                outgoing.computeIfAbsent(id,k -> new LinkedHashSet<>());
                incoming.putIfAbsent(id,0);
                if (previous != null && outgoing.get(previous).add(id)) incoming.merge(id,1,Integer::sum);
                previous = id;
            }
        }
        var queue = new ArrayDeque<String>();
        incoming.forEach((id,count) -> { if (count == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            visited++;
            for (String to : outgoing.get(queue.remove())) if (incoming.merge(to,-1,Integer::sum) == 0) queue.add(to);
        }
        if (visited != labels.size()) throw new IllegalArgumentException("Dependency cycle in plan");
        var nodes = array(); var edges = array();
        labels.forEach((id,title) -> nodes.add(object("id",id,"title",title)));
        outgoing.forEach((from,targets) -> targets.forEach(to -> edges.add(object("from",from,"to",to))));
        return object("version",version,"nodes",nodes,"edges",edges);
    }
}
