package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NaruBuiltinToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("builtin");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "builtin":
                return new DefaultNaruToolset(id, builtins());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC("%s: unknown type '%s'",getClass().getSimpleName(), type)
                );
        }
    }

    private List<NaruTool> builtins() {
        //by default there is nothing!!
        return Collections.emptyList();
    }


}