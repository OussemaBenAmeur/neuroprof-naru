package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;

import java.util.Collections;
import java.util.List;

public record DefaultNaruToolset(String id, List<NaruTool> tools) implements NaruToolset {

    public DefaultNaruToolset(String id, List<NaruTool> tools) {
        this.id = id;
        this.tools = Collections.unmodifiableList(tools);
    }

    @Override
    public void open(NaruSession session) { /* nothing */ }

    @Override
    public void close() { /* nothing */ }
}
