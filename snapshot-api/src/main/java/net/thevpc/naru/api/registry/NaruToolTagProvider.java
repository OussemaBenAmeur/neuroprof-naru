package net.thevpc.naru.api.registry;

import net.thevpc.nuts.spi.NComponent;

import java.util.List;

public interface NaruToolTagProvider extends NComponent {
    String name();
    List<NaruToolTag> tags();
}
