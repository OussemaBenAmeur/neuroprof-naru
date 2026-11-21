package org.naruprof.runtime;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.naruprof.ports.KeyStore;
import org.naruprof.infra.Json;

public final class ProtectedKeys implements KeyStore {
    private final Path file;
    public ProtectedKeys(Path file) { this.file = file.toAbsolutePath(); }
    public String environment(String id) { return "1".equals(System.getenv("LH_IGNORE_ENV_KEYS")) ? null : System.getenv(id); }
    public synchronized Map<String,String> saved() {
        if (!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Auth file is not a regular file");
        try {
            var root = Json.MAPPER.readTree(Files.readAllBytes(file));
            if (!root.isObject()) throw new IllegalStateException("Invalid auth store");
            var values = new LinkedHashMap<String,String>();
            for (var entry : root.properties()) if (entry.getValue().isString()) values.put(entry.getKey(),entry.getValue().asString());
            return Map.copyOf(values);
        } catch (IOException e) { throw new IllegalStateException("Cannot read protected auth file"); }
    }
    public synchronized void put(String id,String value) {
        Map<String,String> next = new TreeMap<>(saved());
        if (value == null) next.remove(id); else next.put(id,value);
        try {
            Files.createDirectories(file.getParent());
            Path temp;
            try { temp = Files.createTempFile(file.getParent(),".auth-",null,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException os) { temp = Files.createTempFile(file.getParent(),".auth-",null); }
            try {
                Files.write(temp,Json.MAPPER.writeValueAsBytes(next),StandardOpenOption.WRITE,StandardOpenOption.SYNC);
                Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new IllegalStateException("Cannot save protected auth file"); }
    }
}
