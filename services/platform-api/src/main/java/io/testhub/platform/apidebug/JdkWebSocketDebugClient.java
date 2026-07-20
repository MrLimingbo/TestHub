package io.testhub.platform.apidebug;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class JdkWebSocketDebugClient implements WebSocketDebugClient {
    private static final int MAX_FRAGMENTED_TEXT_LENGTH = 64 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public Connection connect(URI url, List<ApiDebugTransport.Header> headers, List<String> subprotocols, Listener listener) throws Exception {
        WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        for (ApiDebugTransport.Header header : headers) builder.header(header.name(), header.value());
        if (subprotocols != null && !subprotocols.isEmpty()) {
            builder.subprotocols(subprotocols.getFirst(), subprotocols.subList(1, subprotocols.size()).toArray(String[]::new));
        }
        WebSocket socket = builder.buildAsync(url, new IncomingListener(listener)).get(10, TimeUnit.SECONDS);
        return new JdkConnection(socket);
    }

    private static final class JdkConnection implements Connection {
        private final WebSocket socket;

        private JdkConnection(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void sendText(String payload) throws Exception {
            socket.sendText(payload, true).get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close(int statusCode, String reason) throws Exception {
            socket.sendClose(statusCode, reason).get(5, TimeUnit.SECONDS);
        }

        @Override
        public String subprotocol() {
            return socket.getSubprotocol();
        }
    }

    private static final class IncomingListener implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder fragments = new StringBuilder();
        private boolean rejected;

        private IncomingListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (rejected) return CompletableFuture.completedFuture(null);
            if (fragments.length() + data.length() > MAX_FRAGMENTED_TEXT_LENGTH) {
                rejected = true;
                listener.onError();
                webSocket.sendClose(1009, "Message too large");
                return CompletableFuture.completedFuture(null);
            }
            fragments.append(data);
            if (last) {
                listener.onText(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClosed(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError();
        }
    }
}
