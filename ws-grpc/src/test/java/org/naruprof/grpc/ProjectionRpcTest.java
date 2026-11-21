package org.naruprof.grpc;

import static org.junit.jupiter.api.Assertions.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.naruprof.api.Learning;
import org.naruprof.contract.learning.v1.*;
import org.naruprof.infra.*;
import org.junit.jupiter.api.Test;

class ProjectionRpcTest {
    @Test void authenticatedSnapshotWatchCursorRefusalAndLiveDeletion() throws Exception {
        String id = "f7933216-20db-49cb-90e0-3a9d5396966d", name = UUID.randomUUID().toString();
        var rows = new CopyOnWriteArrayList<>(List.of(new Projection(id,0,0,0,0,0,false),
                new Projection(id,1,1,0,0,0,false),new Projection(id,2,1,1,0,0,false),new Projection(id,3,1,1,2,1,false)));
        // A deterministic committed peer isolates the actual gRPC binding/metadata from storage.
        Learning api = (Learning)Proxy.newProxyInstance(Learning.class.getClassLoader(),new Class[]{Learning.class},(p,m,args) -> {
            if (!id.equals(args[0])) throw new Refusal(404,"Unknown profile");
            if (m.getName().equals("snapshot")) return rows.getLast();
            if (!m.getName().equals("replay")) throw new AssertionError();
            long after = (long)args[1];
            if (Long.compareUnsigned(after,rows.getLast().sequence())>0) throw new Refusal(416,"Cursor ahead");
            return rows.stream().filter(r -> r.sequence()>after).toList();
        });
        try (var server = new ProjectionServer(api,InProcessServerBuilder.forName(name).directExecutor(),"fixture-twin")) {
            var raw = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                var profile = Profile.newBuilder().setProfileId(id).build();
                var options = CallOptions.DEFAULT.withDeadlineAfter(5,TimeUnit.SECONDS);
                assertEquals(Status.Code.UNAUTHENTICATED,assertThrows(StatusRuntimeException.class,
                        () -> ClientCalls.blockingUnaryCall(raw,ProjectionBinding.SNAPSHOT,options,profile)).getStatus().getCode());
                var metadata = new Metadata();
                metadata.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer fixture-twin");
                Channel client = ClientInterceptors.intercept(raw,MetadataUtils.newAttachHeadersInterceptor(metadata));
                var state = ClientCalls.blockingUnaryCall(client,ProjectionBinding.SNAPSHOT,options,profile);
                assertEquals(3,state.getSequence()); assertEquals(2,state.getSummary().getPropositions());
                assertEquals(Status.Code.NOT_FOUND,assertThrows(StatusRuntimeException.class,() -> ClientCalls.blockingUnaryCall(client,
                        ProjectionBinding.SNAPSHOT,options,Profile.newBuilder().setProfileId("other").build())).getStatus().getCode());
                var watch = WatchRequest.newBuilder().setProfileId(id);
                assertEquals(Status.Code.OUT_OF_RANGE,assertThrows(StatusRuntimeException.class,() -> ClientCalls.blockingServerStreamingCall(client,
                        ProjectionBinding.WATCH,options,watch.setAfterSequence(-1L).build()).next()).getStatus().getCode());
                var stream = ClientCalls.blockingServerStreamingCall(client,ProjectionBinding.WATCH,options,watch.setAfterSequence(1).build());
                assertEquals(2,stream.next().getSequence()); assertEquals(3,stream.next().getSequence());
                rows.add(new Projection(id,4,0,0,0,0,true)); assertTrue(stream.next().hasDeleted());
            } finally { raw.shutdownNow().awaitTermination(2,TimeUnit.SECONDS); }
        }
    }
}
