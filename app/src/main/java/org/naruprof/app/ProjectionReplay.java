package org.naruprof.app;

import static org.naruprof.infra.Json.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.naruprof.api.ProjectionRead;
import org.naruprof.grpc.ProjectionServer;
import org.naruprof.infra.*;
import tools.jackson.databind.JsonNode;

/** Scripted Ditto integration peer. The production RPC implementation serves the replay. */
final class ProjectionReplay implements ProjectionRead {
    private final List<Projection> timeline;
    private final AtomicInteger head = new AtomicInteger();

    ProjectionReplay(List<Projection> timeline) {
        if (timeline.isEmpty() || timeline.getFirst().sequence() != 0)
            throw new IllegalArgumentException("Replay begins with sequence zero");
        for (int i=0;i<timeline.size();i++) {
            var revision = timeline.get(i);
            if (revision.sequence() != i || !revision.profileId().equals(timeline.getFirst().profileId()))
                throw new IllegalArgumentException("Replay requires consecutive revisions for one profile");
        }
        this.timeline = List.copyOf(timeline);
    }
    public Projection snapshot(String profile) {
        if (!timeline.getFirst().profileId().equals(profile)) throw new Refusal(404,"Unknown profile");
        return timeline.get(head.get());
    }
    public List<Projection> replay(String profile,long after,int limit) {
        long last = snapshot(profile).sequence();
        if (Long.compareUnsigned(after,last)>0) throw new Refusal(416,"Cursor ahead of profile");
        return timeline.subList((int)after+1,(int)Math.min(last+1,after+1L+limit));
    }
    void advance() { head.updateAndGet(index -> Math.min(index+1,timeline.size()-1)); }

    static void run(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("projection-replay <projection.jsonl>");
        List<Projection> rows;
        try (var lines = Files.lines(Path.of(args[1]))) {
            rows = lines.filter(line -> !line.isBlank()).map(MAPPER::readTree).map(ProjectionReplay::row).toList();
        }
        var replay = new ProjectionReplay(rows);
        URI address = URI.create("grpc://"+System.getenv().getOrDefault("NARU_GRPC_TARGET","127.0.0.1:9092"));
        try (var server = new ProjectionServer(replay,new InetSocketAddress(address.getHost(),address.getPort()),System.getenv("TWIN_TO_NARU_TOKEN"));
             var timer = Executors.newSingleThreadScheduledExecutor()) {
            timer.scheduleAtFixedRate(replay::advance,2,2,TimeUnit.SECONDS);
            System.out.println("Projection fixture listening on port "+server.port());
            try { new CountDownLatch(1).await(); }
            finally { timer.shutdownNow(); }
        }
    }
    private static Projection row(JsonNode row) {
        var summary = row.path("summary");
        if (summary.isObject() == row.path("deleted").isObject()) throw new IllegalArgumentException("Expected exactly one replay state");
        return new Projection(text(row,"profileId"),unsigned(row,"sequence"),
                unsigned(summary,"teachingSessions"),unsigned(summary,"practiceSessions"),
                unsigned(summary,"propositions"),unsigned(summary,"reasoningChains"),row.has("deleted"));
    }
    private static long unsigned(JsonNode row,String field) {
        return row.isMissingNode() ? 0 : Long.parseUnsignedLong(text(row,field));
    }
}
