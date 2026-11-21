package org.naruprof.grpc;

import io.grpc.*;
import io.grpc.stub.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.naruprof.api.ProjectionRead;
import org.naruprof.contract.learning.v1.*;
import org.naruprof.infra.Projection;
import org.naruprof.infra.Refusal;

public final class ProjectionServer implements AutoCloseable {
    private final Server server;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    public ProjectionServer(ProjectionRead learning, InetSocketAddress address, String token) throws IOException {
        this(learning,NettyServerBuilder.forAddress(address),token);
    }
    ProjectionServer(ProjectionRead learning,ServerBuilder<?> builder,String token) throws IOException {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("TWIN_TO_NARU_TOKEN required");
        byte[] expected = ("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        server = builder.addService(new Feed(learning)).intercept(new ServerInterceptor() {
            public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata meta,ServerCallHandler<Q,S> next) {
                var values = meta.getAll(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER));
                if (values != null) {
                    var headers = values.iterator();
                    if (headers.hasNext() && MessageDigest.isEqual(expected,headers.next().getBytes(StandardCharsets.UTF_8)) && !headers.hasNext())
                        return next.startCall(call,meta);
                }
                call.close(Status.UNAUTHENTICATED,new Metadata());
                return new ServerCall.Listener<>() {};
            }
        }).build().start();
    }
    public int port() { return server.getPort(); }
    public void close() { server.shutdownNow(); streams.shutdownNow(); }
    private static ProjectionState wire(Projection p) {
        var state = ProjectionState.newBuilder().setProfileId(p.profileId()).setSequence(p.sequence());
        if (p.deleted()) state.setDeleted(Deleted.getDefaultInstance());
        else state.setSummary(Summary.newBuilder().setTeachingSessions(p.teachingSessions()).setPracticeSessions(p.practiceSessions())
                .setPropositions(p.propositions()).setReasoningChains(p.reasoningChains()));
        return state.build();
    }
    private static Status status(Exception e) {
        if (e instanceof Refusal r) return r.status == 404 ? Status.NOT_FOUND : Status.OUT_OF_RANGE;
        return Status.UNAVAILABLE;
    }
    private final class Feed implements BindableService {
        private final ProjectionRead learning;
        Feed(ProjectionRead learning) { this.learning = learning; }
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(ProjectionBinding.NAME)
                    .addMethod(ProjectionBinding.SNAPSHOT,ServerCalls.asyncUnaryCall(this::getSnapshot))
                    .addMethod(ProjectionBinding.WATCH,ServerCalls.asyncServerStreamingCall(this::watch)).build();
        }
        public void getSnapshot(Profile profile,StreamObserver<ProjectionState> out) {
            try { out.onNext(wire(learning.snapshot(profile.getProfileId()))); out.onCompleted(); }
            catch (RuntimeException ex) { out.onError(status(ex).asRuntimeException()); }
        }
        public void watch(WatchRequest request,StreamObserver<ProjectionState> observer) {
            var out = (ServerCallStreamObserver<ProjectionState>) observer;
            streams.execute(() -> {
                long cursor = request.getAfterSequence();
                long idleMs = 25;
                try {
                    while (!out.isCancelled() && !Thread.currentThread().isInterrupted()) {
                        var rows = learning.replay(request.getProfileId(),cursor,64);
                        for (var row : rows) {
                            if (out.isCancelled()) return;
                            if (!out.isReady()) { out.onError(Status.RESOURCE_EXHAUSTED.withDescription("Resume from last applied sequence").asRuntimeException()); return; }
                            out.onNext(wire(row));
                            cursor = row.sequence();
                        }
                        if (rows.isEmpty()) { Thread.sleep(idleMs); idleMs = Math.min(500,idleMs*2); }
                        else idleMs = 25;
                    }
                } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                catch (RuntimeException ex) { if (!out.isCancelled()) out.onError(status(ex).asRuntimeException()); }
            });
        }
    }
}
