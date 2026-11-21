package org.naruprof.ports;

import java.util.Map;

public interface KeyStore {
    Map<String,String> saved();
    String environment(String id);
    void put(String id,String value);
    default String resolve(String id) {
        String env = environment(id);
        return env == null ? saved().get(id) : env;
    }
}
