package org.naruprof.ports;

import java.util.List;
import java.util.function.Function;
import org.naruprof.infra.Projection;
import tools.jackson.databind.node.ObjectNode;

/** A callback observes one committed profile. Writers hold the profile fence through commit. */
public interface ProfileStore {
    interface View {
        ObjectNode get(String collection, String id);
        List<ObjectNode> list(String collection);
        Projection snapshot();
        List<Projection> after(long sequence, int limit);
    }
    interface Change extends View {
        void save(String collection, String id, ObjectNode record);
        void remove(String collection, String id);
        void project(boolean retired);
    }
    <T> T read(Function<View,T> action);
    <T> T write(Function<Change,T> action);
}
