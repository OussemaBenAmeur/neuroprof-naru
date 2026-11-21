package org.naruprof.proof;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import org.naruprof.infra.Json;
import org.naruprof.ports.*;
import tools.jackson.databind.node.ObjectNode;

/** Real, isolated H2 file for service/Servlet tests that also exercise crash boundaries.
 * JpaProfileTest separately drives the production ORM adapter. */
final class DurableFixture implements ProfileStore {
    static final String PROFILE = "f7933216-20db-49cb-90e0-3a9d5396966d";
    private final String url;
    private final Connection connection;
    volatile String failOn;
    DurableFixture(Path directory) throws SQLException {
        url = "jdbc:h2:file:"+directory.resolve("fixture");
        connection = DriverManager.getConnection(url);
        try (var sql = connection.createStatement()) {
            sql.execute("create table if not exists records (collection varchar, id varchar, body clob, primary key(collection,id))");
        }
    }
    synchronized void close() throws SQLException { connection.close(); }
    public synchronized <T> T read(Function<View,T> fn) { return transaction(tx -> fn.apply(tx)); }
    public synchronized <T> T write(Function<Change,T> fn) { return transaction(fn); }
    private <T> T transaction(Function<Change,T> fn) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = fn.apply(new Rows());
                connection.commit();
                return result;
            } catch (RuntimeException | Error error) { connection.rollback(); throw error; }
        } catch (SQLException ex) { throw new IllegalStateException(ex); }
    }
    private class Rows extends ProfileLedger {
        Rows() { super(PROFILE); }
        public ObjectNode get(String group,String id) {
            var rows = query("select body from records where collection=? and id=?",group,id);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        public List<ObjectNode> list(String group) { return query("select body from records where collection=? order by id",group); }
        private List<ObjectNode> query(String sql,String... params) {
            try (var stmt = statement(sql,params); var rs = stmt.executeQuery()) {
                var rows = new ArrayList<ObjectNode>();
                while (rs.next()) rows.add((ObjectNode)Json.MAPPER.readTree(rs.getString(1)));
                return rows;
            } catch (SQLException ex) { throw new IllegalStateException(ex); }
        }
        public void save(String group,String id,ObjectNode row) {
            if (group.equals(failOn)) { failOn = null; throw new IllegalStateException("Injected write failure"); }
            update("merge into records key(collection,id) values(?,?,?)",group,id,row.toString());
        }
        public void remove(String group,String id) {
            update("delete from records where collection=? and id=?",group,id);
        }
        private void update(String sql,String... params) {
            try (var stmt = statement(sql,params)) { stmt.executeUpdate(); }
            catch (SQLException ex) { throw new IllegalStateException(ex); }
        }
        private PreparedStatement statement(String sql,String... params) throws SQLException {
            var stmt = connection.prepareStatement(sql);
            for (int i=0;i<params.length;i++) stmt.setString(i+1,params[i]);
            return stmt;
        }
    }
}
