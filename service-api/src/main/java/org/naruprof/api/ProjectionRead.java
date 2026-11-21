package org.naruprof.api;

import java.util.List;
import org.naruprof.infra.Projection;

/** Read-only facet exposed to the projection relay. */
public interface ProjectionRead {
    Projection snapshot(String profileId);
    List<Projection> replay(String profileId,long after,int limit);
}
