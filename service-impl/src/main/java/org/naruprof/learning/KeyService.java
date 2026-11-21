package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.io.IOException;
import java.util.*;
import org.naruprof.infra.Refusal;
import org.naruprof.ports.KeyStore;
import tools.jackson.databind.node.ObjectNode;

final class KeyService {
    private final KeyStore store;
    private final ObjectNode catalog;
    private final Set<String> writable = new LinkedHashSet<>();
    private final Map<String,ObjectNode> info = new LinkedHashMap<>();
    KeyService(KeyStore store) {
        this.store = store;
        try (var in = getClass().getResourceAsStream("/key-catalog.json")) { catalog = (ObjectNode)MAPPER.readTree(in); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
        catalog.path("keys").forEach(n -> info.put(text(n,"id"),(ObjectNode)n));
        writable.addAll(info.keySet());
        catalog.path("providers").forEach(n -> writable.add(text(n,"envVar")));
    }
    private ObjectNode status(String id,Map<String,String> saved) {
        // Only recognized variable names can consult the process environment.
        String env = writable.contains(id) ? store.environment(id) : null, file = saved.get(id);
        String value = env == null ? file : env;
        String masked = value == null ? null : (value.length() <= 4 || !writable.contains(id)) ? "••••"
                : "…"+value.substring(value.length()-4)+" ("+value.length()+" chars)";
        return object("id",id,"info",info.get(id),"present",value!=null,"masked",masked,
                "source",env!=null?"env":file!=null?"file":"none","shadowedByEnv",env!=null && file!=null && !env.equals(file));
    }
    ObjectNode list() {
        var files = store.saved();
        Set<String> ids = new LinkedHashSet<>(info.keySet());
        files.keySet().stream().filter(k -> !k.startsWith("LH_") && !k.contains("BINDING")).forEach(ids::add);
        writable.stream().filter(id -> store.environment(id)!=null).forEach(ids::add);
        return object("keys",ids.stream().map(id -> status(id,files)).toList(),"providers",catalog.path("providers"));
    }
    ObjectNode change(String id,ObjectNode input) {
        if (!writable.contains(id)) throw new Refusal(404,"unknown key \""+id+"\"");
        String value = null;
        if (input != null) {
            if (!input.path("value").isString()) throw new Refusal(400,"body must be {value: string}");
            value = text(input,"value").strip();
            String prefix = switch(id) {case "FIRECRAWL_API_KEY" -> "fc-";case "TAVILY_API_KEY" -> "tvly-";default -> "";};
            int min = Set.of("TAVILY_API_KEY","BRAVE_API_KEY","FIRECRAWL_API_KEY","DEEPGRAM_API_KEY").contains(id)?20:12;
            if (!value.startsWith(prefix) || value.length()<min || value.chars().anyMatch(Character::isWhitespace))
                throw new Refusal(400,"Invalid key format for "+id);
        }
        store.put(id,value);
        return object("status",status(id,store.saved()));
    }
}
