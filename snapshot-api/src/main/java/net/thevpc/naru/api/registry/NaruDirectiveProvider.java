package net.thevpc.naru.api.registry;

import net.thevpc.nuts.spi.NComponent;

import java.util.List;

public interface NaruDirectiveProvider extends NComponent {
    String name();                      // "mcp-stdio", "mcp-sse", "builtin", ...

    List<NaruDirective> directives();   // static: what types I know about
}
