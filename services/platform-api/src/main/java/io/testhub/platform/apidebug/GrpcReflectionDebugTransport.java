package io.testhub.platform.apidebug;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Uses the standard gRPC reflection service to find a Unary method at runtime,
 * then serializes dynamic protobuf messages as JSON. No schema or payload is saved.
 */
@Component
public class GrpcReflectionDebugTransport implements GrpcDebugTransport {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    @Override
    public Response unary(Request request) throws TransportException {
        ManagedChannel channel = channel(request);
        try {
            Channel callChannel = metadataChannel(channel, request.metadata());
            Descriptors.MethodDescriptor method = request.descriptorProtos().isEmpty() ? reflectedMethod(callChannel, request) : offlineMethod(request);
            if (method.isClientStreaming() || method.isServerStreaming()) {
                throw new TransportException("UNARY_ONLY");
            }
            DynamicMessage requestMessage = requestMessage(method, request.requestJson());
            DynamicMessage response = invoke(callChannel, method, requestMessage, request.timeoutMs());
            return new Response(JsonFormat.printer().includingDefaultValueFields().print(response));
        } catch (TransportException exception) {
            throw exception;
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        } catch (Exception exception) {
            throw new TransportException("UNAVAILABLE");
        } finally {
            close(channel);
        }
    }

