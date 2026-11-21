package org.naruprof.jpa;

import jakarta.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.naruprof.ports.*;
import org.naruprof.infra.*;
import tools.jackson.databind.node.ObjectNode;

public final class JpaProfile implements ProfileStore, AutoCloseable {
    private final EntityManagerFactory factory;
    private final String profile;
    public JpaProfile(Path directory, String requestedId) {
        String db = directory.resolve("learning").toAbsolutePath().toString();
        if (db.contains(";")) throw new IllegalArgumentException("Invalid profile path");
        factory = Persistence.createEntityManagerFactory("learning", Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:h2:file:" + db,
                "jakarta.persistence.jdbc.user", "sa",
                "hibernate.hbm2ddl.auto", "update",
                "hibernate.show_sql", "false"));
        try {
            profile = transaction(em -> {
                StoredRecord identity = em.find(StoredRecord.class, "identity");
                if (identity == null) {
                    String id = requestedId == null ? UUID.randomUUID().toString() : UUID.fromString(requestedId).toString();
                    em.persist(new StoredRecord("identity", "identity", id));
                    return id;
                }
                if (requestedId != null && !identity.payload.equals(requestedId)) throw new IllegalStateException("Configured profile differs from stored identity");
                return identity.payload;
            });
        } catch (RuntimeException e) { factory.close(); throw e; }
    }
    private <T> T transaction(Function<EntityManager,T> f) {
        try (var em = factory.createEntityManager()) {
            var tx = em.getTransaction();
            tx.begin();
            try {
                T result = f.apply(em);
                tx.commit();
                return result;
            } catch (RuntimeException | Error e) {
                if (tx.isActive()) tx.rollback();
                throw e;
            }
        }
    }
    public <T> T read(Function<View,T> f) {
        return transaction(em -> {
            em.find(StoredRecord.class, "identity", LockModeType.PESSIMISTIC_READ);
            return f.apply(new Rows(em));
        });
    }
    public <T> T write(Function<Change,T> f) {
        return transaction(em -> {
            em.find(StoredRecord.class, "identity", LockModeType.PESSIMISTIC_WRITE);
            var rows = new Rows(em);
            if (rows.snapshot().deleted()) throw new Refusal(410, "Profile retired");
            return f.apply(rows);
        });
    }
    public void close() { factory.close(); }

    private final class Rows extends ProfileLedger {
        private final EntityManager em;
        Rows(EntityManager em) { super(profile); this.em = em; }
        public ObjectNode get(String collection, String id) {
            var row = em.find(StoredRecord.class, collection + "/" + id);
            return row == null ? null : (ObjectNode) Json.MAPPER.readTree(row.payload);
        }
        public List<ObjectNode> list(String collection) {
            return em.createQuery("from StoredRecord where collectionName = :c order by recordKey", StoredRecord.class)
                    .setParameter("c", collection).getResultList().stream()
                    .map(row -> (ObjectNode) Json.MAPPER.readTree(row.payload)).toList();
        }
        public void save(String collection, String id, ObjectNode body) {
            String key = collection + "/" + id;
            var row = em.find(StoredRecord.class, key);
            if (row == null) em.persist(new StoredRecord(key, collection, body.toString()));
            else row.payload = body.toString();
        }
        public void remove(String collection, String id) {
            var row = em.find(StoredRecord.class, collection + "/" + id);
            if (row != null) em.remove(row);
        }
    }
}
