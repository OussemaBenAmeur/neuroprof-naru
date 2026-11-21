package org.naruprof.grpc;

import com.google.protobuf.Message;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import org.naruprof.contract.learning.v1.*;

/** Derive full names and streaming cardinality from protoc's service descriptor.
 * This also allows an in-process consumer to exercise the wire without a network listener. */
final class ProjectionBinding {
    private static final com.google.protobuf.Descriptors.ServiceDescriptor SERVICE =
            LearningProjectionV1.getDescriptor().findServiceByName("LearningProjection");
    static final String NAME = SERVICE.getFullName();
    static final MethodDescriptor<Profile,ProjectionState> SNAPSHOT = method("GetSnapshot",Profile.getDefaultInstance());
    static final MethodDescriptor<WatchRequest,ProjectionState> WATCH = method("Watch",WatchRequest.getDefaultInstance());
    private static <T extends Message> MethodDescriptor<T,ProjectionState> method(String name,T input) {
        var rpc = SERVICE.findMethodByName(name);
        if (!rpc.getInputType().equals(input.getDescriptorForType()) || !rpc.getOutputType().equals(ProjectionState.getDescriptor()))
            throw new IllegalStateException("Projection contract changed");
        return MethodDescriptor.<T,ProjectionState>newBuilder().setFullMethodName(MethodDescriptor.generateFullMethodName(NAME,rpc.getName()))
                .setType(rpc.isServerStreaming() ? MethodDescriptor.MethodType.SERVER_STREAMING : MethodDescriptor.MethodType.UNARY)
                .setRequestMarshaller(ProtoUtils.marshaller(input)).setResponseMarshaller(ProtoUtils.marshaller(ProjectionState.getDefaultInstance())).build();
    }
}