    @Override
    public StreamSubscription serverStreaming(Request request, StreamListener listener) throws TransportException {
        if (listener == null) throw new TransportException("UNAVAILABLE");
        ManagedChannel channel = channel(request);
        try {
            Channel callChannel = metadataChannel(channel, request.metadata());
            Descriptors.MethodDescriptor method = request.descriptorProtos().isEmpty() ? reflectedMethod(callChannel, request) : offlineMethod(request);
            if (!method.isServerStreaming() || method.isClientStreaming()) throw new TransportException("SERVER_STREAMING_ONLY");
            DynamicMessage requestMessage = requestMessage(method, request.requestJson());
            DynamicMessage responsePrototype = DynamicMessage.getDefaultInstance(method.getOutputType());
            MethodDescriptor<DynamicMessage, DynamicMessage> descriptor = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(method.getService().getFullName(), method.getName()))
                    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                    .setResponseMarshaller(ProtoUtils.marshaller(responsePrototype))
                    .build();
            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicReference<ClientCall<DynamicMessage, DynamicMessage>> active = new AtomicReference<>();
            ClientCall.Listener<DynamicMessage> callback = new ClientCall.Listener<>() {
                @Override
                public void onMessage(DynamicMessage value) {
                    if (terminal.get()) return;
                    try {
                        listener.onMessage(JsonFormat.printer().includingDefaultValueFields().print(value));
                        ClientCall<DynamicMessage, DynamicMessage> call = active.get();
                        if (!terminal.get() && call != null) call.request(1);
                    } catch (Exception exception) {
                        ClientCall<DynamicMessage, DynamicMessage> call = active.get();
                        if (call != null) call.cancel("TestHub stream listener failed", exception);
                    }
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                    if (!terminal.compareAndSet(false, true)) return;
                    try {
                        if (status.isOk()) listener.onCompleted();
                        else listener.onError(status.getCode().name());
                    } finally {
                        close(channel);
                    }
                }
            };
            ClientCall<DynamicMessage, DynamicMessage> call = callChannel.newCall(descriptor,
                    CallOptions.DEFAULT.withDeadlineAfter(request.timeoutMs(), TimeUnit.MILLISECONDS));
            active.set(call);
            call.start(callback, new Metadata());
            call.request(1);
            call.sendMessage(requestMessage);
            call.halfClose();
            return reason -> {
                if (terminal.compareAndSet(false, true)) {
                    call.cancel(reason == null || reason.isBlank() ? "Cancelled by TestHub" : reason, null);
                    close(channel);
                }
            };
        } catch (TransportException exception) {
            close(channel);
            throw exception;
        } catch (StatusRuntimeException exception) {
            close(channel);
            throw status(exception.getStatus());
        } catch (Exception exception) {
            close(channel);
            throw new TransportException("UNAVAILABLE");
        }
    }

    @Override
    public ClientStreamSubscription clientStreaming(Request request, ClientStreamListener listener) throws TransportException {
        if (listener == null) throw new TransportException("UNAVAILABLE");
        ManagedChannel channel = channel(request);
        try {
            Channel callChannel = metadataChannel(channel, request.metadata());
            Descriptors.MethodDescriptor method = request.descriptorProtos().isEmpty() ? reflectedMethod(callChannel, request) : offlineMethod(request);
            if (!method.isClientStreaming() || method.isServerStreaming()) throw new TransportException("CLIENT_STREAMING_ONLY");
            DynamicMessage responsePrototype = DynamicMessage.getDefaultInstance(method.getOutputType());
            MethodDescriptor<DynamicMessage, DynamicMessage> descriptor = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                    .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(method.getService().getFullName(), method.getName()))
                    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                    .setResponseMarshaller(ProtoUtils.marshaller(responsePrototype))
                    .build();
            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicBoolean halfClosed = new AtomicBoolean(false);
            ClientCall.Listener<DynamicMessage> callback = new ClientCall.Listener<>() {
                @Override
                public void onMessage(DynamicMessage value) {
                    if (terminal.get()) return;
                    try {
                        listener.onResponse(JsonFormat.printer().includingDefaultValueFields().print(value));
                    } catch (Exception exception) {
                        terminal.set(true);
                    }
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                    if (!terminal.compareAndSet(false, true)) return;
                    try {
                        if (status.isOk()) listener.onCompleted();
                        else listener.onError(status.getCode().name());
                    } finally {
                        close(channel);
                    }
                }
            };
            ClientCall<DynamicMessage, DynamicMessage> call = callChannel.newCall(descriptor,
                    CallOptions.DEFAULT.withDeadlineAfter(request.timeoutMs(), TimeUnit.MILLISECONDS));
            call.start(callback, new Metadata());
            call.request(1);
            return new ClientStreamSubscription() {
                private final Object monitor = new Object();

                @Override
                public void send(String requestJson) throws TransportException {
                    DynamicMessage message = requestMessage(method, requestJson);
                    synchronized (monitor) {
                        if (terminal.get() || halfClosed.get()) throw new TransportException("CLIENT_STREAM_CLOSED");
                        try {
                            call.sendMessage(message);
                        } catch (RuntimeException exception) {
                            throw new TransportException("UNAVAILABLE");
                        }
                    }
                }

                @Override
                public void complete() {
                    synchronized (monitor) {
                        if (terminal.get() || !halfClosed.compareAndSet(false, true)) return;
                        try {
                            call.halfClose();
                        } catch (RuntimeException exception) {
                            call.cancel("TestHub client stream completion failed", exception);
                        }
                    }
                }

                @Override
                public void cancel(String reason) {
                    synchronized (monitor) {
                        if (!terminal.compareAndSet(false, true)) return;
                        call.cancel(reason == null || reason.isBlank() ? "Cancelled by TestHub" : reason, null);
                        close(channel);
                    }
                }
            };
        } catch (TransportException exception) {
            close(channel);
            throw exception;
        } catch (StatusRuntimeException exception) {
            close(channel);
            throw status(exception.getStatus());
        } catch (Exception exception) {
            close(channel);
            throw new TransportException("UNAVAILABLE");
        }
    }

    @Override
    public BidiStreamSubscription bidiStreaming(Request request, BidiStreamListener listener) throws TransportException {
        if (listener == null) throw new TransportException("UNAVAILABLE");
        ManagedChannel channel = channel(request);
        try {
            Channel callChannel = metadataChannel(channel, request.metadata());
            Descriptors.MethodDescriptor method = request.descriptorProtos().isEmpty() ? reflectedMethod(callChannel, request) : offlineMethod(request);
            if (!method.isClientStreaming() || !method.isServerStreaming()) throw new TransportException("BIDI_STREAMING_ONLY");
            DynamicMessage responsePrototype = DynamicMessage.getDefaultInstance(method.getOutputType());
            MethodDescriptor<DynamicMessage, DynamicMessage> descriptor = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(method.getService().getFullName(), method.getName()))
                    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                    .setResponseMarshaller(ProtoUtils.marshaller(responsePrototype))
                    .build();
            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicBoolean halfClosed = new AtomicBoolean(false);
            AtomicReference<ClientCall<DynamicMessage, DynamicMessage>> active = new AtomicReference<>();
            ClientCall.Listener<DynamicMessage> callback = new ClientCall.Listener<>() {
                @Override
                public void onMessage(DynamicMessage value) {
                    if (terminal.get()) return;
                    try {
                        listener.onMessage(JsonFormat.printer().includingDefaultValueFields().print(value));
                        ClientCall<DynamicMessage, DynamicMessage> call = active.get();
                        if (!terminal.get() && call != null) call.request(1);
                    } catch (Exception exception) {
                        ClientCall<DynamicMessage, DynamicMessage> call = active.get();
                        if (call != null && terminal.compareAndSet(false, true)) {
                            call.cancel("TestHub bidirectional stream listener failed", exception);
                            close(channel);
                        }
                    }
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                    if (!terminal.compareAndSet(false, true)) return;
                    try {
                        if (status.isOk()) listener.onCompleted();
                        else listener.onError(status.getCode().name());
                    } finally {
                        close(channel);
                    }
                }
            };
            ClientCall<DynamicMessage, DynamicMessage> call = callChannel.newCall(descriptor,
                    CallOptions.DEFAULT.withDeadlineAfter(request.timeoutMs(), TimeUnit.MILLISECONDS));
            active.set(call);
            call.start(callback, new Metadata());
            call.request(1);
            return new BidiStreamSubscription() {
                private final Object monitor = new Object();

                @Override
                public void send(String requestJson) throws TransportException {
                    DynamicMessage message = requestMessage(method, requestJson);
                    synchronized (monitor) {
                        if (terminal.get() || halfClosed.get()) throw new TransportException("BIDI_STREAM_CLOSED");
                        try {
                            call.sendMessage(message);
                        } catch (RuntimeException exception) {
                            throw new TransportException("UNAVAILABLE");
                        }
                    }
                }

                @Override
                public void complete() {
                    synchronized (monitor) {
                        if (terminal.get() || !halfClosed.compareAndSet(false, true)) return;
                        try {
                            call.halfClose();
                        } catch (RuntimeException exception) {
                            call.cancel("TestHub bidirectional stream completion failed", exception);
                        }
                    }
                }

                @Override
                public void cancel(String reason) {
                    synchronized (monitor) {
                        if (!terminal.compareAndSet(false, true)) return;
                        call.cancel(reason == null || reason.isBlank() ? "Cancelled by TestHub" : reason, null);
                        close(channel);
                    }
                }
            };
        } catch (TransportException exception) {
            close(channel);
            throw exception;
        } catch (StatusRuntimeException exception) {
            close(channel);
            throw status(exception.getStatus());
        } catch (Exception exception) {
            close(channel);
            throw new TransportException("UNAVAILABLE");
        }
    }

    @Override
    public List<String> listServices(DiscoveryRequest request) throws TransportException {
        ManagedChannel channel = channel(request);
        try {
            return requestServiceNames(metadataChannel(channel, request.metadata()), request.timeoutMs());
        } catch (TransportException exception) {
            throw exception;
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        } catch (Exception exception) {
            throw new TransportException("UNAVAILABLE");
        } finally {
            close(channel);
        }
    }

    @Override
    public List<MethodSummary> listMethods(DiscoveryRequest request, String service) throws TransportException {
        ManagedChannel channel = channel(request);
        try {
            Descriptors.ServiceDescriptor descriptor = reflectedService(metadataChannel(channel, request.metadata()), service, request.timeoutMs());
            return descriptor.getMethods().stream()
                    .map(method -> new MethodSummary(method.getName(), callType(method)))
                    .sorted(java.util.Comparator.comparing(MethodSummary::name))
                    .toList();
        } catch (TransportException exception) {
            throw exception;
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        } catch (Exception exception) {
            throw new TransportException("UNAVAILABLE");
        } finally {
            close(channel);
        }
    }

    private ManagedChannel channel(Request request) {
        return channel(request.host(), request.port(), request.tls());
    }

    private ManagedChannel channel(DiscoveryRequest request) {
        return channel(request.host(), request.port(), request.tls());
    }

    private ManagedChannel channel(String host, int port, boolean tls) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port)
                .maxInboundMessageSize(MAX_RESPONSE_BYTES);
        if (tls) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }
        return builder.build();
    }

    private Channel metadataChannel(ManagedChannel channel, List<MetadataEntry> entries) {
        Metadata metadata = new Metadata();
        for (MetadataEntry entry : entries) {
            metadata.put(Metadata.Key.of(entry.key(), Metadata.ASCII_STRING_MARSHALLER), entry.value());
        }
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private Descriptors.MethodDescriptor reflectedMethod(Channel channel, Request request) throws TransportException {
        Descriptors.ServiceDescriptor service = reflectedService(channel, request.service(), request.timeoutMs());
        Descriptors.MethodDescriptor method = service.findMethodByName(request.method());
        if (method == null) throw new TransportException("UNIMPLEMENTED");
        return method;
    }

    private Descriptors.MethodDescriptor offlineMethod(Request request) throws TransportException {
        try {
            Descriptors.ServiceDescriptor service = GrpcDescriptorRuntime.service(request.descriptorProtos(), request.service());
            Descriptors.MethodDescriptor method = service.findMethodByName(request.method());
            if (method == null) throw new TransportException("UNIMPLEMENTED");
            return method;
        } catch (GrpcDescriptorRuntime.InvalidDescriptorException exception) {
            throw new TransportException("UNIMPLEMENTED");
        }
    }

    private Descriptors.ServiceDescriptor reflectedService(Channel channel, String serviceName, int timeoutMs) throws TransportException {
        List<FileDescriptorProto> descriptorProtos = requestDescriptors(channel, serviceName, timeoutMs);
        try {
            return GrpcDescriptorRuntime.service(descriptorProtos, serviceName);
        } catch (GrpcDescriptorRuntime.InvalidDescriptorException exception) {
            throw new TransportException("UNIMPLEMENTED");
        }
    }

    private String callType(Descriptors.MethodDescriptor method) {
        if (method.isClientStreaming() && method.isServerStreaming()) return "BIDI_STREAMING";
        if (method.isClientStreaming()) return "CLIENT_STREAMING";
        if (method.isServerStreaming()) return "SERVER_STREAMING";
        return "UNARY";
    }

    private List<String> requestServiceNames(Channel channel, int timeoutMs) throws TransportException {
        try {
            return requestServiceNamesV1(channel, timeoutMs);
        } catch (TransportException exception) {
            if (!"UNIMPLEMENTED".equals(exception.status())) throw exception;
            return requestServiceNamesV1Alpha(channel, timeoutMs);
        }
    }

    private List<String> requestServiceNamesV1(Channel channel, int timeoutMs) throws TransportException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ServerReflectionResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamObserver<ServerReflectionResponse> observer = new StreamObserver<>() {
            @Override
            public void onNext(ServerReflectionResponse value) {
                response.compareAndSet(null, value);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                completed.countDown();
            }

            @Override
            public void onCompleted() {
                completed.countDown();
            }
        };
        try {
            StreamObserver<ServerReflectionRequest> requests = ServerReflectionGrpc.newStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).serverReflectionInfo(observer);
            requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
            requests.onCompleted();
            if (!completed.await(timeoutMs + 250L, TimeUnit.MILLISECONDS)) throw new TransportException("DEADLINE_EXCEEDED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportException("CANCELLED");
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        }
        if (failure.get() != null) throw mapped(failure.get());
        ServerReflectionResponse value = response.get();
        if (value == null) throw new TransportException("UNIMPLEMENTED");
        if (value.hasErrorResponse()) throw status(Status.fromCodeValue(value.getErrorResponse().getErrorCode()));
        if (!value.hasListServicesResponse()) throw new TransportException("UNIMPLEMENTED");
        return value.getListServicesResponse().getServiceList().stream().map(item -> item.getName()).distinct().sorted().toList();
    }

    private List<String> requestServiceNamesV1Alpha(Channel channel, int timeoutMs) throws TransportException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<io.grpc.reflection.v1alpha.ServerReflectionResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse> observer = new StreamObserver<>() {
            @Override
            public void onNext(io.grpc.reflection.v1alpha.ServerReflectionResponse value) {
                response.compareAndSet(null, value);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                completed.countDown();
            }

            @Override
            public void onCompleted() {
                completed.countDown();
            }
        };
        try {
            StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionRequest> requests = io.grpc.reflection.v1alpha.ServerReflectionGrpc.newStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).serverReflectionInfo(observer);
            requests.onNext(io.grpc.reflection.v1alpha.ServerReflectionRequest.newBuilder().setListServices("").build());
            requests.onCompleted();
            if (!completed.await(timeoutMs + 250L, TimeUnit.MILLISECONDS)) throw new TransportException("DEADLINE_EXCEEDED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportException("CANCELLED");
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        }
        if (failure.get() != null) throw mapped(failure.get());
        io.grpc.reflection.v1alpha.ServerReflectionResponse value = response.get();
        if (value == null) throw new TransportException("UNIMPLEMENTED");
        if (value.hasErrorResponse()) throw status(Status.fromCodeValue(value.getErrorResponse().getErrorCode()));
        if (!value.hasListServicesResponse()) throw new TransportException("UNIMPLEMENTED");
        return value.getListServicesResponse().getServiceList().stream().map(item -> item.getName()).distinct().sorted().toList();
    }

    private List<FileDescriptorProto> requestDescriptors(Channel channel, String service, int timeoutMs) throws TransportException {
        try {
            return requestDescriptorsV1(channel, service, timeoutMs);
        } catch (TransportException exception) {
            if (!"UNIMPLEMENTED".equals(exception.status())) throw exception;
            return requestDescriptorsV1Alpha(channel, service, timeoutMs);
        }
    }

    private List<FileDescriptorProto> requestDescriptorsV1(Channel channel, String service, int timeoutMs) throws TransportException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ServerReflectionResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamObserver<ServerReflectionResponse> observer = new StreamObserver<>() {
            @Override
            public void onNext(ServerReflectionResponse value) {
                response.compareAndSet(null, value);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                completed.countDown();
            }

            @Override
            public void onCompleted() {
                completed.countDown();
            }
        };
        try {
            StreamObserver<ServerReflectionRequest> requests = ServerReflectionGrpc.newStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).serverReflectionInfo(observer);
            requests.onNext(ServerReflectionRequest.newBuilder().setFileContainingSymbol(service).build());
            requests.onCompleted();
            if (!completed.await(timeoutMs + 250L, TimeUnit.MILLISECONDS)) throw new TransportException("DEADLINE_EXCEEDED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportException("CANCELLED");
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        }
        if (failure.get() != null) throw mapped(failure.get());
        ServerReflectionResponse value = response.get();
        if (value == null) throw new TransportException("UNIMPLEMENTED");
        if (value.hasErrorResponse()) {
            throw status(Status.fromCodeValue(value.getErrorResponse().getErrorCode()));
        }
        if (!value.hasFileDescriptorResponse() || value.getFileDescriptorResponse().getFileDescriptorProtoCount() == 0) {
            throw new TransportException("UNIMPLEMENTED");
        }
        try {
            List<FileDescriptorProto> result = new ArrayList<>();
            for (ByteString bytes : value.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                result.add(FileDescriptorProto.parseFrom(bytes));
            }
            return result;
        } catch (InvalidProtocolBufferException exception) {
            throw new TransportException("UNIMPLEMENTED");
        }
    }

    private List<FileDescriptorProto> requestDescriptorsV1Alpha(Channel channel, String service, int timeoutMs) throws TransportException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<io.grpc.reflection.v1alpha.ServerReflectionResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse> observer = new StreamObserver<>() {
            @Override
            public void onNext(io.grpc.reflection.v1alpha.ServerReflectionResponse value) {
                response.compareAndSet(null, value);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                completed.countDown();
            }

            @Override
            public void onCompleted() {
                completed.countDown();
            }
        };
        try {
            StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionRequest> requests = io.grpc.reflection.v1alpha.ServerReflectionGrpc.newStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).serverReflectionInfo(observer);
            requests.onNext(io.grpc.reflection.v1alpha.ServerReflectionRequest.newBuilder().setFileContainingSymbol(service).build());
            requests.onCompleted();
            if (!completed.await(timeoutMs + 250L, TimeUnit.MILLISECONDS)) throw new TransportException("DEADLINE_EXCEEDED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportException("CANCELLED");
        } catch (StatusRuntimeException exception) {
            throw status(exception.getStatus());
        }
        if (failure.get() != null) throw mapped(failure.get());
        io.grpc.reflection.v1alpha.ServerReflectionResponse value = response.get();
        if (value == null) throw new TransportException("UNIMPLEMENTED");
        if (value.hasErrorResponse()) {
            throw status(Status.fromCodeValue(value.getErrorResponse().getErrorCode()));
        }
        if (!value.hasFileDescriptorResponse() || value.getFileDescriptorResponse().getFileDescriptorProtoCount() == 0) {
            throw new TransportException("UNIMPLEMENTED");
        }
        try {
            List<FileDescriptorProto> result = new ArrayList<>();
            for (ByteString bytes : value.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                result.add(FileDescriptorProto.parseFrom(bytes));
            }
            return result;
        } catch (InvalidProtocolBufferException exception) {
            throw new TransportException("UNIMPLEMENTED");
        }
    }

    private Descriptors.FileDescriptor buildFile(String name, Map<String, FileDescriptorProto> sources,
                                                 Map<String, Descriptors.FileDescriptor> built, Set<String> building) throws TransportException {
        Descriptors.FileDescriptor existing = built.get(name);
        if (existing != null) return existing;
        FileDescriptorProto source = sources.get(name);
        if (source == null || !building.add(name)) throw new TransportException("UNIMPLEMENTED");
        try {
            List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
            for (String dependency : source.getDependencyList()) {
                dependencies.add(buildFile(dependency, sources, built, building));
            }
            Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(source,
                    dependencies.toArray(Descriptors.FileDescriptor[]::new));
            built.put(name, descriptor);
            return descriptor;
        } catch (Descriptors.DescriptorValidationException exception) {
            throw new TransportException("UNIMPLEMENTED");
        } finally {
            building.remove(name);
        }
    }

    private DynamicMessage requestMessage(Descriptors.MethodDescriptor method, String requestJson) throws TransportException {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(method.getInputType());
        try {
            JsonFormat.parser().merge(requestJson, builder);
            return builder.build();
        } catch (InvalidProtocolBufferException exception) {
            throw new TransportException("INVALID_ARGUMENT");
        }
    }

    private DynamicMessage invoke(Channel channel, Descriptors.MethodDescriptor method, DynamicMessage request, int timeoutMs) {
        DynamicMessage responsePrototype = DynamicMessage.getDefaultInstance(method.getOutputType());
        MethodDescriptor<DynamicMessage, DynamicMessage> descriptor = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(method.getService().getFullName(), method.getName()))
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(method.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(responsePrototype))
                .build();
        return ClientCalls.blockingUnaryCall(channel, descriptor,
                CallOptions.DEFAULT.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS), request);
    }

    private TransportException mapped(Throwable failure) {
        if (failure instanceof StatusRuntimeException exception) return status(exception.getStatus());
        if (failure instanceof StatusException exception) return status(exception.getStatus());
        return new TransportException("UNAVAILABLE");
    }

    private void close(ManagedChannel channel) {
        channel.shutdownNow();
        try {
            channel.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private TransportException status(Status status) {
        return new TransportException(status.getCode().name());
    }
}
