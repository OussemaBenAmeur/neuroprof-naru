package org.naruprof.jpa;

import static org.junit.jupiter.api.Assertions.*;
import static org.naruprof.infra.Json.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.naruprof.infra.Projection;

class JpaProfileTest {
    @Test void serializedOutboxSurvivesRollbackAndProcessReopen(@TempDir Path dir) throws Exception {
        String profile;
        try (var db = new JpaProfile(dir,null); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            profile = db.read(v -> v.snapshot().profileId());
            var futures = new ArrayList<Future<?>>();
            for (int n=0;n<16;n++) {
                String id = "chat_"+n;
                futures.add(pool.submit(() -> db.write(tx -> {tx.save("chats",id,object("id",id));tx.project(false);return null;})));
            }
            for (var f : futures) f.get();
            assertThrows(IllegalStateException.class,() -> db.write(tx -> {
                tx.remove("chats","chat_0");tx.project(false);throw new IllegalStateException("rollback");
            }));
        }
        try (var db = new JpaProfile(dir,profile)) {
            Projection head = db.read(v->v.snapshot()); assertEquals(16L,head.sequence());
            assertEquals(16L,head.teachingSessions());
            var rows = db.read(v -> v.after(0,32));
            for (int i=0;i<16;i++) { assertEquals(i+1,rows.get(i).sequence());assertEquals(i+1,rows.get(i).teachingSessions()); }
        }
    }
}
