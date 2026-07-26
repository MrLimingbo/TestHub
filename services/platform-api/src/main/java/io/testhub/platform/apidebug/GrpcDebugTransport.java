package io.testhub.platform.apidebug;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import java.util.List;

/**
 * Executes a reflected gRPC Unary call. The application service owns permission,
 * scope, validation, history and audit concerns; transports never persist payloads.
 */
public interface GrpcDebugTransport {
    Response unary(Request request) throws TransportException;

    /**
     * Starts a server-streaming gRPC call. The caller owns the short-lived
     * session and may cancel it through the returned subscription.
     */
    StreamSubscription serverStreaming(Request request, StreamListener listener) throws TransportException;

    /**
     * Starts a client-streaming gRPC call. The caller serializes send and
     * complete operations through the returned short-lived subscription.
     */
    ClientStreamSubscription clientStreaming(Request request, ClientStreamListener listener) throws TransportException;

    /**
     * Starts a bidirectional-streaming gRPC call. The caller serializes request
     * sending and completion, while response delivery is bounded by the listener.
     */
    BidiStreamSubscription bidiStreaming(Request request, BidiStreamListener listener) throws TransportException;

    List<String> listServices(DiscoveryRequest request) throws TransportException;

    List<MethodSummary> listMethods(DiscoveryRequest request, String service) throws TransportException;

    record Request(String host, int port, boolean tls, String service, String method, String requestJson, int timeoutMs,
                   List<MetadataEntry> metadata, List<FileDescriptorProto> descriptorProtos) {
        public Request {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
            descriptorProtos = descriptorProtos == null ? List.of() : List.copyOf(descriptorProtos);
        }

        public Request(String host, int port, boolean tls, String service, String method, String requestJson, int timeoutMs,
                       List<MetadataEntry> metadata) {
            this(host, port, tls, service, method, requestJson, timeoutMs, metadata, List.of());
        }
    }

    /**
     * ASCII gRPC metadata validated by the application service before a call is opened.
     * Values are execution-only and must never be persisted by a transport.
     */
    record MetadataEntry(String key, String value) {
    }

    record DiscoveryRequest(String host, int port, boolean tls, int timeoutMs, List<MetadataEntry> metadata) {
        public DiscoveryRequest {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }

    record MethodSummary(String name, String callType) {
    }

    record Response(String responseJson) {
    }

    interface StreamSubscription {
        void cancel(String reason);
    }

    interface StreamListener {
        void onMessage(String responseJson);

        void onCompleted();

        void onError(String status);
    }

    interface ClientStreamSubscription extends StreamSubscription {
        void send(String requestJson) throws TransportException;

        void complete();
    }

    interface ClientStreamListener {
        void onResponse(String responseJson);

        void onCompleted();

        void onError(String status);
    }

    interface BidiStreamSubscription extends StreamSubscription {
        void send(String requestJson) throws TransportException;

        void complete();
    }

    interface BidiStreamListener {
        void onMessage(String responseJson);

        void onCompleted();

        void onError(String status);
    }

    final class TransportException extends Exception {
        private final String status;

        public TransportException(String status) {
            super(status);
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
