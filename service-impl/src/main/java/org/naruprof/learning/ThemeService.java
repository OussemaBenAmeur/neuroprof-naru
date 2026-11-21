package org.naruprof.learning;

import static org.naruprof.infra.Json.*;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import org.naruprof.infra.Refusal;
import org.naruprof.ports.ProfileStore;
import tools.jackson.databind.node.*;

final class ThemeService {
    private final ObjectNode catalog;
    ThemeService() {
        try (var in = getClass().getResourceAsStream("/themes.json")) { catalog = (ObjectNode) MAPPER.readTree(in); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    private boolean builtin(String id) { return catalog.path("themes").valueStream().anyMatch(t -> id.equals(text(t,"id"))); }
    ObjectNode list(ProfileStore.View tx) {
        var themes = ((ArrayNode)catalog.get("themes")).deepCopy();
        for (var theme : tx.list("themes")) themes.add(theme.put("builtin",false));
        return object("themes",themes);
    }
    ObjectNode create(ProfileStore.Change tx,ObjectNode input) {
        String name = text(input,"name").trim(), mode = text(input,"mode");
        if (name.isBlank()) throw new Refusal(400,"name must be a non-empty string");
        String base = Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-");
        base = base.substring(0,Math.min(64,base.length())).replaceAll("^-+|-+$","");
        if (base.isEmpty()) throw new Refusal(400,"name must contain a letter or digit");
        if (!Set.of("dark","light").contains(mode)) throw new Refusal(400,"mode must be \"dark\" or \"light\"");
        var colors = object();
        for (var k : catalog.path("colors")) {
            String key = k.asString(), value = text(input.path("colors"),key);
            if (!value.matches("#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{6})")) throw new Refusal(400,"colors."+key+" must be a #hex color");
            colors.put(key,value);
        }
        String id = base;
        for (int n=2; builtin(id) || tx.get("themes",id) != null; n++) {
            String suffix = "-"+n; id = base.substring(0,Math.min(base.length(),64-suffix.length())).replaceAll("-+$","")+suffix;
        }
        var theme = object("id",id,"name",name,"mode",mode,"colors",colors,"builtin",false);
        tx.save("themes",id,theme);
        return object("theme",theme);
    }
    ObjectNode delete(ProfileStore.Change tx,String id) {
        if (builtin(id)) throw new Refusal(400,"\""+id+"\" is a built-in theme");
        var theme = tx.get("themes",id);
        if (theme == null) throw new Refusal(404,"unknown theme \""+id+"\"");
        tx.remove("themes",id);
        var settings = tx.get("settings","current");
        if (settings != null && id.equals(settings.at("/appearance/theme").asString(""))) {
            settings.withObject("appearance").put("theme","default");
            tx.save("settings","current",settings);
        }
        return object("theme",theme);
    }
}
