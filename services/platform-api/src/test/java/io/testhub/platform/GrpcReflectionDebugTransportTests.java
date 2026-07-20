package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.health.v1.HealthProto;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.testhub.platform.apidebug.GrpcDebugTransport;
import io.testhub.platform.apidebug.GrpcReflectionDebugTransport;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GrpcReflectionDebugTransportTests {
    @Test
    void invokesUnaryMethodFromReflectionDescriptors() throws Exception {
        AtomicInteger metadataChecks = new AtomicInteger();
        ServerInterceptor requiresMetadata = new MetadataCheckingInterceptor(metadataChecks);
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(new ServingHealthService(), requiresMetadata))
                .addService(ServerInterceptors.intercept(ProtoReflectionService.newInstance(), requiresMetadata))
                .build()
                .start();
        try {
            GrpcDebugTransport.Response response = new GrpcReflectionDebugTransport().unary(new GrpcDebugTransport.Request(
                    "127.0.0.1", server.getPort(), false, "grpc.health.v1.Health", "Check", "{\"service\":\"\"}", 2_000,
                    java.util.List.of(new GrpcDebugTransport.MetadataEntry("x-testhub-trace", "trace-123"))));

            assertTrue(response.responseJson().contains("SERVING"));
            assertEquals(2, metadataChecks.get());
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void discoversServicesAndMethodsWithMetadata() throws Exception {
        AtomicInteger metadataChecks = new AtomicInteger();
        ServerInterceptor requiresMetadata = new MetadataCheckingInterceptor(metadataChecks);
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(new ServingHealthService(), requiresMetadata))
                .addService(ServerInterceptors.intercept(ProtoReflectionService.newInstance(), requiresMetadata))
                .build()
                .start();
        try {
            GrpcReflectionDebugTransport transport = new GrpcReflectionDebugTransport();
            GrpcDebugTransport.DiscoveryRequest request = new GrpcDebugTransport.DiscoveryRequest("127.0.0.1", server.getPort(), false, 2_000,
                    List.of(new GrpcDebugTransport.MetadataEntry("x-testhub-trace", "trace-123")));

            assertTrue(transport.listServices(request).contains("grpc.health.v1.Health"));
            List<GrpcDebugTransport.MethodSummary> methods = transport.listMethods(request, "grpc.health.v1.Health");
            assertTrue(methods.contains(new GrpcDebugTransport.MethodSummary("Check", "UNARY")));
            assertTrue(methods.contains(new GrpcDebugTransport.MethodSummary("Watch", "SERVER_STREAMING")));
            assertEquals(2, metadataChecks.get());
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void invokesUnaryMethodFromOfflineDescriptorWithoutReflectionService() throws Exception {
        AtomicInteger metadataChecks = new AtomicInteger();
        ServerInterceptor requiresMetadata = new MetadataCheckingInterceptor(metadataChecks);
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(new ServingHealthService(), requiresMetadata))
                .build()
                .start();
        try {
            GrpcDebugTransport.Response response = new GrpcReflectionDebugTransport().unary(new GrpcDebugTransport.Request(
                    "127.0.0.1", server.getPort(), false, "grpc.health.v1.Health", "Check", "{\"service\":\"\"}", 2_000,
                    List.of(new GrpcDebugTransport.MetadataEntry("x-testhub-trace", "trace-123")), List.of(HealthProto.getDescriptor().toProto())));

            assertTrue(response.responseJson().contains("SERVING"));
            assertEquals(1, metadataChecks.get());
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void receivesServerStreamingMethodWithBoundedDemand() throws Exception {
        AtomicInteger metadataChecks = new AtomicInteger();
        ServerInterceptor requiresMetadata = new MetadataCheckingInterceptor(metadataChecks);
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(new ServingHealthService(), requiresMetadata))
                .addService(ServerInterceptors.intercept(ProtoReflectionService.newInstance(), requiresMetadata))
                .build()
                .start();
        try {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger messages = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            new GrpcReflectionDebugTransport().serverStreaming(new GrpcDebugTransport.Request(
                    "127.0.0.1", server.getPort(), false, "grpc.health.v1.Health", "Watch", "{\"service\":\"\"}", 2_000,
                    List.of(new GrpcDebugTransport.MetadataEntry("x-testhub-trace", "trace-123"))), new GrpcDebugTransport.StreamListener() {
                @Override
                public void onMessage(String responseJson) {
                    assertTrue(responseJson.contains("SERVING"));
                    messages.incrementAndGet();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }

                @Override
                public void onError(String status) {
                    failures.incrementAndGet();
                    completed.countDown();
                }
            });

            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(2, messages.get());
            assertEquals(0, failures.get());
            assertEquals(2, metadataChecks.get());
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void sendsClientStreamingMethodWithOfflineDescriptor() throws Exception {
        AtomicInteger metadataChecks = new AtomicInteger();
        ServerInterceptor requiresMetadata = new MetadataCheckingInterceptor(metadataChecks);
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(clientStreamingService(), requiresMetadata))
                .build()
                .start();
        try {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger responses = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            GrpcDebugTransport.ClientStreamSubscription stream = new GrpcReflectionDebugTransport().clientStreaming(new GrpcDebugTransport.Request(
                    "127.0.0.1", server.getPort(), false, "testhub.stream.v1.Collector", "Collect", "{}", 2_000,
                    List.of(new GrpcDebugTransport.MetadataEntry("x-testhub-trace", "trace-123")),
                    List.of(clientStreamingDescriptor(), HealthProto.getDescriptor().toProto())), new GrpcDebugTransport.ClientStreamListener() {
                @Override
                public void onResponse(String responseJson) {
                    assertTrue(responseJson.contains("SERVING"));
                    responses.incrementAndGet();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }

                @Override
                public void onError(String status) {
                    failures.incrementAndGet();
                    completed.countDown();
                }
            });
            stream.send("{\"service\":\"first\"}");
            stream.send("{\"service\":\"second\"}");
            stream.complete();

            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(1, responses.get());
            assertEquals(0, failures.get());
            assertEquals(1, metadataChecks.get());
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void sendsAndReceivesBidirectionalMethodWithOfflineDescriptor() throws Exception {
        AtomicInteger metadataChecks = new AtomicInteger();
        ServerInterceptor requiresMetadata = new MetadataCheckingInterceptor(metadataChecks);
        Server server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(bidiStreamingService(), requiresMetadata))
                .build()
                .start();
        try {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger responses = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            GrpcDebugTransport.BidiStreamSubscription stream = new GrpcReflectionDebugTransport().bidiStreaming(new GrpcDebugTransport.Request(
                    "127.0.0.1", server.getPort(), false, "testhub.stream.v1.Chat", "Exchange", "{}", 2_000,
                    List.of(new GrpcDebugTransport.MetadataEntry("x-testhub-trace", "trace-123")),
                    List.of(bidiStreamingDescriptor(), HealthProto.getDescriptor().toProto())), new GrpcDebugTransport.BidiStreamListener() {
                @Override
                public void onMessage(String responseJson) {
                    assertTrue(responseJson.contains("SERVING"));
                    responses.incrementAndGet();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }

                @Override
                public void onError(String status) {
                    failures.incrementAndGet();
                    completed.countDown();
                }
            });
            stream.send("{\"service\":\"first\"}");
            stream.send("{\"service\":\"second\"}");
            stream.complete();

            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(2, responses.get());
            assertEquals(0, failures.get());
            assertEquals(1, metadataChecks.get());
        } finally {
            server.shutdownNow();
        }
    }

    private static ServerServiceDefinition clientStreamingService() {
        MethodDescriptor<HealthCheckRequest, HealthCheckResponse> collect = MethodDescriptor.<HealthCheckRequest, HealthCheckResponse>newBuilder()
                .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
                .setFullMethodName(MethodDescriptor.generateFullMethodName("testhub.stream.v1.Collector", "Collect"))
                .setRequestMarshaller(ProtoUtils.marshaller(HealthCheckRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(HealthCheckResponse.getDefaultInstance()))
                .build();
        return ServerServiceDefinition.builder("testhub.stream.v1.Collector")
                .addMethod(collect, ServerCalls.asyncClientStreamingCall(response -> new StreamObserver<HealthCheckRequest>() {
                    private int count;

                    @Override
                    public void onNext(HealthCheckRequest ignored) {
                        count++;
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        // The transport cancellation test covers the client-side terminal path.
                    }

                    @Override
                    public void onCompleted() {
                        response.onNext(HealthCheckResponse.newBuilder().setStatus(count == 2
                                ? HealthCheckResponse.ServingStatus.SERVING : HealthCheckResponse.ServingStatus.NOT_SERVING).build());
                        response.onCompleted();
                    }
                })).build();
    }

    private static ServerServiceDefinition bidiStreamingService() {
        MethodDescriptor<HealthCheckRequest, HealthCheckResponse> exchange = MethodDescriptor.<HealthCheckRequest, HealthCheckResponse>newBuilder()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName(MethodDescriptor.generateFullMethodName("testhub.stream.v1.Chat", "Exchange"))
                .setRequestMarshaller(ProtoUtils.marshaller(HealthCheckRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(HealthCheckResponse.getDefaultInstance()))
                .build();
        return ServerServiceDefinition.builder("testhub.stream.v1.Chat")
                .addMethod(exchange, ServerCalls.asyncBidiStreamingCall(response -> new StreamObserver<HealthCheckRequest>() {
                    @Override
                    public void onNext(HealthCheckRequest ignored) {
                        response.onNext(HealthCheckResponse.newBuilder().setStatus(HealthCheckResponse.ServingStatus.SERVING).build());
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        // The client may cancel an active bidirectional exchange at any time.
                    }

                    @Override
                    public void onCompleted() {
                        response.onCompleted();
                    }
                })).build();
    }

    private static FileDescriptorProto clientStreamingDescriptor() {
        return FileDescriptorProto.newBuilder().setName("testhub/client_stream.proto").setPackage("testhub.stream.v1").setSyntax("proto3")
                .addDependency(HealthProto.getDescriptor().getName())
                .addService(ServiceDescriptorProto.newBuilder().setName("Collector")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Collect").setClientStreaming(true)
                                .setInputType(".grpc.health.v1.HealthCheckRequest").setOutputType(".grpc.health.v1.HealthCheckResponse")))
                .build();
    }

    private static FileDescriptorProto bidiStreamingDescriptor() {
        return FileDescriptorProto.newBuilder().setName("testhub/bidi_stream.proto").setPackage("testhub.stream.v1").setSyntax("proto3")
                .addDependency(HealthProto.getDescriptor().getName())
                .addService(ServiceDescriptorProto.newBuilder().setName("Chat")
                        .addMethod(MethodDescriptorProto.newBuilder().setName("Exchange").setClientStreaming(true).setServerStreaming(true)
                                .setInputType(".grpc.health.v1.HealthCheckRequest").setOutputType(".grpc.health.v1.HealthCheckResponse")))
                .build();
    }

    private static final class MetadataCheckingInterceptor implements ServerInterceptor {
        private static final Metadata.Key<String> TRACE = Metadata.Key.of("x-testhub-trace", Metadata.ASCII_STRING_MARSHALLER);
        private final AtomicInteger checks;

        private MetadataCheckingInterceptor(AtomicInteger checks) {
            this.checks = checks;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                     ServerCallHandler<ReqT, RespT> next) {
            if (!"trace-123".equals(headers.get(TRACE))) {
                call.close(Status.UNAUTHENTICATED.withDescription("missing trace metadata"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
            checks.incrementAndGet();
            return next.startCall(call, headers);
        }
    }

    private static final class ServingHealthService extends HealthGrpc.HealthImplBase {
        @Override
        public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> observer) {
            observer.onNext(HealthCheckResponse.newBuilder()
                    .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                    .build());
            observer.onCompleted();
        }

        @Override
        public void watch(HealthCheckRequest request, StreamObserver<HealthCheckResponse> observer) {
            observer.onNext(HealthCheckResponse.newBuilder()
                    .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                    .build());
            observer.onNext(HealthCheckResponse.newBuilder()
                    .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                    .build());
            observer.onCompleted();
        }
    }
}
