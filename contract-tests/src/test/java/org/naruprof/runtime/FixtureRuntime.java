package org.naruprof.runtime;

import java.nio.file.Path;
import java.util.function.Function;
import tools.jackson.databind.node.ObjectNode;

public class FixtureRuntime {
    public static NaruRuntime create(Path directory,Function<ObjectNode,ObjectNode> exchange) throws Exception {
        return new NaruRuntime(directory,exchange::apply);
    }
}
