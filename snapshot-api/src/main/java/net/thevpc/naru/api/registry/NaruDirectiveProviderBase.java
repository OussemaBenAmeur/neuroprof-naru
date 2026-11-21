package net.thevpc.naru.api.registry;

import java.util.*;

public class NaruDirectiveProviderBase implements NaruDirectiveProvider {
    private final Map<String,NaruDirective> availableDirectives = new LinkedHashMap<>();
    private final Map<String, String> directiveAliases = new LinkedHashMap<>();
    private String name;
    public NaruDirectiveProviderBase(String name) {
        this.name=name;
    }

    protected NaruDirectiveProviderBase registerDirective(NaruDirective tool) {
        availableDirectives.put(tool.name(), tool);
        for (String alias : tool.getAliases()) {
            String old = directiveAliases.get(alias);
            if (old != null && !old.equals(tool.name())) {
                throw new IllegalArgumentException("alias " + alias + " is already used by " + old);
            }
            directiveAliases.put(alias, tool.name());
        }
        return this;
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public List<NaruDirective> directives() {
        return Collections.unmodifiableList(new ArrayList<>(availableDirectives.values()));
    }
}