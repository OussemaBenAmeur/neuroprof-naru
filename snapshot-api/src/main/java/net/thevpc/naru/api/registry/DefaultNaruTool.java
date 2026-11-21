package net.thevpc.naru.api.registry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public abstract class DefaultNaruTool implements NaruTool {
    private final String name;
    private final Set<String> tags;

    public DefaultNaruTool(String name, String[] tags) {
        this.name = name;
        this.tags = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(tags)));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> tags() {
        return tags;
    }


}
