package org.naruprof.ports;

import java.util.ArrayList;
import java.util.List;
import org.naruprof.infra.Json;
import org.naruprof.infra.Projection;
import org.naruprof.infra.Refusal;
import tools.jackson.databind.node.ObjectNode;

/** Shared outbox semantics. The enclosing store serializes the transaction through commit. */
public abstract class ProfileLedger implements ProfileStore.Change {
    private final String profile;
    protected ProfileLedger(String profile) { this.profile = profile; }

    public Projection snapshot() {
        ObjectNode head = get("meta", "head");
        return head == null ? new Projection(profile, 0, 0, 0, 0, 0, false)
                : Json.MAPPER.treeToValue(head, Projection.class);
    }

    public List<Projection> after(long cursor, int limit) {
        long head = snapshot().sequence();
        if (Long.compareUnsigned(cursor, head) > 0) throw new Refusal(416, "Cursor ahead of profile");
        var result = new ArrayList<Projection>();
        while (cursor != head && result.size() < limit) {
            var row = get("outbox", Long.toUnsignedString(++cursor));
            if (row == null) throw new Refusal(416, "Cursor expired");
            result.add(Json.MAPPER.treeToValue(row, Projection.class));
        }
        return result;
    }

    public void project(boolean retired) {
        Projection old = snapshot();
        if (old.deleted()) throw new Refusal(410, "Profile retired");
        var chats = list("chats");
        long practice = chats.stream().filter(c -> "practice".equals(Json.text(c, "kind"))).count();
        long teaching = chats.size() - practice;
        long nodes = list("nodes").size();
        long chains = list("chains").size();
        if (!retired && teaching == old.teachingSessions() && practice == old.practiceSessions()
                && nodes == old.propositions() && chains == old.reasoningChains()) return;
        if (old.sequence() == -1L) throw new IllegalStateException("Sequence exhausted");
        var next = new Projection(profile, old.sequence() + 1, teaching, practice, nodes, chains, retired);
        ObjectNode body = Json.MAPPER.valueToTree(next);
        save("outbox", Long.toUnsignedString(next.sequence()), body);
        save("meta", "head", body);
    }
}
